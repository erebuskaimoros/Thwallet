package io.horizontalsystems.walletkit.chain.xrp.adapters

import io.horizontalsystems.marketkit.models.Token
import io.horizontalsystems.walletkit.chain.xrp.XrpAddressCodec
import io.horizontalsystems.walletkit.chain.xrp.XrpAmount
import io.horizontalsystems.walletkit.chain.xrp.rpc.XrpNativeTransaction
import io.horizontalsystems.walletkit.chain.xrp.runtime.XrpKitSyncState
import io.horizontalsystems.walletkit.chain.xrp.runtime.XrpSendRequest
import io.horizontalsystems.walletkit.chain.xrp.runtime.XrpWalletKit
import io.horizontalsystems.walletkit.core.AdapterState
import io.horizontalsystems.walletkit.core.BalanceData
import io.horizontalsystems.walletkit.core.IAdapter
import io.horizontalsystems.walletkit.core.IBalanceAdapter
import io.horizontalsystems.walletkit.core.IReceiveAdapter
import io.horizontalsystems.walletkit.core.ISendXrpAdapter
import io.horizontalsystems.walletkit.core.ITransactionsAdapter
import io.horizontalsystems.walletkit.core.XrpSendInfo
import io.horizontalsystems.walletkit.core.XrpSubmissionStatus
import io.horizontalsystems.walletkit.entities.LastBlockInfo
import io.horizontalsystems.walletkit.entities.transactionrecords.TransactionRecord
import io.horizontalsystems.walletkit.modules.transactions.FilterTransactionType
import io.horizontalsystems.walletkit.modules.transactions.TransactionSource
import io.reactivex.Flowable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.rx2.asFlowable
import java.math.BigDecimal
import kotlin.math.min

class XrpAdapter(private val kit: XrpWalletKit) :
    IAdapter,
    IBalanceAdapter,
    IReceiveAdapter,
    ISendXrpAdapter {

    override val receiveAddress: String get() = kit.address
    override val isMainNet = true
    override val canSend: Boolean get() = kit.canSign
    override val terminalSubmissionFlow: Flow<XrpSubmissionStatus?> = kit.lastSubmission.map { submission ->
        submission?.let {
            XrpSubmissionStatus(
                transactionHash = it.hash,
                state = it.state.name,
                result = it.validatedResult ?: it.provisionalResult,
                requiresAcknowledgement = it.state == io.horizontalsystems.walletkit.chain.xrp.submission.XrpPendingState.EXPIRED ||
                    it.state == io.horizontalsystems.walletkit.chain.xrp.submission.XrpPendingState.BLOCKED_SEQUENCE_CONFLICT,
            )
        }
    }
    override val debugInfo: String
        get() = "address=${kit.address}, state=${kit.syncState.value}, ledger=${kit.lastLedgerIndex.value}, pending=${kit.pendingTransaction?.state}, lastSubmission=${kit.lastSubmission.value?.state}"

    override val balanceState: AdapterState get() = kit.syncState.value.toAdapterState()
    override val balanceStateUpdatedFlowable: Flowable<Unit>
        get() = kit.syncState.asFlowable().map {}
    override val balanceUpdatedFlowable: Flowable<Unit>
        get() = kit.balance.filterNotNull().asFlowable().map {}
    override val balanceData: BalanceData?
        get() = kit.balance.value?.let { balance ->
            val heldReserve = min(balance.balanceDrops, balance.reserveDrops)
            BalanceData(
                available = drops(balance.balanceDrops - heldReserve),
                minimumBalance = drops(heldReserve),
            )
        }

    override val maxSendableBalance: BigDecimal
        get() = drops(kit.balance.value?.spendableDrops ?: 0)
    override val fee: BigDecimal
        get() = drops(kit.balance.value?.feeDrops ?: 0)

    override fun start() = kit.start()
    override fun stop() = kit.stop()
    override fun refresh() = kit.refresh()

    override suspend fun sendInfo(
        address: String,
        destinationTag: UInt?,
        recommendedFeeDrops: Long?,
        minimumSendAmountDrops: Long?,
    ): XrpSendInfo {
        val destination = XrpAddressCodec.resolveDestination(address, destinationTag)
        val request = XrpSendRequest(
            address,
            destinationTag,
            amountDrops = minimumSendAmountDrops ?: 1,
            memo = null,
            recommendedFeeDrops = recommendedFeeDrops,
            minimumSendAmountDrops = minimumSendAmountDrops,
        )
        val info = kit.preflight(request)
        return XrpSendInfo(
            normalizedAddress = destination.classicAddress,
            destinationTag = destination.destinationTag,
            fee = drops(info.feeDrops),
            minimumAmount = drops(info.minimumAmountDrops),
            maxSendableBalance = drops(info.spendableDrops),
        )
    }

    override suspend fun send(
        amount: BigDecimal,
        address: String,
        destinationTag: UInt?,
        memo: String?,
        recommendedFeeDrops: Long?,
        minimumSendAmountDrops: Long?,
        maximumFee: BigDecimal?,
        validUntilEpochSeconds: Long?,
    ): String = kit.send(
        XrpSendRequest(
            destination = address,
            destinationTag = destinationTag,
            amountDrops = XrpAmount.toDropsExact(amount),
            memo = memo,
            recommendedFeeDrops = recommendedFeeDrops,
            minimumSendAmountDrops = minimumSendAmountDrops,
            maximumFeeDrops = maximumFee?.let(XrpAmount::toDropsExact),
            validUntilEpochSeconds = validUntilEpochSeconds,
        )
    ).hash

    override suspend fun acknowledgeTerminalSubmission() = kit.acknowledgeTerminalSubmission()

    private fun drops(value: Long) = BigDecimal.valueOf(value, 6)
}

class XrpTransactionConverter(
    private val source: TransactionSource,
    private val token: Token,
) {
    fun convert(transaction: XrpNativeTransaction): XrpTransactionRecord {
        val commonAmount = BigDecimal.valueOf(transaction.amountDrops, 6)
        val fee = BigDecimal.valueOf(transaction.feeDrops, 6)
        val height = Math.toIntExact(transaction.ledgerIndex)
        val timestamp = transaction.timestampEpochSeconds ?: 0
        return if (transaction.incoming) {
            XrpIncomingTransactionRecord(
                token,
                transaction.hash,
                transaction.hash,
                height,
                timestamp,
                !transaction.successful,
                commonAmount,
                transaction.source,
                transaction.destinationTag,
                source,
            )
        } else {
            XrpOutgoingTransactionRecord(
                token,
                transaction.hash,
                transaction.hash,
                height,
                timestamp,
                !transaction.successful,
                fee,
                commonAmount,
                transaction.destination,
                transaction.destination == transaction.source,
                transaction.destinationTag,
                source,
            )
        }
    }
}

class XrpTransactionsAdapter(
    private val kit: XrpWalletKit,
    private val converter: XrpTransactionConverter,
) : ITransactionsAdapter {
    override val explorerTitle = "XRPScan"
    override val transactionsState: AdapterState get() = kit.syncState.value.toAdapterState()
    override val transactionsStateUpdatedFlowable: Flowable<Unit>
        get() = kit.syncState.asFlowable().map {}
    override val lastBlockInfo: LastBlockInfo?
        get() = kit.lastLedgerIndex.value?.let { LastBlockInfo(Math.toIntExact(it)) }
    override val lastBlockUpdatedFlowable: Flowable<Unit>
        get() = kit.lastLedgerIndex.filterNotNull().asFlowable().map {}

    override suspend fun getTransactions(
        from: TransactionRecord?,
        token: Token?,
        limit: Int,
        transactionType: FilterTransactionType,
        address: String?,
    ): List<TransactionRecord> {
        require(limit > 0) { "Transaction page limit must be positive" }
        val fromRecord = from as? XrpTransactionRecord
        var beforeLedger = fromRecord?.blockHeight?.toLong()
        var beforeHash = fromRecord?.transactionHash
        if (fromRecord != null && beforeLedger == null) return emptyList()
        val records = mutableListOf<TransactionRecord>()
        while (records.size < limit) {
            val queryLimit = maxOf(MIN_DURABLE_PAGE_SIZE, limit - records.size)
            val page = kit.transactionPage(beforeLedger, beforeHash, queryLimit)
            if (page.isEmpty()) break
            page.forEach { transaction ->
                if (records.size < limit) {
                    converter.convert(transaction).takeIf {
                        matches(it, token, transactionType, address)
                    }?.let(records::add)
                }
            }
            val last = page.last()
            beforeLedger = last.ledgerIndex
            beforeHash = last.hash
            if (page.size < queryLimit) break
        }
        return records
    }

    override suspend fun getTransactionsAfter(fromTransactionId: String?): List<TransactionRecord> {
        val records = kit.transactions.value
            .sortedWith(compareByDescending<XrpNativeTransaction> { it.ledgerIndex }.thenByDescending { it.hash })
        val boundary = fromTransactionId?.let { id -> records.indexOfFirst { it.hash == id } } ?: records.size
        return records.take(if (boundary < 0) records.size else boundary).map(converter::convert)
    }

    override fun getTransactionRecordsFlow(
        token: Token?,
        transactionType: FilterTransactionType,
        address: String?,
    ): Flow<List<TransactionRecord>> = kit.transactions.map { transactions ->
        transactions
            .sortedWith(compareByDescending<XrpNativeTransaction> { it.ledgerIndex }.thenByDescending { it.hash })
            .map(converter::convert)
            .filter { matches(it, token, transactionType, address) }
    }

    override fun getRawTransaction(transactionHash: String): String? =
        kit.pendingTransaction?.takeIf { it.hash.equals(transactionHash, ignoreCase = true) }?.blob

    override fun getTransactionUrl(transactionHash: String) = "https://xrpscan.com/tx/$transactionHash"

    private fun matches(
        record: XrpTransactionRecord,
        token: Token?,
        transactionType: FilterTransactionType,
        address: String?,
    ): Boolean {
        if (token != null && record.mainValue?.coinUid != token.coin.uid) return false
        val typeMatches = when (transactionType) {
            FilterTransactionType.All -> true
            FilterTransactionType.Incoming -> record is XrpIncomingTransactionRecord
            FilterTransactionType.Outgoing -> record is XrpOutgoingTransactionRecord
            FilterTransactionType.Swap, FilterTransactionType.Approve -> false
        }
        if (!typeMatches) return false
        return address == null || when (record) {
            is XrpIncomingTransactionRecord -> record.from == address
            is XrpOutgoingTransactionRecord -> record.to == address
            else -> false
        }
    }

    private companion object {
        const val MIN_DURABLE_PAGE_SIZE = 100
    }
}

private fun XrpKitSyncState.toAdapterState(): AdapterState = when (this) {
    XrpKitSyncState.Idle -> AdapterState.Syncing()
    XrpKitSyncState.Syncing -> AdapterState.Syncing()
    XrpKitSyncState.Synced -> AdapterState.Synced
    is XrpKitSyncState.Failed -> AdapterState.NotSynced(error)
}
