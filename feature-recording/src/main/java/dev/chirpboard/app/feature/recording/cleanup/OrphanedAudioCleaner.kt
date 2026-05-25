package dev.chirpboard.app.feature.recording.cleanup

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.reliability.ReliabilityOutcome
import dev.chirpboard.app.core.reliability.ReliabilityStage
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.recording.session.validation.RecordingFileValidator
import dev.chirpboard.app.feature.recording.session.RecordingSessionJournal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OrphanedAudioCleaner
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val recordingRepository: RecordingRepository,
        private val sessionJournal: RecordingSessionJournal,
    ) {
        suspend fun cleanOrphanedFiles() {
            withContext(Dispatchers.IO) {
                try {
                    val recordingsDir = File(context.filesDir, "recordings")
                    if (!recordingsDir.exists() || !recordingsDir.isDirectory) {
                        return@withContext
                    }

                    val validPaths = recordingRepository.getAllAudioPaths().toSet()
                    val journalReferencedPaths = sessionJournal.getAllReferencedAudioPaths()
                    val safelistedPaths = sessionJournal.getSafelistedAudioPaths()
                    val startedAtByPath = sessionJournal.startedAtByAudioPath()
                    val now = System.currentTimeMillis()

                    val files = recordingsDir.listFiles() ?: return@withContext

                    var deletedCount = 0
                    for (file in files) {
                        if (file.extension != "m4a" && file.extension != "wav") continue
                        if (file.parentFile?.name == ".capture") continue

                        val absolutePath = file.absolutePath
                        if (validPaths.contains(absolutePath)) continue
                        if (safelistedPaths.contains(absolutePath)) continue
                        if (journalReferencedPaths.contains(absolutePath)) continue

                        val ageReferenceMs = startedAtByPath[absolutePath] ?: file.lastModified()
                        val ageMs = now - ageReferenceMs
                        val graceMs =
                            when {
                                file.length() >= LARGE_ORPHAN_BYTES -> UNKNOWN_LARGE_ORPHAN_GRACE_MS
                                else -> DEFAULT_ORPHAN_GRACE_MS
                            }

                        if (ageMs < graceMs) continue

                        val deleted = file.delete()
                        if (deleted) {
                            deletedCount++
                            Log.d(TAG, "Deleted orphaned audio file: ${file.name}")
                            ReliabilityEventLogger.log(
                                stage = ReliabilityStage.PERSISTENCE_SAVE,
                                outcome = ReliabilityOutcome.SKIPPED,
                                correlationId = ReliabilityEventLogger.newCorrelationId("orphan"),
                                reasonCode = "orphan_audio_deleted",
                                message = file.name,
                            )
                        } else {
                            Log.e(TAG, "Failed to delete orphaned audio file: ${file.name}")
                        }
                    }

                    if (deletedCount > 0) {
                        Log.i(TAG, "Cleaned up $deletedCount orphaned audio file(s)")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error cleaning orphaned audio files", e)
                }
            }
        }

        companion object {
            private const val TAG = "OrphanedAudioCleaner"
            private const val DEFAULT_ORPHAN_GRACE_MS = 5 * 60 * 1000L
            private const val UNKNOWN_LARGE_ORPHAN_GRACE_MS = 24 * 60 * 60 * 1000L
            private const val LARGE_ORPHAN_BYTES = 1_000_000L
        }
    }
