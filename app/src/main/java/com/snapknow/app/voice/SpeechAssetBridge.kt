package com.snapknow.app.voice

import android.content.Context
import java.io.File

private const val SPEECH_RUNTIME_ROOT = "speech-runtime"

data class SpeechRuntimeBridgeState(
    val bundleLabel: String,
    val requiredAssets: List<String>,
    val missingAssets: List<String>,
    val preparedFiles: List<File>,
    val runtimeDirectory: File?,
    val backendLibraryName: String,
    val backendLibraryPresent: Boolean
) {
    val assetBundlePresent: Boolean
        get() = preparedFiles.isNotEmpty()

    val prerequisitesMet: Boolean
        get() = missingAssets.isEmpty() && backendLibraryPresent

    fun summary(): String {
        if (missingAssets.isNotEmpty()) {
            return "Missing assets: ${missingAssets.joinToString()}"
        }
        if (!backendLibraryPresent) {
            val runtimePath = runtimeDirectory?.absolutePath ?: "runtime cache"
            return "Assets prepared at $runtimePath, but native library $backendLibraryName is not bundled."
        }
        return "Assets and native library are present."
    }
}

data class WhisperTinyAssetConfig(
    val encoderAssetPath: String = "speech/stt/whisper-tiny/whisper_encoder.pte",
    val decoderAssetPath: String = "speech/stt/whisper-tiny/whisper_decoder.pte",
    val tokenizerAssetPath: String = "speech/stt/whisper-tiny/tokenizer.json",
    val manifestAssetPath: String = "speech/stt/whisper-tiny/whisper_manifest.json",
    val encoderOutputShape: LongArray = longArrayOf(1, 1500, 384),
    val sampleRateHz: Int = 16_000,
    val maxAudioSeconds: Int = 30,
    val melBins: Int = 80,
    val maxDecoderTokens: Int = 128,
    val decoderStartTokenId: Long = 50_258L,
    val eosTokenId: Long = 50_257L
)

class SpeechAssetBridge(
    private val context: Context
) {
    fun prepareWhisperTiny(): SpeechRuntimeBridgeState {
        val config = whisperTinyConfig()
        val requiredAssets = listOf(
            config.encoderAssetPath,
            config.decoderAssetPath,
            config.tokenizerAssetPath
        )
        return prepareBundle(
            bundleLabel = "Whisper tiny",
            assetPaths = requiredAssets,
            backendLibraryName = "libsnapknow_jni.so"
        )
    }

    fun whisperTinyConfig(): WhisperTinyAssetConfig {
        val defaultConfig = WhisperTinyAssetConfig()
        if (!assetExists(defaultConfig.manifestAssetPath)) {
            return defaultConfig
        }

        return runCatching {
            val manifestJson = context.assets.open(defaultConfig.manifestAssetPath).bufferedReader().use { it.readText() }
            val manifest = org.json.JSONObject(manifestJson)
            val decoderMaxCacheLen = manifest.optInt(
                "decoder_max_cache_len",
                defaultConfig.maxDecoderTokens
            )
            defaultConfig.copy(maxDecoderTokens = decoderMaxCacheLen)
        }.getOrElse {
            defaultConfig
        }
    }

    fun preparePiper(config: PiperVoiceConfig = PiperVoiceConfig()): SpeechRuntimeBridgeState {
        return prepareBundle(
            bundleLabel = "Piper ${config.voiceId}",
            assetPaths = listOf(config.modelAssetPath, config.configAssetPath),
            backendLibraryName = "libsnapknow_piper_runtime.so"
        )
    }

    private fun prepareBundle(
        bundleLabel: String,
        assetPaths: List<String>,
        backendLibraryName: String
    ): SpeechRuntimeBridgeState {
        val availableAssets = assetPaths.filter(::assetExists)
        val missingAssets = assetPaths - availableAssets.toSet()
        val runtimeDirectory = if (availableAssets.isNotEmpty()) {
            File(context.noBackupFilesDir, SPEECH_RUNTIME_ROOT).apply { mkdirs() }
        } else {
            null
        }

        val preparedFiles = buildList {
            val destinationRoot = runtimeDirectory ?: return@buildList
            availableAssets.forEach { assetPath ->
                val destination = File(destinationRoot, assetPath)
                copyAssetIfNeeded(assetPath, destination)
                add(destination)
            }
        }

        return SpeechRuntimeBridgeState(
            bundleLabel = bundleLabel,
            requiredAssets = assetPaths,
            missingAssets = missingAssets,
            preparedFiles = preparedFiles,
            runtimeDirectory = runtimeDirectory,
            backendLibraryName = backendLibraryName,
            backendLibraryPresent = hasNativeLibrary(backendLibraryName)
        )
    }

    private fun assetExists(assetPath: String): Boolean {
        return runCatching {
            context.assets.open(assetPath).close()
            true
        }.getOrDefault(false)
    }

    private fun copyAssetIfNeeded(assetPath: String, destination: File) {
        destination.parentFile?.mkdirs()
        if (destination.exists() && destination.length() > 0L) {
            return
        }
        context.assets.open(assetPath).use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun hasNativeLibrary(libraryName: String): Boolean {
        val nativeDir = context.applicationInfo.nativeLibraryDir ?: return false
        return File(nativeDir, libraryName).exists()
    }

    companion object {
        private val WHISPER_ASSET_DIR = File("speech/stt/whisper-tiny")
    }
}
