package dev.podlink.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.podlink.ble.PodsModel

/**
 * Product illustration per model family, drawn as vector art with gradients and animated:
 * the lid swings open on a spring, buds rise and glow when they are in the ear.
 *
 * If a drawable named `pods_<family>` (e.g. `pods_airpods_pro.png` in res/drawable-nodpi) exists, it is shown
 * instead, so real renders can be dropped in without touching code.
 */
@Composable
fun PodsArt(model: PodsModel, leftInEar: Boolean, rightInEar: Boolean, lidOpen: Boolean, size: Dp = 200.dp) {
    val ctx = LocalContext.current
    val family = model.family
    val resId = remember(family) { ctx.resources.getIdentifier("pods_" + family.name.lowercase(), "drawable", ctx.packageName) }
    if (resId != 0) {
        Image(painterResource(resId), null, Modifier.size(size), contentScale = ContentScale.Fit)
        return
    }
    val softSpring = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
    val lidT by animateFloatAsState(if (lidOpen) 1f else 0f, softSpring, label = "lid")
    val leftT by animateFloatAsState(if (leftInEar) 1f else 0f, softSpring, label = "left")
    val rightT by animateFloatAsState(if (rightInEar) 1f else 0f, softSpring, label = "right")
    val dark = isSystemInDarkTheme()
    val accent = MaterialTheme.colorScheme.primary
    val palette = remember(dark, accent) { Palette(dark, accent) }
    Box(Modifier.size(size)) {
        Canvas(Modifier.size(size)) {
            when (family) {
                PodsModel.Family.AIRPODS_MAX -> drawOverEar(palette, white = true)
                PodsModel.Family.BEATS_OVER -> drawOverEar(palette, white = false)
                PodsModel.Family.BEATS_BUDS -> drawBeatsBuds(palette, leftT, rightT, lidT)
                PodsModel.Family.BEATS_NECK -> drawNeckband(palette, leftT, rightT)
                PodsModel.Family.AIRPODS_CLASSIC -> drawAirPods(palette, Style.CLASSIC, leftT, rightT, lidT)
                PodsModel.Family.AIRPODS_3 -> drawAirPods(palette, Style.GEN3, leftT, rightT, lidT)
                PodsModel.Family.AIRPODS_PRO, PodsModel.Family.GENERIC -> drawAirPods(palette, Style.PRO, leftT, rightT, lidT)
            }
        }
    }
}

private enum class Style { CLASSIC, GEN3, PRO }

private class Palette(dark: Boolean, val accent: Color) {
    val white = Brush.linearGradient(listOf(Color(0xFFFFFFFF), Color(0xFFE9ECF0), Color(0xFFC6CBD3)))
    val whiteFlat = Color(0xFFEDEFF3)
    val highlight = Color(0x66FFFFFF)
    val shade = Color(0x33000000)
    val shadow = Color(if (dark) 0x55000000 else 0x22000000)
    val edge = if (dark) Color(0x22FFFFFF) else Color(0x22000000)
    val black = Brush.linearGradient(listOf(Color(0xFF3A3F47), Color(0xFF1B1E23)))
    val tip = Brush.radialGradient(listOf(Color(0xFF5A6068), Color(0xFF23262B)))
    val caseInside = if (dark) Color(0xFF3A3F47) else Color(0xFFB9BEC6)
    val red = Color(0xFFE0323C)
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

// ---------------------------------------------------------------------------------------------
// AirPods (classic / gen3 / pro) with case
// ---------------------------------------------------------------------------------------------

private fun DrawScope.drawAirPods(p: Palette, style: Style, leftT: Float, rightT: Float, lidT: Float) {
    val w = size.width; val h = size.height
    val caseW = w * 0.50f; val caseH = if (style == Style.CLASSIC) h * 0.40f else h * 0.34f
    val caseX = (w - caseW) / 2; val caseY = h * 0.60f
    val lidH = caseH * 0.36f
    val corner = CornerRadius(w * 0.09f)
    // soft shadow under the case
    drawOval(p.shadow, Offset(caseX - w * 0.02f, caseY + caseH - h * 0.02f), Size(caseW + w * 0.04f, h * 0.05f))
    // body
    drawRoundRect(p.white, Offset(caseX, caseY), Size(caseW, caseH), corner)
    drawRoundRect(p.edge, Offset(caseX, caseY), Size(caseW, caseH), corner, style = Stroke(2f))
    // glossy highlight on the body
    drawRoundRect(p.highlight, Offset(caseX + caseW * 0.08f, caseY + caseH * 0.10f), Size(caseW * 0.12f, caseH * 0.55f), CornerRadius(w * 0.04f))
    // inside becomes visible as the lid opens
    if (lidT > 0.05f) {
        drawRoundRect(p.caseInside.copy(alpha = lidT), Offset(caseX + caseW * 0.08f, caseY + 2), Size(caseW * 0.84f, caseH * 0.18f), CornerRadius(w * 0.03f))
    }
    // lid: translates up and rotates around the hinge on the left
    val lidY = caseY - lerp(lidH * 0.02f, lidH * 0.9f, lidT)
    rotate(-12f * lidT, Offset(caseX, lidY + lidH)) {
        drawRoundRect(p.white, Offset(caseX, lidY), Size(caseW, lidH), corner)
        drawRoundRect(p.edge, Offset(caseX, lidY), Size(caseW, lidH), corner, style = Stroke(2f))
        drawRoundRect(p.highlight, Offset(caseX + caseW * 0.08f, lidY + lidH * 0.2f), Size(caseW * 0.5f, lidH * 0.18f), CornerRadius(w * 0.03f))
    }
    // status LED
    drawCircle(if (lidT > 0.5f) p.accent else Color(0x33000000), w * 0.012f, Offset(w / 2, caseY + caseH * 0.55f))

    // buds
    val budY = h * 0.25f
    drawBud(p, style, Offset(w * 0.33f, budY), leftT, mirror = false)
    drawBud(p, style, Offset(w * 0.67f, budY), rightT, mirror = true)
}

private fun DrawScope.drawBud(p: Palette, style: Style, base: Offset, t: Float, mirror: Boolean) {
    val w = size.width
    val s = if (mirror) -1f else 1f
    val c = Offset(base.x, base.y - w * 0.04f * t)  // rises when in ear
    val alpha = lerp(0.6f, 1f, t)
    if (t > 0.02f) drawCircle(p.accent.copy(alpha = 0.22f * t), w * 0.14f, c)
    // shadow
    drawOval(p.shadow.copy(alpha = p.shadow.alpha * (1f - t * 0.5f)), Offset(c.x - w * 0.06f, base.y + w * 0.25f), Size(w * 0.12f, w * 0.03f))
    // body
    val bodyR = w * 0.075f
    val body = Path().apply { addOval(Rect(c.x - bodyR * 1.1f, c.y - bodyR, c.x + bodyR * 1.1f, c.y + bodyR * 1.15f)) }
    drawPath(body, p.white, alpha = alpha)
    drawPath(body, p.edge, style = Stroke(2f), alpha = alpha)
    drawCircle(p.highlight, bodyR * 0.3f, Offset(c.x - s * bodyR * 0.3f, c.y - bodyR * 0.45f), alpha = alpha)
    // silicone tip (pro)
    if (style == Style.PRO) drawCircle(p.tip, bodyR * 0.62f, Offset(c.x - s * bodyR * 0.75f, c.y + bodyR * 0.15f), alpha = alpha)
    // sensor / mic grille
    drawCircle(Color(0x44000000), bodyR * 0.16f, Offset(c.x + s * bodyR * 0.25f, c.y - bodyR * 0.35f), alpha = alpha)
    // stem
    val stemLen = when (style) { Style.CLASSIC -> w * 0.20f; Style.GEN3 -> w * 0.13f; Style.PRO -> w * 0.12f }
    val stemW = when (style) { Style.CLASSIC -> w * 0.045f; else -> w * 0.055f }
    val stemTop = Offset(c.x + s * bodyR * 0.35f, c.y + bodyR * 0.6f)
    val stemBottom = Offset(stemTop.x + s * stemLen * 0.12f, stemTop.y + stemLen)
    drawLine(p.whiteFlat, stemTop, stemBottom, stemW, StrokeCap.Round, alpha = alpha)
    drawLine(p.edge, stemTop, stemBottom, stemW, StrokeCap.Round, alpha = alpha)
    drawLine(Color(0x22000000), Offset(stemTop.x + s * stemW * 0.25f, stemTop.y + 6), Offset(stemBottom.x + s * stemW * 0.25f, stemBottom.y - 6), stemW * 0.25f, StrokeCap.Round, alpha = alpha * 0.6f)
}

// ---------------------------------------------------------------------------------------------
// Over-ear (AirPods Max / Beats Studio, Solo)
// ---------------------------------------------------------------------------------------------

private fun DrawScope.drawOverEar(p: Palette, white: Boolean) {
    val w = size.width; val h = size.height
    val cup = if (white) p.white else p.black
    val bandColor = if (white) Color(0xFFD9DDE3) else Color(0xFF2A2E34)
    val band = Path().apply {
        moveTo(w * 0.20f, h * 0.55f)
        cubicTo(w * 0.18f, h * 0.10f, w * 0.82f, h * 0.10f, w * 0.80f, h * 0.55f)
    }
    drawPath(band, bandColor, style = Stroke(w * 0.055f, cap = StrokeCap.Round))
    if (!white) drawPath(band, p.red, style = Stroke(w * 0.012f, cap = StrokeCap.Round))
    drawLine(bandColor, Offset(w * 0.20f, h * 0.50f), Offset(w * 0.21f, h * 0.62f), w * 0.03f, StrokeCap.Round)
    drawLine(bandColor, Offset(w * 0.80f, h * 0.50f), Offset(w * 0.79f, h * 0.62f), w * 0.03f, StrokeCap.Round)
    fun cupAt(x: Float) {
        drawOval(p.shadow, Offset(x - w * 0.01f, h * 0.86f), Size(w * 0.22f, h * 0.04f))
        drawRoundRect(cup, Offset(x, h * 0.58f), Size(w * 0.20f, h * 0.30f), CornerRadius(w * 0.07f))
        drawRoundRect(p.edge, Offset(x, h * 0.58f), Size(w * 0.20f, h * 0.30f), CornerRadius(w * 0.07f), style = Stroke(2f))
        drawRoundRect(p.highlight, Offset(x + w * 0.03f, h * 0.61f), Size(w * 0.04f, h * 0.12f), CornerRadius(w * 0.02f))
        if (!white) drawCircle(p.red, w * 0.03f, Offset(x + w * 0.10f, h * 0.73f))
    }
    cupAt(w * 0.11f); cupAt(w * 0.69f)
    drawRoundRect(Color(0x33000000), Offset(w * 0.27f, h * 0.62f), Size(w * 0.04f, h * 0.22f), CornerRadius(8f))
    drawRoundRect(Color(0x33000000), Offset(w * 0.69f, h * 0.62f), Size(w * 0.04f, h * 0.22f), CornerRadius(8f))
}

// ---------------------------------------------------------------------------------------------
// Beats buds (Studio Buds / Fit Pro / Powerbeats Pro / Solo Buds) with case
// ---------------------------------------------------------------------------------------------

private fun DrawScope.drawBeatsBuds(p: Palette, leftT: Float, rightT: Float, lidT: Float) {
    val w = size.width; val h = size.height
    val caseW = w * 0.56f; val caseH = h * 0.30f
    val caseX = (w - caseW) / 2; val caseY = h * 0.62f
    drawOval(p.shadow, Offset(caseX - w * 0.02f, caseY + caseH - h * 0.02f), Size(caseW + w * 0.04f, h * 0.05f))
    drawRoundRect(p.black, Offset(caseX, caseY), Size(caseW, caseH), CornerRadius(w * 0.10f))
    drawRoundRect(p.highlight.copy(alpha = 0.15f), Offset(caseX + caseW * 0.06f, caseY + caseH * 0.15f), Size(caseW * 0.10f, caseH * 0.5f), CornerRadius(w * 0.03f))
    val lidY = caseY - lerp(0f, caseH * 0.35f, lidT)
    rotate(-10f * lidT, Offset(caseX, lidY + caseH * 0.35f)) {
        drawRoundRect(p.black, Offset(caseX, lidY), Size(caseW, caseH * 0.35f), CornerRadius(w * 0.10f))
        drawRoundRect(p.edge, Offset(caseX, lidY), Size(caseW, caseH * 0.35f), CornerRadius(w * 0.10f), style = Stroke(2f))
    }
    drawCircle(if (lidT > 0.5f) p.accent else Color(0x66FFFFFF), w * 0.012f, Offset(w / 2, caseY + caseH * 0.65f))
    fun bud(cx: Float, t: Float, s: Float) {
        val alpha = lerp(0.6f, 1f, t)
        val cy = h * 0.32f - w * 0.04f * t
        if (t > 0.02f) drawCircle(p.accent.copy(alpha = 0.22f * t), w * 0.14f, Offset(cx, cy))
        val wing = Path().apply {
            moveTo(cx, cy - h * 0.08f); quadraticTo(cx - s * w * 0.10f, cy - h * 0.12f, cx - s * w * 0.06f, cy + h * 0.04f); close()
        }
        drawPath(wing, Color(0xFF2A2E34), alpha = alpha)
        drawCircle(p.black, w * 0.085f, Offset(cx, cy), alpha = alpha)
        drawCircle(p.tip, w * 0.045f, Offset(cx - s * w * 0.06f, cy + h * 0.03f), alpha = alpha)
        drawCircle(p.red, w * 0.03f, Offset(cx + s * w * 0.02f, cy - h * 0.01f), alpha = alpha)
    }
    bud(w * 0.32f, leftT, 1f); bud(w * 0.68f, rightT, -1f)
}

// ---------------------------------------------------------------------------------------------
// Neckband (BeatsX / Flex / Powerbeats)
// ---------------------------------------------------------------------------------------------

private fun DrawScope.drawNeckband(p: Palette, leftT: Float, rightT: Float) {
    val w = size.width; val h = size.height
    val cable = Path().apply {
        moveTo(w * 0.30f, h * 0.35f)
        cubicTo(w * 0.15f, h * 0.65f, w * 0.85f, h * 0.65f, w * 0.70f, h * 0.35f)
    }
    drawPath(cable, Color(0xFF2A2E34), style = Stroke(w * 0.02f, cap = StrokeCap.Round))
    drawRoundRect(p.black, Offset(w * 0.22f, h * 0.55f), Size(w * 0.16f, h * 0.06f), CornerRadius(10f))
    drawRoundRect(p.black, Offset(w * 0.62f, h * 0.55f), Size(w * 0.16f, h * 0.06f), CornerRadius(10f))
    fun bud(cx: Float, t: Float) {
        val alpha = lerp(0.6f, 1f, t)
        val cy = h * 0.33f - w * 0.03f * t
        if (t > 0.02f) drawCircle(p.accent.copy(alpha = 0.22f * t), w * 0.11f, Offset(cx, cy))
        drawCircle(p.black, w * 0.07f, Offset(cx, cy), alpha = alpha)
        drawCircle(p.red, w * 0.025f, Offset(cx, cy), alpha = alpha)
    }
    bud(w * 0.30f, leftT); bud(w * 0.70f, rightT)
}
