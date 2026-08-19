package io.horizontalsystems.walletkit.chain.xrp.runtime

import io.horizontalsystems.walletkit.chain.xrp.XrpAddressCodec
import io.horizontalsystems.walletkit.chain.xrp.history.RecordingHistoryStore
import io.horizontalsystems.walletkit.chain.xrp.history.FakeRpc
import io.horizontalsystems.walletkit.chain.xrp.history.state
import io.horizontalsystems.walletkit.chain.xrp.rpc.XrpAccountState
import io.horizontalsystems.walletkit.chain.xrp.rpc.XrpDestinationTagRequiredException
import io.horizontalsystems.walletkit.chain.xrp.rpc.XrpFeeQuote
import io.horizontalsystems.walletkit.chain.xrp.rpc.XrpFeeTooHighException
import io.horizontalsystems.walletkit.chain.xrp.rpc.XrpFeeApprovalExceededException
import io.horizontalsystems.walletkit.chain.xrp.rpc.XrpLedgerRange
import io.horizontalsystems.walletkit.chain.xrp.rpc.XrpLedgerRanges
import io.horizontalsystems.walletkit.chain.xrp.rpc.XrpSubmitResult
import io.horizontalsystems.walletkit.chain.xrp.rpc.XrpTransactionPage
import io.horizontalsystems.walletkit.chain.xrp.submission.InMemoryXrpPendingTransactionStore
import io.horizontalsystems.walletkit.chain.xrp.submission.XrpPendingState
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class XrpWalletEngineTest {
    private val privateKey = "90802A50AA84EFB6CDB225F17C27616EA94048C179142FECF03F4712A07EA7A4".hexToBytes()
    private val source = "rHsMGQEkVNJmpGWs8XUBoTBiAAbwxZN5v3"
    private val destination = "r3AgF9mMBFtaLhKcg96weMhbbEFLZ3mx17"
    private val destinationWithTagZero = XrpAddressCodec.classicToXAddress(destination, 0u)

    @Test
    fun `send persists and submits the exact signed blob`() = runTest {
        val store = InMemoryXrpPendingTransactionStore()
        val rpc = readyRpc(store)
        val engine = engine(rpc, store)

        val result = engine.send(
            XrpSendRequest(destination, destinationTag = 0u, amountDrops = 2_000_000, memo = "hello")
        )

        assertEquals(XrpPendingState.SUBMITTED, result.state)
        assertEquals(result.blob, rpc.submittedBlobs.single())
        assertEquals(result.hash, store.get(source)?.hash)
        assertEquals(0u, engine.lastPreparedPayment?.commitments?.destinationTag)
    }

    @Test
    fun `concurrent sends serialize signing with the first durable pending save`() = runTest {
        val store = InMemoryXrpPendingTransactionStore()
        val feeEntered = CompletableDeferred<Unit>()
        val releaseFee = CompletableDeferred<Unit>()
        val rpc = readyRpc(store).apply {
            onFeeQuote = {
                if (!feeEntered.isCompleted) {
                    feeEntered.complete(Unit)
                    releaseFee.await()
                }
            }
        }
        val engine = engine(rpc, store)
        val request = XrpSendRequest(destination, null, 2_000_000, null)

        val first = async { runCatching { engine.send(request) } }
        feeEntered.await()
        val second = async { runCatching { engine.send(request.copy(amountDrops = 2_000_001)) } }
        releaseFee.complete(Unit)
        val results = listOf(first.await(), second.await())

        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, results.count { it.exceptionOrNull() is io.horizontalsystems.walletkit.chain.xrp.submission.XrpPendingTransactionExistsException })
        assertEquals(1, rpc.feeQuoteCalls)
        assertEquals(1, rpc.submittedBlobs.size)
    }

    @Test
    fun `engine owns a private key copy before the derivation buffer is zeroed`() = runTest {
        val keyBuffer = privateKey.copyOf()
        val rpc = readyRpc(InMemoryXrpPendingTransactionStore())
        val engine = XrpWalletEngine(
            address = source,
            privateKey = keyBuffer,
            rpc = rpc,
            historyStore = RecordingHistoryStore(),
            pendingStore = InMemoryXrpPendingTransactionStore(),
            nowEpochSeconds = { 1_700_000_000 },
        )

        keyBuffer.fill(0)
        val prepared = engine.prepare(XrpSendRequest(destination, null, 2_000_000, null))

        assertEquals(source, prepared.commitments.account)
        assertTrue(prepared.signedBlob.isNotEmpty())
        engine.dispose()
    }

    @Test
    fun `X-address tag zero is present and a conflicting separate tag is rejected`() = runTest {
        val store = InMemoryXrpPendingTransactionStore()
        val rpc = readyRpc(store)
        val engine = engine(rpc, store)

        val prepared = engine.prepare(
            XrpSendRequest(destinationWithTagZero, destinationTag = null, amountDrops = 2_000_000, memo = null)
        )
        assertEquals(0u, prepared.commitments.destinationTag)

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                engine.prepare(
                    XrpSendRequest(destinationWithTagZero, destinationTag = 7u, amountDrops = 2_000_000, memo = null)
                )
            }
        }
    }

    @Test
    fun `RequireDest and inactive reserve are enforced before signing`() = runTest {
        val rpc = readyRpc(InMemoryXrpPendingTransactionStore()).apply {
            accountByAddress[destination] = XrpAccountState(destination, 5_000_000, 1, 0, true, 100)
        }
        val engine = engine(rpc, InMemoryXrpPendingTransactionStore())
        assertThrows(XrpDestinationTagRequiredException::class.java) {
            kotlinx.coroutines.runBlocking {
                engine.prepare(XrpSendRequest(destination, null, 2_000_000, null))
            }
        }

        rpc.accountByAddress[destination] = null
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                engine.prepare(XrpSendRequest(destination, null, 999_999, null))
            }
        }
    }

    @Test
    fun `recommended fee is maxed with live fee but never bypasses cap`() = runTest {
        val store = InMemoryXrpPendingTransactionStore()
        val rpc = readyRpc(store)
        val engine = engine(rpc, store)

        val prepared = engine.prepare(
            XrpSendRequest(destination, null, 2_000_000, null, recommendedFeeDrops = 300)
        )
        assertEquals(300, prepared.commitments.feeDrops)

        assertThrows(XrpFeeTooHighException::class.java) {
            kotlinx.coroutines.runBlocking {
                engine.prepare(
                    XrpSendRequest(destination, null, 2_000_000, null, recommendedFeeDrops = 100_001)
                )
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                engine.prepare(
                    XrpSendRequest(
                        destination,
                        null,
                        amountDrops = 2_000_000,
                        memo = null,
                        minimumSendAmountDrops = 2_000_001,
                    )
                )
            }
        }
    }

    @Test
    fun `send cannot sign a fee above the user approved confirmation maximum`() = runTest {
        val store = InMemoryXrpPendingTransactionStore()
        val rpc = readyRpc(store)
        val engine = engine(rpc, store)
        val request = XrpSendRequest(
            destination,
            null,
            amountDrops = 2_000_000,
            memo = null,
            maximumFeeDrops = 15,
        )

        assertEquals(15L, engine.preflight(request).feeDrops)
        rpc.fee = XrpFeeQuote(10, 10, 12, 16, 100)

        assertThrows(XrpFeeApprovalExceededException::class.java) {
            kotlinx.coroutines.runBlocking { engine.send(request) }
        }
        assertTrue(rpc.submittedBlobs.isEmpty())
        assertEquals(null, engine.lastPreparedPayment)
    }

    @Test
    fun `expired THOR quote after live preflight never signs persists or broadcasts`() = runTest {
        val store = InMemoryXrpPendingTransactionStore()
        var now = 1_700_000_000L
        val quoteExpiry = now + 30
        val rpc = readyRpc(store).apply {
            // Simulate the quote expiring while the final, live fee/account preflight is in flight.
            onFeeQuote = { now = quoteExpiry }
        }
        val engine = XrpWalletEngine(
            address = source,
            privateKey = privateKey,
            rpc = rpc,
            historyStore = RecordingHistoryStore(),
            pendingStore = store,
            nowEpochSeconds = { now },
        )
        val request = expiringThorRequest(quoteExpiry)

        val error = runCatching { engine.send(request) }.exceptionOrNull()

        assertEquals("XrpQuoteExpiredException", error?.javaClass?.simpleName)
        assertEquals(null, engine.lastPreparedPayment)
        assertEquals(null, store.get(source))
        assertTrue(rpc.submittedBlobs.isEmpty())
    }

    @Test
    fun `THOR quote expiring across signing never persists or broadcasts`() = runTest {
        val store = InMemoryXrpPendingTransactionStore()
        val quoteExpiry = 1_700_000_030L
        var clockReads = 0
        val rpc = readyRpc(store)
        val engine = XrpWalletEngine(
            address = source,
            privateKey = privateKey,
            rpc = rpc,
            historyStore = RecordingHistoryStore(),
            pendingStore = store,
            nowEpochSeconds = {
                if (clockReads++ == 0) quoteExpiry - 1 else quoteExpiry
            },
        )

        val error = runCatching { engine.send(expiringThorRequest(quoteExpiry)) }.exceptionOrNull()

        assertEquals("XrpQuoteExpiredException", error?.javaClass?.simpleName)
        assertEquals(2, clockReads)
        assertEquals(null, store.get(source))
        assertTrue(rpc.submittedBlobs.isEmpty())
    }

    @Test
    fun `watch-only engine syncs but cannot prepare a payment`() = runTest {
        val rpc = readyRpc(InMemoryXrpPendingTransactionStore())
        val engine = XrpWalletEngine(
            address = source,
            privateKey = null,
            rpc = rpc,
            historyStore = RecordingHistoryStore(),
            pendingStore = InMemoryXrpPendingTransactionStore(),
            nowEpochSeconds = { 1_700_000_000 },
        )

        engine.refreshNow(syncHistory = false)
        assertEquals(9_000_000L, engine.balance.value?.balanceDrops)
        assertTrue(!engine.canSign)
        assertThrows(XrpWatchOnlyException::class.java) {
            kotlinx.coroutines.runBlocking {
                engine.prepare(XrpSendRequest(destination, null, 2_000_000, null))
            }
        }
    }

    @Test
    fun `refresh after restart retries only the exact durably persisted blob`() = runTest {
        val store = InMemoryXrpPendingTransactionStore()
        val firstRpc = readyRpc(store).apply {
            onSubmit = { throw IOException("ambiguous timeout") }
        }
        val firstEngine = engine(firstRpc, store)
        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking {
                firstEngine.send(XrpSendRequest(destination, null, 2_000_000, null))
            }
        }
        val persistedBlob = requireNotNull(store.get(source)).blob

        val restartedRpc = readyRpc(store).apply {
            transactionResult = null
            onSubmit = { XrpSubmitResult("tesSUCCESS", "queued") }
        }
        engine(restartedRpc, store).refreshNow(syncHistory = false)

        assertEquals(listOf(persistedBlob), restartedRpc.submittedBlobs)
        assertEquals(XrpPendingState.SUBMITTED, store.get(source)?.state)
    }

    @Test
    fun `proven expired submission remains durably visible until explicit acknowledgement`() = runTest {
        val store = InMemoryXrpPendingTransactionStore()
        val rpc = readyRpc(store)
        val engine = engine(rpc, store)
        rpc.onSubmit = { XrpSubmitResult("tesSUCCESS", "queued") }
        engine.send(XrpSendRequest(destination, null, 2_000_000, null))

        rpc.server = state(105, XrpLedgerRanges(listOf(XrpLedgerRange(1, 105))))
        val advancedAccount = XrpAccountState(source, 8_999_985, 7, 0, false, 105)
        rpc.account = advancedAccount
        rpc.accountByAddress[source] = advancedAccount
        rpc.accountByAddress[destination] = null
        rpc.fee = XrpFeeQuote(10, 10, 12, 15, 105)
        rpc.transactionResult = null
        engine.refreshNow(syncHistory = false)

        assertEquals(XrpPendingState.EXPIRED, engine.pendingTransaction?.state)
        assertEquals(XrpPendingState.EXPIRED, engine.lastSubmission.value?.state)

        engine.acknowledgeTerminalSubmission()
        assertEquals(null, engine.pendingTransaction)
        assertEquals(null, engine.lastSubmission.value)
        assertEquals(105L, engine.preflight(XrpSendRequest(destination, null, 2_000_000, null)).ledgerIndex)
    }

    @Test
    fun `sequence conflict survives restart and continues blocking new sends`() = runTest {
        val store = InMemoryXrpPendingTransactionStore()
        val rpc = readyRpc(store)
        val first = engine(rpc, store)
        first.send(XrpSendRequest(destination, null, 2_000_000, null))

        rpc.server = state(103, XrpLedgerRanges(listOf(XrpLedgerRange(1, 103))))
        rpc.accountByAddress[source] = XrpAccountState(source, 7_000_000, 8, 0, false, 103)
        rpc.fee = XrpFeeQuote(10, 10, 12, 15, 103)
        rpc.transactionResult = null
        first.refreshNow(syncHistory = false)
        assertEquals(XrpPendingState.BLOCKED_SEQUENCE_CONFLICT, store.get(source)?.state)

        val restarted = engine(rpc, store)
        restarted.refreshNow(syncHistory = false)
        assertEquals(XrpPendingState.BLOCKED_SEQUENCE_CONFLICT, restarted.pendingTransaction?.state)
        assertEquals(XrpPendingState.BLOCKED_SEQUENCE_CONFLICT, restarted.lastSubmission.value?.state)
        assertThrows(io.horizontalsystems.walletkit.chain.xrp.submission.XrpPendingTransactionExistsException::class.java) {
            kotlinx.coroutines.runBlocking {
                restarted.prepare(XrpSendRequest(destination, null, 1_000_000, null))
            }
        }
    }

    @Test
    fun `terminal conflict is visible after restart even when history refresh fails`() = runTest {
        val store = InMemoryXrpPendingTransactionStore()
        val rpc = readyRpc(store)
        val first = engine(rpc, store)
        first.send(XrpSendRequest(destination, null, 2_000_000, null))

        rpc.server = state(103, XrpLedgerRanges(listOf(XrpLedgerRange(1, 103))))
        rpc.accountByAddress[source] = XrpAccountState(source, 7_000_000, 8, 0, false, 103)
        rpc.fee = XrpFeeQuote(10, 10, 12, 15, 103)
        rpc.transactionResult = null
        first.refreshNow(syncHistory = false)
        assertEquals(XrpPendingState.BLOCKED_SEQUENCE_CONFLICT, store.get(source)?.state)

        val failingHistory = RecordingHistoryStore(checkpoint = 100)
        rpc.pageFailure = IOException("history unavailable")
        val restarted = XrpWalletEngine(
            address = source,
            privateKey = privateKey,
            rpc = rpc,
            historyStore = failingHistory,
            pendingStore = store,
            nowEpochSeconds = { 1_700_000_000 },
        )

        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { restarted.refreshNow(syncHistory = true) }
        }
        assertEquals(
            XrpPendingState.BLOCKED_SEQUENCE_CONFLICT,
            restarted.lastSubmission.value?.state,
        )
        assertEquals(XrpPendingState.BLOCKED_SEQUENCE_CONFLICT, restarted.pendingTransaction?.state)
    }

    @Test
    fun `inactive deleted account still syncs history and publishes durable terminal status`() = runTest {
        val store = InMemoryXrpPendingTransactionStore()
        val rpc = readyRpc(store)
        val history = RecordingHistoryStore(checkpoint = 100)
        val engine = XrpWalletEngine(
            address = source,
            privateKey = privateKey,
            rpc = rpc,
            historyStore = history,
            pendingStore = store,
            nowEpochSeconds = { 1_700_000_000 },
        )
        engine.send(XrpSendRequest(destination, null, 2_000_000, null))

        rpc.server = state(105, XrpLedgerRanges(listOf(XrpLedgerRange(1, 105))))
        rpc.accountByAddress[source] = null
        rpc.transactionResult = null
        rpc.pages += XrpTransactionPage(emptyList(), null, 101, 105)
        engine.refreshNow(syncHistory = true)

        assertEquals(105L, history.checkpoint)
        assertEquals(1, history.commits)
        assertEquals(0L, engine.balance.value?.balanceDrops)
        assertEquals(XrpPendingState.EXPIRED, engine.pendingTransaction?.state)
        assertEquals(XrpPendingState.EXPIRED, engine.lastSubmission.value?.state)
    }

    @Test
    fun `started engine polls pending finality until the ledger proves expiry`() = runTest {
        val store = InMemoryXrpPendingTransactionStore()
        val rpc = readyRpc(store)
        val historyStore = RecordingHistoryStore(checkpoint = 100)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val engine = XrpWalletEngine(
            address = source,
            privateKey = privateKey,
            rpc = rpc,
            historyStore = historyStore,
            pendingStore = store,
            nowEpochSeconds = { 1_700_000_000 },
            coroutineDispatcher = dispatcher,
            pendingPollIntervalMillis = 1_000,
        )
        engine.send(XrpSendRequest(destination, null, 2_000_000, null))

        engine.start()
        runCurrent()
        assertEquals(XrpPendingState.SUBMITTED, engine.pendingTransaction?.state)

        rpc.server = state(105, XrpLedgerRanges(listOf(XrpLedgerRange(1, 105))))
        rpc.accountByAddress[source] = XrpAccountState(source, 8_999_985, 7, 0, false, 105)
        rpc.fee = XrpFeeQuote(10, 10, 12, 15, 105)
        rpc.transactionResult = null
        rpc.pages += XrpTransactionPage(emptyList(), null, 101, 105)
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(XrpPendingState.EXPIRED, engine.pendingTransaction?.state)
        assertEquals(XrpPendingState.EXPIRED, engine.lastSubmission.value?.state)
        engine.dispose()
    }

    private fun engine(rpc: FakeRpc, store: InMemoryXrpPendingTransactionStore) = XrpWalletEngine(
        address = source,
        privateKey = privateKey,
        rpc = rpc,
        historyStore = RecordingHistoryStore(),
        pendingStore = store,
        nowEpochSeconds = { 1_700_000_000 },
    )

    private fun readyRpc(store: InMemoryXrpPendingTransactionStore) = FakeRpc().apply {
        server = state(100, XrpLedgerRanges(listOf(XrpLedgerRange(1, 100))))
        account = XrpAccountState(source, 9_000_000, 7, 0, false, 100)
        accountByAddress[source] = account!!
        fee = XrpFeeQuote(10, 10, 12, 15, 100)
        onSubmit = { blob ->
            assertEquals(blob, store.get(source)?.blob)
            XrpSubmitResult("tesSUCCESS", "queued")
        }
    }

    private fun expiringThorRequest(quoteExpiry: Long): XrpSendRequest {
        return XrpSendRequest(
            destination = destination,
            destinationTag = null,
            amountDrops = 2_000_000L,
            memo = "=:BTC.BTC:rRecipient:1000",
            validUntilEpochSeconds = quoteExpiry,
        )
    }
}

private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
