package io.horizontalsystems.walletkit.core.managers

import io.horizontalsystems.walletkit.core.providers.IAppConfigProvider
import io.horizontalsystems.walletkit.entities.EvmSyncSource
import io.horizontalsystems.ethereumkit.models.RpcSource
import io.horizontalsystems.ethereumkit.models.TransactionSource
import io.horizontalsystems.marketkit.models.BlockchainType
import io.horizontalsystems.walletkit.core.ethereum.EvmNetworkCatalog

// Maps the kit-free sync-source entity to ethereum-kit types at the kit boundary.
val EvmSyncSource.rpcSource: RpcSource
    get() = if (isWebSocket) {
        RpcSource.WebSocket(uri, auth)
    } else {
        RpcSource.Http(uris, auth)
    }

fun evmTransactionSource(blockchainType: BlockchainType, appConfigProvider: IAppConfigProvider): TransactionSource =
    when (blockchainType) {
        BlockchainType.Ethereum -> TransactionSource.ethereum(appConfigProvider.etherscanApiKey)
        BlockchainType.BinanceSmartChain -> TransactionSource.binance(appConfigProvider.bscscanApiKey)
        BlockchainType.Avalanche -> TransactionSource.avalanche(appConfigProvider.bscscanApiKey)
        BlockchainType.Optimism -> TransactionSource.optimism(appConfigProvider.bscscanApiKey)
        BlockchainType.Base -> TransactionSource.base(appConfigProvider.bscscanApiKey)
        BlockchainType.Polygon -> TransactionSource.polygon(appConfigProvider.etherscanApiKey)
        BlockchainType.ArbitrumOne -> TransactionSource.arbitrumOne(appConfigProvider.etherscanApiKey)
        BlockchainType.Gnosis -> TransactionSource.gnosis(appConfigProvider.etherscanApiKey)
        BlockchainType.Fantom -> TransactionSource.fantom(appConfigProvider.etherscanApiKey)
        BlockchainType.ZkSync -> TransactionSource.zkSync(appConfigProvider.otherScanApiKey)
        EvmNetworkCatalog.cronos -> TransactionSource.cronos()
        EvmNetworkCatalog.blast -> etherscanV2(
            "blastscan.io",
            "https://blastscan.io",
            appConfigProvider.etherscanApiKey,
        )
        EvmNetworkCatalog.mantle -> etherscanV2(
            "mantlescan.xyz",
            "https://mantlescan.xyz",
            appConfigProvider.etherscanApiKey,
        )
        EvmNetworkCatalog.sei -> etherscanV2(
            "seiscan.io",
            "https://seiscan.io",
            appConfigProvider.etherscanApiKey,
        )
        EvmNetworkCatalog.hyperEvm -> etherscanV2(
            "hyperevmscan.io",
            "https://hyperevmscan.io",
            appConfigProvider.etherscanApiKey,
        )
        EvmNetworkCatalog.robinhood -> TransactionSource(
            "robinhoodchain.blockscout.com",
            TransactionSource.SourceType.Etherscan(
                "https://robinhoodchain.blockscout.com/",
                "https://robinhoodchain.blockscout.com",
                listOf(""),
            ),
        )
        else -> throw IllegalArgumentException("No transaction source for ${blockchainType.uid}")
    }

private fun etherscanV2(name: String, explorerUrl: String, apiKeys: List<String>) =
    TransactionSource(
        name,
        TransactionSource.SourceType.Etherscan(
            "https://api.etherscan.io/v2/",
            explorerUrl,
            apiKeys,
        ),
    )
