package dev.podlink.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Filtered BLE scan for Apple Proximity Pairing frames plus "ours vs. theirs" selection.
 *
 * One pair of AirPods is really several beacons: the pod inside the case (with lid/case data) and every
 * pod outside the case (with in-ear data), each on its own rotating random address. Locking on a single
 * address therefore loses half of the picture, so instead every frame of the expected model family whose
 * signal is within [ACCEPT_WINDOW_DB] of the strongest recent frame is accepted and merged by the service.
 * Strangers' AirPods almost always sit well below that window (and usually are a different model anyway).
 */
@SuppressLint("MissingPermission")
class PodsScanner(private val context: Context) {

    companion object {
        private const val TAG = "PodsScanner"
        /** Android allows 5 scan starts per 30 s; we never restart faster than this. */
        private const val MIN_RESTART_INTERVAL_MS = 7_000L
        /** Frames this much weaker than the strongest recent one are treated as someone else's. */
        private const val ACCEPT_WINDOW_DB = 12
        /** How long the "strongest recent frame" reference lives. */
        private const val BEST_TTL_MS = 12_000L
    }

    enum class Mode { OFF, LOW_POWER, BALANCED, AGGRESSIVE }

    data class Beacon(val address: String, val model: PodsModel, val rawModelId: Int, val rssi: Int, val lastSeen: Long, val rawHex: String, val packet: ProximityPacket, val ours: Boolean)
    data class Stats(
        val started: Long = 0,
        val packets: Int = 0,
        val appleFrames: Int = 0,
        val unfiltered: Boolean = false,
        val beacons: Map<String, Beacon> = emptyMap(),
        val lastError: String? = null,
    )

    private val adapter get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private val _packets = MutableSharedFlow<ProximityPacket>(extraBufferCapacity = 64)
    /** Every decoded status frame, including other people's AirPods (radar / diagnostics). */
    val allPackets = _packets.asSharedFlow()

    private val _accepted = MutableSharedFlow<ProximityPacket>(extraBufferCapacity = 64)
    /** Frames judged to be from our headset (any of its beacons). */
    val accepted = _accepted.asSharedFlow()

    private val _locked = MutableStateFlow<ProximityPacket?>(null)
    /** Last accepted frame (kept for UI/diagnostics). */
    val locked = _locked.asStateFlow()

    private val _mode = MutableStateFlow(Mode.OFF)
    val mode = _mode.asStateFlow()

    private val _stats = MutableStateFlow(Stats())
    val stats = _stats.asStateFlow()

    /** Model we expect (from the connected / bonded classic device). UNKNOWN = accept anything. */
    var expectedModel: PodsModel = PodsModel.UNKNOWN
    /** Frames weaker than this are never accepted (dBm). */
    var minRssi: Int = -85
    /** Force software-only filtering (compatibility mode). */
    var forceUnfiltered: Boolean = false

    private var unfilteredFallback = false
    private var bestRssi = -127
    private var bestAt = 0L
    private var lastAcceptedAt = 0L
    private var lastStart = 0L
    private var pendingMode: Mode? = null

    /** Addresses accepted recently, so the nearby list can tell ours from theirs. */
    private val ourAddresses = HashMap<String, Long>()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            _stats.value = _stats.value.copy(lastError = "onScanFailed($errorCode)")
            _mode.value = Mode.OFF
        }
    }

    private fun handle(result: ScanResult) {
        val data = result.scanRecord?.getManufacturerSpecificData(ProximityPacket.APPLE_COMPANY_ID) ?: return
        val st = _stats.value
        val pkt = ProximityPacket.parse(data, result.rssi, result.device.address)
        if (pkt == null) { _stats.value = st.copy(appleFrames = st.appleFrames + 1); return }
        val ours = consider(pkt)
        val hex = data.joinToString(" ") { "%02X".format(it) }
        val beacons = st.beacons.filterValues { pkt.timestamp - it.lastSeen < 60_000 } +
            (pkt.address to Beacon(pkt.address, pkt.model, pkt.rawModelId, pkt.rssi, pkt.timestamp, hex, pkt, ours))
        _stats.value = st.copy(packets = st.packets + 1, appleFrames = st.appleFrames + 1, beacons = beacons)
        _packets.tryEmit(pkt)
        if (ours) { _locked.value = pkt; _accepted.tryEmit(pkt) }
    }

    /** Decide whether this frame belongs to our headset. */
    private fun consider(pkt: ProximityPacket): Boolean {
        val now = System.currentTimeMillis()
        if (!expectedModel.sameFamily(pkt.model)) return false
        if (pkt.left == null && pkt.right == null && pkt.case == null) return false
        if (pkt.rssi < minRssi) return false
        if (now - bestAt > BEST_TTL_MS) bestRssi = -127
        if (pkt.rssi > bestRssi) { bestRssi = pkt.rssi; bestAt = now }
        // Known beacon of ours seen within the TTL: keep following it even through a dip.
        val known = ourAddresses[pkt.address]?.let { now - it < BEST_TTL_MS * 2 } == true
        val accepted = known || pkt.rssi >= bestRssi - ACCEPT_WINDOW_DB
        if (accepted) { ourAddresses[pkt.address] = now; lastAcceptedAt = now }
        if (ourAddresses.size > 16) ourAddresses.entries.removeIf { now - it.value > BEST_TTL_MS * 4 }
        return accepted
    }

    fun isOurs(address: String): Boolean = ourAddresses[address]?.let { System.currentTimeMillis() - it < BEST_TTL_MS * 2 } == true

    fun resetLock() { bestRssi = -127; bestAt = 0; ourAddresses.clear(); _locked.value = null }

    /** True when no frame of ours arrived for [ms]. */
    fun lockedSilentFor(ms: Long): Boolean = lastAcceptedAt != 0L && System.currentTimeMillis() - lastAcceptedAt > ms

    /** Called periodically by the service: if the hardware filter delivers nothing, fall back to an unfiltered scan. */
    fun checkHealth() {
        val st = _stats.value
        if (_mode.value == Mode.OFF) { pendingMode?.let { pendingMode = null; start(it) }; return }
        pendingMode?.let { if (System.currentTimeMillis() - lastStart >= MIN_RESTART_INTERVAL_MS) { pendingMode = null; restart(it) } }
        if (unfilteredFallback || forceUnfiltered) return
        if (st.packets == 0 && st.appleFrames == 0 && System.currentTimeMillis() - st.started > 25_000) {
            Log.w(TAG, "no Apple frames in 25s with hardware filter → unfiltered fallback")
            unfilteredFallback = true
            restart(_mode.value)
        }
    }

    fun start(mode: Mode) {
        if (mode == Mode.OFF) { stop(); return }
        if (_mode.value == mode) return
        restart(mode)
    }

    private fun restart(mode: Mode) {
        val now = System.currentTimeMillis()
        if (now - lastStart < MIN_RESTART_INTERVAL_MS && _mode.value != Mode.OFF) {
            // Too soon after the previous start: remember the wish, checkHealth() applies it later.
            pendingMode = mode
            return
        }
        val scanner = adapter?.bluetoothLeScanner ?: run { Log.w(TAG, "no LE scanner"); return }
        if (_mode.value != Mode.OFF) runCatching { scanner.stopScan(callback) }
        val unfiltered = unfilteredFallback || forceUnfiltered
        // Filter on the Apple company id only (empty data = any payload); the type byte is checked in software.
        // A non-empty field keeps the scan "filtered" so Android does not suspend it while the screen is off.
        val filters = if (unfiltered) emptyList()
                      else listOf(ScanFilter.Builder().setManufacturerData(ProximityPacket.APPLE_COMPANY_ID, byteArrayOf()).build())
        val settings = ScanSettings.Builder()
            .setScanMode(
                when (mode) {
                    Mode.LOW_POWER -> ScanSettings.SCAN_MODE_LOW_POWER
                    Mode.BALANCED -> ScanSettings.SCAN_MODE_BALANCED
                    else -> ScanSettings.SCAN_MODE_LOW_LATENCY
                },
            )
            .setReportDelay(0)
            .build()
        lastStart = now
        pendingMode = null
        runCatching { scanner.startScan(filters, settings, callback) }
            .onSuccess {
                _mode.value = mode
                _stats.value = _stats.value.copy(started = now, packets = 0, appleFrames = 0, unfiltered = unfiltered, lastError = null)
                Log.i(TAG, "scan started: $mode unfiltered=$unfiltered")
            }
            .onFailure { _stats.value = _stats.value.copy(lastError = it.toString()); Log.w(TAG, "startScan failed", it) }
    }

    fun stop() {
        pendingMode = null
        if (_mode.value == Mode.OFF) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(callback) }
        _mode.value = Mode.OFF
        Log.i(TAG, "scan stopped")
    }
}
