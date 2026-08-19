package io.horizontalsystems.walletkit.chain.xrp.rpc

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

class XrpJsonRpcClient(
    endpoint: String,
    client: OkHttpClient = OkHttpClient.Builder().build()
) : XrpRpc {
    private val api = Retrofit.Builder()
        .baseUrl(endpoint.ensureTrailingSlash())
        .client(client)
        .addConverterFactory(GsonConverterFactory.create(GsonBuilder().create()))
        .build()
        .create(Api::class.java)

    override suspend fun serverState(): XrpServerState {
        val result = callRequired("server_info", JsonObject())
        val info = result.requiredObject("info")
        val networkId = info.requiredInt("network_id")
        if (networkId != MAINNET_NETWORK_ID) throw XrpNetworkMismatchException(networkId)
        val validated = info.requiredObject("validated_ledger")
        val ledgerIndex = validated.requiredLong("seq")
        val completeLedgers = XrpLedgerRanges.parse(info.requiredString("complete_ledgers"))
        if (!completeLedgers.covers(ledgerIndex, ledgerIndex)) {
            throw XrpLedgerGapException("Validated ledger $ledgerIndex is not in complete_ledgers")
        }
        return XrpServerState(
            networkId = networkId,
            validatedLedgerIndex = ledgerIndex,
            completeLedgers = completeLedgers,
            reserveBaseDrops = validated.requiredXrpAsDrops("reserve_base_xrp"),
            reserveIncrementDrops = validated.requiredXrpAsDrops("reserve_inc_xrp")
        )
    }

    override suspend fun accountState(address: String, ledgerIndex: Long): XrpAccountState? {
        val params = JsonObject().apply {
            addProperty("account", address)
            addProperty("ledger_index", ledgerIndex)
            addProperty("queue", false)
            addProperty("strict", true)
        }
        val result = call("account_info", params, allowedError = "actNotFound") ?: return null
        if (result.optionalBoolean("validated") != true) {
            throw XrpProtocolException("account_info did not return validated data")
        }
        val actualLedger = result.requiredLong("ledger_index")
        if (actualLedger != ledgerIndex) {
            throw XrpProtocolException("account_info returned ledger $actualLedger instead of $ledgerIndex")
        }
        val data = result.requiredObject("account_data")
        val returnedAddress = data.requiredString("Account")
        if (returnedAddress != address) {
            throw XrpProtocolException("account_info returned a different account")
        }
        val flags = data.requiredLong("Flags")
        return XrpAccountState(
            address = returnedAddress,
            balanceDrops = data.requiredDrops("Balance", allowZero = true),
            sequence = data.requiredLong("Sequence").requirePaymentSequence("Sequence"),
            ownerCount = data.requiredLong("OwnerCount").also {
                if (it < 0) throw XrpProtocolException("OwnerCount cannot be negative")
            },
            requireDestinationTag = flags and LSF_REQUIRE_DEST_TAG != 0L,
            ledgerIndex = actualLedger
        )
    }

    override suspend fun feeQuote(): XrpFeeQuote {
        val result = callRequired("fee", JsonObject())
        val drops = result.requiredObject("drops")
        return XrpFeeQuote(
            baseFeeDrops = drops.requiredDrops("base_fee"),
            minimumFeeDrops = drops.requiredDrops("minimum_fee"),
            medianFeeDrops = drops.requiredDrops("median_fee"),
            openLedgerFeeDrops = drops.requiredDrops("open_ledger_fee"),
            currentLedgerIndex = result.requiredLong("ledger_current_index")
        )
    }

    override suspend fun accountTransactions(
        address: String,
        ledgerMin: Long,
        ledgerMax: Long,
        marker: XrpPageMarker?
    ): XrpTransactionPage {
        require(ledgerMin >= 0 && ledgerMax >= ledgerMin) { "Invalid account_tx ledger bounds" }
        val params = JsonObject().apply {
            addProperty("account", address)
            addProperty("ledger_index_min", ledgerMin)
            addProperty("ledger_index_max", ledgerMax)
            addProperty("binary", false)
            addProperty("forward", true)
            addProperty("limit", ACCOUNT_TX_PAGE_SIZE)
            marker?.let { add("marker", it.asJsonElement()) }
        }
        val result = callRequired("account_tx", params)
        val actualMin = result.requiredLong("ledger_index_min")
        val actualMax = result.requiredLong("ledger_index_max")
        if (actualMin != ledgerMin || actualMax != ledgerMax) {
            throw XrpLedgerGapException(
                "account_tx returned ledger range $actualMin-$actualMax instead of $ledgerMin-$ledgerMax"
            )
        }
        if (result.optionalBoolean("validated") == false) {
            throw XrpProtocolException("account_tx result was not validated")
        }
        val transactionElements = result.requiredArray("transactions")
        if (transactionElements.size() > ACCOUNT_TX_PAGE_SIZE) {
            throw XrpProtocolException("account_tx exceeded requested page size")
        }
        val transactions = transactionElements.mapNotNull { element ->
            parseAccountTransaction(address, element.requiredObject())
        }
        val nextMarker = result.get("marker")
            ?.takeUnless { it.isJsonNull }
            ?.let(XrpPageMarker::fromJson)
        return XrpTransactionPage(transactions, nextMarker, actualMin, actualMax)
    }

    override suspend fun transaction(hash: String): XrpTransactionLookup? {
        val params = JsonObject().apply {
            addProperty("transaction", hash)
            addProperty("binary", false)
        }
        val result = call("tx", params, allowedError = "txnNotFound") ?: return null
        val actualHash = result.optionalString("hash")
            ?: result.getAsJsonObject("tx_json")?.optionalString("hash")
            ?: throw XrpProtocolException("tx response is missing transaction hash")
        if (!actualHash.equals(hash, ignoreCase = true)) {
            throw XrpProtocolException("tx returned a different transaction hash")
        }
        val validated = result.optionalBoolean("validated") ?: false
        val meta = result.getAsJsonObject("meta")
        return XrpTransactionLookup(
            hash = actualHash.uppercase(),
            validated = validated,
            ledgerIndex = result.optionalLong("ledger_index"),
            resultCode = meta?.optionalString("TransactionResult")
        )
    }

    override suspend fun submit(signedBlob: String): XrpSubmitResult {
        val params = JsonObject().apply {
            addProperty("tx_blob", signedBlob)
            addProperty("fail_hard", false)
        }
        val result = callRequired("submit", params)
        return XrpSubmitResult(
            engineResult = result.requiredString("engine_result"),
            message = result.optionalString("engine_result_message")
        )
    }

    private suspend fun call(method: String, params: JsonObject, allowedError: String? = null): JsonObject? {
        params.addProperty("api_version", 2)
        val request = JsonObject().apply {
            addProperty("method", method)
            add("params", com.google.gson.JsonArray().apply { add(params) })
        }
        val envelope = try {
            api.call(request)
        } catch (error: XrpRpcException) {
            throw error
        } catch (error: Exception) {
            throw XrpRpcException("XRPL $method request failed", error)
        }
        val result = envelope.getAsJsonObject("result")
            ?: throw XrpProtocolException("XRPL $method response is missing result")
        val errorCode = result.optionalString("error") ?: envelope.optionalString("error")
        if (errorCode != null) {
            if (errorCode == allowedError) return null
            val message = result.optionalString("error_message") ?: envelope.optionalString("error_message")
            throw XrpRpcException("XRPL $method failed: $errorCode${message?.let { ": $it" }.orEmpty()}")
        }
        val status = result.optionalString("status")
        if (status != null && status != "success") {
            throw XrpRpcException("XRPL $method returned status $status")
        }
        return result
    }

    private suspend fun callRequired(method: String, params: JsonObject): JsonObject =
        call(method, params) ?: throw XrpProtocolException("XRPL $method unexpectedly returned no result")

    private fun parseAccountTransaction(address: String, wrapper: JsonObject): XrpNativeTransaction? {
        if (wrapper.optionalBoolean("validated") != true) {
            throw XrpProtocolException("account_tx contained an unvalidated transaction")
        }
        val transaction = wrapper.getAsJsonObject("tx_json")
            ?: wrapper.getAsJsonObject("tx")
            ?: throw XrpProtocolException("account_tx transaction is missing tx_json")
        if (transaction.optionalString("TransactionType") != "Payment") return null
        val source = transaction.requiredString("Account")
        val destination = transaction.requiredString("Destination")
        val incoming = destination == address && source != address
        if (!incoming && source != address) return null
        if (listOf("SendMax", "DeliverMin", "Paths").any(transaction::has)) return null
        val legacyAmount = transaction.get("Amount")
        val deliverMax = transaction.get("DeliverMax")
        if (legacyAmount != null && deliverMax != null) return null
        val amountName = if (deliverMax != null) "DeliverMax" else "Amount"
        val amount = deliverMax ?: legacyAmount
            ?: throw XrpProtocolException("Payment is missing Amount or DeliverMax")
        if (!amount.isNativeDrops()) return null

        val meta = wrapper.getAsJsonObject("meta")
            ?: throw XrpProtocolException("account_tx transaction is missing metadata")
        val successful = meta.requiredString("TransactionResult") == "tesSUCCESS"
        val flags = transaction.optionalLong("Flags") ?: 0L
        if (flags and TF_PARTIAL_PAYMENT != 0L) return null
        val delivered = meta.get("delivered_amount") ?: meta.get("DeliveredAmount")
        val amountDrops = when {
            !successful -> 0L
            incoming && delivered?.isNativeDrops() == true -> delivered.asString.toDrops("delivered_amount", false)
            incoming && delivered?.isJsonPrimitive == true && delivered.asString == "unavailable" ->
                amount.asString.toDrops(amountName, false)
            incoming && delivered != null -> return null
            else -> amount.asString.toDrops(amountName, false)
        }
        val hash = wrapper.optionalString("hash")
            ?: transaction.optionalString("hash")
            ?: throw XrpProtocolException("account_tx transaction is missing hash")
        if (!HASH_REGEX.matches(hash)) throw XrpProtocolException("Invalid XRP transaction hash")
        val ledgerIndex = wrapper.optionalLong("ledger_index")
            ?: transaction.optionalLong("ledger_index")
            ?: throw XrpProtocolException("account_tx transaction is missing ledger index")
        val rippleDate = transaction.optionalLong("date")
        val isoDate = wrapper.optionalString("close_time_iso")
        val timestamp = rippleDate?.let { Math.addExact(it, RIPPLE_EPOCH_OFFSET_SECONDS) }
            ?: isoDate?.let {
                try {
                    Instant.parse(it).epochSecond
                } catch (error: RuntimeException) {
                    throw XrpProtocolException("Invalid account_tx close_time_iso", error)
                }
            }
        return XrpNativeTransaction(
            hash = hash.uppercase(),
            ledgerIndex = ledgerIndex,
            timestampEpochSeconds = timestamp,
            source = source,
            destination = destination,
            destinationTag = transaction.optionalLong("DestinationTag")?.toUInt32("DestinationTag"),
            amountDrops = amountDrops,
            feeDrops = transaction.requiredDrops("Fee"),
            sequence = transaction.requiredLong("Sequence").requirePaymentSequence("Sequence"),
            incoming = incoming,
            successful = successful
        )
    }

    private interface Api {
        @POST(".")
        suspend fun call(@Body request: JsonObject): JsonObject
    }

    companion object {
        private const val MAINNET_NETWORK_ID = 0
        private const val ACCOUNT_TX_PAGE_SIZE = 200
        private const val LSF_REQUIRE_DEST_TAG = 0x0002_0000L
        private const val TF_PARTIAL_PAYMENT = 0x0002_0000L
        private const val RIPPLE_EPOCH_OFFSET_SECONDS = 946_684_800L
        private val HASH_REGEX = Regex("^[A-Fa-f0-9]{64}$")
    }
}

private fun String.ensureTrailingSlash() = if (endsWith('/')) this else "$this/"

private fun JsonElement.requiredObject(): JsonObject =
    if (isJsonObject) asJsonObject else throw XrpProtocolException("Expected JSON object")

private fun JsonObject.requiredObject(name: String): JsonObject = get(name)?.requiredObject()
    ?: throw XrpProtocolException("Missing $name")

private fun JsonObject.requiredArray(name: String) = getAsJsonArray(name)
    ?: throw XrpProtocolException("Missing $name")

private fun JsonObject.requiredString(name: String): String = optionalString(name)
    ?: throw XrpProtocolException("Missing or invalid $name")

private fun JsonObject.optionalString(name: String): String? {
    val value = get(name) ?: return null
    if (value.isJsonNull || !value.isJsonPrimitive) return null
    return try {
        value.asString
    } catch (_: RuntimeException) {
        null
    }
}

private fun JsonObject.requiredInt(name: String): Int {
    val value = requiredLong(name)
    if (value !in Int.MIN_VALUE..Int.MAX_VALUE) throw XrpProtocolException("$name is outside Int range")
    return value.toInt()
}

private fun JsonObject.requiredLong(name: String): Long = optionalLong(name)
    ?: throw XrpProtocolException("Missing or invalid $name")

private fun JsonObject.optionalLong(name: String): Long? {
    val value = get(name) ?: return null
    if (value.isJsonNull || !value.isJsonPrimitive) return null
    return value.asJsonPrimitive.asString.toLongOrNull()
}

private fun JsonObject.optionalBoolean(name: String): Boolean? {
    val value = get(name) ?: return null
    if (value.isJsonNull || !value.isJsonPrimitive) return null
    return when (value.asJsonPrimitive.asString) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

private fun JsonObject.requiredDrops(name: String, allowZero: Boolean = false): Long =
    requiredString(name).toDrops(name, allowZero)

private fun String.toDrops(name: String, allowZero: Boolean): Long {
    if (!all(Char::isDigit)) throw XrpProtocolException("$name must be an integer drops string")
    val drops = toLongOrNull() ?: throw XrpProtocolException("$name is outside Long range")
    if (drops < 0 || (!allowZero && drops == 0L)) throw XrpProtocolException("$name must be positive")
    if (drops > XRP_MAX_NATIVE_DROPS) {
        throw XrpProtocolException("$name exceeds the native XRP supply")
    }
    return drops
}

private fun JsonObject.requiredXrpAsDrops(name: String): Long {
    val value = requiredString(name)
    val decimal = try {
        BigDecimal(value)
    } catch (error: NumberFormatException) {
        throw XrpProtocolException("$name is not a decimal XRP value", error)
    }
    return try {
        decimal.movePointRight(6).setScale(0, RoundingMode.UNNECESSARY).longValueExact().also {
            if (it < 0) throw XrpProtocolException("$name cannot be negative")
            if (it > XRP_MAX_NATIVE_DROPS) {
                throw XrpProtocolException("$name exceeds the native XRP supply")
            }
        }
    } catch (error: ArithmeticException) {
        throw XrpProtocolException("$name cannot be represented exactly in drops", error)
    }
}

private fun Long.requireUInt32(name: String): Long {
    if (this !in 0..UInt.MAX_VALUE.toLong()) throw XrpProtocolException("$name is outside UInt32 range")
    return this
}

private fun Long.requirePaymentSequence(name: String): Long {
    requireUInt32(name)
    if (this == 0L) throw XrpProtocolException("$name zero is reserved for ticketed transactions")
    return this
}

private fun Long.toUInt32(name: String): UInt = requireUInt32(name).toUInt()

private fun JsonElement.isNativeDrops(): Boolean =
    isJsonPrimitive && asJsonPrimitive.isString && asString.all(Char::isDigit)
