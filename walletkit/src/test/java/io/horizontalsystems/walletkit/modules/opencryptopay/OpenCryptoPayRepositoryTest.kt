package io.horizontalsystems.walletkit.modules.opencryptopay

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenCryptoPayRepositoryTest {

    @Test
    fun `does not advertise undocumented Dogecoin payment method`() {
        val transfer = OcpTransferAmount(method = "Dogecoin")

        assertEquals(emptyList<Any>(), transfer.supportedBlockchainTypes())
    }
}
