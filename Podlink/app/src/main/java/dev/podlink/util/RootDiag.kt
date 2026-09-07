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

    /** Fallbacks; the real directory is derived from the module's own APEX root at runtime. */
    private val LIB_DIRS = listOf(
        "/apex/com.android.btservices/lib64",
        "/apex/com.android.bt/lib64",
        "/apex/com.android.bluetooth/lib64",
        "/apex/com.google.android.btservices/lib64",
        "/system/lib64",
        "/system_ext/lib64",
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

    enum class Verdict { UPDATABLE, VENDOR_BUILT, BUILT_IN, UNKNOWN }

    /**
     * Can this phone still receive the L2CAP fix through Google Play system updates?
     *
     * Play only replaces modules that Google itself signed and shipped (`com.google.android.*`, or an
     * AOSP-named module enrolled in the Mainline train, whose version code is a nine-digit train number).
     * A module the vendor built from AOSP source carries the platform SDK level as its version code and
     * can only ever change with a full firmware OTA.
     */
    fun verdict(context: Context): Verdict {
        val mods = modules(context)
        if (mods.isEmpty()) return Verdict.UNKNOWN
        return when {
            mods.any { it.looksUpdatable } -> Verdict.UPDATABLE
            mods.any { it.isApex } -> Verdict.VENDOR_BUILT
            else -> Verdict.BUILT_IN
        }
    }

    /** How many Mainline modules on this phone have actually been updated by Google Play, out of how many. */
    fun mainlineStats(context: Context): Pair<Int, Int> {
        val pm = context.packageManager
        val flags = (if (Build.VERSION.SDK_INT >= 29) PackageManager.MATCH_APEX else 0) or PackageManager.MATCH_UNINSTALLED_PACKAGES
        val all = runCatching { pm.getInstalledPackages(flags) }.getOrNull() ?: return 0 to 0
        val apexes = all.filter { it.applicationInfo?.sourceDir?.startsWith("/apex/") == true || it.applicationInfo?.sourceDir?.startsWith("/data/apex/") == true }
        val updated = apexes.count { p ->
            val src = p.applicationInfo?.sourceDir ?: ""
            val code = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
            src.startsWith("/data/apex/") || code > 300_000_000L
        }
        return updated to apexes.size
    }

    /** The APEX root a module is mounted at, e.g. /apex/com.android.bt — /apex itself is not listable by apps. */
    private fun apexRoot(sourceDir: String): String? {
        if (!sourceDir.startsWith("/apex/")) return null
        val parts = sourceDir.split("/")
        return if (parts.size > 2) "/apex/" + parts[2] else null
    }

    fun apexDirs(context: Context): List<String> = modules(context).mapNotNull { apexRoot(it.sourceDir) }.distinct()

    // ---- the stack library ------------------------------------------------------------------------

    data class LibInfo(val path: String, val size: Long, val readable: Boolean)

    /** Every libbluetooth*.so we can see, starting with the directory of the actual Bluetooth module. */
    fun libInfo(context: Context? = null): List<LibInfo> {
        val out = LinkedHashMap<String, LibInfo>()
        val fromModule = context?.let { c -> modules(c).mapNotNull { apexRoot(it.sourceDir) }.flatMap { listOf("$it/lib64", "$it/lib") } } ?: emptyList()
        for (dir in fromModule + LIB_DIRS) {
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
        // MediaTek ships a pile of unrelated libbluetooth_* helpers in /vendor; the stack itself is the big one.
        return out.values.sortedWith(compareByDescending<LibInfo> { it.path.startsWith("/apex/") }.thenByDescending { it.size })
    }

    /** Looks inside the ROM's Bluetooth stack for the function that breaks AirPods L2CAP. */
    fun inspectStack(context: Context): ElfScan.Result? {
        val lib = libInfo(context).firstOrNull { it.readable && it.size > 1_000_000 } ?: libInfo(context).firstOrNull() ?: return null
        return ElfScan.inspect(lib.path)
    }

    /** Copies the stack library to the app cache (directly, or through `su cat`) and returns a shareable URI. */
    fun collectLib(context: Context): Result<Pair<Uri, String>> = runCatching {
        val src = libInfo(context).firstOrNull { it.readable || suAvailable() } ?: error("libbluetooth*.so not visible to apps on this ROM")
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
        val (upd, total) = mainlineStats(context)
        appendLine("mainline: $upd of $total modules updated by Play")
        modules(context).forEach { appendLine("module: ${it.line}  ${it.sourceDir}") }
        appendLine("apex roots: ${apexDirs(context).joinToString(", ").ifEmpty { "none" }}")
        val libs = libInfo(context)
        libs.forEach { appendLine("lib: ${it.path} ${it.size / 1024}KB readable=${it.readable}") }
        if (libs.isEmpty()) appendLine("lib: none visible to apps")
        inspectStack(context)?.let { r ->
            appendLine("stack: ${r.verdict}${r.error?.let { e -> " ($e)" } ?: ""}")
            r.symbol?.let { sym -> appendLine("sym: ${sym.name} vaddr=0x%x size=%d off=0x%x".format(sym.vaddr, sym.size, sym.fileOffset)) }
            if (r.hex.isNotEmpty()) appendLine("code: ${r.hex}")
        }
    }
}
