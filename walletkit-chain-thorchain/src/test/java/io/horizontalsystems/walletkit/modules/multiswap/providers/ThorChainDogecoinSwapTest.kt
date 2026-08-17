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
import org.junit.Assert.assertThrows
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
        val token = dogecoinToken()
        val quote = dogecoinQuote(gasRateUnits = "satsperbyte")

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

    @Test
    fun `dogecoin quote accepts case insensitive sats per byte units`() = runBlocking {
        val data = TestProvider().dogecoinSendData(
            dogecoinToken(),
            BigDecimal("10"),
            dogecoinQuote(gasRateUnits = "SatsPerByte"),
        )

        assertTrue(data is SendTransactionData.Btc)
        assertEquals(750_000, (data as SendTransactionData.Btc).recommendedGasRate)
    }

    @Test
    fun `dogecoin quote rejects missing gas rate units`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                TestProvider().dogecoinSendData(
                    dogecoinToken(),
                    BigDecimal("10"),
                    dogecoinQuote(gasRateUnits = null),
                )
            }
        }
    }

    @Test
    fun `dogecoin quote rejects malformed or different gas rate units`() {
        listOf("", " satsperbyte ", "gwei", "coinsperbyte").forEach { gasRateUnits ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    TestProvider().dogecoinSendData(
                        dogecoinToken(),
                        BigDecimal("10"),
                        dogecoinQuote(gasRateUnits = gasRateUnits),
                    )
                }
            }
        }
    }

    @Test
    fun `dogecoin quote rejects missing nonpositive or excessive gas rates`() {
        listOf(null, "", "0", "-1", "1000001", Long.MAX_VALUE.toString()).forEach { gasRate ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    TestProvider().dogecoinSendData(
                        dogecoinToken(),
                        BigDecimal("10"),
                        dogecoinQuote(
                            gasRateUnits = "satsperbyte",
                            recommendedGasRate = gasRate,
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun `dogecoin quote rejects missing nonpositive or excessive dust thresholds`() {
        listOf(null, "", "0", "-1", "1000000001", Long.MAX_VALUE.toString()).forEach { dustThreshold ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    TestProvider().dogecoinSendData(
                        dogecoinToken(),
                        BigDecimal("10"),
                        dogecoinQuote(
                            gasRateUnits = "satsperbyte",
                            dustThreshold = dustThreshold,
                        ),
                    )
                }
            }
        }
    }

    private fun dogecoinToken() = Token(
        coin = Coin("dogecoin", "Dogecoin", "DOGE"),
        blockchain = Blockchain(BlockchainType.Dogecoin, "Dogecoin", null),
        type = TokenType.Native,
        decimals = 8,
    )

    private fun dogecoinQuote(
        gasRateUnits: String?,
        recommendedGasRate: String? = "750000",
        dustThreshold: String? = "100000000",
    ) = Response.QuoteSwap(
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
        dust_threshold = dustThreshold,
        recommended_gas_rate = recommendedGasRate,
        gas_rate_units = gasRateUnits,
        memo = "=:THOR.RUNE:thor1destination",
        expected_amount_out = BigDecimal.ONE,
        streaming_swap_seconds = 6,
        total_swap_seconds = 126,
    )
}
