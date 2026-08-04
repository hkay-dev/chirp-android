package dev.chirpboard.app

import android.os.Process
import java.util.Collections
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufDecodeDispatcherTest {
    @Test
    fun `decode work stays on one named thread and applies priority once`() =
        runBlocking {
            val priorities = Collections.synchronizedList(mutableListOf<Int>())
            val dispatcher =
                GgufDecodeDispatcher(
                    controls = GgufDecodeControls(coordinatorPriority = Process.THREAD_PRIORITY_DEFAULT),
                    setAndroidThreadPriority = priorities::add,
                )
            try {
                val workers =
                    (1..8)
                        .map {
                            async {
                                dispatcher.run {
                                    Thread.currentThread().name to System.identityHashCode(Thread.currentThread())
                                }
                            }
                        }.awaitAll()

                assertEquals(1, workers.map { it.second }.distinct().size)
                assertTrue(workers.all { it.first.startsWith("chirp-gguf-decode") })
                assertEquals(listOf(Process.THREAD_PRIORITY_DEFAULT), priorities)
            } finally {
                dispatcher.close()
            }
        }

    @Test
    fun `benchmark controls accept bounded overrides`() {
        val values =
            mapOf(
                GGUF_THREAD_COUNT_PROPERTY to "2",
                GGUF_THREAD_PRIORITY_PROPERTY to "-99",
            )

        val controls = GgufDecodeControls.fromSystemProperties(values::get)

        assertEquals(2, controls.threadCountOverride)
        assertEquals(GGUF_MIN_COORDINATOR_PRIORITY, controls.coordinatorPriority)
    }

    @Test
    fun `invalid benchmark thread override keeps production selection`() {
        val controls =
            GgufDecodeControls.fromSystemProperties { key ->
                if (key == GGUF_THREAD_COUNT_PROPERTY) "12" else "not-an-int"
            }

        assertNull(controls.threadCountOverride)
        assertEquals(Process.THREAD_PRIORITY_MORE_FAVORABLE, controls.coordinatorPriority)
        assertEquals(
            3,
            resolvedGgufThreadCount(
                controls = controls,
                availableProcessors = 3,
                maxFrequencyReader = { null },
            ),
        )
    }

    @Test
    fun `priority rejection keeps decode thread usable`() =
        runBlocking {
            val dispatcher =
                GgufDecodeDispatcher(
                    setAndroidThreadPriority = { throw SecurityException("not allowed") },
                )
            try {
                assertEquals("decoded", dispatcher.run { "decoded" })
            } finally {
                dispatcher.close()
            }
        }
}
