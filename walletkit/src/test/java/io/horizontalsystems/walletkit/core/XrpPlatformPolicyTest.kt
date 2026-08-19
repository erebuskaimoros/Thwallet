package io.horizontalsystems.walletkit.core

import io.horizontalsystems.marketkit.models.BlockchainType
import io.horizontalsystems.marketkit.models.TokenQuery
import io.horizontalsystems.marketkit.models.TokenType
import io.horizontalsystems.walletkit.core.factories.removeScheme
import io.horizontalsystems.walletkit.core.factories.uriScheme
import io.horizontalsystems.walletkit.entities.AccountType
import io.horizontalsystems.walletkit.modules.backuplocal.BackupLocalModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XrpPlatformPolicyTest {

    private val address = "rHsMGQEkVNJmpGWs8XUBoTBiAAbwxZN5v3"

    @Test
    fun `XRP keeps cross-platform identifiers and native-only policy`() {
        assertEquals("ripple", BlockchainType.Ripple.uid)
        assertEquals(BlockchainType.Ripple, BlockchainType.fromUid("ripple"))
        assertTrue(TokenQuery(BlockchainType.Ripple, TokenType.Native).isSupported)
        assertFalse(
            TokenQuery(
                BlockchainType.Ripple,
                TokenType.Unsupported("issued", "USD:rIssuer"),
            ).isSupported,
        )
        assertEquals("XRP", io.horizontalsystems.marketkit.models.Blockchain(BlockchainType.Ripple, "XRP Ledger", null).description)
        assertEquals("XRP Ledger", BlockchainType.Ripple.title)
        assertEquals(4L, BlockchainType.Ripple.blockTime)
        assertEquals("xrpl", BlockchainType.Ripple.uriScheme)
        assertTrue(BlockchainType.Ripple.removeScheme)
    }

    @Test
    fun `XRP watch account survives encrypted-backup representation`() {
        val accountType = AccountType.XrpAddress(address)
        val serializedType = BackupLocalModule.getAccountTypeString(accountType)
        val encryptedData = BackupLocalModule.getDataForEncryption(accountType)

        assertEquals("xrp_address", serializedType)
        assertEquals(accountType, BackupLocalModule.getAccountTypeFromData(serializedType, encryptedData))
        assertTrue(accountType.isWatchAccountType)
        assertEquals(address, accountType.watchAccountAddress)
        assertTrue(BlockchainType.Ripple.supports(accountType))
        assertFalse(BlockchainType.Stellar.supports(accountType))
    }
}
