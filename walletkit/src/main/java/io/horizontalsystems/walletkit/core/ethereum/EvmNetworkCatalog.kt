package io.horizontalsystems.walletkit.core.ethereum

import io.horizontalsystems.marketkit.models.Blockchain
import io.horizontalsystems.marketkit.models.BlockchainType
import io.horizontalsystems.marketkit.models.Coin
import io.horizontalsystems.marketkit.models.FullCoin
import io.horizontalsystems.marketkit.models.Token
import io.horizontalsystems.marketkit.models.TokenQuery
import io.horizontalsystems.marketkit.models.TokenType

/**
 * EVM mainnets supported by Thwallet but not represented by first-class BlockchainType values in
 * market-kit.  Robinhood Chain left this catalog when market-kit gained
 * BlockchainType.RobinhoodChain under the same `robinhood` UID.  Keep their UIDs aligned with the market API's blockchain/platform UIDs so synced
 * market data can be overlaid rather than copied into a fork of market-kit.
 */
object EvmNetworkCatalog {
    private const val MANTLE_NATIVE_PSEUDO_ADDRESS =
        "0xdeaddeaddeaddeaddeaddeaddeaddeaddead0000"

    val cronos: BlockchainType = BlockchainType.Unsupported("cronos")
    val blast: BlockchainType = BlockchainType.Unsupported("blast")
    val mantle: BlockchainType = BlockchainType.Unsupported("mantle")
    val sei: BlockchainType = BlockchainType.Unsupported("sei-network")
    val hyperEvm: BlockchainType = BlockchainType.Unsupported("hyperevm")

    private data class Network(
        val chainId: Int,
        val blockchain: Blockchain,
        val nativeCoin: Coin,
    ) {
        val nativeToken = Token(
            coin = nativeCoin,
            blockchain = blockchain,
            type = TokenType.Native,
            decimals = 18,
        )
    }

    private val ethereumCoin = Coin(
        uid = "ethereum",
        name = "Ethereum",
        code = "ETH",
        coinGeckoId = "ethereum",
    )

    private val networks = listOf(
        Network(
            chainId = 25,
            blockchain = Blockchain(cronos, "Cronos", "https://explorer.cronos.com/address/\$ref"),
            nativeCoin = Coin(
                uid = "crypto-com-chain",
                name = "Cronos",
                code = "CRO",
                coinGeckoId = "crypto-com-chain",
            ),
        ),
        Network(
            chainId = 81457,
            blockchain = Blockchain(blast, "Blast", "https://blastscan.io/address/\$ref"),
            nativeCoin = ethereumCoin,
        ),
        Network(
            chainId = 5000,
            blockchain = Blockchain(mantle, "Mantle", "https://mantlescan.xyz/address/\$ref"),
            nativeCoin = Coin(
                uid = "mantle",
                name = "Mantle",
                code = "MNT",
                coinGeckoId = "mantle",
            ),
        ),
        Network(
            chainId = 1329,
            blockchain = Blockchain(sei, "Sei EVM", "https://seiscan.io/address/\$ref"),
            nativeCoin = Coin(
                uid = "sei-network",
                name = "Sei",
                code = "SEI",
                coinGeckoId = "sei-network",
            ),
        ),
        Network(
            chainId = 999,
            blockchain = Blockchain(hyperEvm, "HyperEVM", "https://hyperevmscan.io/address/\$ref"),
            nativeCoin = Coin(
                uid = "hyperliquid",
                name = "Hyperliquid",
                code = "HYPE",
                coinGeckoId = "hyperliquid",
            ),
        ),
    )

    private val networkByType = networks.associateBy { it.blockchain.type }
    private val networkByUid = networks.associateBy { it.blockchain.uid }

    val blockchainTypes: List<BlockchainType> = networks.map { it.blockchain.type }
    val blockchains: List<Blockchain> = networks.map { it.blockchain }
    val nativeTokens: List<Token> = networks.map { it.nativeToken }

    fun chainId(type: BlockchainType): Int? = networkByType[type]?.chainId

    fun blockchain(type: BlockchainType): Blockchain? = networkByType[type]?.blockchain

    fun blockchain(uid: String): Blockchain? = networkByUid[uid]?.blockchain

    fun nativeToken(type: BlockchainType): Token? = networkByType[type]?.nativeToken

    internal fun localCoinUids(filter: String): Set<String> {
        val normalizedFilter = filter.trim()
        return networks
            .filter { network ->
                normalizedFilter.isBlank() ||
                    network.nativeCoin.uid.contains(normalizedFilter, ignoreCase = true) ||
                    network.nativeCoin.name.contains(normalizedFilter, ignoreCase = true) ||
                    network.nativeCoin.code.contains(normalizedFilter, ignoreCase = true) ||
                    network.blockchain.uid.contains(normalizedFilter, ignoreCase = true) ||
                    network.blockchain.name.contains(normalizedFilter, ignoreCase = true)
            }
            .mapTo(linkedSetOf()) { it.nativeCoin.uid }
    }

    /** Queries market-kit's legacy per-chain token type as a fallback for an EIP-20 query. */
    internal fun marketLookupQueries(query: TokenQuery): List<TokenQuery> {
        val network = networkByType[query.blockchainType] ?: return listOf(query)
        val tokenType = query.tokenType
        if (tokenType !is TokenType.Eip20) return listOf(query)

        val address = tokenType.address.lowercase()
        return listOf(
            TokenQuery(network.blockchain.type, TokenType.Eip20(address)),
            TokenQuery(
                network.blockchain.type,
                TokenType.Unsupported(network.blockchain.uid, address),
            ),
        )
    }

    internal fun canonicalQuery(query: TokenQuery): TokenQuery {
        val tokenType = query.tokenType
        return if (query.blockchainType in networkByType && tokenType is TokenType.Eip20) {
            TokenQuery(query.blockchainType, TokenType.Eip20(tokenType.address.lowercase()))
        } else {
            query
        }
    }

    /**
     * Replaces incomplete market-kit chain metadata, repairs native rows with missing decimals,
     * and translates legacy chain-specific contract rows to the EIP-20 type used by walletkit.
     */
    internal fun normalize(token: Token): Token? {
        val network = networkByType[token.blockchainType] ?: return token
        val tokenType = token.type
        val contractAddress = when (tokenType) {
            is TokenType.Eip20 -> tokenType.address
            is TokenType.Unsupported -> tokenType.reference
            else -> null
        }?.takeIf { it.isEvmContractAddress() }

        if (contractAddress != null) {
            // market-kit currently publishes Mantle's native MNT as a contract-shaped row.
            // The catalog-native token below is the authoritative representation.
            if (
                network.blockchain.type == mantle &&
                contractAddress.equals(MANTLE_NATIVE_PSEUDO_ADDRESS, ignoreCase = true)
            ) {
                return null
            }

            // A null decimals value is materialized as zero by market-kit. Enabling such a row
            // could construct a transaction at the wrong scale, so leave it unavailable until
            // the API supplies authoritative decimals or the user adds it on-chain.
            if (token.decimals <= 0) return null
        }

        val coin = if (token.coin.uid == network.nativeCoin.uid) {
            token.coin.withFallback(network.nativeCoin)
        } else {
            token.coin
        }

        val isNativeRow = tokenType is TokenType.Native ||
            (tokenType is TokenType.Unsupported && tokenType.reference.isBlank())
        if (coin.uid == network.nativeCoin.uid && isNativeRow) {
            return network.nativeToken.copy(coin = coin)
        }

        val normalizedType = contractAddress
            ?.let { TokenType.Eip20(it.lowercase()) }
            ?: tokenType

        return token.copy(
            coin = coin,
            blockchain = network.blockchain,
            type = normalizedType,
        )
    }

    /** Adds/replaces the catalog's native tokens while retaining market-kit's richer Coin metadata. */
    internal fun overlayFullCoins(
        marketFullCoins: List<FullCoin>,
        additionalCoinUids: Collection<String> = emptyList(),
    ): List<FullCoin> {
        val fullCoinsByUid = linkedMapOf<String, FullCoin>()

        marketFullCoins.forEach { fullCoin ->
            val normalizedTokens = fullCoin.tokens.mapNotNull(::normalize)
            fullCoinsByUid[fullCoin.coin.uid] = FullCoin(
                coin = fullCoin.coin,
                tokens = normalizedTokens.distinctBy { canonicalQuery(it.tokenQuery) },
            )
        }

        val includedCoinUids = fullCoinsByUid.keys + additionalCoinUids
        networks
            .filter { it.nativeCoin.uid in includedCoinUids }
            .forEach { network ->
                val existing = fullCoinsByUid[network.nativeCoin.uid]
                val coin = existing?.coin?.withFallback(network.nativeCoin) ?: network.nativeCoin
                val localToken = network.nativeToken.copy(coin = coin)
                val tokens = existing?.tokens.orEmpty()
                    .filterNot { canonicalQuery(it.tokenQuery) == localToken.tokenQuery }
                    .plus(localToken)

                fullCoinsByUid[coin.uid] = FullCoin(coin, tokens)
            }

        return fullCoinsByUid.values.filter { it.tokens.isNotEmpty() }
    }

    internal fun overlayCoins(marketCoins: List<Coin>): List<Coin> {
        val coinsByUid = marketCoins.associateByTo(linkedMapOf()) { it.uid }
        networks.forEach { network ->
            val marketCoin = coinsByUid[network.nativeCoin.uid]
            coinsByUid[network.nativeCoin.uid] = marketCoin?.withFallback(network.nativeCoin)
                ?: network.nativeCoin
        }
        return coinsByUid.values.toList()
    }

    private fun Coin.withFallback(fallback: Coin): Coin = Coin(
        uid = uid,
        name = name.ifBlank { fallback.name },
        code = code.ifBlank { fallback.code },
        marketCapRank = marketCapRank ?: fallback.marketCapRank,
        coinGeckoId = coinGeckoId ?: fallback.coinGeckoId,
        image = image ?: fallback.image,
    )

    private fun String.isEvmContractAddress(): Boolean =
        length == 42 && startsWith("0x", ignoreCase = true) && drop(2).all { it.isHexDigitValue() }

    private fun Char.isHexDigitValue(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
