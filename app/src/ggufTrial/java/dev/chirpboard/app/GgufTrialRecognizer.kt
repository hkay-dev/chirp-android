package dev.chirpboard.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.chirpboard.app.core.audio.recorder.VoiceRecorder
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import dev.chirpboard.app.download.ModelDownloader
import dev.chirpboard.app.feature.transcription.audio.ChunkedAudioProcessor
import dev.chirpboard.app.gguf.GgufNativeRecognizer
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val GGUF_TAG = "GgufTrialRecognizer"
private const val SINGLE_UTTERANCE_SECONDS = 30
private const val CHUNK_OVERLAP_MS = 2_000L

internal class GgufTrialRecognizer(
    private val context: Context,
    private val downloader: ModelDownloader,
) {
    private var native: GgufNativeRecognizer? = null
    private val mutex = Mutex()

    val isReady: Boolean
        get() = native?.isLoaded() == true

    suspend fun initialize(): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (native?.isLoaded() == true) return@withLock true
                val model = downloader.resolvedGgufModelFile() ?: return@withLock false
                val candidate = GgufNativeRecognizer()
                val started = SystemClock.elapsedRealtime()
                val threads = optimizedGgufThreadCount()
                val loaded = candidate.load(model.absolutePath, threads)
                val elapsed = SystemClock.elapsedRealtime() - started
                Log.i(
                    GGUF_TAG,
                    "benchmark backend=gguf-parakeet-110m-q8 phase=load elapsedMs=$elapsed threads=$threads success=$loaded",
                )
                if (loaded) {
                    native = candidate
                } else {
                    Log.e(GGUF_TAG, "Native load failed: ${candidate.lastError()}")
                    candidate.release()
                }
                loaded
            }
        }

    suspend fun transcribe(samples: FloatArray, sampleRate: Int): TranscriptionOutcome =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                if (sampleRate != VoiceRecorder.SAMPLE_RATE) {
                    return@withLock TranscriptionOutcome.EngineError(
                        reason = "GGUF trial requires 16 kHz audio",
                        retryable = false,
                    )
                }
                val engine = native ?: return@withLock TranscriptionOutcome.ModelUnavailable("Recognizer is not initialized")
                val started = SystemClock.elapsedRealtime()
                val text =
                    if (samples.size <= sampleRate * SINGLE_UTTERANCE_SECONDS) {
                        engine.transcribe(samples)
                    } else {
                        transcribeChunked(engine, samples, sampleRate)
                    }
                val elapsed = SystemClock.elapsedRealtime() - started
                val audioMs = samples.size * 1_000L / sampleRate
                val rtf = if (audioMs > 0) elapsed.toDouble() / audioMs else 0.0
                Log.i(
                    GGUF_TAG,
                    "benchmark backend=gguf-parakeet-110m-q8 phase=decode audioMs=$audioMs elapsedMs=$elapsed rtf=$rtf",
                )
                if (text == null) {
                    TranscriptionOutcome.EngineError(engine.lastError().ifBlank { "GGUF transcription failed" }, false)
                } else if (text.isBlank()) {
                    TranscriptionOutcome.NoSpeech
                } else {
                    TranscriptionOutcome.Success(text.trim(), wordTimings = null)
                }
            }
        }

    private suspend fun transcribeChunked(
        engine: GgufNativeRecognizer,
        samples: FloatArray,
        sampleRate: Int,
    ): String? {
        var failure = false
        val joined =
            ChunkedAudioProcessor(
                chunkDurationMs = SINGLE_UTTERANCE_SECONDS * 1_000L,
                overlapDurationMs = CHUNK_OVERLAP_MS,
                sampleRate = sampleRate,
            ).processAndJoin(samples.asFlow(sampleRate)) { chunk ->
                engine.transcribe(chunk) ?: run {
                    failure = true
                    ""
                }
            }
        return if (failure) null else joined
    }

    suspend fun release() {
        mutex.withLock {
            native?.release()
            native = null
        }
    }
}

private object GgufTrialRecognizerManager {
    private val mutex = Mutex()
    private val leases = AtomicInteger(0)
    private var recognizer: GgufTrialRecognizer? = null

    fun peek(): GgufTrialRecognizer? = recognizer?.takeIf(GgufTrialRecognizer::isReady)

    suspend fun initialize(context: Context, downloader: ModelDownloader): Boolean =
        mutex.withLock {
            peek()?.let { return@withLock true }
            val candidate = GgufTrialRecognizer(context.applicationContext, downloader)
            if (candidate.initialize()) {
                recognizer = candidate
                true
            } else {
                candidate.release()
                false
            }
        }

    suspend fun <T> withLease(block: suspend () -> T): T {
        leases.incrementAndGet()
        return try {
            block()
        } finally {
            leases.decrementAndGet()
        }
    }

    suspend fun release() {
        mutex.withLock {
            if (leases.get() == 0) {
                recognizer?.release()
                recognizer = null
            }
        }
    }
}

internal class GgufTrialRecognizerProvider(
    private val context: Context,
    private val downloader: ModelDownloader,
) : TranscriberProvider {
    override fun isReady(): Boolean = GgufTrialRecognizerManager.peek() != null

    override fun isModelDownloaded(): Boolean = downloader.isModelDownloaded()

    override suspend fun initialize(): Boolean = GgufTrialRecognizerManager.initialize(context, downloader)

    override suspend fun transcribe(samples: FloatArray, sampleRate: Int): TranscriptionOutcome =
        GgufTrialRecognizerManager.withLease {
            val recognizer =
                GgufTrialRecognizerManager.peek()
                    ?: (if (initialize()) GgufTrialRecognizerManager.peek() else null)
                    ?: return@withLease TranscriptionOutcome.ModelUnavailable("Recognizer is not initialized")
            recognizer.transcribe(samples, sampleRate)
        }

    override suspend fun release() = GgufTrialRecognizerManager.release()
}

private fun FloatArray.asFlow(sliceSize: Int): Flow<FloatArray> =
    flow {
        var offset = 0
        while (offset < size) {
            val end = minOf(size, offset + sliceSize)
            emit(copyOfRange(offset, end))
            offset = end
        }
    }

internal fun optimizedGgufThreadCount(
    availableProcessors: Int = Runtime.getRuntime().availableProcessors(),
    maxFrequencyReader: (Int) -> Long? = ::readCpuMaxFrequency,
): Int {
    val frequencies = (0 until availableProcessors).mapNotNull(maxFrequencyReader)
    val fastest = frequencies.maxOrNull() ?: return availableProcessors.coerceIn(1, 4)
    val fastCoreFloor = fastest * 7 / 10
    return frequencies.count { it >= fastCoreFloor }.coerceIn(1, 4)
}

private fun readCpuMaxFrequency(cpu: Int): Long? {
    val base = "/sys/devices/system/cpu/cpu$cpu/cpufreq"
    return sequenceOf("$base/cpuinfo_max_freq", "$base/scaling_max_freq")
        .mapNotNull { path -> runCatching { java.io.File(path).readText().trim().toLong() }.getOrNull() }
        .firstOrNull()
}
