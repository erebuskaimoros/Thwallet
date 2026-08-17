package io.horizontalsystems.walletkit.modules.multiswap

import io.horizontalsystems.marketkit.models.BlockchainType
import org.junit.Assert.assertEquals
import org.junit.Test

class SwapPopularTokensTest {

    @Test
    fun `dogecoin is discoverable among native swap assets`() {
        assertEquals(
            listOf(
                BlockchainType.Bitcoin,
                BlockchainType.Ethereum,
                BlockchainType.Monero,
                BlockchainType.Zcash,
                BlockchainType.Dogecoin,
                BlockchainType.Tron,
            ),
            SwapPopularTokens.baseNativeTypes,
        )
    }
}
