package dev.chirpboard.app.navigation

import io.mockk.mockk
import org.junit.Assert.assertNotNull
import org.junit.Test
import dev.chirpboard.app.feature.recording.service.RecordingServiceController

class HomeRecordEntryViewModelTest {
    @Test
    fun `test initialization`() {
        val controller = mockk<RecordingServiceController>(relaxed = true)
        val vm = HomeRecordEntryViewModel(controller)
        assertNotNull(vm)
    }
}
