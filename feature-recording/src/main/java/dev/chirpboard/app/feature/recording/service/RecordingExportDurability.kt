package dev.chirpboard.app.feature.recording.service

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordingExportDurability
    @Inject
    constructor() {
        /** Makes export bytes durable before the only capture segments are deleted. */
        fun sync(file: File): Boolean =
            runCatching {
                RandomAccessFile(file, "rw").use { it.fd.sync() }
                syncParentDirectory(file)
                true
            }.getOrDefault(false)

        private fun syncParentDirectory(file: File) {
            val directory = file.parentFile ?: return
            val descriptor = runCatching { Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0) }.getOrNull()
                ?: return
            try {
                Os.fsync(descriptor)
            } catch (_: Exception) {
                // The export bytes are already synced. Directory fsync is unavailable on
                // some test and vendor filesystems, so keep the strongest available result.
            } finally {
                runCatching { Os.close(descriptor) }
            }
        }
    }
