package dev.chirpboard.app.feature.recording.service

import dev.chirpboard.app.core.audio.RecordingOutputFormat
import dev.chirpboard.app.core.audio.WavFileWriter
import dev.chirpboard.app.feature.recording.session.RecordingCapturePaths
import dev.chirpboard.app.feature.recording.session.RecordingSessionJournal
import java.io.File
import java.util.UUID
import dev.chirpboard.app.feature.recording.session.validation.RecordingFileValidator
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A materialized export file. [validatedPlayable] is true when this materialization pass
 * already ran a full [RecordingFileValidator.validateForStop] PLAYABLE check on [file], so
 * callers on the stop path can skip a duplicate full-file validation read (PERF: the stop
 * path used to validate the same export twice back to back).
 */
data class MaterializedExport(
    val file: File,
    val validatedPlayable: Boolean,
)

@Singleton
class RecordingSegmentFinalize
    @Inject
    constructor(
        private val sessionJournal: RecordingSessionJournal,
        private val segmentConcatenator: RecordingSegmentConcatenator,
        private val capturePaths: RecordingCapturePaths,
        private val fileValidator: RecordingFileValidator,
        private val exportDurability: RecordingExportDurability,
    ) {
        fun materializeExportFile(
            sessionId: UUID?,
            activeSegmentPath: String?,
        ): MaterializedExport? {
            if (sessionId == null) {
                return unvalidatedExport(activeSegmentPath?.let(::File))
            }

            val entry = sessionJournal.findBySessionId(sessionId)
                ?: return unvalidatedExport(activeSegmentPath?.let(::File))
            if (!entry.usesSegmentCapture()) {
                return unvalidatedExport(activeSegmentPath?.let(::File))
                    ?: unvalidatedExport(File(entry.audioPath))
            }

            val exportFile = File(entry.exportAudioPath())
            if (exportFile.exists() && fileValidator.validateForStop(exportFile).isPlayable) {
                return exportFile
                    .takeIf(exportDurability::sync)
                    ?.let { MaterializedExport(it, validatedPlayable = true) }
            }

            val segmentFiles = entry.orderedSegmentFiles(activeSegmentPath)
            if (segmentFiles.isEmpty()) {
                return repairedLegacyExport(exportFile)
            }

            return when (segmentConcatenator.concatToExport(segmentFiles, exportFile)) {
                is SegmentConcatResult.Success -> {
                    // Validate (and repair when possible) BEFORE deleting capture artifacts:
                    // the segments are the only remaining source of audio if the export is bad.
                    if (RecordingOutputFormat.fromFile(exportFile) == RecordingOutputFormat.WAV) {
                        WavFileWriter.repairHeaderIfNeeded(exportFile)
                    }
                    val playableExport = exportFile.takeIf { fileValidator.validateForStop(it).isPlayable }
                    val durableExport = playableExport?.takeIf(exportDurability::sync)
                    if (durableExport != null) {
                        capturePaths.deleteCaptureArtifacts(entry.sessionId)
                    }
                    durableExport?.let { MaterializedExport(it, validatedPlayable = true) }
                }
                is SegmentConcatResult.Failed -> null
            }
        }

        private fun unvalidatedExport(file: File?): MaterializedExport? =
            file?.takeIf { it.exists() }?.let { MaterializedExport(it, validatedPlayable = false) }

        /**
         * Pre-fix app versions could delete segments while leaving an export with a
         * stale/zeroed WAV header. With no segments left, that export's payload is the
         * only remaining audio, so repair its header before giving up on the session.
         */
        private fun repairedLegacyExport(exportFile: File): MaterializedExport? {
            if (!exportFile.exists()) {
                return null
            }
            if (RecordingOutputFormat.fromFile(exportFile) == RecordingOutputFormat.WAV) {
                WavFileWriter.repairHeaderIfNeeded(exportFile)
            }
            return exportFile
                .takeIf { fileValidator.validateForStop(it).isPlayable }
                ?.takeIf(exportDurability::sync)
                ?.let { MaterializedExport(it, validatedPlayable = true) }
        }
    }
