package dev.chirpboard.app.feature.recording.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class HomeScreenTest {
    @Test
    fun `quick-start surface is hidden when there are no quick starts`() {
        assertFalse(shouldShowHomeQuickStartSurface(emptyList()))
    }

    @Test
    fun `quick-start surface is shown when quick starts exist`() {
        val quickStarts = listOf(HomeQuickStartEntry(id = UUID.randomUUID(), name = "Meeting"))

        assertTrue(shouldShowHomeQuickStartSurface(quickStarts))
    }
}
