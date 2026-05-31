package dev.chirpboard.app.feature.widget

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetReceiverDispatchTest {
    @Test
    fun `finishes broadcast after durable toggle path completes`() =
        runTest {
            val events = mutableListOf<String>()

            WidgetReceiverDispatch.dispatchToggle(
                scope = this,
                toggleRecording = {
                    events += "durable-stop-start"
                    events += "durable-stop-complete"
                },
                finish = { events += "finish" },
            )
            advanceUntilIdle()

            assertEquals(
                listOf(
                    "durable-stop-start",
                    "durable-stop-complete",
                    "finish",
                ),
                events,
            )
        }
}
