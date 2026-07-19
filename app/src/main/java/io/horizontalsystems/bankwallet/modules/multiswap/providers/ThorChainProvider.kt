package io.horizontalsystems.bankwallet.modules.multiswap.providers

object ThorChainProvider : BaseThorChainProvider(
    baseUrl = "https://gateway.liquify.com/chain/thorchain_api/thorchain/",
    // Retrofit omits both nullable query parameters, so THORChain builds the
    // returned transaction memo without a wallet affiliate fee.
    affiliate = null,
    affiliateBps = null,
) {
    override val streamingInterval: Long = 0
    override val id = "thorchain"
    override val title = "THORChain"
    override val riskLevel = RiskLevel.EXCELLENT
}
