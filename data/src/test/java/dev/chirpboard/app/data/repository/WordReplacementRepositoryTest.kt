package dev.chirpboard.app.data.repository

import dev.chirpboard.app.data.dao.WordReplacementDao
import dev.chirpboard.app.data.entity.WordReplacement
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class WordReplacementRepositoryTest {

    private val mockDao: WordReplacementDao = mockk(relaxed = true)
    private val repository = WordReplacementRepository(mockDao)

    @Test
    fun `getAllReplacements delegates to dao`() = runTest {
        val replacements = listOf(WordReplacement(UUID.randomUUID(), "a", "b", true, false))
        coEvery { mockDao.getAllReplacements() } returns flowOf(replacements)

        val result = repository.getAllReplacements().first()
        assertEquals(1, result.value.size)
        assertEquals(replacements, result.value)
    }

    @Test
    fun `getEnabledReplacements delegates to dao`() = runTest {
        val expected = listOf(WordReplacement(UUID.randomUUID(), "a", "b", true, true))
        coEvery { mockDao.getEnabledReplacements() } returns expected
        val result = repository.getEnabledReplacements()
        assertEquals(expected, result)
    }

    @Test
    fun `getReplacement delegates to dao`() = runTest {
        val id = UUID.randomUUID()
        val expected = WordReplacement(id, "a", "b", true, true)
        coEvery { mockDao.getReplacement(id) } returns expected
        val result = repository.getReplacement(id)
        assertEquals(expected, result)
    }

    @Test
    fun `getEquivalentReplacement delegates to dao`() = runTest {
        val expected = WordReplacement(UUID.randomUUID(), "foo", "bar", false, true)
        coEvery { mockDao.getEquivalentReplacement("foo", "bar", false) } returns expected

        val result = repository.getEquivalentReplacement("foo", "bar")

        assertEquals(expected, result)
    }

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

    @Test
    fun `insert delegates to dao`() = runTest {
        val repl = WordReplacement(UUID.randomUUID(), "a", "b")
        repository.insert(repl)
        coVerify { mockDao.insert(repl) }
    }

    @Test
    fun `update delegates to dao`() = runTest {
        val repl = WordReplacement(UUID.randomUUID(), "a", "b")
        repository.update(repl)
        coVerify { mockDao.update(repl) }
    }

    @Test
    fun `delete delegates to dao`() = runTest {
        val repl = WordReplacement(UUID.randomUUID(), "a", "b")
        repository.delete(repl)
        coVerify { mockDao.delete(repl) }
    }

    @Test
    fun `deleteById delegates to dao`() = runTest {
        val id = UUID.randomUUID()
        repository.deleteById(id)
        coVerify { mockDao.deleteById(id) }
    }

    @Test
    fun `setEnabled delegates to dao`() = runTest {
        val id = UUID.randomUUID()
        repository.setEnabled(id, false)
        coVerify { mockDao.setEnabled(id, false) }
    }

    @Test
    fun `getCount delegates to dao`() = runTest {
        coEvery { mockDao.getCount() } returns 5
        val result = repository.getCount()
        assertEquals(5, result)
    }

    @Test
    fun `getEnabledCount delegates to dao`() = runTest {
        coEvery { mockDao.getEnabledCount() } returns 3
        val result = repository.getEnabledCount()
        assertEquals(3, result)
    }

    @Test
    fun `applyReplacements applies multiple rules properly`() = runTest {
        val rules = listOf(
            WordReplacement(UUID.randomUUID(), "quick", "slow", caseSensitive = false, enabled = true),
            WordReplacement(UUID.randomUUID(), "fox", "bear", caseSensitive = false, enabled = true)
        )
        coEvery { mockDao.getEnabledReplacements() } returns rules
        
        val input = "The quick brown fox."
        val output = repository.applyReplacements(input)
        
        assertEquals("The slow brown bear.", output)
    }

    @Test
    fun `applyReplacements handles case sensitivity`() = runTest {
        val rules = listOf(
            WordReplacement(UUID.randomUUID(), "apple", "banana", caseSensitive = true, enabled = true)
        )
        coEvery { mockDao.getEnabledReplacements() } returns rules
        
        val input = "I have an apple and an Apple."
        val output = repository.applyReplacements(input)
        
        // Only lowercase 'apple' should be replaced
        assertEquals("I have an banana and an Apple.", output)
    }

    @Test
    fun `applyReplacements ignores case when configured`() = runTest {
        val rules = listOf(
            WordReplacement(UUID.randomUUID(), "apple", "banana", caseSensitive = false, enabled = true)
        )
        coEvery { mockDao.getEnabledReplacements() } returns rules
        
        val input = "I have an apple and an Apple."
        val output = repository.applyReplacements(input)
        
        assertEquals("I have an banana and an banana.", output)
    }
}
