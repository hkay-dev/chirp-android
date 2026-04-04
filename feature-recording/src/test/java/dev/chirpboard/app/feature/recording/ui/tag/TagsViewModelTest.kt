package dev.chirpboard.app.feature.recording.ui.tag

import dev.chirpboard.app.data.repository.TagRepository
import io.mockk.mockk
import org.junit.Assert.assertNotNull
import org.junit.Test

class TagsViewModelTest {
    @Test
    fun `test initialization`() {
        val repository: TagRepository = mockk(relaxed = true)
        assertNotNull(repository)
    }
}
