package dev.chirpboard.app.core.audio

import android.content.Context
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class StorageCheckLevel {
    OK,
    LOW,
    CRITICAL,
}

data class StorageCheckResult(
    val level: StorageCheckLevel,
    val availableBytes: Long,
)

@Singleton
class RecordingStorageMonitor
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun checkAvailableStorage(): StorageCheckResult {
            val availableBytes = availableBytes(context.filesDir.absolutePath)
            val level =
                when {
                    availableBytes <= CRITICAL_THRESHOLD_BYTES -> StorageCheckLevel.CRITICAL
                    availableBytes <= LOW_THRESHOLD_BYTES -> StorageCheckLevel.LOW
                    else -> StorageCheckLevel.OK
                }
            return StorageCheckResult(level = level, availableBytes = availableBytes)
        }

        companion object {
            const val LOW_THRESHOLD_BYTES = 100L * 1024L * 1024L
            const val CRITICAL_THRESHOLD_BYTES = 50L * 1024L * 1024L

            fun availableBytes(path: String): Long {
                val statFs = StatFs(path)
                return statFs.availableBlocksLong * statFs.blockSizeLong
            }
        }
    }
