package io.horizontalsystems.walletkit.modules.evmfee

import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.core.rollup.MantleFeeProvider
import io.horizontalsystems.ethereumkit.models.GasPrice
import io.horizontalsystems.ethereumkit.models.TransactionData
import kotlinx.coroutines.CancellationException
import java.math.BigInteger

/** Adds Mantle's post-Arsia L1-data and operator fees to the selected L2 execution fee. */
class EvmMantleGasDataService(
    evmKit: EthereumKit,
    private val mantleFeeProvider: MantleFeeProvider,
    predefinedGasLimit: Long? = null,
) : EvmCommonGasDataService(evmKit, predefinedGasLimit) {

    override suspend fun estimatedGasData(
        gasPrice: GasPrice,
        transactionData: TransactionData,
        stubAmount: BigInteger?,
    ): GasData {
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
            val estimatedGasLimit = estimateExecutionGas(feeTransactionData, gasPrice)
            RollupGasData(
                gasLimit = explicitGasLimit,
                estimatedGasLimit = estimatedGasLimit,
                gasPrice = gasPrice,
                l1Fee = additionalFee(feeTransactionData, estimatedGasLimit),
            )
        } else {
            val gasData = super.estimatedGasData(gasPrice, transactionData, stubAmount)
            RollupGasData(
                gasLimit = gasData.gasLimit,
                estimatedGasLimit = gasData.estimatedGasLimit,
                gasPrice = gasPrice,
                l1Fee = additionalFee(feeTransactionData, gasData.estimatedGasLimit),
            )
        }
    }

    private suspend fun estimateExecutionGas(
        transactionData: TransactionData,
        gasPrice: GasPrice,
    ): Long = try {
        evmKit.estimateGas(transactionData, gasPrice)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        evmKit.estimateGas(transactionData)
    }

    private suspend fun additionalFee(transactionData: TransactionData, gasLimit: Long): BigInteger =
        mantleFeeProvider.getAdditionalFee(
            gasLimit,
            transactionData.to,
            transactionData.value,
            transactionData.input,
        )

}
