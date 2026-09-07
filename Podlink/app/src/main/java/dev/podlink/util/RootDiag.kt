package dev.podlink.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

/**
 * Groundwork for a future root mode, plus the answer to "will the L2CAP fix ever reach this phone?".
 *
 * The AirPods L2CAP bug (Google issue 371713238) lives in `l2c_fcr_chk_chan_modes` inside libbluetooth.
 * Google ships the fix inside the Bluetooth Mainline module (APEX `com.android.btservices`), which is
 * updated through Google Play system updates — but only on devices where that module is actually an
 * updatable APEX. When the stack is baked into the firmware, only a vendor OTA (or Android 17) can fix it.
 */
object RootDiag {

    private val LIB_DIRS = listOf(
        "/apex/com.android.btservices/lib64",
        "/apex/com.android.bluetooth/lib64",
        "/apex/com.google.android.btservices/lib64",
        "/system/lib64",
        "/system_ext/lib64",
        "/vendor/lib64",
        "/apex/com.android.btservices/lib",
        "/system/lib",
    )

    private val MODULE_PACKAGES = listOf(
        "com.android.btservices",
        "com.google.android.btservices",
        "com.android.bluetooth",
        "com.google.android.bluetooth",
    )

    fun suAvailable(): Boolean =
        listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/data/adb/magisk").any { File(it).exists() } ||
            runCatching { ProcessBuilder("sh", "-c", "command -v su").redirectErrorStream(true).start().inputStream.bufferedReader().readText().isNotBlank() }.getOrDefault(false)

    fun fingerprint(): String = "${Build.FINGERPRINT}\nSDK ${Build.VERSION.SDK_INT} · ${Build.MANUFACTURER} ${Build.MODEL} · ${Build.HARDWARE}"

    // ---- Bluetooth stack module -----------------------------------------------------------------

    data class ModuleInfo(
        val pkg: String,
        val versionName: String,
        val versionCode: Long,
        val sourceDir: String,
        val isApex: Boolean,
        val isUpdated: Boolean,
    ) {
        /** Play system updates bump the version code into the 3xxxxxxxx range; factory builds stay small. */
        val looksUpdatable: Boolean get() = isApex && (isUpdated || versionCode > 300_000_000L)
        val line: String get() = "$pkg $versionName ($versionCode)" +
            (if (isApex) " apex" else " built-in") + (if (isUpdated) " updated" else "")
    }

    fun modules(context: Context): List<ModuleInfo> {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 29) PackageManager.MATCH_APEX else 0
        return MODULE_PACKAGES.mapNotNull { name ->
            val info = runCatching { pm.getPackageInfo(name, flags) }.getOrNull() ?: return@mapNotNull null
            val app: ApplicationInfo? = info.applicationInfo
            val src = app?.sourceDir ?: ""
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            ModuleInfo(
                pkg = name,
                versionName = info.versionName ?: "?",
                versionCode = code,
                sourceDir = src,
                isApex = src.startsWith("/apex/") || (Build.VERSION.SDK_INT >= 29 && runCatching { info.isApex }.getOrDefault(false)),
                isUpdated = app != null && (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
            )
        }
    }

    enum class Verdict { UPDATABLE, BUILT_IN, UNKNOWN }

    /** Can this phone still receive the L2CAP fix through Google Play system updates? */
    fun verdict(context: Context): Verdict {
        val mods = modules(context)
        if (mods.isEmpty()) return Verdict.UNKNOWN
        return when {
            mods.any { it.looksUpdatable } -> Verdict.UPDATABLE
            mods.any { it.isApex } -> Verdict.UNKNOWN     // apex present but never updated yet
            else -> Verdict.BUILT_IN
        }
    }

    /** Directories under /apex that exist, useful to see how the ROM is put together. */
    fun apexDirs(): List<String> = runCatching {
        File("/apex").listFiles()?.filter { it.isDirectory }?.map { it.name }?.filter { it.contains("bt") || it.contains("bluetooth") } ?: emptyList()
    }.getOrDefault(emptyList())

    // ---- the stack library ------------------------------------------------------------------------

    data class LibInfo(val path: String, val size: Long, val readable: Boolean)

    /** Every libbluetooth*.so we can see, in every plausible directory. */
    fun libInfo(): List<LibInfo> {
        val out = LinkedHashMap<String, LibInfo>()
        for (dir in LIB_DIRS) {
            val d = File(dir)
            // Direct hits first: listFiles() returns null when the directory is not listable for apps.
            val direct = listOf("libbluetooth.so", "libbluetooth_jni.so", "libbluetooth_core.so", "libbluetooth-core.so")
            for (n in direct) {
                val f = File(d, n)
                if (f.exists()) out[f.path] = LibInfo(f.path, f.length(), f.canRead())
            }
            val listed = runCatching { d.listFiles { _, name -> name.startsWith("libbluetooth") && name.endsWith(".so") } }.getOrNull()
            listed?.forEach { f -> out[f.path] = LibInfo(f.path, f.length(), f.canRead()) }
        }
        return out.values.toList()
    }

    /** Copies the stack library to the app cache (directly, or through `su cat`) and returns a shareable URI. */
    fun collectLib(context: Context): Result<Pair<Uri, String>> = runCatching {
        val src = libInfo().sortedByDescending { it.size }.firstOrNull() ?: error("libbluetooth*.so not visible to apps on this ROM")
        val file = File(src.path)
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val dst = File(dir, file.name)
        if (src.readable) file.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
        else {
            val p = ProcessBuilder("su", "-c", "cat ${file.path}").start()
            p.inputStream.use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
            if (p.waitFor() != 0 || dst.length() == 0L) error("not readable and su is unavailable")
        }
        val sha = MessageDigest.getInstance("SHA-256").digest(dst.readBytes()).joinToString("") { "%02x".format(it) }
        FileProvider.getUriForFile(context, context.packageName + ".files", dst) to sha
    }

    fun shareLib(context: Context): String {
        val r = collectLib(context)
        val (uri, sha) = r.getOrElse { return it.message ?: it.toString() }
        val i = Intent(Intent.ACTION_SEND).setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "libbluetooth ${Build.MODEL}")
            .putExtra(Intent.EXTRA_TEXT, fingerprint() + "\nsha256 " + sha)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(i, "libbluetooth").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "ok"
    }

    /** Full text for the diagnostics share / clipboard. */
    fun report(context: Context): String = buildString {
        appendLine(fingerprint())
        appendLine("su: ${suAvailable()}")
        appendLine("verdict: ${verdict(context)}")
        modules(context).forEach { appendLine("module: ${it.line}  ${it.sourceDir}") }
        appendLine("apex dirs: ${apexDirs().joinToString(", ").ifEmpty { "none visible" }}")
        libInfo().forEach { appendLine("lib: ${it.path} ${it.size / 1024}KB readable=${it.readable}") }
        if (libInfo().isEmpty()) appendLine("lib: none visible to apps")
    }
}
