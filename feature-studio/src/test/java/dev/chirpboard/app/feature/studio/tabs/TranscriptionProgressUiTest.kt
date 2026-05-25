package dev.chirpboard.app.feature.studio.tabs

import dev.chirpboard.app.core.ui.components.TranscriptionProgressKind
import dev.chirpboard.app.core.ui.components.transcriptionProgressKind
import dev.chirpboard.app.data.model.RecordingStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptionProgressUiTest {
    @Test
    fun transcriptionProgressKind_mapsRecordingToFinalizing() {
        assertEquals(
            TranscriptionProgressKind.Finalizing,
            RecordingStatus.RECORDING.transcriptionProgressKind(),
        )
    }

    @Test
    fun transcriptionProgressKind_mapsPendingTranscriptionToQueued() {
        assertEquals(
            TranscriptionProgressKind.Queued,
            RecordingStatus.PENDING_TRANSCRIPTION.transcriptionProgressKind(),
        )
    }

    @Test
    fun transcriptionProgressKind_mapsPendingEnhancementToQueued() {
        assertEquals(
            TranscriptionProgressKind.Queued,
            RecordingStatus.PENDING_ENHANCEMENT.transcriptionProgressKind(),
        )
    }

    @Test
    fun transcriptionProgressKind_mapsTranscribingToTranscribing() {
        assertEquals(
            TranscriptionProgressKind.Transcribing,
            RecordingStatus.TRANSCRIBING.transcriptionProgressKind(),
        )
    }
}
