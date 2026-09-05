package dev.podlink.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import dev.podlink.MainActivity
import dev.podlink.R
import dev.podlink.data.Settings

object Notifications {
    const val CH_CONNECTED = "connected"
    const val CH_IDLE = "idle"
    const val CH_IDLE_QUIET = "idle_quiet"
    const val CH_ALERTS = "alerts"
    const val ID_STATUS = 1
    const val ID_TAP_TO_START = 2
    const val ID_ALERT_BASE = 100

    /** Notification.EXTRA_REQUEST_PROMOTED_ONGOING is an SDK 36.1 symbol; the string key works on 36.0 too. */
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_CONNECTED, context.getString(R.string.ch_connected), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false); setSound(null, null); description = context.getString(R.string.ch_connected_desc)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_IDLE, context.getString(R.string.ch_idle), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false); setSound(null, null); description = context.getString(R.string.ch_idle_desc)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_IDLE_QUIET, context.getString(R.string.ch_idle_quiet), NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false); setSound(null, null)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, context.getString(R.string.ch_alerts), NotificationManager.IMPORTANCE_HIGH),
        )
        // legacy channel from 0.1–0.3
        runCatching { nm.deleteNotificationChannel("status") }
    }

    private fun openApp(context: Context, find: Boolean = false): PendingIntent = PendingIntent.getActivity(
        context, if (find) 1 else 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("find", find),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun serviceAction(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getService(
        context, code, Intent(context, PodsService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun pct(v: Int?, charging: Boolean): String = if (v == null) "–" else "$v%${if (charging) "⚡" else ""}"

    private fun minutes(context: Context, m: Int?): String? = m?.let {
        if (it >= 60) context.getString(R.string.hours_min, it / 60, it % 60) else context.getString(R.string.minutes, it)
    }

    fun status(context: Context, s: PodsState, settings: Settings): Notification {
        val name = s.deviceName ?: s.model.label
        val title: String
        val text: String
        val lines = ArrayList<String>()
        when {
            !s.connected && s.nearbyNotConnected -> {
                title = context.getString(R.string.notif_nearby_title, name)
                text = context.getString(R.string.notif_nearby_text)
            }
            !s.connected -> {
                title = context.getString(R.string.notif_waiting)
                text = if (s.hasBattery && settings.keepAfterDisconnect)
                    context.getString(R.string.notif_last_seen, pct(s.left, false), pct(s.right, false), pct(s.case, false))
                else context.getString(R.string.notif_waiting_text)
            }
            s.isHeadphones -> {
                title = name
                text = context.getString(R.string.notif_single, pct(s.single, s.leftCharging))
                minutes(context, s.leftMinutes)?.let { lines += context.getString(R.string.time_left, it) }
            }
            else -> {
                title = name
                text = context.getString(R.string.notif_lrc, pct(s.left, s.leftCharging), pct(s.right, s.rightCharging), pct(s.case, s.caseCharging))
                minutes(context, s.leftMinutes)?.let { lines += context.getString(R.string.left) + ": " + context.getString(R.string.time_left, it) }
                minutes(context, s.rightMinutes)?.let { lines += context.getString(R.string.right) + ": " + context.getString(R.string.time_left, it) }
                if (s.lidOpen) lines += context.getString(R.string.lid_open)
                lines += context.getString(R.string.mic_side, context.getString(if (s.leftIsMicrophone) R.string.left else R.string.right))
            }
        }
        val channel = when {
            s.connected -> CH_CONNECTED
            settings.idleNotification -> CH_IDLE
            else -> CH_IDLE_QUIET
        }
        val iconValue = when (settings.statusBarSource) {
            "left" -> s.left; "right" -> s.right; "case" -> s.case
            else -> s.lowest
        }
        val iconCharging = when (settings.statusBarSource) {
            "left" -> s.leftCharging; "right" -> s.rightCharging; "case" -> s.caseCharging
            else -> s.leftCharging && s.rightCharging || (s.isHeadphones && s.leftCharging)
        }
        val b = Notification.Builder(context, channel)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openApp(context))
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setColor(0xFF3DDCC4.toInt())
        if (settings.statusBarPercent && s.connected && iconValue != null) b.setSmallIcon(StatusIcon.render(context, iconValue, iconCharging))
        else b.setSmallIcon(R.drawable.ic_pods)
        if (lines.isNotEmpty()) b.setStyle(Notification.BigTextStyle().bigText((listOf(text) + lines).joinToString("\n")))
        if (settings.notificationActions) {
            if (!s.connected) b.addAction(Notification.Action.Builder(null, context.getString(R.string.btn_connect_short), serviceAction(context, PodsService.ACTION_CONNECT_NOW, 10)).build())
            b.addAction(Notification.Action.Builder(null, context.getString(R.string.radar_title), openApp(context, find = true)).build())
        }
        if (Build.VERSION.SDK_INT >= 34) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        // Android 16 "Live Updates": status-bar chip with the lowest pod level. Never colorized (36.1 rejects it).
        if (settings.statusBarChip && Build.VERSION.SDK_INT >= 36 && s.connected && s.lowest != null) {
            runCatching {
                Notification.Builder::class.java.getMethod("setShortCriticalText", String::class.java).invoke(b, "${s.lowest}%")
            }
            b.addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
        }
        return b.build()
    }

    private var lastSignature: String? = null
    private var lastPosted = 0L

    /**
     * Posts the status notification only when its visible content changed (or once a minute), because
     * NotificationManager rate-limits apps to ~5 posts/s and every post re-lays-out the status bar.
     * Returns true when a post happened.
     */
    fun notifyStatus(context: Context, s: PodsState, settings: Settings): Boolean {
        val sig = listOf(
            s.connected, s.nearbyNotConnected, s.deviceName, s.left, s.right, s.case, s.leftCharging, s.rightCharging, s.caseCharging,
            s.lidOpen, s.leftIsMicrophone, s.leftMinutes, s.rightMinutes, s.aapState, s.noiseMode,
            settings.statusBarPercent, settings.statusBarSource, settings.statusBarChip, settings.notificationActions, settings.keepAfterDisconnect, settings.idleNotification,
        ).joinToString("|")
        val now = System.currentTimeMillis()
        if (sig == lastSignature && now - lastPosted < 60_000) return false
        lastSignature = sig; lastPosted = now
        context.getSystemService(NotificationManager::class.java).notify(ID_STATUS, status(context, s, settings))
        return true
    }

    fun alert(context: Context, id: Int, title: String, text: String) {
        val n = Notification.Builder(context, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_pods)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setColor(0xFF3DDCC4.toInt())
            .setContentIntent(openApp(context))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_ALERT_BASE + id, n)
    }

    /** Shown when a receiver may not start the foreground service from the background (Android 12+ rules). */
    fun tapToStart(context: Context) {
        val pi = PendingIntent.getForegroundService(
            context, 20, Intent(context, PodsService::class.java).setAction(PodsService.ACTION_START),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_pods)
            .setContentTitle(context.getString(R.string.notif_tap_start_title))
            .setContentText(context.getString(R.string.notif_tap_start_text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_TAP_TO_START, n)
    }

    fun cancelTapToStart(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(ID_TAP_TO_START)
}
