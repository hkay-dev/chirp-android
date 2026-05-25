package dev.chirpboard.app.feature.recording.session

import android.media.MediaMetadataRetriever
import dev.chirpboard.app.feature.recording.service.RecordingFileValidator
import dev.chirpboard.app.feature.recording.util.useCompat
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.max

data class SessionRecoveryAssessment(
    val recoverableDurationMs: Long,
    val estimatedLostDurationMs: Long,
    val atRiskWindowMs: Long,
    val finalizedSegmentCount: Int,
    val hasIncompleteActiveSegment: Boolean,
    val activeSegmentPlayable: Boolean,
) {
    val hasPotentialLoss: Boolean
        get() = estimatedLostDurationMs >= LOSS_DISPLAY_THRESHOLD_MS

    fun lossSummaryMinutes(): Int =
        TimeUnit.MILLISECONDS.toMinutes(estimatedLostDurationMs.coerceAtLeast(0L))
            .toInt()
            .coerceAtLeast(1)

    companion object {
        private const val LOSS_DISPLAY_THRESHOLD_MS = 15_000L
    }
}

object SessionRecoveryAssessor {
    fun assess(entry: RecordingSessionEntry): SessionRecoveryAssessment {
        val validator = RecordingFileValidator()
        val finalizedSegments = entry.segmentPaths.map(::File).filter { it.exists() }
        val activeFile = File(entry.audioPath)
        val activeInFinalizedList = entry.audioPath in entry.segmentPaths

        val finalizedDurationMs = finalizedSegments.sumOf { probeDurationMs(it) }
        val activeValidation =
            if (activeFile.exists() && !activeInFinalizedList) {
                validator.validateForRecovery(activeFile)
            } else {
                null
            }
        val activeDurationMs =
            if (activeValidation != null && activeValidation.isRecoverableStub) {
                probeDurationMs(activeFile)
            } else {
                0L
            }
        val activePlayable =
            activeFile.exists() &&
                !activeInFinalizedList &&
                validator.validateForStop(activeFile).isPlayable

        val activeSegmentStartedMs = entry.activeSegmentStartedAtEpochMs
        val atRiskWindowMs =
            if (activeFile.exists() && !activeInFinalizedList) {
                (entry.lastHeartbeatEpochMs - activeSegmentStartedMs).coerceAtLeast(0L)
            } else {
                0L
            }

        val hasIncompleteActive =
            activeFile.exists() &&
                !activeInFinalizedList &&
                activeValidation?.isRecoverableStub == true &&
                !activePlayable

        val estimatedLostDurationMs =
            when {
                activePlayable -> 0L
                hasIncompleteActive -> max(atRiskWindowMs - activeDurationMs, 0L)
                !activeInFinalizedList && activeFile.exists() -> atRiskWindowMs
                else -> 0L
            }

        return SessionRecoveryAssessment(
            recoverableDurationMs = finalizedDurationMs + activeDurationMs,
            estimatedLostDurationMs = estimatedLostDurationMs,
            atRiskWindowMs = atRiskWindowMs,
            finalizedSegmentCount = finalizedSegments.size,
            hasIncompleteActiveSegment = hasIncompleteActive,
            activeSegmentPlayable = activePlayable,
        )
    }

    private fun probeDurationMs(file: File): Long =
        runCatching {
            MediaMetadataRetriever().useCompat { retriever ->
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            }
        }.getOrNull()?.coerceAtLeast(0L) ?: 0L
}
