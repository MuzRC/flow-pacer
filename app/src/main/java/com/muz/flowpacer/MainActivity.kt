package com.muz.flowpacer

import android.Manifest
import android.app.Activity
import android.content.Intent
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
import com.muz.flowpacer.service.FlowPacerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Pelancar izin sistem VpnService
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startFlowPacerService()
        } else {
            Toast.makeText(this, getString(R.string.err_vpn_permission_denied), Toast.LENGTH_SHORT).show()
        }
    }

    // Pelancar izin notifikasi untuk Android 13 (API 33) ke atas
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Pengendalian izin notifikasi latar belakang
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkNotificationPermission()
        setupListeners()
        updateUiState()
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
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
            binding.tvStatusDesc.text = "Kadar muat naik dihadkan (~3.8 Mbps) untuk mengelakkan antrean repeater membuak."
            binding.imgStatusIcon.setImageResource(android.R.drawable.ic_lock_idle_charging)
            binding.imgStatusIcon.setColorFilter(Color.parseColor("#34D399")) // Hijau aktif
            binding.btnToggleService.text = getString(R.string.btn_stop)
            binding.btnToggleService.setBackgroundColor(Color.parseColor("#DC2626")) // Merah henti
        } else {
            binding.tvStatusTitle.text = getString(R.string.status_idle)
            binding.tvStatusDesc.text = "Trafik bergerak pada laluan lalai tanpa kawalan antrean."
            binding.imgStatusIcon.setImageResource(android.R.drawable.ic_lock_idle_lock)
            binding.imgStatusIcon.setColorFilter(Color.parseColor("#64748B")) // Kelabu rehat
            binding.btnToggleService.text = getString(R.string.btn_start)
            binding.btnToggleService.setBackgroundColor(Color.parseColor("#0284C7")) // Biru mula
        }
    }

    private fun runLatencyTest() {
        binding.tvPingValue.text = "Menguji..."
        binding.btnTestPing.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val pingMs = measureLatency("1.1.1.1", 53, 1500)

            withContext(Dispatchers.Main) {
                binding.btnTestPing.isEnabled = true
                if (pingMs >= 0) {
                    binding.tvPingValue.text = "$pingMs ms"
                    when {
                        pingMs < 100 -> binding.tvPingValue.setTextColor(Color.parseColor("#34D399")) // Hijau cemerlang
                        pingMs < 250 -> binding.tvPingValue.setTextColor(Color.parseColor("#FBBF24")) // Kuning sederhana
                        else -> binding.tvPingValue.setTextColor(Color.parseColor("#F87171")) // Merah sesak
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
