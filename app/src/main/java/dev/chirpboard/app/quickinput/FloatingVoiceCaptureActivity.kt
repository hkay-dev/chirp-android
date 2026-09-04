package dev.chirpboard.app.quickinput

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.chirpboard.app.R
import dev.chirpboard.app.VoiceRecognitionActivity
import dev.chirpboard.app.core.ui.theme.ChirpTheme
import dev.chirpboard.app.feature.transcription.R as TranscriptionR

/**
 * Keeps a bubble-started recognition session and its editable review in a temporary task.
 *
 * The accessibility service has to launch an activity with [Intent.FLAG_ACTIVITY_NEW_TASK].
 * Sending that flag straight to [VoiceRecognitionActivity] can reopen Chirp's existing task,
 * leaving the main app behind the recognition sheet. This activity is declared with an empty
 * task affinity, starts the existing recognition activity for a result, then lets the user edit
 * the returned text before writing it to the clipboard.
 */
class FloatingVoiceCaptureActivity : ComponentActivity() {
    private var phase by mutableStateOf(FloatingVoiceCapturePhase.AwaitingRecognition)
    private var reviewField by mutableStateOf(TextFieldValue())
    private var restoredAwaitingResult = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FloatingVoiceCaptureSession.isActive = true
        window.addFlags(
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                WindowManager.LayoutParams.FLAG_SECURE,
        )

        if (savedInstanceState == null) {
            launchRecognition()
            return
        }

        phase = floatingVoiceCapturePhase(savedInstanceState.getString(STATE_PHASE))
        val restoredText = savedInstanceState.getString(STATE_REVIEW_TEXT).orEmpty()
        val restoredSelectionStart =
            savedInstanceState
                .getInt(STATE_REVIEW_SELECTION_START, restoredText.length)
                .coerceIn(0, restoredText.length)
        val restoredSelectionEnd =
            savedInstanceState
                .getInt(STATE_REVIEW_SELECTION_END, restoredSelectionStart)
                .coerceIn(0, restoredText.length)
        reviewField =
            TextFieldValue(
                text = restoredText,
                selection = TextRange(restoredSelectionStart, restoredSelectionEnd),
            )
        when (phase) {
            FloatingVoiceCapturePhase.AwaitingRecognition -> restoredAwaitingResult = true
            FloatingVoiceCapturePhase.Reviewing -> showReview()
            FloatingVoiceCapturePhase.CopyStarted -> finishAndRemoveTask()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (
            hasFocus &&
            restoredAwaitingResult &&
            phase == FloatingVoiceCapturePhase.AwaitingRecognition &&
            !isFinishing
        ) {
            finishAndRemoveTask()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PHASE, phase.name)
        outState.putString(STATE_REVIEW_TEXT, reviewField.text)
        outState.putInt(STATE_REVIEW_SELECTION_START, reviewField.selection.start)
        outState.putInt(STATE_REVIEW_SELECTION_END, reviewField.selection.end)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        FloatingVoiceCaptureSession.isActive = true
    }

    override fun onStop() {
        if (!isChangingConfigurations && phase == FloatingVoiceCapturePhase.Reviewing) {
            FloatingVoiceCaptureSession.isActive = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            FloatingVoiceCaptureSession.isActive = false
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in the Android framework")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_RECOGNITION) return
        restoredAwaitingResult = false
        if (data?.getBooleanExtra(EXTRA_FLOATING_VOICE_REVIEW_HANDLED, false) == true) {
            finishAndRemoveTask()
            return
        }

        val text =
            floatingVoiceCaptureResultText(
                resultCode = resultCode,
                results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS),
            )
        if (text == null) {
            finishAndRemoveTask()
            return
        }

        reviewField = TextFieldValue(text = text, selection = TextRange(text.length))
        phase = FloatingVoiceCapturePhase.Reviewing
        showReview()
    }

    private fun launchRecognition() {
        startActivityForResult(
            Intent(this, VoiceRecognitionActivity::class.java).apply {
                action = RecognizerIntent.ACTION_RECOGNIZE_SPEECH
                putExtra(EXTRA_FLOATING_VOICE_REVIEW, true)
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
            },
            REQUEST_RECOGNITION,
        )
    }

    private fun showReview() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        setContent {
            ChirpTheme {
                FloatingTranscriptReviewDialog(
                    value = reviewField,
                    copyStarted = phase == FloatingVoiceCapturePhase.CopyStarted,
                    onValueChange = { reviewField = it },
                    onCopy = ::copyReviewedText,
                    onCancel = ::finishAndRemoveTask,
                )
            }
        }
    }

    private fun copyReviewedText() {
        val text =
            floatingVoiceReviewCopyText(
                reviewField.text,
                phase == FloatingVoiceCapturePhase.CopyStarted,
            ) ?: return
        val clipboard = getSystemService(ClipboardManager::class.java)
        if (clipboard == null) {
            Toast.makeText(this, R.string.floating_mic_review_copy_failed, Toast.LENGTH_SHORT).show()
            return
        }

        phase = FloatingVoiceCapturePhase.CopyStarted
        try {
            clipboard.setPrimaryClip(
                ClipData.newPlainText(getString(TranscriptionR.string.transcription_title), text),
            )
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not copy the reviewed dictation", error)
            phase = FloatingVoiceCapturePhase.Reviewing
            Toast.makeText(this, R.string.floating_mic_review_copy_failed, Toast.LENGTH_SHORT).show()
            return
        }

        finishAndRemoveTask()
    }

    private companion object {
        const val TAG = "FloatingVoiceCapture"
        const val REQUEST_RECOGNITION = 7_201
        const val STATE_PHASE = "floating_voice_capture_phase"
        const val STATE_REVIEW_TEXT = "floating_voice_capture_review_text"
        const val STATE_REVIEW_SELECTION_START = "floating_voice_capture_selection_start"
        const val STATE_REVIEW_SELECTION_END = "floating_voice_capture_selection_end"
    }
}

internal enum class FloatingVoiceCapturePhase {
    AwaitingRecognition,
    Reviewing,
    CopyStarted,
}

internal fun floatingVoiceCapturePhase(savedPhase: String?): FloatingVoiceCapturePhase =
    savedPhase
        ?.let { saved -> FloatingVoiceCapturePhase.entries.firstOrNull { it.name == saved } }
        ?: FloatingVoiceCapturePhase.AwaitingRecognition

/** Keeps the accessibility bubble hidden throughout recording and transcript review. */
internal object FloatingVoiceCaptureSession {
    @Volatile
    var isActive: Boolean = false
}

internal const val EXTRA_FLOATING_VOICE_REVIEW =
    "dev.chirpboard.app.extra.FLOATING_VOICE_REVIEW"
internal const val EXTRA_FLOATING_VOICE_REVIEW_HANDLED =
    "dev.chirpboard.app.extra.FLOATING_VOICE_REVIEW_HANDLED"

internal fun floatingVoiceCaptureResultText(
    resultCode: Int,
    results: List<String>?,
): String? {
    if (resultCode != Activity.RESULT_OK) return null
    return results
        ?.firstOrNull { it.isNotBlank() }
        ?.trim()
}

/** Keeps user edits exact and gates blank or already-consumed copy requests. */
internal fun floatingVoiceReviewCopyText(
    draft: String,
    copyStarted: Boolean,
): String? = draft.takeIf { !copyStarted && it.isNotBlank() }
