package dev.podlink.ui.screens

import android.content.Intent
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.podlink.R
import dev.podlink.data.HistoryDb
import dev.podlink.data.Sample
import dev.podlink.service.PodsRepo
import dev.podlink.ui.components.SectionCard
import dev.podlink.ui.theme.Amber
import dev.podlink.ui.theme.Mint
import dev.podlink.ui.theme.Teal
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen() {
    val ctx = LocalContext.current
    val db = remember { HistoryDb(ctx) }
    val scope = rememberCoroutineScope()
    var range by remember { mutableStateOf(24) }
    var samples by remember { mutableStateOf<List<Sample>>(emptyList()) }
    var events by remember { mutableStateOf<List<HistoryDb.Ev>>(emptyList()) }
    var rates by remember { mutableStateOf(Triple<Double?, Double?, Double?>(null, null, null)) }
    val state by PodsRepo.state.collectAsState()
    LaunchedEffect(range, state.lastUpdate / 60_000) {
        samples = db.samples(System.currentTimeMillis() - range * 3600_000L)
        events = db.events(60)
        rates = Triple(db.estimate('L').percentPerHour, db.estimate('R').percentPerHour, db.estimate('C').percentPerHour)
    }
    val fmt = remember { java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()) }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.history_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                scope.launch {
                    val all = db.samples(0)
                    val csv = buildString {
                        appendLine("timestamp,iso,left,right,case,leftCharging,rightCharging,caseCharging,leftInEar,rightInEar")
                        val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                        all.forEach { s -> appendLine("${s.ts},${iso.format(java.util.Date(s.ts))},${s.left ?: ""},${s.right ?: ""},${s.case ?: ""},${s.leftCharging},${s.rightCharging},${s.caseCharging},${s.leftInEar},${s.rightInEar}") }
                    }
                    val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_SUBJECT, "podlink-history.csv").putExtra(Intent.EXTRA_TEXT, csv)
                    ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.export_csv)))
                }
            }) { Icon(Icons.Rounded.IosShare, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.export_csv)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1 to R.string.range_1h, 6 to R.string.range_6h, 24 to R.string.range_24h, 168 to R.string.range_7d).forEach { (h, l) ->
                FilterChip(selected = range == h, onClick = { range = h }, label = { Text(stringResource(l)) })
            }
        }
        SectionCard {
            if (samples.size < 2) Text(stringResource(R.string.no_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
            else Chart(samples, range * 3600_000L)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Legend(Teal, stringResource(R.string.left)); Legend(Mint, stringResource(R.string.right)); Legend(Amber, stringResource(R.string.case_))
            }
        }
        SectionCard(stringResource(R.string.discharge_rate)) {
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                Rate(Teal, stringResource(R.string.left), rates.first)
                Rate(Mint, stringResource(R.string.right), rates.second)
                Rate(Amber, stringResource(R.string.case_), rates.third)
            }
        }
        SectionCard(stringResource(R.string.events_log)) {
            if (events.isEmpty()) Text(stringResource(R.string.no_data), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            events.forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        when {
                            e.kind.startsWith("lid") -> Icons.Rounded.Inventory2
                            e.kind == "disconnected" -> Icons.Rounded.LinkOff
                            e.kind.startsWith("aap") -> Icons.Rounded.Cable
                            else -> Icons.Rounded.Link
                        }, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(fmt.format(java.util.Date(e.ts)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(e.kind + (e.detail?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall, maxLines = 2, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(5.dp)); Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun Rate(color: Color, label: String, v: Double?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(v?.let { "%.1f".format(it) } ?: "–", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
        Text("%/h · $label", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Chart(samples: List<Sample>, spanMs: Long) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val timeFmt = remember(spanMs) { java.text.SimpleDateFormat(if (spanMs > 24 * 3600_000L) "dd.MM" else "HH:mm", java.util.Locale.getDefault()) }
    Canvas(Modifier.fillMaxWidth().height(200.dp)) {
        val left = 28.dp.toPx(); val bottom = 18.dp.toPx()
        val w = size.width - left; val h = size.height - bottom
        val t1 = System.currentTimeMillis(); val t0 = t1 - spanMs
        val paint = android.graphics.Paint().apply { color = labelColor.toArgb(); textSize = 10.dp.toPx(); isAntiAlias = true }
        for (p in listOf(0, 25, 50, 75, 100)) {
            val y = h - h * p / 100f
            drawLine(grid, Offset(left, y), Offset(left + w, y), 1f)
            drawContext.canvas.nativeCanvas.drawText("$p", 0f, y + paint.textSize / 3, paint)
        }
        for (i in 0..4) {
            val x = left + w * i / 4f
            val t = t0 + spanMs * i / 4
            val label = timeFmt.format(java.util.Date(t))
            val tw = paint.measureText(label)
            drawContext.canvas.nativeCanvas.drawText(label, (x - tw / 2).coerceIn(left, left + w - tw), size.height - 2f, paint)
        }
        fun series(color: Color, pick: (Sample) -> Int?) {
            val line = Path(); val fill = Path()
            var started = false; var lastX = -1f; var firstX = 0f
            fun closeFill() { if (started) { fill.lineTo(lastX, h); fill.lineTo(firstX, h); fill.close() } }
            for (s in samples) {
                val v = pick(s) ?: run { closeFill(); started = false; null } ?: continue
                val x = left + ((s.ts - t0).toFloat() / spanMs * w).coerceIn(0f, w)
                val y = h - h * v / 100f
                if (!started || x - lastX > w * 0.08f) {
                    closeFill()
                    line.moveTo(x, y); fill.moveTo(x, h); fill.lineTo(x, y); started = true; firstX = x
                } else { line.lineTo(x, y); fill.lineTo(x, y) }
                lastX = x
            }
            closeFill()
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.25f), color.copy(alpha = 0f)), endY = h))
            drawPath(line, color, style = Stroke(3f))
        }
        series(Amber) { it.case }; series(Mint) { it.right }; series(Teal) { it.left }
    }
}

