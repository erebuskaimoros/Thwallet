package io.horizontalsystems.walletkit.chain.xrp.send

import io.horizontalsystems.marketkit.models.Token
import io.horizontalsystems.walletkit.core.ISendXrpAdapter
import io.horizontalsystems.walletkit.core.XrpSendInfo
import io.horizontalsystems.walletkit.core.ethereum.CautionViewItem
import io.horizontalsystems.walletkit.entities.CoinValue
import io.horizontalsystems.walletkit.modules.multiswap.sendtransaction.AbstractSendTransactionService
import io.horizontalsystems.walletkit.modules.multiswap.sendtransaction.SendTransactionData
import io.horizontalsystems.walletkit.modules.multiswap.sendtransaction.SendTransactionResult
import io.horizontalsystems.walletkit.modules.multiswap.sendtransaction.SendTransactionServiceState
import io.horizontalsystems.walletkit.modules.multiswap.sendtransaction.SendTransactionSettings
import io.horizontalsystems.walletkit.modules.send.SendModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

class SendTransactionServiceXrp(
    private val adapter: ISendXrpAdapter,
    private val token: Token,
) : AbstractSendTransactionService(false, false) {
    override val sendTransactionSettingsFlow = MutableStateFlow(SendTransactionSettings.Xrp())
    private var data: SendTransactionData.Xrp? = null
    private var sendInfo: XrpSendInfo? = null
    private var cautions = emptyList<CautionViewItem>()

    override fun start(coroutineScope: CoroutineScope) = Unit

    override suspend fun setSendTransactionData(data: SendTransactionData) {
        check(data is SendTransactionData.Xrp) { "XRP send service requires typed XRP transaction data" }
        this.data = data
        cautions = try {
            data.memo?.let { memo ->
                require(memo.toByteArray(Charsets.UTF_8).size <= 256) {
                    "XRP MemoData cannot exceed 256 UTF-8 bytes"
                }
            }
            val info = adapter.sendInfo(
                data.address,
                data.destinationTag,
                data.recommendedFeeDrops,
                data.minimumSendAmountDrops,
            )
            sendInfo = info
            when {
                !adapter.canSend -> listOf(error("Watch-only XRP accounts cannot send"))
                data.amount < info.minimumAmount -> listOf(error("XRP amount is below the required minimum"))
                data.amount > info.maxSendableBalance -> listOf(error("Insufficient spendable XRP after reserve and fee"))
                else -> emptyList()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            sendInfo = null
            listOf(error(throwable.message ?: throwable.javaClass.simpleName))
        }
        emitState()
    }

    override suspend fun sendTransaction(mevProtectionEnabled: Boolean): SendTransactionResult {
        check(createState().sendable) { "XRP transaction is not sendable" }
        val data = requireNotNull(data)
        val hash = adapter.send(
            data.amount,
            data.address,
            data.destinationTag,
            data.memo,
            data.recommendedFeeDrops,
            data.minimumSendAmountDrops,
            maximumFee = requireNotNull(sendInfo).fee,
            validUntilEpochSeconds = data.validUntilEpochSeconds,
        )
        return SendTransactionResult.Xrp(hash)
    }

    override fun createState(): SendTransactionServiceState {
        val fee = sendInfo?.fee ?: adapter.fee
        val feeData = SendModule.AmountData(
            SendModule.AmountInfo.CoinValueInfo(CoinValue(token, fee)),
            null,
        )
        return SendTransactionServiceState(
            uuid = uuid,
            networkFee = feeData,
            cautions = cautions,
            sendable = data != null && adapter.canSend && sendInfo != null &&
                cautions.none { it.type == CautionViewItem.Type.Error },
            loading = data == null,
            fields = emptyList(),
        )
    }

    private fun error(message: String) = CautionViewItem(
        title = "XRP transaction",
        text = message,
        type = CautionViewItem.Type.Error,
    )
}
