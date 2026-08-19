package io.horizontalsystems.walletkit.chain.xrp.send

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.horizontalsystems.marketkit.models.BlockchainType
import io.horizontalsystems.walletkit.R
import io.horizontalsystems.walletkit.core.App
import io.horizontalsystems.walletkit.core.AppLogger
import io.horizontalsystems.walletkit.core.HSCaution
import io.horizontalsystems.walletkit.core.ISendXrpAdapter
import io.horizontalsystems.walletkit.core.ViewModelUiState
import io.horizontalsystems.walletkit.core.XrpSendInfo
import io.horizontalsystems.walletkit.core.XrpSubmissionStatus
import io.horizontalsystems.walletkit.core.managers.RecentAddressManager
import io.horizontalsystems.walletkit.entities.Address
import io.horizontalsystems.walletkit.entities.Wallet
import io.horizontalsystems.walletkit.modules.amount.SendAmountService
import io.horizontalsystems.walletkit.modules.contacts.ContactsRepository
import io.horizontalsystems.walletkit.modules.contacts.model.Contact
import io.horizontalsystems.walletkit.modules.send.SendResult
import io.horizontalsystems.walletkit.modules.xrate.XRateService
import io.horizontalsystems.walletkit.ui.compose.TranslatableString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.net.UnknownHostException

data class XrpConfirmationData(
    val amount: BigDecimal,
    val fee: BigDecimal,
    val address: Address,
    val contact: Contact?,
    val memo: String?,
    val destinationTag: UInt?,
)

class SendXrpViewModel(
    val wallet: Wallet,
    private val adapter: ISendXrpAdapter,
    private val address: Address,
    private val showAddressInput: Boolean,
    private val amountService: SendAmountService,
    private val xRateService: XRateService,
    private val contactsRepository: ContactsRepository,
    private val recentAddressManager: RecentAddressManager,
    initialDestinationTag: UInt?,
    initialMemo: String?,
) : ViewModelUiState<SendXrpUiState>() {
    private var amountState = amountService.stateFlow.value
    private var tagText: String
    private var tagError: Throwable? = null
    private var sendInfo: XrpSendInfo? = null
    private var preflightError: Throwable? = null
    private var preflightLoading = false
    private var memo: String? = initialMemo
    private var memoError: Throwable? = null
    private var preflightJob: Job? = null
    private var terminalSubmission: XrpSubmissionStatus? = null
    val tagLocked: Boolean
    val coinMaxAllowedDecimals = wallet.token.decimals
    val feeTokenMaxAllowedDecimals = wallet.token.decimals
    val fiatMaxAllowedDecimals = App.appConfigProvider.fiatDecimal

    var coinRate by mutableStateOf(xRateService.getRate(wallet.coin.uid))
        private set
    var sendResult by mutableStateOf<SendResult?>(null)
        private set

    private val logger = AppLogger("send-xrp")

    init {
        val destination = io.horizontalsystems.walletkit.chain.xrp.XrpAddressCodec.resolveDestination(
            address.hex,
            initialDestinationTag,
        )
        tagLocked = destination.destinationTag != null
        tagText = destination.destinationTag?.toString().orEmpty()
        memoError = memo?.takeIf { it.toByteArray(Charsets.UTF_8).size > 256 }
            ?.let { IllegalArgumentException("XRP memo cannot exceed 256 UTF-8 bytes") }

        viewModelScope.launch(Dispatchers.Default) {
            amountService.stateFlow.collect {
                amountState = it
                emitState()
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            xRateService.getRateFlow(wallet.coin.uid).collect { coinRate = it }
        }
        viewModelScope.launch(Dispatchers.Default) {
            adapter.terminalSubmissionFlow.collect {
                terminalSubmission = it
                emitState()
            }
        }
        refreshPreflight()
    }

    override fun createState() = SendXrpUiState(
        availableBalance = amountState.availableBalance,
        amountCaution = amountState.amountCaution,
        address = address,
        showAddressInput = showAddressInput,
        tagText = tagText,
        tagLocked = tagLocked,
        tagError = tagError ?: preflightError,
        preflightLoading = preflightLoading,
        memoError = memoError,
        memo = memo,
        terminalSubmission = terminalSubmission,
        fee = sendInfo?.fee ?: adapter.fee,
        canSend = adapter.canSend && amountState.canBeSend && sendInfo != null &&
            tagError == null && preflightError == null && memoError == null,
    )

    fun onEnterAmount(amount: BigDecimal?) = amountService.setAmount(amount)

    fun onEnterDestinationTag(value: String) {
        if (tagLocked) return
        tagText = value
        tagError = runCatching { XrpDestinationTag.parse(value) }.exceptionOrNull()
        if (tagError == null) refreshPreflight() else {
            sendInfo = null
            preflightError = null
            emitState()
        }
    }

    fun onEnterMemo(value: String) {
        memo = value.ifBlank { null }
        memoError = memo?.takeIf { it.toByteArray(Charsets.UTF_8).size > 256 }
            ?.let { IllegalArgumentException("XRP memo cannot exceed 256 UTF-8 bytes") }
        emitState()
    }

    fun confirmationData(): XrpConfirmationData {
        val destinationTag = XrpDestinationTag.parse(tagText)
        val contact = contactsRepository.getContactsFiltered(
            BlockchainType.Ripple,
            addressQuery = address.hex,
        ).firstOrNull()
        return XrpConfirmationData(
            amount = requireNotNull(amountState.amount),
            fee = sendInfo?.fee ?: adapter.fee,
            address = address,
            contact = contact,
            memo = memo,
            destinationTag = destinationTag,
        )
    }

    fun onClickSend(confirmed: XrpConfirmationData) {
        viewModelScope.launch { send(confirmed) }
    }

    fun onAcknowledgeTerminalSubmission() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { adapter.acknowledgeTerminalSubmission() }
                .onSuccess { refreshPreflight() }
                .onFailure {
                    preflightError = it
                    emitState()
                }
        }
    }

    private fun refreshPreflight() {
        preflightJob?.cancel()
        preflightJob = viewModelScope.launch(Dispatchers.IO) {
            preflightLoading = true
            preflightError = null
            emitState()
            try {
                val destinationTag = XrpDestinationTag.parse(tagText)
                val info = adapter.sendInfo(address.hex, destinationTag)
                sendInfo = info
                amountService.setMinimumSendAmount(info.minimumAmount)
                amountService.setAvailableBalance(info.maxSendableBalance)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                sendInfo = null
                preflightError = error
            } finally {
                preflightLoading = false
                emitState()
            }
        }
    }

    private suspend fun send(confirmed: XrpConfirmationData) = withContext(Dispatchers.IO) {
        try {
            sendResult = SendResult.Sending
            val hash = adapter.send(
                amount = confirmed.amount,
                address = confirmed.address.hex,
                destinationTag = confirmed.destinationTag,
                memo = confirmed.memo,
                maximumFee = confirmed.fee,
            )
            sendResult = SendResult.Sent(txHash = hash)
            recentAddressManager.setRecentAddress(confirmed.address, BlockchainType.Ripple)
        } catch (error: Throwable) {
            logger.warning("XRP send failed", error)
            sendResult = SendResult.Failed(caution(error))
        }
    }

    private fun caution(error: Throwable) = when (error) {
        is UnknownHostException -> HSCaution(TranslatableString.ResString(R.string.Hud_Text_NoInternet))
        else -> HSCaution(TranslatableString.PlainString(error.message ?: error.javaClass.simpleName))
    }
}

data class SendXrpUiState(
    val availableBalance: BigDecimal?,
    val amountCaution: HSCaution?,
    val address: Address,
    val showAddressInput: Boolean,
    val tagText: String,
    val tagLocked: Boolean,
    val tagError: Throwable?,
    val preflightLoading: Boolean,
    val memoError: Throwable?,
    val memo: String?,
    val terminalSubmission: XrpSubmissionStatus?,
    val fee: BigDecimal,
    val canSend: Boolean,
)
