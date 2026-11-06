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

                if (ping >= 0) {
                    binding.tvPingValue.text = "$ping ms"
                    when {
                        ping < 100 -> binding.tvPingValue.setTextColor(Color.parseColor("#34D399"))
                        ping < 200 -> binding.tvPingValue.setTextColor(Color.parseColor("#FBBF24"))
                        else -> binding.tvPingValue.setTextColor(Color.parseColor("#F87171"))
                    }
                }

                // Paparkan kedua-dua siling (Muat Naik & Muat Turun)
                when (FlowPacerService.activeMode) {
                    TokenBucket.MODE_GAMING -> {
                        binding.tvShaperLimit.text = "Kunci (UL: 3.8M | DL: 12M)"
                        binding.tvShaperLimit.setTextColor(Color.parseColor("#FBBF24"))
                    }
                    TokenBucket.MODE_UNLIMITED -> {
                        binding.tvShaperLimit.text = "Tiada Had (Bypass)"
                        binding.tvShaperLimit.setTextColor(Color.parseColor("#38BDF8"))
                    }
                    else -> {
                        if (ulMbps > 0 && dlMbps > 0) {
                            binding.tvShaperLimit.text = String.format(Locale.US, "UL: %.1fM | DL: %.0fM", ulMbps, dlMbps)
                        } else {
                            binding.tvShaperLimit.text = "Dinamik Adaptif"
                        }
                        binding.tvShaperLimit.setTextColor(Color.parseColor("#34D399"))
                    }
                }

                binding.tvSessionTraffic.text = "DL: ${formatBytes(rx)} | UL: ${formatBytes(tx)}"
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

        binding.chipGroupDns.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener

            val dnsType = when (checkedIds.first()) {
                R.id.chipDnsAdGuard -> FlowPacerService.DNS_ADGUARD
                R.id.chipDnsGoogle -> FlowPacerService.DNS_GOOGLE
                else -> FlowPacerService.DNS_CLOUDFLARE
            }

            val prefs = getSharedPreferences(FlowPacerService.PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putInt(FlowPacerService.PREF_DNS_TYPE, dnsType).apply()

            if (FlowPacerService.isServiceRunning) {
                Toast.makeText(this, "Mulakan semula servis untuk mengaktifkan DNS baru", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun syncSavedDnsSelection() {
        val prefs = getSharedPreferences(FlowPacerService.PREFS_NAME, Context.MODE_PRIVATE)
        when (prefs.getInt(FlowPacerService.PREF_DNS_TYPE, FlowPacerService.DNS_CLOUDFLARE)) {
            FlowPacerService.DNS_ADGUARD -> binding.chipDnsAdGuard.isChecked = true
            FlowPacerService.DNS_GOOGLE -> binding.chipDnsGoogle.isChecked = true
            else -> binding.chipDnsCloudflare.isChecked = true
        }
    }

    private fun showAppExclusionDialog() {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString() }

        val appNames = resolveInfos.map { it.loadLabel(pm).toString() }.toTypedArray()
        val appPackages = resolveInfos.map { it.activityInfo.packageName }.toTypedArray()

        val prefs = getSharedPreferences(FlowPacerService.PREFS_NAME, Context.MODE_PRIVATE)
        val savedExcluded = prefs.getStringSet(FlowPacerService.PREF_EXCLUDED_APPS, emptySet())?.toMutableSet() ?: mutableSetOf()
        val checkedItems = BooleanArray(appPackages.size) { idx ->
            savedExcluded.contains(appPackages[idx])
        }

        AlertDialog.Builder(this)
            .setTitle("Pilih Aplikasi Dikecualikan")
            .setMultiChoiceItems(appNames, checkedItems) { _, which, isChecked ->
                val pkg = appPackages[which]
                if (isChecked) savedExcluded.add(pkg) else savedExcluded.remove(pkg)
            }
            .setPositiveButton("Simpan") { _, _ ->
                prefs.edit().putStringSet(FlowPacerService.PREF_EXCLUDED_APPS, savedExcluded).apply()
                Toast.makeText(this, "Tetapan disimpan (${savedExcluded.size} aplikasi)", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)
            .show()
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
                binding.tvShaperLimit.text = "Kunci (UL: 3.8M | DL: 12M)"
                binding.tvShaperLimit.setTextColor(Color.parseColor("#FBBF24"))
            }
            TokenBucket.MODE_UNLIMITED -> {
                binding.tvShaperLimit.text = "Tiada Had (Bypass)"
                binding.tvShaperLimit.setTextColor(Color.parseColor("#38BDF8"))
            }
            else -> {
                binding.tvShaperLimit.text = "Dinamik Adaptif"
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
        val intent = Intent(this, FlowPacerService::class.java).apply {
            action = FlowPacerService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        binding.root.postDelayed({ updateUiState() }, 350)
    }

    private fun stopFlowPacerService() {
        val intent = Intent(this, FlowPacerService::class.java).apply {
            action = FlowPacerService.ACTION_STOP
        }
        startService(intent)
        binding.root.postDelayed({ updateUiState() }, 350)
    }

    private fun updateUiState() {
        val isRunning = FlowPacerService.isServiceRunning

        if (isRunning) {
            binding.tvStatusTitle.text = getString(R.string.status_running)
            binding.tvStatusDesc.text = "Perlindungan aktif. Kawalan kependaman dua hala sedang berjalan."
            binding.imgStatusIcon.setImageResource(android.R.drawable.ic_lock_idle_charging)
            binding.imgStatusIcon.setColorFilter(Color.parseColor("#34D399"))
            binding.btnToggleService.text = getString(R.string.btn_stop)
            binding.btnToggleService.setBackgroundColor(Color.parseColor("#DC2626"))
        } else {
            binding.tvStatusTitle.text = getString(R.string.status_idle)
            binding.tvStatusDesc.text = "Trafik bergerak pada laluan lalai tanpa kawalan antrean."
            binding.imgStatusIcon.setImageResource(android.R.drawable.ic_lock_idle_lock)
            binding.imgStatusIcon.setColorFilter(Color.parseColor("#64748B"))
            binding.btnToggleService.text = getString(R.string.btn_start)
            binding.btnToggleService.setBackgroundColor(Color.parseColor("#0284C7"))
        }
    }

    private fun runLatencyTest() {
        binding.tvPingValue.text = "Menguji..."
        binding.btnTestPing.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val pingMs = measureLatency("1.1.1.1", 443, 2500)

            withContext(Dispatchers.Main) {
                binding.btnTestPing.isEnabled = true
                if (pingMs >= 0) {
                    binding.tvPingValue.text = "$pingMs ms"
                    when {
                        pingMs < 100 -> binding.tvPingValue.setTextColor(Color.parseColor("#34D399"))
                        pingMs < 200 -> binding.tvPingValue.setTextColor(Color.parseColor("#FBBF24"))
                        else -> binding.tvPingValue.setTextColor(Color.parseColor("#F87171"))
                    }
                } else {
                    binding.tvPingValue.text = "Gagal"
                    binding.tvPingValue.setTextColor(Color.parseColor("#EF4444"))
                }
            }
        }
    }

    private fun measureLatency(host: String, port: Int, timeoutMs: Int): Long {
        val socket = Socket()
        val startTime = System.currentTimeMillis()
        return try {
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            val duration = System.currentTimeMillis() - startTime
            socket.close()
            duration
        } catch (_: Exception) {
            -1L
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
