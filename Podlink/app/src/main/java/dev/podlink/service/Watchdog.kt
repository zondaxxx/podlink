package dev.podlink.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import dev.podlink.data.Prefs
import dev.podlink.util.Permissions
import kotlinx.coroutines.runBlocking

/**
 * XOS kills background apps generously. An inexact 15-minute alarm re-checks that the service is alive
 * and restarts it (allowed from the background only when the app is exempt from battery optimisation,
 * which onboarding asks for; otherwise a tap-to-start notification is shown).
 */
object Watchdog {
    private const val TAG = "Watchdog"
    private const val REQ = 77

    private fun pending(context: Context) = PendingIntent.getBroadcast(
        context, REQ, Intent(context, WatchdogReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun schedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
            AlarmManager.INTERVAL_FIFTEEN_MINUTES, pending(context),
        )
    }

    fun cancel(context: Context) = context.getSystemService(AlarmManager::class.java).cancel(pending(context))

    fun batteryExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** Start the service if it should be running. Safe to call from any receiver. */
    fun ensureRunning(context: Context, fromBoot: Boolean) {
        if (PodsRepo.service != null) return
        val s = runBlocking { Prefs(context).current() }
        if (!s.serviceEnabled || !s.onboardingDone || !Permissions.bluetoothGranted(context)) return
        // Android 12+: FGS starts from the background are only allowed from BOOT/PACKAGE_REPLACED or with
        // the battery-optimisation exemption. Otherwise offer a tap-to-start notification.
        if (fromBoot || Build.VERSION.SDK_INT < 31 || batteryExempt(context)) {
            try { PodsService.start(context) } catch (t: Throwable) {
                Log.w(TAG, "start refused: $t"); Notifications.tapToStart(context)
            }
        } else Notifications.tapToStart(context)
    }
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Watchdog.ensureRunning(context, fromBoot = false)
}
