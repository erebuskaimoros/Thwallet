package io.horizontalsystems.walletkit.chain.xrp.send

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.horizontalsystems.walletkit.R
import io.horizontalsystems.walletkit.core.providers.Translator
import io.horizontalsystems.walletkit.entities.DataState
import io.horizontalsystems.walletkit.modules.address.AddressParserModule
import io.horizontalsystems.walletkit.modules.address.AddressParserViewModel
import io.horizontalsystems.walletkit.modules.address.HSAddressCell
import io.horizontalsystems.walletkit.modules.amount.AmountInputModeViewModel
import io.horizontalsystems.walletkit.modules.amount.HSAmountInput
import io.horizontalsystems.walletkit.modules.availablebalance.AvailableBalance
import io.horizontalsystems.walletkit.modules.fee.HSFee
import io.horizontalsystems.walletkit.modules.memo.HSMemoInput
import io.horizontalsystems.walletkit.modules.memo.MemoVisibility
import io.horizontalsystems.walletkit.modules.nav3.HSNavigation
import io.horizontalsystems.walletkit.modules.nav3.HSPage
import io.horizontalsystems.walletkit.modules.send.AddressRiskySheet
import io.horizontalsystems.walletkit.modules.send.SendScreen
import io.horizontalsystems.walletkit.ui.compose.components.ButtonPrimaryYellow
import io.horizontalsystems.walletkit.ui.compose.components.ButtonSecondaryDefault
import io.horizontalsystems.walletkit.ui.compose.components.FormsInput
import io.horizontalsystems.walletkit.ui.compose.components.TextPreprocessor
import io.horizontalsystems.walletkit.ui.compose.components.VSpacer
import io.horizontalsystems.walletkit.ui.compose.components.caption_lucian
import java.math.BigDecimal
import kotlin.reflect.KClass

@Composable
fun SendXrpScreen(
    title: String,
    navigation: HSNavigation,
    viewModel: SendXrpViewModel,
    amountInputModeViewModel: AmountInputModeViewModel,
    sendEntryPointDestId: KClass<out HSPage>,
    amount: BigDecimal?,
    riskyAddress: Boolean,
) {
    val state = viewModel.uiState
    val wallet = viewModel.wallet
    val amountInputType = amountInputModeViewModel.inputType
    val keyboardController = LocalSoftwareKeyboardController.current
    val parser = viewModel<AddressParserViewModel>(factory = AddressParserModule.Factory(wallet.token, amount))
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    SendScreen(title = title, onBack = navigation::removeLastOrNull) {
        VSpacer(16.dp)
        state.terminalSubmission?.takeIf { it.requiresAcknowledgement }?.let { submission ->
            caption_lucian(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                text = when (submission.state) {
                    "BLOCKED_SEQUENCE_CONFLICT" ->
                        "XRP transaction ${submission.transactionHash} conflicts with the account sequence. Review it before acknowledging."
                    else ->
                        "XRP transaction ${submission.transactionHash} expired without validation. Review it before acknowledging."
                },
            )
            ButtonSecondaryDefault(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                title = "Acknowledge XRP status",
                onClick = viewModel::onAcknowledgeTerminalSubmission,
            )
            VSpacer(8.dp)
        }
        if (state.showAddressInput) {
            HSAddressCell(
                title = stringResource(R.string.Send_Confirmation_To),
                value = state.address.hex,
                riskyAddress = riskyAddress,
                onClick = navigation::removeLastOrNull,
            )
            VSpacer(16.dp)
        }
        HSAmountInput(
            modifier = Modifier.padding(horizontal = 16.dp),
            focusRequester = focusRequester,
            availableBalance = state.availableBalance ?: BigDecimal.ZERO,
            caution = state.amountCaution,
            coinCode = wallet.coin.code,
            coinDecimal = viewModel.coinMaxAllowedDecimals,
            fiatDecimal = viewModel.fiatMaxAllowedDecimals,
            onClickHint = amountInputModeViewModel::onToggleInputType,
            onValueChange = viewModel::onEnterAmount,
            inputType = amountInputType,
            rate = viewModel.coinRate,
            amountUnique = parser.amountUnique,
        )
        VSpacer(8.dp)
        AvailableBalance(
            coinCode = wallet.coin.code,
            coinDecimal = viewModel.coinMaxAllowedDecimals,
            fiatDecimal = viewModel.fiatMaxAllowedDecimals,
            availableBalance = state.availableBalance,
            amountInputType = amountInputType,
            rate = viewModel.coinRate,
        )
        VSpacer(16.dp)
        FormsInput(
            modifier = Modifier.padding(horizontal = 16.dp),
            enabled = !state.tagLocked,
            initial = state.tagText,
            hint = "Destination Tag (optional)",
            singleLine = true,
            maxLength = 10,
            pasteEnabled = !state.tagLocked,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textPreprocessor = object : TextPreprocessor {
                override fun process(text: String) = text.filter(Char::isDigit)
            },
            state = when {
                state.tagError != null -> DataState.Error(state.tagError)
                state.preflightLoading -> DataState.Loading
                else -> DataState.Success(Unit)
            },
            onValueChange = viewModel::onEnterDestinationTag,
        )
        VSpacer(16.dp)
        HSMemoInput(
            maxLength = 256,
            memo = state.memo,
            visibility = MemoVisibility.Public,
            onValueChange = viewModel::onEnterMemo,
        )
        state.memoError?.localizedMessage?.let { message ->
            caption_lucian(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                text = message,
            )
        }
        VSpacer(16.dp)
        HSFee(
            coinCode = wallet.coin.code,
            coinDecimal = viewModel.feeTokenMaxAllowedDecimals,
            fee = state.fee,
            amountInputType = amountInputType,
            rate = viewModel.coinRate,
            navigation = navigation,
        )

        val proceed = {
            navigation.slideFromRight(SendXrpConfirmationPage(sendEntryPointDestId))
        }
        val riskyResult = navigation.slideFromBottomForResult<AddressRiskySheet.Result>(
            {
                AddressRiskySheet(
                    AddressRiskySheet.Input(Translator.getString(R.string.Send_RiskyAddress_AlertText))
                )
            },
            onResult = { proceed() },
        )
        ButtonPrimaryYellow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            title = stringResource(R.string.Button_Next),
            enabled = state.canSend,
            onClick = {
                if (riskyAddress) {
                    keyboardController?.hide()
                    riskyResult()
                } else {
                    proceed()
                }
            },
        )
    }
}
