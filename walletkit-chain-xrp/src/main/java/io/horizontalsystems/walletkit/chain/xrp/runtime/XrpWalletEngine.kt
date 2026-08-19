package io.horizontalsystems.walletkit.chain.xrp.runtime

import io.horizontalsystems.walletkit.chain.xrp.XrpAddressCodec
import io.horizontalsystems.walletkit.chain.xrp.XrpPaymentInput
import io.horizontalsystems.walletkit.chain.xrp.XrpPaymentSigner
import io.horizontalsystems.walletkit.chain.xrp.XrpSignedPayment
import io.horizontalsystems.walletkit.chain.xrp.history.XrpHistoryStore
import io.horizontalsystems.walletkit.chain.xrp.history.XrpHistorySyncer
import io.horizontalsystems.walletkit.chain.xrp.history.XrpQueryableHistoryStore
import io.horizontalsystems.walletkit.chain.xrp.rpc.XrpFeePolicy
import io.horizontalsystems.walletkit.chain.xrp.rpc.XrpFeeApprovalExceededException
import io.horizontalsystems.walletkit.chain.xrp.rpc.XrpInactiveAccountException
import io.horizontalsystems.walletkit.chain.xrp.rpc.XrpLedgerReader
import io.horizontalsystems.walletkit.chain.xrp.rpc.XrpRpc
import io.horizontalsystems.walletkit.chain.xrp.submission.XrpPendingTransaction
import io.horizontalsystems.walletkit.chain.xrp.submission.XrpPendingTransactionExistsException
import io.horizontalsystems.walletkit.chain.xrp.submission.XrpPendingTransactionStore
import io.horizontalsystems.walletkit.chain.xrp.submission.XrpPendingState
import io.horizontalsystems.walletkit.chain.xrp.submission.XrpReliableSubmitter
import io.horizontalsystems.walletkit.chain.xrp.submission.XrpSignedTransaction
import io.horizontalsystems.walletkit.chain.xrp.rpc.XrpNativeTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import kotlin.math.max

data class XrpSendRequest(
    val destination: String,
    val destinationTag: UInt?,
    val amountDrops: Long,
    val memo: String?,
    val recommendedFeeDrops: Long? = null,
    val minimumSendAmountDrops: Long? = null,
    val maximumFeeDrops: Long? = null,
    val validUntilEpochSeconds: Long? = null,
)

data class XrpBalance(
    val balanceDrops: Long,
    val reserveDrops: Long,
    val spendableDrops: Long,
    val feeDrops: Long,
    val ledgerIndex: Long,
)

data class XrpPreflightInfo(
    val normalizedAddress: String,
    val destinationTag: UInt?,
    val feeDrops: Long,
    val minimumAmountDrops: Long,
    val spendableDrops: Long,
    val ledgerIndex: Long,
)

sealed class XrpKitSyncState {
    object Idle : XrpKitSyncState()
    object Syncing : XrpKitSyncState()
    object Synced : XrpKitSyncState()
    data class Failed(val error: Throwable) : XrpKitSyncState()
}

interface XrpWalletKit {
    val address: String
    val canSign: Boolean
    val balance: StateFlow<XrpBalance?>
    val syncState: StateFlow<XrpKitSyncState>
    val transactions: StateFlow<List<XrpNativeTransaction>>
    val lastLedgerIndex: StateFlow<Long?>
    val lastSubmission: StateFlow<XrpPendingTransaction?>
    val pendingTransaction: XrpPendingTransaction?

    fun start()
    fun stop()
    fun refresh()
    suspend fun refreshNow(syncHistory: Boolean = true)
    fun transactionPage(beforeLedger: Long?, beforeHash: String?, limit: Int): List<XrpNativeTransaction>
    suspend fun acknowledgeTerminalSubmission()
    suspend fun preflight(request: XrpSendRequest): XrpPreflightInfo
    suspend fun prepare(request: XrpSendRequest): XrpSignedPayment
    suspend fun send(request: XrpSendRequest): XrpPendingTransaction
}

class XrpWalletEngine(
    override val address: String,
    privateKey: ByteArray?,
    private val rpc: XrpRpc,
    historyStore: XrpHistoryStore,
    pendingStore: XrpPendingTransactionStore,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000 },
    maxFeeDrops: Long = DEFAULT_MAX_FEE_DROPS,
    private val lastLedgerOffset: Long = DEFAULT_LAST_LEDGER_OFFSET,
    coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pendingPollIntervalMillis: Long = DEFAULT_PENDING_POLL_INTERVAL_MILLIS,
    private val idleRefreshIntervalMillis: Long = DEFAULT_IDLE_REFRESH_INTERVAL_MILLIS,
) : XrpWalletKit {
    private val privateKey = privateKey?.copyOf()
    private val scope = CoroutineScope(SupervisorJob() + coroutineDispatcher)
    private val refreshMutex = Mutex()
    private val paymentMutex = Mutex()
    private val lifecycleLock = Any()
    private val refreshSignal = Channel<Unit>(Channel.CONFLATED)
    private val reader = XrpLedgerReader(rpc, XrpFeePolicy(maxFeeDrops))
    private val historySyncer = XrpHistorySyncer(rpc, historyStore)
    private val queryableHistoryStore = historyStore as? XrpQueryableHistoryStore
    private val closeableHistoryStore = historyStore as? Closeable
    private val submitter = XrpReliableSubmitter(rpc, pendingStore)
    private var lifecycleJob: Job? = null
    private var refreshJob: Job? = null

    private val _balance = MutableStateFlow<XrpBalance?>(null)
    override val balance = _balance.asStateFlow()
    private val _syncState = MutableStateFlow<XrpKitSyncState>(XrpKitSyncState.Idle)
    override val syncState = _syncState.asStateFlow()
    private val _transactions = MutableStateFlow<List<XrpNativeTransaction>>(emptyList())
    override val transactions = _transactions.asStateFlow()
    private val _lastLedgerIndex = MutableStateFlow<Long?>(null)
    override val lastLedgerIndex = _lastLedgerIndex.asStateFlow()
    private val _lastSubmission = MutableStateFlow<XrpPendingTransaction?>(null)
    override val lastSubmission = _lastSubmission.asStateFlow()

    @Volatile
    var lastPreparedPayment: XrpSignedPayment? = null
        private set

    override val canSign: Boolean get() = privateKey != null
    override val pendingTransaction: XrpPendingTransaction? get() = submitter.pending(address)

    init {
        XrpAddressCodec.parse(address).also {
            require(it.destinationTag == null) { "An XRP wallet account must use a classic address" }
        }
        privateKey?.let {
            require(XrpAddressCodec.classicAddress(it) == address) { "XRP private key does not match account" }
        }
        require(lastLedgerOffset == XrpSignedTransaction.SIGNED_LEDGER_WINDOW) {
            "LastLedgerSequence offset must match the durable signed-transaction policy"
        }
        require(pendingPollIntervalMillis > 0) { "Pending poll interval must be positive" }
        require(idleRefreshIntervalMillis > 0) { "Idle refresh interval must be positive" }
    }

    override fun start() {
        synchronized(lifecycleLock) {
            if (lifecycleJob?.isActive == true) return
            lifecycleJob = scope.launch {
                while (true) {
                    runCatching { refreshNow() }
                    val pending = submitter.pending(address)
                    val interval = if (pending != null && !pending.state.final) {
                        pendingPollIntervalMillis
                    } else {
                        idleRefreshIntervalMillis
                    }
                    withTimeoutOrNull(interval) { refreshSignal.receive() }
                }
            }
        }
    }

    override fun stop() {
        synchronized(lifecycleLock) {
            lifecycleJob?.cancel()
            lifecycleJob = null
            refreshJob?.cancel()
            refreshJob = null
        }
        if (_syncState.value == XrpKitSyncState.Syncing) _syncState.value = XrpKitSyncState.Idle
    }

    internal fun dispose() {
        stop()
        privateKey?.fill(0)
        scope.cancel()
        closeableHistoryStore?.close()
    }

    override fun refresh() {
        synchronized(lifecycleLock) {
            if (lifecycleJob?.isActive == true) {
                refreshSignal.trySend(Unit)
                return
            }
            if (refreshJob?.isActive == true) return
            refreshJob = scope.launch {
                runCatching { refreshNow() }
            }
        }
    }

    override suspend fun refreshNow(syncHistory: Boolean) = refreshMutex.withLock {
        _syncState.value = XrpKitSyncState.Syncing
        try {
            var reconciledSubmission: XrpPendingTransaction? = null
            submitter.pending(address)?.let { pending ->
                reconciledSubmission = if (pending.state == XrpPendingState.PERSISTED) {
                    submitter.resume(address)
                } else {
                    submitter.reconcile(address)
                }
            }
            // Terminal submission state is independently durable and must remain visible even
            // when later balance or history RPC work fails. Validated rows are only auto-cleared
            // below after a successful history sync proves that the transaction was archived.
            reconciledSubmission?.takeIf { it.state.final }?.let {
                _lastSubmission.value = it
            }
            try {
                val snapshot = reader.snapshot(address)
                val reserve = Math.addExact(
                    snapshot.reserveBaseDrops,
                    Math.multiplyExact(snapshot.reserveIncrementDrops, snapshot.ownerCount),
                )
                _balance.value = XrpBalance(
                    snapshot.balanceDrops,
                    reserve,
                    snapshot.spendableDrops,
                    snapshot.feeDrops,
                    snapshot.ledgerIndex,
                )
                _lastLedgerIndex.value = snapshot.ledgerIndex
            } catch (_: XrpInactiveAccountException) {
                val server = rpc.serverState()
                if (server.networkId != 0) {
                    throw io.horizontalsystems.walletkit.chain.xrp.rpc.XrpNetworkMismatchException(server.networkId)
                }
                if (!server.completeLedgers.covers(server.validatedLedgerIndex, server.validatedLedgerIndex)) {
                    throw io.horizontalsystems.walletkit.chain.xrp.rpc.XrpLedgerGapException(
                        "Current validated ledger is not in complete_ledgers"
                    )
                }
                _balance.value = XrpBalance(
                    0,
                    server.reserveBaseDrops,
                    0,
                    0,
                    server.validatedLedgerIndex,
                )
                _lastLedgerIndex.value = server.validatedLedgerIndex
            }
            if (syncHistory) historySyncer.sync(address)
            reloadTransactions()
            reconciledSubmission?.let { archiveResolvedSubmission(it, syncHistory) }
            _syncState.value = XrpKitSyncState.Synced
        } catch (error: Throwable) {
            _syncState.value = XrpKitSyncState.Failed(error)
            throw error
        }
    }

    override suspend fun preflight(request: XrpSendRequest): XrpPreflightInfo = paymentMutex.withLock {
        buildPreflight(request).publicInfo
    }

    override fun transactionPage(
        beforeLedger: Long?,
        beforeHash: String?,
        limit: Int,
    ): List<XrpNativeTransaction> {
        queryableHistoryStore?.let {
            return it.transactions(address, beforeLedger, beforeHash, limit)
        }
        require(limit > 0) { "Transaction page limit must be positive" }
        require((beforeLedger == null) == (beforeHash == null)) {
            "Transaction cursor requires ledger and hash"
        }
        return _transactions.value
            .asSequence()
            .filter { transaction ->
                beforeLedger == null || transaction.ledgerIndex < beforeLedger ||
                    (transaction.ledgerIndex == beforeLedger && transaction.hash < beforeHash!!)
            }
            .sortedWith(
                compareByDescending<XrpNativeTransaction> { it.ledgerIndex }
                    .thenByDescending { it.hash }
            )
            .take(limit)
            .toList()
    }

    override suspend fun acknowledgeTerminalSubmission() = paymentMutex.withLock {
        val pending = submitter.pending(address)
            ?: throw IllegalStateException("There is no terminal XRP submission to acknowledge")
        check(pending.state.final) { "XRP submission is not terminal" }
        submitter.clearTerminal(address)
        _lastSubmission.value = null
    }

    override suspend fun prepare(request: XrpSendRequest): XrpSignedPayment = paymentMutex.withLock {
        prepareLocked(request)
    }

    private suspend fun prepareLocked(request: XrpSendRequest): XrpSignedPayment {
        val signingKey = privateKey ?: throw XrpWatchOnlyException()
        val prepared = buildPreflight(request)
        require(request.amountDrops >= prepared.publicInfo.minimumAmountDrops) {
            "XRP amount ${request.amountDrops} drops is below required minimum ${prepared.publicInfo.minimumAmountDrops} drops"
        }
        require(request.amountDrops <= prepared.publicInfo.spendableDrops) {
            "Insufficient spendable XRP after account reserve and network fee"
        }
        val preflight = prepared.rpcPreflight
        val destination = prepared.destination
        val lastLedger = Math.addExact(preflight.source.ledgerIndex, lastLedgerOffset)
        require(lastLedger <= UInt.MAX_VALUE.toLong()) { "LastLedgerSequence exceeds UInt32" }
        require(preflight.source.sequence in 1..UInt.MAX_VALUE.toLong()) { "XRP account sequence is invalid" }
        requireQuoteNotExpired(request)

        return XrpPaymentSigner.sign(
            XrpPaymentInput(
                privateKey = signingKey,
                account = address,
                destination = destination.classicAddress,
                destinationTag = destination.destinationTag,
                amountDrops = request.amountDrops,
                feeDrops = preflight.source.feeDrops,
                sequence = preflight.source.sequence.toUInt(),
                lastLedgerSequence = lastLedger.toUInt(),
                memo = request.memo,
            )
        ).also { payment ->
            // Signer already performs a decode/signature/hash/canonical-serialization commitment check.
            lastPreparedPayment = payment
            _balance.value = XrpBalance(
                preflight.source.balanceDrops,
                Math.addExact(
                    preflight.source.reserveBaseDrops,
                    Math.multiplyExact(preflight.source.reserveIncrementDrops, preflight.source.ownerCount),
                ),
                preflight.source.spendableDrops,
                preflight.source.feeDrops,
                preflight.source.ledgerIndex,
            )
            _lastLedgerIndex.value = preflight.source.ledgerIndex
        }
    }

    override suspend fun send(request: XrpSendRequest): XrpPendingTransaction = paymentMutex.withLock {
        val payment = prepareLocked(request)
        // Signing is intentionally followed by a second clock check so an expiry crossed during
        // signature generation can never reach the durable pending store or the network.
        requireQuoteNotExpired(request)
        val signed = XrpSignedTransaction(
            hash = payment.hash,
            blob = payment.signedBlob.toHex(),
            sourceAddress = payment.commitments.account,
            sequence = payment.commitments.sequence.toLong(),
            preparedLedgerIndex = payment.commitments.lastLedgerSequence.toLong() - lastLedgerOffset,
            lastLedgerSequence = payment.commitments.lastLedgerSequence.toLong(),
            createdAtEpochSeconds = nowEpochSeconds(),
        )
        submitter.submit(signed).also {
            if (lifecycleJob?.isActive == true) refreshSignal.trySend(Unit)
        }
    }

    private suspend fun buildPreflight(request: XrpSendRequest): PreparedPreflight {
        submitter.pending(address)?.let { throw XrpPendingTransactionExistsException(it.hash) }
        require(request.amountDrops > 0) { "XRP amount must be positive" }
        request.validUntilEpochSeconds?.let {
            require(it in 1..MAX_SUPPORTED_EPOCH_SECONDS) {
                "XRP quote expiry must be a bounded Unix epoch timestamp in seconds"
            }
        }
        request.minimumSendAmountDrops?.let {
            require(it > 0) { "Minimum XRP send amount must be positive" }
            require(it <= MAX_NATIVE_DROPS) { "Minimum XRP send amount exceeds protocol supply" }
        }
        request.maximumFeeDrops?.let {
            require(it > 0) { "Approved maximum XRP fee must be positive" }
            require(it <= MAX_NATIVE_DROPS) { "Approved maximum XRP fee exceeds protocol supply" }
        }
        val destination = XrpAddressCodec.resolveDestination(request.destination, request.destinationTag)
        val rpcPreflight = reader.paymentPreflight(
            sourceAddress = address,
            destinationAddress = destination.classicAddress,
            destinationTag = destination.destinationTag,
            recommendedFeeDrops = request.recommendedFeeDrops,
        )
        val minimum = max(
            rpcPreflight.destination.minimumAmountDrops,
            request.minimumSendAmountDrops ?: 1,
        )
        request.maximumFeeDrops?.let { approved ->
            if (rpcPreflight.source.feeDrops > approved) {
                throw XrpFeeApprovalExceededException(rpcPreflight.source.feeDrops, approved)
            }
        }
        return PreparedPreflight(
            destination,
            rpcPreflight,
            XrpPreflightInfo(
                destination.classicAddress,
                destination.destinationTag,
                rpcPreflight.source.feeDrops,
                minimum,
                rpcPreflight.source.spendableDrops,
                rpcPreflight.source.ledgerIndex,
            ),
        )
    }

    private fun requireQuoteNotExpired(request: XrpSendRequest) {
        val validUntil = request.validUntilEpochSeconds ?: return
        val now = nowEpochSeconds()
        require(now in 0..MAX_SUPPORTED_EPOCH_SECONDS) {
            "XRP wallet clock must be a bounded Unix epoch timestamp in seconds"
        }
        if (now >= validUntil) throw XrpQuoteExpiredException(validUntil, now)
    }

    private fun reloadTransactions() {
        queryableHistoryStore?.let {
            _transactions.value = it.transactions(address, limit = MAX_HISTORY_IN_MEMORY)
        }
    }

    private fun archiveResolvedSubmission(submission: XrpPendingTransaction, historyWasSynced: Boolean) {
        if (!submission.state.final) return
        _lastSubmission.value = submission
        val safelyRecordedInHistory = when (submission.state) {
            // These states have no validated transaction row. Keep the signed commitments and
            // terminal reason durable until an explicit recovery/acknowledgement flow clears it.
            XrpPendingState.EXPIRED, XrpPendingState.BLOCKED_SEQUENCE_CONFLICT -> false
            XrpPendingState.VALIDATED_SUCCESS, XrpPendingState.VALIDATED_FAILURE ->
                historyWasSynced && _transactions.value.any { it.hash.equals(submission.hash, ignoreCase = true) }
            XrpPendingState.PERSISTED, XrpPendingState.SUBMITTED -> false
        }
        if (safelyRecordedInHistory) {
            submitter.clearTerminal(address)
        }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02X".format(it.toInt() and 0xff) }

    companion object {
        const val DEFAULT_MAX_FEE_DROPS = 100_000L
        const val DEFAULT_LAST_LEDGER_OFFSET = 4L
        const val DEFAULT_PENDING_POLL_INTERVAL_MILLIS = 4_000L
        const val DEFAULT_IDLE_REFRESH_INTERVAL_MILLIS = 30_000L
        private const val MAX_NATIVE_DROPS = 100_000_000_000_000_000L
        // 9999-12-31T23:59:59Z: bounds the wire contract and rejects millisecond timestamps.
        private const val MAX_SUPPORTED_EPOCH_SECONDS = 253_402_300_799L
        private const val MAX_HISTORY_IN_MEMORY = 10_000
    }

    private data class PreparedPreflight(
        val destination: io.horizontalsystems.walletkit.chain.xrp.XrpDestination,
        val rpcPreflight: io.horizontalsystems.walletkit.chain.xrp.rpc.XrpPaymentPreflight,
        val publicInfo: XrpPreflightInfo,
    )
}

class XrpWatchOnlyException : IllegalStateException("Watch-only XRP accounts cannot sign transactions")

class XrpQuoteExpiredException(
    val validUntilEpochSeconds: Long,
    val observedEpochSeconds: Long,
) : IllegalStateException("XRP quote expired at $validUntilEpochSeconds (now $observedEpochSeconds)")
