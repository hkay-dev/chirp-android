package dev.chirpboard.app.feature.recording.session

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
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
        ): File = File(captureDir(sessionId), "seg-${index.toString().padStart(3, '0')}.m4a")

        fun deleteCaptureArtifacts(sessionId: UUID) {
            captureDir(sessionId).deleteRecursively()
        }
    }
