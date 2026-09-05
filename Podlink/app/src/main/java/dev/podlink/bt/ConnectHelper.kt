package dev.podlink.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.util.Log
import dev.podlink.util.HiddenApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Initiates a Bluetooth Classic connection to a bonded headset **without root**.
 *
 * Strategy (each step is tried in turn):
 *  1. Hidden `BluetoothA2dp.connect(device)` / `BluetoothHeadset.connect(device)` through HiddenApiBypass.
 *     Works on many OEM ROMs; strict builds throw SecurityException (BLUETOOTH_PRIVILEGED) — we remember
 *     that and stop trying, like CAPod does.
 *  2. "ACL nudge": open an RFCOMM socket to the Hands-Free SDP record. Even when the socket itself fails,
 *     the stack has to bring up the ACL link, and Android's profile auto-connect policy then
 *     connects A2DP/HFP by itself.
 */
@SuppressLint("MissingPermission")
class ConnectHelper(
    private val monitor: ClassicMonitor,
    private val hiddenBroken: () -> Boolean = { false },
    private val markHiddenBroken: () -> Unit = {},
) {

    companion object {
        private const val TAG = "ConnectHelper"
        private val HFP_UUID: UUID = UUID.fromString("0000111E-0000-1000-8000-00805F9B34FB")
        private val A2DP_SINK_UUID: UUID = UUID.fromString("0000110B-0000-1000-8000-00805F9B34FB")
    }

    sealed class Result {
        data class Ok(val via: String) : Result()
        data class Failed(val log: List<String>) : Result()
    }

    suspend fun connect(device: BluetoothDevice): Result = withContext(Dispatchers.IO) {
        val log = ArrayList<String>()
        val (a2dp, hfp) = monitor.profiles()

        // 1. hidden profile connect()
        if (hiddenBroken()) log += "hidden connect() known to be blocked on this ROM, skipped"
        else for ((proxy, name) in listOf(a2dp to "A2DP", hfp to "HFP")) {
            if (proxy == null) { log += "$name proxy not bound"; continue }
            val r = HiddenApi.invoke(proxy, "connect", arrayOf(BluetoothDevice::class.java), arrayOf(device))
            val err = r.exceptionOrNull()?.let { (it as? java.lang.reflect.InvocationTargetException)?.targetException ?: it }
            log += "$name.connect() -> ${r.fold({ it.toString() }, { err?.javaClass?.simpleName + ": " + err?.message })}"
            if (err is SecurityException) { markHiddenBroken(); log += "SecurityException → hidden connect() disabled"; break }
            if (r.getOrNull() == true && waitConnected(device, 6_000)) return@withContext Result.Ok("$name.connect()")
        }

        // 2. ACL nudge via RFCOMM
        for ((uuid, name) in listOf(HFP_UUID to "HFP", A2DP_SINK_UUID to "A2DP")) {
            val sock = runCatching { device.createInsecureRfcommSocketToServiceRecord(uuid) }
            if (sock.isFailure) { log += "rfcomm($name) create failed: ${sock.exceptionOrNull()?.message}"; continue }
            val s = sock.getOrThrow()
            val r = runCatching { s.connect() }
            log += "rfcomm($name) connect -> ${if (r.isSuccess) "open" else r.exceptionOrNull()?.message}"
            runCatching { s.close() }
            if (waitConnected(device, 7_000)) return@withContext Result.Ok("ACL nudge ($name)")
        }
        Log.w(TAG, "connect failed: $log")
        Result.Failed(log)
    }

    private suspend fun waitConnected(device: BluetoothDevice, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                monitor.refresh()
                val (a2dp, hfp) = monitor.profiles()
                val st = listOfNotNull(
                    runCatching { a2dp?.getConnectionState(device) }.getOrNull(),
                    runCatching { hfp?.getConnectionState(device) }.getOrNull(),
                )
                if (st.any { it == BluetoothProfile.STATE_CONNECTED }) return@withTimeoutOrNull true
                delay(400)
            }
            @Suppress("UNREACHABLE_CODE") false
        } ?: false
}
