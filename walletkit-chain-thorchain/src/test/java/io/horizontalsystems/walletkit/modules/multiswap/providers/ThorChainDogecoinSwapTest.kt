package io.horizontalsystems.walletkit.modules.multiswap.providers

import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import io.horizontalsystems.marketkit.models.Blockchain
import io.horizontalsystems.marketkit.models.BlockchainType
import io.horizontalsystems.marketkit.models.Coin
import io.horizontalsystems.marketkit.models.Token
import io.horizontalsystems.marketkit.models.TokenType
import io.horizontalsystems.walletkit.modules.multiswap.providers.ThornodeAPI.Response
import io.horizontalsystems.walletkit.modules.multiswap.sendtransaction.SendTransactionData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class ThorChainDogecoinSwapTest {

    private class TestProvider : BaseThorChainProvider(
        baseUrl = "https://example.invalid/",
        affiliate = null,
        affiliateBps = null,
    ) {
        override val id = "test"
        override val title = "Test"
        override val riskLevel = RiskLevel.EXCELLENT

        suspend fun dogecoinSendData(
            token: Token,
            amount: BigDecimal,
            quote: Response.QuoteSwap,
        ) = getSendTransactionData(token, amount, quote, token)
    }

    @Test
    fun `dogecoin quote builds legacy p2pkh inbound transaction`() = runBlocking {
        val token = Token(
            coin = Coin("dogecoin", "Dogecoin", "DOGE"),
            blockchain = Blockchain(BlockchainType.Dogecoin, "Dogecoin", null),
            type = TokenType.Native,
            decimals = 8,
        )
        val quote = Response.QuoteSwap(
            inbound_address = "D9EGy2TqzyLmL1PJ9K8LhoM9HuZaogDkJY",
            inbound_confirmation_seconds = 60,
            outbound_delay_seconds = 60,
            fees = Response.QuoteSwap.Fees(
                affiliate = BigDecimal.ZERO,
                outbound = BigDecimal.ONE,
                liquidity = BigDecimal.ONE,
                total = BigDecimal("2"),
            ),
            router = null,
            expiry = 1_800_000_000,
            dust_threshold = "100000000",
            recommended_gas_rate = "750000",
            memo = "=:THOR.RUNE:thor1destination",
            expected_amount_out = BigDecimal.ONE,
            streaming_swap_seconds = 6,
            total_swap_seconds = 126,
        )

        val data = TestProvider().dogecoinSendData(token, BigDecimal("10"), quote)

        assertTrue(data is SendTransactionData.Btc)
        data as SendTransactionData.Btc
        assertEquals(quote.inbound_address, data.address)
        assertEquals(quote.memo, data.memo)
        assertEquals(BigDecimal("10"), data.amount)
        assertEquals(750_000, data.recommendedGasRate)
        assertEquals(100_000_001, data.minimumSendAmount)
        assertTrue(data.changeToFirstInput)
        assertEquals(listOf(ScriptType.P2PKH), data.utxoFilters.scriptTypes)
        assertEquals(10, data.utxoFilters.maxOutputsCountForInputs)
    }
}
