package dev.podlink.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("podlink")

data class Settings(
    val serviceEnabled: Boolean = true,
    val earDetection: Boolean = true,
    val pauseOnOneBud: Boolean = true,
    val resumeOnReinsert: Boolean = true,
    val popupOnLidOpen: Boolean = true,
    val autoConnectOnLidOpen: Boolean = true,
    val alwaysScan: Boolean = true,           // scan even when not connected (needed for lid-open popup / auto-connect)
    val minRssi: Int = -85,
    val lowBatteryAlert: Int = 20,
    val fullChargeAlert: Boolean = true,
    val statusBarChip: Boolean = true,        // Android 16 "Live Updates" promoted notification
    val automationBroadcasts: Boolean = true,
    val aapEnabled: Boolean = true,           // try the experimental AAP link when connected
    val conversationDuck: Boolean = true,     // lower media volume when AirPods report you are speaking (AAP)
    val duckPercent: Int = 70,
    val preferredAddress: String = "",
    val customName: String = "",
    val onboardingDone: Boolean = false,
    // appearance
    val theme: String = "system",            // system | dark | light
    val dynamicColor: Boolean = true,        // Material You on Android 12+
    // notification / status bar
    val statusBarPercent: Boolean = true,    // render battery % as the status-bar small icon
    val statusBarSource: String = "lowest",  // lowest | left | right | case
    val notificationActions: Boolean = true,
    // popup
    val popupOnConnect: Boolean = true,
    val popupDurationSec: Int = 6,
    val popupShowOnLockScreen: Boolean = true,
    // scan compatibility
    val scanUnfiltered: Boolean = false,     // force software filtering (compatibility mode)
    val scanBalancedWhenConnected: Boolean = true,
    // reliability
    val keepAliveWatchdog: Boolean = true,   // AlarmManager watchdog restarts the service if XOS kills it
    val vibrateOnConnect: Boolean = false,
    // charged notification
    val chargedThreshold: Int = 100,         // 50..100 step 10
    val chargedScope: String = "both",       // pods | case | both
    // auto-connect
    val autoConnectMode: String = "lid",     // lid | seen | inear
    val hiddenConnectBroken: Boolean = false,// remembered after a SecurityException from the hidden connect()
    // notification
    val keepAfterDisconnect: Boolean = true, // keep last battery in the notification when disconnected
    val idleNotification: Boolean = true,    // show the "waiting" notification at all (separate channel, can be muted)
    // home
    val hideUnmatched: Boolean = false,      // hide other people's AirPods card
    val startMusicOnWear: Boolean = false,   // play when both pods go in even if we did not pause
    val voiceAnnouncements: Boolean = false, // TTS: connected + battery, low battery, charged
    val heroVideo: Boolean = true,           // play the real connection animation on Home and in the popup
)

class Prefs(private val context: Context) {
    private object K {
        val serviceEnabled = booleanPreferencesKey("serviceEnabled")
        val earDetection = booleanPreferencesKey("earDetection")
        val pauseOnOneBud = booleanPreferencesKey("pauseOnOneBud")
        val resumeOnReinsert = booleanPreferencesKey("resumeOnReinsert")
        val popupOnLidOpen = booleanPreferencesKey("popupOnLidOpen")
        val autoConnectOnLidOpen = booleanPreferencesKey("autoConnectOnLidOpen")
        val alwaysScan = booleanPreferencesKey("alwaysScan")
        val minRssi = intPreferencesKey("minRssi")
        val lowBatteryAlert = intPreferencesKey("lowBatteryAlert")
        val fullChargeAlert = booleanPreferencesKey("fullChargeAlert")
        val statusBarChip = booleanPreferencesKey("statusBarChip")
        val automationBroadcasts = booleanPreferencesKey("automationBroadcasts")
        val aapEnabled = booleanPreferencesKey("aapEnabled")
        val conversationDuck = booleanPreferencesKey("conversationDuck")
        val duckPercent = intPreferencesKey("duckPercent")
        val preferredAddress = stringPreferencesKey("preferredAddress")
        val customName = stringPreferencesKey("customName")
        val onboardingDone = booleanPreferencesKey("onboardingDone")
        val theme = stringPreferencesKey("theme")
        val dynamicColor = booleanPreferencesKey("dynamicColor")
        val statusBarPercent = booleanPreferencesKey("statusBarPercent")
        val statusBarSource = stringPreferencesKey("statusBarSource")
        val notificationActions = booleanPreferencesKey("notificationActions")
        val popupOnConnect = booleanPreferencesKey("popupOnConnect")
        val popupDurationSec = intPreferencesKey("popupDurationSec")
        val popupShowOnLockScreen = booleanPreferencesKey("popupShowOnLockScreen")
        val scanUnfiltered = booleanPreferencesKey("scanUnfiltered")
        val scanBalancedWhenConnected = booleanPreferencesKey("scanBalancedWhenConnected")
        val keepAliveWatchdog = booleanPreferencesKey("keepAliveWatchdog")
        val vibrateOnConnect = booleanPreferencesKey("vibrateOnConnect")
        val chargedThreshold = intPreferencesKey("chargedThreshold")
        val chargedScope = stringPreferencesKey("chargedScope")
        val autoConnectMode = stringPreferencesKey("autoConnectMode")
        val hiddenConnectBroken = booleanPreferencesKey("hiddenConnectBroken")
        val keepAfterDisconnect = booleanPreferencesKey("keepAfterDisconnect")
        val idleNotification = booleanPreferencesKey("idleNotification")
        val hideUnmatched = booleanPreferencesKey("hideUnmatched")
        val startMusicOnWear = booleanPreferencesKey("startMusicOnWear")
        val voiceAnnouncements = booleanPreferencesKey("voiceAnnouncements")
        val heroVideo = booleanPreferencesKey("heroVideo")
    }

    val flow: Flow<Settings> = context.store.data.map { decode(it) }

    private fun decode(p: androidx.datastore.preferences.core.Preferences): Settings {
        val d = Settings()
        return Settings(
            serviceEnabled = p[K.serviceEnabled] ?: d.serviceEnabled,
            earDetection = p[K.earDetection] ?: d.earDetection,
            pauseOnOneBud = p[K.pauseOnOneBud] ?: d.pauseOnOneBud,
            resumeOnReinsert = p[K.resumeOnReinsert] ?: d.resumeOnReinsert,
            popupOnLidOpen = p[K.popupOnLidOpen] ?: d.popupOnLidOpen,
            autoConnectOnLidOpen = p[K.autoConnectOnLidOpen] ?: d.autoConnectOnLidOpen,
            alwaysScan = p[K.alwaysScan] ?: d.alwaysScan,
            minRssi = p[K.minRssi] ?: d.minRssi,
            lowBatteryAlert = p[K.lowBatteryAlert] ?: d.lowBatteryAlert,
            fullChargeAlert = p[K.fullChargeAlert] ?: d.fullChargeAlert,
            statusBarChip = p[K.statusBarChip] ?: d.statusBarChip,
            automationBroadcasts = p[K.automationBroadcasts] ?: d.automationBroadcasts,
            aapEnabled = p[K.aapEnabled] ?: d.aapEnabled,
            conversationDuck = p[K.conversationDuck] ?: d.conversationDuck,
            duckPercent = p[K.duckPercent] ?: d.duckPercent,
            preferredAddress = p[K.preferredAddress] ?: d.preferredAddress,
            customName = p[K.customName] ?: d.customName,
            onboardingDone = p[K.onboardingDone] ?: d.onboardingDone,
            theme = p[K.theme] ?: d.theme,
            dynamicColor = p[K.dynamicColor] ?: d.dynamicColor,
            statusBarPercent = p[K.statusBarPercent] ?: d.statusBarPercent,
            statusBarSource = p[K.statusBarSource] ?: d.statusBarSource,
            notificationActions = p[K.notificationActions] ?: d.notificationActions,
            popupOnConnect = p[K.popupOnConnect] ?: d.popupOnConnect,
            popupDurationSec = p[K.popupDurationSec] ?: d.popupDurationSec,
            popupShowOnLockScreen = p[K.popupShowOnLockScreen] ?: d.popupShowOnLockScreen,
            scanUnfiltered = p[K.scanUnfiltered] ?: d.scanUnfiltered,
            scanBalancedWhenConnected = p[K.scanBalancedWhenConnected] ?: d.scanBalancedWhenConnected,
            keepAliveWatchdog = p[K.keepAliveWatchdog] ?: d.keepAliveWatchdog,
            vibrateOnConnect = p[K.vibrateOnConnect] ?: d.vibrateOnConnect,
            chargedThreshold = p[K.chargedThreshold] ?: d.chargedThreshold,
            chargedScope = p[K.chargedScope] ?: d.chargedScope,
            autoConnectMode = p[K.autoConnectMode] ?: d.autoConnectMode,
            hiddenConnectBroken = p[K.hiddenConnectBroken] ?: d.hiddenConnectBroken,
            keepAfterDisconnect = p[K.keepAfterDisconnect] ?: d.keepAfterDisconnect,
            idleNotification = p[K.idleNotification] ?: d.idleNotification,
            hideUnmatched = p[K.hideUnmatched] ?: d.hideUnmatched,
            startMusicOnWear = p[K.startMusicOnWear] ?: d.startMusicOnWear,
            voiceAnnouncements = p[K.voiceAnnouncements] ?: d.voiceAnnouncements,
            heroVideo = p[K.heroVideo] ?: d.heroVideo,
        )
    }

    suspend fun current(): Settings = flow.first()

    /** Read-modify-write inside a single edit() transaction so concurrent updates never clobber each other. */
    suspend fun update(block: Settings.() -> Settings) {
        context.store.edit { p ->
            val s = decode(p).block()
            p[K.serviceEnabled] = s.serviceEnabled
            p[K.earDetection] = s.earDetection
            p[K.pauseOnOneBud] = s.pauseOnOneBud
            p[K.resumeOnReinsert] = s.resumeOnReinsert
            p[K.popupOnLidOpen] = s.popupOnLidOpen
            p[K.autoConnectOnLidOpen] = s.autoConnectOnLidOpen
            p[K.alwaysScan] = s.alwaysScan
            p[K.minRssi] = s.minRssi
            p[K.lowBatteryAlert] = s.lowBatteryAlert
            p[K.fullChargeAlert] = s.fullChargeAlert
            p[K.statusBarChip] = s.statusBarChip
            p[K.automationBroadcasts] = s.automationBroadcasts
            p[K.aapEnabled] = s.aapEnabled
            p[K.conversationDuck] = s.conversationDuck
            p[K.duckPercent] = s.duckPercent
            p[K.preferredAddress] = s.preferredAddress
            p[K.customName] = s.customName
            p[K.onboardingDone] = s.onboardingDone
            p[K.theme] = s.theme
            p[K.dynamicColor] = s.dynamicColor
            p[K.statusBarPercent] = s.statusBarPercent
            p[K.statusBarSource] = s.statusBarSource
            p[K.notificationActions] = s.notificationActions
            p[K.popupOnConnect] = s.popupOnConnect
            p[K.popupDurationSec] = s.popupDurationSec
            p[K.popupShowOnLockScreen] = s.popupShowOnLockScreen
            p[K.scanUnfiltered] = s.scanUnfiltered
            p[K.scanBalancedWhenConnected] = s.scanBalancedWhenConnected
            p[K.keepAliveWatchdog] = s.keepAliveWatchdog
            p[K.vibrateOnConnect] = s.vibrateOnConnect
            p[K.chargedThreshold] = s.chargedThreshold
            p[K.chargedScope] = s.chargedScope
            p[K.autoConnectMode] = s.autoConnectMode
            p[K.hiddenConnectBroken] = s.hiddenConnectBroken
            p[K.keepAfterDisconnect] = s.keepAfterDisconnect
            p[K.idleNotification] = s.idleNotification
            p[K.hideUnmatched] = s.hideUnmatched
            p[K.startMusicOnWear] = s.startMusicOnWear
            p[K.voiceAnnouncements] = s.voiceAnnouncements
            p[K.heroVideo] = s.heroVideo
        }
    }
}
