package com.muz.flowpacer.engine

/**
 * Enjin Token Bucket Adaptif dengan Sistem Hutang (Deficit Debt Pacing).
 * Mengawal aliran trafik Layer 3 secara titisan lancar bagi mengelakkan
 * penampan modem daripada limpah (Anti-Bufferbloat).
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

    // Kadar pengisian token dalam bait sesaat (Bytes/sec)
    @Volatile
    private var refillRatePerSec: Long = ((initialRateMbps * 1024.0 * 1024.0) / 8.0).toLong().coerceAtLeast(1024L)

    private var availableTokens: Long = maxTokens
    private var lastRefillTimestamp: Long = System.nanoTime()

    /**
     * Menetapkan mod operasi tanpa menimpa kadar kelajuan secara senyap.
     */
    @Synchronized
    fun setMode(mode: Int) {
        currentMode = mode
        resetDebt()
    }

    /**
     * Melaraskan had siling kelajuan dalam unit Mbps secara dinamik.
     */
    @Synchronized
    fun adjustRate(mbps: Double) {
        val clampedMbps = mbps.coerceIn(0.5, 100.0)
        refillRatePerSec = ((clampedMbps * 1024.0 * 1024.0) / 8.0).toLong().coerceAtLeast(1024L)

        // Hadkan saiz baldi token sekitar 40ms tetingkap data (burst window)
        // supaya tidak melancarkan lambakan paket mengejut ke modem
        val burstWindowSec = 0.04
        maxTokens = (refillRatePerSec * burstWindowSec).toLong().coerceIn(4 * 1024L, 64 * 1024L)

        if (availableTokens > maxTokens) {
            availableTokens = maxTokens
        }
    }

    /**
     * Memeriksa dan menuntut token untuk saiz bait tertentu.
     * Mengembalikan tempoh tunggu (dalam milisaat) jika baki token negatif (berhutang).
     */
    @Synchronized
    fun acquire(bytes: Int): Long {
        if (currentMode == MODE_UNLIMITED || bytes <= 0) {
            return 0L
        }

        refill()

        // Tolak bait daripada baki semasa (boleh jatuh ke zon hutang)
        availableTokens -= bytes

        // Jika baki token masih ada, lepaskan serta-merta tanpa senggang masa
        if (availableTokens >= 0) {
            return 0L
        }

        // Kira tempoh masa yang diperlukan untuk melunaskan hutang bait berkenaan
        val debt = -availableTokens
        // Formula: (debt * 1000 ms) / refillRatePerSec
        val waitTimeMs = (debt * 1000L) / refillRatePerSec

        // Sekat senggang masa maksimum pada 300ms untuk cegah paket TCP/UDP luput masa (timeout)
        return waitTimeMs.coerceIn(1L, 300L)
    }

    /**
     * Menambah token berdasarkan peredaran masa nano (High-resolution timer).
     */
    private fun refill() {
        val now = System.nanoTime()
        val elapsedNanos = now - lastRefillTimestamp

        if (elapsedNanos > 0) {
            // Pengiraan integer tulen bagi mengelakkan beban CPU
            val newTokens = (elapsedNanos * refillRatePerSec) / 1_000_000_000L
            if (newTokens > 0) {
                availableTokens = (availableTokens + newTokens).coerceAtMost(maxTokens)
                lastRefillTimestamp = now
            }
        }
    }

    /**
     * Mengosongkan hutang dan memenuhkan semula token baldi.
     */
    @Synchronized
    fun resetDebt() {
        availableTokens = maxTokens
        lastRefillTimestamp = System.nanoTime()
    }

    fun getCurrentRateMbps(): Double {
        return (refillRatePerSec * 8.0) / (1024.0 * 1024.0)
    }
}
