package dev.chirpboard.app

import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SherpaRecognizerTimingTest {
    @Test
    fun `recognizer timing groups whitespace-prefixed tokens into words`() {
        val result =
            OfflineRecognizerResult(
                text = "hello world",
                tokens = arrayOf(" hello", " world"),
                timestamps = floatArrayOf(0.1f, 0.8f),
                lang = "",
                emotion = "",
                event = "",
                durations = floatArrayOf(0.3f, 0.4f),
            )

        val timings = result.toRecognizedWordTimingsOrNull("hello world")

        requireNotNull(timings)
        assertEquals(2, timings.size)
        assertEquals("hello", timings[0].text)
        assertEquals(100L, timings[0].startTimestampMs)
        assertEquals(400L, timings[0].endTimestampMs)
        assertEquals("world", timings[1].text)
        assertEquals(800L, timings[1].startTimestampMs)
        assertEquals(1200L, timings[1].endTimestampMs)
    }

    @Test
    fun `recognizer timing returns null when token alignment diverges from transcript`() {
        val result =
            OfflineRecognizerResult(
                text = "hello world",
                tokens = arrayOf(" hello", " there"),
                timestamps = floatArrayOf(0.1f, 0.8f),
                lang = "",
                emotion = "",
                event = "",
                durations = floatArrayOf(0.3f, 0.4f),
            )

        assertNull(result.toRecognizedWordTimingsOrNull("hello world"))
    }
}
