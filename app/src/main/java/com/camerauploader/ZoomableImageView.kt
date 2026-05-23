package com.camerauploader

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * Full-screen image view with pinch-to-zoom, drag-to-pan, and double-tap zoom.
 * Dependency-free: transforms the image with a [Matrix] and clamps panning so the
 * image stays within the view (centered while it fits, edge-locked once zoomed).
 *
 * A confirmed single tap (not part of a double tap or pinch) invokes [onSingleTap]
 * — used by the preview overlay to dismiss.
 */
class ZoomableImageView(context: Context) : AppCompatImageView(context) {

    var onSingleTap: (() -> Unit)? = null

    private val m = Matrix()
    private val values = FloatArray(9)
    private val mappedRect = RectF()

    private var viewW = 0
    private var viewH = 0
    private var fitScale = 1f
    private var maxScale = 5f

    private var lastX = 0f
    private var lastY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val current = currentScale()
                val target = (current * detector.scaleFactor).coerceIn(fitScale, maxScale)
                val factor = if (current == 0f) 1f else target / current
                m.postScale(factor, factor, detector.focusX, detector.focusY)
                clampTranslation()
                imageMatrix = m
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTap?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val current = currentScale()
                if (current > fitScale * 1.05f) {
                    resetToFit()
                } else {
                    val target = (fitScale * 3f).coerceAtMost(maxScale)
                    val factor = if (current == 0f) 1f else target / current
                    m.postScale(factor, factor, e.x, e.y)
                    clampTranslation()
                    imageMatrix = m
                }
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        viewW = w
        viewH = h
        resetToFit()
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        super.setImageBitmap(bm)
        resetToFit()
    }

    private fun currentScale(): Float {
        m.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    /** Reset the matrix to fit-and-center the image inside the view. */
    private fun resetToFit() {
        val d = drawable ?: return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        if (viewW == 0 || viewH == 0 || dw <= 0f || dh <= 0f) return
        fitScale = minOf(viewW / dw, viewH / dh)
        maxScale = fitScale * 5f
        m.reset()
        m.postScale(fitScale, fitScale)
        m.postTranslate((viewW - dw * fitScale) / 2f, (viewH - dh * fitScale) / 2f)
        imageMatrix = m
    }

    /** Keep the image within the view: centered when it fits, edge-locked when zoomed. */
    private fun clampTranslation() {
        val d = drawable ?: return
        mappedRect.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(mappedRect)
        val w = viewW.toFloat()
        val h = viewH.toFloat()

        val dx = when {
            mappedRect.width() <= w -> (w - mappedRect.width()) / 2f - mappedRect.left
            mappedRect.left > 0f     -> -mappedRect.left
            mappedRect.right < w     -> w - mappedRect.right
            else                     -> 0f
        }
        val dy = when {
            mappedRect.height() <= h -> (h - mappedRect.height()) / 2f - mappedRect.top
            mappedRect.top > 0f      -> -mappedRect.top
            mappedRect.bottom < h    -> h - mappedRect.bottom
            else                     -> 0f
        }
        m.postTranslate(dx, dy)
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                activePointerId = event.getPointerId(0)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && currentScale() > fitScale * 1.01f) {
                    val idx = event.findPointerIndex(activePointerId)
                    if (idx != -1) {
                        val x = event.getX(idx)
                        val y = event.getY(idx)
                        m.postTranslate(x - lastX, y - lastY)
                        clampTranslation()
                        imageMatrix = m
                        lastX = x
                        lastY = y
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // The active finger lifted; continue panning from a remaining finger.
                val upIndex = event.actionIndex
                if (event.getPointerId(upIndex) == activePointerId) {
                    val newIndex = if (upIndex == 0) 1 else 0
                    lastX = event.getX(newIndex)
                    lastY = event.getY(newIndex)
                    activePointerId = event.getPointerId(newIndex)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
        }
        return true
    }
}
