package dev.chirpboard.app.feature.recording.ui.replacement

import dev.chirpboard.app.data.repository.WordReplacementRepository
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WordReplacementsViewModelTest {
    private lateinit var wordReplacementRepository: WordReplacementRepository
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        wordReplacementRepository = mockk(relaxed = true)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `viewModel can be initialized`() {
        val viewModel = WordReplacementsViewModel(wordReplacementRepository)
        assertNotNull(viewModel)
    }
}
