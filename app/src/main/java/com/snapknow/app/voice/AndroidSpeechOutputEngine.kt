package com.snapknow.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val ANDROID_TTS_TAG = "AndroidSpeechOutput"

class AndroidSpeechOutputEngine(
    private val context: Context
) : SpeechOutputEngine {

    override val backend: SpeechBackend = SpeechBackend.ANDROID_SYSTEM

    private var textToSpeech: TextToSpeech? = null
    private var listener: SpeechOutputEngine.Listener? = null
    private var ready = false
    private val pendingRequests = ConcurrentHashMap<String, SpeechOutputRequest>()

    override fun initialize(listener: SpeechOutputEngine.Listener) {
        this.listener = listener
        textToSpeech = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) {
                ready = false
                listener.onError(null, "Android TTS failed to initialize (code $status).")
                return@TextToSpeech
            }

            val localeResult = textToSpeech?.setLanguage(Locale.US)
            if (localeResult == TextToSpeech.LANG_MISSING_DATA || localeResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(ANDROID_TTS_TAG, "Locale.US not supported; falling back to device locale")
                textToSpeech?.setLanguage(Locale.getDefault())
            }

            textToSpeech?.setSpeechRate(0.85f)
            textToSpeech?.setPitch(1.0f)
            textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    val request = utteranceId?.let(pendingRequests::get) ?: return
                    this@AndroidSpeechOutputEngine.listener?.onStart(request)
                }

                override fun onDone(utteranceId: String?) {
                    val request = utteranceId?.let(pendingRequests::remove) ?: return
                    this@AndroidSpeechOutputEngine.listener?.onDone(request)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    val request = utteranceId?.let(pendingRequests::remove)
                    this@AndroidSpeechOutputEngine.listener?.onError(request, "Android TTS could not speak the utterance.")
                }
            })

            ready = true
            listener.onReady()
            Log.d(ANDROID_TTS_TAG, "Android TTS ready")
        }
    }

    override fun isReady(): Boolean = ready

    override fun speak(request: SpeechOutputRequest): Boolean {
        if (!ready) {
            listener?.onError(request, "Android TTS is still initializing.")
            return false
        }

        val utteranceId = "snapknow_${System.currentTimeMillis()}"
        pendingRequests[utteranceId] = request
        val queueMode = if (request.flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val result = textToSpeech?.speak(request.text, queueMode, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            pendingRequests.remove(utteranceId)
            listener?.onError(request, "Android TTS rejected the utterance request.")
            return false
        }
        return true
    }

    override fun stop() {
        textToSpeech?.stop()
        pendingRequests.clear()
    }

    override fun shutdown() {
        stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        ready = false
    }

    override fun statusMessage(): String = if (ready) {
        "Android speech output ready"
    } else {
        "Android speech output is initializing"
    }
}
