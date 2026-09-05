package dev.podlink.ui.components

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.podlink.R
import dev.podlink.ble.PodsModel

/**
 * The real "connected" animation (AirPods Pro 2 buds and case turning), played muted and looped.
 * Rendered through a TextureView so it composes like any other view: clipped by rounded corners,
 * faded, scaled. The video has an opaque background, so a "stage" of exactly that colour sits under it.
 */
@Composable
fun ConnectedVideo(modifier: Modifier = Modifier, loop: Boolean = true, corner: androidx.compose.ui.unit.Dp = 20.dp) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val resId = if (dark) R.raw.connected_dark else R.raw.connected_light
    val stage = if (dark) Color(0xFF1B1A1D) else Color.White
    Box(modifier.fillMaxWidth().aspectRatio(1050f / 354f).clip(RoundedCornerShape(corner)).background(stage)) {
        VideoLoop(resId, loop, Modifier.fillMaxSize())
    }
}

/** True when a real animation exists for this model family. */
fun hasVideo(model: PodsModel): Boolean = model.family == PodsModel.Family.AIRPODS_PRO || model.family == PodsModel.Family.GENERIC

@Composable
fun VideoLoop(resId: Int, loop: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val holder = remember(resId) { PlayerHolder() }
    DisposableEffect(resId) {
        onDispose { holder.release() }
    }
    AndroidView(
        modifier = modifier,
        factory = { c ->
            TextureView(c).apply {
                isOpaque = false
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                        holder.start(ctx, resId, loop, Surface(st))
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { holder.release(); return true }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        },
    )
}

private class PlayerHolder {
    private var player: MediaPlayer? = null
    private var surface: Surface? = null

    fun start(ctx: android.content.Context, resId: Int, loop: Boolean, s: Surface) {
        release()
        surface = s
        player = runCatching {
            MediaPlayer().apply {
                setDataSource(ctx, Uri.parse("android.resource://${ctx.packageName}/$resId"))
                setSurface(s)
                setVolume(0f, 0f)
                isLooping = loop
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        }.getOrNull()
    }

    fun release() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        runCatching { surface?.release() }
        surface = null
    }
}
