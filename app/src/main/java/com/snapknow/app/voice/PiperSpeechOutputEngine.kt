package com.snapknow.app.voice

import android.content.Context

class PiperSpeechOutputEngine(
    private val context: Context,
    private val config: PiperVoiceConfig = PiperVoiceConfig()
) : SpeechOutputEngine {

    override val backend: SpeechBackend = SpeechBackend.PIPER
    private var listener: SpeechOutputEngine.Listener? = null

    override fun initialize(listener: SpeechOutputEngine.Listener) {
        this.listener = listener
        if (hasBundledVoiceAssets()) {
            listener.onError(
                null,
                "Piper assets are present, but the Android runtime is not wired yet. Complete the native audio path before enabling Piper."
            )
        } else {
            listener.onError(
                null,
                "Piper voice package not bundled yet. Add ${config.modelAssetPath} and ${config.configAssetPath} when the runtime is ready."
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

    override fun statusMessage(): String = if (hasBundledVoiceAssets()) {
        "Piper assets detected, runtime hookup still TODO"
    } else {
        "Piper placeholder configured for en_US-lessac-medium"
    }

    private fun hasBundledVoiceAssets(): Boolean {
        return try {
            context.assets.open(config.modelAssetPath).close()
            context.assets.open(config.configAssetPath).close()
            true
        } catch (_: Exception) {
            false
        }
    }
}
