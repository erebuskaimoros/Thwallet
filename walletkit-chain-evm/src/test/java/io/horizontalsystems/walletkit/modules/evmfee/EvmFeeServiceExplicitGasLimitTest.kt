package io.horizontalsystems.walletkit.modules.evmfee

import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.core.rollup.L1FeeProvider
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.GasPrice
import io.horizontalsystems.ethereumkit.models.TransactionData
import io.horizontalsystems.walletkit.entities.DataState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.math.BigInteger

class EvmFeeServiceExplicitGasLimitTest {

    @Test
    fun `explicit gas limit still consults specialized gas-data service`() = runBlocking<Unit> {
        val evmKit = mock(EthereumKit::class.java)
        val gasPrice = GasPrice.Eip1559(100, 2)
        val explicitGasLimit = 250_000L
        val additionalFee = BigInteger.valueOf(999)
        val transactionData = TransactionData(
            Address("0x2222222222222222222222222222222222222222"),
            BigInteger.valueOf(42),
            byteArrayOf(1),
        )
        val l1FeeProvider = mock(L1FeeProvider::class.java)
        `when`(
            l1FeeProvider.getL1Fee(
                gasPrice,
                explicitGasLimit,
                transactionData.to,
                transactionData.value,
                transactionData.input,
            ),
        ).thenReturn(additionalFee)
        val feeService = EvmFeeService(
            evmKit = evmKit,
            gasPriceService = FixedGasPriceService(gasPrice),
            gasDataService = EvmRollupGasDataService(
                evmKit,
                l1FeeProvider,
            ),
        )

        feeService.setGasLimit(explicitGasLimit)
        feeService.setTransactionData(transactionData)
        feeService.start()

        val state = withTimeout(5_000) {
            feeService.transactionStatusFlow.first { it is DataState.Success }
        } as DataState.Success
        feeService.clear()

        verify(l1FeeProvider).getL1Fee(
            gasPrice,
            explicitGasLimit,
            transactionData.to,
            transactionData.value,
            transactionData.input,
        )
        assertEquals(explicitGasLimit, state.data.gasData.gasLimit)
        assertSame(gasPrice, state.data.gasData.gasPrice)
        assertEquals(additionalFee, (state.data.gasData as RollupGasData).l1Fee)
    }

    private class FixedGasPriceService(
        private val gasPrice: GasPrice,
    ) : IEvmGasPriceService() {
        override fun createState(): DataState<GasPriceInfo> = DataState.Success(
            GasPriceInfo(
                gasPrice = gasPrice,
                gasPriceDefault = gasPrice,
                default = true,
                warnings = emptyList(),
                errors = emptyList(),
            ),
        )

        override fun setRecommended() = Unit

        override fun start() = Unit

        override fun clear() = Unit
    }
}
