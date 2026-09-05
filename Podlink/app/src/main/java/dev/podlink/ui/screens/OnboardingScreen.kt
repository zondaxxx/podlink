package dev.podlink.ui.screens

import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.podlink.R
import dev.podlink.ble.PodsModel
import dev.podlink.data.Prefs
import dev.podlink.ui.components.PodsArt
import dev.podlink.ui.components.SectionCard
import dev.podlink.util.Permissions
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(prefs: Prefs, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    val btGranted = remember(tick) { Permissions.bluetoothGranted(ctx) }
    val overlay = remember(tick) { Settings.canDrawOverlays(ctx) }
    val battery = remember(tick) { ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            PodsArt(PodsModel.AIRPODS_PRO_2, leftInEar = false, rightInEar = false, lidOpen = true, size = 150.dp)
            Text(stringResource(R.string.ob_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.ob_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Step(Icons.Rounded.Bluetooth, stringResource(R.string.ob_bt), stringResource(R.string.ob_bt_desc), btGranted) { launcher.launch(Permissions.required().toTypedArray()) }
        Step(Icons.Rounded.Layers, stringResource(R.string.ob_overlay), stringResource(R.string.ob_overlay_desc), overlay) { SetupLinks.openOverlay(ctx) }
        Step(Icons.Rounded.BatteryChargingFull, stringResource(R.string.ob_battery), stringResource(R.string.ob_battery_desc), battery) { SetupLinks.openBatteryOpt(ctx) }
        SectionCard(stringResource(R.string.companion_section)) { CompanionRow() }
        SectionCard(stringResource(R.string.sec_setup)) {
            Text(stringResource(R.string.ob_xos_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = { SetupLinks.openAutostart(ctx) }, Modifier.fillMaxWidth()) { Icon(Icons.Rounded.RocketLaunch, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.set_autostart)) }
            FilledTonalButton(onClick = { SetupLinks.openBatteryLab(ctx) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.set_battery_lab)) }
            Text(stringResource(R.string.ob_xos_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick = { scope.launch { prefs.update { copy(onboardingDone = true) }; onDone() } }, modifier = Modifier.fillMaxWidth(), enabled = btGranted) {
            Text(stringResource(R.string.ob_done))
        }
    }
}

@Composable
private fun Step(icon: ImageVector, title: String, desc: String, done: Boolean, onGrant: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(28.dp), tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            if (done) Icon(Icons.Rounded.CheckCircle, stringResource(R.string.granted), tint = MaterialTheme.colorScheme.primary)
            else Button(onClick = onGrant) { Text(stringResource(R.string.grant)) }
        }
    }
}
