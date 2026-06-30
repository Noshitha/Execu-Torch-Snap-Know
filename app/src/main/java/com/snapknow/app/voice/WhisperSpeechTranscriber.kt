package com.snapknow.app.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

private const val TAG = "WhisperSpeechTranscriber"

class WhisperSpeechTranscriber(
    context: Context,
    private val onResult: SpeechTranscriberResultListener,
    private val onStateChanged: SpeechTranscriberStateListener
) : SpeechTranscriber {

    override val engineLabel: String = "Whisper tiny"

    private val appContext = context.applicationContext
    private val assetBridge = SpeechAssetBridge(appContext)
    private val config = assetBridge.whisperTinyConfig()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private var processingJob: Job? = null
    private var isSessionActive = false
    private var isMuted = false
    private var shouldResumeAfterMute = false
    private val capturedSamples = ArrayList<Short>(config.sampleRateHz * config.maxAudioSeconds)

    override fun availability(): SpeechTranscriberAvailability {
        val bridgeState = assetBridge.prepareWhisperTiny()
        if (bridgeState.missingAssets.isNotEmpty()) {
            return SpeechTranscriberAvailability(
                available = false,
                reason = "Whisper tiny is not bundled yet. Missing ${bridgeState.missingAssets.joinToString()}."
            )
        }
        if (!bridgeState.backendLibraryPresent) {
            return SpeechTranscriberAvailability(
                available = false,
                reason = "Whisper runtime requires libsnapknow_jni.so plus the Whisper asset bundle."
            )
        }

        WhisperExecuTorchSession(appContext, config).use { session ->
            val sessionAvailability = session.availability()
            return SpeechTranscriberAvailability(
                available = sessionAvailability.available,
                reason = sessionAvailability.reason
            )
        }
    }

    override fun start() {
        val availability = availability()
        if (!availability.available) {
            publishState(
                mode = SpeechTranscriberMode.UNAVAILABLE,
                message = availability.reason ?: "Whisper unavailable.",
                canStart = false,
                canStop = false
            )
            return
        }
        if (captureJob != null && isSessionActive && !isMuted) return

        processingJob?.cancel()
        cancelCapture()
        shouldResumeAfterMute = false
        isMuted = false
        isSessionActive = true
        capturedSamples.clear()

        val minBufferSize = AudioRecord.getMinBufferSize(
            config.sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) {
            publishState(
                mode = SpeechTranscriberMode.ERROR,
                message = "Whisper microphone setup failed on this device.",
                canStart = true,
                canStop = false
            )
            isSessionActive = false
            return
        }

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            config.sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize * 2
        )
        audioRecord = recorder
        recorder.startRecording()

        publishState(
            mode = SpeechTranscriberMode.STARTING,
            message = "Starting Whisper microphone…",
            canStart = false,
            canStop = true
        )

        captureJob = scope.launch {
            val buffer = ShortArray(max(1024, minBufferSize / 2))
            val maxSampleCount = config.sampleRateHz * config.maxAudioSeconds
            var heardSpeech = false
            var lastSpeechAt = SystemClock.elapsedRealtime()
            val silenceThreshold = 900

            publishState(
                mode = SpeechTranscriberMode.LISTENING,
                message = "Listening with Whisper… tap Stop Mic when you're done.",
                canStart = false,
                canStop = true
            )

            while (isSessionActive && !isMuted) {
                val readCount = recorder.read(buffer, 0, buffer.size)
                if (readCount <= 0) continue

                var chunkPeak = 0
                for (index in 0 until readCount) {
                    val sample = buffer[index]
                    if (capturedSamples.size < maxSampleCount) {
                        capturedSamples += sample
                    }
                    chunkPeak = max(chunkPeak, abs(sample.toInt()))
                }

                if (chunkPeak > silenceThreshold) {
                    heardSpeech = true
                    lastSpeechAt = SystemClock.elapsedRealtime()
                }

                val reachedMaxDuration = capturedSamples.size >= maxSampleCount
                val silenceElapsed = SystemClock.elapsedRealtime() - lastSpeechAt
                if (reachedMaxDuration || (heardSpeech && silenceElapsed > 1200)) {
                    finalizeCapture(manual = false)
                    return@launch
                }
            }
        }
    }

    override fun stop() {
        if (!isSessionActive && audioRecord == null) {
            publishState(
                mode = SpeechTranscriberMode.IDLE,
                message = "Tap Start Mic when you're ready.",
                canStart = true,
                canStop = false
            )
            return
        }
        shouldResumeAfterMute = false
        isMuted = false
        finalizeCapture(manual = true)
    }

    override fun mute() {
        if (!isSessionActive || isMuted) return
        isMuted = true
        shouldResumeAfterMute = true
        cancelCapture()
        publishState(
            mode = SpeechTranscriberMode.MUTED,
            message = "Whisper microphone paused while the assistant speaks.",
            canStart = false,
            canStop = true
        )
    }

    override fun unmute() {
        if (!shouldResumeAfterMute) return
        shouldResumeAfterMute = false
        isMuted = false
        start()
    }

    override fun destroy() {
        isSessionActive = false
        cancelCapture()
        processingJob?.cancel()
        scope.cancel()
    }

    private fun finalizeCapture(manual: Boolean) {
        cancelCapture()
        val samples = capturedSamples.toShortArray()
        isSessionActive = false

        if (samples.isEmpty()) {
            publishState(
                mode = if (manual) SpeechTranscriberMode.IDLE else SpeechTranscriberMode.ERROR,
                message = if (manual) "Mic stopped." else "No speech detected. Tap Start Mic and try again.",
                canStart = true,
                canStop = false
            )
            return
        }

        publishState(
            mode = SpeechTranscriberMode.PROCESSING,
            message = "Transcribing with Whisper…",
            canStart = false,
            canStop = true
        )

        processingJob = scope.launch {
            WhisperExecuTorchSession(appContext, config).use { session ->
                session.transcribe(samples)
                    .onSuccess { transcript ->
                        if (transcript.isBlank()) {
                            publishState(
                                mode = SpeechTranscriberMode.ERROR,
                                message = "Whisper produced no text. Check tokenizer.json and model compatibility.",
                                canStart = true,
                                canStop = false
                            )
                        } else {
                            publishState(
                                mode = SpeechTranscriberMode.IDLE,
                                message = "Heard: \"$transcript\"",
                                canStart = true,
                                canStop = false
                            )
                            onResult.onTranscript(transcript)
                        }
                    }
                    .onFailure { error ->
                        Log.e(TAG, "Whisper transcription failed", error)
                        publishState(
                            mode = SpeechTranscriberMode.ERROR,
                            message = error.message ?: "Whisper transcription failed.",
                            canStart = true,
                            canStop = false
                        )
                    }
            }
        }
    }

    private fun cancelCapture() {
        captureJob?.cancel()
        captureJob = null
        audioRecord?.runCatching {
            stop()
            release()
        }
        audioRecord = null
    }

    private fun publishState(
        mode: SpeechTranscriberMode,
        message: String,
        canStart: Boolean,
        canStop: Boolean
    ) {
        onStateChanged.onStateChanged(
            SpeechTranscriberState(
                mode = mode,
                engineLabel = engineLabel,
                message = message,
                canStart = canStart,
                canStop = canStop
            )
        )
    }
}
