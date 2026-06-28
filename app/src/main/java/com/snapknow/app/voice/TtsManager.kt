package com.snapknow.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

private const val TAG = "TtsManager"

/**
 * Wraps Android's [TextToSpeech] engine.
 * Runs fully on-device — no network required.
 *
 * Callbacks [onStart] / [onDone] can be used to mute/unmute voice recognition
 * while the assistant is speaking, preventing it from hearing itself.
 */
class TtsManager(
    context: Context,
    private val onStart: () -> Unit = {},
    private val onDone: () -> Unit = {}
) {
    private var tts: TextToSpeech? = null
    private var isReady = false

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "Language not supported, trying default locale")
                    tts?.setLanguage(Locale.getDefault())
                }
                // Slightly slower speech rate for dementia-friendly output
                tts?.setSpeechRate(0.85f)
                tts?.setPitch(1.0f)
                isReady = true

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { onStart() }
                    override fun onDone(utteranceId: String?) { onDone() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { onDone() }
                })
                Log.d(TAG, "TTS ready")
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
        }
    }

    fun speak(text: String, flush: Boolean = true) {
        if (!isReady) {
            Log.w(TAG, "TTS not ready, dropping: $text")
            return
        }
        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(text, queueMode, null, "snapknow_${System.currentTimeMillis()}")
        Log.d(TAG, "Speaking: '$text'")
    }

    fun stop() { tts?.stop() }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }

    val ready: Boolean get() = isReady
}
