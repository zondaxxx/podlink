package dev.podlink.ui.components

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.podlink.R
import dev.podlink.ble.ProximityPacket
import dev.podlink.service.PodsState
import dev.podlink.ui.theme.batteryColor

fun formatMinutes(context: Context, minutes: Int?): String? = minutes?.let {
    if (it >= 60) context.getString(R.string.hours_min, it / 60, it % 60) else context.getString(R.string.minutes, it)
}

private val heroSpring = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)

/**
 * Vertical battery "pill": fills from the bottom, big animated number, bolt while charging.
 * Unknown level → dashed outline and an en dash.
 */
@Composable
fun BatteryPill(
    level: Int?,
    charging: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    minutesLeft: Int? = null,
    compact: Boolean = false,
    minutesToFull: Int? = null,
) {
    val w = if (compact) 44.dp else 64.dp
    val h = if (compact) 110.dp else 150.dp
    val fill by animateFloatAsState((level ?: 0) / 100f, heroSpring, label = "fill")
    val shown by animateIntAsState(level ?: 0, tween(500), label = "pct")
    val color = batteryColor(level)
    val track = MaterialTheme.colorScheme.surfaceVariant
    val outline = MaterialTheme.colorScheme.outline
    // The infinite transition only exists while charging, so idle pills cost no frames.
    val boltAlpha: Float = if (charging) {
        val pulse = rememberInfiniteTransition(label = "pulse")
        pulse.animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "bolt").value
    } else 1f
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(w, h), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(w, h)) {
                val r = size.width / 2
                if (level == null) {
                    drawRoundRect(outline, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
                    return@Canvas
                }
                drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
                val fh = size.height * fill
                if (fh > 0f) {
                    clipRoundRect(r) {
                        drawRect(
                            Brush.verticalGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.65f)), startY = size.height - fh, endY = size.height),
                            topLeft = Offset(0f, size.height - fh), size = Size(size.width, fh),
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (level == null) "–" else "$shown",
                    fontSize = if (compact) 18.sp else 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                if (level != null) Text("%", fontSize = if (compact) 10.sp else 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (charging) Icon(Icons.Rounded.Bolt, null, Modifier.size(if (compact) 14.dp else 18.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onSurface.copy(alpha = boltAlpha))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val ctx = androidx.compose.ui.platform.LocalContext.current
        if (charging && minutesToFull != null && minutesToFull > 0) {
            Text("⚡ ≈ " + formatMinutes(ctx, minutesToFull), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        } else formatMinutes(ctx, minutesLeft)?.let {
            Text("≈ $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.clipRoundRect(radius: Float, block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
    val path = androidx.compose.ui.graphics.Path().apply {
        addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, radius, radius))
    }
    clipPath(path) { block() }
}

/** Animated ring with the percentage inside; used by the popup and widgets. */
@Composable
fun BatteryRing(level: Int?, charging: Boolean, label: String, size: Dp = 96.dp, stroke: Dp = 8.dp, sub: String? = null) {
    val progress by animateFloatAsState((level ?: 0) / 100f, heroSpring, label = "ring")
    val shown by animateIntAsState(level ?: 0, tween(500), label = "ringPct")
    val color = batteryColor(level)
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(size)) {
                val sw = stroke.toPx()
                val inset = sw / 2
                val arcSize = Size(this.size.width - sw, this.size.height - sw)
                drawArc(track, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(sw, cap = StrokeCap.Round))
                if (level != null) drawArc(color, -90f, 360f * progress, false, Offset(inset, inset), arcSize, style = Stroke(sw, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (level == null) "–" else "$shown",
                    fontSize = (size.value / 3.6f).sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
                )
                if (charging) Icon(Icons.Rounded.Bolt, null, Modifier.size((size.value / 5f).dp), tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun StatusChip(text: String, icon: ImageVector? = null, active: Boolean = false, modifier: Modifier = Modifier) {
    val bg = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(14.dp), tint = fg); Spacer(Modifier.width(4.dp)) }
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

/** Product art + battery pills + status chips. Everything animates when the state changes. */
@Composable
fun HeroCard(state: PodsState, modifier: Modifier = Modifier, artSize: Dp = 200.dp) {
    val s = state
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PodsArt(s.model, s.leftInEar || s.isHeadphones, s.rightInEar || s.isHeadphones, s.lidOpen, artSize)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Bottom) {
                fun eta(level: Int?): Int? = level?.let { (100 - it) * s.model.podFullChargeMinutes / 100 }
                if (s.isHeadphones) {
                    BatteryPill(s.single, s.leftCharging, s.model.label, minutesLeft = s.leftMinutes, minutesToFull = eta(s.single))
                } else {
                    BatteryPill(s.left, s.leftCharging, stringResource(R.string.left), minutesLeft = s.leftMinutes, minutesToFull = eta(s.left))
                    BatteryPill(s.case, s.caseCharging, stringResource(R.string.case_), minutesLeft = null, compact = true, minutesToFull = s.case?.let { (100 - it) * 90 / 100 })
                    BatteryPill(s.right, s.rightCharging, stringResource(R.string.right), minutesLeft = s.rightMinutes, minutesToFull = eta(s.right))
                }
            }
            StatusChips(s)
        }
    }
}

@Composable
fun StatusChips(s: PodsState) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        AnimatedContent(s.lidState, transitionSpec = { (fadeIn() + slideInVertically { it / 2 }) togetherWith (fadeOut() + slideOutVertically { -it / 2 }) }, label = "lid") { lid ->
            when (lid) {
                ProximityPacket.LidState.OPEN -> StatusChip(stringResource(R.string.lid_open), Icons.Rounded.Inventory2, active = true)
                ProximityPacket.LidState.CLOSED -> StatusChip(stringResource(R.string.lid_closed), Icons.Rounded.Inventory2)
                else -> {}
            }
        }
        if (!s.isHeadphones) {
            StatusChip("L", Icons.Rounded.Hearing, active = s.leftInEar)
            StatusChip("R", Icons.Rounded.Hearing, active = s.rightInEar)
            if (s.connected) StatusChip(if (s.leftIsMicrophone) "L" else "R", Icons.Rounded.Mic)
        }
        when (s.connectionState) {
            ProximityPacket.ConnectionState.MUSIC -> StatusChip(stringResource(R.string.state_music), Icons.Rounded.MusicNote, active = true)
            ProximityPacket.ConnectionState.CALL, ProximityPacket.ConnectionState.RINGING -> StatusChip(stringResource(R.string.state_call), Icons.Rounded.Phone, active = true)
            else -> {}
        }
        if (!s.isHeadphones && s.case != null && s.model.caseRecharges > 0) {
            val charges = s.case * s.model.caseRecharges / 100
            StatusChip(stringResource(R.string.case_charges, charges), Icons.Rounded.BatteryChargingFull)
        }
        s.rssi?.let { SignalBars(it) }
    }
}

/** Four-bar signal indicator from RSSI (-95 … -45 dBm). */
@Composable
fun SignalBars(rssi: Int, modifier: Modifier = Modifier) {
    val bars = when { rssi >= -55 -> 4; rssi >= -65 -> 3; rssi >= -75 -> 2; rssi >= -88 -> 1; else -> 0 }
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.outline
    Row(modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..4) Box(Modifier.width(4.dp).height((4 + i * 3).dp).clip(RoundedCornerShape(2.dp)).background(if (i <= bars) on else off))
    }
}

@Suppress("unused")
private val unusedIcon = Icons.Rounded.SignalCellularAlt
