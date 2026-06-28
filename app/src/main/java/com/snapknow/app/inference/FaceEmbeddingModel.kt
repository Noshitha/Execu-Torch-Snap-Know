package com.snapknow.app.inference

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

private const val TAG = "FaceEmbeddingModel"

// InceptionResnetV1 expects 112×112 RGB images, normalised to [-1, 1]
private const val FACE_SIZE = 112

/**
 * Extracts 512-dim L2-normalised face embeddings using InceptionResnetV1
 * via PyTorch Mobile (TorchScript, CPU).
 *
 * Construction is lightweight. Call [preload] on a background coroutine
 * before using [getEmbedding].
 */
class FaceEmbeddingModel(private val context: Context) {

    private var module: Module? = null

    /** True once [preload] has succeeded */
    val isAvailable: Boolean get() = module != null

    /**
     * Extracts the model asset (107 MB, first run only) and loads it via
     * PyTorch Mobile. Must be called from a coroutine — runs on [Dispatchers.IO].
     */
    suspend fun preload() = withContext(Dispatchers.IO) {
        if (module != null) return@withContext
        try {
            val modelFile = extractAsset("face_embedding.pt") ?: return@withContext
            module = Module.load(modelFile.absolutePath)
            Log.i(TAG, "PyTorch Mobile model loaded (${modelFile.length() / 1_048_576} MB)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load face embedding model", e)
        }
    }

    /**
     * Returns a 512-dim L2-normalised embedding for [faceBitmap], or null if
     * the model isn't loaded yet.
     */
    fun getEmbedding(faceBitmap: Bitmap): FloatArray? {
        val mod = module ?: return null
        return try {
            val resized = Bitmap.createScaledBitmap(faceBitmap, FACE_SIZE, FACE_SIZE, true)
            val input = preprocessBitmap(resized)
            val inputTensor = Tensor.fromBlob(
                input, longArrayOf(1, 3, FACE_SIZE.toLong(), FACE_SIZE.toLong())
            )
            val output = mod.forward(IValue.from(inputTensor)).toTensor()
            l2Normalize(output.dataAsFloatArray)
        } catch (e: Exception) {
            Log.e(TAG, "Embedding inference failed", e)
            null
        }
    }

    fun close() {
        module = null
    }

    // ─── Asset extraction ─────────────────────────────────────────────────────

    private fun extractAsset(name: String): File? {
        return try {
            val dest = File(context.filesDir, name)
            if (!dest.exists()) {
                Log.i(TAG, "Extracting $name from assets (first run)…")
                context.assets.open(name).use { src ->
                    FileOutputStream(dest).use { dst -> src.copyTo(dst) }
                }
                Log.i(TAG, "Extracted $name (${dest.length() / 1_048_576} MB)")
            }
            dest
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract asset $name", e)
            null
        }
    }

    // ─── Pre-processing ───────────────────────────────────────────────────────

    private fun preprocessBitmap(bitmap: Bitmap): FloatArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val result = FloatArray(3 * h * w)
        for (i in pixels.indices) {
            val px = pixels[i]
            result[i]             = ((px shr 16) and 0xFF) / 127.5f - 1f
            result[i + h * w]     = ((px shr 8)  and 0xFF) / 127.5f - 1f
            result[i + 2 * h * w] = ( px         and 0xFF) / 127.5f - 1f
        }
        return result
    }

    // ─── L2 normalisation ─────────────────────────────────────────────────────

    private fun l2Normalize(v: FloatArray): FloatArray {
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm).coerceAtLeast(1e-10f)
        return FloatArray(v.size) { v[it] / norm }
    }
}
