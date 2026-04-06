package dev.chirpboard.app.core.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineUtilsTest {

    @Test
    fun testUiStateProperties() {
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

        val error = UiState.Error("Failed")
        assertFalse(error.isLoading)
        assertFalse(error.isSuccess)
        assertTrue(error.isError)
        assertNull(error.getOrNull())
        assertEquals("Failed", error.errorOrNull())
    }

    @Test
    fun testAsUiStateSuccess() = runTest {
        val states = flowOf("A", "B").asUiState().toList()
        assertEquals(2, states.size)
        assertEquals("A", (states[0] as UiState.Success).data)
        assertEquals("B", (states[1] as UiState.Success).data)
    }

    @Test
    fun testAsUiStateError() = runTest {
        val states = flow {
            emit("A")
            throw RuntimeException("Flow Error")
        }.asUiState().toList()

        assertEquals(2, states.size)
        assertEquals("A", (states[0] as UiState.Success).data)
        val errorState = states[1] as UiState.Error
        assertEquals("Flow Error", errorState.message)
    }

    @Test
    fun testRunCatchingUiState() = runTest {
        val success = runCatchingUiState { "Success" }
        assertTrue(success is UiState.Success)
        assertEquals("Success", (success as UiState.Success).data)

        val error = runCatchingUiState { throw RuntimeException("Error!") }
        assertTrue(error is UiState.Error)
        assertEquals("Error!", (error as UiState.Error).message)
    }

    @Test
    fun testLaunchWithUiState() = runTest {
        val emittedStates = mutableListOf<UiState<String>>()
        
        launchWithUiState(
            onStateChange = { emittedStates.add(it) },
            block = { "Result" }
        )
        
        // Let coroutines run
        kotlinx.coroutines.test.runCurrent()
        
        assertEquals(2, emittedStates.size)
        assertTrue(emittedStates[0] is UiState.Loading)
        assertTrue(emittedStates[1] is UiState.Success)
        assertEquals("Result", (emittedStates[1] as UiState.Success).data)
    }
}
