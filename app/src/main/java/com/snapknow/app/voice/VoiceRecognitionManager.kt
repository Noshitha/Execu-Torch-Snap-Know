package com.snapknow.app.voice

import android.content.Context

class VoiceRecognitionManager(
    context: Context,
    onResult: (String) -> Unit,
    onStateChanged: (SpeechTranscriberState) -> Unit = {}
) {
    private val delegates: List<SpeechTranscriber> = listOf(
        WhisperSpeechTranscriber(
            context = context,
            onResult = SpeechTranscriberResultListener(onResult),
            onStateChanged = SpeechTranscriberStateListener(onStateChanged)
        ),
        AndroidSpeechTranscriber(
            context = context,
            onResult = SpeechTranscriberResultListener(onResult),
            onStateChanged = SpeechTranscriberStateListener(onStateChanged)
        )
    )

    private val activeTranscriber: SpeechTranscriber =
        delegates.firstOrNull { it.availability().available } ?: delegates.first()

    init {
        val availability = activeTranscriber.availability()
        val initialState = if (availability.available) {
            SpeechTranscriberState(
                mode = SpeechTranscriberMode.IDLE,
                engineLabel = activeTranscriber.engineLabel,
                message = "Tap Start Mic when you're ready.",
                canStart = true,
                canStop = false
            )
        } else {
            val whisperReason = delegates
                .filterIsInstance<WhisperSpeechTranscriber>()
                .firstOrNull()
                ?.availability()
                ?.reason
            SpeechTranscriberState(
                mode = SpeechTranscriberMode.UNAVAILABLE,
                engineLabel = activeTranscriber.engineLabel,
                message = listOfNotNull(availability.reason, whisperReason).joinToString(" "),
                canStart = false,
                canStop = false
            )
        }
        onStateChanged(initialState)
    }

    fun start() = activeTranscriber.start()

    fun stop() = activeTranscriber.stop()

    fun mute() = activeTranscriber.mute()

    fun unmute() = activeTranscriber.unmute()

    fun destroy() {
        delegates.forEach(SpeechTranscriber::destroy)
    }
}
