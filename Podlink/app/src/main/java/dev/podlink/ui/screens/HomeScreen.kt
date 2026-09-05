package dev.podlink.ui.screens

import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Timeline
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.WebAsset
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.podlink.R
import dev.podlink.aap.AapClient
import dev.podlink.aap.AapProtocol
import dev.podlink.ble.PodsModel
import dev.podlink.data.Prefs
import dev.podlink.data.Settings
import dev.podlink.service.PodsRepo
import dev.podlink.service.PodsService
import dev.podlink.service.PodsState
import dev.podlink.service.Source
import dev.podlink.ui.components.HeroCard
import dev.podlink.ui.components.SectionCard
import dev.podlink.ui.components.SignalBars
import dev.podlink.ui.theme.batteryColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(prefs: Prefs, onNavigate: (String) -> Unit = {}, openRadar: Boolean = false) {
    val ctx = LocalContext.current
    val s by PodsRepo.state.collectAsState()
    val settings by prefs.flow.collectAsState(initial = Settings())
    val events = remember { mutableStateListOf<Pair<Long, String>>() }
    LaunchedEffect(Unit) { PodsRepo.events.collect { events.add(0, System.currentTimeMillis() to it); if (events.size > 30) events.removeAt(events.size - 1) } }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(5_000); tick++ } }
    var radarOpen by remember { mutableStateOf(openRadar) }
    LaunchedEffect(openRadar) { if (openRadar) radarOpen = true }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Header(s)
        if (!s.serviceRunning) Banner(Icons.Rounded.Warning, stringResource(R.string.service_off), stringResource(R.string.start_service), error = true) { PodsService.start(ctx) }
        if (!s.bluetoothOn) Banner(Icons.Rounded.BluetoothDisabled, stringResource(R.string.bt_off), null, error = true) {}
        if (s.nearbyNotConnected && !s.connected) Banner(Icons.Rounded.Bluetooth, stringResource(R.string.status_nearby), stringResource(R.string.btn_connect)) {
            ctx.startService(Intent(ctx, PodsService::class.java).setAction(PodsService.ACTION_CONNECT_NOW))
        }
        HeroCard(s, video = settings.heroVideo)
        QuickActions(s, radarOpen, onRadar = { radarOpen = !radarOpen }, onNavigate = onNavigate)
        if (s.connected && s.aapState == AapClient.State.CONNECTED) AncCard(s)
        if (s.conversationLevel != null && s.conversationLevel!! <= 3) Banner(Icons.Rounded.Hearing, stringResource(R.string.conversation_now), null) {}
        AnimatedVisibility(radarOpen) { RadarCard(s) }
        if (!settings.hideUnmatched && s.nearby.isNotEmpty()) NearbyCard(s)
        EventsCard(events)
        Footer(s, tick)
    }
}

@Composable
private fun Header(s: PodsState) {
    val statusColor by animateColorAsState(
        when { s.connected -> MaterialTheme.colorScheme.primary; s.nearbyNotConnected -> MaterialTheme.colorScheme.tertiary; else -> MaterialTheme.colorScheme.onSurfaceVariant },
        tween(400), label = "status",
    )
    Column(Modifier.padding(top = 8.dp)) {
        Text(s.deviceName ?: s.model.label, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        val status = when {
            s.connected -> stringResource(R.string.status_connected)
            s.nearbyNotConnected -> stringResource(R.string.status_nearby)
            else -> stringResource(R.string.status_not_connected)
        }
        val src = when (s.source) { Source.AAP -> " · " + stringResource(R.string.source_aap); Source.BLE -> " · " + stringResource(R.string.source_ble); else -> "" }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (s.connected) Icons.Rounded.Link else Icons.Rounded.LinkOff, null, Modifier.size(16.dp), tint = statusColor)
            Spacer(Modifier.width(6.dp))
            Text(status + src, color = statusColor, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Banner(icon: ImageVector, text: String, action: String?, error: Boolean = false, onAction: () -> Unit) {
    val bg = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
    val fg = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = bg) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = fg)
            Spacer(Modifier.width(12.dp))
            Text(text, Modifier.weight(1f), color = fg, style = MaterialTheme.typography.bodyMedium)
            if (action != null) TextButton(onClick = onAction) { Text(action, color = fg, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun QuickActions(s: PodsState, radarOpen: Boolean, onRadar: () -> Unit, onNavigate: (String) -> Unit) {
    val ctx = LocalContext.current
    val svc = PodsRepo.service
    val hasBonded = remember(svc) { svc?.monitor?.bondedPods()?.isNotEmpty() == true }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!s.connected && hasBonded) Action(Icons.Rounded.Bluetooth, stringResource(R.string.btn_connect_short), Modifier.weight(1f), primary = true) {
            ctx.startService(Intent(ctx, PodsService::class.java).setAction(PodsService.ACTION_CONNECT_NOW))
        }
        Action(Icons.Rounded.Radar, stringResource(R.string.act_find), Modifier.weight(1f), primary = radarOpen, onClick = onRadar)
        Action(Icons.Rounded.WebAsset, stringResource(R.string.act_popup), Modifier.weight(1f)) {
            ctx.startService(Intent(ctx, PodsService::class.java).setAction(PodsService.ACTION_SHOW_POPUP))
        }
        Action(Icons.Rounded.Timeline, stringResource(R.string.nav_history), Modifier.weight(1f)) { onNavigate("history") }
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    val bg = if (primary) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val fg = if (primary) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(18.dp), color = bg) {
        Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, tint = fg)
            Text(label, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
        }
    }
}

@Composable
private fun AncCard(s: PodsState) {
    val svc = PodsRepo.service
    SectionCard(stringResource(R.string.anc_title)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val modes = buildList {
                add(AapProtocol.NoiseMode.OFF to R.string.anc_off)
                add(AapProtocol.NoiseMode.ANC to R.string.anc_on)
                add(AapProtocol.NoiseMode.TRANSPARENCY to R.string.anc_transparency)
                if (s.model.supportsAdaptive || s.model == PodsModel.UNKNOWN) add(AapProtocol.NoiseMode.ADAPTIVE to R.string.anc_adaptive)
            }
            modes.forEach { (m, label) ->
                androidx.compose.material3.FilterChip(selected = s.noiseMode == m, onClick = { svc?.aap?.setNoiseMode(m) }, label = { Text(stringResource(label)) })
            }
        }
    }
}

/** RSSI radar: fast scan while visible, haptic pulses scale with proximity. */
@Composable
private fun RadarCard(s: PodsState) {
    val ctx = LocalContext.current
    val svc = PodsRepo.service
    var rssi by remember { mutableStateOf<Int?>(null) }
    var lastSeen by remember { mutableStateOf(0L) }
    DisposableEffect(svc) {
        svc?.requestAggressiveScan(true)
        onDispose { svc?.requestAggressiveScan(false) }
    }
    LaunchedEffect(svc) {
        val sc = svc?.scanner ?: return@LaunchedEffect
        launch {
            sc.allPackets.filter { p -> sc.isOurs(p.address) || sc.expectedModel.sameFamily(p.model) }
                .collect { p -> rssi = (rssi?.let { (it * 0.6 + p.rssi * 0.4).toInt() } ?: p.rssi); lastSeen = System.currentTimeMillis() }
        }
        val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        while (true) {
            val r = rssi
            if (r != null && System.currentTimeMillis() - lastSeen < 4000) {
                val strength = ((r + 95) / 60f).coerceIn(0f, 1f)
                runCatching { vib.vibrate(VibrationEffect.createOneShot((20 + 60 * strength).toLong(), (60 + 190 * strength).toInt())) }
                delay((1400 - 1200 * strength).toLong())
            } else { rssi = null; delay(500) }
        }
    }
    SectionCard(stringResource(R.string.radar_title)) {
        val r = rssi
        val strength by animateFloatAsState(r?.let { ((it + 95) / 60f).coerceIn(0f, 1f) } ?: 0f, tween(400), label = "strength")
        Box(Modifier.fillMaxWidth().height(28.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(strength.coerceAtLeast(0.02f)).height(28.dp).clip(RoundedCornerShape(14.dp)).background(batteryColor((strength * 100).toInt())))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (r == null) stringResource(R.string.radar_no_signal) else "${(strength * 100).toInt()}%", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (r != null) { SignalBars(r); Text("$r dBm", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(stringResource(R.string.radar_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NearbyCard(s: PodsState) {
    SectionCard(stringResource(R.string.nearby_title)) {
        s.nearby.forEach { d ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.Headphones, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(d.model.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        listOfNotNull(d.left?.let { "L $it%" }, d.right?.let { "R $it%" }, d.case?.let { "C $it%" }).joinToString(" · ").ifEmpty { "–" },
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SignalBars(d.rssi)
            }
        }
        Text(stringResource(R.string.nearby_hint), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EventsCard(events: List<Pair<Long, String>>) {
    if (events.isEmpty()) return
    val fmt = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()) }
    SectionCard(stringResource(R.string.recent_events)) {
        events.take(8).forEach { (ts, e) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(eventIcon(e), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(fmt.format(java.util.Date(ts)), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(8.dp))
                Text(e, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
        }
    }
}

@Composable
private fun eventIcon(e: String): ImageVector {
    val lid = stringResource(R.string.ev_lid_open); val lidC = stringResource(R.string.ev_lid_closed)
    val paused = stringResource(R.string.ev_paused); val resumed = stringResource(R.string.ev_resumed)
    val disc = stringResource(R.string.ev_disconnected)
    return when {
        e == lid || e == lidC -> Icons.Rounded.Inventory2
        e == paused -> Icons.Rounded.Pause
        e == resumed -> Icons.Rounded.PlayArrow
        e == disc -> Icons.Rounded.LinkOff
        e.contains("AAP") -> Icons.Rounded.Cable
        else -> Icons.Rounded.Link
    }
}

@Composable
private fun Footer(s: PodsState, tick: Int) {
    if (s.lastUpdate == 0L) return
    val ago = remember(tick, s.lastUpdate) { ((System.currentTimeMillis() - s.lastUpdate) / 1000).toInt() }
    Text(
        stringResource(R.string.model_label) + ": " + s.model.label + "  ·  " +
            (if (ago < 10) stringResource(R.string.just_now) else stringResource(R.string.last_update, if (ago < 60) "${ago}s" else "${ago / 60}m")),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    if (s.scanning.not() && s.serviceRunning) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
}
