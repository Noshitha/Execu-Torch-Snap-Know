package com.snapknow.app.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.snapknow.app.inference.TfliteObjectDetector

private const val TAG = "FaceAnalyzer"

/**
 * CameraX [ImageAnalysis.Analyzer] that:
 * 1. Runs Google ML Kit face detection on each frame (on-device, no network)
 * 2. Crops each detected face from the frame into a [Bitmap]
 * 3. Delivers results to [onFacesDetected]
 *
 * Results include the original bounding box (in image coordinates) so the
 * caller can scale them to screen coordinates in [FaceOverlayView].
 */
class FaceAnalyzer(
    private val objectDetector: TfliteObjectDetector? = null,
    private val onFacesDetected: (
        faces: List<DetectedFace>,
        objects: List<DetectedObject>,
        imageWidth: Int,
        imageHeight: Int
    ) -> Unit
) : ImageAnalysis.Analyzer {

    data class DetectedFace(
        val bounds: Rect,       // In image coordinate space
        val trackingId: Int?,
        val bitmap: Bitmap      // Cropped face region at original scale
    )

    data class DetectedObject(
        val bounds: RectF,
        val label: String,
        val score: Float
    )

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .enableTracking()   // Gives stable tracking IDs across frames
            .setMinFaceSize(0.10f)
            .build()
    )

    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }

        val rotation = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)
        val imgWidth  = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
        val imgHeight = if (rotation == 90 || rotation == 270) imageProxy.width  else imageProxy.height

        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                val fullBitmap = imageProxy.toBitmap(rotation)
                val objects = objectDetector
                    ?.detect(fullBitmap)
                    ?.map { detection ->
                        DetectedObject(
                            bounds = RectF(detection.boundingBox),
                            label = detection.label,
                            score = detection.score
                        )
                    }
                    .orEmpty()

                if (faces.isNotEmpty()) {
                    val detected = faces.mapNotNull { face ->
                        cropFace(fullBitmap, face, imgWidth, imgHeight)?.let { crop ->
                            DetectedFace(
                                bounds = face.boundingBox,
                                trackingId = face.trackingId,
                                bitmap = crop
                            )
                        }
                    }
                    onFacesDetected(detected, objects, imgWidth, imgHeight)
                } else {
                    onFacesDetected(emptyList(), objects, imgWidth, imgHeight)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Face detection failed", e)
                onFacesDetected(emptyList(), emptyList(), imgWidth, imgHeight)
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    /**
     * Crops [face] from [bitmap], adding 20% padding on each side to
     * ensure the embedding model sees the full face including forehead/chin.
     */
    private fun cropFace(bitmap: Bitmap, face: Face, imgW: Int, imgH: Int): Bitmap? {
        return try {
            val box = face.boundingBox
            val pad = (box.width() * 0.20f).toInt()
            val left   = (box.left   - pad).coerceAtLeast(0)
            val top    = (box.top    - pad).coerceAtLeast(0)
            val right  = (box.right  + pad).coerceAtMost(bitmap.width)
            val bottom = (box.bottom + pad).coerceAtMost(bitmap.height)
            val w = right  - left
            val h = bottom - top
            if (w <= 0 || h <= 0) return null
            Bitmap.createBitmap(bitmap, left, top, w, h)
        } catch (e: Exception) {
            Log.w(TAG, "Face crop failed", e)
            null
        }
    }

    // ─── ImageProxy → Bitmap ──────────────────────────────────────────────────

    private fun ImageProxy.toBitmap(rotationDegrees: Int): Bitmap {
        val yuvToRgbConverter = YuvToRgbConverter
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        yuvToRgbConverter.yuvToRgb(this, bitmap)
        return if (rotationDegrees != 0) bitmap.rotate(rotationDegrees) else bitmap
    }

    private fun Bitmap.rotate(degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }
}

// ─── Minimal YUV → RGB converter ─────────────────────────────────────────────

object YuvToRgbConverter {
    fun yuvToRgb(image: ImageProxy, output: Bitmap) {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = android.graphics.YuvImage(
            nv21, android.graphics.ImageFormat.NV21,
            image.width, image.height, null
        )
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, image.width, image.height), 90, out)
        val jpeg = out.toByteArray()
        val decoded = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        val canvas = android.graphics.Canvas(output)
        canvas.drawBitmap(decoded, 0f, 0f, null)
    }
}
