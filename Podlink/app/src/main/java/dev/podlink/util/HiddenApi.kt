package dev.podlink.util

import android.os.Build
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

/** Thin wrapper around LSPosed's HiddenApiBypass with graceful degradation. */
object HiddenApi {
    private const val TAG = "HiddenApi"
    @Volatile private var unlocked = false

    fun unlock(): Boolean {
        if (unlocked) return true
        if (Build.VERSION.SDK_INT < 28) { unlocked = true; return true }
        unlocked = runCatching { HiddenApiBypass.addHiddenApiExemptions("") }.getOrElse {
            Log.w(TAG, "bypass failed", it); false
        }
        return unlocked
    }

    fun invoke(target: Any, method: String, types: Array<Class<*>>, args: Array<Any?>): kotlin.Result<Any?> = runCatching {
        unlock()
        val m = target.javaClass.getMethod(method, *types)
        m.isAccessible = true
        m.invoke(target, *args)
    }
}
