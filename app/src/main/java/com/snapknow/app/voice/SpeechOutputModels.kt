package com.snapknow.app.voice

enum class SpeechBackend {
    ANDROID_SYSTEM,
    PIPER
}

enum class SpeechBackendPreference {
    PIPER_WITH_ANDROID_FALLBACK,
    ANDROID_SYSTEM_ONLY
}

data class PiperVoiceConfig(
    val modelAssetPath: String = "voice/en_US-lessac-medium/en_US-lessac-medium.onnx",
    val configAssetPath: String = "voice/en_US-lessac-medium/en_US-lessac-medium.onnx.json",
    val speakerId: Int? = null,
    val sampleRateHz: Int = 22_050
)

data class SpeechOutputRequest(
    val text: String,
    val flush: Boolean = true,
    val source: String = "assistant"
)

data class SpeechOutputState(
    val preferredBackend: SpeechBackendPreference = SpeechBackendPreference.PIPER_WITH_ANDROID_FALLBACK,
    val activeBackend: SpeechBackend = SpeechBackend.ANDROID_SYSTEM,
    val isReady: Boolean = false,
    val isSpeaking: Boolean = false,
    val status: String = "Speech output unavailable",
    val lastError: String? = null
)
