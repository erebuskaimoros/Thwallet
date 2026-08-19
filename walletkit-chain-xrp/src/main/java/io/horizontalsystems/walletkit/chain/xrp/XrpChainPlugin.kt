package io.horizontalsystems.walletkit.chain.xrp

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import io.horizontalsystems.marketkit.models.BlockchainType
import io.horizontalsystems.marketkit.models.Token
import io.horizontalsystems.marketkit.models.TokenQuery
import io.horizontalsystems.marketkit.models.TokenType
import io.horizontalsystems.walletkit.chain.xrp.adapters.XrpAdapter
import io.horizontalsystems.walletkit.chain.xrp.adapters.XrpTransactionConverter
import io.horizontalsystems.walletkit.chain.xrp.adapters.XrpTransactionsAdapter
import io.horizontalsystems.walletkit.chain.xrp.runtime.XrpKitManager
import io.horizontalsystems.walletkit.chain.xrp.send.SendTransactionServiceXrp
import io.horizontalsystems.walletkit.chain.xrp.send.SendXrpModule
import io.horizontalsystems.walletkit.chain.xrp.send.SendXrpScreen
import io.horizontalsystems.walletkit.chain.xrp.send.SendXrpViewModel
import io.horizontalsystems.walletkit.chain.xrp.send.XrpPaymentUriParser
import io.horizontalsystems.walletkit.core.App
import io.horizontalsystems.walletkit.core.IAdapter
import io.horizontalsystems.walletkit.core.ISendXrpAdapter
import io.horizontalsystems.walletkit.core.ITransactionsAdapter
import io.horizontalsystems.walletkit.core.chain.ChainPlugin
import io.horizontalsystems.walletkit.core.chain.ChainSendScreenArgs
import io.horizontalsystems.walletkit.core.managers.RestoreSettings
import io.horizontalsystems.walletkit.entities.Account
import io.horizontalsystems.walletkit.entities.AccountType
import io.horizontalsystems.walletkit.entities.Address
import io.horizontalsystems.walletkit.entities.AddressUri
import io.horizontalsystems.walletkit.entities.Wallet
import io.horizontalsystems.walletkit.modules.address.IAddressHandler
import io.horizontalsystems.walletkit.modules.multiswap.sendtransaction.AbstractSendTransactionService
import io.horizontalsystems.walletkit.modules.send.address.EnterAddressValidator
import io.horizontalsystems.walletkit.modules.transactions.TransactionSource

/** Native-XRP Ledger composition boundary. Issued assets and raw transaction signing stay disabled. */
class XrpChainPlugin : ChainPlugin {
    override val blockchainType: BlockchainType = BlockchainType.Ripple

    private val kitManager by lazy { XrpKitManager(App.instance) }

    override fun createAdapter(wallet: Wallet, restoreSettings: RestoreSettings): IAdapter? =
        if (wallet.token.type == TokenType.Native) XrpAdapter(kitManager.getKit(wallet.account)) else null

    override fun createTransactionsAdapter(source: TransactionSource): ITransactionsAdapter? {
        val token = App.coinManager.getToken(TokenQuery(BlockchainType.Ripple, TokenType.Native)) ?: return null
        val kit = kitManager.getKit(source.account)
        return XrpTransactionsAdapter(kit, XrpTransactionConverter(source, token))
    }

    override fun unlink(account: Account) = kitManager.unlink(account)

    override fun clearAccountData(accountId: String) = kitManager.clear(accountId)

    override fun statusInfo(): Map<String, Any>? = kitManager.statusInfo

    override suspend fun refreshKit() = kitManager.refresh()

    override fun addressHandlers(): List<IAddressHandler> = listOf(AddressHandlerXrp())

    override val hasStrictAddressUriParser = true

    override fun parseAddressUri(value: String): AddressUri? = runCatching {
        val payment = XrpPaymentUriParser.parse(value)
        AddressUri("xrpl").apply {
            // Keep routing metadata inside the validated address as well as the typed field.
            // Generic address-entry preprocessors retain only AddressUri.address.
            address = payment.destinationTag?.let {
                XrpAddressCodec.classicToXAddress(payment.classicAddress, it)
            } ?: payment.classicAddress
            payment.destinationTag?.let {
                parameters[AddressUri.Field.DestinationTag] = it.toString()
            }
            payment.amount?.let { parameters[AddressUri.Field.Amount] = it.toPlainString() }
            payment.memo?.let { parameters[AddressUri.Field.Memo] = it }
        }
    }.getOrNull()

    override fun addressValidator(token: Token): EnterAddressValidator? =
        if (token.type is TokenType.Native) XrpAddressValidator(token) else null

    override fun watchAccountType(address: Address): AccountType.XrpAddress? {
        if (address.blockchainType != BlockchainType.Ripple) return null
        val destination = XrpAddressCodec.parse(address.hex)
        // A destination tag routes a payment inside a shared account; it cannot scope ledger
        // history or balance. Refuse to silently turn an exchange X-address into a watch account.
        if (destination.destinationTag != null) return null
        return AccountType.XrpAddress(destination.classicAddress)
    }

    override suspend fun swapDestinationAddress(account: Account): String? = when (val type = account.type) {
        is AccountType.Mnemonic -> XrpKeyDerivation.address(type.seed)
        is AccountType.XrpAddress -> XrpAddressCodec.parse(type.address).takeIf {
            it.destinationTag == null
        }?.classicAddress
        else -> null
    }

    override fun analyticsAddress(accountType: AccountType): String? = when (accountType) {
        is AccountType.Mnemonic -> XrpKeyDerivation.address(accountType.seed)
        is AccountType.XrpAddress -> accountType.address
        else -> null
    }

    override fun sendTransactionService(token: Token): AbstractSendTransactionService? {
        if (token.blockchainType != BlockchainType.Ripple || token.type != TokenType.Native) return null
        val adapter = App.adapterManager.getAdapterForToken<ISendXrpAdapter>(token) ?: return null
        return SendTransactionServiceXrp(adapter, token)
    }

    @Composable
    override fun SendScreen(args: ChainSendScreenArgs) {
        val factory = SendXrpModule.Factory(
            args.wallet,
            args.address,
            args.hideAddress,
            args.destinationTag,
            args.memo,
        )
        val sendViewModel = viewModel<SendXrpViewModel>(factory = factory)
        SendXrpScreen(
            title = args.title,
            navigation = args.navigation,
            viewModel = sendViewModel,
            amountInputModeViewModel = args.amountInputModeViewModel,
            sendEntryPointDestId = args.sendEntryPointDestId,
            amount = args.amount,
            riskyAddress = args.riskyAddress,
        )
    }
}
