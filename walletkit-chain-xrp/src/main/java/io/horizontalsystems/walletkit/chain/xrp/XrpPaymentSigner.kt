package io.horizontalsystems.walletkit.chain.xrp

import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.crypto.signers.HMacDSAKCalculator
import java.io.ByteArrayOutputStream
import java.math.BigInteger

data class XrpPaymentInput(
    val privateKey: ByteArray,
    val account: String,
    val destination: String,
    val destinationTag: UInt?,
    val amountDrops: Long,
    val feeDrops: Long,
    val sequence: UInt,
    val lastLedgerSequence: UInt,
    val memo: String?,
)

data class XrpPaymentCommitments(
    val account: String,
    val destination: String,
    val destinationTag: UInt?,
    val amountDrops: Long,
    val feeDrops: Long,
    val sequence: UInt,
    val lastLedgerSequence: UInt,
    val memo: String?,
    val signingPublicKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is XrpPaymentCommitments &&
        account == other.account && destination == other.destination &&
        destinationTag == other.destinationTag && amountDrops == other.amountDrops &&
        feeDrops == other.feeDrops && sequence == other.sequence &&
        lastLedgerSequence == other.lastLedgerSequence && memo == other.memo &&
        signingPublicKey.contentEquals(other.signingPublicKey)

    override fun hashCode(): Int = 31 * account.hashCode() + signingPublicKey.contentHashCode()
}

class XrpSignedPayment(
    val signedBlob: ByteArray,
    val hash: String,
    val commitments: XrpPaymentCommitments,
) {
    override fun equals(other: Any?): Boolean = other is XrpSignedPayment &&
        signedBlob.contentEquals(other.signedBlob) && hash == other.hash && commitments == other.commitments

    override fun hashCode(): Int = 31 * signedBlob.contentHashCode() + hash.hashCode()
}

/** Canonical serializer/signature boundary for one native-XRP Payment shape. */
object XrpPaymentSigner {
    private val signingPrefix = byteArrayOf(0x53, 0x54, 0x58, 0x00)
    private val transactionPrefix = byteArrayOf(0x54, 0x58, 0x4e, 0x00)
    private const val fullyCanonicalSignatureFlag = 0x80000000u
    private const val maxMemoBytes = 256
    private const val maxNativeDrops = 100_000_000_000_000_000L

    fun sign(input: XrpPaymentInput): XrpSignedPayment {
        validateDrops(input.amountDrops, "amount")
        validateDrops(input.feeDrops, "fee")
        require(input.amountDrops > 0) { "Payment amount must be positive" }
        require(input.feeDrops > 0) { "Fee must be positive" }
        require(input.lastLedgerSequence > 0u) { "LastLedgerSequence is invalid" }
        val account = XrpAddressCodec.parse(input.account)
        val destination = XrpAddressCodec.resolveDestination(input.destination, input.destinationTag)
        require(account.destinationTag == null) { "Source must be a classic address" }
        val publicKey = XrpAddressCodec.compressedPublicKey(input.privateKey)
        require(XrpAddressCodec.classicAddressFromPublicKey(publicKey) == account.classicAddress) {
            "Private key does not match the source account"
        }
        val memo = input.memo?.also {
            require(it.toByteArray(Charsets.UTF_8).size <= maxMemoBytes) { "Memo is too large" }
        }
        val commitments = XrpPaymentCommitments(
            account.classicAddress,
            destination.classicAddress,
            destination.destinationTag,
            input.amountDrops,
            input.feeDrops,
            input.sequence,
            input.lastLedgerSequence,
            memo,
            publicKey,
        )
        val unsigned = serialize(commitments, signature = null)
        val signature = signDigest(input.privateKey, sha512Half(signingPrefix + unsigned))
        val signed = serialize(commitments, signature)
        val hash = sha512Half(transactionPrefix + signed).toHex().uppercase()
        return decodeAndVerifyCommitments(signed, hash)
    }

    fun decodeAndVerifyCommitments(signedBlob: ByteArray, expectedHash: String): XrpSignedPayment {
        val actualHash = sha512Half(transactionPrefix + signedBlob).toHex().uppercase()
        require(actualHash == expectedHash.uppercase()) { "Signed transaction hash does not match" }
        val parsed = Parser(signedBlob).parse()
        val unsigned = serialize(parsed.commitments, signature = null)
        require(verifyDigest(parsed.commitments.signingPublicKey, sha512Half(signingPrefix + unsigned), parsed.signature)) {
            "Transaction signature is invalid"
        }
        require(serialize(parsed.commitments, parsed.signature).contentEquals(signedBlob)) {
            "Transaction is not canonically serialized"
        }
        return XrpSignedPayment(signedBlob.copyOf(), actualHash, parsed.commitments)
    }

    private fun serialize(commitments: XrpPaymentCommitments, signature: ByteArray?): ByteArray =
        ByteArrayOutputStream().apply {
            write(byteArrayOf(0x12, 0x00, 0x00)) // TransactionType: Payment
            write(byteArrayOf(0x22)); writeUInt(fullyCanonicalSignatureFlag)
            write(byteArrayOf(0x24)); writeUInt(commitments.sequence)
            commitments.destinationTag?.let { write(byteArrayOf(0x2e)); writeUInt(it) }
            write(byteArrayOf(0x20, 0x1b)); writeUInt(commitments.lastLedgerSequence)
            write(byteArrayOf(0x61)); writeNativeAmount(commitments.amountDrops)
            write(byteArrayOf(0x68)); writeNativeAmount(commitments.feeDrops)
            write(byteArrayOf(0x73)); writeVariable(commitments.signingPublicKey)
            signature?.let { write(byteArrayOf(0x74)); writeVariable(it) }
            write(byteArrayOf(0x81.toByte())); writeAccount(commitments.account)
            write(byteArrayOf(0x83.toByte())); writeAccount(commitments.destination)
            commitments.memo?.let { value ->
                write(byteArrayOf(0xf9.toByte(), 0xea.toByte(), 0x7d))
                writeVariable(value.toByteArray(Charsets.UTF_8))
                write(byteArrayOf(0xe1.toByte(), 0xf1.toByte()))
            }
        }.toByteArray()

    private fun ByteArrayOutputStream.writeUInt(value: UInt) {
        write(byteArrayOf((value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte()))
    }

    private fun ByteArrayOutputStream.writeNativeAmount(drops: Long) {
        val encoded = drops or 0x4000000000000000L
        write(ByteArray(8) { index -> (encoded ushr (56 - index * 8)).toByte() })
    }

    private fun ByteArrayOutputStream.writeAccount(address: String) {
        val accountId = XrpAddressCodec.accountId(address)
        write(20)
        write(accountId)
    }

    private fun ByteArrayOutputStream.writeVariable(value: ByteArray) {
        write(encodeVariableLength(value.size))
        write(value)
    }

    private fun encodeVariableLength(length: Int): ByteArray = when {
        length <= 192 -> byteArrayOf(length.toByte())
        length <= 12_480 -> {
            val adjusted = length - 193
            byteArrayOf((193 + (adjusted ushr 8)).toByte(), (adjusted and 0xff).toByte())
        }
        length <= 918_744 -> {
            val adjusted = length - 12_481
            byteArrayOf(
                (241 + (adjusted ushr 16)).toByte(),
                ((adjusted ushr 8) and 0xff).toByte(),
                (adjusted and 0xff).toByte(),
            )
        }
        else -> throw IllegalArgumentException("Variable-length field is too large")
    }

    private fun validateDrops(value: Long, label: String) {
        require(value in 0..maxNativeDrops) { "Invalid $label drops" }
    }

    private fun signDigest(privateKey: ByteArray, digest: ByteArray): ByteArray {
        val domain = domainParameters()
        val scalar = BigInteger(1, privateKey)
        val signer = ECDSASigner(HMacDSAKCalculator(SHA256Digest()))
        signer.init(true, ECPrivateKeyParameters(scalar, domain))
        val signature = signer.generateSignature(digest)
        val lowS = if (signature[1] > domain.n.shiftRight(1)) domain.n - signature[1] else signature[1]
        return DERSequence(arrayOf(ASN1Integer(signature[0]), ASN1Integer(lowS))).encoded
    }

    private fun verifyDigest(publicKey: ByteArray, digest: ByteArray, signature: ByteArray): Boolean {
        val sequence = runCatching { org.bouncycastle.asn1.ASN1Sequence.getInstance(signature) }.getOrNull() ?: return false
        if (sequence.size() != 2) return false
        val r = ASN1Integer.getInstance(sequence.getObjectAt(0)).value
        val s = ASN1Integer.getInstance(sequence.getObjectAt(1)).value
        val domain = domainParameters()
        if (r.signum() <= 0 || r >= domain.n || s.signum() <= 0 || s > domain.n.shiftRight(1)) return false
        val canonicalDer = DERSequence(arrayOf(ASN1Integer(r), ASN1Integer(s))).encoded
        if (!canonicalDer.contentEquals(signature)) return false
        val point = runCatching { domain.curve.decodePoint(publicKey) }.getOrNull() ?: return false
        return ECDSASigner().apply { init(false, ECPublicKeyParameters(point, domain)) }.verifySignature(digest, r, s)
    }

    private fun domainParameters(): ECDomainParameters {
        val curve = SECNamedCurves.getByName("secp256k1")
        return ECDomainParameters(curve.curve, curve.g, curve.n, curve.h)
    }

    private fun sha512Half(value: ByteArray): ByteArray {
        val digest = SHA512Digest()
        digest.update(value, 0, value.size)
        return ByteArray(digest.digestSize).also { digest.doFinal(it, 0) }.copyOfRange(0, 32)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private data class Parsed(val commitments: XrpPaymentCommitments, val signature: ByteArray)

    private class Parser(private val bytes: ByteArray) {
        private var offset = 0

        fun parse(): Parsed {
            expect(0x12); expect(0x00); expect(0x00)
            expect(0x22); require(readUInt() == fullyCanonicalSignatureFlag) { "Canonical signature flag is required" }
            expect(0x24); val sequence = readUInt()
            val destinationTag = if (peek() == 0x2e) { offset++; readUInt() } else null
            expect(0x20); expect(0x1b); val lastLedgerSequence = readUInt()
            expect(0x61); val amount = readNativeAmount()
            expect(0x68); val fee = readNativeAmount()
            expect(0x73); val publicKey = readVariable().also { require(it.size == 33) }
            expect(0x74); val signature = readVariable().also { require(it.size in 68..72) }
            expect(0x81); val account = readAccount()
            expect(0x83); val destination = readAccount()
            val memo = if (offset < bytes.size) {
                expect(0xf9); expect(0xea); expect(0x7d)
                val value = readVariable()
                require(value.size <= maxMemoBytes) { "Memo is too large" }
                expect(0xe1); expect(0xf1)
                value.toString(Charsets.UTF_8).also {
                    require(it.toByteArray(Charsets.UTF_8).contentEquals(value)) { "Memo is not valid UTF-8" }
                }
            } else null
            require(offset == bytes.size) { "Unexpected transaction fields" }
            validateDrops(amount, "amount"); validateDrops(fee, "fee")
            return Parsed(
                XrpPaymentCommitments(account, destination, destinationTag, amount, fee, sequence, lastLedgerSequence, memo, publicKey),
                signature,
            )
        }

        private fun readNativeAmount(): Long {
            require(bytes.size - offset >= 8)
            var encoded = 0L
            repeat(8) { encoded = (encoded shl 8) or (bytes[offset++].toLong() and 0xff) }
            require(encoded and 0xc000000000000000uL.toLong() == 0x4000000000000000L) { "Issued currencies are not supported" }
            return encoded and 0x3fffffffffffffffL
        }

        private fun readUInt(): UInt {
            require(bytes.size - offset >= 4)
            var value = 0u
            repeat(4) { value = (value shl 8) or (bytes[offset++].toUInt() and 0xffu) }
            return value
        }

        private fun readAccount(): String {
            require(readLength() == 20) { "AccountID must be 20 bytes" }
            require(bytes.size - offset >= 20)
            val accountId = bytes.copyOfRange(offset, offset + 20)
            offset += 20
            return XrpAddressCodec.classicAddressFromAccountId(accountId)
        }

        private fun readVariable(): ByteArray {
            val length = readLength()
            require(length >= 0 && bytes.size - offset >= length) { "Truncated variable-length field" }
            return bytes.copyOfRange(offset, offset + length).also { offset += length }
        }

        private fun readLength(): Int {
            require(offset < bytes.size)
            val first = bytes[offset++].toInt() and 0xff
            return when {
                first <= 192 -> first
                first <= 240 -> {
                    require(offset < bytes.size)
                    193 + (first - 193) * 256 + (bytes[offset++].toInt() and 0xff)
                }
                first <= 254 -> {
                    require(bytes.size - offset >= 2)
                    val second = bytes[offset++].toInt() and 0xff
                    val third = bytes[offset++].toInt() and 0xff
                    12_481 + (first - 241) * 65_536 + second * 256 + third
                }
                else -> throw IllegalArgumentException("Invalid variable-length prefix")
            }
        }

        private fun peek(): Int = if (offset < bytes.size) bytes[offset].toInt() and 0xff else -1
        private fun expect(value: Int) {
            require(offset < bytes.size && (bytes[offset++].toInt() and 0xff) == value) { "Unexpected transaction field" }
        }
    }

}
