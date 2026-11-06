package com.muz.flowpacer.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class FlowPacerTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val isRunning = FlowPacerService.isServiceRunning

        val intent = Intent(this, FlowPacerService::class.java).apply {
            action = if (isRunning) FlowPacerService.ACTION_STOP else FlowPacerService.ACTION_START
        }

        if (!isRunning) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } else {
            startService(intent)
        }

        qsTile?.state = if (!isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile?.updateTile()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isRunning = FlowPacerService.isServiceRunning
        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = if (isRunning) "Aktif" else "Mati"
        tile.updateTile()
    }
}
