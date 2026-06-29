package com.snapknow.app.voice

import android.content.Context

class PiperSpeechOutputEngine(
    private val context: Context,
    private val config: PiperVoiceConfig = PiperVoiceConfig()
) : SpeechOutputEngine {

    override val backend: SpeechBackend = SpeechBackend.PIPER
    private var listener: SpeechOutputEngine.Listener? = null
    private val assetBridge = SpeechAssetBridge(context.applicationContext)
    private var bridgeState: SpeechRuntimeBridgeState? = null

    override fun initialize(listener: SpeechOutputEngine.Listener) {
        this.listener = listener
        bridgeState = assetBridge.preparePiper(config)
        val state = bridgeState ?: return
        if (state.prerequisitesMet) {
            listener.onError(
                null,
                "Piper assets and native prerequisites are packaged, but this branch still needs the synthesis session binding before Piper can speak."
            )
        } else {
            listener.onError(
                null,
                "Piper backend unavailable. ${state.summary()}"
            )
        }
    }

    override fun isReady(): Boolean = false

    override fun speak(request: SpeechOutputRequest): Boolean {
        listener?.onError(request, "Piper speech is not available in this build yet.")
        return false
    }

    override fun stop() = Unit

    override fun shutdown() = Unit

    override fun statusMessage(): String {
        val state = bridgeState ?: assetBridge.preparePiper(config).also { bridgeState = it }
        return when {
            state.prerequisitesMet ->
                "Piper assets prepared for ${config.voiceId}; synthesis session still needs binding"
            state.assetBundlePresent ->
                "Piper bundle partially prepared; ${state.summary()}"
            else ->
                "Piper bundle missing for ${config.voiceId}"
        }
    }
}
