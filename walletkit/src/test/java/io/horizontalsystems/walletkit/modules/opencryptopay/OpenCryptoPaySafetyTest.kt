package io.horizontalsystems.walletkit.modules.opencryptopay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class OpenCryptoPaySafetyTest {

    private val now = Instant.parse("2026-08-17T12:00:00Z")

    @Test
    fun `expired quote is neither payable nor broadcastable`() {
        val expiration = "2026-08-17T11:59:59Z"

        assertFalse(isOcpQuoteActive(expiration, now))
        assertThrows<IllegalStateException> {
            requireOcpQuoteActive(expiration, now)
        }
    }

    @Test
    fun `quote expiring now is already expired`() {
        assertFalse(isOcpQuoteActive(now.toString(), now))
    }

    @Test
    fun `future quote remains payable`() {
        val expiration = "2026-08-17T12:00:01Z"

        assertTrue(isOcpQuoteActive(expiration, now))
        requireOcpQuoteActive(expiration, now)
    }

    @Test
    fun `malformed expiration fails closed`() {
        assertFalse(isOcpQuoteActive("not-an-instant", now))
        assertThrows<IllegalStateException> {
            requireOcpQuoteActive("not-an-instant", now)
        }
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            if (error !is T) throw error
        }
    }
}
