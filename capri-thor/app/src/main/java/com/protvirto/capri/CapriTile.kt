package com.protvirto.capri

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings: one tap turns Capri on or off. */
class CapriTile : TileService() {
    override fun onStartListening() {
        refresh()
    }

    override fun onClick() {
        if (CapriService.running) CapriService.stop(this) else CapriService.start(this)
        qsTile?.let {
            it.state = if (CapriService.running) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            it.updateTile()
        }
    }

    private fun refresh() {
        qsTile?.let {
            it.state = if (CapriService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            it.subtitle = if (CapriService.running) "Online" else "Off"
            it.updateTile()
        }
    }
}
