package com.snapknow.app.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "ImageVlmModel"
private const val DEFAULT_MANIFEST_ASSET = "vlm/smolvlm_manifest.json"

data class ImageVlmRequest(
    val prompt: String,
    val isQuestion: Boolean
)

data class ImageVlmResponse(
    val spokenText: String,
    val debugSummary: String
)

enum class ImageVlmStage {
    MISSING_ASSETS,
    ENCODER_READY,
    FULL_ASSETS_PRESENT
}

data class ImageVlmReadiness(
    val stage: ImageVlmStage,
    val statusLine: String,
    val missingArtifacts: List<String>,
    val installedArtifacts: List<String>
) {
    val canProcessImage: Boolean
        get() = stage == ImageVlmStage.ENCODER_READY || stage == ImageVlmStage.FULL_ASSETS_PRESENT
}

private data class ImageVlmArtifact(
    val name: String,
    val assetPath: String,
    val requiredFor: String
)

private data class ImageVlmManifest(
    val modelId: String,
    val displayName: String,
    val manifestAssetPath: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val mean: FloatArray,
    val std: FloatArray,
    val artifacts: List<ImageVlmArtifact>
) {
    fun artifactsFor(stage: String): List<ImageVlmArtifact> =
        artifacts.filter { it.requiredFor == stage }

    companion object {
        fun fallback(): ImageVlmManifest =
            ImageVlmManifest(
                modelId = "HuggingFaceTB/SmolVLM-256M-Instruct",
                displayName = "SmolVLM-256M-Instruct",
                manifestAssetPath = DEFAULT_MANIFEST_ASSET,
                imageWidth = 384,
                imageHeight = 384,
                mean = floatArrayOf(0.5f, 0.5f, 0.5f),
                std = floatArrayOf(0.5f, 0.5f, 0.5f),
                artifacts = listOf(
                    ImageVlmArtifact("vision_encoder", "vlm/smolvlm/vision_encoder.pte", "scene_embedding"),
                    ImageVlmArtifact("projector", "vlm/smolvlm/projector.pte", "scene_qa"),
                    ImageVlmArtifact("text_decoder", "vlm/smolvlm/text_decoder.pte", "scene_qa"),
                    ImageVlmArtifact("tokenizer", "vlm/smolvlm/tokenizer.json", "scene_qa"),
                    ImageVlmArtifact("tokenizer_config", "vlm/smolvlm/tokenizer_config.json", "scene_qa"),
                    ImageVlmArtifact("special_tokens_map", "vlm/smolvlm/special_tokens_map.json", "scene_qa")
                )
            )

        fun fromJson(json: String): ImageVlmManifest {
            val root = JSONObject(json)
            val input = root.getJSONObject("image_input")
            return ImageVlmManifest(
                modelId = root.getString("model_id"),
                displayName = root.getString("display_name"),
                manifestAssetPath = root.optString("manifest_asset_path", DEFAULT_MANIFEST_ASSET),
                imageWidth = input.getInt("width"),
                imageHeight = input.getInt("height"),
                mean = input.getJSONArray("mean").toFloatArray(),
                std = input.getJSONArray("std").toFloatArray(),
                artifacts = root.getJSONArray("artifacts").toArtifacts()
            )
        }

        private fun JSONArray.toFloatArray(): FloatArray =
            FloatArray(length()) { index -> getDouble(index).toFloat() }

        private fun JSONArray.toArtifacts(): List<ImageVlmArtifact> =
            buildList {
                for (index in 0 until length()) {
                    val artifact = getJSONObject(index)
                    add(
                        ImageVlmArtifact(
                            name = artifact.getString("name"),
                            assetPath = artifact.getString("asset_path"),
                            requiredFor = artifact.getString("required_for")
                        )
                    )
                }
            }
    }
}

class ImageVlmModel(context: Context) : AutoCloseable {

    private val appContext = context.applicationContext
    private var manifest: ImageVlmManifest = ImageVlmManifest.fallback()
    private var readiness: ImageVlmReadiness = ImageVlmReadiness(
        stage = ImageVlmStage.MISSING_ASSETS,
        statusLine = "Scene VLM: checking assets…",
        missingArtifacts = emptyList(),
        installedArtifacts = emptyList()
    )
    private var visionEncoder: ExecuTorchModule? = null

    suspend fun preload() {
        withContext(Dispatchers.IO) {
            manifest = loadManifest()
            readiness = inspectAssets()
            if (readiness.canProcessImage) {
                visionEncoder = ExecuTorchModule.fromAsset(appContext, manifest.artifactsFor("scene_embedding").first().assetPath)
                if (visionEncoder == null) {
                    readiness = readiness.copy(
                        stage = ImageVlmStage.MISSING_ASSETS,
                        statusLine = "Scene VLM: encoder asset present but could not be loaded"
                    )
                }
            }
            Log.i(TAG, readiness.statusLine)
        }
    }

    fun getReadiness(): ImageVlmReadiness = readiness

    fun getUnavailableMessage(): String {
        if (readiness.canProcessImage) {
            return "Scene understanding is partly installed. I can load the SmolVLM vision encoder, but I still need the decoder and tokenizer bundle before I can answer in plain language."
        }
        val missing = readiness.missingArtifacts.joinToString(", ")
        return "Scene understanding isn't installed yet. Missing assets: $missing."
    }

    suspend fun analyze(bitmap: Bitmap, request: ImageVlmRequest): ImageVlmResponse {
        return withContext(Dispatchers.Default) {
            val module = visionEncoder
            if (module == null) {
                ImageVlmResponse(
                    spokenText = getUnavailableMessage(),
                    debugSummary = readiness.statusLine
                )
            } else {
                val input = preprocess(bitmap)
                val output = module.runNamedInference("forward", input, longArrayOf(1, 3, manifest.imageHeight.toLong(), manifest.imageWidth.toLong()))
                val tokenEstimate = output.size / 1024
                val baseSummary = "${manifest.displayName} encoder output: ${output.size} floats from a ${manifest.imageWidth}x${manifest.imageHeight} RGB frame."
                val spokenText = if (readiness.stage == ImageVlmStage.FULL_ASSETS_PRESENT) {
                    "I captured the image and the full SmolVLM asset bundle is present, but this branch still stops at the vision encoder stage. I produced on-device image features, and the next merge step is wiring the tokenizer and decoder loop for spoken answers."
                } else {
                    val requestText = if (request.isQuestion) "question" else "scene description"
                    "I captured the image for your $requestText and ran the on-device SmolVLM vision encoder. I still need the decoder and tokenizer assets before I can answer in natural language."
                }
                ImageVlmResponse(
                    spokenText = spokenText,
                    debugSummary = "$baseSummary Approx token grid: ${tokenEstimate.coerceAtLeast(1)} chunks."
                )
            }
        }
    }

    override fun close() {
        visionEncoder?.close()
        visionEncoder = null
    }

    private fun loadManifest(): ImageVlmManifest {
        return try {
            val json = appContext.assets.open(DEFAULT_MANIFEST_ASSET).bufferedReader().use { it.readText() }
            ImageVlmManifest.fromJson(json)
        } catch (error: Exception) {
            Log.w(TAG, "Falling back to built-in SmolVLM manifest", error)
            ImageVlmManifest.fallback()
        }
    }

    private fun inspectAssets(): ImageVlmReadiness {
        val embeddingArtifacts = manifest.artifactsFor("scene_embedding")
        val qaArtifacts = manifest.artifactsFor("scene_qa")
        val installed = mutableListOf<String>()
        val missing = mutableListOf<String>()

        (embeddingArtifacts + qaArtifacts).forEach { artifact ->
            if (assetExists(artifact.assetPath)) {
                installed += artifact.name
            } else {
                missing += artifact.name
            }
        }

        val encoderReady = embeddingArtifacts.all { assetExists(it.assetPath) }
        val fullAssets = encoderReady && qaArtifacts.all { assetExists(it.assetPath) }
        val statusLine = when {
            fullAssets -> "Scene VLM: full SmolVLM asset bundle present; decoder loop still pending"
            encoderReady -> "Scene VLM: vision encoder ready; add decoder/tokenizer assets for QA"
            else -> "Scene VLM: missing ${missing.joinToString(", ")}"
        }
        val stage = when {
            fullAssets -> ImageVlmStage.FULL_ASSETS_PRESENT
            encoderReady -> ImageVlmStage.ENCODER_READY
            else -> ImageVlmStage.MISSING_ASSETS
        }
        return ImageVlmReadiness(
            stage = stage,
            statusLine = statusLine,
            missingArtifacts = missing,
            installedArtifacts = installed
        )
    }

    private fun assetExists(path: String): Boolean {
        return try {
            appContext.assets.open(path).close()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun preprocess(bitmap: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(bitmap, manifest.imageWidth, manifest.imageHeight, true)
        val pixels = IntArray(manifest.imageWidth * manifest.imageHeight)
        scaled.getPixels(pixels, 0, manifest.imageWidth, 0, 0, manifest.imageWidth, manifest.imageHeight)

        val channelSize = manifest.imageWidth * manifest.imageHeight
        val output = FloatArray(channelSize * 3)
        pixels.forEachIndexed { index, pixel ->
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f
            output[index] = (r - manifest.mean[0]) / manifest.std[0]
            output[index + channelSize] = (g - manifest.mean[1]) / manifest.std[1]
            output[index + channelSize * 2] = (b - manifest.mean[2]) / manifest.std[2]
        }
        if (scaled !== bitmap) {
            scaled.recycle()
        }
        return output
    }
}
