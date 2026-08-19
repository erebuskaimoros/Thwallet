package io.horizontalsystems.walletkit.chain.xrp.submission

import io.horizontalsystems.walletkit.chain.xrp.history.FakeRpc
import io.horizontalsystems.walletkit.chain.xrp.history.state
import io.horizontalsystems.walletkit.chain.xrp.rpc.*
import io.horizontalsystems.walletkit.chain.xrp.XrpPaymentInput
import io.horizontalsystems.walletkit.chain.xrp.XrpPaymentSigner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

class XrpReliableSubmitterTest {
    private val privateKey = "90802A50AA84EFB6CDB225F17C27616EA94048C179142FECF03F4712A07EA7A4".hexToBytes()
    private val source = "rHsMGQEkVNJmpGWs8XUBoTBiAAbwxZN5v3"
    private val destination = "r3AgF9mMBFtaLhKcg96weMhbbEFLZ3mx17"
    private val signedPayment = payment(amountDrops = 100)
    private val signedBlob = signedPayment.signedBlob.toHex()
    private val signed = XrpSignedTransaction(
        hash = signedPayment.hash,
        blob = signedBlob,
        sourceAddress = source,
        sequence = 7,
        preparedLedgerIndex = 100,
        lastLedgerSequence = 104,
        createdAtEpochSeconds = 1_700_000_000
    )

    @Test
    fun `persists before submit and retry sends exactly the same blob`() = runTest {
        val store = InMemoryXrpPendingTransactionStore()
        val rpc = FakeRpc().apply {
            onSubmit = { blob ->
                assertEquals(signed.blob, blob)
                assertEquals(signed.hash, store.get(source)?.hash)
                throw IOException("timeout")
            }
        }
        val submitter = XrpReliableSubmitter(rpc, store)

        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { submitter.submit(signed) }
        }
        assertEquals(XrpPendingState.PERSISTED, store.get(source)?.state)

        rpc.onSubmit = { XrpSubmitResult("tesSUCCESS", "queued") }
        rpc.server = state(101, XrpLedgerRanges(listOf(XrpLedgerRange(90, 101))))
        rpc.transactionResult = null
        rpc.account = XrpAccountState(source, 5_000_000, 7, 0, false, 101)
        submitter.resume(source)

        assertEquals(listOf(signed.blob, signed.blob), rpc.submittedBlobs)
        assertEquals(XrpPendingState.SUBMITTED, store.get(source)?.state)
    }

    @Test
    fun `submit result stays provisional until tx is validated`() = runTest {
        val store = InMemoryXrpPendingTransactionStore()
        val rpc = FakeRpc().apply { onSubmit = { XrpSubmitResult("tesSUCCESS", "accepted") } }
        val submitter = XrpReliableSubmitter(rpc, store)

        val submitted = submitter.submit(signed)
        assertEquals(XrpPendingState.SUBMITTED, submitted.state)

        rpc.server = state(102, XrpLedgerRanges(listOf(XrpLedgerRange(90, 102))))
        rpc.transactionResult = XrpTransactionLookup(signed.hash, validated = false, ledgerIndex = null, resultCode = null)
        assertEquals(XrpPendingState.SUBMITTED, submitter.reconcile(source)?.state)

        rpc.transactionResult = XrpTransactionLookup(signed.hash, validated = true, ledgerIndex = 102, resultCode = "tesSUCCESS")
        assertEquals(XrpPendingState.VALIDATED_SUCCESS, submitter.reconcile(source)?.state)
    }

    @Test
    fun `blocks sequence conflict before expiry and refuses a second blob`() = runTest {
        val store = InMemoryXrpPendingTransactionStore()
        val rpc = FakeRpc().apply { onSubmit = { XrpSubmitResult("tesSUCCESS", null) } }
        val submitter = XrpReliableSubmitter(rpc, store)
        submitter.submit(signed)

        assertThrows(XrpPendingTransactionExistsException::class.java) {
            val otherPayment = payment(amountDrops = 101)
            val otherBlob = otherPayment.signedBlob.toHex()
            kotlinx.coroutines.runBlocking {
                submitter.submit(
                    signed.copy(
                        hash = otherPayment.hash,
                        blob = otherBlob
                    )
                )
            }
        }

        rpc.server = state(103, XrpLedgerRanges(listOf(XrpLedgerRange(90, 103))))
        rpc.transactionResult = null
        rpc.account = XrpAccountState(source, 4_000_000, 8, 0, false, 103)
        val reconciled = submitter.reconcile(source)
        assertEquals(XrpPendingState.BLOCKED_SEQUENCE_CONFLICT, reconciled?.state)
    }

    @Test
    fun `account deletion before expiry becomes durable conflict across restart`() = runTest {
        val directory = Files.createTempDirectory("xrp-account-delete-test").toFile()
        try {
            FileXrpPendingTransactionStore(directory).save(
                XrpPendingTransaction.from(signed).copy(state = XrpPendingState.SUBMITTED)
            )
            val rpc = FakeRpc().apply {
                server = state(103, XrpLedgerRanges(listOf(XrpLedgerRange(90, 103))))
                transactionResult = null
                account = null
            }

            val reconciled = XrpReliableSubmitter(
                rpc,
                FileXrpPendingTransactionStore(directory),
            ).reconcile(source)

            assertEquals(XrpPendingState.BLOCKED_SEQUENCE_CONFLICT, reconciled?.state)
            assertEquals(
                XrpPendingState.BLOCKED_SEQUENCE_CONFLICT,
                FileXrpPendingTransactionStore(directory).get(source)?.state,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `expires only beyond last ledger with complete coverage`() = runTest {
        val store = InMemoryXrpPendingTransactionStore().apply {
            save(XrpPendingTransaction.from(signed))
        }
        val rpc = FakeRpc().apply {
            server = state(105, XrpLedgerRanges(listOf(XrpLedgerRange(90, 105))))
            transactionResult = null
            account = XrpAccountState(source, 4_000_000, 7, 0, false, 105)
        }
        assertEquals(XrpPendingState.EXPIRED, XrpReliableSubmitter(rpc, store).reconcile(source)?.state)

        store.save(XrpPendingTransaction.from(signed))
        rpc.server = state(105, XrpLedgerRanges(listOf(XrpLedgerRange(103, 105))))
        assertThrows(XrpLedgerGapException::class.java) {
            kotlinx.coroutines.runBlocking { XrpReliableSubmitter(rpc, store).reconcile(source) }
        }
        assertEquals(XrpPendingState.PERSISTED, store.get(source)?.state)
    }

    @Test
    fun `unvalidated lookup cannot keep an expired blob pending`() = runTest {
        val store = InMemoryXrpPendingTransactionStore().apply {
            save(XrpPendingTransaction.from(signed))
        }
        val rpc = FakeRpc().apply {
            server = state(105, XrpLedgerRanges(listOf(XrpLedgerRange(90, 105))))
            transactionResult = XrpTransactionLookup(signed.hash, validated = false, null, null)
        }

        assertEquals(
            XrpPendingState.EXPIRED,
            XrpReliableSubmitter(rpc, store).reconcile(source)?.state
        )
    }

    @Test
    fun `rejects hash that does not commit to signed blob`() {
        assertThrows(IllegalArgumentException::class.java) {
            signed.copy(hash = "A".repeat(64))
        }
    }

    @Test
    fun `rejects durable metadata that does not match the decoded signed Payment`() {
        assertThrows(IllegalArgumentException::class.java) {
            signed.copy(sourceAddress = destination)
        }
        assertThrows(IllegalArgumentException::class.java) {
            signed.copy(sequence = signed.sequence + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            signed.copy(lastLedgerSequence = signed.lastLedgerSequence + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            signed.copy(preparedLedgerIndex = signed.preparedLedgerIndex - 1)
        }
    }

    @Test
    fun `file store survives recreation without changing signed commitments`() {
        val directory = Files.createTempDirectory("xrp-pending-test").toFile()
        try {
            val first = FileXrpPendingTransactionStore(directory)
            val pending = XrpPendingTransaction.from(signed).copy(provisionalResult = "tesSUCCESS")
            first.save(pending)

            val restored = FileXrpPendingTransactionStore(directory).get(source)

            assertEquals(pending, restored)
            assertTrue(directory.listFiles().orEmpty().single().name.endsWith(".json"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun payment(amountDrops: Long) = XrpPaymentSigner.sign(
        XrpPaymentInput(
            privateKey = privateKey,
            account = source,
            destination = destination,
            destinationTag = null,
            amountDrops = amountDrops,
            feeDrops = 10,
            sequence = 7u,
            lastLedgerSequence = 104u,
            memo = null,
        )
    )
}

private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
private fun ByteArray.toHex() = joinToString("") { "%02X".format(it.toInt() and 0xff) }
