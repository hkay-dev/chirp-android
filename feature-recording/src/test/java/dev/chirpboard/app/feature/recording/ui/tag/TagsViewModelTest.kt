package dev.chirpboard.app.feature.recording.ui.tag

import dev.chirpboard.app.data.entity.Tag
import dev.chirpboard.app.data.repository.RepositoryFlowState
import dev.chirpboard.app.data.repository.TagRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
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

        // The restore uses the captured entity verbatim, so the original id/name/color survive
        // (with no assignments, the recording/profile id lists are empty).
        coVerify(exactly = 1) { tagRepository.restoreTagWithAssignments(tag, emptyList(), emptyList()) }
        assertNull(viewModel.pendingUndo.value)
    }

    @Test
    fun `deleteTag snapshots assignments and undoDelete restores them losslessly`() = runTest {
        val tag = Tag(id = UUID.randomUUID(), name = "Work", color = "#FF5733")
        val recordingIds = listOf(UUID.randomUUID(), UUID.randomUUID())
        val profileIds = listOf(UUID.randomUUID())
        coEvery { tagRepository.getRecordingIdsForTag(tag.id) } returns recordingIds
        coEvery { tagRepository.getProfileIdsForTag(tag.id) } returns profileIds
        val viewModel = TagsViewModel(tagRepository)

        viewModel.deleteTag(tag)
        advanceUntilIdle()
        viewModel.undoDelete()
        advanceUntilIdle()

        // Assignments captured before the cascade delete are handed back to the lossless restore.
        coVerify(exactly = 1) { tagRepository.restoreTagWithAssignments(tag, recordingIds, profileIds) }
    }

    @Test
    fun `undo tapped before the delete commits waits for it and still restores`() = runTest {
        // The snackbar's Undo can arrive while the delete coroutine is still snapshotting
        // assignments. The restore must wait for the delete to commit (otherwise it would
        // throw on the still-present row, or be wiped by the delete landing afterwards).
        val tag = Tag(id = UUID.randomUUID(), name = "Work", color = "#FF5733")
        val recordingIds = listOf(UUID.randomUUID())
        coEvery { tagRepository.getRecordingIdsForTag(tag.id) } returns recordingIds
        val viewModel = TagsViewModel(tagRepository)

        viewModel.deleteTag(tag)
        viewModel.undoDelete()
        advanceUntilIdle()

        coVerifyOrder {
            tagRepository.delete(tag)
            tagRepository.restoreTagWithAssignments(tag, recordingIds, emptyList())
        }
        coVerify(exactly = 0) { tagRepository.insert(any()) }
    }

    @Test
    fun `undoDelete is a no-op when nothing was deleted`() = runTest {
        val viewModel = TagsViewModel(tagRepository)

        viewModel.undoDelete()
        advanceUntilIdle()

        coVerify(exactly = 0) { tagRepository.insert(any()) }
        coVerify(exactly = 0) { tagRepository.restoreTagWithAssignments(any(), any(), any()) }
    }
}
