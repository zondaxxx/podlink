package dev.podlink.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/** Optional voice announcements (connection, low battery, charged) through the headset, with transient audio focus. */
class Announcer(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayDeque<String>()
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    private fun ensure() {
        if (tts != null) return
        tts = TextToSpeech(context) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.getDefault()
                tts?.setAudioAttributes(attrs)
                while (pending.isNotEmpty()) speakNow(pending.removeFirst())
            } else Log.w("Announcer", "TTS init failed: $status")
        }
    }

    fun say(text: String) {
        ensure()
        if (ready) speakNow(text) else pending.addLast(text)
    }

    private fun speakNow(text: String) {
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
        am.requestAudioFocus(focus)
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { am.abandonAudioFocusRequest(focus) }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { am.abandonAudioFocusRequest(focus) }
        })
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "podlink-${System.currentTimeMillis()}")
    }

    fun shutdown() { runCatching { tts?.shutdown() }; tts = null; ready = false }
}
