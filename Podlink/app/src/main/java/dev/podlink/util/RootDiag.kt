package dev.podlink.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

/**
 * Groundwork for a future root mode: tells whether `su` exists and lets the user share the ROM's
 * Bluetooth stack library so a PSM-0x1001 patch can be prepared for exactly this firmware.
 */
object RootDiag {
    private val LIB_PATHS = listOf(
        "/apex/com.android.btservices/lib64/libbluetooth.so",
        "/apex/com.android.bluetooth/lib64/libbluetooth.so",
        "/system/lib64/libbluetooth.so",
        "/apex/com.android.btservices/lib64/libbluetooth_jni.so",
    )

    fun suAvailable(): Boolean =
        listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/data/adb/magisk").any { File(it).exists() } ||
            runCatching { ProcessBuilder("sh", "-c", "command -v su").redirectErrorStream(true).start().inputStream.bufferedReader().readText().isNotBlank() }.getOrDefault(false)

    fun fingerprint(): String = "${Build.FINGERPRINT}\nSDK ${Build.VERSION.SDK_INT} · ${Build.MANUFACTURER} ${Build.MODEL} · ${Build.HARDWARE}"

    data class LibInfo(val path: String, val size: Long, val readable: Boolean)

    fun libInfo(): List<LibInfo> = LIB_PATHS.map { File(it) }.filter { it.exists() }.map { LibInfo(it.path, it.length(), it.canRead()) }

    /** Copies the stack library to the app cache (directly, or through `su cat` when not readable) and returns a shareable URI. */
    fun collectLib(context: Context): Result<Pair<Uri, String>> = runCatching {
        val src = LIB_PATHS.map { File(it) }.firstOrNull { it.exists() } ?: error("libbluetooth.so not found")
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val dst = File(dir, src.name)
        if (src.canRead()) src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
        else {
            val p = ProcessBuilder("su", "-c", "cat ${src.path}").redirectErrorStream(false).start()
            p.inputStream.use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
            if (p.waitFor() != 0 || dst.length() == 0L) error("su cat failed")
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(dst.readBytes()).joinToString("") { "%02x".format(it) }
        FileProvider.getUriForFile(context, context.packageName + ".files", dst) to sha
    }

    fun shareLib(context: Context): String {
        val r = collectLib(context)
        val (uri, sha) = r.getOrElse { return it.message ?: it.toString() }
        val i = Intent(Intent.ACTION_SEND).setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "libbluetooth.so ${Build.MODEL}")
            .putExtra(Intent.EXTRA_TEXT, fingerprint() + "\nsha256 " + sha)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(i, "libbluetooth.so").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "ok"
    }
}
