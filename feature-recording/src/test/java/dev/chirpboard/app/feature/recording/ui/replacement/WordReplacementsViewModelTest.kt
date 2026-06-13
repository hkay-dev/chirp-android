package dev.chirpboard.app.feature.recording.ui.replacement

import dev.chirpboard.app.data.entity.WordReplacement
import dev.chirpboard.app.data.repository.RepositoryFlowState
import dev.chirpboard.app.data.repository.WordReplacementRepository
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
class WordReplacementsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: WordReplacementRepository

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        repository =
            mockk(relaxed = true) {
                every { getAllReplacements() } returns flowOf(RepositoryFlowState(emptyList()))
            }
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `delete captures the full entity as a pending undo`() = runTest {
        val viewModel = WordReplacementsViewModel(repository)
        val item =
            WordReplacement(
                id = UUID.randomUUID(),
                original = "teh",
                replacement = "the",
                caseSensitive = true,
                enabled = false,
            )

        viewModel.delete(item)
        advanceUntilIdle()

        assertEquals(item, viewModel.pendingUndo.value)
        coVerify(exactly = 1) { repository.delete(item) }
    }

    @Test
    fun `undoDelete re-inserts the deleted replacement verbatim and clears the pending undo`() = runTest {
        val viewModel = WordReplacementsViewModel(repository)
        val item =
            WordReplacement(
                id = UUID.randomUUID(),
                original = "teh",
                replacement = "the",
                caseSensitive = true,
                enabled = false,
            )

        viewModel.delete(item)
        advanceUntilIdle()

        viewModel.undoDelete()
        advanceUntilIdle()

        // PROP-11: re-insert preserves id, original, replacement, case-sensitivity, and enabled.
        coVerify(exactly = 1) { repository.insert(item) }
        assertNull(viewModel.pendingUndo.value)
    }

    @Test
    fun `undoDelete is a no-op when nothing was deleted`() = runTest {
        val viewModel = WordReplacementsViewModel(repository)

        viewModel.undoDelete()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.insert(any()) }
    }
}
