package dev.chirpboard.app.feature.keyboard.session

import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RollingTranscriptionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `live window reads only complete saved samples and keeps little endian values`() {
        val file = temporaryFolder.newFile("live.f32pcm")
        val samples = FloatArray(20) { index -> index / 10f }
        FileOutputStream(file).use { output ->
            val bytes = ByteBuffer.allocate(samples.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach(bytes::putFloat)
            output.write(bytes.array())
        }

        val result = readRollingPcmWindow(file.absolutePath, availableSamples = 15, sampleRate = 1)

        assertEquals(8, result.size)
        assertTrue(result.contentEquals(samples.copyOfRange(7, 15)))
    }

    @Test
    fun `rolling transcript removes the overlapping word boundary`() {
        assertEquals(
            "one two three four five six seven",
            mergeRollingTranscript("one two three four five", "four five six seven"),
        )
    }
}
