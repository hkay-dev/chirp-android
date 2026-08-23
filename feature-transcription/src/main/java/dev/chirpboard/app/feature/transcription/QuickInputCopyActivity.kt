package dev.chirpboard.app.feature.transcription

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.chirpboard.app.core.transcription.EXTRA_TRANSCRIPTION_RECORDING_ID
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.repository.RecordingRepository
import java.util.UUID
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "QuickInputCopy"

/**
 * Invisible focus-holding surface for notification copy actions.
 *
 * Android's clipboard policy only guarantees a write for the focused app or the default
 * IME. The previous BroadcastReceivers ran with the app in the background, so on builds
 * enforcing that policy the write was silently dropped while the receiver's "Copied" toast
 * still showed. Copying from a briefly focused translucent activity makes the write land
 * everywhere, and on Android 13+ the system's own clipboard confirmation replaces the toast.
 *
 * Two source modes: quick-input actions carry the text in an extra; transcription-ready
 * actions carry only a recording id and load the transcript from Room here, keeping
 * transcript text out of PendingIntent extras.
 */
class QuickInputCopyActivity : Activity() {
    // A plain Activity (no ComponentActivity dependency in this module), so the
    // repository comes through an entry point instead of @AndroidEntryPoint field injection.
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface CopyEntryPoint {
        fun recordingRepository(): RecordingRepository
    }

    private val recordingRepository: RecordingRepository by lazy {
        EntryPointAccessors.fromApplication(applicationContext, CopyEntryPoint::class.java).recordingRepository()
    }

    private val scope = MainScope()
    private var copyAttempted = false
    private var focusSettled = false
    private var pendingPlan: Deferred<CopyPlan?>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingPlan = scope.async { resolvePlan(intent) }
        // If window focus never arrives (another window steals it immediately), this
        // invisible activity must not linger: attempt the copy anyway and leave.
        window.decorView.postDelayed(::onFocusSettled, FOCUS_TIMEOUT_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) onFocusSettled()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun onFocusSettled() {
        if (focusSettled) return
        focusSettled = true
        val plan = pendingPlan ?: return
        scope.launch {
            if (copyAttempted || isFinishing) return@launch
            copyAttempted = true
            performCopy(plan.await())
            finish()
        }
    }

    private suspend fun resolvePlan(intent: Intent?): CopyPlan? =
        when (intent?.action) {
            ACTION_COPY_RAW, ACTION_COPY_AI -> {
                val copyAi = intent.action == ACTION_COPY_AI
                CopyPlan(
                    text = intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() },
                    copiedToast =
                        if (copyAi) R.string.quick_input_result_copied_ai else R.string.quick_input_result_copied_raw,
                    failedToast = R.string.quick_input_result_copy_failed,
                )
            }

            ACTION_COPY_TRANSCRIPT_RAW, ACTION_COPY_TRANSCRIPT_AI -> {
                val copyAi = intent.action == ACTION_COPY_TRANSCRIPT_AI
                val recordingId =
                    intent.getStringExtra(EXTRA_TRANSCRIPTION_RECORDING_ID)
                        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                CopyPlan(
                    text =
                        recordingId
                            ?.let { runCatching { recordingRepository.getTranscript(it) }.getOrNull() }
                            ?.let { transcriptionCopyText(it, copyAi) },
                    copiedToast =
                        if (copyAi) R.string.transcription_ready_copied_ai else R.string.transcription_ready_copied_raw,
                    failedToast = R.string.transcription_ready_copy_failed,
                )
            }

            else -> null
        }

    private fun performCopy(plan: CopyPlan?) {
        if (plan == null) return
        if (plan.text == null) {
            Toast.makeText(this, plan.failedToast, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        if (clipboard == null) {
            Toast.makeText(this, plan.failedToast, Toast.LENGTH_SHORT).show()
            return
        }

        val clip = ClipData.newPlainText(getString(R.string.transcription_title), plan.text)
        clip.description.extras =
            PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        try {
            clipboard.setPrimaryClip(clip)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not copy result", error)
            Toast.makeText(this, plan.failedToast, Toast.LENGTH_SHORT).show()
            return
        }
        if (!window.decorView.hasWindowFocus()) {
            // Reached only through the focus timeout. An unfocused write is silently
            // dropped by the clipboard service, which is the exact failure this activity
            // exists to prevent — report it instead of implying success.
            Log.w(TAG, "Copy attempted without window focus; the write may have been dropped")
            Toast.makeText(this, plan.failedToast, Toast.LENGTH_SHORT).show()
            return
        }
        // Android 13+ shows the system clipboard confirmation; a toast would duplicate it.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, plan.copiedToast, Toast.LENGTH_SHORT).show()
        }
    }

    private data class CopyPlan(
        val text: String?,
        @StringRes val copiedToast: Int,
        @StringRes val failedToast: Int,
    )

    companion object {
        internal const val ACTION_COPY_RAW = "dev.chirpboard.app.action.COPY_QUICK_INPUT_RAW"
        internal const val ACTION_COPY_AI = "dev.chirpboard.app.action.COPY_QUICK_INPUT_AI"
        internal const val ACTION_COPY_TRANSCRIPT_RAW = "dev.chirpboard.app.action.COPY_TRANSCRIPTION_RAW"
        internal const val ACTION_COPY_TRANSCRIPT_AI = "dev.chirpboard.app.action.COPY_TRANSCRIPTION_AI"
        internal const val EXTRA_TEXT = "dev.chirpboard.app.extra.QUICK_INPUT_TEXT"
        private const val FOCUS_TIMEOUT_MS = 1_500L
    }
}

/** Selects the transcript variant a copy action asked for, or null when it is absent. */
internal fun transcriptionCopyText(
    transcript: Transcript,
    copyAiResult: Boolean,
): String? =
    if (copyAiResult) {
        transcript.processedText?.takeIf { it.isNotBlank() }
    } else {
        transcript.rawText.takeIf { it.isNotBlank() }
    }
