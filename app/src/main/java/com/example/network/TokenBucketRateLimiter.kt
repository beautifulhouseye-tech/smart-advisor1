package com.example.network

import android.os.SystemClock

class TokenBucketRateLimiter(
    private val capacity: Int = 10,
    private val refillRatePerMinute: Int = 10,
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() }
) {
    private var tokens: Double = capacity.toDouble()
    private var lastRefillMs: Long = clockMs()

    @Synchronized
    fun tryAcquire(): Boolean {
        refill()
        return if (tokens >= 1.0) {
            tokens -= 1.0
            true
        } else {
            false
        }
    }

    @Synchronized
    fun availableTokens(): Double {
        refill()
        return tokens
    }

    @Synchronized
    fun reset() {
        tokens = capacity.toDouble()
        lastRefillMs = clockMs()
    }

    private fun refill() {
        val now = clockMs()
        val elapsedMs = (now - lastRefillMs).coerceAtLeast(0L)
        if (elapsedMs > 0L && refillRatePerMinute > 0) {
            val tokensPerMs = refillRatePerMinute.toDouble() / 60_000.0
            tokens = minOf(capacity.toDouble(), tokens + elapsedMs * tokensPerMs)
            lastRefillMs = now
        }
    }
}
