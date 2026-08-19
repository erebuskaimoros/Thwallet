package io.horizontalsystems.walletkit.chain.xrp.submission

import io.horizontalsystems.walletkit.chain.xrp.rpc.XrpLedgerGapException
import io.horizontalsystems.walletkit.chain.xrp.rpc.XrpNetworkMismatchException
import io.horizontalsystems.walletkit.chain.xrp.rpc.XrpProtocolException
import io.horizontalsystems.walletkit.chain.xrp.rpc.XrpRpc
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class XrpReliableSubmitter(
    private val rpc: XrpRpc,
    private val store: XrpPendingTransactionStore
) {
    private val accountLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun submit(signed: XrpSignedTransaction): XrpPendingTransaction =
        lock(signed.sourceAddress).withLock {
            val existing = store.get(signed.sourceAddress)
            if (existing != null) {
                if (existing.hash.equals(signed.hash, ignoreCase = true) && existing.blob == signed.blob) {
                    return@withLock existing
                }
                throw XrpPendingTransactionExistsException(existing.hash)
            }

            val persisted = XrpPendingTransaction.from(signed)
            store.save(persisted)
            submitPersisted(persisted)
        }

    suspend fun resume(sourceAddress: String): XrpPendingTransaction? = lock(sourceAddress).withLock {
        val reconciled = reconcileLocked(sourceAddress) ?: return@withLock null
        if (reconciled.state.final || reconciled.state == XrpPendingState.BLOCKED_SEQUENCE_CONFLICT) {
            return@withLock reconciled
        }
        submitPersisted(reconciled)
    }

    suspend fun reconcile(sourceAddress: String): XrpPendingTransaction? = lock(sourceAddress).withLock {
        reconcileLocked(sourceAddress)
    }

    fun pending(sourceAddress: String): XrpPendingTransaction? = store.get(sourceAddress)

    /** A caller should clear only after it has presented or archived the terminal result. */
    fun clearTerminal(sourceAddress: String) {
        val pending = store.get(sourceAddress) ?: return
        if (!pending.state.final) throw XrpPendingNotFinalException(pending.state)
        store.remove(sourceAddress)
    }

    private suspend fun submitPersisted(pending: XrpPendingTransaction): XrpPendingTransaction {
        // The exact persisted blob is the only value ever sent or retried.
        val result = rpc.submit(pending.blob)
        val submitted = pending.copy(
            state = XrpPendingState.SUBMITTED,
            provisionalResult = result.engineResult
        )
        store.save(submitted)
        return submitted
    }

    private suspend fun reconcileLocked(sourceAddress: String): XrpPendingTransaction? {
        val pending = store.get(sourceAddress) ?: return null
        if (pending.state.final) return pending

        val server = rpc.serverState()
        if (server.networkId != MAINNET_NETWORK_ID) throw XrpNetworkMismatchException(server.networkId)
        if (!server.completeLedgers.covers(server.validatedLedgerIndex, server.validatedLedgerIndex)) {
            throw XrpLedgerGapException("Current validated ledger is not in complete_ledgers")
        }
        if (server.validatedLedgerIndex < pending.preparedLedgerIndex) {
            throw XrpLedgerGapException("RPC validated ledger is older than pending transaction snapshot")
        }
        val lookup = rpc.transaction(pending.hash)
        if (lookup != null) {
            if (!lookup.hash.equals(pending.hash, ignoreCase = true)) {
                throw XrpProtocolException("tx lookup hash does not match persisted transaction")
            }
            if (!lookup.validated && server.validatedLedgerIndex <= pending.lastLedgerSequence) {
                return persistIfChanged(pending, pending.copy(state = XrpPendingState.SUBMITTED))
            }
            if (lookup.validated) {
                val ledgerIndex = lookup.ledgerIndex
                    ?: throw XrpProtocolException("Validated tx response is missing ledger index")
                val resultCode = lookup.resultCode
                    ?: throw XrpProtocolException("Validated tx response is missing metadata result")
                if (ledgerIndex !in pending.preparedLedgerIndex..pending.lastLedgerSequence) {
                    throw XrpProtocolException("Validated transaction is outside its signed ledger window")
                }
                if (!server.completeLedgers.covers(ledgerIndex, ledgerIndex)) {
                    throw XrpLedgerGapException("Validated transaction ledger $ledgerIndex is not complete")
                }
                val finalState = if (resultCode == TES_SUCCESS) {
                    XrpPendingState.VALIDATED_SUCCESS
                } else {
                    XrpPendingState.VALIDATED_FAILURE
                }
                return persistIfChanged(
                    pending,
                    pending.copy(
                        state = finalState,
                        validatedLedgerIndex = ledgerIndex,
                        validatedResult = resultCode
                    )
                )
            }
        }

        val validatedLedger = server.validatedLedgerIndex
        if (validatedLedger > pending.lastLedgerSequence) {
            requireCompleteSubmissionWindow(pending, server.completeLedgers, validatedLedger)
            return persistIfChanged(pending, pending.copy(state = XrpPendingState.EXPIRED))
        }

        val account = rpc.accountState(sourceAddress, validatedLedger)
        if (account == null) {
            // A source account that existed when this sequence was prepared can only disappear
            // after validated conflicting lifecycle activity (for example AccountDelete). Keep
            // the signed row durable and block automatic retries until the user acknowledges it.
            requireCompleteSubmissionWindow(pending, server.completeLedgers, validatedLedger)
            return persistIfChanged(
                pending,
                pending.copy(state = XrpPendingState.BLOCKED_SEQUENCE_CONFLICT),
            )
        }
        if (account.ledgerIndex != validatedLedger) {
            throw XrpProtocolException("Pending account state is not pinned to validated ledger")
        }
        if (account.sequence != pending.sequence) {
            requireCompleteSubmissionWindow(pending, server.completeLedgers, validatedLedger)
            return persistIfChanged(
                pending,
                pending.copy(state = XrpPendingState.BLOCKED_SEQUENCE_CONFLICT)
            )
        }
        return pending
    }

    private fun requireCompleteSubmissionWindow(
        pending: XrpPendingTransaction,
        completeLedgers: io.horizontalsystems.walletkit.chain.xrp.rpc.XrpLedgerRanges,
        validatedLedger: Long
    ) {
        if (!completeLedgers.covers(pending.preparedLedgerIndex, validatedLedger)) {
            throw XrpLedgerGapException(
                "Complete ledger history does not cover pending window " +
                    "${pending.preparedLedgerIndex}-$validatedLedger"
            )
        }
    }

    private fun persistIfChanged(
        old: XrpPendingTransaction,
        updated: XrpPendingTransaction
    ): XrpPendingTransaction {
        if (old != updated) store.save(updated)
        return updated
    }

    private fun lock(address: String) = accountLocks.computeIfAbsent(address) { Mutex() }

    private companion object {
        const val MAINNET_NETWORK_ID = 0
        const val TES_SUCCESS = "tesSUCCESS"
    }
}

class XrpPendingTransactionExistsException(val transactionHash: String) :
    IllegalStateException("Another XRP transaction is still pending: $transactionHash")

class XrpPendingNotFinalException(val pendingState: XrpPendingState) :
    IllegalStateException("Cannot clear XRP transaction in state $pendingState")
