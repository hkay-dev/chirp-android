package dev.chirpboard.app.core.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalSpeechModelsTest {
    @Test
    fun `new installs default to the compact Q8 model`() {
        assertEquals(LocalSpeechModelId.PARAKEET_CTC_110M_Q8, LocalSpeechModelId.DEFAULT)
        assertEquals(LocalSpeechModelId.DEFAULT, LocalSpeechModelId.fromPersistedValue(null))
        assertEquals(LocalSpeechModelId.DEFAULT, LocalSpeechModelId.fromPersistedValue("unknown"))
    }

    @Test
    fun `default model is the first recommended catalog entry`() {
        assertEquals(LocalSpeechModelId.DEFAULT, LocalSpeechModelCatalog.models.first().id)
        assertEquals(135, LocalSpeechModelCatalog.requireModel(LocalSpeechModelId.DEFAULT).approximateSizeMb)
    }

    @Test
    fun `saved model selections survive an upgrade`() {
        LocalSpeechModelId.entries.forEach { modelId ->
            assertEquals(modelId, LocalSpeechModelId.fromPersistedValue(modelId.persistedValue))
        }
    }
}
