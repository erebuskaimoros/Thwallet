package io.horizontalsystems.walletkit.chain.xrp.runtime

import io.horizontalsystems.walletkit.chain.xrp.XrpSignedPayment
import io.horizontalsystems.walletkit.chain.xrp.rpc.XrpNativeTransaction
import io.horizontalsystems.walletkit.chain.xrp.submission.XrpPendingTransaction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeXrpWalletKit(
    override val address: String,
    initialBalance: XrpBalance? = null,
    initialState: XrpKitSyncState = XrpKitSyncState.Synced,
    override val transactions: MutableStateFlow<List<XrpNativeTransaction>> = MutableStateFlow(emptyList()),
    override val canSign: Boolean = true,
    private val durableTransactions: List<XrpNativeTransaction> = transactions.value,
) : XrpWalletKit {
    override val balance = MutableStateFlow(initialBalance)
    override val syncState = MutableStateFlow(initialState)
    override val lastLedgerIndex = MutableStateFlow(initialBalance?.ledgerIndex)
    override val lastSubmission = MutableStateFlow<XrpPendingTransaction?>(null)
    override val pendingTransaction: XrpPendingTransaction? = null
    override fun start() = Unit
    override fun stop() = Unit
    override fun refresh() = Unit
    override suspend fun refreshNow(syncHistory: Boolean) = Unit
    override fun transactionPage(beforeLedger: Long?, beforeHash: String?, limit: Int) =
        durableTransactions
            .asSequence()
            .filter { transaction ->
                beforeLedger == null || transaction.ledgerIndex < beforeLedger ||
                    (transaction.ledgerIndex == beforeLedger && transaction.hash < requireNotNull(beforeHash))
            }
            .sortedWith(
                compareByDescending<XrpNativeTransaction> { it.ledgerIndex }
                    .thenByDescending { it.hash }
            )
            .take(limit)
            .toList()
    override suspend fun acknowledgeTerminalSubmission() = Unit
    override suspend fun preflight(request: XrpSendRequest): XrpPreflightInfo = error("not used")
    override suspend fun prepare(request: XrpSendRequest): XrpSignedPayment = error("not used")
    override suspend fun send(request: XrpSendRequest): XrpPendingTransaction = error("not used")
}
