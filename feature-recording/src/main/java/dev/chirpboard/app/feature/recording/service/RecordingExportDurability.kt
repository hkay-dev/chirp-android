package dev.chirpboard.app.feature.recording.service

import dev.chirpboard.app.core.util.DurableFiles
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
                file.parentFile?.let(DurableFiles::syncDirectory)
                true
            }.getOrDefault(false)
    }
