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
import com.muz.flowpacer.engine.PacketInspector
import com.muz.flowpacer.engine.TokenBucket
import com.muz.flowpacer.proxy.LocalProxyServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections

class FlowPacerService : VpnService() {

    data class DnsCandidate(
        val typeId: Int,
        val label: String,
        val primaryIp: String,
        val secondaryIp: String
    )

    private var vpnInterface: ParcelFileDescriptor? = null
    private var proxyServer: LocalProxyServer? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var adaptiveMonitorJob: Job? = null
    private var tunWorkerJob: Job? = null

    private val uploadTokenBucket = TokenBucket(
        maxTokens = 16 * 1024,
        initialRateMbps = 3.8
    )

    private val downloadTokenBucket = TokenBucket(
        maxTokens = 32 * 1024,
        initialRateMbps = 16.0
    )

    private var currentAdaptiveUploadMbps = 3.8
    private var currentAdaptiveDownloadMbps = 16.0
    private var baselinePing = 0L

    // Tetingkap bergerak untuk mengelakkan perangkap baseline ping
    private val pingHistory = Collections.synchronizedList(mutableListOf<Long>())

    @Volatile
    private var activeDnsDisplayName: String = "--"

    companion object {
        const val ACTION_START = "com.muz.flowpacer.START"
        const val ACTION_STOP = "com.muz.flowpacer.STOP"
        const val ACTION_SET_MODE = "com.muz.flowpacer.SET_MODE"
        const val ACTION_SET_DNS = "com.muz.flowpacer.SET_DNS"
        const val ACTION_METRICS_UPDATE = "com.muz.flowpacer.METRICS_UPDATE"

        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_DNS_TYPE = "extra_dns_type"
        const val EXTRA_PING = "extra_ping"
        const val EXTRA_RATE_MBPS = "extra_rate_mbps"
        const val EXTRA_DL_RATE_MBPS = "extra_dl_rate_mbps"
        const val EXTRA_TX_BYTES = "extra_tx_bytes"
        const val EXTRA_RX_BYTES = "extra_rx_bytes"
        const val EXTRA_ACTIVE_DNS = "extra_active_dns"

        const val PREFS_NAME = "flowpacer_prefs"
        const val PREF_EXCLUDED_APPS = "excluded_apps"
        const val PREF_DNS_TYPE = "dns_type"

        // Pengenalan Jenis DNS
        const val DNS_AUTO = 0
        const val DNS_CLOUDFLARE_SEC = 1
        const val DNS_QUAD9 = 2
        const val DNS_OPENDNS = 3
        const val DNS_CLOUDFLARE = 4
        const val DNS_ADGUARD = 5
        const val DNS_GOOGLE = 6

        val DNS_SERVERS = listOf(
            DnsCandidate(DNS_CLOUDFLARE_SEC, "Cloudflare Sec", "1.1.1.2", "1.0.0.2"),
            DnsCandidate(DNS_QUAD9, "Quad9", "9.9.9.9", "149.112.112.112"),
            DnsCandidate(DNS_OPENDNS, "OpenDNS Cisco", "208.67.222.123", "208.67.220.123"),
            DnsCandidate(DNS_CLOUDFLARE, "Cloudflare", "1.1.1.1", "1.0.0.1"),
            DnsCandidate(DNS_ADGUARD, "AdGuard", "94.140.14.14", "94.140.15.15"),
            DnsCandidate(DNS_GOOGLE, "Google", "8.8.8.8", "8.8.4.4")
        )

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
            ACTION_SET_DNS -> {
                val newDns = intent.getIntExtra(EXTRA_DNS_TYPE, DNS_AUTO)
                if (isServiceRunning) {
                    serviceScope.launch {
                        applyDnsSelection(newDns)
                    }
                }
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
        downloadTokenBucket.setMode(mode)

        if (mode == TokenBucket.MODE_GAMING) {
            uploadTokenBucket.adjustRate(3.8)
            downloadTokenBucket.adjustRate(12.0)
        } else if (mode == TokenBucket.MODE_AUTO) {
            uploadTokenBucket.adjustRate(currentAdaptiveUploadMbps)
            downloadTokenBucket.adjustRate(currentAdaptiveDownloadMbps)
        }

        updateNotification()

        val reportedUl = if (mode == TokenBucket.MODE_GAMING) 3.8 else if (mode == TokenBucket.MODE_UNLIMITED) -1.0 else currentAdaptiveUploadMbps
        val reportedDl = if (mode == TokenBucket.MODE_GAMING) 12.0 else if (mode == TokenBucket.MODE_UNLIMITED) -1.0 else currentAdaptiveDownloadMbps
        broadcastMetrics(-1L, reportedUl, reportedDl)
    }

    private fun startOptimization() {
        if (isServiceRunning) return

        startForegroundNotification()

        serviceScope.launch {
            try {
                // 1. Mulakan Proksi Tempatan
                proxyServer = LocalProxyServer(
                    port = PROXY_PORT,
                    uploadTokenBucket = uploadTokenBucket,
                    downloadTokenBucket = downloadTokenBucket,
                    onProtectSocket = { socket -> protect(socket) }
                ).apply {
                    start()
                }

                // 2. Tentukan dan Bina Antaramuka DNS + L3 VPN
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val dnsType = prefs.getInt(PREF_DNS_TYPE, DNS_AUTO)
                buildVpnTunInterface(dnsType)

                isServiceRunning = true

                startTunPacketWorker()
                startAdaptiveFeedbackLoop()
                updateNotification()

            } catch (e: Exception) {
                e.printStackTrace()
                stopOptimization()
            }
        }
    }

    /**
     * Menguji dan memilih DNS paling laju atau mengunci pelayan manual pilihan pengguna.
     */
    private suspend fun resolveTargetDns(dnsType: Int): Pair<DnsCandidate, Boolean> {
        if (dnsType == DNS_AUTO) {
            // Tembak semua DNS serentak, cari yang mencatat kependaman terendah
            val fastest = findFastestDns()
            return fastest to true
        }

        val manual = DNS_SERVERS.find { it.typeId == dnsType } ?: DNS_SERVERS[0]
        return manual to false
    }

    private suspend fun findFastestDns(): DnsCandidate = withContext(Dispatchers.IO) {
        val deferredList = DNS_SERVERS.map { candidate ->
            async {
                val ping = probePortLatency(candidate.primaryIp, 53, 1200)
                candidate to ping
            }
        }
        val results = deferredList.awaitAll()
        val successful = results.filter { it.second >= 0 }
        val winner = successful.minByOrNull { it.second }?.first ?: DNS_SERVERS[0]
        winner
    }

    /**
     * Membina atau mengemas kini sambungan antaramuka TUN VPN secara selamat (Hot-Reload).
     */
    private suspend fun buildVpnTunInterface(dnsType: Int) = withContext(Dispatchers.IO) {
        val (selectedDns, isAuto) = resolveTargetDns(dnsType)

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(1500)
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)

        // Masukkan IP Utama & Sandaran DNS terpilih
        builder.addDnsServer(selectedDns.primaryIp)
        builder.addDnsServer(selectedDns.secondaryIp)

        // Kecualikan pakej sendiri bagi mengelakkan loopback
        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: PackageManager.NameNotFoundException) {}

        // Pengendalian Split Tunneling aplikasi
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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

        val oldInterface = vpnInterface
        vpnInterface = builder.establish()

        try {
            oldInterface?.close()
        } catch (_: Exception) {}

        activeDnsDisplayName = if (isAuto) "${selectedDns.label} [Auto]" else "${selectedDns.label} [Manual]"
    }

    private suspend fun applyDnsSelection(dnsType: Int) {
        buildVpnTunInterface(dnsType)
        startTunPacketWorker()
        updateNotification()
        broadcastMetrics(-1L, currentAdaptiveUploadMbps, currentAdaptiveDownloadMbps)
    }

    private fun startTunPacketWorker() {
        tunWorkerJob?.cancel()
        val pfd = vpnInterface ?: return

        tunWorkerJob = serviceScope.launch(Dispatchers.IO) {
            var vpnInput: FileInputStream? = null
            var vpnOutput: FileOutputStream? = null

            try {
                vpnInput = FileInputStream(pfd.fileDescriptor)
                vpnOutput = FileOutputStream(pfd.fileDescriptor)
                val packetBuffer = ByteArray(32767)

                while (isActive && isServiceRunning) {
                    val length = vpnInput.read(packetBuffer)
                    if (length <= 0) continue

                    val isPriority = PacketInspector.isHighPriority(packetBuffer, length)
                    val protocol = PacketInspector.getProtocol(packetBuffer, length)

                    if (isPriority) {
                        // Laluan Ekspres: Dihantar serta-merta tanpa senggang masa
                    } else if (protocol == PacketInspector.PROTOCOL_UDP) {
                        val delayMs = uploadTokenBucket.acquire(length)
                        if (delayMs > 0) delay(delayMs)
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { vpnInput?.close() } catch (_: Exception) {}
                try { vpnOutput?.close() } catch (_: Exception) {}
            }
        }
    }

    private fun startAdaptiveFeedbackLoop() {
        adaptiveMonitorJob?.cancel()
        adaptiveMonitorJob = serviceScope.launch {
            while (isActive) {
                val ping = probeBestLatency(2000)

                if (ping > 0) {
                    pingHistory.add(ping)
                    if (pingHistory.size > 15) {
                        pingHistory.removeAt(0)
                    }

                    baselinePing = pingHistory.minOrNull() ?: ping

                    if (activeMode == TokenBucket.MODE_AUTO) {
                        val bloatDelta = ping - baselinePing

                        when {
                            // 1. ZON CEMAS
                            ping > 300 || bloatDelta > 180 -> {
                                currentAdaptiveUploadMbps = 1.0
                                currentAdaptiveDownloadMbps = 4.0
                                uploadTokenBucket.adjustRate(currentAdaptiveUploadMbps)
                                downloadTokenBucket.adjustRate(currentAdaptiveDownloadMbps)
                            }
                            // 2. SESAK TINGGI
                            bloatDelta > 60 -> {
                                currentAdaptiveUploadMbps = (currentAdaptiveUploadMbps * 0.60).coerceAtLeast(1.2)
                                currentAdaptiveDownloadMbps = (currentAdaptiveDownloadMbps * 0.70).coerceAtLeast(6.0)
                                uploadTokenBucket.adjustRate(currentAdaptiveUploadMbps)
                                downloadTokenBucket.adjustRate(currentAdaptiveDownloadMbps)
                            }
                            // 3. MULA MERAYAP
                            bloatDelta > 20 -> {
                                currentAdaptiveUploadMbps = (currentAdaptiveUploadMbps * 0.85).coerceAtLeast(1.5)
                                currentAdaptiveDownloadMbps = (currentAdaptiveDownloadMbps * 0.90).coerceAtLeast(10.0)
                                uploadTokenBucket.adjustRate(currentAdaptiveUploadMbps)
                                downloadTokenBucket.adjustRate(currentAdaptiveDownloadMbps)
                            }
                            // 4. TALIAN LAPANG & TENANG
                            bloatDelta <= 10 -> {
                                currentAdaptiveUploadMbps = (currentAdaptiveUploadMbps + 0.4).coerceAtMost(30.0)
                                currentAdaptiveDownloadMbps = (currentAdaptiveDownloadMbps + 1.2).coerceAtMost(40.0)
                                uploadTokenBucket.adjustRate(currentAdaptiveUploadMbps)
                                downloadTokenBucket.adjustRate(currentAdaptiveDownloadMbps)
                            }
                        }
                        broadcastMetrics(ping, currentAdaptiveUploadMbps, currentAdaptiveDownloadMbps)
                    } else if (activeMode == TokenBucket.MODE_GAMING) {
                        broadcastMetrics(ping, 3.8, 12.0)
                    } else {
                        broadcastMetrics(ping, -1.0, -1.0)
                    }
                }

                val nextDelay = if (ping > 150 || ping < 0) 1500L else 3000L
                delay(nextDelay)
            }
        }
    }

    private suspend fun probeBestLatency(timeoutMs: Int): Long = withContext(Dispatchers.IO) {
        val targets = listOf(
            "1.1.1.1" to 443,
            "8.8.8.8" to 443
        )
        val deferreds = targets.map { (host, port) ->
            async {
                probePortLatency(host, port, timeoutMs)
            }
        }
        val results = deferreds.awaitAll().filter { it >= 0 }
        results.minOrNull() ?: -1L
    }

    private fun probePortLatency(host: String, port: Int, timeoutMs: Int): Long {
        val socket = Socket()
        return try {
            protect(socket)
            val start = System.currentTimeMillis()
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            val duration = System.currentTimeMillis() - start
            duration
        } catch (_: Exception) {
            -1L
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun broadcastMetrics(ping: Long, ulMbps: Double, dlMbps: Double) {
        val tx = proxyServer?.totalBytesUploaded?.get() ?: 0L
        val rx = proxyServer?.totalBytesDownloaded?.get() ?: 0L

        val intent = Intent(ACTION_METRICS_UPDATE).apply {
            putExtra(EXTRA_PING, ping)
            putExtra(EXTRA_RATE_MBPS, ulMbps)
            putExtra(EXTRA_DL_RATE_MBPS, dlMbps)
            putExtra(EXTRA_TX_BYTES, tx)
            putExtra(EXTRA_RX_BYTES, rx)
            putExtra(EXTRA_ACTIVE_DNS, activeDnsDisplayName)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun stopOptimization() {
        adaptiveMonitorJob?.cancel()
        adaptiveMonitorJob = null

        tunWorkerJob?.cancel()
        tunWorkerJob = null

        pingHistory.clear()
        baselinePing = 0L
        activeDnsDisplayName = "--"

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
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
            TokenBucket.MODE_GAMING -> getString(R.string.notif_mode_gaming)
            TokenBucket.MODE_UNLIMITED -> getString(R.string.notif_mode_unlimited)
            else -> getString(R.string.notif_mode_auto)
        }

        val dnsLabel = if (activeDnsDisplayName.isNotEmpty()) activeDnsDisplayName else "--"

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title_active))
            .setContentText(getString(R.string.notif_desc_format, modeLabel, dnsLabel))
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
