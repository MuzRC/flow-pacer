package com.muz.flowpacer.engine

/**
 * Algoritma Token Bucket untuk mengehadkan kadar muat naik (Rate Limiting).
 * Menahan paket data berat dalam RAM peranti sebelum dihantar ke modem/repeater
 * bagi mengelakkan lonjakan bufferbloat.
 */
class TokenBucket(
    private val maxTokens: Long,       // Kapasiti maksimum lonjakan/burst (dalam bait)
    private val refillRatePerSec: Long // Had pelepasan bait sesaat (contoh: 475_000B/s = ~3.8 Mbps)
) {
    private var availableTokens: Long = maxTokens
    private var lastRefillTimestamp: Long = System.nanoTime()

    @Synchronized
    fun acquire(bytes: Int): Long {
        refill()

        // Jika token mencukupi, tolak token dan terus lepaskan paket (kelewatan 0ms)
        if (availableTokens >= bytes) {
            availableTokens -= bytes
            return 0L
        }

        // Jika token tidak cukup, kira berapa milisaat perlu ditahan
        val deficit = bytes - availableTokens
        val waitTimeNanos = (deficit * 1_000_000_000L) / refillRatePerSec
        val waitTimeMillis = (waitTimeNanos / 1_000_000L).coerceAtLeast(1L)

        // Reset baki token untuk kitaran seterusnya
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
