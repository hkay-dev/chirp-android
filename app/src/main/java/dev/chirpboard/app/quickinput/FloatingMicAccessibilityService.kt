package dev.chirpboard.app.quickinput

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import dagger.hilt.android.AndroidEntryPoint
import dev.chirpboard.app.R
import dev.chirpboard.app.core.preferences.FloatingBubblePosition
import dev.chirpboard.app.core.preferences.KeyboardPreferences
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

/** Shows Chirp's mic when an editable, non-password field and an on-screen keyboard are active. */
@AndroidEntryPoint
class FloatingMicAccessibilityService : AccessibilityService() {
    @Inject lateinit var keyboardPreferences: KeyboardPreferences

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val refreshRunnable = Runnable(::refreshVisibility)
    private val releaseLaunchGuardRunnable =
        Runnable {
            launchPending = false
            scheduleRefresh()
        }
    private var preferenceJob: Job? = null
    private var positionSaveJob: Job? = null
    private var pendingBubblePosition: FloatingBubblePosition? = null
    private var bubble: FloatingMicAccessibilityBubble? = null
    private var bubbleDisplayId = Display.INVALID_DISPLAY
    private var bubbleEnabled = false
    private var bubblePosition = FloatingBubblePosition()
    private var launchPending = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        destroyBubble()
        preferenceJob?.cancel()
        preferenceJob =
            serviceScope.launch {
                combine(
                    keyboardPreferences.floatingMicBubbleEnabled,
                    keyboardPreferences.floatingBubblePosition,
                ) { enabled, position -> enabled to position }
                    .retryWhen { error, _ ->
                        Log.e(TAG, "Could not read floating-mic preferences", error)
                        bubbleEnabled = false
                        bubble?.hide()
                        delay(PREFERENCE_RETRY_MS)
                        true
                    }
                    .collect { (enabled, position) ->
                        bubbleEnabled = enabled
                        bubblePosition = pendingBubblePosition ?: position
                        scheduleRefresh()
                    }
            }
        scheduleRefresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        scheduleRefresh()
    }

    override fun onInterrupt() = Unit

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        destroyBubble()
        scheduleRefresh()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clearConnectedState()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearConnectedState()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun scheduleRefresh() {
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.postDelayed(refreshRunnable, REFRESH_SETTLE_MS)
    }

    private fun refreshVisibility() {
        if (shouldSuppressFloatingMic(launchPending, FloatingVoiceCaptureSession.isActive)) {
            bubble?.hide()
            return
        }
        val windows = runCatching { windows }.getOrDefault(emptyList())
        val focusedNode = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        val editorWindow =
            focusedNode?.let { node ->
                windows.firstOrNull { window ->
                    window.id == node.windowId && window.type == AccessibilityWindowInfo.TYPE_APPLICATION
                }
            }
        val imeWindow =
            editorWindow?.let { activeEditorWindow ->
                windows.firstOrNull { window ->
                    window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                        window.displayId == activeEditorWindow.displayId
                }
            }
        val imeVisible = imeWindow != null
        val imeTopPx =
            imeWindow?.let { window ->
                val bounds = Rect().also(window::getBoundsInScreen)
                bounds.top.takeIf { bounds.height() > 0 && it > 0 }
            }
        val editorState =
            if (focusedNode == null || editorWindow == null) {
                FocusedEditorState.Absent
            } else {
                inspectFocusedNodeChain(focusedNode)
            }
        if (
            shouldShowFloatingMic(
                enabled = bubbleEnabled,
                imeVisible = imeVisible,
                editorWindowFocused = editorWindow?.isFocused == true,
                editorState = editorState,
            )
        ) {
            showBubble(
                position = bubblePosition,
                displayId = checkNotNull(editorWindow).displayId,
                imeTopPx = imeTopPx,
            )
        } else {
            bubble?.hide()
        }
    }

    private fun inspectFocusedNodeChain(node: AccessibilityNodeInfo): FocusedEditorState {
        val traits = ArrayList<FocusedNodeTraits>(MAX_PARENT_NODES)
        var current: AccessibilityNodeInfo? = node
        repeat(MAX_PARENT_NODES) {
            val inspected = current ?: return@repeat
            traits +=
                FocusedNodeTraits(
                    editable = runCatching { inspected.isEditable }.getOrDefault(false),
                    focused = runCatching { inspected.isFocused }.getOrDefault(false),
                    visible = runCatching { inspected.isVisibleToUser }.getOrDefault(false),
                    password = runCatching { inspected.isPassword }.getOrDefault(true),
                    supportsSetText =
                        runCatching {
                            inspected.actionList.any { action ->
                                action.id == AccessibilityNodeInfo.ACTION_SET_TEXT
                            }
                        }.getOrDefault(false),
                )
            current = runCatching { inspected.parent }.getOrNull()
        }
        return focusedEditorState(traits)
    }

    private fun startDictation() {
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.removeCallbacks(releaseLaunchGuardRunnable)
        launchPending = true
        bubble?.hide()
        val intent =
            Intent(this, FloatingVoiceCaptureActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            val options =
                ActivityOptions.makeBasic().apply {
                    launchDisplayId = bubbleDisplayId
                }
            startActivity(intent, options.toBundle())
            mainHandler.postDelayed(releaseLaunchGuardRunnable, LAUNCH_GUARD_MS)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not open Chirp dictation", error)
            Toast.makeText(this, R.string.floating_mic_bubble_launch_failed, Toast.LENGTH_SHORT).show()
            releaseLaunchGuardRunnable.run()
        }
    }

    private fun showBubble(
        position: FloatingBubblePosition,
        displayId: Int,
        imeTopPx: Int?,
    ) {
        if (bubble == null || bubbleDisplayId != displayId) {
            destroyBubble()
            bubble =
                runCatching {
                    FloatingMicAccessibilityBubble(
                        service = this,
                        displayId = displayId,
                        onTap = ::startDictation,
                        onPositionCommitted = ::saveBubblePosition,
                    )
                }.onFailure { error ->
                    Log.e(TAG, "Could not create the accessibility bubble", error)
                }.getOrNull()
            bubbleDisplayId = if (bubble == null) Display.INVALID_DISPLAY else displayId
        }
        bubble?.show(position, imeTopPx)
    }

    private fun saveBubblePosition(position: FloatingBubblePosition) {
        bubblePosition = position
        pendingBubblePosition = position
        positionSaveJob?.cancel()
        positionSaveJob =
            serviceScope.launch {
                while (true) {
                    try {
                        keyboardPreferences.setFloatingBubblePosition(position)
                        if (pendingBubblePosition == position) pendingBubblePosition = null
                        return@launch
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: IOException) {
                        Log.e(TAG, "Could not save the floating-mic position; retrying", error)
                        delay(PREFERENCE_RETRY_MS)
                    } catch (error: Exception) {
                        Log.e(TAG, "Could not save the floating-mic position", error)
                        if (pendingBubblePosition == position) pendingBubblePosition = null
                        return@launch
                    }
                }
            }
    }

    private fun destroyBubble() {
        bubble?.hide()
        bubble = null
        bubbleDisplayId = Display.INVALID_DISPLAY
    }

    private fun clearConnectedState() {
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.removeCallbacks(releaseLaunchGuardRunnable)
        launchPending = false
        preferenceJob?.cancel()
        preferenceJob = null
        destroyBubble()
    }

    companion object {
        private const val TAG = "FloatingMicAccess"
        private const val REFRESH_SETTLE_MS = 75L
        private const val LAUNCH_GUARD_MS = 1_500L
        private const val PREFERENCE_RETRY_MS = 1_000L
        private const val MAX_PARENT_NODES = 6
    }
}
