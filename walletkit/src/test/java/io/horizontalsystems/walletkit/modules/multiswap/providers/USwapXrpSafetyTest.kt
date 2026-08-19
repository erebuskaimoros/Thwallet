package io.horizontalsystems.walletkit.modules.multiswap.providers

import org.junit.Assert.assertNull
import org.junit.Test

class USwapXrpSafetyTest {
    @Test
    fun `XRP stays disabled until destination-tag attachments can be signed`() {
        assertNull(uSwapBlockchainType("ripple"))
        assertNull(uSwapBlockchainType("xrp"))
    }
}
