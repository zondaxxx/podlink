package dev.podlink.service

import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent

/** Media key injection + volume ducking through AudioManager. No special permissions needed. */
class MediaController(context: Context) {
    companion object { private const val TAG = "MediaController" }
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val isMusicActive: Boolean get() = am.isMusicActive

    private fun key(code: Int) {
        val now = System.currentTimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }

    fun pause() { Log.i(TAG, "pause"); key(KeyEvent.KEYCODE_MEDIA_PAUSE) }
    fun play() { Log.i(TAG, "play"); key(KeyEvent.KEYCODE_MEDIA_PLAY) }

    // ---- ducking (conversation awareness) ----
    private var savedVolume: Int? = null

    fun duck(toPercent: Int) {
        if (savedVolume != null) return
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        savedVolume = cur
        val target = (cur * toPercent / 100.0).toInt().coerceAtLeast(0)
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
        Log.i(TAG, "duck $cur -> $target")
    }

    fun unduck() {
        val v = savedVolume ?: return
        savedVolume = null
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) }
        Log.i(TAG, "unduck -> $v")
    }
}
