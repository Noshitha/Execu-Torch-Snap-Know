package com.snapknow.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

private const val TAG = "VoiceRecognition"

/**
 * Wraps Android's [SpeechRecognizer] for explicit, user-driven capture.
 *
 * Usage:
 *   val mgr = VoiceRecognitionManager(context, onResult = { text -> ... })
 *   mgr.start()   // begins one listening session
 *   mgr.stop()    // stops the current session and releases resources
 */
class VoiceRecognitionManager(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onListeningStateChanged: (Boolean) -> Unit = {}
) {
    private var recognizer: SpeechRecognizer? = null
    private var isActive = false
    private var isMuted = false
    private var shouldResumeAfterMute = false

    // ─── Public API ───────────────────────────────────────────────────────────

    fun start() {
        if (isActive && !isMuted) return
        isActive = true
        isMuted = false
        shouldResumeAfterMute = false
        createRecognizer()
        listen()
    }

    fun stop() {
        shouldResumeAfterMute = false
        isActive = false
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
        onListeningStateChanged(false)
        Log.d(TAG, "Voice recognition stopped")
    }

    /** Temporarily suppress listening (e.g. while TTS is speaking so it doesn't hear itself) */
    fun mute() {
        if (!isActive) return
        isMuted = true
        shouldResumeAfterMute = true
        recognizer?.stopListening()
        onListeningStateChanged(false)
        Log.d(TAG, "Muted")
    }

    /** Resume listening after [mute] */
    fun unmute() {
        if (!isActive || !shouldResumeAfterMute) return
        isMuted = false
        shouldResumeAfterMute = false
        listen()
        Log.d(TAG, "Unmuted")
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    private fun createRecognizer() {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
        }
    }

    private fun listen() {
        if (!isActive || isMuted) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false) // true on real device with offline pack
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // Give generous silence margins for people who speak slowly
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
        }
        try {
            recognizer?.startListening(intent)
            onListeningStateChanged(true)
            Log.d(TAG, "Listening started")
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed", e)
            isActive = false
            onListeningStateChanged(false)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            onListeningStateChanged(false)
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
            isActive = false
            shouldResumeAfterMute = false
            onListeningStateChanged(false)
            if (!text.isNullOrBlank()) {
                Log.d(TAG, "Result: '$text'")
                onResult(text)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {}

        override fun onError(error: Int) {
            if (isMuted) {
                Log.d(TAG, "Recognizer stopped while muted")
                return
            }
            isActive = false
            shouldResumeAfterMute = false
            onListeningStateChanged(false)
            val msg = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                SpeechRecognizer.ERROR_NETWORK -> "Network error"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                SpeechRecognizer.ERROR_NO_MATCH -> "No speech match"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RecognitionService busy"
                SpeechRecognizer.ERROR_SERVER -> "Server error"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                else -> "Unknown error $error"
            }
            Log.w(TAG, "SpeechRecognizer error: $msg")
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
