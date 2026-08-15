package io.horizontalsystems.walletkit.core.ethereum

import io.horizontalsystems.marketkit.models.Blockchain
import io.horizontalsystems.marketkit.models.BlockchainType
import io.horizontalsystems.marketkit.models.Coin
import io.horizontalsystems.marketkit.models.FullCoin
import io.horizontalsystems.marketkit.models.Token
import io.horizontalsystems.marketkit.models.TokenQuery
import io.horizontalsystems.marketkit.models.TokenType
import io.horizontalsystems.walletkit.core.blockchainTypeFromChainId
import io.horizontalsystems.walletkit.core.chainId
import io.horizontalsystems.walletkit.core.isEvm
import io.horizontalsystems.walletkit.core.isSupported
import io.horizontalsystems.walletkit.modules.address.AddressHandlerEvm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvmNetworkCatalogTest {

    private val expectedChainIds = linkedMapOf(
        EvmNetworkCatalog.cronos to 25L,
        EvmNetworkCatalog.blast to 81457L,
        EvmNetworkCatalog.mantle to 5000L,
        EvmNetworkCatalog.sei to 1329L,
        EvmNetworkCatalog.hyperEvm to 999L,
        EvmNetworkCatalog.robinhood to 4663L,
    )

    @Test
    fun `catalog keeps stable unique blockchain uids and chain ids`() {
        assertEquals(
            listOf("cronos", "blast", "mantle", "sei-network", "hyperevm", "robinhood"),
            EvmNetworkCatalog.blockchainTypes.map { it.uid },
        )
        assertEquals(
            EvmNetworkCatalog.blockchainTypes.size,
            EvmNetworkCatalog.blockchainTypes.distinct().size,
        )

        expectedChainIds.forEach { (blockchainType, chainId) ->
            assertEquals(chainId.toInt(), EvmNetworkCatalog.chainId(blockchainType))
            assertEquals(chainId, blockchainType.chainId)
            assertEquals(blockchainType, chainId.blockchainTypeFromChainId)
            assertEquals(blockchainType, BlockchainType.fromUid(blockchainType.uid))
        }
    }

    @Test
    fun `every catalog network is exposed as a complete EVM native-token network`() {
        assertEquals(
            EvmNetworkCatalog.blockchainTypes,
            EvmNetworkCatalog.blockchains.map { it.type },
        )
        assertEquals(
            listOf("CRO", "ETH", "MNT", "SEI", "HYPE", "ETH"),
            EvmNetworkCatalog.nativeTokens.map { it.coin.code },
        )

        EvmNetworkCatalog.blockchainTypes.forEach { blockchainType ->
            assertTrue(blockchainType.isEvm)
            assertEquals(blockchainType, EvmNetworkCatalog.blockchain(blockchainType)?.type)
            assertEquals(18, EvmNetworkCatalog.nativeToken(blockchainType)?.decimals)
            assertTrue(TokenQuery(blockchainType, TokenType.Native).isSupported)
            assertTrue(
                TokenQuery(
                    blockchainType,
                    TokenType.Eip20("0x0000000000000000000000000000000000000001"),
                ).isSupported,
            )
        }
    }

    @Test
    fun `every catalog network accepts and preserves an EVM address`() {
        val mixedCaseAddress = "0x52908400098527886E0F7030069857D2E4169EE7"

        EvmNetworkCatalog.blockchainTypes.forEach { blockchainType ->
            val handler = AddressHandlerEvm(blockchainType)

            assertTrue(handler.isSupported(mixedCaseAddress))
            assertEquals(blockchainType, handler.parseAddress(mixedCaseAddress).blockchainType)
            assertEquals(mixedCaseAddress.lowercase(), handler.parseAddress(mixedCaseAddress).hex)
        }
    }

    @Test
    fun `legacy per-chain contract row normalizes to lowercase EIP-20`() {
        val address = "0xA0b86991c6218B36c1d19D4a2e9Eb0cE3606eB48"
        val marketToken = Token(
            coin = Coin("usd-coin", "USD Coin", "USDC"),
            blockchain = Blockchain(EvmNetworkCatalog.blast, "Blast API", null),
            type = TokenType.Unsupported("blast", address),
            decimals = 6,
        )

        val normalized = requireNotNull(EvmNetworkCatalog.normalize(marketToken))

        assertEquals(EvmNetworkCatalog.blast, normalized.blockchainType)
        assertEquals(EvmNetworkCatalog.blockchain(EvmNetworkCatalog.blast), normalized.blockchain)
        assertEquals(6, normalized.decimals)
        assertEquals(TokenType.Eip20(address.lowercase()), normalized.type)
    }

    @Test
    fun `EIP-20 lookup includes the legacy market API token type`() {
        val address = "0xA0b86991c6218B36c1d19D4a2e9Eb0cE3606eB48"

        val queries = EvmNetworkCatalog.marketLookupQueries(
            TokenQuery(EvmNetworkCatalog.cronos, TokenType.Eip20(address)),
        )

        assertEquals(TokenType.Eip20(address.lowercase()), queries[0].tokenType)
        assertEquals(
            TokenType.Unsupported("cronos", address.lowercase()),
            queries[1].tokenType,
        )
    }

    @Test
    fun `incomplete native row is replaced with local native metadata`() {
        val marketToken = Token(
            coin = Coin("sei-network", "", ""),
            blockchain = Blockchain(EvmNetworkCatalog.sei, "Sei API", null),
            type = TokenType.Unsupported("native", ""),
            decimals = 0,
        )

        val normalized = requireNotNull(EvmNetworkCatalog.normalize(marketToken))

        assertEquals(TokenType.Native, normalized.type)
        assertEquals(18, normalized.decimals)
        assertEquals("Sei", normalized.coin.name)
        assertEquals("SEI", normalized.coin.code)
        assertEquals(EvmNetworkCatalog.blockchain(EvmNetworkCatalog.sei), normalized.blockchain)
    }

    @Test
    fun `unknown-decimal legacy contracts and Mantle pseudo-native are suppressed`() {
        val unknownDecimals = Token(
            coin = Coin("usd-coin", "USD Coin", "USDC"),
            blockchain = Blockchain(EvmNetworkCatalog.blast, "Blast API", null),
            type = TokenType.Unsupported(
                "blast",
                "0x4300000000000000000000000000000000000003",
            ),
            decimals = 0,
        )
        val mantlePseudoNative = Token(
            coin = Coin("mantle", "Mantle", "MNT"),
            blockchain = Blockchain(EvmNetworkCatalog.mantle, "Mantle API", null),
            type = TokenType.Unsupported(
                "mantle",
                "0xdeaddeaddeaddeaddeaddeaddeaddeaddead0000",
            ),
            decimals = 0,
        )

        assertNull(EvmNetworkCatalog.normalize(unknownDecimals))
        assertNull(EvmNetworkCatalog.normalize(mantlePseudoNative))
        assertTrue(
            EvmNetworkCatalog.overlayFullCoins(
                listOf(FullCoin(unknownDecimals.coin, listOf(unknownDecimals))),
            ).isEmpty(),
        )
    }

    @Test
    fun `unknown-decimal EIP-20 contract is suppressed`() {
        val unknownDecimals = Token(
            coin = Coin("usd-coin", "USD Coin", "USDC"),
            blockchain = Blockchain(EvmNetworkCatalog.blast, "Blast API", null),
            type = TokenType.Eip20("0x4300000000000000000000000000000000000003"),
            decimals = 0,
        )

        assertNull(EvmNetworkCatalog.normalize(unknownDecimals))
    }

    @Test
    fun `Mantle pseudo-native is suppressed when shaped as EIP-20`() {
        val mantlePseudoNative = Token(
            coin = Coin("mantle", "Mantle", "MNT"),
            blockchain = Blockchain(EvmNetworkCatalog.mantle, "Mantle API", null),
            type = TokenType.Eip20("0xdeaddeaddeaddeaddeaddeaddeaddeaddead0000"),
            decimals = 18,
        )

        assertNull(EvmNetworkCatalog.normalize(mantlePseudoNative))
    }

    @Test
    fun `full coin overlay retains market coin and adds shared ETH networks`() {
        val ethereumCoin = Coin(
            uid = "ethereum",
            name = "Ethereum",
            code = "ETH",
            marketCapRank = 2,
            image = "https://example.com/eth.png",
        )
        val ethereumToken = Token(
            coin = ethereumCoin,
            blockchain = Blockchain(BlockchainType.Ethereum, "Ethereum", null),
            type = TokenType.Native,
            decimals = 18,
        )

        val fullCoins = EvmNetworkCatalog.overlayFullCoins(
            marketFullCoins = listOf(FullCoin(ethereumCoin, listOf(ethereumToken))),
            additionalCoinUids = listOf("hyperliquid"),
        )

        val ethereum = fullCoins.first { it.coin.uid == "ethereum" }
        assertEquals(2, ethereum.coin.marketCapRank)
        assertEquals("https://example.com/eth.png", ethereum.coin.image)
        assertEquals(
            setOf(BlockchainType.Ethereum, EvmNetworkCatalog.blast, EvmNetworkCatalog.robinhood),
            ethereum.tokens.map { it.blockchainType }.toSet(),
        )

        val hyperEvm = fullCoins.first { it.coin.uid == "hyperliquid" }
        assertEquals(listOf(EvmNetworkCatalog.hyperEvm), hyperEvm.tokens.map { it.blockchainType })
        assertEquals("HYPE", hyperEvm.coin.code)
    }
}
