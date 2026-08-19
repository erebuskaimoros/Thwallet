package io.horizontalsystems.walletkit.chain.xrp.rpc

import io.horizontalsystems.walletkit.chain.xrp.history.FakeRpc
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class XrpLedgerReaderSafetyTest {
    @Test
    fun `reserve subtraction overflow fails closed instead of creating spendable balance`() = runTest {
        val rpc = FakeRpc().apply {
            server = XrpServerState(
                networkId = 0,
                validatedLedgerIndex = 100,
                completeLedgers = XrpLedgerRanges(listOf(XrpLedgerRange(1, 100))),
                reserveBaseDrops = Long.MAX_VALUE,
                reserveIncrementDrops = 0,
            )
            account = XrpAccountState("rSource", 0, 1, 0, false, 100)
            fee = XrpFeeQuote(10, 10, 10, 10, 100)
        }

        assertThrows(XrpProtocolException::class.java) {
            kotlinx.coroutines.runBlocking {
                XrpLedgerReader(rpc, XrpFeePolicy(100)).snapshot("rSource")
            }
        }
    }
}
