package dev.chirpboard.app.feature.keyboard.session

import dev.chirpboard.app.core.audio.recorder.VoiceRecorder
import dev.chirpboard.app.core.reliability.DictationReliabilityMetric
import dev.chirpboard.app.core.reliability.DictationReliabilityMetrics
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RollingTranscriptionTest {
    @Test
    fun `latency trace records content free budgets and soak failures`() {
        DictationReliabilityMetrics.clear()
        DictationReliabilityMetrics.startSoak(targetSessions = 1)
        var now = 0L
        val trace = DictationLatencyTrace("test", nowNanos = { now }, sink = {})
        now = 100_000_000L
        trace.mark("first_durable_sample")
        now = 1_000_000_000L
        trace.mark("stop_requested")
        now = 2_000_000_000L
        trace.asObserver().onRawTranscriptReady()
        trace.recordIntegrity(
            VoiceRecorder.CaptureIntegrityReport(
                elapsedMs = 1_000,
                capturedMs = 950,
                estimatedGapMs = 30,
                sampleCount = 15_200,
                recorderRestartCount = 1,
            ),
        )
        now = 2_050_000_000L
        trace.asObserver().onCommitCompleted(accepted = true)

        val snapshot = DictationReliabilityMetrics.snapshot.value
        assertEquals(
            100L,
            snapshot.summaries.single { it.metric == DictationReliabilityMetric.PRESS_TO_AUDIO }.p95,
        )
        assertEquals(
            1_000L,
            snapshot.summaries.single { it.metric == DictationReliabilityMetric.STOP_TO_RAW }.p95,
        )
        assertEquals(1, snapshot.soak.completedSessions)
        assertEquals(1, snapshot.soak.failedSessions)
        DictationReliabilityMetrics.clear()
    }

    @Test
    fun `latency trace reports content-free monotonic stages`() {
        var now = 1_000_000_000L
        val messages = mutableListOf<String>()
        val trace = DictationLatencyTrace("test", nowNanos = { now }, sink = messages::add)

        now += 12_000_000L
        trace.mark("press")
        now += 25_000_000L
        trace.asObserver().onRawTranscriptReady()

        org.junit.Assert.assertEquals(
            listOf(
                "Dictation latency event=press totalMs=12 stageMs=12",
                "Dictation latency event=raw_transcript_ready totalMs=37 stageMs=25",
            ),
            messages,
        )
    }

    @Test
    fun `incremental reader returns only newly captured complete samples`() {
        val file = kotlin.io.path.createTempFile("chirp-incremental", ".pcm").toFile()
        try {
            java.io.DataOutputStream(file.outputStream()).use { output ->
                listOf(0.25f, -0.5f, 0.75f).forEach { output.writeInt(Integer.reverseBytes(it.toBits())) }
                output.writeByte(0x7f)
            }

            val result = readIncrementalPcmSamples(file.absolutePath, startSample = 1, availableSamples = 4)

            org.junit.Assert.assertArrayEquals(floatArrayOf(-0.5f, 0.75f), result, 0f)
        } finally {
            file.delete()
        }
    }

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
