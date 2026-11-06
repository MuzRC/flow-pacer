package com.muz.flowpacer.engine

/**
 * Algoritma Token Bucket Adaptif & Pelbagai Profil.
 * Mengawal kadar aliran muat naik secara dinamik mengikut kependaman rangkaian,
 * mengunci had selamat untuk permua, atau membenarkan laluan bebas 100% tanpa had.
 */
class TokenBucket(
    private var maxTokens: Long = 32 * 1024,
    initialRateMbps: Double = 3.8
) {
    companion object {
        const val MODE_AUTO = 0      // Auto-Pintar (Adaptif ikut kependaman semasa)
        const val MODE_GAMING = 1    // Kunci Permua (Strict ~3.8 Mbps anti-jitter)
        const val MODE_UNLIMITED = 2 // Tiada Had (Bypass 100% bila talian lapang)
    }

    @Volatile
    var currentMode: Int = MODE_AUTO
        private set

    @Volatile
    private var refillRatePerSec: Long = (initialRateMbps * 1024 * 1024 / 8).toLong()

    private var availableTokens: Long = maxTokens
    private var lastRefillTimestamp: Long = System.nanoTime()

    /**
     * Menukar profil operasi aplikasi secara langsung semasa perkhidmatan berjalan.
     */
    fun setMode(mode: Int) {
        currentMode = mode
        if (mode == MODE_GAMING) {
            adjustRate(3.8) // Kunci had siling selamat untuk memastikan tiada lonjakan ping
        }
    }

    /**
     * Melaraskan kadar pelepasan bait sesaat secara dinamik (digunakan oleh enjin Auto-Adaptif).
     */
    @Synchronized
    fun adjustRate(mbps: Double) {
        val clampedMbps = mbps.coerceIn(0.5, 100.0) // Julat selamat antara 500 Kbps hingga 100 Mbps
        refillRatePerSec = (clampedMbps * 1024 * 1024 / 8).toLong()
        
        // Sesuaikan saiz lonjakan (burst) berkadaran dengan kelajuan (anggaran 100ms buffer)
        maxTokens = ((clampedMbps * 1024 * 1024 / 8) * 0.1).toLong().coerceIn(16 * 1024, 256 * 1024)
    }

    /**
     * Memeriksa dan menahan aliran data keluar jika melebihi kuota semasa.
     */
    @Synchronized
    fun acquire(bytes: Int): Long {
        // Jika mod tiada had aktif, lepaskan serta-merta (kelewatan 0 ms)
        if (currentMode == MODE_UNLIMITED) {
            return 0L
        }

        refill()

        // Jika baki token mencukupi
        if (availableTokens >= bytes) {
            availableTokens -= bytes
            return 0L
        }

        // Kira kelewatan jika had kuota saat ini sudah habis
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
