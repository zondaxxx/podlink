package dev.podlink.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the service back after boot / update (always allowed) and on Bluetooth activity (allowed only
 * with the battery-optimisation exemption; otherwise a tap-to-start notification is posted).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val fromBoot = intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        Watchdog.ensureRunning(context, fromBoot)
        if (fromBoot) Watchdog.schedule(context)
    }
}
