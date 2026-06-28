package com.snapknow.app.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Transparent overlay drawn on top of the camera preview.
 * Renders bounding boxes and name labels for each detected face.
 *
 * Call [updateFaces] from the main thread whenever the face list changes.
 */
class FaceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class FaceAnnotation(
        val bounds: RectF,        // Scaled to this view's coordinate space
        val label: String,        // e.g. "John · son" or "Unknown"
        val isKnown: Boolean
    )

    private var faces: List<FaceAnnotation> = emptyList()

    // Paint for known faces: green box
    private val knownBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(220, 72, 199, 142)   // mint green
        strokeWidth = 4f
    }

    // Paint for unknown faces: white dashed box
    private val unknownBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(180, 255, 255, 255)
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    private val labelBgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.argb(200, 0, 0, 0)
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
        typeface = Typeface.DEFAULT_BOLD
    }

    private val unknownTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 200, 200, 200)
        textSize = 34f
        typeface = Typeface.DEFAULT
    }

    // Scaling factors from image space → view space
    private var scaleX = 1f
    private var scaleY = 1f

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Update overlay with the latest face list.
     * [imageWidth] / [imageHeight] are the dimensions of the camera frame
     * from which [faces] bounding boxes were computed.
     */
    fun updateFaces(
        rawFaces: List<Pair<android.graphics.Rect, String>>,
        imageWidth: Int,
        imageHeight: Int
    ) {
        if (imageWidth > 0 && imageHeight > 0) {
            scaleX = width.toFloat()  / imageWidth.toFloat()
            scaleY = height.toFloat() / imageHeight.toFloat()
        }

        faces = rawFaces.map { (rect, label) ->
            FaceAnnotation(
                bounds = RectF(
                    rect.left   * scaleX,
                    rect.top    * scaleY,
                    rect.right  * scaleX,
                    rect.bottom * scaleY
                ),
                label = label,
                isKnown = label != "Unknown" && label.isNotEmpty()
            )
        }
        invalidate()
    }

    fun clearFaces() {
        faces = emptyList()
        invalidate()
    }

    // ─── Drawing ──────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        faces.forEach { face ->
            drawFace(canvas, face)
        }
    }

    private fun drawFace(canvas: Canvas, face: FaceAnnotation) {
        val r = face.bounds
        val paint = if (face.isKnown) knownBoxPaint else unknownBoxPaint
        // Rounded rectangle for a friendlier look
        canvas.drawRoundRect(r, 16f, 16f, paint)

        val text = face.label.ifEmpty { "?" }
        val textPaint = if (face.isKnown) labelTextPaint else unknownTextPaint
        val textWidth = textPaint.measureText(text)
        val textHeight = textPaint.textSize

        // Label background pill
        val labelLeft  = r.left
        val labelTop   = (r.top - textHeight - 16f).coerceAtLeast(0f)
        val labelRight = (r.left + textWidth + 24f).coerceAtMost(width.toFloat())
        val labelBottom = labelTop + textHeight + 16f

        canvas.drawRoundRect(labelLeft, labelTop, labelRight, labelBottom, 8f, 8f, labelBgPaint)
        canvas.drawText(text, labelLeft + 12f, labelBottom - 10f, textPaint)
    }
}
