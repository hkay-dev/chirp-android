package dev.chirpboard.app

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import dev.chirpboard.app.gguf.GgufNativeRecognizer
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

/** Controlled cold-load plus warm-decode harness for one file-backed GGUF model. */
class GgufNativeBenchmarkTest {
    @Test
    fun benchmarkFileBackedModel() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val model = requiredFile(arguments.getString("modelPath"), "modelPath")
        val audio = requiredFile(arguments.getString("audioPath"), "audioPath")
        val modelLabel = arguments.getString("modelLabel") ?: model.nameWithoutExtension
        val threads = arguments.getString("threads")?.toIntOrNull()?.coerceIn(1, 8) ?: 4
        val warmRuns = arguments.getString("warmRuns")?.toIntOrNull()?.coerceIn(1, 10) ?: 3
        val useVulkan = arguments.getString("backend") == "vulkan"
        val sampleCount = audio.length() / Float.SIZE_BYTES
        assertTrue("PCM float input must contain complete samples", audio.length() % Float.SIZE_BYTES == 0L)

        val recognizer = GgufNativeRecognizer()
        val dispatcher = GgufDecodeDispatcher(GgufDecodeControls(threadCountOverride = threads))
        val reserved = GgufRecognizerManager.reserveNativeSessionForBenchmark()
        try {
            assertTrue("Could not reserve the process-global GGUF session", reserved)
            val loadStarted = SystemClock.elapsedRealtime()
            assertTrue(
                "Failed to load $modelLabel",
                dispatcher.run { recognizer.load(model.absolutePath, threads, useVulkan) },
            )
            val loadMs = SystemClock.elapsedRealtime() - loadStarted
            Log.i(
                TAG,
                "event=load model=$modelLabel requestedBackend=${if (useVulkan) "vulkan" else "cpu"} " +
                    "actualBackend=${recognizer.loadedBackend()} cpuFallback=${recognizer.usedCpuFallback()} " +
                    "kleidiai=${recognizer.usesKleidiAi()} threads=$threads " +
                    "modelBytes=${model.length()} loadMs=$loadMs",
            )

            val watchdog =
                Executors.newSingleThreadScheduledExecutor { task ->
                    Thread(task, "chirp-benchmark-watchdog").apply { isDaemon = true }
                }
            try {
                for (run in 0..warmRuns) {
                    val operationId = recognizer.beginDecode()
                    val timedOut = AtomicBoolean(false)
                    val timeout =
                        watchdog.schedule(
                            {
                                if (recognizer.cancelDecode(operationId)) timedOut.set(true)
                            },
                            MAX_DECODE_SECONDS,
                            TimeUnit.SECONDS,
                        )
                    val started = SystemClock.elapsedRealtime()
                    val transcript =
                        dispatcher.run { recognizer.transcribePcmFloatFile(audio.absolutePath, sampleCount) }
                    timeout.cancel(false)
                    val elapsedMs = SystemClock.elapsedRealtime() - started
                    val telemetry = recognizer.decodeTelemetry(operationId)
                    Log.i(
                        TAG,
                        "event=decode model=$modelLabel run=$run warm=${run > 0} audioSamples=$sampleCount " +
                            "elapsedMs=$elapsedMs loadMs=${telemetry?.loadMs} melMs=${telemetry?.melMs} " +
                            "encodeMs=${telemetry?.encodeMs} decodeMs=${telemetry?.decodeMs} " +
                            "status=${telemetry?.statusCode} timedOut=${timedOut.get()} " +
                            "transcriptSha256=${transcript.orEmpty().sha256()}",
                    )
                    if (timedOut.get()) break
                    assertNotNull("Decode failed on run $run with ${recognizer.lastError()}", transcript)
                }
            } finally {
                watchdog.shutdownNow()
            }
        } finally {
            if (reserved) dispatcher.run { recognizer.release() }
            dispatcher.close()
            if (reserved) GgufRecognizerManager.releaseNativeSessionReservation()
        }
    }

    private fun requiredFile(path: String?, argument: String): File {
        require(!path.isNullOrBlank()) { "Missing instrumentation argument $argument" }
        return File(path).also { require(it.isFile) { "$argument is not a readable file" } }
    }

    private fun String.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val TAG = "ChirpGgufBenchmark"
        const val MAX_DECODE_SECONDS = 90L
    }
}
