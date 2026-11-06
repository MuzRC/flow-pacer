package com.muz.flowpacer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.muz.flowpacer.MainActivity
import com.muz.flowpacer.R
import com.muz.flowpacer.engine.TokenBucket
import com.muz.flowpacer.proxy.LocalProxyServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.Socket

class FlowPacerService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var proxyServer: LocalProxyServer? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var adaptiveMonitorJob: Job? = null

    private val uploadTokenBucket = TokenBucket(
        maxTokens = 32 * 1024,
        initialRateMbps = 3.8
    )

    private var currentAdaptiveRateMbps = 3.8

    companion object {
        const val ACTION_START = "com.muz.flowpacer.START"
        const val ACTION_STOP = "com.muz.flowpacer.STOP"
        const val ACTION_SET_MODE = "com.muz.flowpacer.SET_MODE"
        const val ACTION_METRICS_UPDATE = "com.muz.flowpacer.METRICS_UPDATE"

        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_PING = "extra_ping"
        const val EXTRA_RATE_MBPS = "extra_rate_mbps"
        const val EXTRA_TX_BYTES = "extra_tx_bytes"
        const val EXTRA_RX_BYTES = "extra_rx_bytes"

        const val PREFS_NAME = "flowpacer_prefs"
        const val PREF_EXCLUDED_APPS = "excluded_apps"
        const val PREF_DNS_TYPE = "dns_type"

        const val DNS_CLOUDFLARE = 0
        const val DNS_ADGUARD = 1
        const val DNS_GOOGLE = 2

        const val NOTIFICATION_CHANNEL_ID = "flowpacer_service_channel"
        const val NOTIFICATION_ID = 1001
        const val PROXY_PORT = 10808

        @Volatile
        var isServiceRunning = false
            private set

        @Volatile
        var activeMode = TokenBucket.MODE_AUTO
            private set
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopOptimization()
            ACTION_SET_MODE -> {
                val newMode = intent.getIntExtra(EXTRA_MODE, TokenBucket.MODE_AUTO)
                setOperationalMode(newMode)
            }
            ACTION_START -> startOptimization()
            else -> {
                if (!isServiceRunning) startOptimization()
            }
        }
        return START_STICKY
    }

    private fun setOperationalMode(mode: Int) {
        activeMode = mode
        uploadTokenBucket.setMode(mode)
        updateNotification()
        
        val reportedRate = when (mode) {
            TokenBucket.MODE_GAMING -> 3.8
            TokenBucket.MODE_UNLIMITED -> -1.0
            else -> currentAdaptiveRateMbps
        }
        broadcastMetrics(-1L, reportedRate)
    }

    private fun startOptimization() {
        if (isServiceRunning) return

        startForegroundNotification()

        try {
            proxyServer = LocalProxyServer(
                port = PROXY_PORT,
                tokenBucket = uploadTokenBucket,
                onProtectSocket = { socket -> protect(socket) }
            ).apply {
                start()
            }

            val builder = Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(1500)
                .addAddress("10.0.0.2", 32)

            // Ciri 5: Pasang Pelayan DNS Terpilih
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val dnsChoice = prefs.getInt(PREF_DNS_TYPE, DNS_CLOUDFLARE)
            when (dnsChoice) {
                DNS_ADGUARD -> {
                    builder.addDnsServer("94.140.14.14")
                    builder.addDnsServer("94.140.15.15")
                }
                DNS_GOOGLE -> {
                    builder.addDnsServer("8.8.8.8")
                    builder.addDnsServer("8.8.4.4")
                }
                else -> {
                    builder.addDnsServer("1.1.1.1")
                    builder.addDnsServer("1.0.0.1")
                }
            }

            // Kecualikan pakej sendiri
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: PackageManager.NameNotFoundException) {}

            // Ciri 2: Kecualikan aplikasi pilihan pengguna (Split Tunneling)
            val excludedApps = prefs.getStringSet(PREF_EXCLUDED_APPS, emptySet()) ?: emptySet()
            for (pkg in excludedApps) {
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (_: Exception) {}
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setHttpProxy(
                    ProxyInfo.buildDirectProxy("127.0.0.1", PROXY_PORT)
                )
            }

            vpnInterface = builder.establish()
            isServiceRunning = true

            startAdaptiveFeedbackLoop()

        } catch (e: Exception) {
            e.printStackTrace()
            stopOptimization()
        }
    }

    private fun startAdaptiveFeedbackLoop() {
        adaptiveMonitorJob?.cancel()
        adaptiveMonitorJob = serviceScope.launch {
            while (isActive) {
                delay(4000)

                val ping = probeLatency("1.1.1.1", 443, 2500)

                if (activeMode == TokenBucket.MODE_AUTO) {
                    if (ping in 0..160 && ping > 120) {
                        currentAdaptiveRateMbps = (currentAdaptiveRateMbps * 0.85).coerceAtLeast(1.5)
                        uploadTokenBucket.adjustRate(currentAdaptiveRateMbps)
                    } else if (ping > 160) {
                        currentAdaptiveRateMbps = (currentAdaptiveRateMbps * 0.75).coerceAtLeast(1.2)
                        uploadTokenBucket.adjustRate(currentAdaptiveRateMbps)
                    } else if (ping in 0..85) {
                        currentAdaptiveRateMbps = (currentAdaptiveRateMbps + 0.6).coerceAtMost(35.0)
                        uploadTokenBucket.adjustRate(currentAdaptiveRateMbps)
                    }
                    broadcastMetrics(ping, currentAdaptiveRateMbps)
                } else if (activeMode == TokenBucket.MODE_GAMING) {
                    broadcastMetrics(ping, 3.8)
                } else {
                    broadcastMetrics(ping, -1.0)
                }
            }
        }
    }

    private fun broadcastMetrics(ping: Long, rateMbps: Double) {
        val tx = proxyServer?.totalBytesUploaded?.get() ?: 0L
        val rx = proxyServer?.totalBytesDownloaded?.get() ?: 0L

        val intent = Intent(ACTION_METRICS_UPDATE).apply {
            putExtra(EXTRA_PING, ping)
            putExtra(EXTRA_RATE_MBPS, rateMbps)
            putExtra(EXTRA_TX_BYTES, tx)
            putExtra(EXTRA_RX_BYTES, rx)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun probeLatency(host: String, port: Int, timeoutMs: Int): Long {
        val socket = Socket()
        protect(socket)
        val start = System.currentTimeMillis()
        return try {
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            val duration = System.currentTimeMillis() - start
            socket.close()
            duration
        } catch (_: Exception) {
            -1L
        }
    }

    private fun stopOptimization() {
        adaptiveMonitorJob?.cancel()
        adaptiveMonitorJob = null

        try {
            proxyServer?.stop()
            proxyServer = null

            vpnInterface?.close()
            vpnInterface = null
        } catch (e: Exception) {
            e.printStackTrace()
        }

        isServiceRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundNotification() {
        createNotificationChannel()
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val modeLabel = when (activeMode) {
            TokenBucket.MODE_GAMING -> "Mod Permua (Terkunci)"
            TokenBucket.MODE_UNLIMITED -> "Mod Bebas (Tiada Had)"
            else -> "Mod Auto-Pintar (Adaptif)"
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title_active))
            .setContentText("Profil: $modeLabel")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        stopOptimization()
        super.onDestroy()
    }
}
