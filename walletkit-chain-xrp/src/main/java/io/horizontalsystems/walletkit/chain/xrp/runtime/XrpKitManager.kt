package io.horizontalsystems.walletkit.chain.xrp.runtime

import android.content.Context
import io.horizontalsystems.walletkit.chain.xrp.XrpAddressCodec
import io.horizontalsystems.walletkit.chain.xrp.XrpKeyDerivation
import io.horizontalsystems.walletkit.chain.xrp.history.SqliteXrpHistoryStore
import io.horizontalsystems.walletkit.chain.xrp.rpc.XrpJsonRpcClient
import io.horizontalsystems.walletkit.chain.xrp.submission.FileXrpPendingTransactionStore
import io.horizontalsystems.walletkit.entities.Account
import io.horizontalsystems.walletkit.entities.AccountType
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class XrpKitManager(
    context: Context,
    private val endpoint: String = DEFAULT_MAINNET_ENDPOINT,
) {
    private val rootDirectory = context.filesDir.resolve("xrp-wallet-v1")
    private val holders = mutableMapOf<String, Holder>()

    @Synchronized
    fun getKit(account: Account): XrpWalletEngine {
        val existing = holders[account.id]
        if (existing != null) {
            existing.references += 1
            return existing.kit
        }
        val accountDirectory = accountDirectory(account.id)
        val credentials = credentials(account.type)
        var historyStore: SqliteXrpHistoryStore? = null
        val kit = try {
            historyStore = SqliteXrpHistoryStore(accountDirectory.resolve("history.sqlite"))
            XrpWalletEngine(
                address = credentials.address,
                privateKey = credentials.privateKey,
                rpc = XrpJsonRpcClient(endpoint),
                historyStore = historyStore,
                pendingStore = FileXrpPendingTransactionStore(accountDirectory.resolve("pending")),
            )
        } catch (error: Throwable) {
            historyStore?.close()
            throw error
        } finally {
            credentials.privateKey?.fill(0)
        }
        holders[account.id] = Holder(kit, 1)
        return kit
    }

    @Synchronized
    fun unlink(account: Account) {
        val holder = holders[account.id] ?: return
        holder.references -= 1
        if (holder.references <= 0) {
            holder.kit.dispose()
            holders.remove(account.id)
        }
    }

    @Synchronized
    fun clear(accountId: String) {
        holders.remove(accountId)?.kit?.dispose()
        accountDirectory(accountId).deleteRecursively()
    }

    @Synchronized
    fun refresh() {
        holders.values.forEach { it.kit.refresh() }
    }

    @get:Synchronized
    val statusInfo: Map<String, Any>?
        get() = holders.takeIf { it.isNotEmpty() }?.mapValues { (_, holder) ->
            mapOf(
                "address" to holder.kit.address,
                "syncState" to holder.kit.syncState.value.toString(),
                "ledger" to (holder.kit.lastLedgerIndex.value ?: "unknown"),
                "pending" to (holder.kit.pendingTransaction?.state?.name ?: "none"),
                "lastSubmission" to (holder.kit.lastSubmission.value?.state?.name ?: "none"),
                "watchOnly" to !holder.kit.canSign,
                "references" to holder.references,
            )
        }

    fun address(accountType: AccountType): String = credentials(accountType).address

    private fun credentials(accountType: AccountType): Credentials = when (accountType) {
        is AccountType.Mnemonic -> {
            val key = XrpKeyDerivation.privateKey(accountType.seed)
            Credentials(XrpAddressCodec.classicAddress(key), key)
        }
        is AccountType.XrpAddress -> {
            val parsed = XrpAddressCodec.parse(accountType.address)
            require(parsed.destinationTag == null) { "Watch-only XRP account cannot contain a destination tag" }
            Credentials(parsed.classicAddress, null)
        }
        else -> throw IllegalArgumentException("Account type does not support XRP Ledger")
    }

    private fun accountDirectory(accountId: String): File {
        require(accountId.isNotBlank()) { "Account id is required" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(accountId.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return rootDirectory.resolve(digest)
    }

    private data class Holder(val kit: XrpWalletEngine, var references: Int)
    private data class Credentials(val address: String, val privateKey: ByteArray?)

    companion object {
        /** Ripple-operated public JSON-RPC endpoint; every response is still mainnet/ledger checked. */
        const val DEFAULT_MAINNET_ENDPOINT = "https://s1.ripple.com:51234/"
    }
}
