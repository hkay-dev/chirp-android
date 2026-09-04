package dev.chirpboard.app.quickinput

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageView
import dev.chirpboard.app.R
import dev.chirpboard.app.core.preferences.FLOATING_BUBBLE_Y_FRACTION_RANGE
import dev.chirpboard.app.core.preferences.FloatingBubblePosition
import dev.chirpboard.app.feature.keyboard.R as KeyboardR
import kotlin.math.abs
import kotlin.math.roundToInt

/** Owns the draggable TYPE_ACCESSIBILITY_OVERLAY mic window. */
internal class FloatingMicAccessibilityBubble(
    service: AccessibilityService,
    displayId: Int,
    private val onTap: () -> Unit,
    private val onPositionCommitted: (FloatingBubblePosition) -> Unit,
) {
    companion object {
        private const val TAG = "FloatingMicBubble"
        private const val BUBBLE_SIZE_DP = 56f
        private const val ICON_PADDING_DP = 14f
        private const val EDGE_MARGIN_DP = 6f
        private const val STROKE_WIDTH_DP = 1f
        private const val COLOR_IDLE = 0xE65E51B5.toInt()
        private const val COLOR_STROKE = 0x40FFFFFF
    }

    private val context =
        run {
            val displayManager = service.getSystemService(DisplayManager::class.java)
            val targetDisplay = checkNotNull(displayManager.getDisplay(displayId))
            service
                .createDisplayContext(targetDisplay)
                .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
        }
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var bubbleView: ImageView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var imeTopPx: Int? = null
    private var dragging = false
    private val background =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(COLOR_IDLE)
            setStroke(dpToPx(STROKE_WIDTH_DP), COLOR_STROKE)
        }

    @SuppressLint("RtlHardcoded")
    fun show(
        position: FloatingBubblePosition,
        imeTopPx: Int?,
    ) {
        this.imeTopPx = imeTopPx
        val attachedView = bubbleView
        val attachedParams = layoutParams
        if (attachedView != null && attachedParams != null) {
            if (!dragging) applyPosition(attachedView, attachedParams, position)
            return
        }
        val sizePx = dpToPx(BUBBLE_SIZE_DP)
        val view =
            ImageView(context).apply {
                background = this@FloatingMicAccessibilityBubble.background
                setImageResource(KeyboardR.drawable.ic_floating_bubble_mic)
                contentDescription = context.getString(R.string.floating_mic_bubble_start_recording)
                scaleType = ImageView.ScaleType.FIT_CENTER
                val padding = dpToPx(ICON_PADDING_DP)
                setPadding(padding, padding, padding, padding)
                isClickable = true
                isFocusable = false
            }
        val params =
            WindowManager.LayoutParams(
                sizePx,
                sizePx,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                val safeArea = safeTopLeftArea(sizePx)
                x = edgeX(position.onRight, safeArea)
                y = yForFraction(position.yFraction, safeArea)
            }
        attachDragHandler(view, params)
        try {
            windowManager.addView(view, params)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not attach the accessibility bubble", error)
            return
        }
        bubbleView = view
        layoutParams = params
    }

    fun hide() {
        val view = bubbleView ?: return
        bubbleView = null
        layoutParams = null
        try {
            windowManager.removeViewImmediate(view)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not detach the accessibility bubble", error)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDragHandler(
        view: ImageView,
        params: WindowManager.LayoutParams,
    ) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var downParamsX = 0
        var downParamsY = 0
        view.setOnClickListener { onTap() }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downParamsX = params.x
                    downParamsY = params.y
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                    if (dragging) {
                        params.x = downParamsX + dx.roundToInt()
                        params.y = downParamsY + dy.roundToInt()
                        updateLayoutSafely(view, params)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (dragging) settleAfterDrag(view, params) else view.performClick()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) settleAfterDrag(view, params)
                    true
                }

                else -> false
            }
        }
    }

    private fun settleAfterDrag(
        view: View,
        params: WindowManager.LayoutParams,
    ) {
        val sizePx = params.width
        val safeArea = safeTopLeftArea(sizePx)
        val onRight = params.x > (safeArea.left + safeArea.right) / 2
        val clampedY = params.y.coerceIn(safeArea.top, safeArea.bottom)
        val usableHeight = (safeArea.bottom - safeArea.top).coerceAtLeast(1)
        val yFraction =
            ((clampedY - safeArea.top).toFloat() / usableHeight)
                .coerceIn(FLOATING_BUBBLE_Y_FRACTION_RANGE)
        params.x = edgeX(onRight, safeArea)
        params.y = yForFraction(yFraction, safeArea)
        updateLayoutSafely(view, params)
        onPositionCommitted(FloatingBubblePosition(onRight = onRight, yFraction = yFraction))
        dragging = false
    }

    private fun applyPosition(
        view: View,
        params: WindowManager.LayoutParams,
        position: FloatingBubblePosition,
    ) {
        val safeArea = safeTopLeftArea(params.width)
        val targetX = edgeX(position.onRight, safeArea)
        val targetY = yForFraction(position.yFraction, safeArea)
        if (params.x == targetX && params.y == targetY) return
        params.x = targetX
        params.y = targetY
        updateLayoutSafely(view, params)
    }

    private fun updateLayoutSafely(
        view: View,
        params: WindowManager.LayoutParams,
    ) {
        try {
            windowManager.updateViewLayout(view, params)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not move the accessibility bubble", error)
        }
    }

    private fun safeTopLeftArea(sizePx: Int): Rect {
        val metrics = windowManager.currentWindowMetrics
        val insets =
            metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout() or
                WindowInsets.Type.systemGestures(),
            )
        val margin = dpToPx(EDGE_MARGIN_DP)
        val width = metrics.bounds.width()
        val height = metrics.bounds.height()
        val left = (insets.left + margin).coerceAtMost((width - sizePx).coerceAtLeast(0))
        val top = (insets.top + margin).coerceAtMost((height - sizePx).coerceAtLeast(0))
        val right = (width - insets.right - margin - sizePx).coerceAtLeast(left)
        val systemBottom = height - insets.bottom - margin - sizePx
        val keyboardBottom = imeTopPx?.minus(margin + sizePx) ?: systemBottom
        val bottom = minOf(systemBottom, keyboardBottom).coerceAtLeast(top)
        return Rect(left, top, right, bottom)
    }

    private fun edgeX(
        onRight: Boolean,
        safeArea: Rect,
    ): Int = if (onRight) safeArea.right else safeArea.left

    private fun yForFraction(
        yFraction: Float,
        safeArea: Rect,
    ): Int =
        safeArea.top +
            ((safeArea.bottom - safeArea.top) *
                yFraction.coerceIn(FLOATING_BUBBLE_Y_FRACTION_RANGE)).roundToInt()

    private fun dpToPx(dp: Float): Int =
        TypedValue
            .applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
            .roundToInt()
}
