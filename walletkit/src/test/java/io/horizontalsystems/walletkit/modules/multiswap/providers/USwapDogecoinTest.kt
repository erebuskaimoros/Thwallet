package io.horizontalsystems.walletkit.modules.multiswap.providers

import io.horizontalsystems.marketkit.models.BlockchainType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class USwapDogecoinTest {

    @Test
    fun `dogecoin chain id is recognized for token discovery and requests`() {
        assertEquals(BlockchainType.Dogecoin, uSwapBlockchainType("dogecoin"))
    }

    @Test
    fun `dogecoin route builds a locally signed simple utxo deposit`() {
        val data = buildUSwapSimpleUtxoSendData(
            blockchainType = BlockchainType.Dogecoin,
            address = "D9EGy2TqzyLmL1PJ9K8LhoM9HuZaogDkJY",
            memo = "order-id",
            amount = BigDecimal("12.5"),
        )

        assertEquals("D9EGy2TqzyLmL1PJ9K8LhoM9HuZaogDkJY", data.address)
        assertEquals("order-id", data.memo)
        assertEquals(BigDecimal("12.5"), data.amount)
        assertNull(data.recommendedGasRate)
        assertNull(data.minimumSendAmount)
        assertFalse(data.changeToFirstInput)
        assertTrue(data.utxoFilters.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `simple utxo builder rejects non utxo chains`() {
        buildUSwapSimpleUtxoSendData(
            blockchainType = BlockchainType.Ethereum,
            address = "0x0000000000000000000000000000000000000000",
            memo = null,
            amount = BigDecimal.ONE,
        )
    }
}
