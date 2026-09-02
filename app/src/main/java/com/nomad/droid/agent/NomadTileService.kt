package com.nomad.droid.agent

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.nomad.droid.R

class NomadTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val store = AgentConfigStore(this)
        if (store.desiredRunning) {
            // Stop agent
            store.desiredRunning = false
            startService(AgentForegroundService.stopIntent(this))
            store.runtimeStatus = "Stopping"
        } else {
            // Start agent
            val config = store.load()
            if (config.validate().isSuccess) {
                store.desiredRunning = true
                startForegroundService(AgentForegroundService.startIntent(this))
                store.runtimeStatus = "Starting"
            }
        }
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val store = AgentConfigStore(this)
        val isRunning = store.desiredRunning && (store.runtimeStatus == "Running" || store.runtimeStatus == "Starting")

        tile.state = if (isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.subtitle = if (isRunning) store.runtimeStatus else getString(R.string.tile_stopped)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_nomad_droid)
        tile.updateTile()
    }

    companion object {
        fun requestTileUpdate(context: Context) {
            runCatching {
                requestListeningState(
                    context,
                    ComponentName(context, NomadTileService::class.java),
                )
            }
        }
    }
}
