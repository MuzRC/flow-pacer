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

    private var currentAdaptiveRateMbps = 5.0

    companion object {
        const val ACTION_START = "com.muz.flowpacer.START"
        const val ACTION_STOP = "com.muz.flowpacer.STOP"
        const val ACTION_SET_MODE = "com.muz.flowpacer.SET_MODE"
        const val EXTRA_MODE = "extra_mode"

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
    }

    private fun startOptimization() {
        if (isServiceRunning) return

        startForegroundNotification()

        try {
            // 1. Hidupkan Pelayan Proksi Tempatan
            proxyServer = LocalProxyServer(
                port = PROXY_PORT,
                tokenBucket = uploadTokenBucket,
                onProtectSocket = { socket ->
                    protect(socket)
                }
            ).apply {
                start()
            }

            // 2. Konfigurasi Terowong VPN Loopback
            val builder = Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(1500)
                .addAddress("10.0.0.2", 32)

            // Kecualikan FlowPacer daripada VPN supaya DNS & sambungan proksi tidak tersangkut
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: PackageManager.NameNotFoundException) {
                e.printStackTrace()
            }

            // Halakan trafik sistem ke proksi tempatan
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setHttpProxy(
                    ProxyInfo.buildDirectProxy("127.0.0.1", PROXY_PORT)
                )
            }

            vpnInterface = builder.establish()
            isServiceRunning = true

            // 3. Jalankan Gelung Pemantau Kependaman Adaptif
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

                if (activeMode != TokenBucket.MODE_AUTO) continue

                val ping = probeLatency("1.1.1.1", 53, 1200)
                if (ping < 0) continue

                if (ping > 160) {
                    currentAdaptiveRateMbps = (currentAdaptiveRateMbps * 0.75).coerceAtLeast(1.5)
                    uploadTokenBucket.adjustRate(currentAdaptiveRateMbps)
                } else if (ping < 85) {
                    currentAdaptiveRateMbps = (currentAdaptiveRateMbps + 0.8).coerceAtMost(40.0)
                    uploadTokenBucket.adjustRate(currentAdaptiveRateMbps)
                }
            }
        }
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
            .setContentText("Profil Semasa: $modeLabel")
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
