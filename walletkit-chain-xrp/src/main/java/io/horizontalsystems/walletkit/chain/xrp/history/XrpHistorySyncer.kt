package io.horizontalsystems.walletkit.chain.xrp.history

import io.horizontalsystems.walletkit.chain.xrp.rpc.XrpLedgerGapException
import io.horizontalsystems.walletkit.chain.xrp.rpc.XrpNativeTransaction
import io.horizontalsystems.walletkit.chain.xrp.rpc.XrpProtocolException
import io.horizontalsystems.walletkit.chain.xrp.rpc.XrpRpc

interface XrpHistoryStore {
    suspend fun checkpoint(address: String): Long?

    /** Starts a crash-safe staging session; staged rows must remain invisible to readers. */
    suspend fun beginSync(address: String, targetCheckpoint: Long)

    /** Stages one bounded RPC page and returns the number of newly staged hashes. */
    suspend fun stagePage(
        address: String,
        targetCheckpoint: Long,
        transactions: List<XrpNativeTransaction>,
    ): Int

    /** Atomically merges staged rows and advances the checkpoint. */
    suspend fun commitSync(address: String, targetCheckpoint: Long)

    /** Removes a failed or abandoned staging session without moving the checkpoint. */
    suspend fun rollbackSync(address: String, targetCheckpoint: Long)

    suspend fun commit(
        address: String,
        transactions: List<XrpNativeTransaction>,
        checkpoint: Long,
    ) {
        beginSync(address, checkpoint)
        try {
            stagePage(address, checkpoint, transactions)
            commitSync(address, checkpoint)
        } catch (error: Throwable) {
            runCatching { rollbackSync(address, checkpoint) }
            throw error
        }
    }
}

interface XrpQueryableHistoryStore : XrpHistoryStore {
    fun transactions(
        address: String,
        beforeLedger: Long? = null,
        beforeHash: String? = null,
        limit: Int,
    ): List<XrpNativeTransaction>

    fun clear(address: String)
}

data class XrpHistorySyncResult(
    val checkpoint: Long,
    val transactionCount: Int
)

class XrpHistorySyncer(
    private val rpc: XrpRpc,
    private val store: XrpHistoryStore,
    private val maxPagesPerSync: Int = 10_000
) {
    init {
        require(maxPagesPerSync > 0) { "maxPagesPerSync must be positive" }
    }

    suspend fun sync(address: String): XrpHistorySyncResult {
        val server = rpc.serverState()
        if (server.networkId != MAINNET_NETWORK_ID) {
            throw XrpProtocolException("History RPC is not XRP Ledger mainnet")
        }
        val validatedLedger = server.validatedLedgerIndex
        server.completeLedgers.rangeContaining(validatedLedger)
            ?: throw XrpLedgerGapException("Validated ledger $validatedLedger is not complete")
        val previousCheckpoint = store.checkpoint(address)
        if (previousCheckpoint != null && previousCheckpoint > validatedLedger) {
            throw XrpLedgerGapException(
                "Stored checkpoint $previousCheckpoint is ahead of validated ledger $validatedLedger"
            )
        }
        if (previousCheckpoint == validatedLedger) {
            return XrpHistorySyncResult(validatedLedger, 0)
        }
        val firstLedger = previousCheckpoint?.let(Math::incrementExact) ?: MAINNET_FIRST_LEDGER
        if (!server.completeLedgers.covers(firstLedger, validatedLedger)) {
            throw XrpLedgerGapException(
                "Complete ledger history does not cover $firstLedger-$validatedLedger"
            )
        }

        val transactionCount = syncRange(address, firstLedger, validatedLedger)
        return XrpHistorySyncResult(validatedLedger, transactionCount)
    }

    private suspend fun syncRange(address: String, firstLedger: Long, lastLedger: Long): Int = try {
        stageRange(address, firstLedger, lastLedger)
    } catch (_: XrpHistoryWindowTooLargeException) {
        if (firstLedger == lastLedger) {
            throw XrpProtocolException(
                "account_tx exceeded $maxPagesPerSync pages for ledger $firstLedger"
            )
        }
        val middle = firstLedger + (lastLedger - firstLedger) / 2
        Math.addExact(
            syncRange(address, firstLedger, middle),
            syncRange(address, Math.incrementExact(middle), lastLedger),
        )
    }

    private suspend fun stageRange(address: String, firstLedger: Long, lastLedger: Long): Int {
        val seenMarkers = mutableSetOf<String>()
        var marker: io.horizontalsystems.walletkit.chain.xrp.rpc.XrpPageMarker? = null
        var pageCount = 0
        var transactionCount = 0
        store.beginSync(address, lastLedger)
        try {
            do {
                if (++pageCount > maxPagesPerSync) {
                    throw XrpHistoryWindowTooLargeException()
                }
                val page = rpc.accountTransactions(address, firstLedger, lastLedger, marker)
                if (page.ledgerMin != firstLedger || page.ledgerMax != lastLedger) {
                    throw XrpLedgerGapException(
                        "account_tx page changed pinned range to ${page.ledgerMin}-${page.ledgerMax}"
                    )
                }
                page.transactions.forEach { transaction ->
                    if (transaction.ledgerIndex !in firstLedger..lastLedger) {
                        throw XrpProtocolException(
                            "account_tx returned ledger ${transaction.ledgerIndex} outside pinned range"
                        )
                    }
                }
                transactionCount += store.stagePage(address, lastLedger, page.transactions)
                marker = page.marker
                marker?.let {
                    if (!seenMarkers.add(it.encoded)) {
                        throw XrpProtocolException("account_tx repeated a pagination marker")
                    }
                }
            } while (marker != null)
            store.commitSync(address, lastLedger)
        } catch (error: Throwable) {
            runCatching { store.rollbackSync(address, lastLedger) }
            throw error
        }
        return transactionCount
    }

    private companion object {
        const val MAINNET_NETWORK_ID = 0
        const val MAINNET_FIRST_LEDGER = 32_570L
    }
}

private class XrpHistoryWindowTooLargeException : RuntimeException()
