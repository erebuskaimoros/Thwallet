package io.horizontalsystems.walletkit.modules.evmfee

import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.core.rollup.MantleFeeProvider
import io.horizontalsystems.ethereumkit.models.GasPrice
import io.horizontalsystems.ethereumkit.models.TransactionData
import io.reactivex.Single
import java.math.BigInteger

/** Adds Mantle's post-Arsia L1-data and operator fees to the selected L2 execution fee. */
class EvmMantleGasDataService(
    evmKit: EthereumKit,
    private val mantleFeeProvider: MantleFeeProvider,
    predefinedGasLimit: Long? = null,
) : EvmCommonGasDataService(evmKit, predefinedGasLimit) {

    override fun estimatedGasDataAsync(
        gasPrice: GasPrice,
        transactionData: TransactionData,
        stubAmount: BigInteger?,
    ): Single<GasData> {
        val feeTransactionData = if (stubAmount != null) {
            // Unlike the OP gas-oracle contract, eth_estimateTotalFee simulates balance
            // sufficiency. Reusing the send-all amount would fail before the fee can be
            // subtracted from that amount.
            TransactionData(transactionData.to, stubAmount, transactionData.input)
        } else {
            transactionData
        }

        val explicitGasLimit = predefinedGasLimit
        return if (explicitGasLimit != null) {
            estimateExecutionGas(feeTransactionData, gasPrice).flatMap { estimatedGasLimit ->
                additionalFee(feeTransactionData, estimatedGasLimit).map { additionalFee ->
                    RollupGasData(
                        gasLimit = explicitGasLimit,
                        estimatedGasLimit = estimatedGasLimit,
                        gasPrice = gasPrice,
                        l1Fee = additionalFee,
                    )
                }
            }
        } else {
            super.estimatedGasDataAsync(gasPrice, transactionData, stubAmount).flatMap { gasData ->
                additionalFee(feeTransactionData, gasData.estimatedGasLimit).map { additionalFee ->
                    RollupGasData(
                        gasLimit = gasData.gasLimit,
                        estimatedGasLimit = gasData.estimatedGasLimit,
                        gasPrice = gasPrice,
                        l1Fee = additionalFee,
                    )
                }
            }
        }
    }

    private fun estimateExecutionGas(
        transactionData: TransactionData,
        gasPrice: GasPrice,
    ): Single<Long> = evmKit.estimateGas(transactionData, gasPrice)
        .onErrorResumeNext { evmKit.estimateGas(transactionData) }

    private fun additionalFee(transactionData: TransactionData, gasLimit: Long): Single<BigInteger> =
        mantleFeeProvider.getAdditionalFee(
            gasLimit,
            transactionData.to,
            transactionData.value,
            transactionData.input,
        )

}
