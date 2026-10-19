package br.com.atos.printbridge.domain.queue

import org.junit.Assert.assertEquals
import org.junit.Test

class RetryPolicyTest {

    @Test
    fun `default base produces 2 4 8 16 32 seconds`() {
        val waits = (1..5).map { RetryPolicy.backoffMillis(baseMillis = 2_000, failedAttempts = it) }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L), waits)
    }

    @Test
    fun `zero base or no failed attempt means no wait`() {
        assertEquals(0L, RetryPolicy.backoffMillis(baseMillis = 0, failedAttempts = 3))
        assertEquals(0L, RetryPolicy.backoffMillis(baseMillis = 2_000, failedAttempts = 0))
    }

    @Test
    fun `huge attempt count is capped instead of overflowing`() {
        assertEquals(RetryPolicy.MAX_BACKOFF_MILLIS, RetryPolicy.backoffMillis(baseMillis = 2_000, failedAttempts = 500))
    }
}
