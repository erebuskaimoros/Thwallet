package io.horizontalsystems.walletkit.chain.xrp.send

import io.horizontalsystems.marketkit.models.Blockchain
import io.horizontalsystems.marketkit.models.BlockchainType
import io.horizontalsystems.marketkit.models.Coin
import io.horizontalsystems.marketkit.models.Token
import io.horizontalsystems.marketkit.models.TokenType
import io.horizontalsystems.walletkit.core.ISendXrpAdapter
import io.horizontalsystems.walletkit.core.XrpSendInfo
import io.horizontalsystems.walletkit.modules.multiswap.sendtransaction.SendTransactionData
import io.horizontalsystems.walletkit.modules.multiswap.sendtransaction.SendTransactionResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class SendTransactionServiceXrpTest {
    private val token = Token(
        Coin("ripple", "XRP", "XRP"),
        Blockchain(BlockchainType.Ripple, "XRP Ledger", "https://xrpscan.com/account/\$ref"),
        TokenType.Native,
        6,
    )

    @Test
    fun `typed swap data preserves tag zero memo fee and dust separately`() = runTest {
        val adapter = RecordingAdapter()
        val service = SendTransactionServiceXrp(adapter, token)
        val data = SendTransactionData.Xrp(
            address = "rVault",
            amount = BigDecimal("101"),
            destinationTag = 0u,
            memo = "=:THOR.RUNE:rRecipient",
            recommendedFeeDrops = 300,
            minimumSendAmountDrops = 100_000_001,
            validUntilEpochSeconds = 1_800_000_000,
        )

        service.setSendTransactionData(data)
        val result = service.sendTransaction()

        assertTrue(result is SendTransactionResult.Xrp)
        assertEquals(0u, adapter.tag)
        assertEquals(data.memo, adapter.memo)
        assertEquals(300L, adapter.recommendedFeeDrops)
        assertEquals(100_000_001L, adapter.minimumSendAmountDrops)
        assertEquals(BigDecimal("0.0003"), adapter.maximumFee)
        assertEquals(1_800_000_000L, adapter.validUntilEpochSeconds)
    }

    private class RecordingAdapter : ISendXrpAdapter {
        override val maxSendableBalance = BigDecimal("1000")
        override val fee = BigDecimal("0.0003")
        override val canSend = true
        override val terminalSubmissionFlow = flowOf(null)
        var tag: UInt? = null
        var memo: String? = null
        var recommendedFeeDrops: Long? = null
        var minimumSendAmountDrops: Long? = null
        var maximumFee: BigDecimal? = null
        var validUntilEpochSeconds: Long? = null

        override suspend fun sendInfo(
            address: String,
            destinationTag: UInt?,
            recommendedFeeDrops: Long?,
            minimumSendAmountDrops: Long?,
        ) = XrpSendInfo(address, destinationTag, fee, BigDecimal("100"), maxSendableBalance)

        override suspend fun send(
            amount: BigDecimal,
            address: String,
            destinationTag: UInt?,
            memo: String?,
            recommendedFeeDrops: Long?,
            minimumSendAmountDrops: Long?,
            maximumFee: BigDecimal?,
            validUntilEpochSeconds: Long?,
        ): String {
            tag = destinationTag
            this.memo = memo
            this.recommendedFeeDrops = recommendedFeeDrops
            this.minimumSendAmountDrops = minimumSendAmountDrops
            this.maximumFee = maximumFee
            this.validUntilEpochSeconds = validUntilEpochSeconds
            return "A".repeat(64)
        }

        override suspend fun acknowledgeTerminalSubmission() = Unit
    }
}
