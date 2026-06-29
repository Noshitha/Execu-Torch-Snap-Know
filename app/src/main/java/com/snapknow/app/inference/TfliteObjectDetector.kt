package com.snapknow.app.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

private const val TAG = "TfliteObjectDetector"

class TfliteObjectDetector(
    private val context: Context,
    private val modelAssetName: String = DEFAULT_MODEL_ASSET
) : AutoCloseable {

    data class Detection(
        val boundingBox: Rect,
        val score: Float,
        val classId: Int,
        val label: String
    )

    @Volatile
    var status: String = "Object detector loading…"
        private set

    val isAvailable: Boolean
        get() = interpreter != null

    private var interpreter: Interpreter? = null
    private var inputTensor: Tensor? = null
    private var outputTensors: List<Tensor> = emptyList()
    private var inputWidth = 320
    private var inputHeight = 320
    private var inputChannels = 3
    private var inputType = DataType.UINT8
    private var lastRunAtMs = 0L
    private var cachedDetections: List<Detection> = emptyList()

    fun preload() {
        if (interpreter != null) return
        runCatching {
            val modelFile = ensureModelFile()
            val options = Interpreter.Options().apply {
                setNumThreads(2)
            }
            val created = Interpreter(modelFile, options)
            val inTensor = created.getInputTensor(0)
            val shape = inTensor.shape()
            require(shape.size == 4) { "Expected 4D input tensor, got ${shape.contentToString()}" }

            interpreter = created
            inputTensor = inTensor
            outputTensors = (0 until created.outputTensorCount).map(created::getOutputTensor)
            inputHeight = shape[1]
            inputWidth = shape[2]
            inputChannels = shape[3]
            inputType = inTensor.dataType()

            status = "Object detection ON (TFLite ${inputWidth}x$inputHeight)"
            Log.i(TAG, "Loaded $modelAssetName with ${outputTensors.size} outputs")
        }.onFailure { error ->
            status = "Object detection unavailable (${error.message ?: "load failed"})"
            Log.e(TAG, "Failed to load object detector", error)
        }
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        val runtime = interpreter ?: return emptyList()
        val now = SystemClock.elapsedRealtime()
        if (now - lastRunAtMs < MIN_DETECTION_INTERVAL_MS) {
            return cachedDetections
        }

        return runCatching {
            val scaled = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
            val inputBuffer = buildInputBuffer(scaled)
            val outputs = outputTensors.mapIndexed { index, tensor ->
                TensorOutput(index = index, tensor = tensor, buffer = allocateOutputBuffer(tensor))
            }
            val outputMap = outputs.associate { it.index to it.buffer }

            runtime.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)
            val parsed = parseOutputs(outputs, bitmap.width, bitmap.height)
            lastRunAtMs = now
            cachedDetections = parsed
            if (isAvailable) {
                status = "Object detection ON (${parsed.size} in frame)"
            }
            parsed
        }.getOrElse { error ->
            status = "Object detection error (${error.message ?: "inference failed"})"
            Log.e(TAG, "Object detection failed", error)
            cachedDetections
        }
    }

    override fun close() {
        interpreter?.close()
        interpreter = null
    }

    private fun ensureModelFile(): File {
        val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
        val outFile = File(modelsDir, modelAssetName)
        if (outFile.exists() && outFile.length() > 0L) return outFile

        context.assets.open(modelAssetName).use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
        return outFile
    }

    private fun buildInputBuffer(bitmap: Bitmap): ByteBuffer {
        val bytesPerChannel = if (inputType == DataType.FLOAT32) 4 else 1
        val buffer = ByteBuffer.allocateDirect(inputWidth * inputHeight * inputChannels * bytesPerChannel)
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputWidth * inputHeight)
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        for (pixel in pixels) {
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF

            if (inputChannels == 1) {
                val gray = ((red * 0.299f) + (green * 0.587f) + (blue * 0.114f)).toInt().coerceIn(0, 255)
                putValue(buffer, gray)
            } else {
                putValue(buffer, red)
                putValue(buffer, green)
                putValue(buffer, blue)
            }
        }

        buffer.rewind()
        return buffer
    }

    private fun putValue(buffer: ByteBuffer, value: Int) {
        when (inputType) {
            DataType.FLOAT32 -> buffer.putFloat(value / 255f)
            DataType.UINT8 -> buffer.put(value.toByte())
            else -> buffer.put(value.toByte())
        }
    }

    private fun allocateOutputBuffer(tensor: Tensor): Any {
        val shape = tensor.shape()
        return when (tensor.dataType()) {
            DataType.FLOAT32 -> allocateFloatArray(shape)
            DataType.INT32 -> allocateIntArray(shape)
            DataType.UINT8 -> allocateByteArray(shape)
            else -> ByteBuffer.allocateDirect(tensor.numBytes()).order(ByteOrder.nativeOrder())
        }
    }

    private fun parseOutputs(
        outputs: List<TensorOutput>,
        sourceWidth: Int,
        sourceHeight: Int
    ): List<Detection> {
        val shapesSummary = outputs.joinToString { output ->
            "${output.index}:${output.tensor.shape().contentToString()}/${output.tensor.dataType()}"
        }

        parseBoxesClassesScores(outputs, sourceWidth, sourceHeight)?.let { return it }
        parseBoxesAndLogits(outputs, sourceWidth, sourceHeight)?.let { return it }

        status = "Object detector loaded; unsupported output layout $shapesSummary"
        Log.w(TAG, "Unsupported detector outputs: $shapesSummary")
        return cachedDetections
    }

    private fun parseBoxesClassesScores(
        outputs: List<TensorOutput>,
        sourceWidth: Int,
        sourceHeight: Int
    ): List<Detection>? {
        val boxes = outputs.firstOrNull { it.tensor.shape().size == 3 && it.tensor.shape().last() == 4 } ?: return null
        val candidateCount = boxes.tensor.shape()[1]

        val scoreOutput = outputs.firstOrNull {
            it !== boxes && it.tensor.shape().contentEquals(intArrayOf(1, candidateCount))
        } ?: return null
        val classOutput = outputs.firstOrNull {
            it !== boxes && it !== scoreOutput && it.tensor.shape().contentEquals(intArrayOf(1, candidateCount))
        } ?: return null
        val countOutput = outputs.firstOrNull {
            it !== boxes && it !== scoreOutput && it !== classOutput &&
                (it.tensor.shape().contentEquals(intArrayOf(1)) || it.tensor.shape().contentEquals(intArrayOf(1, 1)))
        }

        val detectionCount = countOutput?.let(::readDetectionCount) ?: candidateCount
        val boxesValues = boxes.asFloat3()
        val scoreValues = scoreOutput.asFloat2()
        val classValues = classOutput.asFloat2()

        return buildDetectionsFromArrays(
            boxes = boxesValues[0],
            scores = scoreValues[0],
            classIds = classValues[0].map { it.toInt() },
            limit = detectionCount,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight
        )
    }

    private fun parseBoxesAndLogits(
        outputs: List<TensorOutput>,
        sourceWidth: Int,
        sourceHeight: Int
    ): List<Detection>? {
        val boxes = outputs.firstOrNull { it.tensor.shape().size == 3 && it.tensor.shape().last() == 4 } ?: return null
        val candidateCount = boxes.tensor.shape()[1]
        val logits = outputs.firstOrNull {
            it !== boxes && it.tensor.shape().size == 3 && it.tensor.shape()[1] == candidateCount
        } ?: return null

        val boxesValues = boxes.asFloat3()[0]
        val logitsValues = logits.asFloat3()[0]
        val detections = mutableListOf<Detection>()

        for (index in 0 until min(candidateCount, boxesValues.size)) {
            val classScores = logitsValues[index]
            if (classScores.isEmpty()) continue

            var bestClass = -1
            var bestScore = 0f
            for (classIndex in 1 until classScores.size) {
                val probability = logistic(classScores[classIndex])
                if (probability > bestScore) {
                    bestScore = probability
                    bestClass = classIndex
                }
            }

            if (bestClass <= 0 || bestScore < SCORE_THRESHOLD) continue
            toDetection(
                rawBox = boxesValues[index],
                score = bestScore,
                classId = bestClass,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight
            )?.let(detections::add)
        }

        return nonMaxSuppression(detections)
    }

    private fun buildDetectionsFromArrays(
        boxes: Array<FloatArray>,
        scores: FloatArray,
        classIds: List<Int>,
        limit: Int,
        sourceWidth: Int,
        sourceHeight: Int
    ): List<Detection> {
        val detections = mutableListOf<Detection>()
        val count = min(limit, min(boxes.size, min(scores.size, classIds.size)))
        for (index in 0 until count) {
            val score = scores[index]
            if (score < SCORE_THRESHOLD) continue
            toDetection(
                rawBox = boxes[index],
                score = score,
                classId = classIds[index],
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight
            )?.let(detections::add)
        }
        return nonMaxSuppression(detections)
    }

    private fun toDetection(
        rawBox: FloatArray,
        score: Float,
        classId: Int,
        sourceWidth: Int,
        sourceHeight: Int
    ): Detection? {
        if (rawBox.size < 4) return null

        val ymin = rawBox[0]
        val xmin = rawBox[1]
        val ymax = rawBox[2]
        val xmax = rawBox[3]
        if (!listOf(ymin, xmin, ymax, xmax).all { it.isFinite() }) return null

        val left = (xmin.coerceIn(0f, 1f) * sourceWidth).toInt()
        val top = (ymin.coerceIn(0f, 1f) * sourceHeight).toInt()
        val right = (xmax.coerceIn(0f, 1f) * sourceWidth).toInt()
        val bottom = (ymax.coerceIn(0f, 1f) * sourceHeight).toInt()
        if (right <= left || bottom <= top) return null

        return Detection(
            boundingBox = Rect(left, top, right, bottom),
            score = score,
            classId = classId,
            label = labelForClass(classId)
        )
    }

    private fun nonMaxSuppression(detections: List<Detection>): List<Detection> {
        val sorted = detections.sortedByDescending { it.score }
        val kept = mutableListOf<Detection>()
        for (candidate in sorted) {
            val overlaps = kept.any { existing ->
                existing.classId == candidate.classId &&
                    intersectionOverUnion(existing.boundingBox, candidate.boundingBox) > IOU_THRESHOLD
            }
            if (!overlaps) kept += candidate
            if (kept.size >= MAX_DETECTIONS) break
        }
        return kept
    }

    private fun intersectionOverUnion(first: Rect, second: Rect): Float {
        val left = max(first.left, second.left)
        val top = max(first.top, second.top)
        val right = min(first.right, second.right)
        val bottom = min(first.bottom, second.bottom)
        if (right <= left || bottom <= top) return 0f

        val intersection = (right - left) * (bottom - top)
        val firstArea = first.width() * first.height()
        val secondArea = second.width() * second.height()
        val union = firstArea + secondArea - intersection
        return if (union <= 0) 0f else intersection.toFloat() / union.toFloat()
    }

    private fun readDetectionCount(output: TensorOutput): Int {
        return when (val buffer = output.buffer) {
            is FloatArray -> buffer.firstOrNull()?.toInt() ?: 0
            is Array<*> -> ((buffer.firstOrNull() as? FloatArray)?.firstOrNull() ?: 0f).toInt()
            is IntArray -> buffer.firstOrNull() ?: 0
            else -> 0
        }
    }

    private fun labelForClass(classId: Int): String = "Object $classId"

    private fun logistic(value: Float): Float = (1.0 / (1.0 + exp(-value.toDouble()))).toFloat()

    private fun TensorOutput.asFloat2(): Array<FloatArray> {
        val params = tensor.quantizationParams()
        return when (val raw = buffer) {
            is Array<*> -> raw.map { row ->
                when (row) {
                    is FloatArray -> row
                    is ByteArray -> row.map { params.dequantize(it.toInt() and 0xFF) }.toFloatArray()
                    is IntArray -> row.map { params.dequantize(it) }.toFloatArray()
                    else -> FloatArray(0)
                }
            }.toTypedArray()
            else -> emptyArray()
        }
    }

    private fun TensorOutput.asFloat3(): Array<Array<FloatArray>> {
        val params = tensor.quantizationParams()
        return when (val raw = buffer) {
            is Array<*> -> raw.map { plane ->
                when (plane) {
                    is Array<*> -> plane.map { row ->
                        when (row) {
                            is FloatArray -> row
                            is ByteArray -> row.map { params.dequantize(it.toInt() and 0xFF) }.toFloatArray()
                            is IntArray -> row.map { params.dequantize(it) }.toFloatArray()
                            else -> FloatArray(0)
                        }
                    }.toTypedArray()
                    else -> emptyArray()
                }
            }.toTypedArray()
            else -> emptyArray()
        }
    }

    private fun Tensor.QuantizationParams.dequantize(value: Int): Float {
        return if (scale == 0f) value.toFloat() else (value - zeroPoint) * scale
    }

    private data class TensorOutput(
        val index: Int,
        val tensor: Tensor,
        val buffer: Any
    )

    companion object {
        private const val DEFAULT_MODEL_ASSET = "fssd_25_8bit_v2.tflite"
        private const val SCORE_THRESHOLD = 0.45f
        private const val IOU_THRESHOLD = 0.45f
        private const val MAX_DETECTIONS = 6
        private const val MIN_DETECTION_INTERVAL_MS = 350L

        private fun allocateFloatArray(shape: IntArray): Any = when (shape.size) {
            0 -> FloatArray(1)
            1 -> FloatArray(shape[0])
            2 -> Array(shape[0]) { FloatArray(shape[1]) }
            3 -> Array(shape[0]) { Array(shape[1]) { FloatArray(shape[2]) } }
            4 -> Array(shape[0]) { Array(shape[1]) { Array(shape[2]) { FloatArray(shape[3]) } } }
            else -> ByteBuffer.allocateDirect(shape.fold(1) { acc, dim -> acc * dim } * 4)
                .order(ByteOrder.nativeOrder())
        }

        private fun allocateIntArray(shape: IntArray): Any = when (shape.size) {
            0 -> IntArray(1)
            1 -> IntArray(shape[0])
            2 -> Array(shape[0]) { IntArray(shape[1]) }
            3 -> Array(shape[0]) { Array(shape[1]) { IntArray(shape[2]) } }
            else -> IntArray(shape.fold(1) { acc, dim -> acc * dim })
        }

        private fun allocateByteArray(shape: IntArray): Any = when (shape.size) {
            0 -> ByteArray(1)
            1 -> ByteArray(shape[0])
            2 -> Array(shape[0]) { ByteArray(shape[1]) }
            3 -> Array(shape[0]) { Array(shape[1]) { ByteArray(shape[2]) } }
            4 -> Array(shape[0]) { Array(shape[1]) { Array(shape[2]) { ByteArray(shape[3]) } } }
            else -> ByteArray(shape.fold(1) { acc, dim -> acc * dim })
        }

        fun formatSummary(detections: List<Detection>): String {
            if (detections.isEmpty()) return "No objects highlighted right now."
            return detections.joinToString(
                prefix = "Objects in view: ",
                separator = " · "
            ) { detection ->
                String.format(
                    Locale.US,
                    "%s %d%%",
                    detection.label,
                    (detection.score * 100).toInt()
                )
            }
        }
    }
}
