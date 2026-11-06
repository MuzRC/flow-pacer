package com.muz.flowpacer.engine

/**
 * Enjin Token Bucket Adaptif dengan Had Lonjakan Mikro (Micro-burst Limiter).
 * Mengelakkan lambakan paket serentak yang membanjiri penampan repeater Wi-Fi.
 */
class TokenBucket(
    private var maxTokens: Long = 16 * 1024,
    initialRateMbps: Double = 3.8
) {
    companion object {
        const val MODE_AUTO = 0      // Auto-Pintar (Adaptif ikut kependaman semasa)
        const val MODE_GAMING = 1    // Kunci Permua (Strict 3.8 Mbps anti-jitter)
        const val MODE_UNLIMITED = 2 // Tiada Had (Bypass 100%)
    }

    @Volatile
    var currentMode: Int = MODE_AUTO
        private set

    @Volatile
    private var refillRatePerSec: Long = (initialRateMbps * 1024 * 1024 / 8).toLong()

    private var availableTokens: Long = maxTokens
    private var lastRefillTimestamp: Long = System.nanoTime()

    fun setMode(mode: Int) {
        currentMode = mode
        if (mode == MODE_GAMING) {
            adjustRate(3.8)
        }
    }

    @Synchronized
    fun adjustRate(mbps: Double) {
        val clampedMbps = mbps.coerceIn(0.8, 40.0)
        refillRatePerSec = (clampedMbps * 1024 * 1024 / 8).toLong()

        // Hadkan saiz burst kepada ~40ms trafik sahaja (antara 4KB hingga 24KB)
        // Ini memastikan data keluar secara titisan licin, bukan pancutan mengejut
        maxTokens = ((clampedMbps * 1024 * 1024 / 8) * 0.04).toLong().coerceIn(4 * 1024, 24 * 1024)
        if (availableTokens > maxTokens) {
            availableTokens = maxTokens
        }
    }

    @Synchronized
    fun acquire(bytes: Int): Long {
        if (currentMode == MODE_UNLIMITED) {
            return 0L
        }

        refill()

        if (availableTokens >= bytes) {
            availableTokens -= bytes
            return 0L
        }

        val deficit = bytes - availableTokens
        val waitTimeNanos = (deficit * 1_000_000_000L) / refillRatePerSec
        val waitTimeMillis = (waitTimeNanos / 1_000_000L).coerceAtLeast(1L)

        availableTokens = 0
        return waitTimeMillis
    }

    private fun refill() {
        val now = System.nanoTime()
        val elapsedSec = (now - lastRefillTimestamp) / 1_000_000_000.0
        if (elapsedSec > 0) {
            val newTokens = (elapsedSec * refillRatePerSec).toLong()
            if (newTokens > 0) {
                availableTokens = (availableTokens + newTokens).coerceAtMost(maxTokens)
                lastRefillTimestamp = now
            }
        }
    }
}
