package io.horizontalsystems.walletkit.core.managers

import io.horizontalsystems.bitcoincore.BitcoinCore.SyncMode
import io.horizontalsystems.marketkit.models.BlockchainType
import io.horizontalsystems.walletkit.entities.AccountOrigin
import io.horizontalsystems.walletkit.entities.BtcRestoreMode
import org.junit.Assert.assertTrue
import org.junit.Test

class BtcSyncModePolicyTest {

    @Test
    fun `created Dogecoin account can opt into verified blockchain sync`() {
        val mode = resolveBtcSyncMode(
            blockchainType = BlockchainType.Dogecoin,
            accountOrigin = AccountOrigin.Created,
            restoreMode = BtcRestoreMode.Blockchain,
        )

        assertTrue(mode is SyncMode.Full)
    }

    @Test
    fun `created Dogecoin account can explicitly use trusted fast sync`() {
        val mode = resolveBtcSyncMode(
            blockchainType = BlockchainType.Dogecoin,
            accountOrigin = AccountOrigin.Created,
            restoreMode = BtcRestoreMode.Blockchair,
        )

        assertTrue(mode is SyncMode.Blockchair)
    }

    @Test
    fun `existing Bitcoin created-account policy remains unchanged`() {
        val mode = resolveBtcSyncMode(
            blockchainType = BlockchainType.Bitcoin,
            accountOrigin = AccountOrigin.Created,
            restoreMode = BtcRestoreMode.Blockchain,
        )

        assertTrue(mode is SyncMode.Blockchair)
    }
}
