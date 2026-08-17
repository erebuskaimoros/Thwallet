package io.horizontalsystems.walletkit.core.managers

import io.horizontalsystems.bitcoincore.BitcoinCore.SyncMode
import io.horizontalsystems.walletkit.entities.AccountOrigin
import io.horizontalsystems.walletkit.entities.BtcRestoreMode
import io.horizontalsystems.marketkit.models.BlockchainType

private val createdAccountBlockchairOnlyBlockchains =
    listOf(
        BlockchainType.Bitcoin,
        BlockchainType.BitcoinCash,
        BlockchainType.Litecoin,
    )

/** Maps the stored restore mode to the kit's sync mode; lives with the kits in the chain module. */
fun BtcBlockchainManager.syncMode(blockchainType: BlockchainType, accountOrigin: AccountOrigin): SyncMode {
    return resolveBtcSyncMode(blockchainType, accountOrigin, restoreMode(blockchainType))
}

internal fun resolveBtcSyncMode(
    blockchainType: BlockchainType,
    accountOrigin: AccountOrigin,
    restoreMode: BtcRestoreMode,
): SyncMode {
    if (accountOrigin == AccountOrigin.Created && blockchainType in createdAccountBlockchairOnlyBlockchains) {
        return SyncMode.Blockchair()
    }

    return when (restoreMode) {
        BtcRestoreMode.Blockchair -> SyncMode.Blockchair()
        BtcRestoreMode.Hybrid -> SyncMode.Api()
        BtcRestoreMode.Blockchain -> SyncMode.Full()
    }
}
