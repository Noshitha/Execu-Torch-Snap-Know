package com.snapknow.app.voice

import android.content.Context
import com.snapknow.app.inference.ExecuTorchModule
import java.io.File

data class WhisperSessionAvailability(
    val available: Boolean,
    val reason: String? = null
)

class WhisperExecuTorchSession(
    private val context: Context,
    private val config: WhisperTinyAssetConfig
) : AutoCloseable {
    private val encoderModule = ExecuTorchModule.fromAsset(context, config.encoderAssetPath)
    private val decoderModule = WhisperDecoderModule.fromAsset(context, config.decoderAssetPath)
    private val tokenizer = WhisperTokenizer.fromAsset(context, config.tokenizerAssetPath)

    fun availability(): WhisperSessionAvailability {
        if (encoderModule == null) {
            return WhisperSessionAvailability(false, "Whisper encoder asset or JNI bridge is unavailable.")
        }
        if (decoderModule == null) {
            return WhisperSessionAvailability(false, "Whisper decoder asset or JNI bridge is unavailable.")
        }
        if (tokenizer == null) {
            return WhisperSessionAvailability(false, "Whisper tokenizer.json is missing or unreadable.")
        }
        return WhisperSessionAvailability(true)
    }

    fun transcribe(samples: ShortArray): Result<String> {
        val availability = availability()
        if (!availability.available) {
            return Result.failure(IllegalStateException(availability.reason))
        }

        return runCatching {
            val mel = WhisperAudioPreprocessor(config).toLogMelSpectrogram(samples)
            val encoderOutput = checkNotNull(encoderModule).runInference(mel, longArrayOf(1, 80, 3000))
            val decoder = checkNotNull(decoderModule)
            val decoderTokenizer = checkNotNull(tokenizer)

            val attentionMask = FloatArray(config.maxDecoderTokens) { -255f }
            val generatedTokenIds = mutableListOf<Long>()
            var currentTokenId = config.decoderStartTokenId

            for (position in 0 until config.maxDecoderTokens) {
                attentionMask[position] = 0f
                val nextTokenId = decoder.runStep(
                    tokenId = currentTokenId,
                    attentionMask = attentionMask,
                    encoderOutput = encoderOutput,
                    encoderShape = config.encoderOutputShape,
                    position = position.toLong()
                )
                if (nextTokenId == config.eosTokenId) {
                    break
                }
                generatedTokenIds += nextTokenId
                currentTokenId = nextTokenId
            }

            decoderTokenizer.decode(generatedTokenIds)
        }
    }

    override fun close() {
        decoderModule?.close()
        encoderModule?.close()
    }
}

class WhisperDecoderModule private constructor(modelPath: String) : AutoCloseable {
    private var handle: Long = nativeLoadModel(modelPath)

    fun runStep(
        tokenId: Long,
        attentionMask: FloatArray,
        encoderOutput: FloatArray,
        encoderShape: LongArray,
        position: Long
    ): Long {
        check(handle != 0L) { "Whisper decoder module already closed" }
        return nativeRunStep(handle, tokenId, attentionMask, encoderOutput, encoderShape, position)
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroyModel(handle)
            handle = 0L
        }
    }

    private external fun nativeLoadModel(path: String): Long

    private external fun nativeRunStep(
        handle: Long,
        tokenId: Long,
        attentionMask: FloatArray,
        encoderOutput: FloatArray,
        encoderShape: LongArray,
        position: Long
    ): Long

    private external fun nativeDestroyModel(handle: Long)

    companion object {
        private var libLoaded = false

        private fun ensureLibLoaded() {
            if (!libLoaded) {
                System.loadLibrary("snapknow_jni")
                libLoaded = true
            }
        }

        fun fromAsset(context: Context, assetPath: String): WhisperDecoderModule? {
            return runCatching {
                ensureLibLoaded()
                val extracted = extractAsset(context, assetPath)
                WhisperDecoderModule(extracted.absolutePath)
            }.getOrNull()
        }

        private fun extractAsset(context: Context, assetPath: String): File {
            val dest = File(context.filesDir, assetPath)
            if (!dest.exists()) {
                dest.parentFile?.mkdirs()
                context.assets.open(assetPath).use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
            return dest
        }
    }
}
