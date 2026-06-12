package dev.chirpboard.app.feature.transcription

import android.util.Log
import dev.chirpboard.app.core.export.TranscriptExportPort
import dev.chirpboard.app.core.export.TranscriptExportRecording
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.ProfileRepository
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.data.repository.TagRepository
import kotlinx.coroutines.CancellationException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PLH-3/ERR-5/DAT-002: auto-export to Obsidian at the recording pipeline's terminal
 * transition. Called by [TranscriptionWorker] (recordings without enhancement work) and
 * [RecordingEnhancementWorker] (after enhancement resolves), so each completed recording
 * exports exactly once per pipeline run, carrying its final title, transcript, summary,
 * and tag names. The per-profile Auto Export opt-in (PLH-5) is forwarded to the port,
 * which also handles the global toggle, bookkeeping, and failure surfacing.
 *
 * Never throws (besides cancellation): export problems must not fail a worker whose
 * transcription already committed.
 */
@Singleton
class TranscriptionCompletionExporter
    @Inject
    constructor(
        private val recordingRepository: RecordingRepository,
        private val profileRepository: ProfileRepository,
        private val tagRepository: TagRepository,
        private val transcriptExportPort: TranscriptExportPort,
    ) {
        suspend fun exportIfCompleted(recordingId: UUID) {
            try {
                exportCompletedRecording(recordingId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Auto-export after completion failed for $recordingId", e)
            }
        }

        private suspend fun exportCompletedRecording(recordingId: UUID) {
            val recording = recordingRepository.getRecording(recordingId) ?: return
            if (recording.status != RecordingStatus.COMPLETED) {
                return
            }
            val transcript = recordingRepository.getTranscript(recordingId) ?: return
            val text = transcript.processedText?.takeIf { it.isNotBlank() } ?: transcript.rawText
            if (text.isBlank()) {
                return
            }

            val requestedByProfile =
                recording.profileId
                    ?.let { profileRepository.getProfile(it) }
                    ?.autoExportToObsidian == true
            val tagNames = tagRepository.getTagsForRecordingList(recordingId).map { it.name }

            transcriptExportPort
                .exportIfEnabled(
                    recording =
                        TranscriptExportRecording(
                            title = recording.title,
                            createdAtEpochMs = recording.createdAt.time,
                            durationMs = recording.durationMs,
                            sourceName = recording.source.name.lowercase(),
                            id = recording.id,
                        ),
                    transcript = text,
                    summary = transcript.summary,
                    tags = tagNames,
                    requestedByProfile = requestedByProfile,
                ).onSuccess { outcome ->
                    if (outcome.exported) {
                        Log.d(TAG, "Exported completed recording $recordingId to ${outcome.exportedUri}")
                    }
                }.onFailure { error ->
                    // The port already surfaced the failure to the user (notification);
                    // log for diagnostics and keep the worker run successful.
                    Log.e(TAG, "Obsidian export failed for completed recording $recordingId", error)
                }
        }

        private companion object {
            private const val TAG = "TranscriptionExport"
        }
    }
