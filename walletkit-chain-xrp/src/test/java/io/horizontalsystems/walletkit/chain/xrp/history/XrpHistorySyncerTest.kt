package io.horizontalsystems.walletkit.chain.xrp.history

import io.horizontalsystems.walletkit.chain.xrp.rpc.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class XrpHistorySyncerTest {
    @Test
    fun `commits checkpoint only after every pinned page succeeds`() = runTest {
        val rpc = FakeRpc().apply {
            server = state(32_620, XrpLedgerRanges(listOf(XrpLedgerRange(32_570, 32_620))))
            pages += XrpTransactionPage(emptyList(), XrpPageMarker.fromEncoded("{\"ledger\":32595,\"seq\":1}"), 32_570, 32_620)
            pageFailure = XrpRpcException("second page failed")
        }
        val store = RecordingHistoryStore()

        assertThrows(XrpRpcException::class.java) {
            kotlinx.coroutines.runBlocking { XrpHistorySyncer(rpc, store).sync("rMine") }
        }

        assertEquals(null, store.checkpoint)
        assertEquals(0, store.commits)
    }

    @Test
    fun `deduplicates transactions and atomically advances pinned checkpoint`() = runTest {
        val transaction = nativeTransaction("HASH", ledger = 70)
        val rpc = FakeRpc().apply {
            server = state(32_620, XrpLedgerRanges(listOf(XrpLedgerRange(32_570, 32_620))))
            pages += XrpTransactionPage(listOf(transaction.copy(ledgerIndex = 32_590)), XrpPageMarker.fromEncoded("\"next\""), 32_570, 32_620)
            pages += XrpTransactionPage(listOf(transaction.copy(ledgerIndex = 32_590)), null, 32_570, 32_620)
        }
        val store = RecordingHistoryStore()

        val result = XrpHistorySyncer(rpc, store).sync("rMine")

        assertEquals(32_620, result.checkpoint)
        assertEquals(listOf(transaction.copy(ledgerIndex = 32_590)), store.transactions)
        assertEquals(1, store.commits)
    }

    @Test
    fun `fails closed when previous checkpoint crosses a complete ledger gap`() = runTest {
        val rpc = FakeRpc().apply {
            server = state(32_620, XrpLedgerRanges(listOf(XrpLedgerRange(32_570, 32_590), XrpLedgerRange(32_600, 32_620))))
        }
        val store = RecordingHistoryStore(checkpoint = 32_580)

        assertThrows(XrpLedgerGapException::class.java) {
            kotlinx.coroutines.runBlocking { XrpHistorySyncer(rpc, store).sync("rMine") }
        }
        assertEquals(0, store.commits)
    }

    @Test
    fun `fresh sync rejects a pruned endpoint instead of permanently checkpointing partial history`() = runTest {
        val rpc = FakeRpc().apply {
            server = state(40_000, XrpLedgerRanges(listOf(XrpLedgerRange(39_900, 40_000))))
        }
        val store = RecordingHistoryStore()

        assertThrows(XrpLedgerGapException::class.java) {
            kotlinx.coroutines.runBlocking { XrpHistorySyncer(rpc, store).sync("rMine") }
        }

        assertEquals(null, store.checkpoint)
        assertEquals(0, store.commits)
    }

    @Test
    fun `oversized account history is bisected into bounded durable ledger windows`() = runTest {
        val fullMarker = XrpPageMarker.fromEncoded("{\"ledger\":32580,\"seq\":1}")
        val rpc = FakeRpc().apply {
            server = state(32_620, XrpLedgerRanges(listOf(XrpLedgerRange(32_570, 32_620))))
            pages += XrpTransactionPage(emptyList(), fullMarker, 32_570, 32_620)
            pages += XrpTransactionPage(emptyList(), null, 32_570, 32_595)
            pages += XrpTransactionPage(emptyList(), null, 32_596, 32_620)
        }
        val store = RecordingHistoryStore()

        val result = XrpHistorySyncer(rpc, store, maxPagesPerSync = 1).sync("rMine")

        assertEquals(32_620L, result.checkpoint)
        assertEquals(2, store.commits)
        assertEquals(32_620L, store.checkpoint)
    }
}
