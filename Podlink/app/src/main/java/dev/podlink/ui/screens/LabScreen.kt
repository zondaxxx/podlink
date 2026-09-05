package dev.podlink.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.podlink.R
import dev.podlink.aap.AapClient
import dev.podlink.service.PodsRepo
import dev.podlink.service.PodsService
import kotlinx.coroutines.flow.MutableStateFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabScreen() {
    val ctx = LocalContext.current
    val svc = PodsRepo.service
    val s by PodsRepo.state.collectAsState()
    val log by (svc?.aap?.log ?: remember { MutableStateFlow(emptyList<String>()) }).collectAsState()
    val connected = s.aapState == AapClient.State.CONNECTED

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.lab_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        BleDiagnostics()
        RootCard()
        var aapOpen by remember { mutableStateOf(s.aapState == AapClient.State.CONNECTED) }
        Card(onClick = { aapOpen = !aapOpen }) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.lab_aap_section), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(if (aapOpen) "▲" else "▼", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (s.aapState == AapClient.State.UNSUPPORTED) Text(stringResource(R.string.lab_aap_blocked), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Text(stringResource(R.string.lab_intro), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!aapOpen) return@Column

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        when (s.aapState) {
                            AapClient.State.DISCONNECTED -> R.string.aap_disconnected
                            AapClient.State.CONNECTING -> R.string.aap_connecting
                            AapClient.State.CONNECTED -> R.string.aap_connected
                            AapClient.State.UNSUPPORTED -> R.string.aap_unsupported
                        },
                    ),
                    fontWeight = FontWeight.SemiBold,
                    color = if (connected) MaterialTheme.colorScheme.primary else if (s.aapState == AapClient.State.UNSUPPORTED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = s.connected && !connected, onClick = { ctx.startService(android.content.Intent(ctx, PodsService::class.java).setAction(PodsService.ACTION_AAP_RETRY)) }) { Text(stringResource(R.string.btn_retry)) }
                    OutlinedButton(enabled = connected, onClick = { svc?.aap?.disconnect() }) { Text(stringResource(R.string.btn_disconnect)) }
                }
            }
        }

        if (connected) Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                var ca by remember { mutableStateOf(false) }
                var ear by remember { mutableStateOf(true) }
                var adaptive by remember { mutableStateOf(50f) }
                var name by remember { mutableStateOf("") }
                var ctlId by remember { mutableStateOf("0D") }
                var ctlVal by remember { mutableStateOf("1") }

                if (s.model.supportsConversationAwareness || s.model == dev.podlink.ble.PodsModel.UNKNOWN) ToggleRow(stringResource(R.string.ca_toggle), ca) { ca = it; svc?.aap?.setConversationAwareness(it) }
                ToggleRow(stringResource(R.string.ear_toggle), ear) { ear = it; svc?.aap?.setEarDetection(it) }
                if (s.model.supportsAdaptive || s.model == dev.podlink.ble.PodsModel.UNKNOWN) {
                    Text(stringResource(R.string.adaptive_level) + ": ${adaptive.toInt()}")
                    Slider(value = adaptive, onValueChange = { adaptive = it }, onValueChangeFinished = { svc?.aap?.setAdaptiveLevel(adaptive.toInt()) }, valueRange = 0f..100f)
                }
                Text(stringResource(R.string.rename), fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.rename_hint)) }, singleLine = true)
                    Button(enabled = name.isNotBlank(), onClick = { svc?.aap?.rename(name.trim()) }) { Text(stringResource(R.string.send)) }
                }
                Text(stringResource(R.string.send_raw), fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(ctlId, { ctlId = it }, Modifier.width(90.dp), label = { Text(stringResource(R.string.ctl_id)) }, singleLine = true)
                    OutlinedTextField(ctlVal, { ctlVal = it }, Modifier.width(90.dp), label = { Text(stringResource(R.string.ctl_value)) }, singleLine = true)
                    Button(onClick = {
                        val id = ctlId.trim().removePrefix("0x").toIntOrNull(16); val v = ctlVal.trim().toIntOrNull()
                        if (id != null && v != null) svc?.aap?.sendControl(id, v)
                    }) { Text(stringResource(R.string.send)) }
                }
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { svc?.aap?.clearLog() }) { Text(stringResource(R.string.clear_log)) }
                    OutlinedButton(onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("podlink", log.joinToString("\n")))
                        Toast.makeText(ctx, R.string.log_copied, Toast.LENGTH_SHORT).show()
                    }) { Text(stringResource(R.string.copy_log)) }
                }
                log.asReversed().forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun RootCard() {
    val ctx = LocalContext.current
    val su = remember { dev.podlink.util.RootDiag.suAvailable() }
    val libs = remember { dev.podlink.util.RootDiag.libInfo() }
    var msg by remember { mutableStateOf<String?>(null) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.root_title), fontWeight = FontWeight.SemiBold)
            Text(stringResource(if (su) R.string.root_yes else R.string.root_no), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(dev.podlink.util.RootDiag.fingerprint(), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            libs.forEach { Text("${it.path}  ${it.size / 1024} KB${if (it.readable) "" else "  (needs su)"}", fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
            Text(stringResource(R.string.root_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = libs.isNotEmpty(), onClick = { msg = dev.podlink.util.RootDiag.shareLib(ctx) }) { Text(stringResource(R.string.root_share_lib)) }
            }
            msg?.takeIf { it != "ok" }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun BleDiagnostics() {
    val svc = PodsRepo.service
    val s by PodsRepo.state.collectAsState()
    val stats by (svc?.scanner?.stats ?: remember { MutableStateFlow(dev.podlink.ble.PodsScanner.Stats()) }).collectAsState()
    val locked by (svc?.scanner?.locked ?: remember { MutableStateFlow(null) }).collectAsState()
    val ctx = LocalContext.current
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.ble_diag), fontWeight = FontWeight.SemiBold)
            val mono = FontFamily.Monospace
            Text("scan=${if (s.scanning) "on" else "off"} unfiltered=${stats.unfiltered} apple=${stats.appleFrames} pods=${stats.packets} err=${stats.lastError ?: "-"}", fontFamily = mono, fontSize = 11.sp)
            Text("expected=${svc?.scanner?.expectedModel} lock=${locked?.address ?: "-"} rssi=${locked?.rssi ?: "-"}", fontFamily = mono, fontSize = 11.sp)
            stats.beacons.values.sortedByDescending { it.rssi }.take(6).forEach { b ->
                val isLocked = b.ours
                val p = b.packet
                Text(
                    "${if (isLocked) "★ " else ""}${b.address} ${b.model.label}(0x%04X) ${b.rssi}dBm  L${p.left ?: "–"} R${p.right ?: "–"} C${p.case ?: "–"} lid=${p.lidState.name.lowercase()}${if (p.thisPodInCase) " inCase" else ""}".format(b.rawModelId),
                    fontFamily = mono, fontSize = 11.sp, color = if (isLocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text("  ${b.rawHex.take(33)}", fontFamily = mono, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = {
                val txt = buildString {
                    appendLine("Podlink ${dev.podlink.BuildConfig.VERSION_NAME} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}")
                    appendLine("state=$s")
                    appendLine("stats=${stats.copy(beacons = emptyMap())}")
                    stats.beacons.values.forEach { appendLine("${it.address} ${it.model} 0x%04X ${it.rssi} ${it.rawHex}".format(it.rawModelId)) }
                    appendLine("--- AAP log ---")
                    (svc?.aap?.log?.value ?: emptyList()).forEach { appendLine(it) }
                }
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_SUBJECT, "Podlink diagnostics").putExtra(android.content.Intent.EXTRA_TEXT, txt)
                ctx.startActivity(android.content.Intent.createChooser(send, ctx.getString(R.string.share_diag)))
            }) { Text(stringResource(R.string.share_diag)) }
            OutlinedButton(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val txt = buildString {
                    appendLine("state=$s")
                    appendLine("stats=${stats.copy(beacons = emptyMap())}")
                    stats.beacons.values.forEach { appendLine("${it.address} ${it.model} 0x%04X ${it.rssi} ${it.rawHex}".format(it.rawModelId)) }
                }
                cm.setPrimaryClip(ClipData.newPlainText("podlink-ble", txt))
                Toast.makeText(ctx, R.string.log_copied, Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.copy_log)) }
        }
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}
