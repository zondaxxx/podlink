package dev.podlink.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Sample(
    val ts: Long,
    val left: Int?, val right: Int?, val case: Int?,
    val leftCharging: Boolean, val rightCharging: Boolean, val caseCharging: Boolean,
    val leftInEar: Boolean, val rightInEar: Boolean,
)

data class Estimate(val minutesLeft: Int?, val percentPerHour: Double?)

/** Battery history in plain SQLite: small, dependency-free, plenty for a few weeks of samples. */
class HistoryDb(context: Context) : SQLiteOpenHelper(context, "history.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE samples(
                ts INTEGER PRIMARY KEY, l INTEGER, r INTEGER, c INTEGER,
                lc INTEGER NOT NULL, rc INTEGER NOT NULL, cc INTEGER NOT NULL,
                li INTEGER NOT NULL, ri INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE TABLE events(ts INTEGER, kind TEXT NOT NULL, detail TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private var lastInsert: Sample? = null

    /** Store a sample only when something actually changed or 5 minutes have elapsed. */
    suspend fun record(s: Sample) = withContext(Dispatchers.IO) {
        val prev = lastInsert
        val same = prev != null && prev.copy(ts = s.ts) == s
        if (same && s.ts - prev!!.ts < 5 * 60_000) return@withContext
        writableDatabase.insertWithOnConflict("samples", null, ContentValues().apply {
            put("ts", s.ts); put("l", s.left); put("r", s.right); put("c", s.case)
            put("lc", if (s.leftCharging) 1 else 0); put("rc", if (s.rightCharging) 1 else 0); put("cc", if (s.caseCharging) 1 else 0)
            put("li", if (s.leftInEar) 1 else 0); put("ri", if (s.rightInEar) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        lastInsert = s
        writableDatabase.delete("samples", "ts < ?", arrayOf((s.ts - 30L * 24 * 3600_000).toString()))
    }

    suspend fun event(kind: String, detail: String? = null) = withContext(Dispatchers.IO) {
        writableDatabase.insert("events", null, ContentValues().apply {
            put("ts", System.currentTimeMillis()); put("kind", kind); put("detail", detail)
        })
        writableDatabase.delete("events", "ts < ?", arrayOf((System.currentTimeMillis() - 30L * 24 * 3600_000).toString()))
    }

    suspend fun samples(sinceMs: Long): List<Sample> = withContext(Dispatchers.IO) {
        val out = ArrayList<Sample>()
        readableDatabase.rawQuery("SELECT ts,l,r,c,lc,rc,cc,li,ri FROM samples WHERE ts >= ? ORDER BY ts", arrayOf(sinceMs.toString())).use { cur ->
            while (cur.moveToNext()) {
                fun n(i: Int): Int? = if (cur.isNull(i)) null else cur.getInt(i)
                out += Sample(cur.getLong(0), n(1), n(2), n(3), cur.getInt(4) == 1, cur.getInt(5) == 1, cur.getInt(6) == 1, cur.getInt(7) == 1, cur.getInt(8) == 1)
            }
        }
        out
    }

    data class Ev(val ts: Long, val kind: String, val detail: String?)

    suspend fun events(limit: Int = 200): List<Ev> = withContext(Dispatchers.IO) {
        val out = ArrayList<Ev>()
        readableDatabase.rawQuery("SELECT ts,kind,detail FROM events ORDER BY ts DESC LIMIT $limit", null).use { cur ->
            while (cur.moveToNext()) out += Ev(cur.getLong(0), cur.getString(1), cur.getString(2))
        }
        out
    }

    /**
     * Estimate remaining minutes for one component from the discharge slope of the current session
     * (samples since the last charging period). Least squares over percent vs time.
     */
    suspend fun estimate(component: Char, now: Long = System.currentTimeMillis()): Estimate = withContext(Dispatchers.IO) {
        val col = when (component) { 'L' -> "l"; 'R' -> "r"; else -> "c" }
        val chg = when (component) { 'L' -> "lc"; 'R' -> "rc"; else -> "cc" }
        val pts = ArrayList<Pair<Long, Int>>()
        readableDatabase.rawQuery(
            "SELECT ts,$col,$chg FROM samples WHERE ts >= ? AND $col IS NOT NULL ORDER BY ts DESC",
            arrayOf((now - 12 * 3600_000L).toString()),
        ).use { cur ->
            while (cur.moveToNext()) {
                if (cur.getInt(2) == 1) break           // charging → session boundary
                pts += cur.getLong(0) to cur.getInt(1)
            }
        }
        if (pts.size < 3) return@withContext Estimate(null, null)
        val span = pts.first().first - pts.last().first
        if (span < 10 * 60_000L) return@withContext Estimate(null, null)
        val n = pts.size.toDouble()
        val mx = pts.sumOf { it.first.toDouble() } / n
        val my = pts.sumOf { it.second.toDouble() } / n
        var sxy = 0.0; var sxx = 0.0
        for ((x, y) in pts) { sxy += (x - mx) * (y - my); sxx += (x - mx) * (x - mx) }
        if (sxx == 0.0) return@withContext Estimate(null, null)
        val slopePerMs = sxy / sxx                    // percent per ms (negative when discharging)
        val perHour = slopePerMs * 3600_000
        if (slopePerMs >= -1e-9) return@withContext Estimate(null, perHour)
        val current = pts.first().second
        Estimate(((current / -slopePerMs) / 60_000).toInt(), perHour)
    }
}
