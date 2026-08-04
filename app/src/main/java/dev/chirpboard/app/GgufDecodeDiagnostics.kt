package dev.chirpboard.app

import android.util.Log
import dev.chirpboard.app.gguf.GgufNativeDecodeTelemetry
import java.io.File
import java.util.concurrent.Executors

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
    val modelId: String,
    val computeBackend: String,
    val threadCount: Int,
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

    fun replaceWith(restored: List<GgufDecodeDiagnostic>) {
        synchronized(lock) {
            val live = entries.toList()
            entries.clear()
            (restored + live).takeLast(capacity).forEach(entries::addLast)
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 64
    }
}

/** Bounded, content-free diagnostics persisted for comparisons across process restarts. */
internal object GgufDecodeDiagnostics {
    private val history = GgufDecodeDiagnosticHistory()
    private val persistenceExecutor =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "chirp-gguf-diagnostics").apply { isDaemon = true }
        }
    @Volatile private var store: GgufDecodeDiagnosticStore? = null

    fun installPersistence(file: File) {
        persistenceExecutor.execute {
            val installed = GgufDecodeDiagnosticStore(file)
            history.replaceWith(installed.read())
            store = installed
            installed.write(history.snapshot())
        }
    }

    fun record(entry: GgufDecodeDiagnostic) {
        history.add(entry)
        persistenceExecutor.execute { store?.write(history.snapshot()) }
    }

    fun snapshot(): List<GgufDecodeDiagnostic> = history.snapshot()
}

internal class GgufDecodeDiagnosticStore(private val file: File) {
    fun read(): List<GgufDecodeDiagnostic> {
        if (!file.isFile) return emptyList()
        return runCatching {
            file.useLines { lines ->
                lines.mapNotNull(::decode).toList().takeLast(GgufDecodeDiagnosticHistory.DEFAULT_CAPACITY)
            }
        }.onFailure { error -> Log.w(TAG, "Could not read GGUF diagnostics", error) }
            .getOrDefault(emptyList())
    }

    fun write(entries: List<GgufDecodeDiagnostic>) {
        runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.bufferedWriter().use { writer ->
                entries.takeLast(GgufDecodeDiagnosticHistory.DEFAULT_CAPACITY).forEach { entry ->
                    writer.appendLine(encode(entry))
                }
            }
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
        }.onFailure { error -> Log.w(TAG, "Could not persist GGUF diagnostics", error) }
    }

    private fun encode(entry: GgufDecodeDiagnostic): String =
        listOf(
            entry.modelId,
            entry.computeBackend,
            entry.threadCount,
            entry.source.name,
            entry.audioDurationMs,
            entry.totalMs,
            entry.loadMs,
            entry.melMs,
            entry.encodeMs,
            entry.decodeMs,
            entry.nativeStatusCode,
            entry.result.name,
        ).joinToString("\t")

    private fun decode(line: String): GgufDecodeDiagnostic? {
        val fields = line.split('\t')
        if (fields.size != 12) return null
        return runCatching {
            GgufDecodeDiagnostic(
                modelId = fields[0],
                computeBackend = fields[1],
                threadCount = fields[2].toInt(),
                source = enumValueOf(fields[3]),
                audioDurationMs = fields[4].toLong(),
                totalMs = fields[5].toLong(),
                loadMs = fields[6].toLong(),
                melMs = fields[7].toLong(),
                encodeMs = fields[8].toLong(),
                decodeMs = fields[9].toLong(),
                nativeStatusCode = fields[10].toInt(),
                result = enumValueOf(fields[11]),
            )
        }.getOrNull()
    }

    private companion object {
        const val TAG = "GgufDiagnostics"
    }
}

internal fun GgufNativeDecodeTelemetry?.toDiagnostic(
    modelId: String,
    computeBackend: String,
    threadCount: Int,
    source: GgufDecodeSource,
    audioDurationMs: Long,
    totalMs: Long,
    result: GgufDecodeResultKind,
): GgufDecodeDiagnostic =
    GgufDecodeDiagnostic(
        modelId = modelId,
        computeBackend = computeBackend,
        threadCount = threadCount.coerceAtLeast(1),
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
