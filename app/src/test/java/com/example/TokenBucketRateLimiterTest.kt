package com.example

import com.example.network.TokenBucketRateLimiter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TokenBucketRateLimiterTest {

    @Test
    fun `allows burst up to 10 then blocks 11th and refills after 60 seconds`() = runTest {
        val limiter = TokenBucketRateLimiter(
            capacity = 10,
            refillRatePerMinute = 10,
            clockMs = { testScheduler.currentTime }
        )

        repeat(10) {
            assertTrue("Expected call #${it + 1} to succeed", limiter.tryAcquire())
        }

        assertFalse("Expected 11th call to be rate-limited", limiter.tryAcquire())

        // After 6 seconds (1/10th of a minute), 1 token refills
        advanceTimeBy(6_000L)
        assertTrue("Expected 1 token refilled after 6 seconds", limiter.tryAcquire())
        assertFalse("Expected bucket empty again after consuming single refilled token", limiter.tryAcquire())

        // After full 60 seconds, bucket refills up to capacity (10)
        advanceTimeBy(60_000L)
        repeat(10) {
            assertTrue("Expected refilled call #${it + 1} to succeed", limiter.tryAcquire())
        }
        assertFalse(limiter.tryAcquire())
    }
}
