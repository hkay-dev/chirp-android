package dev.chirpboard.app

import dev.chirpboard.app.gguf.GgufNativeDecodeTelemetry

internal enum class GgufDecodeSource {
    MEMORY,
    MAPPED_FILE,
    RECOVERY_BATCH,
}

internal enum class GgufDecodeResultKind {
    SUCCESS,
    NO_SPEECH,
    ENGINE_FAILURE,
    WATCHDOG_TIMEOUT,
    CALLER_CANCELLED,
}

/** Content-free diagnostic for one GGUF native call. */
internal data class GgufDecodeDiagnostic(
    val source: GgufDecodeSource,
    val audioDurationMs: Long,
    val totalMs: Long,
    val loadMs: Long,
    val melMs: Long,
    val encodeMs: Long,
    val decodeMs: Long,
    val nativeStatusCode: Int,
    val result: GgufDecodeResultKind,
)

internal class GgufDecodeDiagnosticHistory(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val lock = Any()
    private val entries = ArrayDeque<GgufDecodeDiagnostic>(capacity)

    init {
        require(capacity > 0)
    }

    fun add(entry: GgufDecodeDiagnostic) {
        synchronized(lock) {
            while (entries.size >= capacity) entries.removeFirst()
            entries.addLast(entry)
        }
    }

    fun snapshot(): List<GgufDecodeDiagnostic> = synchronized(lock) { entries.toList() }

    companion object {
        const val DEFAULT_CAPACITY = 64
    }
}

/** Process-local by design, keeping diagnostic data off disk and bounded to 64 calls. */
internal object GgufDecodeDiagnostics {
    private val history = GgufDecodeDiagnosticHistory()

    fun record(entry: GgufDecodeDiagnostic) = history.add(entry)

    fun snapshot(): List<GgufDecodeDiagnostic> = history.snapshot()
}

internal fun GgufNativeDecodeTelemetry?.toDiagnostic(
    source: GgufDecodeSource,
    audioDurationMs: Long,
    totalMs: Long,
    result: GgufDecodeResultKind,
): GgufDecodeDiagnostic =
    GgufDecodeDiagnostic(
        source = source,
        audioDurationMs = audioDurationMs.coerceAtLeast(0L),
        totalMs = totalMs.coerceAtLeast(0L),
        loadMs = this?.loadMs?.toLong()?.coerceAtLeast(0L) ?: 0L,
        melMs = this?.melMs?.toLong()?.coerceAtLeast(0L) ?: 0L,
        encodeMs = this?.encodeMs?.toLong()?.coerceAtLeast(0L) ?: 0L,
        decodeMs = this?.decodeMs?.toLong()?.coerceAtLeast(0L) ?: 0L,
        nativeStatusCode = this?.statusCode ?: 0,
        result = result,
    )
