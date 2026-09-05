package dev.podlink.ui.screens

import android.annotation.SuppressLint
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.podlink.BuildConfig
import dev.podlink.R
import dev.podlink.data.Prefs
import dev.podlink.data.Settings
import dev.podlink.service.PodsRepo
import dev.podlink.service.PodsService
import dev.podlink.service.Watchdog
import dev.podlink.ui.components.SectionCard
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
@SuppressLint("MissingPermission")
@Composable
fun SettingsScreen(prefs: Prefs) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by prefs.flow.collectAsState(initial = Settings())
    fun set(block: Settings.() -> Settings) = scope.launch { prefs.update(block) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))

        Section(Icons.Rounded.Palette, stringResource(R.string.sec_appearance)) {
            Choice(stringResource(R.string.set_theme), listOf("system" to R.string.theme_system, "dark" to R.string.theme_dark, "light" to R.string.theme_light), s.theme) { set { copy(theme = it) } }
            if (Build.VERSION.SDK_INT >= 31) Toggle(stringResource(R.string.set_dynamic), stringResource(R.string.set_dynamic_desc), s.dynamicColor) { set { copy(dynamicColor = it) } }
            Toggle(stringResource(R.string.set_hero_video), stringResource(R.string.set_hero_video_desc), s.heroVideo) { set { copy(heroVideo = it) } }
        }

        Section(Icons.Rounded.Notifications, stringResource(R.string.sec_notification)) {
            Toggle(stringResource(R.string.set_status_percent), stringResource(R.string.set_status_percent_desc), s.statusBarPercent) { set { copy(statusBarPercent = it) } }
            if (s.statusBarPercent) Choice(stringResource(R.string.set_status_source), listOf("lowest" to R.string.src_lowest, "left" to R.string.left, "right" to R.string.right, "case" to R.string.case_), s.statusBarSource) { set { copy(statusBarSource = it) } }
            Toggle(stringResource(R.string.set_notif_actions), null, s.notificationActions) { set { copy(notificationActions = it) } }
            Toggle(stringResource(R.string.set_keep_after), stringResource(R.string.set_keep_after_desc), s.keepAfterDisconnect) { set { copy(keepAfterDisconnect = it) } }
            Toggle(stringResource(R.string.set_idle_notif), stringResource(R.string.set_idle_notif_desc), s.idleNotification) { set { copy(idleNotification = it) } }
            if (Build.VERSION.SDK_INT >= 36) {
                Toggle(stringResource(R.string.set_chip), stringResource(R.string.set_chip_desc), s.statusBarChip) { set { copy(statusBarChip = it) } }
                Link(stringResource(R.string.set_live_updates)) { SetupLinks.openLiveUpdates(ctx) }
            }
            Link(stringResource(R.string.set_channels)) { SetupLinks.openNotificationChannels(ctx) }
        }

        Section(Icons.Rounded.Hearing, stringResource(R.string.sec_ear)) {
            Toggle(stringResource(R.string.set_ear), null, s.earDetection) { set { copy(earDetection = it) } }
            Toggle(stringResource(R.string.set_one_bud), stringResource(R.string.set_one_bud_desc), s.pauseOnOneBud, enabled = s.earDetection) { set { copy(pauseOnOneBud = it) } }
            Toggle(stringResource(R.string.set_resume), null, s.resumeOnReinsert, enabled = s.earDetection) { set { copy(resumeOnReinsert = it) } }
            Toggle(stringResource(R.string.set_start_on_wear), stringResource(R.string.set_start_on_wear_desc), s.startMusicOnWear) { set { copy(startMusicOnWear = it) } }
        }

        Section(Icons.Rounded.Inventory2, stringResource(R.string.sec_popup)) {
            Toggle(stringResource(R.string.set_popup_lid), null, s.popupOnLidOpen) { set { copy(popupOnLidOpen = it) } }
            Toggle(stringResource(R.string.set_popup_connect), null, s.popupOnConnect) { set { copy(popupOnConnect = it) } }
            SliderRow(stringResource(R.string.set_popup_duration), s.popupDurationSec.toFloat(), 3f..15f, "${s.popupDurationSec} s", steps = 11) { set { copy(popupDurationSec = it.toInt()) } }
            Toggle(stringResource(R.string.set_popup_lock), null, s.popupShowOnLockScreen) { set { copy(popupShowOnLockScreen = it) } }
            Link(stringResource(R.string.set_overlay)) { SetupLinks.openOverlay(ctx) }
        }

        Section(Icons.Rounded.Bluetooth, stringResource(R.string.sec_autoconnect)) {
            Toggle(stringResource(R.string.set_autoconnect), stringResource(R.string.set_autoconnect_desc), s.autoConnectOnLidOpen) { set { copy(autoConnectOnLidOpen = it) } }
            if (s.autoConnectOnLidOpen) Choice(stringResource(R.string.set_autoconnect_mode), listOf("lid" to R.string.ac_lid, "seen" to R.string.ac_seen, "inear" to R.string.ac_inear), s.autoConnectMode) { set { copy(autoConnectMode = it) } }
            if (s.hiddenConnectBroken) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.set_hidden_broken), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { set { copy(hiddenConnectBroken = false) } }) { Text(stringResource(R.string.btn_retry)) }
            }
            Toggle(stringResource(R.string.set_vibrate_connect), null, s.vibrateOnConnect) { set { copy(vibrateOnConnect = it) } }
            Toggle(stringResource(R.string.set_voice), stringResource(R.string.set_voice_desc), s.voiceAnnouncements) { set { copy(voiceAnnouncements = it) } }
        }

        Section(Icons.Rounded.NotificationsActive, stringResource(R.string.sec_alerts)) {
            SliderRow(stringResource(R.string.set_low), s.lowBatteryAlert.toFloat(), 5f..50f, "${s.lowBatteryAlert}%", steps = 8) { set { copy(lowBatteryAlert = (it / 5).toInt() * 5) } }
            Toggle(stringResource(R.string.set_full), null, s.fullChargeAlert) { set { copy(fullChargeAlert = it) } }
            if (s.fullChargeAlert) {
                SliderRow(stringResource(R.string.set_charged_threshold), s.chargedThreshold.toFloat(), 50f..100f, "${s.chargedThreshold}%", steps = 4) { set { copy(chargedThreshold = (it / 10).toInt() * 10) } }
                Choice(stringResource(R.string.set_charged_scope), listOf("pods" to R.string.scope_pods, "case" to R.string.case_, "both" to R.string.scope_both), s.chargedScope) { set { copy(chargedScope = it) } }
            }
        }

        Section(Icons.Rounded.Radar, stringResource(R.string.sec_scan)) {
            SliderRow(stringResource(R.string.set_min_rssi), s.minRssi.toFloat(), -95f..-45f, "${s.minRssi} dBm", steps = 9) { set { copy(minRssi = it.toInt()) } }
            Toggle(stringResource(R.string.set_always_scan), stringResource(R.string.set_always_scan_desc), s.alwaysScan) { set { copy(alwaysScan = it) } }
            Toggle(stringResource(R.string.set_balanced), stringResource(R.string.set_balanced_desc), s.scanBalancedWhenConnected) { set { copy(scanBalancedWhenConnected = it) } }
            Toggle(stringResource(R.string.set_unfiltered), stringResource(R.string.set_unfiltered_desc), s.scanUnfiltered) { set { copy(scanUnfiltered = it) } }
            Toggle(stringResource(R.string.set_hide_unmatched), null, s.hideUnmatched) { set { copy(hideUnmatched = it) } }
        }

        Section(Icons.Rounded.Shield, stringResource(R.string.sec_reliability)) {
            Toggle(stringResource(R.string.set_service), null, s.serviceEnabled) { on -> set { copy(serviceEnabled = on) }; if (on) PodsService.start(ctx) else PodsService.stop(ctx) }
            Toggle(stringResource(R.string.set_watchdog), stringResource(R.string.set_watchdog_desc), s.keepAliveWatchdog) { on -> set { copy(keepAliveWatchdog = on) }; if (on) Watchdog.schedule(ctx) else Watchdog.cancel(ctx) }
            CompanionRow()
            Link(stringResource(R.string.set_battery_opt), done = Watchdog.batteryExempt(ctx)) { SetupLinks.openBatteryOpt(ctx) }
            Link(stringResource(R.string.set_autostart)) { SetupLinks.openAutostart(ctx) }
            Link(stringResource(R.string.set_battery_lab)) { SetupLinks.openBatteryLab(ctx) }
            Link(stringResource(R.string.set_app_info)) { SetupLinks.openAppInfo(ctx) }
            val stats = remember { dev.podlink.service.ServiceStats.snapshot(ctx) }
            val fmt = remember { java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()) }
            Text(
                stringResource(R.string.stats_line, stats.starts, stats.killed, if (stats.lastStart > 0) fmt.format(java.util.Date(stats.lastStart)) else "–"),
                style = MaterialTheme.typography.bodySmall, color = if (stats.killed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(stringResource(R.string.xos_checklist), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Section(Icons.Rounded.Headphones, stringResource(R.string.sec_device)) {
            val bonded = remember { PodsRepo.service?.monitor?.bondedPods()?.map { it.name to it.address } ?: emptyList() }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = s.preferredAddress.isBlank(), onClick = { set { copy(preferredAddress = "") } }, label = { Text(stringResource(R.string.set_device_any)) })
                bonded.forEach { (n, a) -> FilterChip(selected = s.preferredAddress.equals(a, true), onClick = { set { copy(preferredAddress = a) } }, label = { Text(n ?: a) }) }
            }
            var name by remember(s.customName) { mutableStateOf(s.customName) }
            LaunchedEffect(Unit) { snapshotFlow { name }.debounce(600).collect { v -> if (v != s.customName) prefs.update { copy(customName = v) } } }
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.set_name)) }, singleLine = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.set_alias_desc), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(enabled = name.isNotBlank(), onClick = {
                    val ok = PodsRepo.service?.monitor?.setSystemAlias(s.preferredAddress.ifBlank { PodsRepo.state.value.address ?: "" }, name.trim()) == true
                    android.widget.Toast.makeText(ctx, if (ok) R.string.alias_ok else R.string.alias_fail, android.widget.Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.set_alias)) }
            }
        }

        Section(Icons.Rounded.SmartToy, stringResource(R.string.sec_automation)) {
            Toggle(stringResource(R.string.set_broadcasts), null, s.automationBroadcasts) { set { copy(automationBroadcasts = it) } }
            Text(stringResource(R.string.automation_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Section(Icons.Rounded.Cable, stringResource(R.string.sec_aap)) {
            Toggle(stringResource(R.string.set_aap), stringResource(R.string.set_aap_desc), s.aapEnabled) { set { copy(aapEnabled = it) } }
            Toggle(stringResource(R.string.set_duck), null, s.conversationDuck, enabled = s.aapEnabled) { set { copy(conversationDuck = it) } }
            SliderRow(stringResource(R.string.set_duck_pct), s.duckPercent.toFloat(), 0f..90f, "${s.duckPercent}%", steps = 8) { set { copy(duckPercent = (it / 10).toInt() * 10) } }
        }

        Section(Icons.Rounded.Info, stringResource(R.string.sec_about)) {
            Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Podlink ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.size(8.dp))
    }
}

@Composable
private fun Section(icon: ImageVector, title: String, content: @Composable () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        content()
    }
}

@Composable
private fun Toggle(label: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, steps: Int = 0, onFinished: (Float) -> Unit) {
    var v by remember(value) { mutableStateOf(value) }
    Column {
        Row { Text(label, Modifier.weight(1f)); Text(display, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
        Slider(value = v, onValueChange = { v = it }, onValueChangeFinished = { onFinished(v) }, valueRange = range, steps = steps)
    }
}

@Composable
private fun Choice(label: String, options: List<Pair<String, Int>>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (options.size <= 3) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (key, res) ->
                SegmentedButton(selected = selected == key, onClick = { onSelect(key) }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(stringResource(res), maxLines = 1) }
            }
        } else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (key, res) -> FilterChip(selected = selected == key, onClick = { onSelect(key) }, label = { Text(stringResource(res)) }) }
        }
    }
}

@Composable
private fun Link(label: String, done: Boolean? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        if (done == true) Text(stringResource(R.string.granted), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Suppress("unused") private val unusedIcon = Icons.Rounded.AutoAwesome
