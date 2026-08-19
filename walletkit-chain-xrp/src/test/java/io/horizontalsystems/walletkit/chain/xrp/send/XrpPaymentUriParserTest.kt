package io.horizontalsystems.walletkit.chain.xrp.send

import io.horizontalsystems.walletkit.chain.xrp.XrpAddressCodec
import io.horizontalsystems.walletkit.chain.xrp.XrpChainPlugin
import io.horizontalsystems.walletkit.core.chain.ChainRegistry
import io.horizontalsystems.walletkit.core.utils.AddressUriParser
import io.horizontalsystems.walletkit.entities.AddressUri
import io.horizontalsystems.walletkit.modules.send.address.AddressExtractor
import io.horizontalsystems.walletkit.modules.address.AddressParserViewModel
import io.horizontalsystems.marketkit.models.BlockchainType
import io.horizontalsystems.marketkit.models.TokenType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class XrpPaymentUriParserTest {
    private val classic = "r3AgF9mMBFtaLhKcg96weMhbbEFLZ3mx17"

    @Test
    fun `classic URI preserves tag zero amount and decoded public memo`() {
        val payment = XrpPaymentUriParser.parse(
            "xrpl:$classic?dt=0&amount=1.250001&memo=THOR%3Ahello%20world"
        )

        assertEquals(classic, payment.classicAddress)
        assertEquals(0u, payment.destinationTag)
        assertEquals(BigDecimal("1.250001"), payment.amount)
        assertEquals("THOR:hello world", payment.memo)
    }

    @Test
    fun `maximum tag and tag alias remain distinct from no tag`() {
        assertEquals(
            UInt.MAX_VALUE,
            XrpPaymentUriParser.parse("xrpl:$classic?tag=4294967295").destinationTag,
        )
        assertEquals(null, XrpPaymentUriParser.parse("xrpl:$classic").destinationTag)
    }

    @Test
    fun `X-address accepts the same separate tag and rejects conflicts`() {
        val xAddress = XrpAddressCodec.classicToXAddress(classic, 0u)

        val payment = XrpPaymentUriParser.parse("xrpl:$xAddress?dt=0")
        assertEquals(classic, payment.classicAddress)
        assertEquals(0u, payment.destinationTag)

        assertThrows(IllegalArgumentException::class.java) {
            XrpPaymentUriParser.parse("xrpl:$xAddress?dt=1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            XrpPaymentUriParser.parse("xrpl:$classic?dt=0&tag=1")
        }
    }

    @Test
    fun `malformed testnet duplicate required and unknown parameters fail closed`() {
        val invalidUris = listOf(
            "xrpl:$classic?dt=-1",
            "xrpl:$classic?dt=",
            "xrpl:$classic?dt=4294967296",
            "xrpl:$classic?dt=0&dt=0",
            "xrpl:$classic?memo=%ZZ",
            "xrpl:$classic?req-feature=1",
            "xrpl:$classic?label=exchange",
            "xrpl:TVHLFWLKvbMv1LFzd6FA2Bf9MPpcy4mRto4VFAAxLuNpvdW",
        )

        invalidUris.forEach { uri ->
            assertThrows(uri, IllegalArgumentException::class.java) {
                XrpPaymentUriParser.parse(uri)
            }
        }
    }

    @Test
    fun `registered strict parser propagates destination tag structurally through core URI`() {
        ChainRegistry.register(XrpChainPlugin())

        val uri = requireNotNull(AddressUriParser.addressUri("xrpl:$classic?dt=0&memo=hello"))

        assertEquals(0u, XrpAddressCodec.parse(uri.address).destinationTag)
        assertEquals(0u, uri.destinationTag)
        assertEquals("hello", uri.memo)
        assertEquals("0", uri.value<String>(AddressUri.Field.DestinationTag))
        assertTrue(uri.unhandledParameters.isEmpty())

        val extracted = AddressExtractor(
            BlockchainType.Ripple,
            AddressUriParser(BlockchainType.Ripple, TokenType.Native),
        ).extractAddressFromUri("xrpl:$classic?dt=0&memo=hello")
        assertEquals(0u, XrpAddressCodec.parse(extracted).destinationTag)

        val inputViewModel = AddressParserViewModel(
            AddressUriParser(BlockchainType.Ripple, TokenType.Native),
            tokenDecimals = 6,
            prefilledAmount = null,
        )
        val processed = inputViewModel.process("xrpl:$classic?dt=0&amount=1.25&memo=hello")
        assertEquals(0u, XrpAddressCodec.parse(processed).destinationTag)
        assertEquals(0u, inputViewModel.paymentUri?.destinationTag)
        assertEquals("hello", inputViewModel.paymentUri?.memo)
        assertEquals(BigDecimal("1.25"), inputViewModel.amountUnique?.amount)
    }
}
