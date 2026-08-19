package io.horizontalsystems.walletkit.chain.xrp.history

import io.horizontalsystems.walletkit.chain.xrp.rpc.*

class FakeRpc : XrpRpc {
    lateinit var server: XrpServerState
    var account: XrpAccountState? = null
    val accountByAddress = mutableMapOf<String, XrpAccountState?>()
    var fee = XrpFeeQuote(10, 10, 10, 10, 1)
    val pages = mutableListOf<XrpTransactionPage>()
    var pageFailure: Throwable? = null
    var transactionResult: XrpTransactionLookup? = null
    var onFeeQuote: suspend () -> Unit = {}
    var feeQuoteCalls = 0
    var onSubmit: suspend (String) -> XrpSubmitResult = { XrpSubmitResult("tesSUCCESS", null) }
    val submittedBlobs = mutableListOf<String>()
    private var pageIndex = 0

    override suspend fun serverState() = server
    override suspend fun accountState(address: String, ledgerIndex: Long) =
        if (accountByAddress.containsKey(address)) accountByAddress[address] else account
    override suspend fun feeQuote(): XrpFeeQuote {
        feeQuoteCalls++
        onFeeQuote()
        return fee
    }
    override suspend fun accountTransactions(address: String, ledgerMin: Long, ledgerMax: Long, marker: XrpPageMarker?): XrpTransactionPage {
        if (pageIndex >= pages.size) throw pageFailure ?: error("No fake page")
        return pages[pageIndex++]
    }
    override suspend fun transaction(hash: String) = transactionResult
    override suspend fun submit(signedBlob: String): XrpSubmitResult {
        submittedBlobs += signedBlob
        return onSubmit(signedBlob)
    }
}

class RecordingHistoryStore(var checkpoint: Long? = null) : XrpHistoryStore {
    var transactions = emptyList<XrpNativeTransaction>()
    var commits = 0
    private var targetCheckpoint: Long? = null
    private val staged = linkedMapOf<String, XrpNativeTransaction>()
    override suspend fun checkpoint(address: String) = checkpoint
    override suspend fun beginSync(address: String, targetCheckpoint: Long) {
        this.targetCheckpoint = targetCheckpoint
        staged.clear()
    }
    override suspend fun stagePage(
        address: String,
        targetCheckpoint: Long,
        transactions: List<XrpNativeTransaction>,
    ): Int {
        check(this.targetCheckpoint == targetCheckpoint)
        var inserted = 0
        transactions.forEach { transaction ->
            val previous = staged[transaction.hash]
            if (previous != null && previous != transaction) {
                throw XrpProtocolException("Conflicting account_tx records for ${transaction.hash}")
            }
            if (previous == null) {
                staged[transaction.hash] = transaction
                inserted++
            }
        }
        return inserted
    }
    override suspend fun commitSync(address: String, targetCheckpoint: Long) {
        check(this.targetCheckpoint == targetCheckpoint)
        val merged = transactions.associateByTo(linkedMapOf()) { it.hash }
        merged.putAll(staged)
        transactions = merged.values.toList()
        checkpoint = targetCheckpoint
        commits++
        staged.clear()
        this.targetCheckpoint = null
    }
    override suspend fun rollbackSync(address: String, targetCheckpoint: Long) {
        staged.clear()
        this.targetCheckpoint = null
    }
}

fun state(validated: Long, complete: XrpLedgerRanges) = XrpServerState(
    networkId = 0,
    validatedLedgerIndex = validated,
    completeLedgers = complete,
    reserveBaseDrops = 1_000_000,
    reserveIncrementDrops = 200_000
)

fun nativeTransaction(hash: String, ledger: Long) = XrpNativeTransaction(
    hash = hash,
    ledgerIndex = ledger,
    timestampEpochSeconds = null,
    source = "rOther",
    destination = "rMine",
    destinationTag = null,
    amountDrops = 100,
    feeDrops = 10,
    sequence = 1,
    incoming = true,
    successful = true
)
