package io.horizontalsystems.walletkit.modules.multiswap.providers

object ThorChainProvider : BaseThorChainProvider(
    baseUrl = "https://gateway.liquify.com/chain/thorchain_api/thorchain/",
    // Retrofit omits both nullable query parameters, so THORChain builds the
    // returned transaction memo without a wallet affiliate fee.
    affiliate = null,
    affiliateBps = null,
) {
    override val streamingInterval: Long = 0
    override val id = THORCHAIN_PROVIDER_ID
    override val title = "THORChain"
    override val securedAssetsSupported = true
}
