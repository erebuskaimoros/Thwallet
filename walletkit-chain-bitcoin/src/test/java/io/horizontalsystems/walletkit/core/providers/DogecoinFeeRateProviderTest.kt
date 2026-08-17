package io.horizontalsystems.walletkit.core.providers

import io.horizontalsystems.walletkit.modules.multiswap.sendtransaction.effectiveExternalFeeRate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DogecoinFeeRateProviderTest {

    @Test
    fun `uses dogecoin core 1_14_9 policy rates`() = runBlocking {
        val provider = DogecoinFeeRateProvider()

        assertFalse(provider.feeRateChangeable)
        assertEquals(100, provider.hardMinimumFeeRate)
        assertEquals(
            FeeRates(recommended = 1_000, minimum = 100),
            provider.getFeeRates(),
        )
    }

    @Test
    fun `external fee cannot undercut dogecoin relay floor`() {
        assertEquals(100, effectiveExternalFeeRate(0, 100))
        assertEquals(100, effectiveExternalFeeRate(99, 100))
        assertEquals(100, effectiveExternalFeeRate(100, 100))
    }

    @Test
    fun `external fee preserves a higher server requirement`() {
        assertEquals(750_000, effectiveExternalFeeRate(750_000, 100))
        assertEquals(750_000, effectiveExternalFeeRate(750_000, null))
    }
}
