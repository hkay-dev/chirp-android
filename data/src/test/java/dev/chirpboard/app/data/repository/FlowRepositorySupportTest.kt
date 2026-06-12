package dev.chirpboard.app.data.repository

import app.cash.turbine.test
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import dev.chirpboard.app.core.testing.MockAndroidLogRule
import org.junit.Test

class FlowRepositorySupportTest {
    @get:Rule
    val androidLog = MockAndroidLogRule()

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

    @Test
    fun `catchRepositoryFlowState drops identical Room re-emissions`() =
        runTest {
            // Mimics Room re-running the same query on an unrelated table invalidation: the same
            // value is emitted twice. distinctUntilChanged (DATA-3) must collapse it to one.
            flow {
                emit(listOf("a", "b"))
                emit(listOf("a", "b"))
                emit(listOf("a", "c"))
            }.catchRepositoryFlowState(tag = "TestRepo", default = emptyList()).test {
                assertEquals(listOf("a", "b"), awaitItem().value)
                assertEquals(listOf("a", "c"), awaitItem().value)
                awaitComplete()
            }
        }

    @Test
    fun `catchRepositoryFlow drops identical re-emissions`() =
        runTest {
            flow {
                emit(listOf("a"))
                emit(listOf("a"))
                emit(listOf("b"))
            }.catchRepositoryFlow(tag = "TestRepo", default = emptyList()).test {
                assertEquals(listOf("a"), awaitItem())
                assertEquals(listOf("b"), awaitItem())
                awaitComplete()
            }
        }
}
