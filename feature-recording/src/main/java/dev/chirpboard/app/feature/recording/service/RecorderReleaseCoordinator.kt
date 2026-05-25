package dev.chirpboard.app.feature.recording.service

import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.chirpboard.app.feature.recording.session.validation.RecordingFileValidator
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class RecorderReleaseResult(
    val stopSucceeded: Boolean,
    val backupPath: String? = null,
)

@Singleton
class RecorderReleaseCoordinator
    @Inject
    constructor() {
        suspend fun releaseRecorder(
            recorder: MediaRecorder?,
            wasPaused: Boolean,
            outputFile: File? = null,
        ): RecorderReleaseResult {
            if (recorder == null) return RecorderReleaseResult(stopSucceeded = true)
            return withContext(Dispatchers.IO) {
                var stopSucceeded = true
                var backupPath: String? = null
                try {
                    if (wasPaused) {
                        recorder.resume()
                    }
                    recorder.stop()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    stopSucceeded = false
                    Log.w(TAG, "MediaRecorder.stop failed; creating backup before release", e)
                    backupPath = outputFile?.let { createBackupCopy(it) }
                } finally {
                    try {
                        recorder.release()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "MediaRecorder.release failed", e)
                    }
                }
                RecorderReleaseResult(stopSucceeded = stopSucceeded, backupPath = backupPath)
            }
        }

        suspend fun releaseWithoutStop(recorder: MediaRecorder?) {
            if (recorder == null) return
            withContext(Dispatchers.IO) {
                try {
                    recorder.release()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "MediaRecorder.release failed", e)
                }
            }
        }

        private fun createBackupCopy(source: File): String? {
            if (!source.exists() || source.length() < RecordingFileValidator.MIN_BYTES) return null
            val backup = File(RecordingFileValidator.recoveryPathFor(source.absolutePath))
            return runCatching {
                source.copyTo(backup, overwrite = true)
                backup.absolutePath
            }.getOrNull()
        }

        companion object {
            private const val TAG = "RecorderReleaseCoordinator"
        }
    }
