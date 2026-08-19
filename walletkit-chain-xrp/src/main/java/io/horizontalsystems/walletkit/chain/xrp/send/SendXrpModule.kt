package io.horizontalsystems.walletkit.chain.xrp.send

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import io.horizontalsystems.walletkit.core.App
import io.horizontalsystems.walletkit.core.ISendXrpAdapter
import io.horizontalsystems.walletkit.entities.Address
import io.horizontalsystems.walletkit.entities.Wallet
import io.horizontalsystems.walletkit.modules.amount.AmountValidator
import io.horizontalsystems.walletkit.modules.amount.SendAmountService
import io.horizontalsystems.walletkit.modules.xrate.XRateService

object SendXrpModule {
    class Factory(
        private val wallet: Wallet,
        private val address: Address,
        private val hideAddress: Boolean,
        private val initialDestinationTag: UInt?,
        private val initialMemo: String?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val adapter = App.adapterManager.getAdapterForWallet<ISendXrpAdapter>(wallet)
                ?: throw IllegalStateException("XRP send adapter is unavailable")
            val amountService = SendAmountService(
                amountValidator = AmountValidator(),
                coinCode = wallet.coin.code,
                availableBalance = adapter.maxSendableBalance,
                // XRP engine's max already subtracts the dynamic reserve and selected fee.
                leaveSomeBalanceForFee = false,
            )
            return SendXrpViewModel(
                wallet = wallet,
                adapter = adapter,
                address = address,
                showAddressInput = !hideAddress,
                amountService = amountService,
                xRateService = XRateService(App.marketKit, App.currencyManager.baseCurrency),
                contactsRepository = App.contactsRepository,
                recentAddressManager = App.recentAddressManager,
                initialDestinationTag = initialDestinationTag,
                initialMemo = initialMemo,
            ) as T
        }
    }
}
