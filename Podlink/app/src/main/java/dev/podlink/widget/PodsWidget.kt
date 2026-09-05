package dev.podlink.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.podlink.MainActivity
import dev.podlink.R
import dev.podlink.service.PodsRepo
import dev.podlink.ui.theme.batteryColor

class PodsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PodsWidget()
}

/** Home-screen widget: compact row of numbers on small sizes, three rings on larger ones. Follows Material You. */
class PodsWidget : GlanceAppWidget() {
    companion object {
        suspend fun updateAll(context: Context) = PodsWidget().updateAll(context)
        private val SMALL = DpSize(120.dp, 50.dp)
        private val NORMAL = DpSize(220.dp, 110.dp)
    }

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, NORMAL))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        PodsRepo.restore(context)
        provideContent {
            GlanceTheme {
                val s = PodsRepo.state.value
                val size = LocalSize.current
                val bg = GlanceTheme.colors.widgetBackground
                val fg = GlanceTheme.colors.onSurface
                val dim = GlanceTheme.colors.onSurfaceVariant
                Column(
                    modifier = GlanceModifier.fillMaxSize().background(bg).cornerRadius(24.dp).padding(12.dp)
                        .clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (size.height >= NORMAL.height) {
                        Text(s.deviceName ?: s.model.label, style = TextStyle(color = dim, fontSize = 12.sp), maxLines = 1)
                        Spacer(GlanceModifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (s.isHeadphones) Ring(context, s.single, s.leftCharging, "", fg) else {
                                Ring(context, s.left, s.leftCharging, "L", fg); Spacer(GlanceModifier.width(10.dp))
                                Ring(context, s.right, s.rightCharging, "R", fg); Spacer(GlanceModifier.width(10.dp))
                                Ring(context, s.case, s.caseCharging, "C", fg)
                            }
                        }
                        if (!s.connected) Text(context.getString(R.string.tile_not_connected), style = TextStyle(color = dim, fontSize = 11.sp))
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (s.isHeadphones) Cell("", s.single, s.leftCharging, fg, dim) else {
                                Cell("L", s.left, s.leftCharging, fg, dim); Spacer(GlanceModifier.width(12.dp))
                                Cell("R", s.right, s.rightCharging, fg, dim); Spacer(GlanceModifier.width(12.dp))
                                Cell("C", s.case, s.caseCharging, fg, dim)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Cell(label: String, value: Int?, charging: Boolean, fg: ColorProvider, dim: ColorProvider) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (label.isNotEmpty()) { Text(label, style = TextStyle(color = dim, fontSize = 12.sp)); Spacer(GlanceModifier.width(3.dp)) }
            Text(
                text = if (value == null) "–" else "$value%${if (charging) "⚡" else ""}",
                style = TextStyle(color = ColorProvider(batteryColorOr(value, fg)), fontSize = 18.sp, fontWeight = FontWeight.Bold),
            )
        }
    }

    @Composable
    private fun Ring(context: Context, value: Int?, charging: Boolean, label: String, fg: ColorProvider) {
        val textColor = fg.getColor(context).toArgb()
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(ImageProvider(ringBitmap(context, value, charging, textColor)), contentDescription = label, modifier = GlanceModifier.size(56.dp))
            if (label.isNotEmpty()) Text(label, style = TextStyle(color = fg, fontSize = 11.sp))
        }
    }

    private fun batteryColorOr(value: Int?, fallback: ColorProvider): Color = if (value == null) Color(0xFF8A96A3) else batteryColor(value)

    /** Rings are drawn as bitmaps because Glance has no arc primitive. */
    private fun ringBitmap(context: Context, value: Int?, charging: Boolean, textColor: Int): Bitmap {
        val d = context.resources.displayMetrics.density
        val px = (56 * d).toInt()
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val stroke = 6 * d
        val rect = RectF(stroke, stroke, px - stroke, px - stroke)
        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = (textColor and 0x00FFFFFF) or 0x33000000; strokeCap = Paint.Cap.ROUND }
        c.drawArc(rect, -90f, 360f, false, track)
        if (value != null) {
            val p = Paint(track).apply { color = batteryColor(value).toArgb() }
            c.drawArc(rect, -90f, 360f * value / 100f, false, p)
        }
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = textColor; textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif", Typeface.BOLD); textSize = 15 * d }
        val label = if (value == null) "–" else "$value"
        c.drawText(label, px / 2f, px / 2f + t.textSize * 0.35f, t)
        if (charging) { t.textSize = 9 * d; c.drawText("⚡", px / 2f, px / 2f + t.textSize * 2.1f, t) }
        return bmp
    }
}
