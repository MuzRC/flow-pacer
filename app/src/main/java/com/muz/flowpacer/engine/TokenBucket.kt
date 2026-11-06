package com.muz.flowpacer.engine

/**
 * Enjin Token Bucket Adaptif dengan Sistem Hutang (Deficit Debt Pacing).
 * Menghalang sambungan selari (multi-connection) daripada membocorkan had laju.
 */
class TokenBucket(
    private var maxTokens: Long = 16 * 1024,
    initialRateMbps: Double = 3.8
) {
    companion object {
        const val MODE_AUTO = 0
        const val MODE_GAMING = 1
        const val MODE_UNLIMITED = 2
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
        val clampedMbps = mbps.coerceIn(0.8, 50.0)
        refillRatePerSec = (clampedMbps * 1024 * 1024 / 8).toLong()
        maxTokens = ((clampedMbps * 1024 * 1024 / 8) * 0.04).toLong().coerceIn(4 * 1024, 32 * 1024)
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

        // Tolak terus bait yang diminta (baki boleh jatuh ke angka negatif / berhutang)
        availableTokens -= bytes

        // Jika baki masih ada, lepas terus tanpa sebarang delay
        if (availableTokens >= 0) {
            return 0L
        }

        // Jika masuk baki negatif, kira berapa lama masa untuk bayar balik hutang token tersebut
        val debt = -availableTokens
        val waitTimeNanos = (debt * 1_000_000_000L) / refillRatePerSec
        return (waitTimeNanos / 1_000_000L).coerceIn(1L, 400L)
    }

    private fun refill() {
        val now = System.nanoTime()
        val elapsedSec = (now - lastRefillTimestamp) / 1_000_000_000.0
        if (elapsedSec > 0) {
            val newTokens = (elapsedSec * refillRatePerSec).toLong()
            if (newTokens > 0) {
                // Tambah token dari baki semasa (walaupun tengah negatif)
                availableTokens = (availableTokens + newTokens).coerceAtMost(maxTokens)
                lastRefillTimestamp = now
            }
        }
    }
}
