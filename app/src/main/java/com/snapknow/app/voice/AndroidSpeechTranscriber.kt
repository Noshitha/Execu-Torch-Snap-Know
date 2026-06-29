package com.snapknow.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

private const val TAG = "AndroidSpeechTranscriber"

class AndroidSpeechTranscriber(
    private val context: Context,
    private val onResult: SpeechTranscriberResultListener,
    private val onStateChanged: SpeechTranscriberStateListener
) : SpeechTranscriber {

    override val engineLabel: String = "Android system speech"

    private var recognizer: SpeechRecognizer? = null
    private var isSessionActive = false
    private var isMuted = false
    private var shouldResumeAfterMute = false
    private var manualStopRequested = false

    override fun availability(): SpeechTranscriberAvailability =
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            SpeechTranscriberAvailability(available = true)
        } else {
            SpeechTranscriberAvailability(
                available = false,
                reason = "Speech recognition is unavailable on this device."
            )
        }

    override fun start() {
        val availability = availability()
        if (!availability.available) {
            publishState(
                mode = SpeechTranscriberMode.UNAVAILABLE,
                message = availability.reason ?: "Speech recognition unavailable.",
                canStart = false,
                canStop = false
            )
            return
        }
        if (isSessionActive && !isMuted) return

        isSessionActive = true
        isMuted = false
        shouldResumeAfterMute = false
        manualStopRequested = false
        ensureRecognizer()
        publishState(
            mode = SpeechTranscriberMode.STARTING,
            message = "Starting microphone…",
            canStart = false,
            canStop = true
        )
        startListening()
    }

    override fun stop() {
        if (!isSessionActive && recognizer == null) {
            publishState(
                mode = SpeechTranscriberMode.IDLE,
                message = "Tap Start Mic when you're ready.",
                canStart = true,
                canStop = false
            )
            return
        }
        shouldResumeAfterMute = false
        isMuted = false
        isSessionActive = false
        manualStopRequested = true
        recognizer?.stopListening()
        publishState(
            mode = SpeechTranscriberMode.IDLE,
            message = "Mic stopped.",
            canStart = true,
            canStop = false
        )
    }

    override fun mute() {
        if (!isSessionActive || isMuted) return
        isMuted = true
        shouldResumeAfterMute = true
        recognizer?.stopListening()
        publishState(
            mode = SpeechTranscriberMode.MUTED,
            message = "Microphone paused while the assistant speaks.",
            canStart = false,
            canStop = true
        )
    }

    override fun unmute() {
        if (!isSessionActive || !shouldResumeAfterMute) return
        isMuted = false
        shouldResumeAfterMute = false
        publishState(
            mode = SpeechTranscriberMode.STARTING,
            message = "Resuming microphone…",
            canStart = false,
            canStop = true
        )
        startListening()
    }

    override fun destroy() {
        shouldResumeAfterMute = false
        isMuted = false
        isSessionActive = false
        manualStopRequested = true
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    private fun ensureRecognizer() {
        if (recognizer != null) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
        }
    }

    private fun startListening() {
        if (!isSessionActive || isMuted) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
        }

        try {
            recognizer?.startListening(intent)
            Log.d(TAG, "Listening started")
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed", e)
            isSessionActive = false
            shouldResumeAfterMute = false
            publishState(
                mode = SpeechTranscriberMode.ERROR,
                message = "Couldn't start the microphone. Please try again.",
                canStart = true,
                canStop = false
            )
        }
    }

    private fun finishSessionWithResult(text: String?) {
        isSessionActive = false
        shouldResumeAfterMute = false
        if (manualStopRequested) {
            manualStopRequested = false
            publishState(
                mode = SpeechTranscriberMode.IDLE,
                message = "Mic stopped.",
                canStart = true,
                canStop = false
            )
            return
        }
        if (!text.isNullOrBlank()) {
            publishState(
                mode = SpeechTranscriberMode.IDLE,
                message = "Heard: \"$text\"",
                canStart = true,
                canStop = false
            )
            onResult.onTranscript(text)
        } else {
            publishState(
                mode = SpeechTranscriberMode.ERROR,
                message = "I didn't catch that. Try speaking again.",
                canStart = true,
                canStop = false
            )
        }
    }

    private fun publishState(
        mode: SpeechTranscriberMode,
        message: String,
        canStart: Boolean,
        canStop: Boolean
    ) {
        onStateChanged.onStateChanged(
            SpeechTranscriberState(
                mode = mode,
                engineLabel = engineLabel,
                message = message,
                canStart = canStart,
                canStop = canStop
            )
        )
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            publishState(
                mode = SpeechTranscriberMode.LISTENING,
                message = "Listening… tap Stop Mic when you're done.",
                canStart = false,
                canStop = true
            )
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (isMuted || manualStopRequested) return
            publishState(
                mode = SpeechTranscriberMode.PROCESSING,
                message = "Transcribing…",
                canStart = false,
                canStop = true
            )
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
            finishSessionWithResult(text)
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit

        override fun onError(error: Int) {
            if (isMuted) {
                Log.d(TAG, "Recognizer stopped while muted")
                return
            }
            if (manualStopRequested) {
                manualStopRequested = false
                publishState(
                    mode = SpeechTranscriberMode.IDLE,
                    message = "Mic stopped.",
                    canStart = true,
                    canStop = false
                )
                return
            }

            isSessionActive = false
            shouldResumeAfterMute = false
            val state = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechTranscriberState(
                    mode = SpeechTranscriberMode.ERROR,
                    engineLabel = engineLabel,
                    message = "No speech detected. Tap Start Mic and try again.",
                    canStart = true,
                    canStop = false
                )
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SpeechTranscriberState(
                    mode = SpeechTranscriberMode.ERROR,
                    engineLabel = engineLabel,
                    message = "Speech recognizer is busy. Please retry in a moment.",
                    canStart = true,
                    canStop = false
                )
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechTranscriberState(
                    mode = SpeechTranscriberMode.ERROR,
                    engineLabel = engineLabel,
                    message = "Microphone permission is missing.",
                    canStart = false,
                    canStop = false
                )
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> SpeechTranscriberState(
                    mode = SpeechTranscriberMode.ERROR,
                    engineLabel = engineLabel,
                    message = "Android speech service needs offline packs or a working recognizer.",
                    canStart = true,
                    canStop = false
                )
                else -> SpeechTranscriberState(
                    mode = SpeechTranscriberMode.ERROR,
                    engineLabel = engineLabel,
                    message = "Speech recognition stopped unexpectedly. Please try again.",
                    canStart = true,
                    canStop = false
                )
            }
            onStateChanged.onStateChanged(state)
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
