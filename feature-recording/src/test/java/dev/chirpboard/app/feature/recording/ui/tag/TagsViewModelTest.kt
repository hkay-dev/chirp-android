package dev.chirpboard.app.feature.recording.ui.tag

import dev.chirpboard.app.data.entity.Tag
import dev.chirpboard.app.data.repository.RepositoryFlowState
import dev.chirpboard.app.data.repository.TagRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class TagsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var tagRepository: TagRepository

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        tagRepository =
            mockk(relaxed = true) {
                every { getAllTags() } returns flowOf(RepositoryFlowState(emptyList()))
            }
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `deleteTag captures the full entity as a pending undo`() = runTest {
        val viewModel = TagsViewModel(tagRepository)
        val tag = Tag(id = UUID.randomUUID(), name = "Work", color = "#FF5733")

        viewModel.deleteTag(tag)
        advanceUntilIdle()

        // PROP-11: the whole entity (id, name, color) is captured before the delete commits.
        assertEquals(tag, viewModel.pendingUndo.value)
        coVerify(exactly = 1) { tagRepository.delete(tag) }
    }

    @Test
    fun `undoDelete re-inserts the deleted tag preserving its id and clears the pending undo`() = runTest {
        val viewModel = TagsViewModel(tagRepository)
        val tag = Tag(id = UUID.randomUUID(), name = "Work", color = "#FF5733")

        viewModel.deleteTag(tag)
        advanceUntilIdle()

        viewModel.undoDelete()
        advanceUntilIdle()

        // The re-insert uses the captured entity verbatim, so the original id/name/color survive.
        coVerify(exactly = 1) { tagRepository.insert(tag) }
        assertNull(viewModel.pendingUndo.value)
    }

    @Test
    fun `undoDelete is a no-op when nothing was deleted`() = runTest {
        val viewModel = TagsViewModel(tagRepository)

        viewModel.undoDelete()
        advanceUntilIdle()

        coVerify(exactly = 0) { tagRepository.insert(any()) }
    }
}
