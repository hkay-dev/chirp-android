package dev.chirpboard.app.feature.studio.tabs

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
    fun transcriptionProgressKind_mapsPendingTranscriptionToTranscribing() {
        assertEquals(
            TranscriptionProgressKind.Transcribing,
            RecordingStatus.PENDING_TRANSCRIPTION.transcriptionProgressKind(),
        )
    }
}
