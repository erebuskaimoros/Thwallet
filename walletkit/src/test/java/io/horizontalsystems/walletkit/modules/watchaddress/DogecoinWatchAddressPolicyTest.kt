package io.horizontalsystems.walletkit.modules.watchaddress

import io.horizontalsystems.marketkit.models.BlockchainType
import io.horizontalsystems.marketkit.models.TokenType
import io.horizontalsystems.walletkit.entities.BitcoinAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DogecoinWatchAddressPolicyTest {

    @Test
    fun `parsed Dogecoin P2PKH address can become a watch account`() {
        val parsedAddress = BitcoinAddress(
            hex = "DBus3bamQjgJULBJtYXpEzDWQRwF5iwxgC",
            domain = null,
            blockchainType = BlockchainType.Dogecoin,
            derivation = TokenType.Derivation.Bip44,
        )

        val accountType = bitcoinWatchAccountTypeOrNull(parsedAddress)

        assertNotNull(accountType)
        assertEquals(parsedAddress.hex, accountType?.address)
        assertEquals(BlockchainType.Dogecoin, accountType?.blockchainType)
        assertEquals(TokenType.Native, accountType?.tokenType)
    }

    @Test
    fun `parsed Dogecoin P2SH address cannot become a watch account`() {
        val parsedAddress = BitcoinAddress(
            hex = "AETZJzedcmLM2rxCM6VqCGF3YEMUjA3jMw",
            domain = null,
            blockchainType = BlockchainType.Dogecoin,
            derivation = TokenType.Derivation.Bip49,
        )

        assertNull(bitcoinWatchAccountTypeOrNull(parsedAddress))
    }
}
