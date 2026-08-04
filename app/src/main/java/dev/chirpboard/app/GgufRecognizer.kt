package dev.chirpboard.app

import android.os.SystemClock
import android.util.Log
import dev.chirpboard.app.core.audio.recorder.VoiceRecorder
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.core.transcription.ContinuousAudioTranscriberPreference
import dev.chirpboard.app.core.transcription.PcmFloatFileTranscriberProvider
import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import dev.chirpboard.app.download.ModelDownloader
import dev.chirpboard.app.feature.transcription.audio.ChunkedAudioProcessor
import dev.chirpboard.app.gguf.GgufNativeRecognizer
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val GGUF_TAG = "GgufRecognizer"
private const val RECOVERY_CHUNK_SECONDS = 30
private const val RECOVERY_CHUNK_OVERLAP_MS = 2_000L
private const val RECOVERY_BATCH_SIZE = 2

internal class GgufRecognizer(
    private val downloader: ModelDownloader,
    private val decodeControls: GgufDecodeControls = GgufDecodeControls.fromSystemProperties(),
    private val decodeDispatcher: GgufDecodeDispatcher = GgufDecodeDispatcher(decodeControls),
) {
    @Volatile private var native: GgufNativeRecognizer? = null
    private val mutex = Mutex()
    private val released = java.util.concurrent.atomic.AtomicBoolean(false)

    val isReady: Boolean
        get() = native?.isLoaded() == true

    suspend fun initialize(): Boolean =
        decodeDispatcher.run {
            mutex.withLock {
                if (native?.isLoaded() == true) return@withLock true
                val model = downloader.resolvedGgufModelFile() ?: return@withLock false
                val candidate = GgufNativeRecognizer()
                val started = SystemClock.elapsedRealtime()
                val threads = resolvedGgufThreadCount(decodeControls)
                val loaded = candidate.load(model.absolutePath, threads)
                Log.i(
                    GGUF_TAG,
                    "backend=gguf-parakeet-110m-q8 phase=load elapsedMs=${SystemClock.elapsedRealtime() - started} " +
                        "threads=$threads success=$loaded",
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
        decodeDispatcher.run {
            mutex.withLock {
                if (sampleRate != VoiceRecorder.SAMPLE_RATE) {
                    return@withLock TranscriptionOutcome.EngineError(
                        reason = "GGUF transcription requires 16 kHz audio",
                        retryable = false,
                    )
                }
                val engine =
                    native ?: return@withLock TranscriptionOutcome.ModelUnavailable("Recognizer is not initialized")
                val started = SystemClock.elapsedRealtime()

                // Content integrity rule: the complete continuous recording is authoritative.
                // Overlapping chunks are only a recovery path if the native whole-run fails.
                val continuousText = engine.transcribe(samples)
                val text =
                    if (continuousText != null) {
                        continuousText
                    } else if (samples.size > sampleRate * RECOVERY_CHUNK_SECONDS) {
                        Log.w(GGUF_TAG, "Continuous decode failed; retrying from preserved audio in recovery chunks")
                        transcribeRecoveryChunks(engine, samples, sampleRate)
                    } else {
                        null
                    }

                val elapsed = SystemClock.elapsedRealtime() - started
                val audioMs = samples.size * 1_000L / sampleRate
                val rtf = if (audioMs > 0) elapsed.toDouble() / audioMs else 0.0
                Log.i(GGUF_TAG, "backend=gguf-parakeet-110m-q8 phase=decode audioMs=$audioMs elapsedMs=$elapsed rtf=$rtf")
                when {
                    text == null ->
                        TranscriptionOutcome.EngineError(
                            engine.lastError().ifBlank { "GGUF transcription failed" },
                            retryable = true,
                        )

                    text.isBlank() -> TranscriptionOutcome.NoSpeech
                    else -> TranscriptionOutcome.Success(text.trim(), wordTimings = null)
                }
            }
        }

    suspend fun transcribePcmFloatFile(
        path: String,
        sampleCount: Long,
        sampleRate: Int,
    ): TranscriptionOutcome =
        decodeDispatcher.run {
            mutex.withLock {
                if (sampleRate != VoiceRecorder.SAMPLE_RATE) {
                    return@withLock TranscriptionOutcome.EngineError(
                        reason = "GGUF transcription requires 16 kHz audio",
                        retryable = false,
                    )
                }
                val engine =
                    native ?: return@withLock TranscriptionOutcome.ModelUnavailable("Recognizer is not initialized")
                val started = SystemClock.elapsedRealtime()
                val text = engine.transcribePcmFloatFile(path, sampleCount)
                val elapsed = SystemClock.elapsedRealtime() - started
                val audioMs = sampleCount * 1_000L / sampleRate
                val rtf = if (audioMs > 0) elapsed.toDouble() / audioMs else 0.0
                Log.i(
                    GGUF_TAG,
                    "backend=gguf-parakeet-110m-q8 phase=decode source=mmap audioMs=$audioMs elapsedMs=$elapsed rtf=$rtf",
                )
                when {
                    text == null ->
                        TranscriptionOutcome.EngineError(
                            engine.lastError().ifBlank { "GGUF transcription failed" },
                            retryable = true,
                        )

                    text.isBlank() -> TranscriptionOutcome.NoSpeech
                    else -> TranscriptionOutcome.Success(text.trim(), wordTimings = null)
                }
            }
        }

    private suspend fun transcribeRecoveryChunks(
        engine: GgufNativeRecognizer,
        samples: FloatArray,
        sampleRate: Int,
    ): String? {
        return ChunkedAudioProcessor(
                chunkDurationMs = RECOVERY_CHUNK_SECONDS * 1_000L,
                overlapDurationMs = RECOVERY_CHUNK_OVERLAP_MS,
                sampleRate = sampleRate,
            ).processAndJoinBatched(
                audioSource = samples.asFlow(sampleRate),
                batchSize = RECOVERY_BATCH_SIZE,
            ) { batch ->
                engine.transcribeBatch(batch)?.toList()
            }
    }

    suspend fun release() {
        if (!released.compareAndSet(false, true)) return
        try {
            withContext(NonCancellable) {
                decodeDispatcher.run {
                    mutex.withLock {
                        native?.release()
                        native = null
                    }
                }
            }
        } finally {
            decodeDispatcher.close()
        }
    }
}

internal object GgufRecognizerManager {
    private val mutex = Mutex()
    private val activeLeases = AtomicInteger(0)
    private var recognizer: GgufRecognizer? = null

    fun peekReadyRecognizer(): GgufRecognizer? = recognizer?.takeIf(GgufRecognizer::isReady)

    fun isResident(): Boolean = peekReadyRecognizer() != null

    suspend fun initialize(downloader: ModelDownloader): Boolean =
        mutex.withLock {
            peekReadyRecognizer()?.let { return@withLock true }
            val candidate = GgufRecognizer(downloader)
            if (candidate.initialize()) {
                recognizer = candidate
                true
            } else {
                candidate.release()
                false
            }
        }

    suspend fun <T> withUsageLease(block: suspend () -> T): T {
        mutex.withLock { activeLeases.incrementAndGet() }
        return try {
            block()
        } finally {
            mutex.withLock { activeLeases.decrementAndGet() }
        }
    }

    suspend fun releaseIfUnused(): Boolean =
        mutex.withLock {
            if (activeLeases.get() != 0) return@withLock false
            val resident = recognizer ?: return@withLock false
            resident.release()
            recognizer = null
            true
        }
}

class GgufRecognizerProvider(
    private val downloader: ModelDownloader,
) : TranscriberProvider, ContinuousAudioTranscriberPreference, PcmFloatFileTranscriberProvider {
    override fun prefersContinuousAudio(): Boolean = true

    override fun isReady(): Boolean = GgufRecognizerManager.isResident()

    override fun isModelDownloaded(): Boolean = downloader.resolvedGgufModelFile() != null

    override suspend fun initialize(): Boolean = GgufRecognizerManager.initialize(downloader)

    override suspend fun transcribe(samples: FloatArray, sampleRate: Int): TranscriptionOutcome =
        GgufRecognizerManager.withUsageLease {
            val recognizer =
                GgufRecognizerManager.peekReadyRecognizer()
                    ?: (if (initialize()) GgufRecognizerManager.peekReadyRecognizer() else null)
                    ?: return@withUsageLease TranscriptionOutcome.ModelUnavailable("Recognizer is not initialized")
            recognizer.transcribe(samples, sampleRate)
        }

    override suspend fun transcribePcmFloatFile(
        path: String,
        sampleCount: Long,
        sampleRate: Int,
    ): TranscriptionOutcome =
        GgufRecognizerManager.withUsageLease {
            val recognizer =
                GgufRecognizerManager.peekReadyRecognizer()
                    ?: (if (initialize()) GgufRecognizerManager.peekReadyRecognizer() else null)
                    ?: return@withUsageLease TranscriptionOutcome.ModelUnavailable("Recognizer is not initialized")
            recognizer.transcribePcmFloatFile(path, sampleCount, sampleRate)
        }

    override suspend fun release() {
        GgufRecognizerManager.releaseIfUnused()
    }
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
