package dev.chirpboard.app

import dev.chirpboard.app.gguf.GgufNativeDecodeTelemetry
import org.junit.Assert.assertEquals
import org.junit.Test

class GgufDecodeDiagnosticsTest {
    @Test
    fun `history keeps only the newest content free entries`() {
        val history = GgufDecodeDiagnosticHistory(capacity = 2)

        repeat(3) { index ->
            history.add(
                GgufDecodeDiagnostic(
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
}
