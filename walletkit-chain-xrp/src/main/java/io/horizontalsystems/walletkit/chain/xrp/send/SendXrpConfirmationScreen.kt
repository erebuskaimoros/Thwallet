package io.horizontalsystems.walletkit.chain.xrp.send

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.horizontalsystems.walletkit.modules.multiswap.QuoteInfoRow
import io.horizontalsystems.walletkit.modules.nav3.HSNavigation
import io.horizontalsystems.walletkit.modules.nav3.HSPage
import io.horizontalsystems.walletkit.modules.send.SendConfirmationScreen
import io.horizontalsystems.walletkit.uiv3.components.cell.hs
import kotlin.reflect.KClass

@Composable
fun SendXrpConfirmationScreen(
    navigation: HSNavigation,
    viewModel: SendXrpViewModel,
    sendEntryPointDestId: KClass<out HSPage>,
) {
    val data = remember { viewModel.confirmationData() }
    SendConfirmationScreen(
        navigation = navigation,
        coinMaxAllowedDecimals = viewModel.coinMaxAllowedDecimals,
        feeCoinMaxAllowedDecimals = viewModel.feeTokenMaxAllowedDecimals,
        rate = viewModel.coinRate,
        feeCoinRate = viewModel.coinRate,
        sendResult = viewModel.sendResult,
        token = viewModel.wallet.token,
        feeCoin = viewModel.wallet.coin,
        amount = data.amount,
        address = data.address,
        contact = data.contact,
        fee = data.fee,
        memo = data.memo,
        onClickSend = { viewModel.onClickSend(data) },
        sendEntryPointDestId = sendEntryPointDestId,
        additionalFields = data.destinationTag?.let { destinationTag ->
            @Composable {
                QuoteInfoRow(title = "Destination Tag", value = destinationTag.toString().hs)
            }
        },
    )
}
