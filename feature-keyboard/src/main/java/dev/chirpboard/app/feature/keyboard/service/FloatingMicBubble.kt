package dev.chirpboard.app.feature.keyboard.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import dev.chirpboard.app.core.preferences.FLOATING_BUBBLE_Y_FRACTION_RANGE
import dev.chirpboard.app.core.preferences.FloatingBubblePosition
import dev.chirpboard.app.feature.keyboard.R
import dev.chirpboard.app.feature.keyboard.session.KeyboardUiState
import dev.chirpboard.app.feature.keyboard.session.VoicePanelPhase
import kotlin.math.abs
import kotlin.math.roundToInt

/** BUB-1: visual/behavioral state of the floating mic bubble. */
enum class FloatingBubblePhase {
    /** Ready to start a dictation. */
    Idle,

    /** A dictation is live; a tap stops and transcribes it. */
    Recording,

    /** Model load / transcription / polishing is running; taps are ignored. */
    Busy,
}

/**
 * Maps the keyboard session's UI state onto the bubble's three visuals. The bubble mirrors the
 * in-keyboard mic: error states render as Idle because the keyboard panel itself carries the
 * error message and the bubble tap re-enters the same mic flow, which surfaces it.
 */
fun floatingBubblePhaseFor(uiState: KeyboardUiState): FloatingBubblePhase =
    when (uiState.voicePanel) {
        VoicePanelPhase.Recording -> FloatingBubblePhase.Recording
        VoicePanelPhase.LoadingModel,
        VoicePanelPhase.Transcribing,
        VoicePanelPhase.Polishing,
        -> FloatingBubblePhase.Busy
        VoicePanelPhase.Idle,
        VoicePanelPhase.Error,
        VoicePanelPhase.LlmError,
        -> FloatingBubblePhase.Idle
    }

/**
 * BUB-1 visibility rule. The bubble exists only alongside a live, non-sensitive Chirp input
 * session: it triggers [ChirpKeyboardService.onMicTapForCurrentInput], and outside such a
 * session that tap has nothing to commit into. [canDrawOverlays] gates on the user's
 * "display over other apps" grant, which is separate from enabling the toggle.
 */
fun shouldShowFloatingBubble(
    enabled: Boolean,
    canDrawOverlays: Boolean,
    windowShown: Boolean,
    inputViewActive: Boolean,
    sensitiveInput: Boolean,
): Boolean = enabled && canDrawOverlays && windowShown && inputViewActive && !sensitiveInput

/**
 * Owns the overlay window for the floating mic bubble (BUB-1).
 *
 * A TYPE_APPLICATION_OVERLAY window with FLAG_NOT_FOCUSABLE never steals focus from the target
 * editor, so the tap starts/stops dictation while the field stays bound to Chirp's own
 * InputConnection — the one insertion path that is reliable on every app. The bubble is dragged
 * anywhere, snaps to the nearest screen edge on release, and reports the settled position so the
 * service can persist it.
 *
 * All methods must be called on the main thread (the IME service's thread).
 */
internal class FloatingMicBubbleController(
    private val context: Context,
    private val onTap: () -> Unit,
    private val onPositionCommitted: (FloatingBubblePosition) -> Unit,
) {
    companion object {
        private const val TAG = "FloatingMicBubble"
        private const val BUBBLE_SIZE_DP = 56f
        private const val ICON_PADDING_DP = 14f
        private const val EDGE_MARGIN_DP = 6f
        private const val STROKE_WIDTH_DP = 1f

        // Translucent so the host app stays readable underneath.
        private const val COLOR_IDLE = 0xE65E51B5.toInt()
        private const val COLOR_RECORDING = 0xF2D64545.toInt()
        private const val COLOR_BUSY = 0xE66E6A85.toInt()
        private const val COLOR_STROKE = 0x40FFFFFF

        private const val PULSE_SCALE_MAX = 1.12f
        private const val PULSE_DURATION_MS = 600L
    }

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var bubbleView: ImageView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var phase = FloatingBubblePhase.Idle
    private var pulseAnimator: ValueAnimator? = null

    private val background =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(COLOR_IDLE)
            setStroke(dpToPx(STROKE_WIDTH_DP), COLOR_STROKE)
        }

    val isShowing: Boolean get() = bubbleView != null

    fun show(position: FloatingBubblePosition) {
        if (bubbleView != null) return
        val manager = windowManager ?: return
        val sizePx = dpToPx(BUBBLE_SIZE_DP)
        val view =
            ImageView(context).apply {
                background = this@FloatingMicBubbleController.background
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
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                val bounds = manager.currentWindowMetrics.bounds
                x = edgeX(position.onRight, bounds.width(), sizePx)
                y = ((bounds.height() - sizePx) * position.yFraction.coerceIn(FLOATING_BUBBLE_Y_FRACTION_RANGE))
                    .roundToInt()
            }
        attachDragHandler(view, params)
        try {
            manager.addView(view, params)
        } catch (error: RuntimeException) {
            // SecurityException (grant revoked between the check and here) or BadTokenException.
            // The bubble is a convenience surface; the keyboard's own mic remains available.
            Log.w(TAG, "Could not attach the floating bubble", error)
            return
        }
        bubbleView = view
        layoutParams = params
        applyPhaseVisuals()
    }

    fun hide() {
        val view = bubbleView ?: return
        stopPulse()
        bubbleView = null
        layoutParams = null
        try {
            windowManager?.removeViewImmediate(view)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not detach the floating bubble", error)
        }
    }

    fun setPhase(newPhase: FloatingBubblePhase) {
        if (phase == newPhase) return
        phase = newPhase
        applyPhaseVisuals()
    }

    private fun applyPhaseVisuals() {
        val view = bubbleView ?: return
        when (phase) {
            FloatingBubblePhase.Idle -> {
                stopPulse()
                background.setColor(COLOR_IDLE)
                view.setImageResource(R.drawable.ic_floating_bubble_mic)
                view.contentDescription = context.getString(R.string.keyboard_desc_start_recording)
                view.alpha = 1f
            }
            FloatingBubblePhase.Recording -> {
                background.setColor(COLOR_RECORDING)
                view.setImageResource(R.drawable.ic_floating_bubble_stop)
                view.contentDescription = context.getString(R.string.keyboard_desc_stop_recording)
                view.alpha = 1f
                startPulse(view)
            }
            FloatingBubblePhase.Busy -> {
                stopPulse()
                background.setColor(COLOR_BUSY)
                view.setImageResource(R.drawable.ic_floating_bubble_mic)
                view.contentDescription = context.getString(R.string.keyboard_transcribing)
                view.alpha = 0.85f
            }
        }
    }

    private fun startPulse(view: View) {
        if (pulseAnimator != null) return
        pulseAnimator =
            ValueAnimator.ofFloat(1f, PULSE_SCALE_MAX).apply {
                duration = PULSE_DURATION_MS
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener { animator ->
                    val scale = animator.animatedValue as Float
                    view.scaleX = scale
                    view.scaleY = scale
                }
                start()
            }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        bubbleView?.scaleX = 1f
        bubbleView?.scaleY = 1f
    }

    // The view is an action button, not a text surface; drag + tap are both handled here and a
    // tap is forwarded through performClick for accessibility.
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
        var dragging = false
        view.setOnClickListener { if (phase != FloatingBubblePhase.Busy) onTap() }
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
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = downParamsX + dx.roundToInt()
                        params.y = downParamsY + dy.roundToInt()
                        updateLayoutSafely(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        settleAfterDrag(view, params)
                    } else {
                        view.performClick()
                    }
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
        val manager = windowManager ?: return
        val bounds = manager.currentWindowMetrics.bounds
        val sizePx = params.width
        val onRight = params.x + sizePx / 2 > bounds.width() / 2
        val usableHeight = (bounds.height() - sizePx).coerceAtLeast(1)
        val yFraction =
            (params.y.toFloat() / usableHeight).coerceIn(FLOATING_BUBBLE_Y_FRACTION_RANGE)
        params.x = edgeX(onRight, bounds.width(), sizePx)
        params.y = (usableHeight * yFraction).roundToInt()
        updateLayoutSafely(view, params)
        onPositionCommitted(FloatingBubblePosition(onRight = onRight, yFraction = yFraction))
    }

    private fun updateLayoutSafely(
        view: View,
        params: WindowManager.LayoutParams,
    ) {
        try {
            windowManager?.updateViewLayout(view, params)
        } catch (error: RuntimeException) {
            // The view can already be detached when a hide raced the drag.
            Log.w(TAG, "Could not move the floating bubble", error)
        }
    }

    private fun edgeX(
        onRight: Boolean,
        screenWidth: Int,
        sizePx: Int,
    ): Int {
        val margin = dpToPx(EDGE_MARGIN_DP)
        return if (onRight) screenWidth - sizePx - margin else margin
    }

    private fun dpToPx(dp: Float): Int =
        TypedValue
            .applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, context.resources.displayMetrics)
            .roundToInt()
}
