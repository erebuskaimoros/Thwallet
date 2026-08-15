package io.horizontalsystems.walletkit.core.managers

import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.core.signer.Signer
import io.horizontalsystems.ethereumkit.models.Chain
import io.horizontalsystems.walletkit.core.ethereum.EvmNetworkCatalog
import io.horizontalsystems.walletkit.modules.walletconnect.WCUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvmKitManagerRegistryTest {

    private val expectedChains = linkedMapOf(
        EvmNetworkCatalog.cronos to Chain.Cronos,
        EvmNetworkCatalog.blast to Chain.Blast,
        EvmNetworkCatalog.mantle to Chain.Mantle,
        EvmNetworkCatalog.sei to Chain.Sei,
        EvmNetworkCatalog.hyperEvm to Chain.HyperEvm,
        EvmNetworkCatalog.robinhood to Chain.Robinhood,
    )

    @Test
    fun `catalog networks map to the intended ethereum-kit chains`() {
        expectedChains.forEach { (blockchainType, expectedChain) ->
            val chain = EvmKitManagerRegistry.getChain(blockchainType)

            assertEquals(expectedChain, chain)
            assertEquals(EvmNetworkCatalog.chainId(blockchainType), chain.id)
            assertEquals(60, chain.coinType)
            assertTrue(chain.isMainNet)
            assertTrue(chain.isEIP1559Supported)
        }
    }

    @Test
    fun `all catalog networks derive the standard Ethereum address`() {
        EthereumKit.init()
        val words = List(11) { "abandon" } + "about"
        val ethereumAddress = Signer.address(words, chain = Chain.Ethereum)

        expectedChains.values.forEach { chain ->
            assertEquals(ethereumAddress, Signer.address(words, chain = chain))
        }
    }

    @Test
    fun `adapter clear traversal includes every catalog network`() {
        val clearChains = EvmBlockchainManager.blockchainTypes
            .map(EvmKitManagerRegistry::getChain)

        expectedChains.values.forEach { chain ->
            assertTrue(chain in clearChains)
        }
    }

    @Test
    fun `WalletConnect recognizes every catalog CAIP-2 chain`() {
        expectedChains.values.forEach { chain ->
            val chainData = WCUtils.getChainData("eip155:${chain.id}")

            assertEquals(chain.id, chainData?.id)
            assertEquals(chain.name, chainData?.name)
        }
    }
}
