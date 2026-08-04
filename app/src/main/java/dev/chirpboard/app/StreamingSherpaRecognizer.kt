package dev.chirpboard.app

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.PowerManager
import android.os.Process
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import dev.chirpboard.app.core.transcription.StreamingTranscriberProvider
import dev.chirpboard.app.core.transcription.StreamingTranscriptionSession
import dev.chirpboard.app.download.confirmModelActivation
import dev.chirpboard.app.download.hasPendingModelActivation
import dev.chirpboard.app.download.promoteModelCandidateAtomically
import dev.chirpboard.app.download.recoverInterruptedModelRollback
import dev.chirpboard.app.download.rollbackModelActivation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A deliberately separate first-pass recognizer. It never shares the Parakeet mutex or executor,
 * so a preview decode cannot get in front of the authoritative full-file decode.
 */
class StreamingSherpaRecognizerProvider(
    context: Context,
) : StreamingTranscriberProvider {
    private val appContext = context.applicationContext
    private val modelStore = StreamingModelStore(appContext)
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val initializeMutex = Mutex()
    private var recognizer: OnlineRecognizer? = null
    private var activeSessionCount = 0
    private var releaseWhenIdle = false

    override suspend fun prepare(): Boolean =
        initializeMutex.withLock {
            // A prepare call represents a live IME owner, including the first prepare after a
            // prior service requested deferred cleanup.
            releaseWhenIdle = false
            if (recognizer != null) {
                return@withLock true
            }
            val modelDir = modelStore.ensureAvailable() ?: return@withLock false
            if (!recoverInterruptedModelRollback(modelDir)) return@withLock false
            for (attempt in 0..1) {
                try {
                    withContext(previewDispatcher) {
                        val model =
                            OnlineModelConfig(
                                transducer =
                                    OnlineTransducerModelConfig(
                                        encoder = File(modelDir, ENCODER).absolutePath,
                                        decoder = File(modelDir, DECODER).absolutePath,
                                        joiner = File(modelDir, JOINER).absolutePath,
                                    ),
                                tokens = File(modelDir, TOKENS).absolutePath,
                                numThreads = 1,
                                debug = false,
                                provider = "cpu",
                                modelType = "zipformer",
                            )
                        val initializedRecognizer =
                            OnlineRecognizer(
                                assetManager = null,
                                config =
                                    OnlineRecognizerConfig(
                                        featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
                                        modelConfig = model,
                                        enableEndpoint = false,
                                        decodingMethod = "greedy_search",
                                    ),
                            )
                        confirmModelActivation(modelDir)
                        recognizer = initializedRecognizer
                    }
                    return@withLock true
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Throwable) {
                    Log.w(TAG, "Streaming preview recognizer is unavailable", failure)
                    val canRetryLastWorking = attempt == 0 && hasPendingModelActivation(modelDir)
                    if (!canRetryLastWorking || !rollbackModelActivation(modelDir)) {
                        return@withLock false
                    }
                    Log.i(TAG, "Retrying streaming recognizer with the last working model")
                }
            }
            false
        }

    override suspend fun openSession(sampleRate: Int): StreamingTranscriptionSession? {
        if (sampleRate != 16_000 || !prepare()) return null
        return initializeMutex.withLock {
            val activeRecognizer = recognizer ?: return@withLock null
            val stream = withContext(previewDispatcher) { activeRecognizer.createStream() }
            activeSessionCount += 1
            Session(activeRecognizer, stream, powerManager, previewDispatcher, ::onSessionClosed)
        }
    }

    override suspend fun release() {
        val recognizerToRelease =
            initializeMutex.withLock {
                releaseWhenIdle = true
                takeRecognizerForReleaseIfIdle()
            }
        releaseRecognizer(recognizerToRelease)
    }

    private suspend fun onSessionClosed() {
        val recognizerToRelease =
            initializeMutex.withLock {
                activeSessionCount = (activeSessionCount - 1).coerceAtLeast(0)
                takeRecognizerForReleaseIfIdle()
            }
        releaseRecognizer(recognizerToRelease)
    }

    private fun takeRecognizerForReleaseIfIdle(): OnlineRecognizer? =
        if (releaseWhenIdle && activeSessionCount == 0) {
            recognizer.also { recognizer = null }
        } else {
            null
        }

    private suspend fun releaseRecognizer(target: OnlineRecognizer?) {
        if (target != null) {
            withContext(previewDispatcher) { target.release() }
        }
    }

    private class Session(
        private val recognizer: OnlineRecognizer,
        private val stream: OnlineStream,
        private val powerManager: PowerManager,
        private val dispatcher: CoroutineDispatcher,
        private val onClosed: suspend () -> Unit,
    ) : StreamingTranscriptionSession {
        private var closed = false
        private var lastText = ""

        override suspend fun accept(samples: FloatArray): String =
            withContext(dispatcher) {
                if (closed || samples.isEmpty()) {
                    return@withContext lastText
                }
                stream.acceptWaveform(samples, 16_000)
                if (!shouldThrottlePreview(powerManager)) decodeReady()
                lastText
            }

        override suspend fun finish(): String =
            withContext(dispatcher) {
                if (!closed) {
                    stream.inputFinished()
                    decodeReady()
                }
                lastText
            }

        override suspend fun close() =
            withContext(NonCancellable) {
                var didClose = false
                try {
                    withContext(dispatcher) {
                        if (!closed) {
                            closed = true
                            didClose = true
                            stream.release()
                        }
                    }
                } finally {
                    if (didClose) onClosed()
                }
            }

        private fun decodeReady() {
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream)
            }
            lastText = recognizer.getResult(stream).text.trim()
        }
    }

    companion object {
        private const val TAG = "StreamingSherpa"
        internal const val MODEL_DIR = "streaming-zipformer-en-20m-2023-02-17"
        internal const val ENCODER = "encoder-epoch-99-avg-1.int8.onnx"
        internal const val DECODER = "decoder-epoch-99-avg-1.int8.onnx"
        internal const val JOINER = "joiner-epoch-99-avg-1.int8.onnx"
        internal const val TOKENS = "tokens.txt"

        private val previewDispatcher =
            Executors
                .newSingleThreadExecutor { runnable ->
                    Thread {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                        runnable.run()
                    }.apply { name = "chirp-streaming-preview" }
                }.asCoroutineDispatcher()
    }
}

internal fun shouldThrottlePreview(powerManager: PowerManager): Boolean =
    powerManager.isPowerSaveMode || powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE

internal fun adaptiveOfflineThreadCount(
    availableProcessors: Int,
    lowRamDevice: Boolean,
): Int =
    when {
        lowRamDevice -> availableProcessors.coerceIn(1, 2)
        availableProcessors >= 8 -> 4
        availableProcessors >= 6 -> 3
        else -> availableProcessors.coerceIn(1, 2)
    }

internal fun Context.offlineRecognizerThreadCount(): Int =
    adaptiveOfflineThreadCount(
        availableProcessors = Runtime.getRuntime().availableProcessors(),
        lowRamDevice = getSystemService(ActivityManager::class.java)?.isLowRamDevice == true,
    )

/** Downloads the optional 43.7 MB first-pass model only on an unmetered connection. */
internal class StreamingModelStore(
    private val context: Context,
) {
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

    suspend fun ensureAvailable(): File? =
        withContext(Dispatchers.IO) {
            val dir = File(context.filesDir, "models/${StreamingSherpaRecognizerProvider.MODEL_DIR}")
            if (FILES.all { it.isValidIn(dir) }) return@withContext dir

            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            if (connectivity == null || connectivity.isActiveNetworkMetered) {
                Log.i(TAG, "Streaming preview model absent; waiting for an unmetered connection")
                return@withContext null
            }
            if (!dir.exists() && !dir.mkdirs()) return@withContext null
            val missingFiles = FILES.filterNot { it.isValidIn(dir) }
            val missingBytes = missingFiles.sumOf { it.size }
            if (dir.usableSpace < missingBytes + DOWNLOAD_HEADROOM_BYTES) {
                Log.w(TAG, "Streaming preview model skipped because storage is low")
                return@withContext null
            }

            for (modelFile in missingFiles) {
                if (!download(modelFile, dir)) return@withContext null
            }
            dir.takeIf { target -> FILES.all { it.isValidIn(target) } }
        }

    private fun download(modelFile: StreamingModelFile, dir: File): Boolean {
        val target = File(dir, modelFile.name)
        val temporary = File(dir, "${modelFile.name}.download")
        return try {
            runCatching {
                val request = Request.Builder().url("$BASE_URL/${modelFile.name}").build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return false
                    val body = response.body ?: return false
                    FileOutputStream(temporary, false).use { output ->
                        body.byteStream().use { input -> copyBounded(input, output, modelFile.size) }
                        output.fd.sync()
                    }
                }
                if (!modelFile.isValidFile(temporary)) return false
                replaceDownloadedFile(temporary, target)
                true
            }.getOrElse { failure ->
                if (failure is CancellationException) throw failure
                Log.w(TAG, "Could not prepare streaming preview model file ${modelFile.name}", failure)
                false
            }
        } finally {
            temporary.delete()
        }
    }

    companion object {
        private const val TAG = "StreamingModelStore"
        private const val BASE_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/resolve/main"
        private const val DOWNLOAD_HEADROOM_BYTES = 16L * 1024L * 1024L

        internal val FILES =
            listOf(
                StreamingModelFile(StreamingSherpaRecognizerProvider.ENCODER, 42_845_182L, "3810755ce7c3ab26b42a8bcf39d191308fa27fb0f53358823ba46141d03b7eb3"),
                StreamingModelFile(StreamingSherpaRecognizerProvider.DECODER, 539_499L, "21e2a2acd961b3ac72f55be2f10f1a285e1b0b0ba010d7c0b6eab141411b163c"),
                StreamingModelFile(StreamingSherpaRecognizerProvider.JOINER, 259_572L, "e085d73b593cf9b0707f370dbd656d58327d3fe36d80d849202ef81df02cb01e"),
                StreamingModelFile(StreamingSherpaRecognizerProvider.TOKENS, 5_048L, "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb"),
            )
    }
}

internal fun copyBounded(
    input: InputStream,
    output: FileOutputStream,
    expectedBytes: Long,
) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var copied = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        copied += read
        check(copied <= expectedBytes) { "Streaming model response exceeded its manifest size" }
        output.write(buffer, 0, read)
    }
}

internal fun replaceDownloadedFile(
    temporary: File,
    target: File,
) {
    check(promoteModelCandidateAtomically(temporary, target)) { "Could not activate streaming model candidate" }
}

internal data class StreamingModelFile(
    val name: String,
    val size: Long,
    val sha256: String,
) {
    fun isValidIn(dir: File): Boolean = isValidFile(File(dir, name))

    fun isValidFile(file: File): Boolean {
        if (!file.isFile || file.length() != size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == sha256
    }
}
