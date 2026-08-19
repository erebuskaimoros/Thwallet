package io.horizontalsystems.walletkit.chain.xrp

import org.bouncycastle.asn1.sec.SECNamedCurves
import java.math.BigInteger
import java.security.MessageDigest

data class XrpDestination(
    val classicAddress: String,
    val destinationTag: UInt?,
)

/** Strict mainnet XRPL classic/X-address codec. */
object XrpAddressCodec {
    private const val alphabet = "rpshnaf39wBUDNEGHJKLM4PQRST7VWXYZ2bcdeCg65jkm8oFqi1tuvAxyz"
    private val mainnetXPrefix = byteArrayOf(0x05, 0x44)
    private val testnetXPrefix = byteArrayOf(0x04, 0x93.toByte())

    fun classicAddress(privateKey: ByteArray): String =
        classicAddressFromPublicKey(compressedPublicKey(privateKey))

    fun classicAddressFromPublicKey(publicKey: ByteArray): String {
        require(publicKey.size == 33) { "A compressed secp256k1 public key is required" }
        return classicAddressFromAccountId(ripemd160(sha256(publicKey)))
    }

    internal fun classicAddressFromAccountId(accountId: ByteArray): String {
        require(accountId.size == 20) { "An XRPL AccountID must be 20 bytes" }
        return encodeChecked(byteArrayOf(0) + accountId)
    }

    fun parse(value: String): XrpDestination {
        val decoded = decodeChecked(value)
        return when {
            decoded.size == 21 && decoded[0] == 0.toByte() -> {
                XrpDestination(encodeChecked(decoded), null)
            }

            decoded.size == 31 && decoded.copyOfRange(0, 2).contentEquals(mainnetXPrefix) -> {
                require(decoded.sliceArray(27 until 31).all { it == 0.toByte() }) {
                    "X-address reserved bytes must be zero"
                }
                val tag = when (decoded[22].toInt() and 0xff) {
                    0 -> {
                        require(decoded.sliceArray(23 until 27).all { it == 0.toByte() }) {
                            "An X-address without a tag must contain a zero tag value"
                        }
                        null
                    }

                    1 -> littleEndianUInt(decoded, 23)
                    else -> throw IllegalArgumentException("Invalid X-address tag flag")
                }
                val classic = encodeChecked(byteArrayOf(0) + decoded.copyOfRange(2, 22))
                XrpDestination(classic, tag)
            }

            decoded.size == 31 && decoded.copyOfRange(0, 2).contentEquals(testnetXPrefix) -> {
                throw IllegalArgumentException("Testnet X-addresses are not supported")
            }

            else -> throw IllegalArgumentException("Unsupported XRP address")
        }
    }

    fun resolveDestination(value: String, explicitTag: UInt?): XrpDestination {
        val parsed = parse(value)
        if (parsed.destinationTag != null && explicitTag != null) {
            require(parsed.destinationTag == explicitTag) { "Destination tags conflict" }
        }
        return parsed.copy(destinationTag = parsed.destinationTag ?: explicitTag)
    }

    fun classicToXAddress(classicAddress: String, destinationTag: UInt?): String {
        val classic = parse(classicAddress)
        require(classic.destinationTag == null) { "A classic address is required" }
        val accountId = decodeChecked(classic.classicAddress).copyOfRange(1, 21)
        val payload = ByteArray(31)
        mainnetXPrefix.copyInto(payload, 0)
        accountId.copyInto(payload, 2)
        if (destinationTag != null) {
            payload[22] = 1
            writeLittleEndian(destinationTag, payload, 23)
        }
        return encodeChecked(payload)
    }

    internal fun accountId(classicAddress: String): ByteArray {
        val parsed = parse(classicAddress)
        require(parsed.destinationTag == null) { "A classic address is required" }
        return decodeChecked(parsed.classicAddress).copyOfRange(1, 21)
    }

    internal fun compressedPublicKey(privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32) { "A 32-byte secp256k1 private key is required" }
        val curve = SECNamedCurves.getByName("secp256k1")
        val scalar = BigInteger(1, privateKey)
        require(scalar.signum() > 0 && scalar < curve.n) { "Invalid secp256k1 private key" }
        return curve.g.multiply(scalar).normalize().getEncoded(true)
    }

    private fun encodeChecked(payload: ByteArray): String =
        encodeBase58(payload + doubleSha256(payload).copyOfRange(0, 4))

    private fun decodeChecked(value: String): ByteArray {
        require(value.isNotEmpty()) { "XRP address is empty" }
        val decoded = decodeBase58(value)
        require(decoded.size >= 5) { "XRP address is too short" }
        val payload = decoded.copyOfRange(0, decoded.size - 4)
        val checksum = decoded.copyOfRange(decoded.size - 4, decoded.size)
        require(checksum.contentEquals(doubleSha256(payload).copyOfRange(0, 4))) {
            "Invalid XRP address checksum"
        }
        return payload
    }

    private fun encodeBase58(bytes: ByteArray): String {
        var value = BigInteger(1, bytes)
        val base = BigInteger.valueOf(58)
        val result = StringBuilder()
        while (value.signum() > 0) {
            val division = value.divideAndRemainder(base)
            result.append(alphabet[division[1].toInt()])
            value = division[0]
        }
        bytes.takeWhile { it == 0.toByte() }.forEach { _ -> result.append(alphabet[0]) }
        return result.reverse().toString()
    }

    private fun decodeBase58(value: String): ByteArray {
        var number = BigInteger.ZERO
        val base = BigInteger.valueOf(58)
        value.forEach { character ->
            val index = alphabet.indexOf(character)
            require(index >= 0) { "Invalid XRP Base58 character" }
            number = number.multiply(base).add(BigInteger.valueOf(index.toLong()))
        }
        var decoded = number.toByteArray()
        if (decoded.size > 1 && decoded[0] == 0.toByte()) decoded = decoded.copyOfRange(1, decoded.size)
        val leadingZeros = value.takeWhile { it == alphabet[0] }.length
        return ByteArray(leadingZeros) + decoded
    }

    private fun littleEndianUInt(bytes: ByteArray, offset: Int): UInt =
        ((bytes[offset].toUInt() and 0xffu) or
            ((bytes[offset + 1].toUInt() and 0xffu) shl 8) or
            ((bytes[offset + 2].toUInt() and 0xffu) shl 16) or
            ((bytes[offset + 3].toUInt() and 0xffu) shl 24))

    private fun writeLittleEndian(value: UInt, bytes: ByteArray, offset: Int) {
        repeat(4) { index -> bytes[offset + index] = (value shr (index * 8)).toByte() }
    }

    internal fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
    private fun doubleSha256(value: ByteArray): ByteArray = sha256(sha256(value))

    private fun ripemd160(value: ByteArray): ByteArray {
        val digest = org.bouncycastle.crypto.digests.RIPEMD160Digest()
        digest.update(value, 0, value.size)
        return ByteArray(digest.digestSize).also { digest.doFinal(it, 0) }
    }
}
