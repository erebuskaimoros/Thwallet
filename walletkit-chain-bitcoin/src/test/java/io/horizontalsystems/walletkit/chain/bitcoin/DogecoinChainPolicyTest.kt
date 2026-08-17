package io.horizontalsystems.walletkit.chain.bitcoin

import io.horizontalsystems.bitcoincore.transactions.scripts.ScriptType
import io.horizontalsystems.dogecoinkit.DogecoinKit
import io.horizontalsystems.dogecoinkit.DogecoinKit.NetworkType
import io.horizontalsystems.hdwalletkit.Mnemonic
import io.horizontalsystems.marketkit.models.Blockchain
import io.horizontalsystems.marketkit.models.BlockchainType
import io.horizontalsystems.marketkit.models.Coin
import io.horizontalsystems.marketkit.models.Token
import io.horizontalsystems.marketkit.models.TokenQuery
import io.horizontalsystems.marketkit.models.TokenType
import io.horizontalsystems.walletkit.core.adapters.DogecoinAdapter
import io.horizontalsystems.walletkit.core.adapters.resolveDogecoinFullSyncStart
import io.horizontalsystems.walletkit.core.isSupported
import io.horizontalsystems.walletkit.entities.Account
import io.horizontalsystems.walletkit.entities.AccountOrigin
import io.horizontalsystems.walletkit.entities.AccountType
import io.horizontalsystems.walletkit.entities.BitcoinAddress
import io.horizontalsystems.walletkit.entities.tokenType
import io.horizontalsystems.walletkit.modules.address.AddressHandlerBase58
import io.horizontalsystems.walletkit.modules.send.address.BitcoinAddressValidator
import io.horizontalsystems.walletkit.modules.send.bitcoin.SendBitcoinModule.rbfSupported
import io.horizontalsystems.walletkit.modules.send.bitcoin.SendBitcoinPluginService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DogecoinChainPolicyTest {

    @Test
    fun `full sync checkpoint follows account origin`() {
        assertEquals(
            DogecoinKit.FullSyncStart.RecentPinned,
            resolveDogecoinFullSyncStart(AccountOrigin.Created),
        )
        assertEquals(
            DogecoinKit.FullSyncStart.Bip44,
            resolveDogecoinFullSyncStart(AccountOrigin.Restored),
        )
    }

    @Test
    fun `dogecoin is native-only BIP44 without bitcoin-only send features`() {
        assertTrue(TokenQuery(BlockchainType.Dogecoin, TokenType.Native).isSupported)
        assertFalse(
            TokenQuery(
                BlockchainType.Dogecoin,
                TokenType.Derived(TokenType.Derivation.Bip44),
            ).isSupported,
        )
        assertFalse(
            TokenQuery(
                BlockchainType.Dogecoin,
                TokenType.Eip20("0x0000000000000000000000000000000000000000"),
            ).isSupported,
        )
        assertFalse(BlockchainType.Dogecoin.rbfSupported)
        assertFalse(SendBitcoinPluginService(BlockchainType.Dogecoin).isLockTimeEnabled)

        val handler = DogecoinChainPlugin().addressHandlers().single()
        assertTrue(handler is AddressHandlerBase58)

        val words = List(11) { "abandon" } + "about"
        val seed = Mnemonic().toSeed(words, "")
        val firstAddress = DogecoinKit.firstAddress(seed, NetworkType.MainNet).stringValue
        assertTrue(handler.isSupported(firstAddress))

        val staticallyValidated = BitcoinAddressValidator
            .staticAddressConverter(BlockchainType.Dogecoin)
            .convert(firstAddress)
        assertEquals(firstAddress, staticallyValidated.stringValue)
        assertEquals(ScriptType.P2PKH, staticallyValidated.scriptType)

        val parsed = handler.parseAddress(firstAddress) as BitcoinAddress
        assertEquals(BlockchainType.Dogecoin, parsed.blockchainType)
        assertEquals(TokenType.Native, parsed.tokenType)

        // P2SH is a valid Dogecoin destination even though the single-address watch-wallet
        // implementation intentionally accepts only P2PKH.
        val p2shAddress = "AETZJzedcmLM2rxCM6VqCGF3YEMUjA3jMw"
        assertTrue(handler.isSupported(p2shAddress))
        assertEquals(
            TokenType.Derivation.Bip49,
            (handler.parseAddress(p2shAddress) as BitcoinAddress).derivation,
        )
        assertEquals(
            ScriptType.P2SH,
            BitcoinAddressValidator.staticAddressConverter(BlockchainType.Dogecoin)
                .convert(p2shAddress)
                .scriptType,
        )
        assertEquals(1, DogecoinAdapter.confirmationsThreshold)

        val account = Account(
            id = "dogecoin-test",
            name = "Dogecoin Test",
            type = AccountType.Mnemonic(words, ""),
            origin = AccountOrigin.Created,
            level = 0,
        )
        val token = Token(
            coin = Coin("dogecoin", "Dogecoin", "DOGE"),
            blockchain = Blockchain(BlockchainType.Dogecoin, "Dogecoin", null),
            type = TokenType.Native,
            decimals = 8,
        )
        val swapAddress = runBlocking {
            DogecoinChainPlugin().swapDestinationAddress(account, token)
        }
        assertEquals(firstAddress, swapAddress)
    }
}
