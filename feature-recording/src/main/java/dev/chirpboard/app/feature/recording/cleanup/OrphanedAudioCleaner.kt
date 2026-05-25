package dev.chirpboard.app.feature.recording.cleanup

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.data.repository.RecordingRepository
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
    ) {
        suspend fun cleanOrphanedFiles() {
            withContext(Dispatchers.IO) {
                try {
                    val recordingsDir = File(context.filesDir, "recordings")
                    if (!recordingsDir.exists() || !recordingsDir.isDirectory) {
                        return@withContext
                    }

                    // Get all valid paths from the database
                    val validPaths = recordingRepository.getAllAudioPaths().toSet()

                    // Keep files modified within the last 5 minutes to avoid race conditions
                    // where a file is created but not yet inserted into the DB
                    val thresholdMs = System.currentTimeMillis() - 5 * 60 * 1000

                    val files = recordingsDir.listFiles() ?: return@withContext

                    var deletedCount = 0
                    for (file in files) {
                        // Only process files that look like audio recordings
                        if (file.extension == "m4a" || file.extension == "wav") {
                            val isOldEnough = file.lastModified() < thresholdMs
                            val isOrphaned = !validPaths.contains(file.absolutePath)

                            if (isOldEnough && isOrphaned) {
                                val deleted = file.delete()
                                if (deleted) {
                                    deletedCount++
                                    Log.d(TAG, "Deleted orphaned audio file: ${file.name}")
                                } else {
                                    Log.e(TAG, "Failed to delete orphaned audio file: ${file.name}")
                                }
                            }
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
        }
    }
