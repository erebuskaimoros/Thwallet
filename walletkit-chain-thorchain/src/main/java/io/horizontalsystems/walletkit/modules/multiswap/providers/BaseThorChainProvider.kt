package io.horizontalsystems.walletkit.modules.multiswap.providers

import io.horizontalsystems.bitcoincore.storage.UtxoFilters
import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import io.horizontalsystems.walletkit.core.App
import io.horizontalsystems.walletkit.core.blockTime
import io.horizontalsystems.walletkit.core.derivation
import io.horizontalsystems.walletkit.core.managers.APIClient
import io.horizontalsystems.walletkit.core.nativeTokenQueries
import io.horizontalsystems.walletkit.modules.multiswap.SwapFinalQuote
import io.horizontalsystems.walletkit.modules.multiswap.SwapQuote
import io.horizontalsystems.walletkit.modules.multiswap.providers.ThornodeAPI.Response
import io.horizontalsystems.walletkit.modules.multiswap.sendtransaction.SendTransactionData
import io.horizontalsystems.walletkit.modules.multiswap.sendtransaction.toEvmTransactionData
import io.horizontalsystems.walletkit.modules.multiswap.sendtransaction.SendTransactionSettings
import io.horizontalsystems.walletkit.modules.multiswap.ui.DataFieldRecipient
import io.horizontalsystems.walletkit.modules.multiswap.ui.DataFieldSlippage
import io.horizontalsystems.ethereumkit.contracts.ContractMethod
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.TransactionData
import io.horizontalsystems.marketkit.models.BlockchainType
import io.horizontalsystems.marketkit.models.Token
import io.horizontalsystems.marketkit.models.TokenQuery
import io.horizontalsystems.marketkit.models.TokenType
import io.horizontalsystems.thorchainkit.models.Asset as ThorAsset
import io.horizontalsystems.thorchainkit.models.ThorchainAssetResolver
import kotlinx.coroutines.CancellationException
import retrofit2.http.GET
import retrofit2.http.Query
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Date

abstract class BaseThorChainProvider(
    baseUrl: String,
    internal val affiliate: String?,
    internal val affiliateBps: Int?,
) : IMultiSwapProvider {
    open val streamingInterval: Long = 1
    override val type = SwapProviderType.DEX
    override val requireTerms = false

    // The provider's settlement chain and its native asset — the asset that backs every pool and
    // is never listed in /pools. THORChain/RUNE by default; MayaProvider overrides to Maya/CACAO.
    // Referenced only from runtime functions, so overrides are safe despite init order.
    protected open val settlementBlockchainType: BlockchainType = BlockchainType.Thorchain
    protected open val settlementAsset: String = "THOR.RUNE"

    // THORChain exposes secured assets (btc-btc, eth-eth, …) via /securedassets and makes
    // them swappable through the standard `=:` memo. Maya has none, so it stays false.
    protected open val securedAssetsSupported: Boolean = false

    // Base-unit scale the protocol uses for a quote amount. Every pool asset is quoted at 1e8,
    // regardless of its real decimals (ETH is still 1e8 here, and so are ThorChain secured assets).
    // The lone exception is the settlement asset's own native coin, quoted in its native decimals —
    // 1e8 for THORChain's RUNE, 1e10 for Maya's CACAO. Encoding CACAO at 1e8 sends a 100x-too-small
    // amount and the quote fails with "not enough asset to pay for fees" — i.e. no route.
    private fun protocolDecimals(token: Token): Int =
        if (token.type is TokenType.Native && token.blockchainType == settlementBlockchainType) token.decimals else 8

    companion object {
        // bump to invalidate cached asset maps when the mapping logic changes
        private const val ASSETS_MAP_VERSION = 6
        // Preserves THORChain's live DOGE rate (750k) while bounding downstream size × rate arithmetic.
        private const val MAX_UTXO_GAS_RATE = 1_000_000
        // Ten times the live DOGE threshold (1 DOGE), with ample room for other UTXO chains.
        private const val MAX_UTXO_DUST_THRESHOLD = 1_000_000_000
        private const val MAX_XRP_FEE_DROPS = 100_000L
    }

    protected val thornodeAPI =
        APIClient.retrofit(baseUrl, 60).create(ThornodeAPI::class.java)

    private var assetsMap = mapOf<Token, String>()

    override suspend fun start() {
        assetsMap = SwapProviderCacheHelper.getOrFetch(
            // versioned so cached maps built by older asset-mapping logic are not reused
            providerId = "$id-v$ASSETS_MAP_VERSION",
            deserialize = { it },
            serialize = { it },
            fetch = { fetchAssetsMap() }
        )
    }

    private suspend fun fetchAssetsMap(): Map<Token, String> {
        val assetsMap = mutableMapOf<Token, String>()

        val pools = thornodeAPI.pools().filter { it.status.lowercase() == "available" }
        for (pool in pools) {
            val (assetBlockchainId, assetId) = pool.asset.split(".")
            val blockchainType = thorChainBlockchainType(assetBlockchainId) ?: continue

            when (blockchainType) {
                BlockchainType.ArbitrumOne,
                BlockchainType.Avalanche,
                BlockchainType.BinanceSmartChain,
                BlockchainType.Ethereum,
                BlockchainType.Base,
                    -> {
                    val components = assetId.split("-")

                    val tokenType = if (components.size == 2) {
                        TokenType.Eip20(components[1])
                    } else {
                        TokenType.Native
                    }

                    App.marketKit.token(TokenQuery(blockchainType, tokenType))?.let { token ->
                        assetsMap[token] = pool.asset
                    }
                }

                BlockchainType.BitcoinCash,
                BlockchainType.Bitcoin,
                BlockchainType.Litecoin,
                BlockchainType.Dogecoin,
                BlockchainType.Dash,
                BlockchainType.Zcash,
                BlockchainType.Ripple -> {
                    var nativeTokenQueries = blockchainType.nativeTokenQueries

                    // filter out taproot for ltc
                    if (blockchainType == BlockchainType.Litecoin) {
                        nativeTokenQueries = nativeTokenQueries.filterNot {
                            it.tokenType.derivation == TokenType.Derivation.Bip86
                        }
                    }

                    val tokens = App.marketKit.tokens(nativeTokenQueries)
                    assetsMap.putAll(tokens.map { it to pool.asset })
                }

                BlockchainType.Thorchain -> {
                    val denom = ThorchainAssetResolver.denomFor(ThorAsset.fromString(pool.asset))
                    val tokenType = if (denom == ThorchainAssetResolver.nativeDenom) TokenType.Native else TokenType.ThorchainAsset(denom)

                    App.marketKit.token(TokenQuery(blockchainType, tokenType))?.let { token ->
                        assetsMap[token] = pool.asset
                    }
                }

                else -> Unit
            }
        }

        // The settlement asset (RUNE on THORChain, CACAO on Maya) backs every pool and is never
        // listed in /pools, so add it explicitly — otherwise swaps to/from it show no route.
        App.marketKit.token(TokenQuery(settlementBlockchainType, TokenType.Native))?.let { native ->
            assetsMap[native] = settlementAsset
        }

        // Secured assets (btc-btc, eth-eth, …) live in THORChain's x/bank, not in /pools, but are
        // swappable via the standard `=:` memo. Map only those registered in marketkit — the rest
        // resolve to null and stay non-swap-eligible. Fetched defensively: a /securedassets outage
        // must not discard the pool-based map (which would disable all THORChain swaps).
        if (securedAssetsSupported) {
            try {
                for (securedAsset in thornodeAPI.securedAssets()) {
                    val denom = securedAsset.asset.lowercase()
                    val token = App.marketKit.token(
                        TokenQuery(settlementBlockchainType, TokenType.ThorchainAsset(denom))
                    ) ?: continue
                    assetsMap[token] = securedAsset.asset
                }
            } catch (e: CancellationException) {
                // a cancelled refresh must propagate, not return a partial assetsMap
                throw e
            } catch (_: Throwable) {
                // keep the pool-based map; secured swaps are simply unavailable until next refresh
            }
        }

        return assetsMap
    }

    override fun isSingleTransactionSwap(tokenInBlockchainTypeUid: String, tokenOutBlockchainTypeUid: String) = false

    override fun supports(blockchainType: BlockchainType): Boolean {
        // overriding fun supports(tokenFrom: Token, tokenTo: Token) makes this method redundant
        return true
    }

    override fun supports(tokenFrom: Token, tokenTo: Token): Boolean {
        return assetsMap.contains(tokenFrom) && assetsMap.contains(tokenTo)
    }

    override suspend fun fetchQuote(
        tokenIn: Token,
        tokenOut: Token,
        amountIn: BigDecimal
    ): SwapQuote {
        // A secured-asset output lives on THORChain, and the node refuses to quote a swap whose
        // destination isn't on the target asset's chain ("swap destination address is not the same
        // chain as the target asset"). A null destination is fine for L1/RUNE outputs but fails for
        // secured targets, so resolve the THORChain destination up front for the preview quote too.
        val destination = if (tokenOut.type is TokenType.ThorchainAsset) {
            resolveDestinationAddress(tokenOut)
        } else {
            null
        }
        val quoteSwap = quoteSwap(tokenIn, tokenOut, amountIn, null, destination, refundAddress = null, fromAddress = null)

        val routerAddress = quoteSwap.router?.let { router ->
            try {
                Address(router)
            } catch (_: Throwable) {
                null
            }
        }

        val allowance = routerAddress?.let { EvmSwapHelper.getAllowance(tokenIn, it) }
        val actionApprove = routerAddress?.let {
            EvmSwapHelper.actionApprove(allowance, amountIn, it, tokenIn)
        }

        return SwapQuote(
            amountOut = quoteSwap.expected_amount_out.movePointLeft(protocolDecimals(tokenOut)),
            tokenIn = tokenIn,
            tokenOut = tokenOut,
            amountIn = amountIn,
            actionRequired = actionApprove,
            estimationTime = estimatedTime(quoteSwap, tokenOut)
        )
    }

    private suspend fun quoteSwap(
        tokenIn: Token,
        tokenOut: Token,
        amountIn: BigDecimal,
        slippage: BigDecimal?,
        destinationAddress: String?,
        refundAddress: String?,
        fromAddress: String?
    ): Response.QuoteSwap {
        val assetIn = assetsMap[tokenIn]!!
        val assetOut = assetsMap[tokenOut]!!

        return thornodeAPI.quoteSwap(
            fromAsset = assetIn,
            toAsset = assetOut,
            amount = amountIn.movePointRight(protocolDecimals(tokenIn)).toLong(),
            destination = destinationAddress,
            affiliate = affiliate,
            affiliateBps = affiliateBps,
            streamingInterval = streamingInterval,
            streamingQuantity = 0,
            liquidityToleranceBps = slippage?.movePointRight(2)?.toLong(),
            refundAddress = refundAddress,
            fromAddress = fromAddress
        )
    }

    protected open fun getRefundAddress(tokenIn: Token): String? = null
    protected open fun getFromAddress(tokenIn: Token): String? = null

    private fun validatedUtxoGasRate(quoteSwap: Response.QuoteSwap): Int {
        require(quoteSwap.gas_rate_units?.equals("satsperbyte", ignoreCase = true) == true) {
            "Invalid THORChain UTXO gas rate units"
        }

        val gasRate = quoteSwap.recommended_gas_rate?.toIntOrNull()
        require(gasRate != null && gasRate in 1..MAX_UTXO_GAS_RATE) {
            "Invalid THORChain UTXO gas rate"
        }

        return gasRate
    }

    private fun validatedUtxoMinimumSendAmount(quoteSwap: Response.QuoteSwap): Int {
        val dustThreshold = quoteSwap.dust_threshold?.toIntOrNull()
        require(dustThreshold != null && dustThreshold in 1..MAX_UTXO_DUST_THRESHOLD) {
            "Invalid THORChain UTXO dust threshold"
        }

        return Math.addExact(dustThreshold, 1)
    }

    private fun validatedXrpFeeDrops(quoteSwap: Response.QuoteSwap): Long {
        require(quoteSwap.gas_rate_units?.equals("drop", ignoreCase = true) == true) {
            "Invalid THORChain XRP gas rate units"
        }
        val fee = quoteSwap.recommended_gas_rate?.toLongOrNull()
        require(fee != null && fee in 1..MAX_XRP_FEE_DROPS) {
            "Invalid THORChain XRP fee"
        }
        return fee
    }

    private fun validatedXrpMinimumSendAmount(quoteSwap: Response.QuoteSwap): Long {
        val dust = quoteSwap.dust_threshold?.toLongOrNull()
        require(dust != null && dust in 1..MAX_UTXO_DUST_THRESHOLD.toLong()) {
            "Invalid THORChain XRP dust threshold"
        }
        return Math.addExact(dust, 1L)
    }

    protected open suspend fun resolveDestinationAddress(tokenOut: Token): String {
        return SwapHelper.getReceiveAddressForToken(tokenOut)
    }

    private fun estimatedTime(quoteSwap: Response.QuoteSwap, tokenOut: Token): Long {
        val inbound = quoteSwap.inbound_confirmation_seconds ?: 0L
        val swap = quoteSwap.streaming_swap_seconds ?: 6L
        val outbound = (quoteSwap.outbound_delay_seconds ?: 6L) + (tokenOut.blockchainType.blockTime ?: 6L)
        return inbound + swap + outbound
    }

    override suspend fun fetchFinalQuote(
        tokenIn: Token,
        tokenOut: Token,
        amountIn: BigDecimal,
        sendTransactionSettings: SendTransactionSettings?,
        swapQuote: SwapQuote,
        recipient: io.horizontalsystems.walletkit.entities.Address?,
        slippage: BigDecimal,
    ): SwapFinalQuote {
        val destination = recipient?.hex ?: resolveDestinationAddress(tokenOut)
        val refundAddress = getRefundAddress(tokenIn)
        val quoteSwap = quoteSwap(tokenIn, tokenOut, amountIn, slippage, destination, refundAddress, getFromAddress(tokenIn))
        if (tokenIn.blockchainType == BlockchainType.Ripple) {
            val pools = thornodeAPI.pools().filter { it.status.equals("available", ignoreCase = true) }
            validatedThorXrpInbound(
                quote = quoteSwap,
                inboundAddresses = thornodeAPI.inboundAddresses(),
                nowEpochSeconds = Date().time / 1_000,
            )
            validatedThorXrpSwapCommitment(
                quote = quoteSwap,
                expectedAsset = checkNotNull(assetsMap[tokenOut]),
                availablePoolAssets = pools.map { it.asset },
                expectedDestination = destination,
                expectedRefundAddress = refundAddress,
                approvedSlippageBps = slippage.movePointRight(2).longValueExact(),
                requestedStreamingInterval = streamingInterval,
            )
        } else {
            validatedThorSwapMemoDestination(quoteSwap.memo, destination)
        }

        val amountOut = quoteSwap.expected_amount_out.movePointLeft(protocolDecimals(tokenOut))

        val amountOutMin = amountOut.subtract(amountOut.multiply(slippage.movePointLeft(2)))

        val fields = buildList {
            recipient?.let {
                add(DataFieldRecipient(it))
            }
            DataFieldSlippage.getField(slippage)?.let {
                add(it)
            }
        }

        return SwapFinalQuote(
            tokenIn = tokenIn,
            tokenOut = tokenOut,
            amountIn = amountIn,
            amountOut = amountOut,
            amountOutMin = amountOutMin,
            sendTransactionData = getSendTransactionData(
                tokenIn,
                amountIn,
                quoteSwap,
                tokenOut
            ),
            priceImpact = null,
            fields = fields,
            estimatedTime = estimatedTime(quoteSwap, tokenOut),
            slippage = slippage,
            fromAsset = assetsMap[tokenIn],
            toAsset = assetsMap[tokenOut],
            depositAddress = quoteSwap.inbound_address,
        )
    }

    protected open suspend fun getSendTransactionData(
        tokenIn: Token,
        amountIn: BigDecimal,
        quoteSwap: Response.QuoteSwap,
        tokenOut: Token,
    ): SendTransactionData {
        val memo = quoteSwap.memo

        val router = quoteSwap.router

        if (tokenIn.blockchainType == settlementBlockchainType) {
            // native input (RUNE/CACAO): MsgDeposit when the quote has no inbound vault (the
            // settlement chain itself), MsgSend to the vault otherwise (e.g. Maya's THORChain inbound)
            return when (val inboundAddress = quoteSwap.inbound_address) {
                null -> SendTransactionData.Thorchain.Deposit(
                    asset = assetsMap[tokenIn]!!,
                    amount = amountIn,
                    memo = memo,
                )

                else -> SendTransactionData.Thorchain.Send(
                    address = inboundAddress,
                    amount = amountIn,
                    memo = memo,
                )
            }
        }

        val inboundAddress = checkNotNull(quoteSwap.inbound_address)

        return when (tokenIn.blockchainType) {
            BlockchainType.ArbitrumOne,
            BlockchainType.Avalanche,
            BlockchainType.BinanceSmartChain,
            BlockchainType.Ethereum,
            BlockchainType.Base,
                -> {
                val transactionData = when (val tokenType = tokenIn.type) {
                    TokenType.Native -> {
                        TransactionData(
                            Address(inboundAddress),
                            amountIn.movePointRight(tokenIn.decimals).toBigInteger(),
                            memo.toByteArray()
                        )
                    }

                    is TokenType.Eip20 -> {
                        val method = DepositWithExpiryMethod(
                            Address(inboundAddress),
                            Address(tokenType.address),
                            amountIn.movePointRight(tokenIn.decimals).toBigInteger(),
                            memo,
                            BigInteger.valueOf(Date().time / 1000 + 1 * 60 * 60)
                        )

                        checkNotNull(router)

                        TransactionData(
                            Address(router),
                            BigInteger.ZERO,
                            method.encodedABI()
                        )
                    }

                    else -> throw IllegalArgumentException()
                }

                SendTransactionData.Evm(
                    transactionData = transactionData.toEvmTransactionData(),
                    gasLimit = null,
                )
            }

            BlockchainType.BitcoinCash,
            BlockchainType.Bitcoin,
            BlockchainType.Litecoin,
            BlockchainType.Dash,
                -> {
                SendTransactionData.Btc(
                    address = inboundAddress,
                    memo = memo,
                    amount = amountIn,
                    recommendedGasRate = validatedUtxoGasRate(quoteSwap),
                    minimumSendAmount = validatedUtxoMinimumSendAmount(quoteSwap),
                    changeToFirstInput = true,
                    utxoFilters = UtxoFilters(
                        scriptTypes = listOf(ScriptType.P2PKH, ScriptType.P2WPKHSH, ScriptType.P2WPKH),
                        maxOutputsCountForInputs = 10
                    ),
                )
            }

            BlockchainType.Dogecoin -> {
                SendTransactionData.Btc(
                    address = inboundAddress,
                    memo = memo,
                    amount = amountIn,
                    recommendedGasRate = validatedUtxoGasRate(quoteSwap),
                    minimumSendAmount = validatedUtxoMinimumSendAmount(quoteSwap),
                    changeToFirstInput = true,
                    utxoFilters = UtxoFilters(
                        scriptTypes = listOf(ScriptType.P2PKH),
                        maxOutputsCountForInputs = 10,
                    ),
                )
            }

            BlockchainType.Ripple -> SendTransactionData.Xrp(
                address = inboundAddress,
                amount = amountIn,
                destinationTag = null,
                memo = memo,
                recommendedFeeDrops = validatedXrpFeeDrops(quoteSwap),
                minimumSendAmountDrops = validatedXrpMinimumSendAmount(quoteSwap),
                validUntilEpochSeconds = quoteSwap.expiry,
            )

            else -> throw IllegalArgumentException()
        }
    }
}

internal fun validatedThorSwapMemoDestination(memo: String, expectedDestination: String): String {
    val components = memo.split(':')
    require(components.size >= 3) { "Invalid THORChain swap memo" }
    require(components[0].equals("swap", ignoreCase = true) ||
            components[0].equals("s", ignoreCase = true) ||
            components[0] == "=") {
        "Invalid THORChain swap memo function"
    }

    // THORChain optionally appends a custom refund address to DESTADDR as DESTADDR/REFUNDADDR.
    // The output destination remains the first component and must match the address committed by
    // the user. Non-EVM address formats are case-sensitive, so never lowercase this boundary.
    val destination = components[2].substringBefore('/')
    require(destination.isNotEmpty() && destination == expectedDestination) {
        "THORChain swap memo destination mismatch"
    }
    return destination
}

private val THORCHAIN_ASSET_SHORT_CODES = mapOf(
    "THOR.RUNE" to "r",
    "BTC.BTC" to "b",
    "ETH.ETH" to "e",
    "GAIA.ATOM" to "g",
    "DOGE.DOGE" to "d",
    "LTC.LTC" to "l",
    "BCH.BCH" to "c",
    "AVAX.AVAX" to "a",
    "BSC.BNB" to "s",
    "BASE.ETH" to "f",
    "TRON.TRX" to "tr",
    "XRP.XRP" to "x",
    "SOL.SOL" to "o",
    "TAO.TAO" to "ta",
    "ZEC.ZEC" to "z",
    "XMR.XMR" to "m",
    "POL.POL" to "p",
    "SUI.SUI" to "u",
    "DOT.DOT" to "do",
    "ADA.ADA" to "ad",
)

private data class FuzzyPoolAsset(
    val full: String,
    val chain: String,
    val ticker: String,
    val address: String,
)

private fun fuzzyPoolAsset(asset: String): FuzzyPoolAsset? {
    val chainSeparator = asset.indexOf('.')
    val addressSeparator = asset.indexOf('-', startIndex = chainSeparator + 1)
    if (chainSeparator <= 0 || addressSeparator <= chainSeparator + 1 || addressSeparator == asset.lastIndex) {
        return null
    }
    return FuzzyPoolAsset(
        full = asset,
        chain = asset.substring(0, chainSeparator),
        ticker = asset.substring(chainSeparator + 1, addressSeparator),
        address = asset.substring(addressSeparator + 1),
    )
}

private fun expectedThorChainMemoAsset(
    expectedAsset: String,
    availablePoolAssets: List<String>,
): String {
    THORCHAIN_ASSET_SHORT_CODES[expectedAsset.uppercase()]?.let { return it }
    val expected = fuzzyPoolAsset(expectedAsset) ?: return expectedAsset
    require(availablePoolAssets.any { it.equals(expectedAsset, ignoreCase = true) }) {
        "Expected THORChain output pool is unavailable"
    }

    val otherAddresses = availablePoolAssets.mapNotNull(::fuzzyPoolAsset)
        .filter {
            !it.full.equals(expected.full, ignoreCase = true) &&
                it.chain.equals(expected.chain, ignoreCase = true) &&
                it.ticker.equals(expected.ticker, ignoreCase = true)
        }
        .map { it.address }

    val prefix = "${expected.chain}.${expected.ticker}"
    if (otherAddresses.isEmpty()) return prefix

    for (index in expected.address.lastIndex downTo 1) {
        val suffix = expected.address.substring(index)
        if (otherAddresses.none { it.endsWith(suffix, ignoreCase = true) }) {
            return "$prefix-$suffix"
        }
    }
    return expectedAsset
}

internal fun validatedThorXrpSwapCommitment(
    quote: Response.QuoteSwap,
    expectedAsset: String,
    availablePoolAssets: List<String>,
    expectedDestination: String,
    expectedRefundAddress: String?,
    approvedSlippageBps: Long,
    requestedStreamingInterval: Long,
): String {
    require(approvedSlippageBps in 1..9_999) { "Invalid THORChain liquidity tolerance" }
    require(requestedStreamingInterval in 0..43_200) { "Invalid THORChain streaming interval" }
    require(quote.fees.affiliate.compareTo(BigDecimal.ZERO) == 0) {
        "THORChain XRP quote contains an affiliate fee"
    }
    require(quote.fees.asset?.equals(expectedAsset, ignoreCase = true) == true) {
        "THORChain XRP fee asset mismatch"
    }

    val components = quote.memo.split(':')
    require(components.size == 4) { "Invalid THORChain XRP swap memo shape" }
    require(components[0] == "=") { "Invalid THORChain XRP swap memo function" }

    val expectedMemoAsset = expectedThorChainMemoAsset(expectedAsset, availablePoolAssets)
    require(
        components[1].equals(expectedAsset, ignoreCase = true) ||
            components[1].equals(expectedMemoAsset, ignoreCase = true)
    ) { "THORChain XRP swap memo asset mismatch" }

    val destinations = components[2].split('/', limit = 2)
    require(destinations.firstOrNull() == expectedDestination) {
        "THORChain XRP swap memo destination mismatch"
    }
    if (expectedRefundAddress == null) {
        require(destinations.size == 1) { "Unexpected THORChain XRP refund address" }
    } else {
        require(destinations.size == 2 && destinations[1] == expectedRefundAddress) {
            "THORChain XRP refund address mismatch"
        }
    }

    val expectedAmountOut = quote.expected_amount_out.toBigIntegerExact()
    require(expectedAmountOut.signum() > 0) { "Invalid THORChain expected output" }
    val expectedLimit = expectedAmountOut
        .multiply(BigInteger.valueOf(10_000 - approvedSlippageBps))
        .divide(BigInteger.valueOf(10_000))
    require(expectedLimit.signum() > 0) { "Invalid THORChain XRP trade target" }

    val maxStreamingQuantity = quote.max_streaming_quantity
    require(maxStreamingQuantity != null && maxStreamingQuantity in 1..1_000_000) {
        "Invalid THORChain streaming quantity"
    }
    val expectedTradeTarget = when {
        // Thornode preserves an explicitly requested positive interval and auto quantity as /I/0.
        requestedStreamingInterval > 0 -> "$expectedLimit/$requestedStreamingInterval/0"
        // Rapid auto-streaming rewrites quantity only when more than one sub-swap is useful.
        maxStreamingQuantity > 1 -> "$expectedLimit/0/$maxStreamingQuantity"
        else -> expectedLimit.toString()
    }
    require(components[3] == expectedTradeTarget) {
        "THORChain XRP trade target or streaming policy mismatch"
    }

    return expectedDestination
}

internal fun thorChainBlockchainType(chain: String): BlockchainType? = when (chain) {
    "ARB" -> BlockchainType.ArbitrumOne
    "AVAX" -> BlockchainType.Avalanche
    "BASE" -> BlockchainType.Base
    "BCH" -> BlockchainType.BitcoinCash
    "BSC" -> BlockchainType.BinanceSmartChain
    "BTC" -> BlockchainType.Bitcoin
    "DASH" -> BlockchainType.Dash
    "DOGE" -> BlockchainType.Dogecoin
    "ETH" -> BlockchainType.Ethereum
    "LTC" -> BlockchainType.Litecoin
    "THOR" -> BlockchainType.Thorchain
    "XRP" -> BlockchainType.Ripple
    "ZEC" -> BlockchainType.Zcash
    else -> null
}

internal fun validatedThorXrpInbound(
    quote: Response.QuoteSwap,
    inboundAddresses: List<Response.InboundAddress>,
    nowEpochSeconds: Long,
): String {
    val current = inboundAddresses.singleOrNull { it.chain.equals("XRP", ignoreCase = true) }
        ?: throw IllegalArgumentException("Missing unique THORChain XRP inbound")
    require(!current.halted) { "THORChain XRP inbound is halted" }
    require(quote.router == null && current.router == null) { "THORChain XRP quote must not use a router" }
    require(quote.expiry > Math.addExact(nowEpochSeconds, 30)) { "THORChain XRP quote is expired" }
    require(quote.inbound_address == current.address) { "THORChain XRP inbound address mismatch" }
    val quotedFee = quote.recommended_gas_rate?.toLongOrNull()
    val currentFee = current.gas_rate.toLongOrNull()
    require(quotedFee != null && currentFee != null && quotedFee == currentFee) {
        "THORChain XRP fee mismatch"
    }
    require(current.dust_threshold != null && quote.dust_threshold == current.dust_threshold) {
        "THORChain XRP dust threshold mismatch"
    }
    return current.address
}

interface ThornodeAPI {
    @GET("pools")
    suspend fun pools(): List<Response.Pool>

    @GET("securedassets")
    suspend fun securedAssets(): List<Response.SecuredAsset>

    @GET("inbound_addresses")
    suspend fun inboundAddresses(): List<Response.InboundAddress>

    @GET("quote/swap")
    suspend fun quoteSwap(
        @Query("from_asset") fromAsset: String,
        @Query("to_asset") toAsset: String,
        @Query("amount") amount: Long,
        @Query("destination") destination: String?,
        @Query("affiliate") affiliate: String?,
        @Query("affiliate_bps") affiliateBps: Int?,
        @Query("streaming_interval") streamingInterval: Long,
        @Query("streaming_quantity") streamingQuantity: Long,
        @Query("liquidity_tolerance_bps") liquidityToleranceBps: Long?,
        @Query("refund_address") refundAddress: String?,
        @Query("from_address") fromAddress: String?,

    ): Response.QuoteSwap

    object Response {
        data class QuoteSwap(
            // absent when the input asset is THORChain-native (MsgDeposit, no inbound vault)
            val inbound_address: String?,
//  "inbound_confirmation_blocks": 1,
            val inbound_confirmation_seconds: Long?,
//  "outbound_delay_blocks": 179,
            val outbound_delay_seconds: Long?,
            val fees: Fees,
//  "fees": {
//    "asset": "ETH.ETH",
//    "affiliate": "0",
//    "outbound": "54840",
//    "liquidity": "2037232",
//    "total": "2092072",
//    "slippage_bps": 9,
//    "total_bps": 10
//  },
            val router: String?,
//  "slippage_bps": 41,
//  "streaming_slippage_bps": 9,
            val expiry: Long,
//  "warning": "Do not cache this response. Do not send funds after the expiry.",
//  "notes": "First output should be to inbound_address, second output should be change back to self, third output should be OP_RETURN, limited to 80 bytes. Do not send below the dust threshold. Do not use exotic spend scripts, locks or address formats (P2WSH with Bech32 address format preferred).",
            val dust_threshold: String?,
//  "recommended_min_amount_in": "10760",
            // absent when the input asset is THORChain-native
            val recommended_gas_rate: String?,
            val gas_rate_units: String?,
            val memo: String,
            val expected_amount_out: BigDecimal,
            val max_streaming_quantity: Long? = null,
//  "expected_amount_out_streaming": "2035299208",
//  "max_streaming_quantity": 8,
//  "streaming_swap_blocks": 7,
            val streaming_swap_seconds: Long?,
            val total_swap_seconds: Long?,
        ) {
            data class Fees(
                val asset: String? = null,
                val affiliate: BigDecimal,
                val outbound: BigDecimal,
                val liquidity: BigDecimal,
                val total: BigDecimal,
            )
        }

        data class Pool(
            val asset: String,
            val status: String,
        )

        // /securedassets returns a flat array; only `asset` (e.g. "BTC-BTC") is needed to
        // resolve the token and use it as the swap `from_asset`/`to_asset`.
        data class SecuredAsset(
            val asset: String,
        )

        data class InboundAddress(
            val chain: String,
            val address: String,
            val router: String?,
            val halted: Boolean,
            val gas_rate: String,
            val dust_threshold: String?,
            val shielded_memo_config: ShieldedMemoConfig?,
        )

        data class ShieldedMemoConfig(
            val unified_address: String,
            val uivk: String,
            val enabled: Boolean,
        )
    }
}

class DepositWithExpiryMethod(
    val inboundAddress: Address,
    val asset: Address,
    val amount: BigInteger,
    val memo: String,
    val expiry: BigInteger,
) : ContractMethod() {
    override val methodSignature = "depositWithExpiry(address,address,uint256,string,uint256)"
    override fun getArguments() = listOf(inboundAddress, asset, amount, memo, expiry)
}
