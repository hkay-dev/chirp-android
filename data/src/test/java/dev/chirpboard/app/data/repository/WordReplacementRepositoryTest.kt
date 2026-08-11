package dev.chirpboard.app.data.repository

import dev.chirpboard.app.data.dao.WordReplacementDao
import dev.chirpboard.app.data.entity.WordReplacement
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class WordReplacementRepositoryTest {

    private val mockDao: WordReplacementDao = mockk(relaxed = true)
    private val repository = WordReplacementRepository(mockDao)

    @Test
    fun `createReplacement creates and inserts new replacement`() = runTest {
        val original = "foo"
        val replacement = "bar"
        
        val result = repository.createReplacement(original, replacement, caseSensitive = true, enabled = false)
        
        assertEquals(original, result.original)
        assertEquals(replacement, result.replacement)
        assertEquals(true, result.caseSensitive)
        assertEquals(false, result.enabled)
        
        coVerify { mockDao.insert(result) }
    }

}
