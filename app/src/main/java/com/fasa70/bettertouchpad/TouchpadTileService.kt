package com.fasa70.bettertouchpad

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

private const val TAG = "TouchpadTileService"

class TouchpadTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        syncTileState()
    }

    override fun onClick() {
        super.onClick()
        if (TouchpadService.isRunning) {
            stopTouchpad()
        } else {
            startTouchpad()
        }
        syncTileState()
    }

    private fun startTouchpad() {
        Log.i(TAG, "Starting TouchpadService")
        val intent = Intent(applicationContext, TouchpadService::class.java)
        startForegroundService(intent)
    }

    private fun stopTouchpad() {
        Log.i(TAG, "Stopping TouchpadService")
        val intent = Intent(applicationContext, TouchpadService::class.java)
        stopService(intent)
    }

    private fun syncTileState() {
        val tile = qsTile ?: return
        if (TouchpadService.isRunning) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "触控板 开"
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "触控板 关"
        }
        tile.updateTile()
    }
}

