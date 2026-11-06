package com.muz.flowpacer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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

class FlowPacerService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var proxyServer: LocalProxyServer? = null

    // Had siling muat naik: ~3.8 Mbps (498,000 bait/saat) dengan kapasiti lonjakan 32 KB
    private val uploadTokenBucket = TokenBucket(
        maxTokens = 32 * 1024,
        refillRatePerSec = (3.8 * 1024 * 1024 / 8).toLong()
    )

    companion object {
        const val ACTION_START = "com.muz.flowpacer.START"
        const val ACTION_STOP = "com.muz.flowpacer.STOP"
        const val NOTIFICATION_CHANNEL_ID = "flowpacer_service_channel"
        const val NOTIFICATION_ID = 1001
        const val PROXY_PORT = 10808

        @Volatile
        var isServiceRunning = false
            private set
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopOptimization()
            ACTION_START -> startOptimization()
            else -> {
                if (!isServiceRunning) startOptimization()
            }
        }
        return START_STICKY
    }

    private fun startOptimization() {
        if (isServiceRunning) return

        startForegroundNotification()

        try {
            // 1. Lancarkan Pelayan Proksi Tempatan dengan fungsi protect()
            proxyServer = LocalProxyServer(
                port = PROXY_PORT,
                tokenBucket = uploadTokenBucket,
                onProtectSocket = { socket ->
                    protect(socket)
                }
            ).apply {
                start()
            }

            // 2. Konfigurasi Terowong VPN Tempatan
            val builder = Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(1500)
                .addAddress("10.0.0.2", 32)
                // Kunci pelayan DNS berprestasi tinggi
                .addDnsServer("1.1.1.1")
                .addDnsServer("1.0.0.1")
                .addRoute("1.1.1.1", 32)
                .addRoute("1.0.0.1", 32)

            // Pautkan proksi kawalan trafik jika peranti Android 10 (API 29) ke atas
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setHttpProxy(
                    ProxyInfo.buildDirectProxy("127.0.0.1", PROXY_PORT)
                )
            }

            vpnInterface = builder.establish()
            isServiceRunning = true

        } catch (e: Exception) {
            e.printStackTrace()
            stopOptimization()
        }
    }

    private fun stopOptimization() {
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

        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title_active))
            .setContentText(getString(R.string.notif_desc_active))
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

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
        stopOptimization()
        super.onDestroy()
    }
}
