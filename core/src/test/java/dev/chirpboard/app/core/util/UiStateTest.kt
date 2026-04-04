package dev.chirpboard.app.core.util

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UiStateTest {

    @Test
    fun `UiState properties work correctly`() {
        val loading = UiState.Loading
        assertTrue(loading.isLoading)
        assertFalse(loading.isSuccess)
        assertFalse(loading.isError)
        assertNull(loading.getOrNull())
        assertNull(loading.errorOrNull())

        val success = UiState.Success("Data")
        assertFalse(success.isLoading)
        assertTrue(success.isSuccess)
        assertFalse(success.isError)
        assertEquals("Data", success.getOrNull())
        assertNull(success.errorOrNull())

        val error = UiState.Error("Error Message")
        assertFalse(error.isLoading)
        assertFalse(error.isSuccess)
        assertTrue(error.isError)
        assertNull(error.getOrNull())
        assertEquals("Error Message", error.errorOrNull())
    }

    @Test
    fun `asUiState transforms successful flow`() = runTest {
        val flow = flowOf("Item 1", "Item 2")
        
        flow.asUiState().test {
            assertEquals(UiState.Success("Item 1"), awaitItem())
            assertEquals(UiState.Success("Item 2"), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `asUiState transforms failing flow`() = runTest {
        val exception = RuntimeException("Flow failed")
        val flow = flow<String> { throw exception }
        
        flow.asUiState().test {
            val error = awaitItem()
            assertTrue(error is UiState.Error)
            assertEquals("Flow failed", error.errorOrNull())
            assertEquals(exception, (error as UiState.Error).cause)
            awaitComplete()
        }
    }

    @Test
    fun `runCatchingUiState returns Success when block succeeds`() = runTest {
        val result = runCatchingUiState { "Success Result" }
        assertTrue(result is UiState.Success)
        assertEquals("Success Result", result.getOrNull())
    }

    @Test
    fun `runCatchingUiState returns Error when block fails`() = runTest {
        val exception = IllegalArgumentException("Bad arg")
        val result = runCatchingUiState { throw exception }
        assertTrue(result is UiState.Error)
        assertEquals("Bad arg", result.errorOrNull())
        assertEquals(exception, (result as UiState.Error).cause)
    }
}