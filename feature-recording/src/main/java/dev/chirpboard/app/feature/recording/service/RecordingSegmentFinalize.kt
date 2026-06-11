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

@Singleton
class RecordingSegmentFinalize
    @Inject
    constructor(
        private val sessionJournal: RecordingSessionJournal,
        private val segmentConcatenator: RecordingSegmentConcatenator,
        private val capturePaths: RecordingCapturePaths,
        private val fileValidator: RecordingFileValidator,
    ) {
        fun materializeExportFile(
            sessionId: UUID?,
            activeSegmentPath: String?,
        ): File? {
            if (sessionId == null) {
                return activeSegmentPath?.let(::File)?.takeIf { it.exists() }
            }

            val entry = sessionJournal.findBySessionId(sessionId) ?: return activeSegmentPath?.let(::File)?.takeIf { it.exists() }
            if (!entry.usesSegmentCapture()) {
                return activeSegmentPath?.let(::File)?.takeIf { it.exists() }
                    ?: File(entry.audioPath).takeIf { it.exists() }
            }

            val exportFile = File(entry.exportAudioPath())
            if (exportFile.exists() && fileValidator.validateForStop(exportFile).isPlayable) {
                return exportFile
            }

            val segmentFiles = entry.orderedSegmentFiles(activeSegmentPath)
            if (segmentFiles.isEmpty()) {
                return null
            }

            return when (segmentConcatenator.concatToExport(segmentFiles, exportFile)) {
                is SegmentConcatResult.Success -> {
                    // Validate (and repair when possible) BEFORE deleting capture artifacts:
                    // the segments are the only remaining source of audio if the export is bad.
                    if (RecordingOutputFormat.fromFile(exportFile) == RecordingOutputFormat.WAV) {
                        WavFileWriter.repairHeaderIfNeeded(exportFile)
                    }
                    val playableExport = exportFile.takeIf { fileValidator.validateForStop(it).isPlayable }
                    if (playableExport != null) {
                        capturePaths.deleteCaptureArtifacts(entry.sessionId)
                    }
                    playableExport
                }
                is SegmentConcatResult.Failed -> null
            }
        }
    }
