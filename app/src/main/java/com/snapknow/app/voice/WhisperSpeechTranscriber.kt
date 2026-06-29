package com.snapknow.app.voice

import android.content.Context

class WhisperSpeechTranscriber(
    private val context: Context
) : SpeechTranscriber {

    override val engineLabel: String = "Whisper tiny"
    private val assetBridge = SpeechAssetBridge(context.applicationContext)

    override fun availability(): SpeechTranscriberAvailability {
        val state = assetBridge.prepareWhisperTiny()

        return if (state.prerequisitesMet) {
            SpeechTranscriberAvailability(
                available = false,
                reason = "Whisper tiny assets and native prerequisites are packaged, but the streaming transcription session is not implemented in this branch yet."
            )
        } else if (state.assetBundlePresent) {
            SpeechTranscriberAvailability(
                available = false,
                reason = "Whisper tiny bundle detected. ${state.summary()}"
            )
        } else {
            SpeechTranscriberAvailability(
                available = false,
                reason = "Whisper tiny is not bundled yet. Stage whisper_encoder.pte and whisper_decoder.pte into app/src/main/assets/speech/stt/whisper-tiny/."
            )
        }
    }

    override fun start() = Unit

    override fun stop() = Unit

    override fun mute() = Unit

    override fun unmute() = Unit

    override fun destroy() = Unit
}
