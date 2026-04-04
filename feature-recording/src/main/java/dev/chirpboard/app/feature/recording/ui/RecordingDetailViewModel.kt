package dev.chirpboard.app.feature.recording.ui

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.recording.audio.AudioPlayer
import dev.chirpboard.app.feature.recording.audio.PlaybackState
import dev.chirpboard.app.feature.transcription.ManualRecoveryResult
import dev.chirpboard.app.feature.transcription.RecoveryDiagnostics
import dev.chirpboard.app.feature.transcription.RecoveryOwnershipState
import dev.chirpboard.app.feature.transcription.TranscriptionQueueManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class RecordingDetailViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val recordingRepository: RecordingRepository,
        private val audioPlayer: AudioPlayer,
        private val transcriptionQueueManager: TranscriptionQueueManager,
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        private val recordingId: UUID =
            savedStateHandle
                .get<String>("recordingId")
                ?.let { UUID.fromString(it) }
                ?: throw IllegalArgumentException("recordingId is required")

        val recording: StateFlow<Recording?> =
            recordingRepository
                .getRecordingFlow(recordingId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

        val transcript: StateFlow<Transcript?> =
            recordingRepository
                .getTranscriptFlow(recordingId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

        val playbackState: StateFlow<PlaybackState> = audioPlayer.state

        private val _recoveryDiagnostics = MutableStateFlow(RecoveryDiagnosticsUi())
        internal val recoveryDiagnostics: StateFlow<RecoveryDiagnosticsUi> = _recoveryDiagnostics.asStateFlow()

        internal val recoveryActions: StateFlow<DetailRecoveryActions> =
            combine(recording, _recoveryDiagnostics) { rec, diagnostics ->
                computeDetailRecoveryActions(rec?.status, diagnostics.ownership)
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                computeDetailRecoveryActions(null, RecoveryOwnershipState.MISSING_OR_TERMINAL),
            )

        private val _isEditing = MutableStateFlow(false)
        val isEditing: StateFlow<Boolean> = _isEditing.asStateFlow()

        private val _editedTitle = MutableStateFlow("")
        val editedTitle: StateFlow<String> = _editedTitle.asStateFlow()

        private val _message = MutableStateFlow<String?>(null)
        val message: StateFlow<String?> = _message.asStateFlow()

        fun clearMessage() {
            _message.value = null
        }

        init {
            viewModelScope.launch {
                recording.collect { rec ->
                    if (rec == null) {
                        _recoveryDiagnostics.value = RecoveryDiagnosticsUi()
                    } else {
                        _recoveryDiagnostics.value =
                            transcriptionQueueManager
                                .getRecoveryDiagnostics(rec.id)
                                .toUiModel()
                    }
                }
            }
        }

        fun startEditing() {
            _editedTitle.value = recording.value?.title ?: ""
            _isEditing.value = true
        }

        fun cancelEditing() {
            _isEditing.value = false
        }

        fun updateTitle(newTitle: String) {
            _editedTitle.value = newTitle
        }

        fun saveTitle() {
            viewModelScope.launch {
                val trimmedTitle = _editedTitle.value.trim()
                if (trimmedTitle.isNotEmpty()) {
                    recordingRepository.updateTitle(recordingId, trimmedTitle)
                }
                _isEditing.value = false
            }
        }

        /**
         * Delete a recording.
         *
         * Order matters: Delete from database FIRST (critical), then audio file (best effort).
         * If DB delete fails, we keep the file. If file delete fails, that's acceptable
         * since the DB record is already gone. Transcript is cascade-deleted by Room.
         */
        fun deleteRecording(onDeleted: () -> Unit) {
            viewModelScope.launch {
                recording.value?.let { rec ->
                    audioPlayer.release()

                    try {
                        // Step 1: Delete from database FIRST (the critical operation)
                        // Transcript is cascade-deleted via ForeignKey constraint
                        recordingRepository.delete(rec)

                        // Step 2: Delete audio file (non-critical, best effort)
                        // Run on IO dispatcher to avoid blocking main thread
                        withContext(Dispatchers.IO) {
                            try {
                                val file = File(rec.audioPath)
                                if (file.exists() && !file.delete()) {
                                    Log.w(TAG, "Failed to delete audio file: ${rec.audioPath}")
                                }
                            } catch (e: Exception) {
                                // File deletion is non-fatal - log and continue
                                Log.w(TAG, "Error deleting audio file: ${rec.audioPath}", e)
                            }
                        }

                        onDeleted()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to delete recording: ${rec.id}", e)
                        _message.value = "Failed to delete recording"
                    }
                }
            }
        }

        companion object {
            private const val TAG = "RecordingDetailViewModel"
        }

        // ===== Sharing =====

        /**
         * Share the audio file using Android's share sheet.
         */
        fun shareAudio() {
            val rec = recording.value ?: return

            viewModelScope.launch {
                val file = File(rec.audioPath)

                // Check file existence on IO dispatcher
                val exists = withContext(Dispatchers.IO) { file.exists() }
                if (!exists) {
                    _message.value = "Audio file not found"
                    return@launch
                }

                try {
                    val uri =
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.provider",
                            file,
                        )

                    val intent =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "audio/m4a"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, rec.title)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }

                    context.startActivity(
                        Intent.createChooser(intent, "Share audio").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                } catch (e: Exception) {
                    _message.value = "Failed to share: ${e.message}"
                }
            }
        }

        /**
         * Share the transcript text using Android's share sheet.
         */
        fun shareTranscript() {
            val rec = recording.value ?: return
            val trans = transcript.value

            if (trans == null) {
                _message.value = "No transcript available"
                return
            }

            val text =
                buildString {
                    appendLine("# ${rec.title}")
                    appendLine()

                    // Include summary if available
                    trans.summary?.let { summary ->
                        appendLine("## Summary")
                        appendLine(summary)
                        appendLine()
                    }

                    appendLine("## Transcript")
                    appendLine(trans.processedText ?: trans.rawText)
                }

            try {
                val intent =
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, rec.title)
                        putExtra(Intent.EXTRA_TEXT, text)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }

                context.startActivity(
                    Intent.createChooser(intent, "Share transcript").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            } catch (e: Exception) {
                _message.value = "Failed to share: ${e.message}"
            }
        }

        /**
         * Share both audio and transcript together.
         */
        fun shareBoth() {
            val rec = recording.value ?: return
            val trans = transcript.value

            viewModelScope.launch {
                val file = File(rec.audioPath)

                // Check file existence on IO dispatcher
                val exists = withContext(Dispatchers.IO) { file.exists() }
                if (!exists) {
                    _message.value = "Audio file not found"
                    return@launch
                }

                try {
                    val uri =
                        FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.provider",
                            file,
                        )

                    val text =
                        if (trans != null) {
                            buildString {
                                appendLine("# ${rec.title}")
                                appendLine()
                                trans.summary?.let { summary ->
                                    appendLine("## Summary")
                                    appendLine(summary)
                                    appendLine()
                                }
                                appendLine("## Transcript")
                                appendLine(trans.processedText ?: trans.rawText)
                            }
                        } else {
                            rec.title
                        }

                    val intent =
                        Intent(Intent.ACTION_SEND).apply {
                            type = "audio/m4a"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, rec.title)
                            putExtra(Intent.EXTRA_TEXT, text)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }

                    context.startActivity(
                        Intent.createChooser(intent, "Share recording").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        },
                    )
                } catch (e: Exception) {
                    _message.value = "Failed to share: ${e.message}"
                }
            }
        }

        // ===== Re-transcription =====

        /**
         * Retry transcription for a failed recording.
         */
        fun retryTranscription() {
            viewModelScope.launch {
                val rec = recording.value ?: return@launch

                if (rec.status == RecordingStatus.FAILED) {
                    transcriptionQueueManager.retry(recordingId)
                    _message.value = "Re-queued for transcription"
                } else if (rec.status == RecordingStatus.COMPLETED) {
                    // For completed recordings, re-enqueue to re-run transcription
                    transcriptionQueueManager.enqueue(recordingId)
                    _message.value = "Re-queued for transcription"
                }
                refreshRecoveryDiagnostics()
            }
        }

        fun recoverPendingTranscription() {
            viewModelScope.launch {
                val result = transcriptionQueueManager.recoverPendingTranscription(recordingId)
                _message.value =
                    result.toUserMessage(
                        success = "Pending transcription recovered",
                    )
                refreshRecoveryDiagnostics()
            }
        }

        fun recoverEnhancing() {
            viewModelScope.launch {
                val result = transcriptionQueueManager.recoverEnhancing(recordingId)
                _message.value =
                    result.toUserMessage(
                        success = "Enhancement recovery queued",
                    )
                refreshRecoveryDiagnostics()
            }
        }

        fun retranscribeFromEnhancing() {
            viewModelScope.launch {
                val result = transcriptionQueueManager.retranscribeFromEnhancing(recordingId)
                _message.value =
                    result.toUserMessage(
                        success = "Full retranscription queued",
                    )
                refreshRecoveryDiagnostics()
            }
        }

        private suspend fun refreshRecoveryDiagnostics() {
            val rec = recording.value ?: return
            _recoveryDiagnostics.value =
                transcriptionQueueManager
                    .getRecoveryDiagnostics(rec.id)
                    .toUiModel()
        }

        // Audio playback controls

        fun loadAudio() {
            recording.value?.let { rec ->
                audioPlayer.load(rec.audioPath, autoPlay = false)
            }
        }

        fun togglePlayPause() {
            val currentState = playbackState.value
            if (currentState is PlaybackState.Idle) {
                recording.value?.let { rec ->
                    audioPlayer.load(rec.audioPath, autoPlay = true)
                }
            } else {
                audioPlayer.togglePlayPause()
            }
        }

        fun seekTo(positionMs: Long) {
            audioPlayer.seekTo(positionMs)
        }

        fun skipBackward() {
            audioPlayer.skipBackward()
        }

        fun skipForward() {
            audioPlayer.skipForward()
        }

        override fun onCleared() {
            super.onCleared()
            audioPlayer.release()
        }
    }

internal data class RecoveryDiagnosticsUi(
    val latestReason: String? = null,
    val lastAttemptEpochMs: Long? = null,
    val ownership: RecoveryOwnershipState = RecoveryOwnershipState.MISSING_OR_TERMINAL,
)

internal data class DetailRecoveryActions(
    val showPendingRecovery: Boolean,
    val showEnhancementRecovery: Boolean,
    val showRetranscribeFromEnhancing: Boolean,
    val showFailedRetry: Boolean,
    val actionsEnabled: Boolean,
)

internal fun computeDetailRecoveryActions(
    status: RecordingStatus?,
    ownership: RecoveryOwnershipState,
): DetailRecoveryActions {
    val isRecoverableStatus =
        status == RecordingStatus.PENDING_TRANSCRIPTION ||
            status == RecordingStatus.ENHANCING
    val disabledByOwnership =
        ownership == RecoveryOwnershipState.ACTIVE ||
            ownership == RecoveryOwnershipState.INSPECTION_TIMEOUT

    return DetailRecoveryActions(
        showPendingRecovery = status == RecordingStatus.PENDING_TRANSCRIPTION,
        showEnhancementRecovery = status == RecordingStatus.ENHANCING,
        showRetranscribeFromEnhancing = status == RecordingStatus.ENHANCING,
        showFailedRetry = status == RecordingStatus.FAILED,
        actionsEnabled = !isRecoverableStatus || !disabledByOwnership,
    )
}

private fun RecoveryDiagnostics.toUiModel(): RecoveryDiagnosticsUi =
    RecoveryDiagnosticsUi(
        latestReason = latestReason,
        lastAttemptEpochMs = lastAttemptEpochMs,
        ownership = ownership,
    )

private fun ManualRecoveryResult.toUserMessage(success: String): String =
    when (this) {
        ManualRecoveryResult.ENQUEUED -> success
        ManualRecoveryResult.BLOCKED_ACTIVE_WORK -> "Already processing. Recovery disabled while active work runs"
        ManualRecoveryResult.BLOCKED_OWNERSHIP_TIMEOUT -> "Could not verify processing ownership. Try again shortly"
        ManualRecoveryResult.NOT_RECOVERABLE_STATE -> "Recovery is unavailable for this state"
    }
