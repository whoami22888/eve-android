package com.eve.agent

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID

/**
 * VoiceManager wraps Android's TextToSpeech engine so EVE can speak responses
 * aloud.
 *
 * IMPORTANT: Requires the RECORD_AUDIO permission for speech recognition
 * (future feature). TTS itself needs no runtime permission.
 *
 * Remember to call [shutdown] when the owning component is destroyed to release
 * the TTS engine resource.
 */
class VoiceManager(context: Context) {

    private var tts: TextToSpeech? = null
    private var isReady = false

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                isReady = result != TextToSpeech.LANG_MISSING_DATA
                          && result != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }
    }

    /**
     * Speak [text] aloud.
     * Speech is queued — successive calls do not interrupt each other.
     * Does nothing if the TTS engine is not yet ready.
     *
     * @param onDone Optional callback invoked when utterance finishes.
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!isReady) return
        val utteranceId = UUID.randomUUID().toString()
        if (onDone != null) {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { if (id == utteranceId) onDone() }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {}
            })
        }
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    /** Stop any current utterance immediately. */
    fun stop() {
        tts?.stop()
    }

    /** Release the TTS engine. Call in onDestroy(). */
    fun shutdown() {
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
