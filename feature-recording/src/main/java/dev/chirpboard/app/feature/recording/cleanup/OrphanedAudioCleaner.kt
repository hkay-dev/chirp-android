package dev.chirpboard.app.feature.recording.cleanup

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.core.audio.recorder.VoiceRecorder
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.reliability.ReliabilityOutcome
import dev.chirpboard.app.core.reliability.ReliabilityStage
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.recording.session.validation.RecordingFileValidator
import dev.chirpboard.app.feature.recording.session.RecordingRecoveryProtectedPathsStore
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
        private val protectedPathsStore: RecordingRecoveryProtectedPathsStore,
    ) {
        suspend fun cleanOrphanedFiles() {
            withContext(Dispatchers.IO) {
                try {
                    val dictationDeletedCount = cleanStaleDictationCaptures(System.currentTimeMillis())
                    if (dictationDeletedCount > 0) {
                        Log.i(TAG, "Cleaned up $dictationDeletedCount stale dictation capture file(s)")
                    }

                    val recordingsDir = File(context.filesDir, "recordings")
                    if (!recordingsDir.exists() || !recordingsDir.isDirectory) {
                        return@withContext
                    }

                    // validPaths covers EVERY recording row regardless of status, so audio
                    // referenced by RECORDING (in-progress) rows is never deleted here.
                    // Live journal entries (ACTIVE/STOPPING) are covered by safelistedPaths,
                    // and journalReferencedPaths additionally includes quarantined
                    // (unparseable) journal entries' best-effort paths.
                    val validPaths = recordingRepository.getAllAudioPaths().toSet()
                    // One journal directory scan under journalLock yields all three
                    // projections; separate getAll/getSafelisted/startedAt calls would
                    // re-list and re-parse the directory three times, each extending the
                    // lock window the recording heartbeat contends on.
                    val journalSnapshot = sessionJournal.loadCleanupSnapshot()
                    val journalReferencedPaths = journalSnapshot.allReferencedAudioPaths()
                    val safelistedPaths = journalSnapshot.safelistedAudioPaths()
                    // Active and expired sets come from a single store snapshot so a TTL
                    // lapsing mid-scan can never land a path in both sets; markers are
                    // cleared only after the audio is durably quarantined or deleted.
                    val (protectedPaths, expiredProtectedPaths) = protectedPathsStore.partitionProtectedPaths()
                    val startedAtByPath = journalSnapshot.startedAtByAudioPath()
                    val now = System.currentTimeMillis()

                    val files = recordingsDir.listFiles() ?: return@withContext

                    var deletedCount = 0
                    for (file in files) {
                        if (file.extension !in ORPHAN_EXTENSIONS) continue
                        if (file.parentFile?.name == ".capture") continue

                        val absolutePath = file.absolutePath
                        if (validPaths.contains(absolutePath)) continue
                        if (safelistedPaths.contains(absolutePath)) continue
                        if (protectedPaths.contains(absolutePath)) continue
                        if (journalReferencedPaths.contains(absolutePath)) continue

                        val ageReferenceMs = startedAtByPath[absolutePath] ?: file.lastModified()
                        val ageMs = now - ageReferenceMs
                        val graceMs =
                            when {
                                file.length() >= LARGE_ORPHAN_BYTES -> UNKNOWN_LARGE_ORPHAN_GRACE_MS
                                else -> DEFAULT_ORPHAN_GRACE_MS
                            }

                        if (ageMs < graceMs) continue

                        if (expiredProtectedPaths.contains(absolutePath) && looksRecoverable(file)) {
                            // The user explicitly kept this audio and the 7-day protection
                            // lapsed; prefer quarantine over deletion while it still looks
                            // recoverable.
                            quarantineExpiredProtectedFile(recordingsDir, file)
                            continue
                        }

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

                    deletedCount +=
                        cleanOrphanedCaptureDirs(
                            recordingsDir = recordingsDir,
                            validPaths = validPaths,
                            safelistedPaths = safelistedPaths,
                            protectedPaths = protectedPaths,
                            journalReferencedPaths = journalReferencedPaths,
                            expiredProtectedPaths = expiredProtectedPaths,
                            now = now,
                        )
                    deletedCount += purgeStaleQuarantine(recordingsDir, now)

                    // Fail closed: markers are consumed only once their file no longer
                    // exists at its original path (quarantined, deleted, or long gone).
                    // A failed rename or a crash leaves the marker in place, so the next
                    // run retries the quarantine instead of hard-deleting the kept audio.
                    protectedPathsStore.clearPaths(
                        expiredProtectedPaths.filterNot { path -> File(path).exists() },
                    )

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

        private fun cleanOrphanedCaptureDirs(
            recordingsDir: File,
            validPaths: Set<String>,
            safelistedPaths: Set<String>,
            protectedPaths: Set<String>,
            journalReferencedPaths: Set<String>,
            expiredProtectedPaths: Set<String>,
            now: Long,
        ): Int {
            val captureRoot = File(recordingsDir, ".capture")
            if (!captureRoot.exists() || !captureRoot.isDirectory) {
                return 0
            }

            var deletedCount = 0
            val sessionDirs = captureRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
            for (sessionDir in sessionDirs) {
                val audioFiles =
                    sessionDir
                        .walkTopDown()
                        .filter { file -> file.isFile && file.extension in ORPHAN_EXTENSIONS }
                        .toList()
                val referenced =
                    audioFiles.any { file ->
                        val path = file.absolutePath
                        validPaths.contains(path) ||
                            safelistedPaths.contains(path) ||
                            protectedPaths.contains(path) ||
                            journalReferencedPaths.contains(path)
                    }
                if (referenced) continue

                val newestModified =
                    (audioFiles.map { it.lastModified() } + sessionDir.lastModified())
                        .maxOrNull()
                        ?: sessionDir.lastModified()
                if (now - newestModified < DEFAULT_ORPHAN_GRACE_MS) continue

                val keptRecoverableAudio =
                    audioFiles.any { file ->
                        expiredProtectedPaths.contains(file.absolutePath) && looksRecoverable(file)
                    }
                if (keptRecoverableAudio) {
                    // The user explicitly kept segment audio in this session dir and the
                    // 7-day protection lapsed on this very run; quarantine the directory
                    // instead of hard-deleting it.
                    quarantineExpiredProtectedCaptureDir(recordingsDir, sessionDir)
                    continue
                }

                if (sessionDir.deleteRecursively()) {
                    deletedCount++
                    Log.d(TAG, "Deleted orphaned capture directory: ${sessionDir.name}")
                } else {
                    Log.e(TAG, "Failed to delete orphaned capture directory: ${sessionDir.name}")
                }
            }
            return deletedCount
        }

        private fun looksRecoverable(file: File): Boolean =
            file.length() >= RecordingSessionJournal.MIN_RECOVERABLE_FILE_BYTES

        private fun quarantineExpiredProtectedFile(
            recordingsDir: File,
            file: File,
        ) {
            val quarantineDir = File(recordingsDir, QUARANTINE_DIR_NAME).apply { mkdirs() }
            val target = uniqueQuarantineTarget(quarantineDir, file.name)
            if (file.renameTo(target)) {
                target.setLastModified(System.currentTimeMillis())
                Log.i(TAG, "Quarantined expired protected audio: ${file.name}")
                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.PERSISTENCE_SAVE,
                    outcome = ReliabilityOutcome.SKIPPED,
                    correlationId = ReliabilityEventLogger.newCorrelationId("orphan"),
                    reasonCode = "orphan_audio_quarantined",
                    message = file.name,
                )
            } else {
                Log.e(TAG, "Failed to quarantine expired protected audio: ${file.name}")
            }
        }

        private fun quarantineExpiredProtectedCaptureDir(
            recordingsDir: File,
            sessionDir: File,
        ) {
            val quarantineDir = File(recordingsDir, QUARANTINE_DIR_NAME).apply { mkdirs() }
            val target = uniqueQuarantineTarget(quarantineDir, sessionDir.name)
            if (sessionDir.renameTo(target)) {
                target.setLastModified(System.currentTimeMillis())
                Log.i(TAG, "Quarantined expired protected capture directory: ${sessionDir.name}")
                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.PERSISTENCE_SAVE,
                    outcome = ReliabilityOutcome.SKIPPED,
                    correlationId = ReliabilityEventLogger.newCorrelationId("orphan"),
                    reasonCode = "orphan_capture_dir_quarantined",
                    message = sessionDir.name,
                )
            } else {
                Log.e(TAG, "Failed to quarantine expired protected capture directory: ${sessionDir.name}")
            }
        }

        private fun uniqueQuarantineTarget(
            quarantineDir: File,
            name: String,
        ): File {
            val direct = File(quarantineDir, name)
            if (!direct.exists()) return direct
            return File(quarantineDir, "${System.currentTimeMillis()}_$name")
        }

        /**
         * Deletes leftover keyboard dictation capture files (cacheDir/keyboard-capture/
         * dictation-*.f32pcm) that VoiceRecorder wrote but a crashed or killed IME never
         * cleaned up. Anything older than [DICTATION_CAPTURE_MAX_AGE_MS] is dead: live
         * dictations are consumed within a single keyboard session.
         */
        private fun cleanStaleDictationCaptures(now: Long): Int {
            val captureDir = File(context.cacheDir, VoiceRecorder.KEYBOARD_CAPTURE_CACHE_DIR)
            if (!captureDir.isDirectory) return 0
            var deleted = 0
            captureDir.listFiles()?.forEach { file ->
                val isDictationCapture =
                    file.isFile &&
                        file.name.startsWith(VoiceRecorder.DICTATION_CAPTURE_FILE_PREFIX) &&
                        file.name.endsWith(VoiceRecorder.DICTATION_CAPTURE_FILE_SUFFIX)
                val stale = now - file.lastModified() >= DICTATION_CAPTURE_MAX_AGE_MS
                if (isDictationCapture && stale) {
                    if (file.delete()) {
                        deleted++
                        Log.d(TAG, "Deleted stale dictation capture: ${file.name}")
                    } else {
                        Log.e(TAG, "Failed to delete stale dictation capture: ${file.name}")
                    }
                }
            }
            return deleted
        }

        private fun purgeStaleQuarantine(
            recordingsDir: File,
            now: Long,
        ): Int {
            val quarantineDir = File(recordingsDir, QUARANTINE_DIR_NAME)
            if (!quarantineDir.isDirectory) return 0
            var deleted = 0
            quarantineDir.listFiles()?.forEach { file ->
                val stale = now - file.lastModified() >= QUARANTINE_RETENTION_MS
                if (!stale) return@forEach
                val removed = if (file.isDirectory) file.deleteRecursively() else file.delete()
                if (removed) {
                    deleted++
                    Log.d(TAG, "Deleted stale quarantined audio: ${file.name}")
                }
            }
            return deleted
        }

        companion object {
            private const val TAG = "OrphanedAudioCleaner"
            internal val ORPHAN_EXTENSIONS = setOf("m4a", "wav", "mp3")
            internal const val QUARANTINE_DIR_NAME = ".quarantine"
            private const val DEFAULT_ORPHAN_GRACE_MS = 5 * 60 * 1000L
            private const val UNKNOWN_LARGE_ORPHAN_GRACE_MS = 24 * 60 * 60 * 1000L
            private const val LARGE_ORPHAN_BYTES = 1_000_000L
            private const val QUARANTINE_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
            internal const val DICTATION_CAPTURE_MAX_AGE_MS = 24 * 60 * 60 * 1000L
        }
    }
