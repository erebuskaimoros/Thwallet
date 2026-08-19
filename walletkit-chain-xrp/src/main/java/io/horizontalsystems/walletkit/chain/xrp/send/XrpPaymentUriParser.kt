package io.horizontalsystems.walletkit.chain.xrp.send

import io.horizontalsystems.walletkit.chain.xrp.XrpAddressCodec
import io.horizontalsystems.walletkit.chain.xrp.XrpAmount
import java.math.BigDecimal
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class XrpPaymentUri(
    val classicAddress: String,
    val destinationTag: UInt?,
    val amount: BigDecimal?,
    val memo: String?,
)

/** Strict native-XRP payment URI parser. Unknown routing semantics are never discarded. */
object XrpPaymentUriParser {
    fun parse(value: String): XrpPaymentUri {
        val uri = try {
            URI(value)
        } catch (error: Exception) {
            throw IllegalArgumentException("Malformed XRP payment URI", error)
        }
        require(uri.scheme?.equals(SCHEME, ignoreCase = true) == true) {
            "XRP payment URI must use the xrpl scheme"
        }
        require(uri.rawFragment == null) { "XRP payment URI fragments are not supported" }
        val raw = uri.rawSchemeSpecificPart ?: throw IllegalArgumentException("XRP URI has no address")
        require(!raw.startsWith("//")) { "Hierarchical XRP payment URIs are not supported" }
        val parts = raw.split('?', limit = 2)
        val address = decode(parts[0], "address")
        require(address.isNotBlank() && '/' !in address) { "XRP URI address is invalid" }
        val parameters = parts.getOrNull(1)?.let(::parseParameters).orEmpty()

        val tagValues = listOfNotNull(
            parameters[DESTINATION_TAG]?.let(::parseTag),
            parameters[TAG_ALIAS]?.let(::parseTag),
        )
        require(tagValues.distinct().size <= 1) { "XRP URI destination tags conflict" }
        val resolved = XrpAddressCodec.resolveDestination(address, tagValues.firstOrNull())

        val amount = parameters[AMOUNT]?.let { rawAmount ->
            val parsed = rawAmount.toBigDecimalOrNull()
                ?: throw IllegalArgumentException("XRP URI amount is not a decimal")
            require(XrpAmount.toDropsExact(parsed) > 0) { "XRP URI amount must be positive" }
            parsed
        }
        val memo = parameters[MEMO]?.also {
            require(it.toByteArray(StandardCharsets.UTF_8).size <= MAX_MEMO_BYTES) {
                "XRP URI memo cannot exceed $MAX_MEMO_BYTES UTF-8 bytes"
            }
        }
        return XrpPaymentUri(
            classicAddress = resolved.classicAddress,
            destinationTag = resolved.destinationTag,
            amount = amount,
            memo = memo,
        )
    }

    private fun parseParameters(rawQuery: String): Map<String, String> {
        require(rawQuery.isNotEmpty()) { "XRP URI query cannot be empty" }
        val parameters = linkedMapOf<String, String>()
        rawQuery.split('&').forEach { pair ->
            val separator = pair.indexOf('=')
            require(separator > 0) { "Malformed XRP URI parameter" }
            val key = decode(pair.substring(0, separator), "parameter name")
            val parameterValue = decode(pair.substring(separator + 1), key)
            require(key !in parameters) { "Duplicate XRP URI parameter: $key" }
            require(!key.startsWith("req-", ignoreCase = true)) {
                "Unsupported required XRP URI parameter: $key"
            }
            require(key in SUPPORTED_PARAMETERS) { "Unsupported XRP URI parameter: $key" }
            parameters[key] = parameterValue
        }
        return parameters
    }

    private fun parseTag(value: String): UInt {
        require(value.isNotEmpty()) { "XRP URI destination tag cannot be empty" }
        return requireNotNull(XrpDestinationTag.parse(value)) {
            "XRP URI destination tag cannot be empty"
        }
    }

    private fun decode(value: String, field: String): String = try {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    } catch (error: IllegalArgumentException) {
        throw IllegalArgumentException("Malformed percent encoding in XRP URI $field", error)
    }

    private const val SCHEME = "xrpl"
    private const val DESTINATION_TAG = "dt"
    private const val TAG_ALIAS = "tag"
    private const val AMOUNT = "amount"
    private const val MEMO = "memo"
    private const val MAX_MEMO_BYTES = 256
    private val SUPPORTED_PARAMETERS = setOf(DESTINATION_TAG, TAG_ALIAS, AMOUNT, MEMO)
}
