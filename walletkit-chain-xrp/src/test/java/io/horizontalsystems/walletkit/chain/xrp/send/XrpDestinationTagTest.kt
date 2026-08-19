package io.horizontalsystems.walletkit.chain.xrp.send

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class XrpDestinationTagTest {
    @Test
    fun `blank is absent while zero and UInt32 max stay present`() {
        assertNull(XrpDestinationTag.parse(""))
        assertEquals(0u, XrpDestinationTag.parse("0"))
        assertEquals(UInt.MAX_VALUE, XrpDestinationTag.parse("4294967295"))
    }

    @Test
    fun `signed decimal and overflow values are rejected`() {
        listOf("-1", "+1", "1.0", "4294967296").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { XrpDestinationTag.parse(value) }
        }
    }
}
