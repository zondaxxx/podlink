package dev.podlink.service

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.podlink.R

/** Second Quick Settings tile: one tap connects the AirPods to this phone. */
class ConnectTileService : TileService() {
    override fun onStartListening() {
        val t = qsTile ?: return
        val s = PodsRepo.state.value
        t.label = getString(R.string.tile_connect)
        t.state = if (s.connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.subtitle = if (s.connected) getString(R.string.status_connected) else getString(R.string.tile_connect_hint)
        t.updateTile()
    }

    override fun onClick() {
        PodsService.start(this)
        startService(Intent(this, PodsService::class.java).setAction(PodsService.ACTION_CONNECT_NOW))
        qsTile?.let { it.subtitle = getString(R.string.ev_connecting_short); it.updateTile() }
    }
}
