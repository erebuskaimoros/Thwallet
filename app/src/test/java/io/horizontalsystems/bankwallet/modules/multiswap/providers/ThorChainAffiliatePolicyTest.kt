package io.horizontalsystems.bankwallet.modules.multiswap.providers

import org.junit.Assert.assertNull
import org.junit.Test

class ThorChainAffiliatePolicyTest {
    @Test
    fun `thorchain swaps do not request an affiliate fee`() {
        assertNull(ThorChainProvider.affiliate)
        assertNull(ThorChainProvider.affiliateBps)
    }
}
