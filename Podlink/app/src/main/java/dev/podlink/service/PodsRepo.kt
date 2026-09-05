package dev.podlink.service

import android.content.Context
import dev.podlink.aap.AapClient
import dev.podlink.aap.AapProtocol
import dev.podlink.ble.PodsModel
import dev.podlink.ble.ProximityPacket
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Source { NONE, BLE, AAP }

/** A beacon that is not ours (someone else's AirPods nearby), for the "nearby devices" card. */
data class NearbyDevice(val address: String, val model: PodsModel, val rssi: Int, val lastSeen: Long, val left: Int?, val right: Int?, val case: Int?)

/** Everything the UI, widget, tile and notification need, in one immutable snapshot. */
data class PodsState(
    val serviceRunning: Boolean = false,
    val serviceStartedAt: Long = 0,
    val bluetoothOn: Boolean = true,
    val connected: Boolean = false,
    val deviceName: String? = null,
    val address: String? = null,
    val model: PodsModel = PodsModel.UNKNOWN,
    val left: Int? = null,
    val right: Int? = null,
    val case: Int? = null,
    val leftCharging: Boolean = false,
    val rightCharging: Boolean = false,
    val caseCharging: Boolean = false,
    val leftInEar: Boolean = false,
    val rightInEar: Boolean = false,
    val lidState: ProximityPacket.LidState = ProximityPacket.LidState.UNKNOWN,
    val primaryIsLeft: Boolean = true,
    val leftIsMicrophone: Boolean = true,
    val thisPodInCase: Boolean = false,
    val onePodInCase: Boolean = false,
    val bothInCase: Boolean = false,
    val connectionState: ProximityPacket.ConnectionState = ProximityPacket.ConnectionState.UNKNOWN,
    val rssi: Int? = null,
    val lastUpdate: Long = 0,
    val lastCaseUpdate: Long = 0,
    val source: Source = Source.NONE,
    /** Our AirPods are advertising nearby but not connected to this phone. */
    val nearbyNotConnected: Boolean = false,
    val nearby: List<NearbyDevice> = emptyList(),
    val aapState: AapClient.State = AapClient.State.DISCONNECTED,
    val noiseMode: AapProtocol.NoiseMode? = null,
    val conversationLevel: Int? = null,
    val leftMinutes: Int? = null,
    val rightMinutes: Int? = null,
    val caseMinutes: Int? = null,
    val scanning: Boolean = false,
    val appInForeground: Boolean = false,
) {
    val hasBattery get() = left != null || right != null || case != null
    val isFresh get() = System.currentTimeMillis() - lastUpdate < 60_000
    val isHeadphones get() = model.kind == PodsModel.Kind.HEADPHONES
    val lidOpen get() = lidState == ProximityPacket.LidState.OPEN
    /** Headphones report one battery in the left slot. */
    val single: Int? get() = left ?: right
    /** Lowest known pod level, what people mean by "how much is left". */
    val lowest: Int? get() = if (isHeadphones) single else listOfNotNull(left, right).minOrNull()

    /**
     * Merge a beacon into the state. The case beacon does not know the level of a pod that is out of it,
     * and a pod beacon does not know the case, so unknown (null) components keep their last known value.
     * Lid state is only taken from frames that are broadcast from inside the case.
     */
    fun apply(p: ProximityPacket) = copy(
        model = if (p.model != PodsModel.UNKNOWN) p.model else model,
        left = p.left ?: left, right = p.right ?: right, case = p.case ?: case,
        leftCharging = if (p.left != null) p.leftCharging else leftCharging,
        rightCharging = if (p.right != null) p.rightCharging else rightCharging,
        caseCharging = if (p.case != null) p.caseCharging else caseCharging,
        leftInEar = p.leftInEar, rightInEar = p.rightInEar,
        lidState = if (p.lidState != ProximityPacket.LidState.UNKNOWN) p.lidState else lidState,
        lastCaseUpdate = if (p.lidState != ProximityPacket.LidState.UNKNOWN) p.timestamp else lastCaseUpdate,
        primaryIsLeft = p.primaryIsLeft, leftIsMicrophone = p.leftIsMicrophone,
        thisPodInCase = p.thisPodInCase, onePodInCase = p.onePodInCase, bothInCase = p.bothInCase,
        connectionState = p.connectionState,
        rssi = p.rssi, lastUpdate = p.timestamp,
        source = if (source == Source.AAP && aapState == AapClient.State.CONNECTED) Source.AAP else Source.BLE,
    )
}

/** Process-wide singleton bridging the foreground service and every UI surface. */
object PodsRepo {
    private val _state = MutableStateFlow(PodsState())
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 32)
    /** Short human-readable event stream shown on the home screen ("Lid opened", "Connected", …). */
    val events = _events.asSharedFlow()

    /** Set by [PodsService]; null when the service is not running. */
    @Volatile var service: PodsService? = null

    fun update(block: PodsState.() -> PodsState) { _state.value = _state.value.block() }
    fun emit(event: String) { _events.tryEmit(event) }

    private const val SNAP = "podlink_snapshot"

    /** Persist a tiny snapshot so widgets/tiles can show the last known values after process death. */
    fun persist(context: Context) {
        val s = _state.value
        context.getSharedPreferences(SNAP, Context.MODE_PRIVATE).edit()
            .putInt("l", s.left ?: -1).putInt("r", s.right ?: -1).putInt("c", s.case ?: -1)
            .putBoolean("lc", s.leftCharging).putBoolean("rc", s.rightCharging).putBoolean("cc", s.caseCharging)
            .putBoolean("connected", s.connected).putString("name", s.deviceName).putString("model", s.model.name)
            .putLong("ts", s.lastUpdate).apply()
    }

    fun restore(context: Context) {
        if (_state.value.lastUpdate != 0L) return
        val p = context.getSharedPreferences(SNAP, Context.MODE_PRIVATE)
        if (!p.contains("ts")) return
        fun v(k: String) = p.getInt(k, -1).takeIf { it >= 0 }
        _state.value = _state.value.copy(
            left = v("l"), right = v("r"), case = v("c"),
            leftCharging = p.getBoolean("lc", false), rightCharging = p.getBoolean("rc", false), caseCharging = p.getBoolean("cc", false),
            connected = false, deviceName = p.getString("name", null),
            model = runCatching { PodsModel.valueOf(p.getString("model", "UNKNOWN")!!) }.getOrDefault(PodsModel.UNKNOWN),
            lastUpdate = p.getLong("ts", 0),
        )
    }
}
