package dev.podlink.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.app.ServiceCompat
import dev.podlink.MainActivity
import dev.podlink.R
import dev.podlink.aap.AapClient
import dev.podlink.aap.AapProtocol
import dev.podlink.ble.PodsModel
import dev.podlink.ble.PodsScanner
import dev.podlink.ble.ProximityPacket
import dev.podlink.bt.ClassicMonitor
import dev.podlink.bt.ConnectHelper
import dev.podlink.data.HistoryDb
import dev.podlink.data.Prefs
import dev.podlink.data.Sample
import dev.podlink.data.Settings as AppSettings
import dev.podlink.ui.PopupActivity
import dev.podlink.widget.PodsWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The heart of the app: a connected-device foreground service that
 *  • follows the Bluetooth Classic connection to the headset,
 *  • runs the filtered BLE scan and locks onto our beacon,
 *  • drives ear-detection play/pause, lid-open popup, auto-connect, alerts, history, widgets,
 *  • opportunistically opens the experimental AAP link (blocked on most ROMs, honest about it).
 */
@SuppressLint("MissingPermission")
class PodsService : Service() {

    companion object {
        private const val TAG = "PodsService"
        const val ACTION_START = "dev.podlink.START"
        const val ACTION_STOP = "dev.podlink.STOP"
        const val ACTION_CONNECT_NOW = "dev.podlink.CONNECT_NOW"
        const val ACTION_AAP_RETRY = "dev.podlink.AAP_RETRY"
        const val ACTION_SHOW_POPUP = "dev.podlink.SHOW_POPUP"
        const val ACTION_FIND = "dev.podlink.FIND"
        /** Local broadcast that tells an open PopupActivity to go away (lid closed / beacon stale). */
        const val ACTION_POPUP_DISMISS = "dev.podlink.POPUP_DISMISS"

        fun start(context: Context) {
            val i = Intent(context, PodsService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
        fun stop(context: Context) = context.startService(Intent(context, PodsService::class.java).setAction(ACTION_STOP))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var monitor: ClassicMonitor; private set
    lateinit var scanner: PodsScanner; private set
    lateinit var aap: AapClient; private set
    lateinit var connectHelper: ConnectHelper; private set
    lateinit var prefs: Prefs; private set
    private lateinit var db: HistoryDb
    private lateinit var media: MediaController
    private val announcer by lazy { Announcer(this) }
    private var settings = AppSettings()
    private var settingsLoaded = false

    // edge-detection memory
    private var prevInEarCount = -1
    private var pausedByUs = false
    private var prevLid: ProximityPacket.LidState? = null
    private var lastPopupAt = 0L
    private var lastAutoConnectAt = 0L
    private var lowAlerted = HashSet<Char>()
    private var chargingSession = false
    private var chargedFired = false
    private var aapAttemptedFor: String? = null
    private var aggressiveScanRequests = 0
    private var lastWidgetPush = 0L
    private var nearbyJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        Notifications.cancelTapToStart(this)
        prefs = Prefs(this)
        db = HistoryDb(this)
        media = MediaController(this)
        monitor = ClassicMonitor(this)
        scanner = PodsScanner(this)
        aap = AapClient(scope)
        connectHelper = ConnectHelper(
            monitor,
            hiddenBroken = { settings.hiddenConnectBroken },
            markHiddenBroken = { scope.launch { prefs.update { copy(hiddenConnectBroken = true) } } },
        )
        PodsRepo.restore(this)
        PodsRepo.service = this
        startForegroundCompat()
        monitor.start()
        wire()
        PodsRepo.update { copy(serviceRunning = true, serviceStartedAt = System.currentTimeMillis(), bluetoothOn = monitor.bluetoothOn.value) }
        Watchdog.schedule(this)
        CompanionLink.observePresence(this)
    }

    private fun startForegroundCompat() {
        val n = Notifications.status(this, PodsRepo.state.value, settings)
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, Notifications.ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else startForeground(Notifications.ID_STATUS, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { Watchdog.cancel(this); stopSelf(); return START_NOT_STICKY }
            ACTION_CONNECT_NOW -> scope.launch { connectNow() }
            ACTION_AAP_RETRY -> scope.launch { aap.resetSupport(); aapAttemptedFor = null; tryAap() }
            ACTION_SHOW_POPUP -> showPopup(force = true)
            ACTION_FIND -> startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("find", true))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        PodsRepo.update { copy(serviceRunning = false, scanning = false) }
        PodsRepo.service = null
        media.unduck()
        announcer.shutdown()
        aap.disconnect()
        scanner.stop()
        monitor.stop()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------------------------------

    private fun wire() {
        scope.launch {
            prefs.flow.collectLatest { s ->
                val first = !settingsLoaded
                settings = s; settingsLoaded = true
                scanner.minRssi = s.minRssi
                scanner.forceUnfiltered = s.scanUnfiltered
                monitor.preferredAddress = s.preferredAddress.ifBlank { null }
                monitor.refresh()
                applyScanPolicy()
                refreshNotification()
                if (!s.serviceEnabled) { Watchdog.cancel(this@PodsService); stopSelf() }
                if (first && !s.keepAliveWatchdog) Watchdog.cancel(this@PodsService)
            }
        }
        scope.launch { monitor.bluetoothOn.collect { on -> PodsRepo.update { copy(bluetoothOn = on) }; applyScanPolicy() } }
        scope.launch { monitor.connected.collect { onConnectionChanged(it) } }
        scope.launch { scanner.locked.collect { pkt -> if (pkt != null) onPacket(pkt) } }
        scope.launch { scanner.mode.collect { m -> PodsRepo.update { copy(scanning = m != PodsScanner.Mode.OFF) } } }
        scope.launch { scanner.allPackets.collect { p -> if (!settings.autoConnectOnLidOpen) return@collect; onAnyPacket(p) } }
        scope.launch { aap.state.collect { st -> PodsRepo.update { copy(aapState = st, noiseMode = if (st == AapClient.State.CONNECTED) noiseMode else null) }; refreshNotification() } }
        scope.launch { aap.noiseMode.collect { m -> PodsRepo.update { copy(noiseMode = m) } } }
        scope.launch { aap.events.collect { onAapEvent(it) } }
        scope.launch { PodsRepo.state.map { it.copy(lastUpdate = 0, rssi = null, nearby = emptyList()) }.distinctUntilChanged().collect { pushWidgets() } }
        // fast ticker: lid staleness, nearby list
        scope.launch {
            while (true) {
                delay(2_000)
                val s = PodsRepo.state.value
                // The case stops broadcasting when the lid closes; treat 4 s of silence as "closed".
                if (s.lidOpen && System.currentTimeMillis() - s.lastCaseUpdate > 4_000) {
                    PodsRepo.update { copy(lidState = ProximityPacket.LidState.CLOSED) }
                    prevLid = ProximityPacket.LidState.CLOSED
                    sendBroadcast(Intent(ACTION_POPUP_DISMISS).setPackage(packageName))
                    PodsRepo.emit(getString(R.string.ev_lid_closed)); db.event("lid_closed"); broadcast("LID_CLOSED")
                    refreshNotification()
                }
                updateNearby()
            }
        }
        // slow ticker: stale-lock check, estimates, scan health
        scope.launch {
            while (true) {
                delay(30_000)
                val s = PodsRepo.state.value
                if (s.connected && scanner.lockedSilentFor(45_000)) scanner.resetLock()
                updateEstimates()
                scanner.checkHealth()
                refreshNotification()
            }
        }
    }

    private fun applyScanPolicy() {
        val s = PodsRepo.state.value
        val mode = when {
            !s.bluetoothOn -> PodsScanner.Mode.OFF
            aggressiveScanRequests > 0 -> PodsScanner.Mode.AGGRESSIVE
            s.connected -> if (settings.scanBalancedWhenConnected) PodsScanner.Mode.BALANCED else PodsScanner.Mode.LOW_POWER
            settings.alwaysScan -> PodsScanner.Mode.LOW_POWER
            else -> PodsScanner.Mode.OFF
        }
        scanner.start(mode)
    }

    /** UI screens (radar) can ask for a fast scan while visible. */
    fun requestAggressiveScan(on: Boolean) {
        aggressiveScanRequests = (aggressiveScanRequests + if (on) 1 else -1).coerceAtLeast(0)
        applyScanPolicy()
    }

    private suspend fun onConnectionChanged(c: ClassicMonitor.Connected?) {
        val was = PodsRepo.state.value.connected
        if (c != null) {
            scanner.expectedModel = c.model
            PodsRepo.update { copy(connected = true, deviceName = settings.customName.ifBlank { c.name }, address = c.device.address, model = if (model == PodsModel.UNKNOWN) c.model else model, nearbyNotConnected = false) }
            if (!was) {
                PodsRepo.emit(getString(R.string.ev_connected, c.name))
                db.event("connected", c.name)
                broadcast("CONNECTED")
                lowAlerted.clear(); chargingSession = false; chargedFired = false
                scanner.resetLock()
                if (settings.vibrateOnConnect) vibrate(longArrayOf(0, 30, 60, 30))
                if (settings.popupOnConnect) scope.launch { delay(1500); showPopup(force = true) }
                if (settings.voiceAnnouncements) scope.launch {
                    delay(3500)
                    val st = PodsRepo.state.value
                    if (!st.connected) return@launch
                    val text = if (st.isHeadphones) getString(R.string.say_connected_single, st.single ?: 0)
                               else getString(R.string.say_connected, st.left ?: 0, st.right ?: 0)
                    announcer.say(text)
                }
                tryAap()
            }
        } else {
            val bonded = monitor.bondedPods()
            val guess = settings.preferredAddress.takeIf { it.isNotBlank() }?.let { a -> bonded.firstOrNull { it.address.equals(a, true) } } ?: bonded.firstOrNull()
            scanner.expectedModel = PodsModel.guessFromName(guess?.name)
            PodsRepo.update { copy(connected = false, source = Source.NONE, aapState = AapClient.State.DISCONNECTED, noiseMode = null, conversationLevel = null, leftInEar = false, rightInEar = false) }
            aap.disconnect(); aapAttemptedFor = null
            media.unduck()
            if (was) {
                PodsRepo.emit(getString(R.string.ev_disconnected)); db.event("disconnected"); broadcast("DISCONNECTED")
            }
            prevInEarCount = -1; pausedByUs = false
        }
        applyScanPolicy()
        refreshNotification()
    }

    private suspend fun onPacket(p: ProximityPacket) {
        val before = PodsRepo.state.value
        val connected = before.connected
        PodsRepo.update { apply(p).copy(nearbyNotConnected = !connected) }

        // lid edges — only from frames that really know the lid (broadcast from inside the case)
        if (p.lidState != ProximityPacket.LidState.UNKNOWN) {
            if (prevLid != null && prevLid != p.lidState) {
                if (p.lidState == ProximityPacket.LidState.OPEN) {
                    PodsRepo.emit(getString(R.string.ev_lid_open)); db.event("lid_open"); broadcast("LID_OPENED")
                    if (!connected && settings.autoConnectOnLidOpen && settings.autoConnectMode == "lid" && p.rssi > -72) autoConnect()
                    if (settings.popupOnLidOpen && p.rssi > -72) showPopup(force = false)
                } else {
                    PodsRepo.emit(getString(R.string.ev_lid_closed)); db.event("lid_closed"); broadcast("LID_CLOSED")
                    sendBroadcast(Intent(ACTION_POPUP_DISMISS).setPackage(packageName))
                }
            }
            prevLid = p.lidState
        }

        if (connected) {
            handleEarDetection(p.leftInEar, p.rightInEar)
            handleBatteryAlerts(PodsRepo.state.value)
            db.record(Sample(p.timestamp, p.left, p.right, p.case, p.leftCharging, p.rightCharging, p.caseCharging, p.leftInEar, p.rightInEar))
            broadcast("BATTERY")
        } else if (settings.autoConnectOnLidOpen && settings.autoConnectMode == "inear") {
            val inEar = if (settings.pauseOnOneBud) p.anyInEar else p.bothInEar
            if (inEar && p.rssi > -72) autoConnect()
        }
        refreshNotification()
    }

    /** "When seen" auto-connect: any packet of our family while disconnected. */
    private fun onAnyPacket(p: ProximityPacket) {
        if (settings.autoConnectMode != "seen") return
        if (PodsRepo.state.value.connected) return
        if (!scanner.expectedModel.sameFamily(p.model) || p.rssi < -65) return
        autoConnect(cooldownMs = 60_000)
    }

    private fun updateNearby() {
        val lockedAddr = scanner.locked.value?.address
        val now = System.currentTimeMillis()
        val list = scanner.stats.value.beacons.values
            .filter { it.address != lockedAddr && now - it.lastSeen < 30_000 }
            .sortedByDescending { it.rssi }
            .take(5)
            .map { NearbyDevice(it.address, it.model, it.rssi, it.lastSeen, it.packet.left, it.packet.right, it.packet.case) }
        if (list != PodsRepo.state.value.nearby) PodsRepo.update { copy(nearby = list) }
    }

    private fun handleEarDetection(l: Boolean, r: Boolean) {
        if (!settings.earDetection && !settings.startMusicOnWear) return
        val count = (if (l) 1 else 0) + (if (r) 1 else 0)
        if (prevInEarCount == -1) { prevInEarCount = count; return }
        if (count == prevInEarCount) return
        val removed = count < prevInEarCount
        val isHeadphones = PodsRepo.state.value.isHeadphones
        val shouldPause = settings.earDetection && removed && (isHeadphones || count == 0 || settings.pauseOnOneBud)
        val fullyOn = isHeadphones || count == 2 || (settings.pauseOnOneBud && count >= 1)
        val shouldResume = !removed && fullyOn
        prevInEarCount = count
        scope.launch {
            delay(700) // debounce: a bud brushing the ear sends a flurry of packets
            val s = PodsRepo.state.value
            val curCount = (if (s.leftInEar) 1 else 0) + (if (s.rightInEar) 1 else 0)
            if (curCount != count) return@launch
            if (shouldPause && media.isMusicActive) {
                media.pause(); pausedByUs = true
                PodsRepo.emit(getString(R.string.ev_paused)); broadcast("OUT_OF_EAR")
            } else if (shouldResume) {
                if (pausedByUs && settings.resumeOnReinsert && settings.earDetection) {
                    media.play(); pausedByUs = false
                    PodsRepo.emit(getString(R.string.ev_resumed)); broadcast("IN_EAR")
                } else if (settings.startMusicOnWear && !media.isMusicActive && (isHeadphones || count == 2)) {
                    media.play(); pausedByUs = false
                    PodsRepo.emit(getString(R.string.ev_resumed)); broadcast("IN_EAR")
                } else broadcast("IN_EAR")
            }
        }
    }

    private fun handleBatteryAlerts(s: PodsState) {
        val th = settings.lowBatteryAlert
        fun check(c: Char, v: Int?, charging: Boolean, label: String) {
            if (v == null) return
            if (v <= th && !charging && c !in lowAlerted) {
                lowAlerted += c
                Notifications.alert(this, c.code, getString(R.string.alert_low_title, label), getString(R.string.alert_low_text, v))
                if (settings.voiceAnnouncements) announcer.say(getString(R.string.say_low, label, v))
                broadcast("LOW_BATTERY", mapOf("component" to label, "level" to v.toString()))
            }
            if (v > th + 10) lowAlerted -= c
        }
        if (s.isHeadphones) check('L', s.single, s.leftCharging, s.model.label) else {
            check('L', s.left, s.leftCharging, getString(R.string.left))
            check('R', s.right, s.rightCharging, getString(R.string.right))
            check('C', s.case, s.caseCharging, getString(R.string.case_))
        }
        // charged notification, once per charging session
        val anyCharging = s.leftCharging || s.rightCharging || s.caseCharging
        if (anyCharging && !chargingSession) { chargingSession = true; chargedFired = false }
        if (!anyCharging && chargingSession && (s.left ?: 100) < 90) { chargingSession = false }
        if (settings.fullChargeAlert && chargingSession && !chargedFired) {
            val t = settings.chargedThreshold
            val podsOk = if (s.isHeadphones) (s.single ?: 0) >= t else (s.left ?: 0) >= t && (s.right ?: 0) >= t
            val caseOk = (s.case ?: 0) >= t
            val ok = when (settings.chargedScope) { "pods" -> podsOk; "case" -> caseOk; else -> podsOk && caseOk }
            if (ok) {
                chargedFired = true
                Notifications.alert(this, 9, getString(R.string.alert_full_title), getString(R.string.alert_full_text_pct, t))
                if (settings.voiceAnnouncements) announcer.say(getString(R.string.say_charged))
                broadcast("FULLY_CHARGED")
            }
        }
    }

    private suspend fun updateEstimates() {
        val s = PodsRepo.state.value
        if (!s.connected) return
        val l = db.estimate('L'); val r = db.estimate('R'); val c = db.estimate('C')
        PodsRepo.update { copy(leftMinutes = l.minutesLeft, rightMinutes = r.minutesLeft, caseMinutes = c.minutesLeft) }
    }

    // ---- popup & auto-connect ----------------------------------------------------------------

    private fun showPopup(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPopupAt < 8_000) return
        if (!force && PodsRepo.state.value.appInForeground) return
        lastPopupAt = now
        if (!Settings.canDrawOverlays(this)) { Log.w(TAG, "no overlay permission → popup skipped"); return }
        val i = Intent(this, PopupActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("duration", settings.popupDurationSec)
            .putExtra("lock", settings.popupShowOnLockScreen)
            .putExtra("theme", settings.theme)
            .putExtra("dynamic", settings.dynamicColor)
        runCatching { startActivity(i) }.onFailure { Log.w(TAG, "popup blocked: $it") }
    }

    private fun autoConnect(cooldownMs: Long = 20_000) {
        val now = System.currentTimeMillis()
        if (now - lastAutoConnectAt < cooldownMs) return
        lastAutoConnectAt = now
        scope.launch { connectNow() }
    }

    suspend fun connectNow(): ConnectHelper.Result {
        val model = PodsRepo.state.value.model
        val bonded = monitor.bondedPods()
        val target = settings.preferredAddress.takeIf { it.isNotBlank() }?.let { a -> bonded.firstOrNull { it.address.equals(a, true) } }
            ?: bonded.firstOrNull { PodsModel.guessFromName(it.name).sameFamily(model) }
            ?: bonded.firstOrNull()
            ?: return ConnectHelper.Result.Failed(listOf("no bonded AirPods/Beats device"))
        PodsRepo.emit(getString(R.string.ev_connecting, target.name ?: target.address))
        val r = connectHelper.connect(target)
        when (r) {
            is ConnectHelper.Result.Ok -> { db.event("autoconnect_ok", r.via); PodsRepo.emit(getString(R.string.ev_connect_ok, r.via)) }
            is ConnectHelper.Result.Failed -> { db.event("autoconnect_fail", r.log.joinToString("; ")); PodsRepo.emit(getString(R.string.ev_connect_fail)) }
        }
        return r
    }

    private fun vibrate(pattern: LongArray) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        runCatching { v.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    // ---- AAP -----------------------------------------------------------------------------------

    private fun tryAap() {
        val c = monitor.connected.value ?: return
        if (!settings.aapEnabled) return
        if (aapAttemptedFor == c.device.address) return
        aapAttemptedFor = c.device.address
        scope.launch {
            delay(2500) // let A2DP/HFP settle first
            if (monitor.connected.value?.device?.address != c.device.address) return@launch
            val ok = aap.connect(c.device)
            db.event(if (ok) "aap_ok" else "aap_unsupported")
            if (ok) PodsRepo.emit(getString(R.string.ev_aap_ok))
        }
    }

    private fun onAapEvent(ev: AapProtocol.Event) {
        when (ev) {
            is AapProtocol.Event.Battery -> {
                PodsRepo.update {
                    copy(
                        left = ev.left ?: left, right = ev.right ?: right, case = ev.case ?: case,
                        leftCharging = ev.leftCharging, rightCharging = ev.rightCharging, caseCharging = ev.caseCharging,
                        lastUpdate = System.currentTimeMillis(), source = Source.AAP,
                    )
                }
                val s = PodsRepo.state.value
                scope.launch { db.record(Sample(s.lastUpdate, s.left, s.right, s.case, s.leftCharging, s.rightCharging, s.caseCharging, s.leftInEar, s.rightInEar)) }
                handleBatteryAlerts(s)
                refreshNotification()
            }
            is AapProtocol.Event.EarDetection -> {
                val primaryLeft = PodsRepo.state.value.primaryIsLeft
                val pIn = ev.primary == 0; val sIn = ev.secondary == 0
                PodsRepo.update { copy(leftInEar = if (primaryLeft) pIn else sIn, rightInEar = if (primaryLeft) sIn else pIn, lastUpdate = System.currentTimeMillis()) }
                handleEarDetection(PodsRepo.state.value.leftInEar, PodsRepo.state.value.rightInEar)
            }
            is AapProtocol.Event.ConversationLevel -> {
                PodsRepo.update { copy(conversationLevel = ev.level) }
                if (settings.conversationDuck) {
                    if (ev.level <= 3) media.duck(settings.duckPercent) else if (ev.level >= 8) media.unduck()
                }
            }
            is AapProtocol.Event.NoiseControl -> { broadcast("ANC", mapOf("mode" to ev.mode.name)); refreshNotification() }
            else -> {}
        }
    }

    // ---- outputs -------------------------------------------------------------------------------

    private fun refreshNotification() {
        if (Notifications.notifyStatus(this, PodsRepo.state.value, settings)) {
            runCatching { TileService.requestListeningState(this, android.content.ComponentName(this, PodsTileService::class.java)) }
        }
    }

    private fun pushWidgets() {
        val now = System.currentTimeMillis()
        if (now - lastWidgetPush < 5_000) return
        lastWidgetPush = now
        PodsRepo.persist(this)
        scope.launch(Dispatchers.IO) { runCatching { PodsWidget.updateAll(this@PodsService) } }
    }

    /** Automation hook: implicit broadcast any automation app (MacroDroid, Tasker+AutoTools, Automate) can listen to. */
    private fun broadcast(event: String, extras: Map<String, String> = emptyMap()) {
        if (!settings.automationBroadcasts) return
        val s = PodsRepo.state.value
        val i = Intent("dev.podlink.event.$event")
            .putExtra("event", event)
            .putExtra("left", s.left ?: -1).putExtra("right", s.right ?: -1).putExtra("case", s.case ?: -1)
            .putExtra("leftCharging", s.leftCharging).putExtra("rightCharging", s.rightCharging).putExtra("caseCharging", s.caseCharging)
            .putExtra("leftInEar", s.leftInEar).putExtra("rightInEar", s.rightInEar)
            .putExtra("lidOpen", s.lidOpen).putExtra("connected", s.connected)
            .putExtra("model", s.model.label).putExtra("name", s.deviceName)
        extras.forEach { (k, v) -> i.putExtra(k, v) }
        sendBroadcast(i)
    }
}
