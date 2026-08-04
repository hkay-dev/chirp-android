package dev.chirpboard.app.feature.transcription

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.widget.Toast
import dagger.hilt.android.AndroidEntryPoint
import dev.chirpboard.app.core.transcription.EXTRA_TRANSCRIPTION_RECORDING_ID
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.repository.RecordingRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Copies a completed result from Room, keeping transcript text out of PendingIntent extras. */
@AndroidEntryPoint
class TranscriptionCopyActionReceiver : BroadcastReceiver() {
    @Inject lateinit var recordingRepository: RecordingRepository

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val recordingId =
            intent.getStringExtra(EXTRA_TRANSCRIPTION_RECORDING_ID)
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return
        val copyAiResult = intent.action == ACTION_COPY_AI
        if (!copyAiResult && intent.action != ACTION_COPY_RAW) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val text =
                    runCatching { recordingRepository.getTranscript(recordingId) }
                        .getOrNull()
                        ?.let { transcriptionCopyText(it, copyAiResult) }
                withContext(Dispatchers.Main) {
                    if (text == null) {
                        Toast.makeText(context, R.string.transcription_ready_copy_failed, Toast.LENGTH_SHORT).show()
                    } else {
                        val clip = ClipData.newPlainText(context.getString(R.string.transcription_title), text)
                        clip.description.extras =
                            PersistableBundle().apply {
                                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                            }
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
                        Toast.makeText(
                            context,
                            if (copyAiResult) {
                                R.string.transcription_ready_copied_ai
                            } else {
                                R.string.transcription_ready_copied_raw
                            },
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        internal const val ACTION_COPY_RAW = "dev.chirpboard.app.action.COPY_TRANSCRIPTION_RAW"
        internal const val ACTION_COPY_AI = "dev.chirpboard.app.action.COPY_TRANSCRIPTION_AI"
    }
}

internal fun transcriptionCopyText(
    transcript: Transcript,
    copyAiResult: Boolean,
): String? =
    if (copyAiResult) {
        transcript.processedText?.takeIf { it.isNotBlank() }
    } else {
        transcript.rawText.takeIf { it.isNotBlank() }
    }
