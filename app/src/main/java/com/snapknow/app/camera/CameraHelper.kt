package com.snapknow.app.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "CameraHelper"

/**
 * Manages the CameraX pipeline:
 *   • Preview bound to [PreviewView]
 *   • ImageAnalysis for face detection (runs on a dedicated executor)
 *
 * Toggle between front and back camera with [flipCamera].
 */
class CameraHelper(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val faceAnalyzer: ImageAnalysis.Analyzer
) {

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // ─── Public API ───────────────────────────────────────────────────────────

    fun start() {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            cameraProvider = future.get()
            bindCamera()
        }, ContextCompat.getMainExecutor(context))
    }

    fun flipCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT
        else
            CameraSelector.LENS_FACING_BACK
        bindCamera()
    }

    fun stop() {
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
    }

    val isFrontCamera: Boolean get() = lensFacing == CameraSelector.LENS_FACING_FRONT

    // ─── Binding ──────────────────────────────────────────────────────────────

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val selector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val analysis = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            // STRATEGY_KEEP_ONLY_LATEST drops frames if the analyzer is busy —
            // perfect for face recognition (never stall the pipeline)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { it.setAnalyzer(analysisExecutor, faceAnalyzer) }

        try {
            camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            Log.d(TAG, "Camera bound (${if (lensFacing == CameraSelector.LENS_FACING_BACK) "BACK" else "FRONT"})")
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)
        }
    }
}
