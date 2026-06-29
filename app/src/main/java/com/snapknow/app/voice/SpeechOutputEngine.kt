package com.snapknow.app.voice

interface SpeechOutputEngine {
    val backend: SpeechBackend

    fun initialize(listener: Listener)
    fun isReady(): Boolean
    fun speak(request: SpeechOutputRequest): Boolean
    fun stop()
    fun shutdown()
    fun statusMessage(): String

    interface Listener {
        fun onReady()
        fun onStart(request: SpeechOutputRequest)
        fun onDone(request: SpeechOutputRequest)
        fun onError(request: SpeechOutputRequest?, message: String)
    }
}
