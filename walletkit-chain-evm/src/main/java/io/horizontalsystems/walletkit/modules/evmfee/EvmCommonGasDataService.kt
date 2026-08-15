package io.horizontalsystems.walletkit.modules.evmfee

import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.core.rollup.L1FeeProvider
import io.horizontalsystems.ethereumkit.core.rollup.MantleFeeProvider
import io.horizontalsystems.ethereumkit.models.GasPrice
import io.horizontalsystems.ethereumkit.models.TransactionData
import io.horizontalsystems.marketkit.models.BlockchainType
import io.horizontalsystems.walletkit.core.ethereum.EvmNetworkCatalog
import io.reactivex.Single
import java.math.BigInteger

open class EvmCommonGasDataService(
    protected val evmKit: EthereumKit,
    protected var predefinedGasLimit: Long? = null
) {

    internal fun setPredefinedGasLimit(gasLimit: Long?) {
        predefinedGasLimit = gasLimit
    }

    open fun estimatedGasDataAsync(gasPrice: GasPrice, transactionData: TransactionData, stubAmount: BigInteger? = null): Single<GasData> {
        val explicitGasLimit = predefinedGasLimit
        if (explicitGasLimit != null) {
            return Single.just(GasData(gasLimit = explicitGasLimit, gasPrice = gasPrice))
        }

        val surchargeRequired = transactionData.input.isNotEmpty()

        val stubTransactionData = if (stubAmount != null)  {
            TransactionData(transactionData.to, BigInteger.ONE, transactionData.input)
        } else {
            transactionData
        }

        return evmKit.estimateGas(stubTransactionData, gasPrice)
            .onErrorResumeNext {
                evmKit.estimateGas(stubTransactionData)
            }
            .map { estimatedGasLimit ->
                val gasLimit =
                    if (surchargeRequired) EvmFeeModule.surcharged(estimatedGasLimit) else estimatedGasLimit
                GasData(
                    gasLimit = gasLimit,
                    estimatedGasLimit = estimatedGasLimit,
                    gasPrice = gasPrice
                )
            }
    }

    companion object {
        fun instance(evmKit: EthereumKit, blockchainType: BlockchainType, gasLimit: Long? = null): EvmCommonGasDataService {
            if (blockchainType == EvmNetworkCatalog.mantle) {
                return EvmMantleGasDataService(evmKit, MantleFeeProvider(evmKit), gasLimit)
            }

            val l1FeeContractAddress = blockchainType.l1GasFeeContractAddress

            return if (l1FeeContractAddress == null) {
                EvmCommonGasDataService(evmKit, gasLimit)
            } else {
                val l1FeeProvider = L1FeeProvider(evmKit, l1FeeContractAddress)
                EvmRollupGasDataService(evmKit, l1FeeProvider, gasLimit)
            }
        }
    }

}
