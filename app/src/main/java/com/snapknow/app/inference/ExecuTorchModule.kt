package com.snapknow.app.inference

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

private const val TAG = "ExecuTorchModule"

/**
 * Kotlin wrapper around the native ExecuTorch module (libsnapknow_jni.so).
 *
 * Lifecycle:
 *   val module = ExecuTorchModule.fromAsset(context, "face_embedding.pte")
 *   val output = module?.runInference(inputData, longArrayOf(1, 3, 112, 112))
 *   module?.close()
 *
 * If the model file is absent (e.g. before running the conversion script),
 * [fromAsset] returns null and the app operates in camera-only mode (face
 * detection boxes are drawn but recognition is disabled).
 */
class ExecuTorchModule private constructor(modelPath: String) : AutoCloseable {

    private var handle: Long = 0L

    init {
        handle = nativeLoadModel(modelPath)
        if (handle == 0L) {
            throw RuntimeException("ExecuTorch failed to load model: $modelPath")
        }
        Log.i(TAG, "Loaded model: $modelPath  handle=$handle")
    }

    /**
     * Runs a single forward pass.
     *
     * @param input     Flat float array (e.g. CHW normalised pixels)
     * @param shape     Tensor shape matching the input (e.g. [1,3,112,112])
     * @return          Flat float array of model output
     */
    fun runInference(input: FloatArray, shape: LongArray): FloatArray {
        return runNamedInference("forward", input, shape)
    }

    fun runNamedInference(methodName: String, input: FloatArray, shape: LongArray): FloatArray {
        check(handle != 0L) { "Module already closed" }
        return nativeRunNamedInference(handle, methodName, input, shape)
    }

    fun hasMethod(methodName: String): Boolean {
        check(handle != 0L) { "Module already closed" }
        return nativeHasMethod(handle, methodName)
    }

    override fun close() {
        if (handle != 0L) {
            nativeDestroyModule(handle)
            handle = 0L
        }
    }

    // ─── Native declarations ──────────────────────────────────────────────────

    private external fun nativeLoadModel(path: String): Long
    private external fun nativeRunNamedInference(handle: Long, methodName: String, input: FloatArray, shape: LongArray): FloatArray
    private external fun nativeHasMethod(handle: Long, methodName: String): Boolean
    private external fun nativeDestroyModule(handle: Long)

    // ─── Companion ────────────────────────────────────────────────────────────

    companion object {

        private var libLoaded = false

        private fun ensureLib() {
            if (!libLoaded) {
                System.loadLibrary("snapknow_jni")
                libLoaded = true
                Log.i(TAG, "libsnapknow_jni loaded")
            }
        }

        /**
         * Copies [assetName] from app assets to internal storage (so the C++
         * layer can open it as a file path) then creates a module.
         *
         * Returns null if the asset doesn't exist or loading fails.
         */
        fun fromAsset(context: Context, assetName: String): ExecuTorchModule? {
            return try {
                ensureLib()
                val modelFile = extractAsset(context, assetName)
                    ?: run {
                        Log.w(TAG, "Asset '$assetName' not found — running without ExecuTorch")
                        return null
                    }
                ExecuTorchModule(modelFile.absolutePath)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create ExecuTorchModule", e)
                null
            }
        }

        /** Extracts an asset to internal storage if not already there. */
        private fun extractAsset(context: Context, name: String): File? {
            return try {
                val dest = File(context.filesDir, name)
                dest.parentFile?.mkdirs()
                if (!dest.exists()) {
                    context.assets.open(name).use { src ->
                        FileOutputStream(dest).use { dst -> src.copyTo(dst) }
                    }
                    Log.d(TAG, "Extracted asset: $name → ${dest.absolutePath}")
                }
                dest
            } catch (e: Exception) {
                Log.w(TAG, "Asset '$name' not available: ${e.message}")
                null
            }
        }
    }
}
