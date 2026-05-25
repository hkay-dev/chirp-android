package dev.chirpboard.app

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import dev.chirpboard.app.core.transcription.RecognizedWordTiming
import dev.chirpboard.app.core.transcription.TranscriptionOutcome
import dev.chirpboard.app.download.ModelDownloader
import dev.chirpboard.app.core.audio.recorder.VoiceRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToLong

class SherpaRecognizer(
    private val context: Context,
) {
    companion object {
        private const val TAG = "SherpaRecognizer"
        private const val MODEL_DIR = "parakeet-tdt-0.6b-v2"
    }

    private var recognizer: OfflineRecognizer? = null
    private val mutex = Mutex()

    val isReady: Boolean
        get() = recognizer != null

    suspend fun initialize(): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (recognizer != null) return@withContext true

                // Check persistent storage first, then fallback to legacy internal storage
                val persistentPath = ModelDownloader.ensureModelDir(context)
                val legacyPath = File(context.filesDir, "models/$MODEL_DIR")

                val modelPath =
                    when {
                        File(persistentPath, "encoder.int8.onnx").exists() -> {
                            persistentPath
                        }

                        File(legacyPath, "encoder.int8.onnx").exists() -> {
                            legacyPath
                        }

                        else -> {
                            Log.w(
                                TAG,
                                "Model not found in persistent (${persistentPath.absolutePath}) or legacy (${legacyPath.absolutePath})",
                            )
                            return@withContext false
                        }
                    }
                Log.i(TAG, "Using model from: ${modelPath.absolutePath}")

                val encoder = File(modelPath, "encoder.int8.onnx")
                val decoder = File(modelPath, "decoder.int8.onnx")
                val joiner = File(modelPath, "joiner.int8.onnx")
                val tokens = File(modelPath, "tokens.txt")

                if (!encoder.exists() || !decoder.exists() || !joiner.exists() || !tokens.exists()) {
                    Log.w(TAG, "Model files missing in ${modelPath.absolutePath}")
                    return@withContext false
                }

                try {
                    Log.d(TAG, "Creating transducer config...")
                    val transducerConfig =
                        OfflineTransducerModelConfig(
                            encoder = encoder.absolutePath,
                            decoder = decoder.absolutePath,
                            joiner = joiner.absolutePath,
                        )

                    Log.d(TAG, "Creating model config...")
                    val modelConfig =
                        OfflineModelConfig(
                            transducer = transducerConfig,
                            tokens = tokens.absolutePath,
                            numThreads = 8,
                            debug = false,
                            provider = "cpu",
                            modelType = "nemo_transducer",
                        )

                    Log.d(TAG, "Creating feature config...")
                    val featConfig =
                        FeatureConfig(
                            sampleRate = VoiceRecorder.SAMPLE_RATE,
                            featureDim = 80,
                        )

                    Log.d(TAG, "Creating recognizer config...")
                    val config =
                        OfflineRecognizerConfig(
                            featConfig = featConfig,
                            modelConfig = modelConfig,
                            decodingMethod = "greedy_search",
                        )

                    Log.d(TAG, "Creating OfflineRecognizer (this may take 10-30 seconds)...")
                    recognizer = OfflineRecognizer(assetManager = null, config = config)
                    Log.i(TAG, "Recognizer initialized successfully")
                    true
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "Failed to initialize recognizer", e)
                    false
                }
            }
        }

    suspend fun transcribeOutcome(
        samples: FloatArray,
        sampleRate: Int = VoiceRecorder.SAMPLE_RATE,
    ): TranscriptionOutcome =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val rec =
                    recognizer
                        ?: return@withContext TranscriptionOutcome.ModelUnavailable(
                            "Recognizer is not initialized",
                        )

                try {
                    val stream = rec.createStream()
                    try {
                        stream.acceptWaveform(samples, sampleRate)
                        rec.decode(stream)
                        val result = rec.getResult(stream)
                        val text = result.text.trim()
                        if (text.isBlank()) {
                            TranscriptionOutcome.NoSpeech
                        } else {
                            TranscriptionOutcome.Success(
                                text = text,
                                wordTimings = result.toRecognizedWordTimingsOrNull(text),
                            )
                        }
                    } finally {
                        stream.release()
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "Transcription failed", e)
                    TranscriptionOutcome.EngineError(
                        reason = e.message ?: "Transcription failed",
                        retryable = false,
                    )
                }
            }
        }

    suspend fun transcribe(
        samples: FloatArray,
        sampleRate: Int = VoiceRecorder.SAMPLE_RATE,
    ): String =
        when (val outcome = transcribeOutcome(samples, sampleRate)) {
            is TranscriptionOutcome.Success -> outcome.text
            else -> ""
        }

    suspend fun release() {
        mutex.withLock {
            recognizer?.release()
            recognizer = null
        }
    }
}

private val RECOGNIZER_WHITESPACE_REGEX = "\\s+".toRegex()

private data class RecognizerTokenPiece(
    val text: String,
    val startsNewWord: Boolean,
 )

internal fun OfflineRecognizerResult.toRecognizedWordTimingsOrNull(
    transcriptText: String,
 ): List<RecognizedWordTiming>? {
    if (tokens.isEmpty()) return null
    if (timestamps.size != tokens.size || durations.size != tokens.size) return null

    val expectedWords = transcriptText.split(RECOGNIZER_WHITESPACE_REGEX).filter { it.isNotBlank() }
    if (expectedWords.isEmpty()) return null

    val recognizedWords = mutableListOf<RecognizedWordTiming>()
    var currentText = StringBuilder()
    var currentStartMs = 0L
    var currentEndMs = 0L

    fun flushCurrentWord() {
        if (currentText.isEmpty()) return
        recognizedWords += RecognizedWordTiming(
            text = currentText.toString(),
            startTimestampMs = currentStartMs,
            endTimestampMs = currentEndMs,
        )
        currentText = StringBuilder()
    }

    tokens.forEachIndexed { index, rawToken ->
        val piece = rawToken.toRecognizerTokenPiece()
        val tokenStartMs = timestamps[index].secondsToMillisOrNull() ?: return null
        val tokenDurationMs = durations[index].secondsToMillisOrNull() ?: return null
        val tokenEndMs = tokenStartMs + tokenDurationMs
        if (tokenEndMs < tokenStartMs) return null

        if (piece.startsNewWord && currentText.isNotEmpty()) {
            flushCurrentWord()
        }

        if (piece.text.isBlank()) {
            return@forEachIndexed
        }

        if (currentText.isEmpty()) {
            currentStartMs = tokenStartMs
            currentEndMs = tokenEndMs
        } else {
            currentEndMs = max(currentEndMs, tokenEndMs)
        }

        currentText.append(piece.text)
    }

    flushCurrentWord()

    if (recognizedWords.isEmpty()) return null
    if (recognizedWords.map { it.text } != expectedWords) return null
    if (!recognizedWords.hasTrustedMonotonicTiming()) return null

    return recognizedWords
}

private fun String.toRecognizerTokenPiece(): RecognizerTokenPiece {
    var token = this
    var startsNewWord = false

    if (token.firstOrNull()?.isWhitespace() == true) {
        startsNewWord = true
        token = token.trimStart()
    }

    while (token.startsWith('▁') || token.startsWith('Ġ')) {
        startsNewWord = true
        token = token.drop(1)
    }

    return RecognizerTokenPiece(text = token, startsNewWord = startsNewWord)
}

private fun Float.secondsToMillisOrNull(): Long? {
    if (!isFinite() || this < 0f) return null
    return (this * 1000f).roundToLong()
}

private fun List<RecognizedWordTiming>.hasTrustedMonotonicTiming(): Boolean {
    var previousStartMs = -1L
    var previousEndMs = -1L

    for (timing in this) {
        if (timing.text.isBlank()) return false
        if (timing.startTimestampMs < 0L) return false
        if (timing.endTimestampMs < timing.startTimestampMs) return false
        if (timing.startTimestampMs < previousStartMs) return false
        if (timing.endTimestampMs < previousEndMs) return false

        previousStartMs = timing.startTimestampMs
        previousEndMs = timing.endTimestampMs
    }

    return true
}
