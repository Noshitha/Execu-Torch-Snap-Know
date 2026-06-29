package com.snapknow.app.voice

data class SpeechTranscriberAvailability(
    val available: Boolean,
    val reason: String? = null
)

enum class SpeechTranscriberMode {
    IDLE,
    STARTING,
    LISTENING,
    PROCESSING,
    MUTED,
    ERROR,
    UNAVAILABLE
}

data class SpeechTranscriberState(
    val mode: SpeechTranscriberMode,
    val engineLabel: String,
    val message: String,
    val canStart: Boolean,
    val canStop: Boolean
)

fun interface SpeechTranscriberResultListener {
    fun onTranscript(text: String)
}

fun interface SpeechTranscriberStateListener {
    fun onStateChanged(state: SpeechTranscriberState)
}

interface SpeechTranscriber {
    val engineLabel: String

    fun availability(): SpeechTranscriberAvailability
    fun start()
    fun stop()
    fun mute()
    fun unmute()
    fun destroy()
}
