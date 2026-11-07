package com.muz.flowpacer.service

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.muz.flowpacer.MainActivity
import com.muz.flowpacer.R

class FlowPacerTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val isRunning = FlowPacerService.isServiceRunning

        if (isRunning) {
            // Matikan servis jika sedang beroperasi
            val stopIntent = Intent(this, FlowPacerService::class.java).apply {
                action = FlowPacerService.ACTION_STOP
            }
            startService(stopIntent)
            setTileUi(false)
        } else {
            // Semak dahulu sama ada kebenaran VPN sudah diluluskan pengguna
            val vpnConsentIntent = VpnService.prepare(this)
            if (vpnConsentIntent != null) {
                // Kebenaran belum ada: buka MainActivity untuk paparkan dialog sistem
                val openAppIntent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                launchAppFromTile(openAppIntent)
                setTileUi(false)
                return
            }

            // Mulakan servis dengan selamat
            val startIntent = Intent(this, FlowPacerService::class.java).apply {
                action = FlowPacerService.ACTION_START
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(startIntent)
            } else {
                startService(startIntent)
            }
            setTileUi(true)
        }
    }

    private fun updateTileState() {
        val isRunning = FlowPacerService.isServiceRunning
        setTileUi(isRunning)
    }

    private fun setTileUi(isActive: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = if (isActive) getString(R.string.tile_state_active) else getString(R.string.tile_state_inactive)
        tile.updateTile()
    }

    @Suppress("DEPRECATION")
    private fun launchAppFromTile(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            startActivityAndCollapse(intent)
        }
    }
}
