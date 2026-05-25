package dev.chirpboard.app.data.repository

import app.cash.turbine.test
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FlowRepositorySupportTest {
    @Test
    fun `catchRepositoryFlow emits default on failure`() =
        runTest {
            flow<List<String>> {
                emit(listOf("ok"))
                error("boom")
            }.catchRepositoryFlow(tag = "TestRepo", default = emptyList()).test {
                assertEquals(listOf("ok"), awaitItem())
                assertEquals(emptyList<String>(), awaitItem())
                awaitComplete()
            }
        }

    @Test
    fun `catchRepositoryFlowState emits error message on failure`() =
        runTest {
            flow<List<String>> {
                emit(listOf("ok"))
                error("boom")
            }.catchRepositoryFlowState(tag = "TestRepo", default = emptyList()).test {
                assertEquals(listOf("ok"), awaitItem().value)
                val failed = awaitItem()
                assertEquals(emptyList<String>(), failed.value)
                assertEquals("boom", failed.errorMessage)
                awaitComplete()
            }
        }
}
