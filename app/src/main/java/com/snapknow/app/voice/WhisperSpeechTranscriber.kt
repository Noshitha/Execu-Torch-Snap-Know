package com.snapknow.app.voice

import android.content.Context

class WhisperSpeechTranscriber(
    private val context: Context
) : SpeechTranscriber {

    override val engineLabel: String = "Whisper (placeholder)"

    override fun availability(): SpeechTranscriberAvailability {
        val assetExists = runCatching {
            context.assets.open("whisper_tiny.en.tflite").close()
            true
        }.getOrDefault(false)

        return if (assetExists) {
            SpeechTranscriberAvailability(
                available = false,
                reason = "Whisper asset is present, but the Android audio/runtime bridge is still TODO."
            )
        } else {
            SpeechTranscriberAvailability(
                available = false,
                reason = "Whisper runtime placeholder only. Add model asset plus PCM inference pipeline."
            )
        }
    }

    override fun start() = Unit

    override fun stop() = Unit

    override fun mute() = Unit

    override fun unmute() = Unit

    override fun destroy() = Unit
}
