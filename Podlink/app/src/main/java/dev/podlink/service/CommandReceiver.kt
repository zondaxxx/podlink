package dev.podlink.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.podlink.aap.AapProtocol

/**
 * Public command API for automation apps:
 *   am broadcast -a dev.podlink.action.SET_ANC --es mode anc|transparency|adaptive|off
 *   am broadcast -a dev.podlink.action.CONNECT
 *   am broadcast -a dev.podlink.action.REFRESH
 */
class CommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "dev.podlink.action.SET_ANC" -> {
                val mode = when (intent.getStringExtra("mode")?.lowercase()) {
                    "anc", "on" -> AapProtocol.NoiseMode.ANC
                    "transparency" -> AapProtocol.NoiseMode.TRANSPARENCY
                    "adaptive" -> AapProtocol.NoiseMode.ADAPTIVE
                    else -> AapProtocol.NoiseMode.OFF
                }
                PodsRepo.service?.aap?.setNoiseMode(mode)
            }
            "dev.podlink.action.CONNECT" -> context.startService(Intent(context, PodsService::class.java).setAction(PodsService.ACTION_CONNECT_NOW))
            "dev.podlink.action.REFRESH" -> PodsService.start(context)
            "dev.podlink.action.SHOW_POPUP" -> context.startService(Intent(context, PodsService::class.java).setAction(PodsService.ACTION_SHOW_POPUP))
        }
    }
}
