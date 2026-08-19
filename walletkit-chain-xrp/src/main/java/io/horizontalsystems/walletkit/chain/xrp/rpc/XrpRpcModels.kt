package io.horizontalsystems.walletkit.chain.xrp.rpc

import com.google.gson.JsonElement
import com.google.gson.JsonParser

internal const val XRP_MAX_NATIVE_DROPS = 100_000_000_000_000_000L

data class XrpLedgerRange(val first: Long, val last: Long) {
    init {
        require(first >= 0) { "Ledger range cannot start below zero" }
        require(last >= first) { "Ledger range is reversed" }
    }

    fun covers(firstLedger: Long, lastLedger: Long): Boolean =
        firstLedger >= first && lastLedger <= last
}

data class XrpLedgerRanges(val ranges: List<XrpLedgerRange>) {
    init {
        require(ranges.isNotEmpty()) { "At least one complete ledger range is required" }
        require(ranges == ranges.sortedBy { it.first }) { "Ledger ranges must be sorted" }
        require(ranges.zipWithNext().all { (left, right) -> left.last < right.first }) {
            "Ledger ranges must not overlap"
        }
    }

    fun covers(firstLedger: Long, lastLedger: Long): Boolean =
        firstLedger <= lastLedger && ranges.any { it.covers(firstLedger, lastLedger) }

    fun rangeContaining(ledger: Long): XrpLedgerRange? = ranges.firstOrNull { ledger in it.first..it.last }

    companion object {
        fun parse(value: String): XrpLedgerRanges {
            val parsed = value.split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .map { part ->
                    val bounds = part.split('-', limit = 2)
                    val first = bounds[0].toLongOrNull()
                        ?: throw XrpProtocolException("Invalid complete_ledgers value")
                    val last = bounds.getOrNull(1)?.toLongOrNull() ?: first
                    XrpLedgerRange(first, last)
                }
                .sortedBy { it.first }
            return try {
                XrpLedgerRanges(parsed)
            } catch (error: IllegalArgumentException) {
                throw XrpProtocolException("Invalid complete_ledgers value", error)
            }
        }
    }
}

data class XrpServerState(
    val networkId: Int,
    val validatedLedgerIndex: Long,
    val completeLedgers: XrpLedgerRanges,
    val reserveBaseDrops: Long,
    val reserveIncrementDrops: Long
)

data class XrpAccountState(
    val address: String,
    val balanceDrops: Long,
    val sequence: Long,
    val ownerCount: Long,
    val requireDestinationTag: Boolean,
    val ledgerIndex: Long
)

data class XrpFeeQuote(
    val baseFeeDrops: Long,
    val minimumFeeDrops: Long,
    val medianFeeDrops: Long,
    val openLedgerFeeDrops: Long,
    val currentLedgerIndex: Long
)

data class XrpAccountSnapshot(
    val ledgerIndex: Long,
    val completeLedgers: XrpLedgerRanges,
    val balanceDrops: Long,
    val sequence: Long,
    val ownerCount: Long,
    val reserveBaseDrops: Long,
    val reserveIncrementDrops: Long,
    val spendableDrops: Long,
    val feeDrops: Long,
    val requireDestinationTag: Boolean
)

data class XrpDestinationState(
    val active: Boolean,
    val minimumAmountDrops: Long,
    val requireDestinationTag: Boolean
)

data class XrpPaymentPreflight(
    val source: XrpAccountSnapshot,
    val destination: XrpDestinationState,
)

class XrpPageMarker private constructor(val encoded: String) {
    internal fun asJsonElement(): JsonElement = JsonParser.parseString(encoded)

    override fun equals(other: Any?): Boolean = other is XrpPageMarker && encoded == other.encoded
    override fun hashCode(): Int = encoded.hashCode()
    override fun toString(): String = encoded

    companion object {
        fun fromEncoded(encoded: String): XrpPageMarker {
            val element = try {
                JsonParser.parseString(encoded)
            } catch (error: RuntimeException) {
                throw XrpProtocolException("Invalid account_tx marker", error)
            }
            val supported = element.isJsonObject || (element.isJsonPrimitive && element.asJsonPrimitive.isString)
            if (!supported) throw XrpProtocolException("account_tx marker must be an object or string")
            return XrpPageMarker(element.toString())
        }

        internal fun fromJson(element: JsonElement): XrpPageMarker = fromEncoded(element.toString())
    }
}

data class XrpNativeTransaction(
    val hash: String,
    val ledgerIndex: Long,
    val timestampEpochSeconds: Long?,
    val source: String,
    val destination: String,
    val destinationTag: UInt?,
    val amountDrops: Long,
    val feeDrops: Long,
    val sequence: Long,
    val incoming: Boolean,
    val successful: Boolean
)

data class XrpTransactionPage(
    val transactions: List<XrpNativeTransaction>,
    val marker: XrpPageMarker?,
    val ledgerMin: Long,
    val ledgerMax: Long
)

data class XrpTransactionLookup(
    val hash: String,
    val validated: Boolean,
    val ledgerIndex: Long?,
    val resultCode: String?
)

data class XrpSubmitResult(val engineResult: String, val message: String?)

open class XrpRpcException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class XrpProtocolException(message: String, cause: Throwable? = null) : XrpRpcException(message, cause)
class XrpNetworkMismatchException(val actualNetworkId: Int) :
    XrpRpcException("Expected XRP Ledger mainnet network_id 0, got $actualNetworkId")
class XrpFeeTooHighException(val requestedDrops: Long, val maximumDrops: Long) :
    XrpRpcException("XRP fee $requestedDrops drops exceeds configured maximum $maximumDrops drops")
class XrpFeeApprovalExceededException(val selectedDrops: Long, val approvedDrops: Long) :
    XrpRpcException("XRP fee $selectedDrops drops exceeds the approved maximum $approvedDrops drops")
class XrpInactiveAccountException(address: String) : XrpRpcException("XRP account is not active: $address")
class XrpDestinationTagRequiredException(address: String) :
    XrpRpcException("Destination account requires a destination tag: $address")
class XrpLedgerGapException(message: String) : XrpRpcException(message)

interface XrpRpc {
    suspend fun serverState(): XrpServerState
    suspend fun accountState(address: String, ledgerIndex: Long): XrpAccountState?
    suspend fun feeQuote(): XrpFeeQuote
    suspend fun accountTransactions(
        address: String,
        ledgerMin: Long,
        ledgerMax: Long,
        marker: XrpPageMarker?
    ): XrpTransactionPage
    suspend fun transaction(hash: String): XrpTransactionLookup?
    suspend fun submit(signedBlob: String): XrpSubmitResult
}
