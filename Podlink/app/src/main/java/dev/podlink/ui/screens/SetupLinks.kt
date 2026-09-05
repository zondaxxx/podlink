package dev.podlink.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Deep links into OEM (Transsion / XOS / HiOS) and stock settings pages. Every candidate is resolved
 * first and the first one that exists is opened, so nothing crashes on other ROMs.
 */
object SetupLinks {
    private fun open(ctx: Context, intents: List<Intent>): Boolean {
        for (i in intents) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = runCatching { ctx.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY) != null }.getOrDefault(false)
            if (!ok) continue
            if (runCatching { ctx.startActivity(i); true }.getOrDefault(false)) return true
        }
        return false
    }

    private fun appDetails(ctx: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))

    /** Transsion Phone Master "Auto-start management" — component names seen in shipping open-source apps. */
    fun openAutostart(ctx: Context) = open(ctx, listOf(
        Intent().setClassName("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"),
        Intent().setClassName("com.transsion.phonemanager", "com.itel.autobootmanager.activity.AutoBootMgrActivity"),
        Intent().setClassName("com.transsion.phonemaster", "com.cyin.himgr.MainSettingActivity"),
        Intent().setClassName("com.transsion.phonemaster", "com.cyin.himgr.MainActivity"),
        appDetails(ctx),
    ))

    fun openAppInfo(ctx: Context) = open(ctx, listOf(appDetails(ctx)))

    fun openLiveUpdates(ctx: Context) = open(ctx, buildList {
        if (Build.VERSION.SDK_INT >= 36) add(Intent("android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS").putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        add(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
    })

    fun openNotificationChannels(ctx: Context) = open(ctx, listOf(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
    ))

    fun openOverlay(ctx: Context) = open(ctx, listOf(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))))

    fun openBatteryOpt(ctx: Context) = open(ctx, listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    ))

    /** Transsion "Battery Lab / Power Marathon" where screen-off push blocking lives. */
    fun openBatteryLab(ctx: Context) = open(ctx, listOf(
        ctx.packageManager.getLaunchIntentForPackage("com.transsion.batterylab") ?: Intent(),
        Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    ))

    fun openBluetoothSettings(ctx: Context) = open(ctx, listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
}
