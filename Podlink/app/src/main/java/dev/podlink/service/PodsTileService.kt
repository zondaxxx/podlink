package dev.podlink.service

import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.podlink.MainActivity
import dev.podlink.R
import dev.podlink.aap.AapClient
import dev.podlink.aap.AapProtocol

/** Quick Settings tile: battery at a glance (icon shows the lowest pod %); tap cycles ANC when AAP is up, else opens the app. */
class PodsTileService : TileService() {

    override fun onStartListening() { PodsRepo.restore(this); render() }

    private fun render() {
        val t = qsTile ?: return
        val s = PodsRepo.state.value
        t.icon = if (s.connected && s.lowest != null) StatusIcon.render(this, s.lowest, s.leftCharging && s.rightCharging) else Icon.createWithResource(this, R.drawable.ic_pods)
        when {
            !s.connected -> { t.state = Tile.STATE_INACTIVE; t.label = getString(R.string.app_name); t.subtitle = getString(R.string.tile_not_connected) }
            s.isHeadphones -> { t.state = Tile.STATE_ACTIVE; t.label = s.deviceName ?: s.model.label; t.subtitle = "${s.single ?: "–"}%" }
            else -> {
                t.state = Tile.STATE_ACTIVE
                t.label = s.deviceName ?: s.model.label
                val anc = s.noiseMode?.let { " · " + ancLabel(it) } ?: ""
                t.subtitle = "L${s.left ?: "–"} R${s.right ?: "–"} C${s.case ?: "–"}$anc"
            }
        }
        t.updateTile()
    }

    private fun ancLabel(m: AapProtocol.NoiseMode) = when (m) {
        AapProtocol.NoiseMode.OFF -> getString(R.string.anc_off)
        AapProtocol.NoiseMode.ANC -> getString(R.string.anc_on)
        AapProtocol.NoiseMode.TRANSPARENCY -> getString(R.string.anc_transparency)
        AapProtocol.NoiseMode.ADAPTIVE -> getString(R.string.anc_adaptive)
    }

    override fun onClick() {
        val svc = PodsRepo.service
        val s = PodsRepo.state.value
        if (svc != null && s.aapState == AapClient.State.CONNECTED && s.noiseMode != null) {
            val modes = if (s.model.supportsAdaptive) AapProtocol.NoiseMode.entries else AapProtocol.NoiseMode.entries.filter { it != AapProtocol.NoiseMode.ADAPTIVE }
            val next = modes[(modes.indexOf(s.noiseMode) + 1) % modes.size]
            svc.aap.setNoiseMode(next)
        } else {
            val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            @Suppress("DEPRECATION")
            if (android.os.Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(android.app.PendingIntent.getActivity(this, 0, i, android.app.PendingIntent.FLAG_IMMUTABLE))
            else startActivityAndCollapse(i)
        }
    }
}
