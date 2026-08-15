package io.horizontalsystems.walletkit.modules.evmfee

import io.horizontalsystems.ethereumkit.core.EthereumKit
import io.horizontalsystems.ethereumkit.core.rollup.MantleFeeProvider
import io.horizontalsystems.ethereumkit.models.Address
import io.horizontalsystems.ethereumkit.models.GasPrice
import io.horizontalsystems.ethereumkit.models.TransactionData
import io.reactivex.Single
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.math.BigInteger

class EvmMantleGasDataServiceTest {

    private val recipient = Address("0x2222222222222222222222222222222222222222")
    @Test
    fun `additional fee uses estimated gas while signing keeps surcharged gas`() {
        val evmKit = mock(EthereumKit::class.java)
        val mantleFeeProvider = mock(MantleFeeProvider::class.java)
        val gasPrice = GasPrice.Eip1559(100, 2)
        val estimatedGasLimit = 100_000L
        val surchargedGasLimit = 110_000L
        val expectedAdditionalFee = BigInteger.valueOf(777)
        val transactionData = TransactionData(
            recipient,
            BigInteger.valueOf(42),
            byteArrayOf(1, 2, 3),
        )
        `when`(evmKit.estimateGas(transactionData, gasPrice))
            .thenReturn(Single.just(estimatedGasLimit))
        `when`(
            mantleFeeProvider.getAdditionalFee(
                estimatedGasLimit,
                transactionData.to,
                transactionData.value,
                transactionData.input,
            ),
        ).thenReturn(Single.just(expectedAdditionalFee))
        `when`(
            mantleFeeProvider.getAdditionalFee(
                surchargedGasLimit,
                transactionData.to,
                transactionData.value,
                transactionData.input,
            ),
        ).thenReturn(Single.just(expectedAdditionalFee))

        val gasData = EvmMantleGasDataService(evmKit, mantleFeeProvider)
            .estimatedGasDataAsync(gasPrice, transactionData, null)
            .blockingGet()

        assertTrue(gasData is RollupGasData)
        assertEquals(estimatedGasLimit, gasData.estimatedGasLimit)
        assertEquals(surchargedGasLimit, gasData.gasLimit)
        assertEquals(expectedAdditionalFee, (gasData as RollupGasData).l1Fee)
        verify(mantleFeeProvider).getAdditionalFee(
            estimatedGasLimit,
            transactionData.to,
            transactionData.value,
            transactionData.input,
        )
    }

    @Test
    fun `send-all stub value is used for gas and Mantle total-fee estimation`() {
        val evmKit = mock(EthereumKit::class.java)
        val mantleFeeProvider = mock(MantleFeeProvider::class.java)
        val gasPrice = GasPrice.Eip1559(100, 2)
        val stubAmount = BigInteger.ONE
        val transactionData = TransactionData(
            recipient,
            BigInteger("1000000000000000000"),
            byteArrayOf(),
        )
        val stubTransactionData = TransactionData(
            transactionData.to,
            stubAmount,
            transactionData.input,
        )
        `when`(evmKit.estimateGas(stubTransactionData, gasPrice))
            .thenReturn(Single.just(21_000L))
        `when`(
            mantleFeeProvider.getAdditionalFee(
                21_000L,
                transactionData.to,
                stubAmount,
                transactionData.input,
            ),
        ).thenReturn(Single.just(BigInteger.valueOf(123)))

        EvmMantleGasDataService(evmKit, mantleFeeProvider)
            .estimatedGasDataAsync(gasPrice, transactionData, stubAmount)
            .blockingGet()

        verify(evmKit).estimateGas(stubTransactionData, gasPrice)
        verify(mantleFeeProvider).getAdditionalFee(
            21_000L,
            transactionData.to,
            stubAmount,
            transactionData.input,
        )
    }

    @Test
    fun `explicit provider gas is retained while estimated gas decomposes Mantle fee`() {
        val evmKit = mock(EthereumKit::class.java)
        val mantleFeeProvider = mock(MantleFeeProvider::class.java)
        val gasPrice = GasPrice.Eip1559(100, 2)
        val estimatedGasLimit = 100_000L
        val explicitGasLimit = 250_000L
        val expectedAdditionalFee = BigInteger.valueOf(777)
        val transactionData = TransactionData(
            recipient,
            BigInteger.valueOf(42),
            byteArrayOf(1, 2, 3),
        )
        `when`(evmKit.estimateGas(transactionData, gasPrice))
            .thenReturn(Single.just(estimatedGasLimit))
        `when`(
            mantleFeeProvider.getAdditionalFee(
                estimatedGasLimit,
                transactionData.to,
                transactionData.value,
                transactionData.input,
            ),
        ).thenReturn(Single.just(expectedAdditionalFee))
        `when`(
            mantleFeeProvider.getAdditionalFee(
                explicitGasLimit,
                transactionData.to,
                transactionData.value,
                transactionData.input,
            ),
        ).thenReturn(Single.just(expectedAdditionalFee))

        val gasData = EvmMantleGasDataService(
            evmKit,
            mantleFeeProvider,
            predefinedGasLimit = explicitGasLimit,
        ).estimatedGasDataAsync(gasPrice, transactionData, null).blockingGet()

        verify(evmKit).estimateGas(transactionData, gasPrice)
        verify(mantleFeeProvider).getAdditionalFee(
            estimatedGasLimit,
            transactionData.to,
            transactionData.value,
            transactionData.input,
        )
        assertEquals(estimatedGasLimit, gasData.estimatedGasLimit)
        assertEquals(explicitGasLimit, gasData.gasLimit)
        assertEquals(expectedAdditionalFee, (gasData as RollupGasData).l1Fee)
    }
}
