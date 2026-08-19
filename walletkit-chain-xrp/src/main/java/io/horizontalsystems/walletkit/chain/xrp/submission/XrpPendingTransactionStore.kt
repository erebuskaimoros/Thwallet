package io.horizontalsystems.walletkit.chain.xrp.submission

import com.google.gson.Gson
import com.google.gson.JsonParseException
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.channels.FileChannel
import java.security.MessageDigest
import io.horizontalsystems.walletkit.chain.xrp.XrpPaymentSigner

enum class XrpPendingState {
    PERSISTED,
    SUBMITTED,
    BLOCKED_SEQUENCE_CONFLICT,
    VALIDATED_SUCCESS,
    VALIDATED_FAILURE,
    EXPIRED;

    val final: Boolean
        get() = this == BLOCKED_SEQUENCE_CONFLICT || this == VALIDATED_SUCCESS ||
            this == VALIDATED_FAILURE || this == EXPIRED
}

data class XrpSignedTransaction(
    val hash: String,
    val blob: String,
    val sourceAddress: String,
    val sequence: Long,
    val preparedLedgerIndex: Long,
    val lastLedgerSequence: Long,
    val createdAtEpochSeconds: Long
) {
    init {
        require(blob.length >= 2 && blob.length % 2 == 0 && blob.all(Char::isHexDigit)) {
            "Signed XRP blob must be non-empty hexadecimal"
        }
        validateHash(hash)
        require(hash.equals(transactionHash(blob), ignoreCase = true)) {
            "XRP transaction hash does not commit to the signed blob"
        }
        val decoded = XrpPaymentSigner.decodeAndVerifyCommitments(blob.hexToBytes(), hash).commitments
        require(decoded.account == sourceAddress) { "Source address does not match the signed Payment" }
        require(sequence in 1..UInt.MAX_VALUE.toLong()) { "Sequence is outside Payment UInt32 range" }
        require(decoded.sequence.toLong() == sequence) { "Sequence does not match the signed Payment" }
        require(preparedLedgerIndex > 0) { "Prepared ledger index must be positive" }
        require(lastLedgerSequence > preparedLedgerIndex) { "LastLedgerSequence must follow prepared ledger" }
        require(lastLedgerSequence <= UInt.MAX_VALUE.toLong()) { "LastLedgerSequence is outside UInt32 range" }
        require(decoded.lastLedgerSequence.toLong() == lastLedgerSequence) {
            "LastLedgerSequence does not match the signed Payment"
        }
        require(lastLedgerSequence - preparedLedgerIndex == SIGNED_LEDGER_WINDOW) {
            "Prepared ledger does not match the signed ledger-window policy"
        }
        require(createdAtEpochSeconds >= 0) { "Creation time cannot be negative" }
    }

    companion object {
        const val SIGNED_LEDGER_WINDOW = 4L

        fun transactionHash(signedBlob: String): String {
            require(signedBlob.length >= 2 && signedBlob.length % 2 == 0 && signedBlob.all(Char::isHexDigit)) {
                "Signed XRP blob must be non-empty hexadecimal"
            }
            val blobBytes = signedBlob.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val digest = MessageDigest.getInstance("SHA-512").apply {
                update(byteArrayOf(0x54, 0x58, 0x4E, 0x00)) // TXN\0
                update(blobBytes)
            }.digest()
            return digest.copyOfRange(0, 32).joinToString("") { "%02X".format(it) }
        }
    }
}

data class XrpPendingTransaction(
    val hash: String,
    val blob: String,
    val sourceAddress: String,
    val sequence: Long,
    val preparedLedgerIndex: Long,
    val lastLedgerSequence: Long,
    val createdAtEpochSeconds: Long,
    val state: XrpPendingState,
    val provisionalResult: String? = null,
    val validatedLedgerIndex: Long? = null,
    val validatedResult: String? = null
) {
    init {
        XrpSignedTransaction(
            hash,
            blob,
            sourceAddress,
            sequence,
            preparedLedgerIndex,
            lastLedgerSequence,
            createdAtEpochSeconds
        )
        if (validatedLedgerIndex != null) require(validatedLedgerIndex > 0)
        if (state == XrpPendingState.VALIDATED_SUCCESS || state == XrpPendingState.VALIDATED_FAILURE) {
            require(validatedLedgerIndex != null && !validatedResult.isNullOrBlank()) {
                "Validated pending state requires ledger and result commitments"
            }
            require(validatedLedgerIndex in preparedLedgerIndex..lastLedgerSequence) {
                "Validated ledger is outside the signed transaction window"
            }
            require((state == XrpPendingState.VALIDATED_SUCCESS) == (validatedResult == "tesSUCCESS")) {
                "Validated result does not match pending terminal state"
            }
        }
    }

    companion object {
        fun from(signed: XrpSignedTransaction) = XrpPendingTransaction(
            hash = signed.hash,
            blob = signed.blob,
            sourceAddress = signed.sourceAddress,
            sequence = signed.sequence,
            preparedLedgerIndex = signed.preparedLedgerIndex,
            lastLedgerSequence = signed.lastLedgerSequence,
            createdAtEpochSeconds = signed.createdAtEpochSeconds,
            state = XrpPendingState.PERSISTED
        )
    }
}

interface XrpPendingTransactionStore {
    fun get(sourceAddress: String): XrpPendingTransaction?
    fun save(transaction: XrpPendingTransaction)
    fun remove(sourceAddress: String)
}

class InMemoryXrpPendingTransactionStore : XrpPendingTransactionStore {
    private val records = mutableMapOf<String, XrpPendingTransaction>()

    @Synchronized
    override fun get(sourceAddress: String) = records[sourceAddress]

    @Synchronized
    override fun save(transaction: XrpPendingTransaction) {
        records[transaction.sourceAddress] = transaction
    }

    @Synchronized
    override fun remove(sourceAddress: String) {
        records.remove(sourceAddress)
    }
}

class FileXrpPendingTransactionStore(
    private val directory: File,
    private val gson: Gson = Gson()
) : XrpPendingTransactionStore {
    private val lock = Any()

    init {
        if (directory.exists() && !directory.isDirectory) {
            throw XrpPendingStoreException("XRP pending store path is not a directory")
        }
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
            throw XrpPendingStoreException("Unable to create XRP pending store directory")
        }
    }

    override fun get(sourceAddress: String): XrpPendingTransaction? = synchronized(lock) {
        val file = fileFor(sourceAddress)
        if (!file.exists()) return@synchronized null
        try {
            val decoded = gson.fromJson(file.readText(StandardCharsets.UTF_8), XrpPendingTransaction::class.java)
                ?: throw XrpPendingStoreException("XRP pending record is empty")
            // Gson can allocate Kotlin objects without invoking init; copy reconstructs and validates it.
            val transaction = decoded.copy()
            if (transaction.sourceAddress != sourceAddress) {
                throw XrpPendingStoreException("XRP pending record address commitment does not match")
            }
            transaction
        } catch (error: XrpPendingStoreException) {
            throw error
        } catch (error: JsonParseException) {
            throw XrpPendingStoreException("Unable to decode XRP pending record", error)
        } catch (error: IllegalArgumentException) {
            throw XrpPendingStoreException("Invalid XRP pending record", error)
        }
    }

    override fun save(transaction: XrpPendingTransaction) {
        synchronized(lock) {
            val target = fileFor(transaction.sourceAddress)
            val temporary = Files.createTempFile(directory.toPath(), target.name, ".tmp").toFile()
            try {
                FileOutputStream(temporary).use { stream ->
                    stream.write(gson.toJson(transaction).toByteArray(StandardCharsets.UTF_8))
                    stream.fd.sync()
                }
                try {
                    Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                forceDirectory()
            } catch (error: Exception) {
                throw XrpPendingStoreException("Unable to persist XRP pending transaction", error)
            } finally {
                if (temporary.exists() && !temporary.delete()) {
                    temporary.deleteOnExit()
                }
            }
        }
    }

    override fun remove(sourceAddress: String) = synchronized(lock) {
        val target = fileFor(sourceAddress)
        if (target.exists() && !target.delete()) {
            throw XrpPendingStoreException("Unable to remove XRP pending transaction")
        }
        forceDirectory()
    }

    private fun fileFor(sourceAddress: String): File {
        require(sourceAddress.isNotBlank()) { "Source address is required" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sourceAddress.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return directory.resolve("$digest.json")
    }

    private fun forceDirectory() {
        try {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (error: Exception) {
            throw XrpPendingStoreException("Unable to durably sync XRP pending store directory", error)
        }
    }
}

class XrpPendingStoreException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

private fun validateHash(hash: String) {
    require(hash.length == 64 && hash.all(Char::isHexDigit)) { "XRP transaction hash must be 32-byte hex" }
}

private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
