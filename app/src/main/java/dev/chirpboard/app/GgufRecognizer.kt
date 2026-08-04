package dev.chirpboard.app

import android.os.SystemClock
import android.util.Log
import dev.chirpboard.app.core.audio.recorder.VoiceRecorder
import dev.chirpboard.app.core.transcription.TranscriberProvider
import dev.chirpboard.app.core.transcription.ContinuousAudioTranscriberPreference
import dev.chirpboard.app.core.transcription.LocalSpeechComputeBackend
import dev.chirpboard.app.core.transcription.LocalSpeechModelId
import dev.chirpboard.app.core.transcription.LocalSpeechModelSelectionStore
import dev.chirpboard.app.core.transcription.PcmFloatFileTranscriberProvider
import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import dev.chirpboard.app.download.ModelDownloader
import dev.chirpboard.app.feature.transcription.audio.ChunkedAudioProcessor
import dev.chirpboard.app.gguf.GgufNativeDecodeTelemetry
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
    val config: GgufRuntimeConfig,
    private val decodeControls: GgufDecodeControls = GgufDecodeControls.fromSystemProperties(),
    private val decodeDispatcher: GgufDecodeDispatcher = GgufDecodeDispatcher(decodeControls),
    private val watchdog: GgufDecodeWatchdog = GgufDecodeWatchdog(),
) {
    @Volatile private var native: GgufNativeRecognizer? = null
    private val mutex = Mutex()
    private val released = java.util.concurrent.atomic.AtomicBoolean(false)
    var actualComputeBackend: LocalSpeechComputeBackend? = null
        private set

    val isReady: Boolean
        get() = native?.isLoaded() == true

    suspend fun initialize(): Boolean =
        decodeDispatcher.run {
            mutex.withLock {
                if (native?.isLoaded() == true) return@withLock true
                val model = downloader.resolvedGgufModelFile(config.modelId) ?: return@withLock false
                val candidate = GgufNativeRecognizer()
                val started = SystemClock.elapsedRealtime()
                val threads = resolvedGgufThreadCount(decodeControls)
                val loaded =
                    candidate.load(
                        modelPath = model.absolutePath,
                        threads = threads,
                        useVulkan = config.computeBackend == LocalSpeechComputeBackend.VULKAN,
                    )
                Log.i(
                    GGUF_TAG,
                    "backend=gguf-${config.modelId.persistedValue} phase=load " +
                        "requestedCompute=${config.computeBackend.persistedValue} " +
                        "actualCompute=${candidate.loadedBackend()} cpuFallback=${candidate.usedCpuFallback()} " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - started} threads=$threads success=$loaded",
                )
                if (loaded) {
                    native = candidate
                    actualComputeBackend =
                        if (candidate.usedCpuFallback()) {
                            LocalSpeechComputeBackend.CPU
                        } else {
                            config.computeBackend
                        }
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
                val continuous =
                    runNativeDecode(
                        engine = engine,
                        audioDurationMs = samples.size * 1_000L / sampleRate,
                        source = GgufDecodeSource.MEMORY,
                        classify = ::classifyText,
                    ) { engine.transcribe(samples) }
                val continuousText = (continuous as? GgufWatchdogResult.Completed)?.value?.value
                val text =
                    if (continuousText != null) {
                        continuousText
                    } else if (
                        continuous is GgufWatchdogResult.TimedOut ||
                        samples.size > sampleRate * RECOVERY_CHUNK_SECONDS
                    ) {
                        Log.w(GGUF_TAG, "Continuous decode stopped; retrying from preserved audio in recovery chunks")
                        transcribeRecoveryChunks(engine, samples.asFlow(sampleRate), sampleRate)
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
                            nativeFailureMessage(continuous, engine),
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
                val continuous =
                    runNativeDecode(
                        engine = engine,
                        audioDurationMs = sampleCount * 1_000L / sampleRate,
                        source = GgufDecodeSource.MAPPED_FILE,
                        classify = ::classifyText,
                    ) { engine.transcribePcmFloatFile(path, sampleCount) }
                val continuousText = (continuous as? GgufWatchdogResult.Completed)?.value?.value
                val text =
                    if (continuousText != null) {
                        continuousText
                    } else if (continuous is GgufWatchdogResult.TimedOut) {
                        Log.w(GGUF_TAG, "Mapped decode timed out; retrying from the preserved PCM file")
                        transcribeRecoveryChunks(
                            engine = engine,
                            audioSource = preservedPcmFloatFlow(path, sampleCount, sampleRate),
                            sampleRate = sampleRate,
                        )
                    } else {
                        null
                    }
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
                            nativeFailureMessage(continuous, engine),
                            retryable = true,
                        )

                    text.isBlank() -> TranscriptionOutcome.NoSpeech
                    else -> TranscriptionOutcome.Success(text.trim(), wordTimings = null)
                }
            }
        }

    private suspend fun transcribeRecoveryChunks(
        engine: GgufNativeRecognizer,
        audioSource: Flow<FloatArray>,
        sampleRate: Int,
    ): String? {
        return ChunkedAudioProcessor(
                chunkDurationMs = RECOVERY_CHUNK_SECONDS * 1_000L,
                overlapDurationMs = RECOVERY_CHUNK_OVERLAP_MS,
                sampleRate = sampleRate,
            ).processAndJoinBatched(
                audioSource = audioSource,
                batchSize = RECOVERY_BATCH_SIZE,
            ) { batch ->
                val audioDurationMs = batch.sumOf { it.size.toLong() } * 1_000L / sampleRate
                when (
                    val result =
                        runNativeDecode(
                            engine = engine,
                            audioDurationMs = audioDurationMs,
                            source = GgufDecodeSource.RECOVERY_BATCH,
                            classify = { value ->
                                when {
                                    value == null -> GgufDecodeResultKind.ENGINE_FAILURE
                                    value.any(String::isNotBlank) -> GgufDecodeResultKind.SUCCESS
                                    else -> GgufDecodeResultKind.NO_SPEECH
                                }
                            },
                        ) { engine.transcribeBatch(batch) }
                ) {
                    is GgufWatchdogResult.Completed -> result.value.value?.toList()
                    is GgufWatchdogResult.Failed,
                    is GgufWatchdogResult.TimedOut,
                    -> null
                }
            }
    }

    private suspend fun <T> runNativeDecode(
        engine: GgufNativeRecognizer,
        audioDurationMs: Long,
        source: GgufDecodeSource,
        classify: (T?) -> GgufDecodeResultKind,
        operation: () -> T?,
    ): GgufWatchdogResult<NativeDecodeResult<T>> =
        watchdog.run(
            audioDurationMs = audioDurationMs,
            beginDecode = engine::beginDecode,
            cancelDecode = engine::cancelDecode,
            operation = { operationId ->
                val value = operation()
                NativeDecodeResult(
                    value = value,
                    telemetry = engine.decodeTelemetry(operationId),
                    error = if (value == null) engine.lastError() else "",
                )
            },
            onNativeFinished = { result, timedOut, elapsedMs ->
                val nativeResult = result.getOrNull()
                val resultKind =
                    when {
                        timedOut -> GgufDecodeResultKind.WATCHDOG_TIMEOUT
                        result.isFailure -> GgufDecodeResultKind.ENGINE_FAILURE
                        nativeResult?.telemetry?.aborted == true -> GgufDecodeResultKind.CALLER_CANCELLED
                        else -> classify(nativeResult?.value)
                    }
                val diagnostic =
                    nativeResult?.telemetry.toDiagnostic(
                        source = source,
                        audioDurationMs = audioDurationMs,
                        totalMs = elapsedMs,
                        result = resultKind,
                    )
                GgufDecodeDiagnostics.record(diagnostic)
                Log.i(
                    GGUF_TAG,
                    "nativeStages source=$source totalMs=$elapsedMs loadMs=${diagnostic.loadMs} " +
                        "melMs=${diagnostic.melMs} encodeMs=${diagnostic.encodeMs} " +
                        "decodeMs=${diagnostic.decodeMs} status=${diagnostic.nativeStatusCode} result=$resultKind",
                )
            },
        )

    private fun classifyText(text: String?): GgufDecodeResultKind =
        when {
            text == null -> GgufDecodeResultKind.ENGINE_FAILURE
            text.isBlank() -> GgufDecodeResultKind.NO_SPEECH
            else -> GgufDecodeResultKind.SUCCESS
        }

    private fun nativeFailureMessage(
        result: GgufWatchdogResult<NativeDecodeResult<String>>,
        engine: GgufNativeRecognizer,
    ): String =
        when (result) {
            is GgufWatchdogResult.Completed -> result.value.error
            is GgufWatchdogResult.Failed -> result.error.message.orEmpty()
            is GgufWatchdogResult.TimedOut -> "GGUF decode timed out; preserved-audio recovery failed"
        }.ifBlank { engine.lastError().ifBlank { "GGUF transcription failed" } }

    suspend fun release() {
        if (!released.compareAndSet(false, true)) return
        try {
            withContext(NonCancellable) {
                decodeDispatcher.run {
                    mutex.withLock {
                        native?.release()
                        native = null
                        actualComputeBackend = null
                    }
                }
            }
        } finally {
            decodeDispatcher.close()
        }
    }
}

private data class NativeDecodeResult<T>(
    val value: T?,
    val telemetry: GgufNativeDecodeTelemetry?,
    val error: String,
)

internal object GgufRecognizerManager {
    private val mutex = Mutex()
    private val activeLeases = AtomicInteger(0)
    private var recognizer: GgufRecognizer? = null

    fun peekReadyRecognizer(config: GgufRuntimeConfig? = null): GgufRecognizer? =
        recognizer?.takeIf { it.isReady && (config == null || it.config == config) }

    fun isResident(config: GgufRuntimeConfig? = null): Boolean = peekReadyRecognizer(config) != null

    fun actualComputeBackend(config: GgufRuntimeConfig): LocalSpeechComputeBackend? =
        peekReadyRecognizer(config)?.actualComputeBackend

    suspend fun initialize(
        downloader: ModelDownloader,
        config: GgufRuntimeConfig,
    ): Boolean =
        mutex.withLock {
            peekReadyRecognizer(config)?.let { return@withLock true }
            val previous = peekReadyRecognizer()
            if (previous != null && activeLeases.get() != 0) return@withLock false
            previous?.release()
            recognizer = null

            val candidate = GgufRecognizer(downloader, config)
            if (candidate.initialize()) {
                recognizer = candidate
                true
            } else {
                candidate.release()
                previous?.let { prior ->
                    val rollback = GgufRecognizer(downloader, prior.config)
                    if (rollback.initialize()) {
                        recognizer = rollback
                    } else {
                        rollback.release()
                    }
                }
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
    private val selectionStore: LocalSpeechModelSelectionStore,
) : TranscriberProvider, ContinuousAudioTranscriberPreference, PcmFloatFileTranscriberProvider {
    private fun selectedConfig(): GgufRuntimeConfig =
        GgufRuntimeConfig(
            modelId = selectionStore.selectedModel.value,
            computeBackend = selectionStore.selectedComputeBackend.value,
        )

    override fun prefersContinuousAudio(): Boolean = true

    override fun isReady(): Boolean = GgufRecognizerManager.isResident(selectedConfig())

    override fun isModelDownloaded(): Boolean = downloader.resolvedGgufModelFile(selectedConfig().modelId) != null

    override suspend fun initialize(): Boolean = initialize(selectedConfig())

    internal suspend fun initialize(config: GgufRuntimeConfig): Boolean =
        GgufRecognizerManager.initialize(downloader, config)

    override suspend fun transcribe(samples: FloatArray, sampleRate: Int): TranscriptionOutcome =
        GgufRecognizerManager.withUsageLease {
            val config = selectedConfig()
            val recognizer =
                GgufRecognizerManager.peekReadyRecognizer(config)
                    ?: (if (initialize(config)) GgufRecognizerManager.peekReadyRecognizer(config) else null)
                    ?: return@withUsageLease TranscriptionOutcome.ModelUnavailable("Recognizer is not initialized")
            recognizer.transcribe(samples, sampleRate)
        }

    override suspend fun transcribePcmFloatFile(
        path: String,
        sampleCount: Long,
        sampleRate: Int,
    ): TranscriptionOutcome =
        GgufRecognizerManager.withUsageLease {
            val config = selectedConfig()
            val recognizer =
                GgufRecognizerManager.peekReadyRecognizer(config)
                    ?: (if (initialize(config)) GgufRecognizerManager.peekReadyRecognizer(config) else null)
                    ?: return@withUsageLease TranscriptionOutcome.ModelUnavailable("Recognizer is not initialized")
            recognizer.transcribePcmFloatFile(path, sampleCount, sampleRate)
        }

    override suspend fun release() {
        GgufRecognizerManager.releaseIfUnused()
    }
}

internal data class GgufRuntimeConfig(
    val modelId: LocalSpeechModelId,
    val computeBackend: LocalSpeechComputeBackend,
)

private fun FloatArray.asFlow(sliceSize: Int): Flow<FloatArray> =
    flow {
        var offset = 0
        while (offset < size) {
            val end = minOf(size, offset + sliceSize)
            emit(copyOfRange(offset, end))
            offset = end
        }
    }
