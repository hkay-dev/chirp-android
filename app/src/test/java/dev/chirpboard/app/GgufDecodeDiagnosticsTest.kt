package dev.chirpboard.app

import dev.chirpboard.app.gguf.GgufNativeDecodeTelemetry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.Executor

class GgufDecodeDiagnosticsTest {
    @Test
    fun `history keeps only the newest content free entries`() {
        val history = GgufDecodeDiagnosticHistory(capacity = 2)

        repeat(3) { index ->
            history.add(
                GgufDecodeDiagnostic(
                    modelId = "model",
                    computeBackend = "cpu",
                    threadCount = 4,
                    source = GgufDecodeSource.MEMORY,
                    audioDurationMs = index.toLong(),
                    totalMs = 10,
                    loadMs = 1,
                    melMs = 2,
                    encodeMs = 3,
                    decodeMs = 4,
                    nativeStatusCode = 0,
                    result = GgufDecodeResultKind.SUCCESS,
                ),
            )
        }

        assertEquals(listOf(1L, 2L), history.snapshot().map(GgufDecodeDiagnostic::audioDurationMs))
    }

    @Test
    fun `native stages map to rounded nonnegative diagnostics`() {
        val telemetry =
            GgufNativeDecodeTelemetry(
                loadMs = 10.9f,
                melMs = 2.8f,
                encodeMs = 30.4f,
                decodeMs = 4.2f,
                aborted = false,
                statusCode = 13,
            )

        val result =
            telemetry.toDiagnostic(
                modelId = "model",
                computeBackend = "cpu",
                threadCount = 4,
                source = GgufDecodeSource.MAPPED_FILE,
                audioDurationMs = 1_000,
                totalMs = 50,
                result = GgufDecodeResultKind.WATCHDOG_TIMEOUT,
            )

        assertEquals(10L, result.loadMs)
        assertEquals(2L, result.melMs)
        assertEquals(30L, result.encodeMs)
        assertEquals(4L, result.decodeMs)
        assertEquals(13, result.nativeStatusCode)
    }

    @Test
    fun `store round trips content free diagnostics and skips corrupt rows`() {
        val directory = Files.createTempDirectory("gguf-diagnostics").toFile()
        val file = directory.resolve("history.tsv")
        val store = GgufDecodeDiagnosticStore(file)
        val entry =
            GgufDecodeDiagnostic(
                modelId = "parakeet-tdt-ctc-110m-q4-k-m",
                computeBackend = "cpu",
                threadCount = 4,
                source = GgufDecodeSource.MAPPED_FILE,
                audioDurationMs = 30_000,
                totalMs = 1_200,
                loadMs = 10,
                melMs = 20,
                encodeMs = 900,
                decodeMs = 270,
                nativeStatusCode = 0,
                result = GgufDecodeResultKind.SUCCESS,
            )

        store.write(listOf(entry))
        file.appendText("corrupt\n")

        assertEquals(listOf(entry), store.read())
        directory.deleteRecursively()
    }

    @Test
    fun `diagnostic writes coalesce a burst into one current snapshot`() {
        val executor = QueuedExecutor()
        var writes = 0
        val coalescer = GgufDiagnosticWriteCoalescer(executor) { writes++ }

        repeat(100) { coalescer.requestWrite() }

        assertEquals(1, executor.pendingCount)
        executor.runAll()
        assertEquals(1, writes)
    }

    private class QueuedExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        val pendingCount: Int get() = tasks.size

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }
}
