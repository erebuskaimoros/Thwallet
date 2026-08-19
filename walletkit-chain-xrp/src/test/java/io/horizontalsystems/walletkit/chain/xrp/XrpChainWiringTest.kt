package io.horizontalsystems.walletkit.chain.xrp

import com.google.gson.JsonParser
import io.horizontalsystems.marketkit.models.Blockchain
import io.horizontalsystems.marketkit.models.BlockchainType
import io.horizontalsystems.marketkit.models.Coin
import io.horizontalsystems.marketkit.models.Token
import io.horizontalsystems.marketkit.models.TokenType
import io.horizontalsystems.walletkit.entities.Account
import io.horizontalsystems.walletkit.entities.AccountOrigin
import io.horizontalsystems.walletkit.entities.AccountType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class XrpChainWiringTest {
    private val classicAddress = "rHsMGQEkVNJmpGWs8XUBoTBiAAbwxZN5v3"
    private val untaggedXAddress = "XVMFQQBMhdouRqhPMuawgBMN1AVFTofPAdRsXG5RkPtUPNQ"
    private val taggedXAddress = "XVMFQQBMhdouRqhPMuawgBMN1AVFToUN3u1CU1342EmLWLX"

    @Test
    fun `cross-platform fixture pins Android chain policy`() {
        val fixture = JsonParser.parseString(
            javaClass.classLoader!!.getResource("xrpl-native-golden-v1.json")!!.readText(),
        ).asJsonObject
        val protocol = fixture.getAsJsonObject("protocol")
        val derivation = fixture.getAsJsonObject("derivation")

        assertEquals(144, protocol.get("coin_type").asInt)
        assertEquals("m/44'/144'/0'/0/0", protocol.get("derivation_path").asString)
        assertEquals(0, protocol.get("network_id").asInt)
        assertEquals(classicAddress, derivation.get("classic_address").asString)
    }

    @Test
    fun `handler accepts mainnet classic and X-addresses only`() {
        val handler = AddressHandlerXrp()

        assertTrue(handler.isSupported(classicAddress))
        assertTrue(handler.isSupported(taggedXAddress))
        assertFalse(handler.isSupported("TVHLFWLKvbMv1LFzd6FA2Bf9MPpcy4iGanqfWYMuwiggt3t"))
        assertEquals(BlockchainType.Ripple, handler.parseAddress(taggedXAddress).blockchainType)
        assertEquals(taggedXAddress, handler.parseAddress(taggedXAddress).hex)
    }

    @Test
    fun `watch conversion normalizes untagged X-address and rejects routing tags`() {
        val plugin = XrpChainPlugin()

        assertEquals(
            AccountType.XrpAddress(classicAddress),
            plugin.watchAccountType(AddressHandlerXrp().parseAddress(untaggedXAddress)),
        )
        assertEquals(null, plugin.watchAccountType(AddressHandlerXrp().parseAddress(taggedXAddress)))
    }

    @Test
    fun `plugin derives the same BIP44 account as the shared parity fixture`() = runBlocking {
        val words = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" ")
        val account = Account(
            id = "public-test-account",
            name = "Fixture",
            type = AccountType.Mnemonic(words, ""),
            origin = AccountOrigin.Restored,
            level = 0,
        )

        assertEquals(classicAddress, XrpChainPlugin().swapDestinationAddress(account))
    }

    @Test
    fun `validator is native XRP only and validates X-address checksum and network`() {
        runBlocking {
            val nativeToken = token(TokenType.Native)
            val validator = XrpAddressValidator(nativeToken)

            validator.validate(AddressHandlerXrp().parseAddress(taggedXAddress))
            assertThrows(IllegalArgumentException::class.java) {
                XrpAddressValidator(token(TokenType.Unsupported("issued", "USD:rIssuer")))
            }
        }
    }

    private fun token(type: TokenType) = Token(
        coin = Coin("ripple", "XRP", "XRP"),
        blockchain = Blockchain(BlockchainType.Ripple, "XRP Ledger", "https://xrpscan.com/account/\$ref"),
        type = type,
        decimals = 6,
    )
}
