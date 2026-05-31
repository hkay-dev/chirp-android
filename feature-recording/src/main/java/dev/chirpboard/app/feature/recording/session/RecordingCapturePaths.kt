package dev.chirpboard.app.feature.recording.session

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.core.audio.RecordingOutputFormat
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordingCapturePaths
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun captureDir(sessionId: UUID): File =
            File(context.filesDir, "recordings/.capture/$sessionId").apply { mkdirs() }

        fun segmentFile(
            sessionId: UUID,
            index: Int,
            format: RecordingOutputFormat,
        ): File =
            File(
                captureDir(sessionId),
                "seg-${index.toString().padStart(3, '0')}${format.fileExtension}",
            )

        fun durableSegmentFile(
            sessionId: UUID,
            index: Int,
        ): File = segmentFile(sessionId, index, RecordingOutputFormat.WAV)

        fun deleteCaptureArtifacts(sessionId: UUID) {
            captureDir(sessionId).deleteRecursively()
        }
    }
