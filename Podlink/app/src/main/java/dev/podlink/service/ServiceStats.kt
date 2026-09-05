package dev.podlink.service

import android.content.Context

/** Counts service starts and unclean deaths (XOS killing the process) for the reliability card. */
object ServiceStats {
    private const val P = "podlink_stats"

    data class Snapshot(val starts: Int, val killed: Int, val lastStart: Long)

    fun onServiceCreate(context: Context) {
        val p = context.getSharedPreferences(P, Context.MODE_PRIVATE)
        val wasRunning = p.getBoolean("running", false)
        p.edit()
            .putInt("starts", p.getInt("starts", 0) + 1)
            .putInt("killed", p.getInt("killed", 0) + if (wasRunning) 1 else 0)
            .putLong("lastStart", System.currentTimeMillis())
            .putBoolean("running", true)
            .apply()
    }

    fun onServiceDestroy(context: Context) {
        context.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putBoolean("running", false).apply()
    }

    fun snapshot(context: Context): Snapshot {
        val p = context.getSharedPreferences(P, Context.MODE_PRIVATE)
        return Snapshot(p.getInt("starts", 0), p.getInt("killed", 0), p.getLong("lastStart", 0))
    }

    fun reset(context: Context) = context.getSharedPreferences(P, Context.MODE_PRIVATE).edit().clear().apply()
}
