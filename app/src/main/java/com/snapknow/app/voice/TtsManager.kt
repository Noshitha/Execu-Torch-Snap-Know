package com.snapknow.app.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "TtsManager"

class TtsManager(
    context: Context,
    preferredBackend: SpeechBackendPreference = SpeechBackendPreference.PIPER_WITH_ANDROID_FALLBACK,
    private val onStart: () -> Unit = {},
    private val onDone: () -> Unit = {},
    private val onError: (String) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(
        SpeechOutputState(preferredBackend = preferredBackend)
    )
    val state: StateFlow<SpeechOutputState> = _state.asStateFlow()

    private var engine: SpeechOutputEngine = selectEngine(preferredBackend)
    private var pendingRetryRequest: SpeechOutputRequest? = null

    init {
        initializeEngine()
    }

    fun speak(text: String, flush: Boolean = true): Boolean {
        return speak(SpeechOutputRequest(text = text, flush = flush))
    }

    fun speak(request: SpeechOutputRequest): Boolean {
        val currentEngine = engine
        val started = currentEngine.speak(request)
        if (!started) {
            Log.w(TAG, "Speech request rejected by ${currentEngine.backend}: ${request.text}")
        }
        return started
    }

    fun stop() {
        engine.stop()
        updateState { copy(isSpeaking = false) }
    }

    fun shutdown() {
        engine.shutdown()
        updateState { copy(isReady = false, isSpeaking = false) }
    }

    val ready: Boolean
        get() = state.value.isReady

    private fun initializeEngine() {
        engine.initialize(object : SpeechOutputEngine.Listener {
            override fun onReady() {
                updateState {
                    copy(
                        activeBackend = engine.backend,
                        isReady = true,
                        status = engine.statusMessage(),
                        lastError = null
                    )
                }
                pendingRetryRequest?.let { request ->
                    pendingRetryRequest = null
                    engine.speak(request)
                }
            }

            override fun onStart(request: SpeechOutputRequest) {
                updateState {
                    copy(
                        activeBackend = engine.backend,
                        isSpeaking = true,
                        status = "Speaking via ${engine.backend.name.lowercase().replace('_', ' ')}"
                    )
                }
                onStart()
            }

            override fun onDone(request: SpeechOutputRequest) {
                updateState {
                    copy(
                        activeBackend = engine.backend,
                        isSpeaking = false,
                        status = engine.statusMessage()
                    )
                }
                onDone()
            }

            override fun onError(request: SpeechOutputRequest?, message: String) {
                Log.w(TAG, message)
                val previousBackend = engine.backend
                if (previousBackend == SpeechBackend.PIPER &&
                    state.value.preferredBackend == SpeechBackendPreference.PIPER_WITH_ANDROID_FALLBACK
                ) {
                    pendingRetryRequest = request
                    fallbackToAndroid(message)
                    return
                }

                updateState {
                    copy(
                        activeBackend = engine.backend,
                        isReady = engine.isReady(),
                        isSpeaking = false,
                        status = engine.statusMessage(),
                        lastError = message
                    )
                }
                onError(message)
                onDone()
            }
        })
    }

    private fun fallbackToAndroid(reason: String) {
        engine.shutdown()
        engine = AndroidSpeechOutputEngine(appContext)
        updateState {
            copy(
                activeBackend = engine.backend,
                isReady = false,
                isSpeaking = false,
                status = "Falling back to Android speech output",
                lastError = reason
            )
        }
        initializeEngine()
    }

    private fun selectEngine(preferredBackend: SpeechBackendPreference): SpeechOutputEngine {
        return when (preferredBackend) {
            SpeechBackendPreference.PIPER_WITH_ANDROID_FALLBACK -> PiperSpeechOutputEngine(appContext)
            SpeechBackendPreference.ANDROID_SYSTEM_ONLY -> AndroidSpeechOutputEngine(appContext)
        }
    }

    private fun updateState(transform: SpeechOutputState.() -> SpeechOutputState) {
        _state.value = _state.value.transform()
    }
}
