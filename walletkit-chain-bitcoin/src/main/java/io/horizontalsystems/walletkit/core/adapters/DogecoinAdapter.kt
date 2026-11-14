package io.horizontalsystems.walletkit.core.adapters

import io.horizontalsystems.bitcoincore.BitcoinCore
import io.horizontalsystems.bitcoincore.models.BalanceInfo
import io.horizontalsystems.bitcoincore.models.BlockInfo
import io.horizontalsystems.bitcoincore.models.TransactionInfo
import io.horizontalsystems.dogecoinkit.DogecoinKit
import io.horizontalsystems.dogecoinkit.DogecoinKit.NetworkType
import io.horizontalsystems.marketkit.models.BlockchainType
import io.horizontalsystems.walletkit.core.App
import io.horizontalsystems.walletkit.core.BackgroundManager
import io.horizontalsystems.walletkit.core.ISendBitcoinAdapter
import io.horizontalsystems.walletkit.core.UnsupportedAccountException
import io.horizontalsystems.walletkit.core.UsedAddress
import io.horizontalsystems.walletkit.entities.AccountType
import io.horizontalsystems.walletkit.entities.AccountOrigin
import io.horizontalsystems.walletkit.entities.Wallet
import io.horizontalsystems.walletkit.entities.transactionrecords.TransactionRecord

class DogecoinAdapter(
    override val kit: DogecoinKit,
    syncMode: BitcoinCore.SyncMode,
    backgroundManager: BackgroundManager,
    wallet: Wallet,
) : BitcoinBaseAdapter(kit, syncMode, backgroundManager, wallet),
    DogecoinKit.Listener,
    ISendBitcoinAdapter {

    constructor(wallet: Wallet, syncMode: BitcoinCore.SyncMode, backgroundManager: BackgroundManager) :
        this(createKit(wallet, syncMode), syncMode, backgroundManager, wallet)

    init {
        kit.listener = this
    }

    override val explorerTitle: String
        get() = "blockchair.com"

    override fun getTransactionUrl(transactionHash: String): String =
        "https://blockchair.com/dogecoin/transaction/$transactionHash"

    override fun onBalanceUpdate(balance: BalanceInfo) {
        _balanceUpdatedFlow.tryEmit(Unit)
    }

    override fun onLastBlockInfoUpdate(blockInfo: BlockInfo) {
        _lastBlockUpdatedFlow.tryEmit(Unit)
    }

    override fun onKitStateUpdate(state: BitcoinCore.KitState) {
        setState(state)
    }

    override fun onTransactionsUpdate(
        inserted: List<TransactionInfo>,
        updated: List<TransactionInfo>,
    ) {
        val records = mutableListOf<TransactionRecord>()
        inserted.forEach { records.add(transactionRecord(it)) }
        updated.forEach { records.add(transactionRecord(it)) }
        _transactionRecordsFlow.tryEmit(records)
    }

    override fun onTransactionsDelete(hashes: List<String>) = Unit

    override val blockchainType = BlockchainType.Dogecoin

    override fun usedAddresses(change: Boolean): List<UsedAddress> =
        kit.usedAddresses(change).map {
            UsedAddress(
                it.index,
                it.address,
                "https://blockchair.com/dogecoin/address/${it.address}",
            )
        }

    companion object {
        // Keep Dogecoin consistent with the app's existing UTXO UX: the first mined block
        // transitions a transaction out of the unconfirmed state.
        internal const val confirmationsThreshold = 1

        fun firstAddress(accountType: AccountType): String = when (accountType) {
            is AccountType.Mnemonic -> DogecoinKit.firstAddress(
                accountType.seed,
                NetworkType.MainNet,
            ).stringValue

            is AccountType.HdExtendedKey -> DogecoinKit.firstAddress(
                accountType.hdExtendedKey,
                NetworkType.MainNet,
            ).stringValue

            is AccountType.BitcoinAddress -> accountType.address
            else -> throw UnsupportedAccountException()
        }

        private fun createKit(wallet: Wallet, syncMode: BitcoinCore.SyncMode): DogecoinKit {
            val account = wallet.account
            val fullSyncStart = resolveDogecoinFullSyncStart(account.origin)
            return when (val accountType = account.type) {
                is AccountType.HdExtendedKey -> DogecoinKit(
                    context = App.instance,
                    extendedKey = accountType.hdExtendedKey,
                    walletId = account.id,
                    syncMode = syncMode,
                    networkType = NetworkType.MainNet,
                    confirmationsThreshold = confirmationsThreshold,
                    fullSyncStart = fullSyncStart,
                )

                is AccountType.Mnemonic -> DogecoinKit(
                    context = App.instance,
                    words = accountType.words,
                    passphrase = accountType.passphrase,
                    walletId = account.id,
                    syncMode = syncMode,
                    networkType = NetworkType.MainNet,
                    confirmationsThreshold = confirmationsThreshold,
                    fullSyncStart = fullSyncStart,
                )

                is AccountType.BitcoinAddress -> DogecoinKit(
                    context = App.instance,
                    watchAddress = accountType.address,
                    walletId = account.id,
                    syncMode = syncMode,
                    networkType = NetworkType.MainNet,
                    confirmationsThreshold = confirmationsThreshold,
                    fullSyncStart = fullSyncStart,
                )

                else -> throw UnsupportedAccountException()
            }
        }

        fun clear(walletId: String) {
            DogecoinKit.clear(App.instance, NetworkType.MainNet, walletId)
        }
    }
}

internal fun resolveDogecoinFullSyncStart(accountOrigin: AccountOrigin) =
    when (accountOrigin) {
        AccountOrigin.Created -> DogecoinKit.FullSyncStart.RecentPinned
        AccountOrigin.Restored -> DogecoinKit.FullSyncStart.Bip44
    }
