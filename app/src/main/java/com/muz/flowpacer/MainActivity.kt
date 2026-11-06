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

    // Penerima data langsung (Live Broadcast) dari FlowPacerService
    private val metricsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == FlowPacerService.ACTION_METRICS_UPDATE) {
                val ping = intent.getLongExtra(FlowPacerService.EXTRA_PING, -1L)
                val rateMbps = intent.getDoubleExtra(FlowPacerService.EXTRA_RATE_MBPS, -1.0)

                // Kemas kini paparan ping secara live
                if (ping >= 0) {
                    binding.tvPingValue.text = "$ping ms"
                    when {
                        ping < 100 -> binding.tvPingValue.setTextColor(Color.parseColor("#34D399")) // Hijau
                        ping < 200 -> binding.tvPingValue.setTextColor(Color.parseColor("#FBBF24")) // Kuning
                        else -> binding.tvPingValue.setTextColor(Color.parseColor("#F87171")) // Merah
                    }
                }

                // Kemas kini angka siling muat naik dinamik (Live Mbps)
                when (FlowPacerService.activeMode) {
                    TokenBucket.MODE_GAMING -> {
                        binding.tvShaperLimit.text = "Terkunci (3.8 Mbps)"
                        binding.tvShaperLimit.setTextColor(Color.parseColor("#FBBF24"))
                    }
                    TokenBucket.MODE_UNLIMITED -> {
                        binding.tvShaperLimit.text = "Tiada Had (Bypass)"
                        binding.tvShaperLimit.setTextColor(Color.parseColor("#38BDF8"))
                    }
                    else -> {
                        if (rateMbps > 0) {
                            binding.tvShaperLimit.text = String.format(Locale.US, "%.1f Mbps (Auto)", rateMbps)
                        } else {
                            binding.tvShaperLimit.text = "Dinamik Adaptif"
                        }
                        binding.tvShaperLimit.setTextColor(Color.parseColor("#34D399"))
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Pastikan paparan cip sentiasa bermula di kedudukan paling kiri
        binding.scrollChips.post {
            binding.scrollChips.scrollTo(0, 0)
        }

        checkNotificationPermission()
        setupListeners()
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
                binding.tvShaperLimit.text = "Terkunci (3.8 Mbps)"
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
            binding.tvStatusDesc.text = "Perlindungan aktif. DNS pantas dan kawalan kependaman sedang berjalan."
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
}
