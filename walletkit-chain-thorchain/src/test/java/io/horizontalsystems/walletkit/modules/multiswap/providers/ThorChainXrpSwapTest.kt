package io.horizontalsystems.walletkit.modules.multiswap.providers

import io.horizontalsystems.marketkit.models.Blockchain
import io.horizontalsystems.marketkit.models.BlockchainType
import io.horizontalsystems.marketkit.models.Coin
import io.horizontalsystems.marketkit.models.Token
import io.horizontalsystems.marketkit.models.TokenType
import io.horizontalsystems.walletkit.modules.multiswap.providers.ThornodeAPI.Response
import io.horizontalsystems.walletkit.modules.multiswap.sendtransaction.SendTransactionData
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class ThorChainXrpSwapTest {
    private class TestProvider : BaseThorChainProvider(
        baseUrl = "https://example.invalid/",
        affiliate = null,
        affiliateBps = null,
    ) {
        override val id = "test"
        override val title = "Test"
        override val riskLevel = RiskLevel.EXCELLENT

        suspend fun xrpSendData(token: Token, quote: Response.QuoteSwap) =
            getSendTransactionData(token, BigDecimal("101"), quote, token)
    }

    @Test
    fun `xrp quote builds tagged-safe native payment with drop fee policy`() = runBlocking {
        assertEquals(BlockchainType.Ripple, thorChainBlockchainType("XRP"))
        val quote = xrpQuote()
        val data = TestProvider().xrpSendData(xrpToken(), quote)

        assertTrue(data is SendTransactionData.Xrp)
        data as SendTransactionData.Xrp
        assertEquals(quote.inbound_address, data.address)
        assertEquals(BigDecimal("101"), data.amount)
        assertNull(data.destinationTag)
        assertEquals(quote.memo, data.memo)
        assertEquals(600L, data.recommendedFeeDrops)
        assertEquals(100_000_001L, data.minimumSendAmountDrops)
    }

    @Test
    fun `xrp send commitment carries the final quote expiry into live preflight`() = runBlocking {
        val quote = xrpQuote()
        val data = TestProvider().xrpSendData(xrpToken(), quote) as SendTransactionData.Xrp

        assertEquals(quote.expiry, data.validUntilEpochSeconds)
    }

    @Test
    fun `xrp quote accepts case insensitive exact drop unit`() = runBlocking {
        val data = TestProvider().xrpSendData(xrpToken(), xrpQuote(gasRateUnits = "DrOp"))
        assertEquals(600L, (data as SendTransactionData.Xrp).recommendedFeeDrops)
    }

    @Test
    fun `xrp quote rejects malformed units rates and dust`() {
        listOf(null, "", " drop ", "drops", "satsperbyte").forEach { unit ->
            assertInvalid(xrpQuote(gasRateUnits = unit))
        }
        listOf(null, "", "0", "-1", "100001", Long.MAX_VALUE.toString()).forEach { rate ->
            assertInvalid(xrpQuote(recommendedGasRate = rate))
        }
        listOf(null, "", "0", "-1", "1000000001", Long.MAX_VALUE.toString()).forEach { dust ->
            assertInvalid(xrpQuote(dustThreshold = dust))
        }
    }

    @Test
    fun `xrp swap memo binds every quoted payment commitment`() {
        val expected = "rHsMGQEkVNJmpGWs8XUBoTBiAAbwxZN5v3"
        assertEquals(
            expected,
            validatedThorXrpSwapCommitment(
                quote = xrpQuote(
                    memo = "=:b:$expected:6109",
                    expectedAmountOut = BigDecimal("6171"),
                    maxStreamingQuantity = 1,
                ),
                expectedAsset = "BTC.BTC",
                availablePoolAssets = listOf("BTC.BTC"),
                expectedDestination = expected,
                expectedRefundAddress = null,
                approvedSlippageBps = 100,
                requestedStreamingInterval = 0,
            ),
        )

        val rapid = xrpQuote(
            memo = "=:b:$expected:6109/0/5",
            expectedAmountOut = BigDecimal("6171"),
            maxStreamingQuantity = 5,
        )
        assertEquals(
            expected,
            validatedThorXrpSwapCommitment(
                rapid, "BTC.BTC", listOf("BTC.BTC"), expected, null, 100, 0,
            ),
        )
        assertEquals(
            expected,
            validatedThorXrpSwapCommitment(
                rapid.copy(memo = "=:b:$expected:6109/1/0", max_streaming_quantity = 1),
                "BTC.BTC",
                listOf("BTC.BTC"),
                expected,
                null,
                100,
                1,
            ),
        )

        val invalidMemos = listOf(
            "SWAP:b:$expected:6109",
            "=:ETH.ETH:$expected:6109",
            "=:b:${expected.lowercase()}:6109",
            "=:b:$expected/rRefundAddress:6109",
            "=:b:$expected:0",
            "=:b:$expected:6110",
            "=:b:$expected:6109/1/5",
            "=:b:$expected:6109/0/4",
            "=:b:$expected:6109:dx:30",
            "=:b:$expected:6109::",
        )
        invalidMemos.forEach { memo ->
            assertThrows(IllegalArgumentException::class.java) {
                validatedThorXrpSwapCommitment(
                    rapid.copy(memo = memo),
                    "BTC.BTC",
                    listOf("BTC.BTC"),
                    expected,
                    null,
                    100,
                    0,
                )
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            validatedThorXrpSwapCommitment(
                rapid.copy(fees = rapid.fees.copy(affiliate = BigDecimal.ONE)),
                "BTC.BTC", listOf("BTC.BTC"), expected, null, 100, 0,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            validatedThorXrpSwapCommitment(
                rapid.copy(fees = rapid.fees.copy(asset = "ETH.ETH")),
                "BTC.BTC", listOf("BTC.BTC"), expected, null, 100, 0,
            )
        }
    }

    @Test
    fun `xrp memo contract asset must use the unique thornode fuzzy suffix`() {
        val destination = "rHsMGQEkVNJmpGWs8XUBoTBiAAbwxZN5v3"
        val expectedAsset = "ETH.USDC-0X1234ABCD"
        val pools = listOf(expectedAsset, "ETH.USDC-0X9999ABCD")
        val quote = xrpQuote(
            memo = "=:ETH.USDC-4ABCD:$destination:990",
            expectedAmountOut = BigDecimal("1000"),
            maxStreamingQuantity = 1,
            feeAsset = expectedAsset,
        )

        assertEquals(
            destination,
            validatedThorXrpSwapCommitment(
                quote, expectedAsset, pools, destination, null, 100, 0,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            validatedThorXrpSwapCommitment(
                quote.copy(memo = "=:ETH.USDC:$destination:990"),
                expectedAsset,
                pools,
                destination,
                null,
                100,
                0,
            )
        }
    }

    @Test
    fun `xrp quote is bound to current unhalted inbound vault`() {
        val quote = xrpQuote()
        val inbound = xrpInbound()

        assertEquals(
            inbound.address,
            validatedThorXrpInbound(quote, listOf(inbound), nowEpochSeconds = 1_799_999_900),
        )

        assertThrows(IllegalArgumentException::class.java) {
            validatedThorXrpInbound(quote, listOf(inbound.copy(halted = true)), 1_799_999_900)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validatedThorXrpInbound(quote, listOf(inbound.copy(address = "rDifferent")), 1_799_999_900)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validatedThorXrpInbound(quote.copy(router = "0xRouter"), listOf(inbound), 1_799_999_900)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validatedThorXrpInbound(quote, listOf(inbound.copy(router = "0xRouter")), 1_799_999_900)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validatedThorXrpInbound(quote.copy(expiry = 1_799_999_900), listOf(inbound), 1_799_999_900)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validatedThorXrpInbound(quote.copy(dust_threshold = "1"), listOf(inbound), 1_799_999_900)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validatedThorXrpInbound(quote.copy(recommended_gas_rate = "1"), listOf(inbound), 1_799_999_900)
        }
    }

    private fun assertInvalid(quote: Response.QuoteSwap) {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { TestProvider().xrpSendData(xrpToken(), quote) }
        }
    }

    private fun xrpToken() = Token(
        coin = Coin("ripple", "XRP", "XRP"),
        blockchain = Blockchain(BlockchainType.Ripple, "XRP Ledger", "https://xrpscan.com/account/\$ref"),
        type = TokenType.Native,
        decimals = 6,
    )

    private fun xrpQuote(
        gasRateUnits: String? = "drop",
        recommendedGasRate: String? = "600",
        dustThreshold: String? = "100000000",
        memo: String = "=:BTC.BTC:1BoatSLRHtKNngkdXEeobR76b53LETtpyT:1",
        expectedAmountOut: BigDecimal = BigDecimal.ONE,
        maxStreamingQuantity: Long? = 1,
        feeAsset: String? = "BTC.BTC",
    ) = Response.QuoteSwap(
        inbound_address = "rg24swjNUoMbFenctAcieM1VXKaa3EP8d",
        inbound_confirmation_seconds = 12,
        outbound_delay_seconds = 60,
        fees = Response.QuoteSwap.Fees(
            asset = feeAsset,
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
        memo = memo,
        expected_amount_out = expectedAmountOut,
        max_streaming_quantity = maxStreamingQuantity,
        streaming_swap_seconds = 6,
        total_swap_seconds = 78,
    )

    private fun xrpInbound() = Response.InboundAddress(
        chain = "XRP",
        address = "rg24swjNUoMbFenctAcieM1VXKaa3EP8d",
        router = null,
        halted = false,
        gas_rate = "600",
        dust_threshold = "100000000",
        shielded_memo_config = null,
    )
}
