package com.google.mediapipe.examples.facelandmarker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.max
import kotlin.math.min

class OverlayView(context: Context?, attrs: AttributeSet?) :
    View(context, attrs) {

    private var results: FaceLandmarkerResult? = null
    private var linePaint = Paint()
    private var pointPaint = Paint()
    private var boxPaint = Paint()
    private var labelPaint = Paint()
    private var labelBackgroundPaint = Paint()

    private var scaleFactor: Float = 1f
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

    private var boundingBox: RectF? = null
    private var boxLabel: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastBoxTimestamp: Long = 0L
    private val boundingBoxDuration = 2000L // 2 seconds

    init {
        initPaints()
    }

    fun clear() {
        results = null
        boundingBox = null
        boxLabel = null
        linePaint.reset()
        pointPaint.reset()
        boxPaint.reset()
        labelPaint.reset()
        labelBackgroundPaint.reset()
        invalidate()
        initPaints()
    }

    private fun initPaints() {
        linePaint.color = ContextCompat.getColor(context!!, R.color.mp_color_primary)
        linePaint.strokeWidth = LANDMARK_STROKE_WIDTH
        linePaint.style = Paint.Style.STROKE

        pointPaint.color = Color.YELLOW
        pointPaint.strokeWidth = LANDMARK_STROKE_WIDTH
        pointPaint.style = Paint.Style.FILL

        boxPaint.color = Color.RED
        boxPaint.strokeWidth = 5f
        boxPaint.style = Paint.Style.STROKE

        labelPaint.color = Color.WHITE
        labelPaint.textSize = 60f
        labelPaint.isAntiAlias = true
        labelPaint.style = Paint.Style.FILL

        labelBackgroundPaint.color = Color.RED
        labelBackgroundPaint.style = Paint.Style.FILL
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        if (results?.faceLandmarks().isNullOrEmpty()) {
            clear()
            return
        }

        results?.let { faceLandmarkerResult ->
            val scaledImageWidth = imageWidth * scaleFactor
            val scaledImageHeight = imageHeight * scaleFactor

            val offsetX = (width - scaledImageWidth) / 2f
            val offsetY = (height - scaledImageHeight) / 2f

            faceLandmarkerResult.faceLandmarks().forEach { faceLandmarks ->
                 drawFaceLandmarks(canvas, faceLandmarks, offsetX, offsetY)
                // drawFaceConnectors(canvas, faceLandmarks, offsetX, offsetY)
            }

            val currentTime = System.currentTimeMillis()
            if (boundingBox != null && currentTime - lastBoxTimestamp <= boundingBoxDuration) {
                canvas.drawRect(boundingBox!!, boxPaint)
                boxLabel?.let {
                    val labelPadding = 10f
                    val textWidth = labelPaint.measureText(it)
                    val labelHeight = labelPaint.textSize + labelPadding * 2
                    val rectLeft = boundingBox!!.left
                    val rectTop = boundingBox!!.top - labelHeight
                    val rectRight = rectLeft + textWidth + labelPadding * 2
                    val rectBottom = boundingBox!!.top

                    canvas.drawRect(
                        rectLeft,
                        rectTop,
                        rectRight,
                        rectBottom,
                        labelBackgroundPaint
                    )
                    canvas.drawText(it, rectLeft + labelPadding, rectBottom - labelPadding, labelPaint)
                }
            } else {
                if (currentTime - lastBoxTimestamp > boundingBoxDuration) {
                    boundingBox = null
                    boxLabel = null
                }
                else {}
            }
        }
    }

    private val SELECTED_LANDMARK_INDICES = listOf(
        10, 152, 1, 13, 14, 78, 308, 159, 145, 386, 374
    )

    private fun drawFaceLandmarks(
        canvas: Canvas,
        faceLandmarks: List<NormalizedLandmark>,
        offsetX: Float,
        offsetY: Float
    ) {
        /*for (index in SELECTED_LANDMARK_INDICES) {
            val landmark = faceLandmarks.getOrNull(index) ?: continue
            val x = landmark.x() * imageWidth * scaleFactor + offsetX
            val y = landmark.y() * imageHeight * scaleFactor + offsetY
            canvas.drawPoint(x, y, pointPaint)
        }*/
        faceLandmarks.forEach { landmark ->
            val x = landmark.x() * imageWidth * scaleFactor + offsetX
            val y = landmark.y() * imageHeight * scaleFactor + offsetY
            canvas.drawPoint(x, y, pointPaint)
        }
    }

    private fun drawFaceConnectors(
        canvas: Canvas,
        faceLandmarks: List<NormalizedLandmark>,
        offsetX: Float,
        offsetY: Float
    ) {
        FaceLandmarker.FACE_LANDMARKS_CONNECTORS.filterNotNull().forEach { connector ->
            val startLandmark = faceLandmarks.getOrNull(connector.start())
            val endLandmark = faceLandmarks.getOrNull(connector.end())

            if (startLandmark != null && endLandmark != null) {
                val startX = startLandmark.x() * imageWidth * scaleFactor + offsetX
                val startY = startLandmark.y() * imageHeight * scaleFactor + offsetY
                val endX = endLandmark.x() * imageWidth * scaleFactor + offsetX
                val endY = endLandmark.y() * imageHeight * scaleFactor + offsetY

                canvas.drawLine(startX, startY, endX, endY, linePaint)
            }
        }
    }

    fun setResults(
        faceLandmarkerResults: FaceLandmarkerResult,
        imageHeight: Int,
        imageWidth: Int,
        runningMode: RunningMode = RunningMode.IMAGE
    ) {
        results = faceLandmarkerResults

        this.imageHeight = imageHeight
        this.imageWidth = imageWidth

        scaleFactor = when (runningMode) {
            RunningMode.IMAGE,
            RunningMode.VIDEO -> {
                min(width * 1f / imageWidth, height * 1f / imageHeight)
            }
            RunningMode.LIVE_STREAM -> {
                max(width * 1f / imageWidth, height * 1f / imageHeight)
            }
        }
        invalidate()
    }

    fun setBoundingBoxFromLandmarks(landmarks: List<NormalizedLandmark>, label: String? = null) {
        if (landmarks.isEmpty()) return

        val xCoords = landmarks.map { it.x() }
        val yCoords = landmarks.map { it.y() }

        val left = xCoords.minOrNull() ?: 0f
        val right = xCoords.maxOrNull() ?: 0f
        val top = yCoords.minOrNull() ?: 0f
        val bottom = yCoords.maxOrNull() ?: 0f

        setBoundingBox(
            left * imageWidth,
            top * imageHeight,
            right * imageWidth,
            bottom * imageHeight,
            label
        )
    }

    fun setBoundingBox(left: Float, top: Float, right: Float, bottom: Float, label: String? = null) {
        val scaledLeft = left * scaleFactor + (width - imageWidth * scaleFactor) / 2f
        val scaledTop = top * scaleFactor + (height - imageHeight * scaleFactor) / 2f
        val scaledRight = right * scaleFactor + (width - imageWidth * scaleFactor) / 2f
        val scaledBottom = bottom * scaleFactor + (height - imageHeight * scaleFactor) / 2f

        boundingBox = RectF(scaledLeft, scaledTop, scaledRight, scaledBottom)
        boxLabel = label

        val boxColor = when (label) {
            "Yawn" -> Color.YELLOW
            "Drowsy" -> Color.rgb(255, 165, 0)
            "Sleep" -> Color.RED
            else -> Color.RED
        }
        boxPaint.color = boxColor
        labelBackgroundPaint.color = boxColor

        lastBoxTimestamp = System.currentTimeMillis()
        invalidate()
    }

    fun clearBoundingBox(force: Boolean = false) {
        if (force || System.currentTimeMillis() - lastBoxTimestamp > boundingBoxDuration) {
            boundingBox = null
            boxLabel = null
            invalidate()
        }
    }

    companion object {
        private const val LANDMARK_STROKE_WIDTH = 8F
        private const val TAG = "Face Landmarker Overlay"
    }
}
