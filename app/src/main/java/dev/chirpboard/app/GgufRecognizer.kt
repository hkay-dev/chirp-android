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
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val GGUF_TAG = "GgufRecognizer"
private const val RECOVERY_CHUNK_SECONDS = 30
private const val RECOVERY_CHUNK_OVERLAP_MS = 2_000L
private const val RECOVERY_BATCH_SIZE = 2
private const val CONTINUOUS_DECODE_MAX_SECONDS = 5 * 60
private const val RELEASE_TIMEOUT_MS = 10_000L

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
    private val threadCount: Int by lazy(LazyThreadSafetyMode.NONE) {
        resolvedGgufThreadCount(decodeControls)
    }
    var actualComputeBackend: LocalSpeechComputeBackend? = null
        private set

    val isReady: Boolean
        get() = native != null

    suspend fun initialize(): Boolean {
        if (GgufNativeEngineHealth.isWedged()) {
            Log.e(GGUF_TAG, "Refusing native load: a wedged decode holds the native session; restart required")
            return false
        }
        return decodeDispatcher.run {
            mutex.withLock {
                if (native?.isLoaded() == true) return@withLock true
                val model = downloader.resolvedGgufModelFile(config.modelId) ?: return@withLock false
                val candidate = GgufNativeRecognizer()
                val started = SystemClock.elapsedRealtime()
                val loaded =
                    candidate.load(
                        modelPath = model.absolutePath,
                        threads = threadCount,
                        useVulkan = config.computeBackend == LocalSpeechComputeBackend.VULKAN,
                    )
                Log.i(
                    GGUF_TAG,
                    "backend=gguf-${config.modelId.persistedValue} phase=load " +
                        "requestedCompute=${config.computeBackend.persistedValue} " +
                        "actualCompute=${candidate.loadedBackend()} cpuFallback=${candidate.usedCpuFallback()} " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - started} threads=$threadCount success=$loaded",
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
                }
                loaded
            }
        }
    }

    suspend fun transcribe(samples: FloatArray, sampleRate: Int): TranscriptionOutcome =
        transcribeContinuous(
            sampleCount = samples.size.toLong(),
            sampleRate = sampleRate,
            source = GgufDecodeSource.MEMORY,
            recoverySource = { samples.asFlow(sampleRate) },
            decode = { engine -> engine.transcribe(samples) },
        )

    suspend fun transcribePcmFloatFile(
        path: String,
        sampleCount: Long,
        sampleRate: Int,
    ): TranscriptionOutcome =
        transcribeContinuous(
            sampleCount = sampleCount,
            sampleRate = sampleRate,
            source = GgufDecodeSource.MAPPED_FILE,
            recoverySource = { preservedPcmFloatFlow(path, sampleCount, sampleRate) },
            decode = { engine -> engine.transcribePcmFloatFile(path, sampleCount) },
        )

    /**
     * Shared decode path for in-memory and preserved-file audio.
     *
     * Content integrity rule: the complete continuous recording is authoritative. Overlapping
     * recovery chunks (from [recoverySource]) are used only when the recording exceeds the
     * proven continuous memory ceiling or the native whole-run fails. A failed Vulkan decode
     * retries once on CPU.
     */
    private suspend fun transcribeContinuous(
        sampleCount: Long,
        sampleRate: Int,
        source: GgufDecodeSource,
        recoverySource: () -> Flow<FloatArray>,
        decode: (GgufNativeRecognizer) -> String?,
    ): TranscriptionOutcome =
        decodeDispatcher.run {
            mutex.withLock {
                if (sampleRate != VoiceRecorder.SAMPLE_RATE) {
                    return@withLock TranscriptionOutcome.EngineError(
                        reason = "GGUF transcription requires 16 kHz audio",
                        retryable = false,
                    )
                }
                var engine =
                    native ?: return@withLock TranscriptionOutcome.ModelUnavailable("Recognizer is not initialized")
                val audioDurationMs = sampleCount * 1_000L / sampleRate
                if (audioDurationMs > CONTINUOUS_DECODE_MAX_SECONDS * 1_000L) {
                    Log.i(GGUF_TAG, "Recording exceeds the proven continuous memory ceiling; decoding preserved audio in recovery chunks")
                    var recovered = transcribeRecoveryChunks(engine, recoverySource(), sampleRate)
                    if (recovered == null && actualComputeBackend == LocalSpeechComputeBackend.VULKAN) {
                        switchToCpu()?.let { cpuEngine ->
                            engine = cpuEngine
                            recovered = transcribeRecoveryChunks(engine, recoverySource(), sampleRate)
                        }
                    }
                    return@withLock recoveryOutcome(recovered)
                }
                val started = SystemClock.elapsedRealtime()
                var continuous =
                    runNativeDecode(
                        engine = engine,
                        audioDurationMs = audioDurationMs,
                        source = source,
                        classify = ::classifyText,
                    ) { decode(engine) }
                var continuousText = (continuous as? GgufWatchdogResult.Completed)?.value?.value
                if (continuousText == null && actualComputeBackend == LocalSpeechComputeBackend.VULKAN) {
                    switchToCpu()?.let { cpuEngine ->
                        engine = cpuEngine
                        continuous =
                            runNativeDecode(
                                engine = engine,
                                audioDurationMs = audioDurationMs,
                                source = source,
                                classify = ::classifyText,
                            ) { decode(engine) }
                        continuousText = (continuous as? GgufWatchdogResult.Completed)?.value?.value
                    }
                }
                val text =
                    if (continuousText != null) {
                        continuousText
                    } else {
                        Log.w(GGUF_TAG, "Continuous decode stopped; retrying from preserved audio in recovery chunks")
                        transcribeRecoveryChunks(engine, recoverySource(), sampleRate)
                    }

                val elapsed = SystemClock.elapsedRealtime() - started
                val rtf = if (audioDurationMs > 0) elapsed.toDouble() / audioDurationMs else 0.0
                Log.i(
                    GGUF_TAG,
                    "backend=gguf-${config.modelId.persistedValue} phase=decode source=${source.name} " +
                        "audioMs=$audioDurationMs elapsedMs=$elapsed rtf=$rtf",
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
        var activeEngine = engine
        return try {
            ChunkedAudioProcessor(
                chunkDurationMs = RECOVERY_CHUNK_SECONDS * 1_000L,
                overlapDurationMs = RECOVERY_CHUNK_OVERLAP_MS,
                sampleRate = sampleRate,
            ).processAndJoinBatched(
                audioSource = audioSource,
                batchSize = RECOVERY_BATCH_SIZE,
            ) { batch ->
                val audioDurationMs = batch.sumOf { it.size.toLong() } * 1_000L / sampleRate
                val classifyBatch: (Array<String>?) -> GgufDecodeResultKind = { value ->
                    when {
                        value == null -> GgufDecodeResultKind.ENGINE_FAILURE
                        value.any(String::isNotBlank) -> GgufDecodeResultKind.SUCCESS
                        else -> GgufDecodeResultKind.NO_SPEECH
                    }
                }
                var result =
                    runNativeDecode(
                        engine = activeEngine,
                        audioDurationMs = audioDurationMs,
                        source = GgufDecodeSource.RECOVERY_BATCH,
                        classify = classifyBatch,
                    ) { activeEngine.transcribeBatch(batch) }
                if (result !is GgufWatchdogResult.Completed && actualComputeBackend == LocalSpeechComputeBackend.VULKAN) {
                    switchToCpu()?.let { cpuEngine ->
                        activeEngine = cpuEngine
                        result =
                            runNativeDecode(
                                engine = activeEngine,
                                audioDurationMs = audioDurationMs,
                                source = GgufDecodeSource.RECOVERY_BATCH,
                                classify = classifyBatch,
                            ) { activeEngine.transcribeBatch(batch) }
                    }
                }
                when (result) {
                    is GgufWatchdogResult.Completed -> result.value.value?.toList()
                    is GgufWatchdogResult.Failed,
                    is GgufWatchdogResult.TimedOut,
                    -> null
                }
            }
        } catch (error: IOException) {
            // Preserved-audio reads can fail at collection time (empty capture, file
            // truncated or deleted since its sample count was recorded). That is a failed
            // recovery, not an exception to throw past the TranscriptionOutcome contract.
            Log.e(GGUF_TAG, "Preserved-audio recovery source failed", error)
            null
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
            // telemetry.aborted is the authoritative "the cancel actually took effect"
            // signal; a decode that finished with a real value beat the watchdog's
            // cancel request and its transcript is kept.
            wasAborted = { it.value == null || it.telemetry?.aborted == true },
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
                        modelId = config.modelId.persistedValue,
                        computeBackend = actualComputeBackend?.persistedValue ?: config.computeBackend.persistedValue,
                        threadCount = threadCount,
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

    private fun recoveryOutcome(text: String?): TranscriptionOutcome =
        when {
            text == null ->
                TranscriptionOutcome.EngineError(
                    reason = "GGUF preserved-audio recovery failed",
                    retryable = true,
                )

            text.isBlank() -> TranscriptionOutcome.NoSpeech
            else -> TranscriptionOutcome.Success(text.trim(), wordTimings = null)
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

    private fun switchToCpu(): GgufNativeRecognizer? {
        val model = downloader.resolvedGgufModelFile(config.modelId) ?: return null
        val candidate = GgufNativeRecognizer()
        val loaded =
            candidate.load(
                modelPath = model.absolutePath,
                threads = threadCount,
                useVulkan = false,
            )
        if (!loaded) {
            Log.e(GGUF_TAG, "CPU recovery load failed: ${candidate.lastError()}")
            return null
        }
        native = candidate
        actualComputeBackend = LocalSpeechComputeBackend.CPU
        Log.w(GGUF_TAG, "Vulkan decode failed; reloaded the same model on CPU")
        return candidate
    }

    suspend fun release() {
        if (!released.compareAndSet(false, true)) return
        try {
            withContext(NonCancellable) {
                // A wedged native decode holds both the dispatcher thread and the mutex
                // forever. Waiting for either is suspending-cancellable, so a timeout can
                // still fire; when it does, leak the native session rather than hang every
                // caller of release() (typically model switching) behind a dead decode.
                val releasedCleanly =
                    withTimeoutOrNull(RELEASE_TIMEOUT_MS) {
                        decodeDispatcher.run {
                            mutex.withLock {
                                native?.release()
                                native = null
                                actualComputeBackend = null
                            }
                        }
                    } != null
                if (!releasedCleanly) {
                    Log.w(GGUF_TAG, "Native release timed out behind a stuck decode; leaking the session")
                    // The leaked decode is still inside transcribe_run holding the native
                    // global mutex. Any further nativeLoad would block on that mutex forever
                    // — on a dispatcher thread, under the manager mutex, inside NonCancellable
                    // — turning one wedged decode into a permanent process-wide transcription
                    // deadlock. Poison the native layer instead so later loads fail fast and
                    // callers get clean retryable errors until the process restarts.
                    GgufNativeEngineHealth.markWedged()
                    native = null
                    actualComputeBackend = null
                }
            }
        } finally {
            decodeDispatcher.close()
        }
    }

    fun retireAfterNativeReplacement() {
        if (!released.compareAndSet(false, true)) return
        native = null
        actualComputeBackend = null
        decodeDispatcher.close()
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
    @Volatile private var recognizer: GgufRecognizer? = null

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
            if (GgufNativeSessionReservation.isReserved()) return@withLock false
            peekReadyRecognizer(config)?.let { return@withLock true }
            val previous = peekReadyRecognizer()
            if (previous != null && activeLeases.get() != 0) return@withLock false
            val candidate = GgufRecognizer(downloader, config)
            if (withContext(NonCancellable) { candidate.initialize() }) {
                previous?.retireAfterNativeReplacement()
                recognizer = candidate
                true
            } else {
                candidate.retireAfterNativeReplacement()
                // nativeLoad closes the resident session before opening its replacement, so
                // after a failed load `previous` can no longer decode. Release it (a no-op
                // when the failure happened before the native load ran) rather than keeping
                // a recognizer that reports ready without a session; use paths reload lazily.
                previous?.let { withContext(NonCancellable) { it.release() } }
                recognizer = null
                false
            }
        }

    suspend fun <T> withUsageLeaseOrNull(block: suspend () -> T): T? {
        val acquired =
            mutex.withLock {
                if (GgufNativeSessionReservation.isReserved()) false else {
                    activeLeases.incrementAndGet()
                    true
                }
            }
        if (!acquired) return null
        return try {
            block()
        } finally {
            // The decrement must survive cancellation: a suspending withLock in a finally
            // throws CancellationException on contention, and a lease that never returns
            // permanently blocks model switching, memory-pressure release, and benchmarks.
            withContext(NonCancellable) {
                mutex.withLock { activeLeases.decrementAndGet() }
            }
        }
    }

    suspend fun reserveNativeSessionForBenchmark(): Boolean =
        mutex.withLock {
            if (activeLeases.get() != 0 || !GgufNativeSessionReservation.tryReserve()) {
                return@withLock false
            }
            recognizer?.release()
            recognizer = null
            true
        }

    suspend fun releaseNativeSessionReservation() =
        mutex.withLock { GgufNativeSessionReservation.release() }

    suspend fun releaseIfUnused(): Boolean =
        mutex.withLock {
            if (activeLeases.get() != 0) return@withLock false
            val resident = recognizer ?: return@withLock false
            resident.release()
            recognizer = null
            true
        }
}

/**
 * Latched when a native release timed out behind a stuck decode. The wedged thread still
 * holds the native global mutex, so any subsequent load would block a dispatcher thread
 * forever; every load path checks this first and fails fast instead. Cleared only by
 * process restart.
 */
internal object GgufNativeEngineHealth {
    private val wedged = java.util.concurrent.atomic.AtomicBoolean(false)

    fun markWedged() = wedged.set(true)

    fun isWedged(): Boolean = wedged.get()
}

internal object GgufNativeSessionReservation {
    private val reserved = java.util.concurrent.atomic.AtomicBoolean(false)

    fun tryReserve(): Boolean = reserved.compareAndSet(false, true)

    fun release() = reserved.set(false)

    fun isReserved(): Boolean = reserved.get()
}

internal object GgufNativeCapabilities {
    /**
     * Resolving this dlopens the native libraries; on a device or ABI where a .so is absent
     * or unloadable, construction throws UnsatisfiedLinkError (an Error, not an Exception).
     * The probe is read from Application.onCreate in a scope with no exception handler and
     * the IME shares this process, so an unguarded throw crash-looped both on every launch.
     * A failed probe caches as "no Vulkan" and the CPU backend takes over.
     */
    val supportsVulkan: Boolean by lazy {
        try {
            GgufNativeRecognizer().supportsVulkan()
        } catch (error: Throwable) {
            android.util.Log.e("GgufNativeCapabilities", "Vulkan capability probe failed", error)
            false
        }
    }
}

internal fun effectiveGgufComputeBackend(
    requested: LocalSpeechComputeBackend,
): LocalSpeechComputeBackend =
    if (requested == LocalSpeechComputeBackend.VULKAN && !GgufNativeCapabilities.supportsVulkan) {
        LocalSpeechComputeBackend.CPU
    } else {
        requested
    }

class GgufRecognizerProvider(
    private val downloader: ModelDownloader,
    private val selectionStore: LocalSpeechModelSelectionStore,
) : TranscriberProvider, ContinuousAudioTranscriberPreference, PcmFloatFileTranscriberProvider {
    private fun selectedConfig(): GgufRuntimeConfig =
        GgufRuntimeConfig(
            modelId = selectionStore.selectedModel.value,
            computeBackend = effectiveGgufComputeBackend(selectionStore.selectedComputeBackend.value),
        )

    override fun prefersContinuousAudio(): Boolean = true

    override fun isReady(): Boolean = GgufRecognizerManager.isResident(selectedConfig())

    override fun isModelDownloaded(): Boolean = downloader.resolvedGgufModelFile(selectedConfig().modelId) != null

    override suspend fun initialize(): Boolean = initialize(selectedConfig())

    internal suspend fun initialize(config: GgufRuntimeConfig): Boolean =
        GgufRecognizerManager.initialize(downloader, config)

    override suspend fun transcribe(samples: FloatArray, sampleRate: Int): TranscriptionOutcome =
        GgufRecognizerManager.withUsageLeaseOrNull {
            val config = selectedConfig()
            val recognizer =
                GgufRecognizerManager.peekReadyRecognizer(config)
                    ?: (if (initialize(config)) GgufRecognizerManager.peekReadyRecognizer(config) else null)
                    ?: return@withUsageLeaseOrNull TranscriptionOutcome.ModelUnavailable("Recognizer is not initialized")
            recognizer.transcribe(samples, sampleRate)
        } ?: TranscriptionOutcome.ModelUnavailable("Recognizer is reserved for a controlled benchmark")

    override suspend fun transcribePcmFloatFile(
        path: String,
        sampleCount: Long,
        sampleRate: Int,
    ): TranscriptionOutcome =
        GgufRecognizerManager.withUsageLeaseOrNull {
            val config = selectedConfig()
            val recognizer =
                GgufRecognizerManager.peekReadyRecognizer(config)
                    ?: (if (initialize(config)) GgufRecognizerManager.peekReadyRecognizer(config) else null)
                    ?: return@withUsageLeaseOrNull TranscriptionOutcome.ModelUnavailable("Recognizer is not initialized")
            recognizer.transcribePcmFloatFile(path, sampleCount, sampleRate)
        } ?: TranscriptionOutcome.ModelUnavailable("Recognizer is reserved for a controlled benchmark")

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
