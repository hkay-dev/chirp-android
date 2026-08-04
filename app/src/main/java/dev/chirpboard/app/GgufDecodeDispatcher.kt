package dev.chirpboard.app

import android.os.Process
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

private const val GGUF_DECODE_THREAD_NAME = "chirp-gguf-decode"
internal const val GGUF_THREAD_COUNT_PROPERTY = "chirp.gguf.decode.threads"
internal const val GGUF_THREAD_PRIORITY_PROPERTY = "chirp.gguf.decode.priority"
internal const val GGUF_MAX_WORKER_THREADS = 4
internal const val GGUF_MIN_COORDINATOR_PRIORITY = Process.THREAD_PRIORITY_MORE_FAVORABLE
internal const val GGUF_MAX_COORDINATOR_PRIORITY = Process.THREAD_PRIORITY_BACKGROUND

/**
 * Optional process-local controls for repeatable GGUF benchmarks. Invalid or unsafe values fall
 * back to production policy rather than changing decode behavior.
 */
internal data class GgufDecodeControls(
    val threadCountOverride: Int? = null,
    val coordinatorPriority: Int = Process.THREAD_PRIORITY_MORE_FAVORABLE,
) {
    companion object {
        fun fromSystemProperties(
            propertyReader: (String) -> String? = System::getProperty,
        ): GgufDecodeControls =
            GgufDecodeControls(
                threadCountOverride =
                    propertyReader(GGUF_THREAD_COUNT_PROPERTY)
                        ?.toIntOrNull()
                        ?.takeIf { it in 1..GGUF_MAX_WORKER_THREADS },
                coordinatorPriority =
                    propertyReader(GGUF_THREAD_PRIORITY_PROPERTY)
                        ?.toIntOrNull()
                        ?.coerceIn(GGUF_MIN_COORDINATOR_PRIORITY, GGUF_MAX_COORDINATOR_PRIORITY)
                        ?: Process.THREAD_PRIORITY_MORE_FAVORABLE,
            )
    }
}

/**
 * One long-lived coordinator thread for model load, decode, and release. GGML worker threads are
 * created from this stable thread, avoiding Default-dispatcher migration between decode calls.
 */
internal class GgufDecodeDispatcher(
    controls: GgufDecodeControls = GgufDecodeControls.fromSystemProperties(),
    private val setAndroidThreadPriority: (Int) -> Unit = Process::setThreadPriority,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val dispatcher =
        Executors
            .newSingleThreadExecutor { task ->
                Thread(
                    {
                        try {
                            setAndroidThreadPriority(controls.coordinatorPriority)
                        } catch (_: SecurityException) {
                            // Keep the dedicated thread at the platform default priority.
                        } catch (_: IllegalArgumentException) {
                            // Keep the dedicated thread at the platform default priority.
                        }
                        task.run()
                    },
                    GGUF_DECODE_THREAD_NAME,
                ).apply { isDaemon = true }
            }.asCoroutineDispatcher()

    suspend fun <T> run(block: suspend () -> T): T {
        check(!closed.get()) { "GGUF decode dispatcher is closed" }
        return withContext(dispatcher) { block() }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            dispatcher.close()
        }
    }
}

internal fun resolvedGgufThreadCount(
    controls: GgufDecodeControls = GgufDecodeControls.fromSystemProperties(),
    availableProcessors: Int = Runtime.getRuntime().availableProcessors(),
    maxFrequencyReader: (Int) -> Long? = ::readGgufCpuMaxFrequency,
): Int =
    controls.threadCountOverride
        ?: optimizedGgufThreadCount(
            availableProcessors = availableProcessors,
            maxFrequencyReader = maxFrequencyReader,
        )

internal fun optimizedGgufThreadCount(
    availableProcessors: Int = Runtime.getRuntime().availableProcessors(),
    maxFrequencyReader: (Int) -> Long? = ::readGgufCpuMaxFrequency,
): Int {
    val frequencies = (0 until availableProcessors).mapNotNull(maxFrequencyReader)
    val fastest = frequencies.maxOrNull() ?: return availableProcessors.coerceIn(1, GGUF_MAX_WORKER_THREADS)
    val fastCoreFloor = fastest * 7 / 10
    return frequencies.count { it >= fastCoreFloor }.coerceIn(1, GGUF_MAX_WORKER_THREADS)
}

private fun readGgufCpuMaxFrequency(cpu: Int): Long? {
    val base = "/sys/devices/system/cpu/cpu$cpu/cpufreq"
    return sequenceOf("$base/cpuinfo_max_freq", "$base/scaling_max_freq")
        .mapNotNull { path -> runCatching { java.io.File(path).readText().trim().toLong() }.getOrNull() }
        .firstOrNull()
}
