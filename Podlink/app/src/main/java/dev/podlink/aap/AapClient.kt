package dev.podlink.aap

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.ParcelUuid
import android.util.Log
import dev.podlink.aap.AapProtocol.toHex
import dev.podlink.util.HiddenApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Constructor

/**
 * Experimental AAP link over a Bluetooth Classic L2CAP channel (PSM 0x1001) **without root**.
 *
 * Android has no public API for classic L2CAP client sockets, so we build the framework's
 * `BluetoothSocket` with its hidden constructor (`TYPE_L2CAP = 3`) via HiddenApiBypass. Whether the
 * Bluetooth stack of a given ROM accepts the connection differs between devices; every step is
 * logged so the user can see exactly where (and if) it fails.
 */
@SuppressLint("MissingPermission")
class AapClient(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "AapClient"
        private const val TYPE_L2CAP = 3
        private const val CONNECT_TIMEOUT_MS = 12_000L
    }

    enum class State { DISCONNECTED, CONNECTING, CONNECTED, UNSUPPORTED }

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<AapProtocol.Event>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    /** Human-readable diagnostics for the "Lab" screen. */
    val log = _log.asStateFlow()

    private val _noiseMode = MutableStateFlow<AapProtocol.NoiseMode?>(null)
    val noiseMode = _noiseMode.asStateFlow()

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var readJob: Job? = null
    @Volatile private var framesIn = 0

    private fun log(msg: String) {
        Log.i(TAG, msg)
        _log.value = (_log.value + "${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}  $msg").takeLast(200)
    }

    fun clearLog() { _log.value = emptyList() }

    /** One way of building the hidden socket; the connect matrix tries each with and without link security. */
    private data class Variant(val ctor: Constructor<*>, val secure: Boolean) {
        val label get() = "ctor(${ctor.parameterTypes.joinToString { it.simpleName }}) ${if (secure) "secure" else "insecure"}"
    }

    suspend fun connect(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        if (_state.value == State.CONNECTED || _state.value == State.CONNECTING) return@withContext true
        _state.value = State.CONNECTING
        log("Connecting AAP to ${device.name} (${device.address}) PSM 0x1001…")
        if (!HiddenApi.unlock()) log("! hidden API bypass unavailable, trying anyway")
        val ctors = BluetoothSocket::class.java.declaredConstructors
            .filter { it.parameterTypes.contains(BluetoothDevice::class.java) }
            .sortedByDescending { it.parameterCount }
        log("BluetoothSocket: ${ctors.size} usable constructors")
        val variants = buildList { for (c in ctors) { add(Variant(c, true)); add(Variant(c, false)) } }
        for ((i, v) in variants.withIndex()) {
            if (_state.value != State.CONNECTING) return@withContext false
            log("attempt ${i + 1}/${variants.size}: ${v.label}")
            val sock = try { buildSocket(v, device) } catch (t: Throwable) {
                log("  ✗ build failed: ${t.javaClass.simpleName}: ${t.message}"); continue
            }
            val t0 = System.currentTimeMillis()
            val watchdog = scope.launch(Dispatchers.IO) { delay(CONNECT_TIMEOUT_MS); log("  ⏱ no answer in ${CONNECT_TIMEOUT_MS / 1000}s, closing"); runCatching { sock.close() } }
            val err = try { sock.connect(); null } catch (e: IOException) { e }
            watchdog.cancel()
            if (err == null) {
                log("  ✓ L2CAP channel open after ${System.currentTimeMillis() - t0} ms via ${v.label}")
                onConnected(sock)
                return@withContext true
            }
            log("  ✗ after ${System.currentTimeMillis() - t0} ms: ${err.message}")
            runCatching { sock.close() }
        }
        log("✗ All variants failed. This Bluetooth stack does not let apps reach L2CAP PSM 0x1001 → AAP needs root here.")
        _state.value = State.UNSUPPORTED
        false
    }

    private suspend fun onConnected(sock: BluetoothSocket) {
        socket = sock
        framesIn = 0
        input = sock.inputStream
        output = sock.outputStream
        _state.value = State.CONNECTED
        readJob = scope.launch(Dispatchers.IO) { readLoop() }
        send(AapProtocol.HANDSHAKE, "handshake")
        delay(150)
        send(AapProtocol.SET_FEATURES, "set features")
        delay(150)
        send(AapProtocol.REQUEST_NOTIFICATIONS, "request notifications")
        scope.launch(Dispatchers.IO) {
            delay(6000)
            if (_state.value == State.CONNECTED && framesIn == 0) log("! channel is open but AirPods sent nothing in 6 s (stack may have accepted the socket without a real L2CAP link)")
        }
    }

    fun disconnect() {
        readJob?.cancel(); readJob = null
        runCatching { socket?.close() }
        socket = null; input = null; output = null
        if (_state.value != State.UNSUPPORTED) _state.value = State.DISCONNECTED
        _noiseMode.value = null
    }

    fun resetSupport() { if (_state.value == State.UNSUPPORTED) _state.value = State.DISCONNECTED }

    fun setNoiseMode(mode: AapProtocol.NoiseMode) = send(AapProtocol.control(AapProtocol.Ctl.NOISE_CONTROL, mode.code), "noise=$mode")
    fun setConversationAwareness(on: Boolean) = send(AapProtocol.control(AapProtocol.Ctl.CONVERSATION_AWARENESS, if (on) 1 else 2), "CA=$on")
    fun setEarDetection(on: Boolean) = send(AapProtocol.control(AapProtocol.Ctl.EAR_DETECTION, if (on) 1 else 2), "earDetection=$on")
    fun setAdaptiveLevel(level: Int) = send(AapProtocol.control(AapProtocol.Ctl.ADAPTIVE_NOISE_LEVEL, level.coerceIn(0, 100)), "adaptiveLevel=$level")
    fun sendControl(id: Int, value: Int) = send(AapProtocol.control(id, value), "ctl 0x%02X=%d".format(id, value))
    fun rename(name: String) = send(AapProtocol.rename(name), "rename→$name")
    fun sendRaw(bytes: ByteArray) = send(bytes, "raw")

    private fun send(bytes: ByteArray, what: String): Boolean {
        val out = output ?: run { log("✗ not connected, dropped: $what"); return false }
        return try {
            out.write(bytes); out.flush()
            log("→ $what  [${bytes.toHex()}]")
            true
        } catch (e: IOException) {
            log("✗ write failed ($what): ${e.message}")
            disconnect(); false
        }
    }

    private suspend fun readLoop() {
        val buf = ByteArray(8192)
        var zeroReads = 0
        val ins = input ?: return
        while (scope.isActive && socket != null) {
            val n = try { ins.read(buf) } catch (e: IOException) { log("link closed: ${e.message}"); break }
            if (n < 0) { log("link closed (EOF)"); break }
            if (n == 0) { zeroReads++; if (zeroReads == 20) log("! read() keeps returning 0 bytes"); delay(50); continue }
            zeroReads = 0
            framesIn++
            val frame = buf.copyOf(n)
            log("← raw [${frame.toHex()}]")
            handleFrame(frame)
        }
        withContext(Dispatchers.Main.immediate) { disconnect() }
    }

    private fun handleFrame(frame: ByteArray) {
        // Frames may be concatenated; split on the 04 00 04 00 header.
        var start = 0
        var i = 4
        while (i <= frame.size - 4) {
            if (frame[i] == 0x04.toByte() && frame[i + 1] == 0x00.toByte() && frame[i + 2] == 0x04.toByte() && frame[i + 3] == 0x00.toByte()) {
                dispatch(frame.copyOfRange(start, i)); start = i; i += 4
            } else i++
        }
        dispatch(frame.copyOfRange(start, frame.size))
    }

    private fun dispatch(frame: ByteArray) {
        val ev = AapProtocol.parse(frame) ?: run { log("← ? [${frame.toHex()}]"); return }
        when (ev) {
            is AapProtocol.Event.NoiseControl -> { _noiseMode.value = ev.mode; log("← noise mode ${ev.mode}") }
            is AapProtocol.Event.Battery -> log("← battery L=${ev.left} R=${ev.right} C=${ev.case}")
            is AapProtocol.Event.EarDetection -> log("← ear primary=${ev.primary} secondary=${ev.secondary}")
            is AapProtocol.Event.ConversationLevel -> log("← conversation level ${ev.level}")
            is AapProtocol.Event.ControlValue -> log("← ctl 0x%02X = %d".format(ev.id, ev.value))
            is AapProtocol.Event.Unknown -> log("← op 0x%02X [%s]".format(ev.opcode, ev.raw.toHex()))
        }
        _events.tryEmit(ev)
    }

    /**
     * Build `BluetoothSocket(TYPE_L2CAP, …, port = 0x1001)` through a given hidden constructor.
     * Signatures changed across releases, so parameters are matched by type:
     *   ints  → type, [fd], port (the int right before the ParcelUuid), then dataPath/maxPacketSize = 0
     *   bools → auth, encrypt (per variant), mitm, min16DigitPin = false
     */
    private fun buildSocket(v: Variant, device: BluetoothDevice): BluetoothSocket {
        val types = v.ctor.parameterTypes
        var intSeen = 0; var boolSeen = 0
        val args = types.map { t ->
            when {
                t == Int::class.javaPrimitiveType -> when (intSeen++) { 0 -> TYPE_L2CAP; else -> 0 }
                t == Boolean::class.javaPrimitiveType -> when (boolSeen++) { 0, 1 -> v.secure; else -> false }
                t == BluetoothDevice::class.java -> device
                t == ParcelUuid::class.java -> null
                t == String::class.java -> null
                t == Long::class.javaPrimitiveType -> 0L
                else -> null
            }
        }.toMutableList()
        val uuidIdx = types.indexOf(ParcelUuid::class.java)
        val ints = types.indices.filter { types[it] == Int::class.javaPrimitiveType }
        val portIdx = if (uuidIdx > 0) ints.last { it < uuidIdx } else ints.last()
        args[portIdx] = AapProtocol.PSM
        // legacy signatures (type, fd, auth, encrypt, device, port, uuid) carry an fd right after the type
        if (ints.size >= 3 && ints[1] < types.indexOf(BluetoothDevice::class.java) && ints[1] != portIdx) args[ints[1]] = -1
        v.ctor.isAccessible = true
        return try { v.ctor.newInstance(*args.toTypedArray()) as BluetoothSocket }
        catch (e: java.lang.reflect.InvocationTargetException) { throw e.targetException ?: e }
    }
}
