package dev.chirpboard.app.feature.transcription

import dev.chirpboard.app.data.entity.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingEnhancementPolicyTest {
    @Test
    fun `profile policy uses profile transform title and summary settings`() {
        val policy =
            resolveRecordingEnhancementPolicy(
                profile =
                    Profile(
                        name = "Meetings",
                        defaultProcessingMode = "meeting_notes",
                        autoTitle = true,
                        autoSummary = false,
                    ),
                globalAutoTitle = false,
                globalAutoSummary = true,
            )

        assertEquals("meeting_notes", policy.processingModeId)
        assertTrue(policy.autoTitle)
        assertFalse(policy.autoSummary)
        assertTrue(policy.hasRequestedWork)
    }

    @Test
    fun `global policy applies when recording has no profile`() {
        val policy =
            resolveRecordingEnhancementPolicy(
                profile = null,
                globalAutoTitle = true,
                globalAutoSummary = true,
            )

        assertEquals(null, policy.processingModeId)
        assertTrue(policy.autoTitle)
        assertTrue(policy.autoSummary)
        assertTrue(policy.hasRequestedWork)
    }

    @Test
    fun `blank profile transform is ignored`() {
        val policy =
            resolveRecordingEnhancementPolicy(
                profile = Profile(name = "Blank", defaultProcessingMode = "   "),
                globalAutoTitle = true,
                globalAutoSummary = true,
            )

        assertEquals(null, policy.processingModeId)
        assertFalse(policy.autoTitle)
        assertFalse(policy.autoSummary)
        assertFalse(policy.hasRequestedWork)
    }
}
