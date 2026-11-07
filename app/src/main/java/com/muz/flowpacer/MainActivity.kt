package com.muz.flowpacer

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.muz.flowpacer.databinding.ActivityMainBinding
import com.muz.flowpacer.engine.TokenBucket
import com.muz.flowpacer.service.FlowPacerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startFlowPacerService()
        } else {
            Toast.makeText(this, getString(R.string.err_vpn_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    private val metricsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == FlowPacerService.ACTION_METRICS_UPDATE) {
                val ping = intent.getLongExtra(FlowPacerService.EXTRA_PING, -1L)
                val ulMbps = intent.getDoubleExtra(FlowPacerService.EXTRA_RATE_MBPS, -1.0)
                val dlMbps = intent.getDoubleExtra(FlowPacerService.EXTRA_DL_RATE_MBPS, -1.0)
                val tx = intent.getLongExtra(FlowPacerService.EXTRA_TX_BYTES, 0L)
                val rx = intent.getLongExtra(FlowPacerService.EXTRA_RX_BYTES, 0L)
                val activeDns = intent.getStringExtra(FlowPacerService.EXTRA_ACTIVE_DNS)

                // Kemas kini paparan DNS Aktif
                if (!activeDns.isNullOrEmpty() && activeDns != "--") {
                    binding.tvActiveDns.text = activeDns
                    binding.tvActiveDns.setTextColor(Color.parseColor("#38BDF8"))
                } else if (!FlowPacerService.isServiceRunning) {
                    binding.tvActiveDns.text = getString(R.string.metric_dns_default)
                    binding.tvActiveDns.setTextColor(Color.parseColor("#94A3B8"))
                }

                // Kemas kini paparan Kependaman (Ping)
                if (ping >= 0) {
                    binding.tvPingValue.text = getString(R.string.metric_ping, ping)
                    when {
                        ping < 100 -> binding.tvPingValue.setTextColor(Color.parseColor("#34D399")) // Hijau
                        ping < 200 -> binding.tvPingValue.setTextColor(Color.parseColor("#FBBF24")) // Kuning
                        else -> binding.tvPingValue.setTextColor(Color.parseColor("#F87171")) // Merah
                    }
                }

                // Kemas kini Siling Had Laju Shaper
                when (FlowPacerService.activeMode) {
                    TokenBucket.MODE_GAMING -> {
                        binding.tvShaperLimit.text = getString(R.string.metric_shaper_gaming)
                        binding.tvShaperLimit.setTextColor(Color.parseColor("#FBBF24"))
                    }
                    TokenBucket.MODE_UNLIMITED -> {
                        binding.tvShaperLimit.text = getString(R.string.metric_shaper_unlimited)
                        binding.tvShaperLimit.setTextColor(Color.parseColor("#38BDF8"))
                    }
                    else -> {
                        if (ulMbps > 0 && dlMbps > 0) {
                            binding.tvShaperLimit.text = getString(R.string.metric_shaper_limit, ulMbps, dlMbps)
                        } else {
                            binding.tvShaperLimit.text = getString(R.string.metric_shaper_dynamic)
                        }
                        binding.tvShaperLimit.setTextColor(Color.parseColor("#34D399"))
                    }
                }

                // Kemas kini Trafik Terkawal Sesi
                binding.tvSessionTraffic.text = getString(
                    R.string.metric_traffic_format,
                    formatBytes(rx),
                    formatBytes(tx)
                )

                updateUiState()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.scrollChips.post { binding.scrollChips.scrollTo(0, 0) }
        binding.scrollDns.post { binding.scrollDns.scrollTo(0, 0) }

        checkNotificationPermission()
        setupListeners()
        syncSavedDnsSelection()
        updateUiState()
    }

    override fun onResume() {
        super.onResume()
        syncSelectedChipWithService()
        syncSavedDnsSelection()
        updateUiState()

        val filter = IntentFilter(FlowPacerService.ACTION_METRICS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(metricsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(metricsReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(metricsReceiver)
        } catch (_: Exception) {}
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun setupListeners() {
        binding.btnToggleService.setOnClickListener {
            if (FlowPacerService.isServiceRunning) {
                stopFlowPacerService()
            } else {
                prepareVpnAndStart()
            }
        }

        binding.btnTestPing.setOnClickListener {
            runLatencyTest()
        }

        binding.btnExcludedApps.setOnClickListener {
            showAppExclusionDialog()
        }

        // Pemilihan Profil Operasi
        binding.chipGroupMode.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener

            val selectedMode = when (checkedIds.first()) {
                R.id.chipGaming -> TokenBucket.MODE_GAMING
                R.id.chipUnlimited -> TokenBucket.MODE_UNLIMITED
                else -> TokenBucket.MODE_AUTO
            }

            dispatchModeChange(selectedMode)
            updateModeDiagnostics(selectedMode)
        }

        // Pemilihan Pelayan DNS (Auto atau Manual)
        binding.chipGroupDns.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener

            val (dnsType, dnsName) = when (checkedIds.first()) {
                R.id.chipDnsCloudflareSec -> FlowPacerService.DNS_CLOUDFLARE_SEC to "Cloudflare Sec (1.1.1.2)"
                R.id.chipDnsQuad9 -> FlowPacerService.DNS_QUAD9 to "Quad9 (9.9.9.9)"
                R.id.chipDnsOpenDns -> FlowPacerService.DNS_OPENDNS to "OpenDNS Cisco"
                R.id.chipDnsCloudflare -> FlowPacerService.DNS_CLOUDFLARE to "Cloudflare (1.1.1.1)"
                R.id.chipDnsAdGuard -> FlowPacerService.DNS_ADGUARD to "AdGuard (Sekat Iklan)"
                R.id.chipDnsGoogle -> FlowPacerService.DNS_GOOGLE to "Google (8.8.8.8)"
                else -> FlowPacerService.DNS_AUTO to "Auto (Ping Terpantas)"
            }

            val prefs = getSharedPreferences(FlowPacerService.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putInt(FlowPacerService.PREF_DNS_TYPE, dnsType).apply()

            if (FlowPacerService.isServiceRunning) {
                dispatchDnsChange(dnsType)
                Toast.makeText(this, getString(R.string.toast_dns_switched, dnsName), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun syncSavedDnsSelection() {
        val prefs = getSharedPreferences(FlowPacerService.PREFS_NAME, Context.MODE_PRIVATE)
        when (prefs.getInt(FlowPacerService.PREF_DNS_TYPE, FlowPacerService.DNS_AUTO)) {
            FlowPacerService.DNS_CLOUDFLARE_SEC -> binding.chipDnsCloudflareSec.isChecked = true
            FlowPacerService.DNS_QUAD9 -> binding.chipDnsQuad9.isChecked = true
            FlowPacerService.DNS_OPENDNS -> binding.chipDnsOpenDns.isChecked = true
            FlowPacerService.DNS_CLOUDFLARE -> binding.chipDnsCloudflare.isChecked = true
            FlowPacerService.DNS_ADGUARD -> binding.chipDnsAdGuard.isChecked = true
            FlowPacerService.DNS_GOOGLE -> binding.chipDnsGoogle.isChecked = true
            else -> binding.chipDnsAuto.isChecked = true
        }
    }

    private fun showAppExclusionDialog() {
        binding.btnExcludedApps.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
                .filter { it.activityInfo.packageName != packageName }
                .sortedBy { it.loadLabel(pm).toString().lowercase(Locale.ROOT) }

            val appNames = resolveInfos.map { it.loadLabel(pm).toString() }.toTypedArray()
            val appPackages = resolveInfos.map { it.activityInfo.packageName }.toTypedArray()

            val prefs = getSharedPreferences(FlowPacerService.PREFS_NAME, Context.MODE_PRIVATE)
            val savedExcluded = prefs.getStringSet(FlowPacerService.PREF_EXCLUDED_APPS, emptySet())?.toMutableSet() ?: mutableSetOf()
            val checkedItems = BooleanArray(appPackages.size) { idx ->
                savedExcluded.contains(appPackages[idx])
            }

            withContext(Dispatchers.Main) {
                binding.btnExcludedApps.isEnabled = true
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.dialog_split_tunnel_title))
                    .setMultiChoiceItems(appNames, checkedItems) { _, which, isChecked ->
                        val pkg = appPackages[which]
                        if (isChecked) savedExcluded.add(pkg) else savedExcluded.remove(pkg)
                    }
                    .setPositiveButton(getString(R.string.dialog_btn_save)) { _, _ ->
                        prefs.edit().putStringSet(FlowPacerService.PREF_EXCLUDED_APPS, savedExcluded).apply()
                        Toast.makeText(this@MainActivity, getString(R.string.toast_apps_saved, savedExcluded.size), Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(getString(R.string.dialog_btn_cancel), null)
                    .show()
            }
        }
    }

    private fun dispatchModeChange(mode: Int) {
        if (FlowPacerService.isServiceRunning) {
            val intent = Intent(this, FlowPacerService::class.java).apply {
                action = FlowPacerService.ACTION_SET_MODE
                putExtra(FlowPacerService.EXTRA_MODE, mode)
            }
            startService(intent)
        }
    }

    private fun dispatchDnsChange(dnsType: Int) {
        if (FlowPacerService.isServiceRunning) {
            val intent = Intent(this, FlowPacerService::class.java).apply {
                action = FlowPacerService.ACTION_SET_DNS
                putExtra(FlowPacerService.EXTRA_DNS_TYPE, dnsType)
            }
            startService(intent)
        }
    }

    private fun syncSelectedChipWithService() {
        when (FlowPacerService.activeMode) {
            TokenBucket.MODE_GAMING -> binding.chipGaming.isChecked = true
            TokenBucket.MODE_UNLIMITED -> binding.chipUnlimited.isChecked = true
            else -> binding.chipAuto.isChecked = true
        }
        updateModeDiagnostics(FlowPacerService.activeMode)
    }

    private fun updateModeDiagnostics(mode: Int) {
        when (mode) {
            TokenBucket.MODE_GAMING -> {
                binding.tvShaperLimit.text = getString(R.string.metric_shaper_gaming)
                binding.tvShaperLimit.setTextColor(Color.parseColor("#FBBF24"))
            }
            TokenBucket.MODE_UNLIMITED -> {
                binding.tvShaperLimit.text = getString(R.string.metric_shaper_unlimited)
                binding.tvShaperLimit.setTextColor(Color.parseColor("#38BDF8"))
            }
            else -> {
                binding.tvShaperLimit.text = getString(R.string.metric_shaper_dynamic)
                binding.tvShaperLimit.setTextColor(Color.parseColor("#34D399"))
            }
        }
    }

    private fun prepareVpnAndStart() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            startFlowPacerService()
        }
    }

    private fun startFlowPacerService() {
        binding.tvActiveDns.text = getString(R.string.metric_dns_measuring)
        val intent = Intent(this, FlowPacerService::class.java).apply {
            action = FlowPacerService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        binding.root.postDelayed({ updateUiState() }, 200)
    }

    private fun stopFlowPacerService() {
        val intent = Intent(this, FlowPacerService::class.java).apply {
            action = FlowPacerService.ACTION_STOP
        }
        startService(intent)
        binding.tvActiveDns.text = getString(R.string.metric_dns_default)
        binding.root.postDelayed({ updateUiState() }, 200)
    }

    private fun updateUiState() {
        val isRunning = FlowPacerService.isServiceRunning

        if (isRunning) {
            binding.tvStatusTitle.text = getString(R.string.status_running)
            binding.tvStatusDesc.text = getString(R.string.status_desc_running)
            binding.imgStatusIcon.setImageResource(android.R.drawable.ic_lock_idle_charging)
            binding.imgStatusIcon.setColorFilter(Color.parseColor("#34D399"))
            binding.btnToggleService.text = getString(R.string.btn_stop)
            binding.btnToggleService.setBackgroundColor(Color.parseColor("#DC2626"))
        } else {
            binding.tvStatusTitle.text = getString(R.string.status_idle)
            binding.tvStatusDesc.text = getString(R.string.status_desc_idle)
            binding.imgStatusIcon.setImageResource(android.R.drawable.ic_lock_idle_lock)
            binding.imgStatusIcon.setColorFilter(Color.parseColor("#64748B"))
            binding.btnToggleService.text = getString(R.string.btn_start)
            binding.btnToggleService.setBackgroundColor(Color.parseColor("#0284C7"))
            binding.tvActiveDns.text = getString(R.string.metric_dns_default)
        }
    }

    private fun runLatencyTest() {
        binding.tvPingValue.text = getString(R.string.metric_ping_testing)
        binding.btnTestPing.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val pingMs = measureBestLatency(2500)

            withContext(Dispatchers.Main) {
                binding.btnTestPing.isEnabled = true
                if (pingMs >= 0) {
                    binding.tvPingValue.text = getString(R.string.metric_ping, pingMs)
                    when {
                        pingMs < 100 -> binding.tvPingValue.setTextColor(Color.parseColor("#34D399"))
                        pingMs < 200 -> binding.tvPingValue.setTextColor(Color.parseColor("#FBBF24"))
                        else -> binding.tvPingValue.setTextColor(Color.parseColor("#F87171"))
                    }
                } else {
                    binding.tvPingValue.text = getString(R.string.metric_ping_failed)
                    binding.tvPingValue.setTextColor(Color.parseColor("#EF4444"))
                }
            }
        }
    }

    private suspend fun measureBestLatency(timeoutMs: Int): Long = withContext(Dispatchers.IO) {
        val targets = listOf(
            "1.1.1.1" to 443,
            "8.8.8.8" to 443
        )
        val deferreds = targets.map { (host, port) ->
            async {
                val socket = Socket()
                try {
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
        }
        val results = deferreds.awaitAll().filter { it >= 0 }
        results.minOrNull() ?: -1L
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / (1024.0 * 1024.0))
            else -> "$bytes B"
        }
    }
}
