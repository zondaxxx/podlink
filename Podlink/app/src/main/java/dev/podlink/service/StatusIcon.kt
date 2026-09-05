package dev.podlink.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.util.LruCache
import dev.podlink.R
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders the battery percentage as the status-bar small icon (the AccuBattery / AndroPods trick).
 *
 * The status bar keeps only the alpha channel and tints it, so we draw opaque white digits on a
 * transparent 24 dp bitmap. Icon instances are cached: a fresh Bitmap per post would make SystemUI
 * re-layout the icon every time (Icon.sameAs compares bitmap references).
 */
object StatusIcon {
    private val cache = LruCache<Int, Icon>(24)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val boltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }

    fun render(context: Context, percent: Int?, charging: Boolean): Icon {
        if (percent == null) return Icon.createWithResource(context, R.drawable.ic_pods)
        val p = percent.coerceIn(0, 100)
        val key = p * 2 + if (charging) 1 else 0
        cache.get(key)?.let { return it }
        val density = context.resources.displayMetrics.density
        val side = (24 * density).roundToInt().coerceAtLeast(24)
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val text = p.toString()
        // Fit: three digits must fit ~96 % of the width, height ~80 % of the box.
        paint.textSize = side * 0.9f
        val ink = Rect()
        paint.getTextBounds(text, 0, text.length, ink)
        val advance = paint.measureText(text)
        val scale = min(side * 0.96f / advance, side * 0.80f / ink.height())
        paint.textSize = paint.textSize * scale
        paint.getTextBounds(text, 0, text.length, ink)
        val cx = side / 2f
        val cy = side / 2f - (ink.top + ink.bottom) / 2f
        canvas.drawText(text, cx, cy, paint)
        if (charging) {
            // small bolt in the top-right corner
            val s = side * 0.30f
            val x0 = side - s; val y0 = 0f
            val bolt = Path().apply {
                moveTo(x0 + s * 0.60f, y0)
                lineTo(x0 + s * 0.15f, y0 + s * 0.58f)
                lineTo(x0 + s * 0.50f, y0 + s * 0.58f)
                lineTo(x0 + s * 0.38f, y0 + s)
                lineTo(x0 + s * 0.88f, y0 + s * 0.40f)
                lineTo(x0 + s * 0.53f, y0 + s * 0.40f)
                close()
            }
            canvas.drawPath(bolt, boltPaint)
        }
        val icon = Icon.createWithBitmap(bmp)
        cache.put(key, icon)
        return icon
    }
}
