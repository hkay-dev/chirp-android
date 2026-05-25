package dev.chirpboard.app.download

import android.content.Context
import android.os.StatFs
import android.util.Log
import androidx.annotation.VisibleForTesting
import dev.chirpboard.app.core.modelreadiness.ModelReadinessEvaluation
import dev.chirpboard.app.core.modelreadiness.ModelReadinessUnavailableReason
import dev.chirpboard.app.core.modelreadiness.ModelReadinessVerificationSource
import dev.chirpboard.app.core.modelreadiness.SpeechModelDownloadState
import dev.chirpboard.app.core.modelreadiness.SpeechModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ModelDownloader(
    private val context: Context,
    private val modelFiles: List<ModelFile> = MODEL_FILES,
    private val modelDirProvider: (Context) -> File = { ensureModelDir(it) },
    private val legacyModelDirProvider: (Context) -> File = { ctx -> File(ctx.filesDir, "models/$MODEL_DIR") },
) : SpeechModelStore {
    companion object {
        private const val TAG = "ModelDownloader"
        private const val MODEL_DIR = "parakeet-tdt-0.6b-v2"
        private const val BASE_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main"
        internal const val VERIFICATION_PREFS_NAME = "model_verification_cache"

        private const val MIN_STORAGE_BUFFER_BYTES = 50L * 1024L * 1024L

        private val MODEL_FILES =
            listOf(
                ModelFile(
                    name = "encoder.int8.onnx",
                    expectedSize = 652_184_296L,
                    expectedSha256 = "a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab",
                ),
                ModelFile(
                    name = "decoder.int8.onnx",
                    expectedSize = 7_257_753L,
                    expectedSha256 = "b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e",
                ),
                ModelFile(
                    name = "joiner.int8.onnx",
                    expectedSize = 1_739_080L,
                    expectedSha256 = "7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2",
                ),
                ModelFile(
                    name = "tokens.txt",
                    expectedSize = 9_384L,
                    expectedSha256 = "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d",
                ),
            )

        private val processVerificationCache = mutableMapOf<String, VerificationCacheEntry>()
        private val processCacheLock = Any()

        @VisibleForTesting
        internal fun clearProcessVerificationCacheForTest() {
            synchronized(processCacheLock) {
                processVerificationCache.clear()
            }
        }

        /**
         * Get the persistent model directory that survives "Clear Data".
         * Uses Documents/.chirpboard/ in shared storage which requires MANAGE_EXTERNAL_STORAGE
         * on Android 11+, but is not touched by pm clear.
         * Falls back to internal storage if the persistent path is not writable.
         */
        fun ensureModelDir(context: Context): File {
            val docsDir =
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOCUMENTS,
                )
            val persistentDir = File(docsDir, ".chirpboard/models/$MODEL_DIR")

            // Try to create and verify writable
            if (persistentDir.exists() || persistentDir.mkdirs()) {
                return persistentDir
            }

            // If we can't write to Documents, fall back to internal storage
            Log.w(TAG, "Cannot write to persistent path ${persistentDir.absolutePath}, falling back to internal")
            return File(context.filesDir, "models/$MODEL_DIR")
        }
    }

    data class ModelFile(
        val name: String,
        val expectedSize: Long,
        val expectedSha256: String,
    )

    private data class VerificationCacheEntry(
        val size: Long,
        val lastModified: Long,
        val expectedSha256: String,
        val valid: Boolean,
    )

    private enum class FileValidationStatus {
        VALID,
        MISSING,
        INVALID,
    }

    private data class FileValidationResult(
        val status: FileValidationStatus,
        val source: ModelReadinessVerificationSource? = null,
    )

    sealed interface DownloadState {
        data class Progress(
            val file: String,
            val bytesDownloaded: Long,
            val totalBytes: Long,
        ) : DownloadState

        data object Complete : DownloadState

        data class Error(
            val message: String,
        ) : DownloadState
    }

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

    private val verificationPrefs by lazy {
        context.getSharedPreferences(VERIFICATION_PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isModelDownloaded(): Boolean = evaluateModelReadiness().isReady

    internal fun evaluateModelReadiness(): ModelReadinessEvaluation {
        val modelPath = modelDirProvider(context)
        val legacyPath = legacyModelDirProvider(context)

        val sources = linkedSetOf<ModelReadinessVerificationSource>()
        var hasIntegrityMismatch = false
        var hasMissing = false

        modelFiles.forEach { modelFile ->
            val persistentFile = File(modelPath, modelFile.name)
            val legacyFile = File(legacyPath, modelFile.name)

            val persistentResult = validateModelCandidate(persistentFile, modelFile)
            val legacyResult = validateModelCandidate(legacyFile, modelFile)

            val winner =
                when {
                    persistentResult.status == FileValidationStatus.VALID -> persistentResult
                    legacyResult.status == FileValidationStatus.VALID -> legacyResult
                    else -> null
                }

            if (winner != null) {
                winner.source?.let(sources::add)
                Log.d(
                    TAG,
                    "  ${modelFile.name}: valid via ${winner.source} (persistent=${persistentFile.exists()}, legacy=${legacyFile.exists()})",
                )
                return@forEach
            }

            if (
                persistentResult.status == FileValidationStatus.INVALID ||
                legacyResult.status == FileValidationStatus.INVALID
            ) {
                hasIntegrityMismatch = true
            } else {
                hasMissing = true
            }

            Log.d(
                TAG,
                "  ${modelFile.name}: unavailable (persistent=${persistentResult.status}, legacy=${legacyResult.status})",
            )
        }

        if (hasIntegrityMismatch || hasMissing) {
            val reason =
                if (hasIntegrityMismatch) {
                    ModelReadinessUnavailableReason.INTEGRITY_MISMATCH
                } else {
                    ModelReadinessUnavailableReason.MISSING_MODEL_FILES
                }
            Log.d(TAG, "isModelDownloaded = false (reason=$reason)")
            return ModelReadinessEvaluation(
                isReady = false,
                unavailableReason = reason,
            )
        }

        val source =
            when {
                ModelReadinessVerificationSource.CHECKSUM_VERIFICATION in sources -> {
                    ModelReadinessVerificationSource.CHECKSUM_VERIFICATION
                }

                ModelReadinessVerificationSource.PERSISTED_CACHE in sources -> {
                    ModelReadinessVerificationSource.PERSISTED_CACHE
                }

                else -> {
                    ModelReadinessVerificationSource.PROCESS_CACHE
                }
            }

        Log.d(TAG, "isModelDownloaded = true (source=$source)")
        return ModelReadinessEvaluation(
            isReady = true,
            verificationSource = source,
        )
    }

    override suspend fun evaluateReadiness(): ModelReadinessEvaluation = evaluateModelReadiness()

    fun downloadModelLegacy(): Flow<DownloadState> =
        flow {
            val modelPath = modelDirProvider(context)
            modelPath.mkdirs()

            var totalDownloaded = 0L
            val totalSize = modelFiles.sumOf { it.expectedSize }

            val requiredDownloadBytes =
                modelFiles.sumOf { file ->
                    val existing = File(modelPath, file.name)
                    if (isValidDownloadedFile(existing, file)) 0L else file.expectedSize
                }

            val availableBytes = getAvailableBytes(modelPath)
            val requiredWithBuffer = requiredDownloadBytes + MIN_STORAGE_BUFFER_BYTES
            if (!hasSufficientStorage(availableBytes, requiredWithBuffer)) {
                emit(
                    DownloadState.Error(
                        "Insufficient storage. Need about ${requiredWithBuffer / (1024 * 1024)} MB free.",
                    ),
                )
                return@flow
            }

            for (file in modelFiles) {
                val destFile = File(modelPath, file.name)
                if (isValidDownloadedFile(destFile, file)) {
                    totalDownloaded += destFile.length()
                    emit(DownloadState.Progress(file.name, totalDownloaded, totalSize))
                    continue
                }

                if (destFile.exists()) {
                    destFile.delete()
                }

                val tempFile = File(modelPath, "${file.name}.download")
                if (tempFile.exists()) {
                    tempFile.delete()
                }

                val url = "$BASE_URL/${file.name}"
                Log.i(TAG, "Downloading $url")

                try {
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            emit(DownloadState.Error("Failed to download ${file.name}: ${response.code}"))
                            return@flow
                        }

                        val body =
                            response.body ?: run {
                                emit(DownloadState.Error("Empty response for ${file.name}"))
                                return@flow
                            }

                        val downloaded =
                            body.byteStream().use { input ->
                                writeInputStreamToTempFile(input, tempFile) { bytesRead ->
                                    emit(
                                        DownloadState.Progress(
                                            file.name,
                                            totalDownloaded + bytesRead,
                                            totalSize,
                                        ),
                                    )
                                }
                            }

                        if (!validateFileIntegrity(tempFile, file.expectedSize, file.expectedSha256)) {
                            tempFile.delete()
                            emit(DownloadState.Error("Checksum validation failed for ${file.name}"))
                            return@flow
                        }

                        if (!promoteTempFileAtomically(tempFile, destFile)) {
                            tempFile.delete()
                            emit(DownloadState.Error("Failed to finalize ${file.name}"))
                            return@flow
                        }

                        cacheValidationResult(destFile, file, valid = true)

                        totalDownloaded += downloaded
                        Log.i(TAG, "Downloaded ${file.name}: $downloaded bytes")
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "Download failed for ${file.name}", e)
                    emit(DownloadState.Error("Download failed: ${e.message}"))
                    return@flow
                } finally {
                    if (tempFile.exists()) tempFile.delete()
                }
            }

            emit(DownloadState.Complete)
        }.flowOn(Dispatchers.IO)

    override fun downloadModel(): Flow<SpeechModelDownloadState> =
        downloadModelLegacy().map { state ->
            when (state) {
                is DownloadState.Progress ->
                    SpeechModelDownloadState.Progress(
                        file = state.file,
                        progress =
                            if (state.totalBytes > 0) {
                                state.bytesDownloaded.toFloat() / state.totalBytes.toFloat()
                            } else {
                                0f
                            },
                    )

                DownloadState.Complete -> SpeechModelDownloadState.Complete
                is DownloadState.Error -> SpeechModelDownloadState.Error(state.message)
            }
        }

    override suspend fun deleteModel(): Boolean =
        withContext(Dispatchers.IO) {
            var success = true
            val modelPath = modelDirProvider(context)
            if (modelPath.exists()) {
                success = modelPath.deleteRecursively() && success
            }
            val legacyPath = legacyModelDirProvider(context)
            if (legacyPath.exists()) {
                success = legacyPath.deleteRecursively() && success
            }
            invalidateVerificationCache()
            success
        }

    override suspend fun getDownloadedSize(): Long =
        withContext(Dispatchers.IO) {
            val modelPath = modelDirProvider(context)
            val legacyPath = legacyModelDirProvider(context)
            modelFiles.sumOf { file ->
                val persistent = File(modelPath, file.name)
                val legacy = File(legacyPath, file.name)
                when {
                    persistent.exists() -> persistent.length()
                    legacy.exists() -> legacy.length()
                    else -> 0L
                }
            }
        }

    override fun invalidateVerificationCache() {
        clearProcessVerificationCacheForTest()
        verificationPrefs.edit().clear().apply()
    }

    private fun isValidDownloadedFile(
        file: File,
        modelFile: ModelFile,
    ): Boolean = validateModelCandidate(file, modelFile).status == FileValidationStatus.VALID

    private fun validateModelCandidate(
        file: File,
        modelFile: ModelFile,
    ): FileValidationResult {
        if (!file.exists()) {
            clearCacheEntry(file.absolutePath)
            return FileValidationResult(FileValidationStatus.MISSING)
        }

        val fileSize = file.length()
        val fileLastModified = file.lastModified()

        val processEntry =
            synchronized(processCacheLock) {
                processVerificationCache[file.absolutePath]
            }
        if (
            processEntry != null &&
            !isCacheEntryUsable(
                entry = processEntry,
                size = fileSize,
                lastModified = fileLastModified,
                expectedSha256 = modelFile.expectedSha256,
            )
        ) {
            synchronized(processCacheLock) {
                processVerificationCache.remove(file.absolutePath)
            }
        }

        if (
            processEntry != null &&
            isCacheEntryUsable(
                entry = processEntry,
                size = fileSize,
                lastModified = fileLastModified,
                expectedSha256 = modelFile.expectedSha256,
            )
        ) {
            return FileValidationResult(
                status = if (processEntry.valid) FileValidationStatus.VALID else FileValidationStatus.INVALID,
                source = ModelReadinessVerificationSource.PROCESS_CACHE,
            )
        }

        val persistentEntry = readPersistentCacheEntry(file.absolutePath)
        if (
            persistentEntry != null &&
            isCacheEntryUsable(
                entry = persistentEntry,
                size = fileSize,
                lastModified = fileLastModified,
                expectedSha256 = modelFile.expectedSha256,
            )
        ) {
            synchronized(processCacheLock) {
                processVerificationCache[file.absolutePath] = persistentEntry
            }
            return FileValidationResult(
                status = if (persistentEntry.valid) FileValidationStatus.VALID else FileValidationStatus.INVALID,
                source = ModelReadinessVerificationSource.PERSISTED_CACHE,
            )
        }
        if (persistentEntry != null) {
            clearCacheEntry(file.absolutePath)
        }

        val valid = validateFileIntegrity(file, modelFile.expectedSize, modelFile.expectedSha256)
        cacheValidationResult(file, modelFile, valid)
        return FileValidationResult(
            status = if (valid) FileValidationStatus.VALID else FileValidationStatus.INVALID,
            source = ModelReadinessVerificationSource.CHECKSUM_VERIFICATION,
        )
    }

    private fun cacheValidationResult(
        file: File,
        modelFile: ModelFile,
        valid: Boolean,
    ) {
        val cacheEntry =
            VerificationCacheEntry(
                size = file.length(),
                lastModified = file.lastModified(),
                expectedSha256 = modelFile.expectedSha256,
                valid = valid,
            )

        synchronized(processCacheLock) {
            processVerificationCache[file.absolutePath] = cacheEntry
        }
        writePersistentCacheEntry(file.absolutePath, cacheEntry)
    }

    private fun isCacheEntryUsable(
        entry: VerificationCacheEntry,
        size: Long,
        lastModified: Long,
        expectedSha256: String,
    ): Boolean =
        entry.size == size &&
            entry.lastModified == lastModified &&
            entry.expectedSha256 == expectedSha256

    private fun readPersistentCacheEntry(filePath: String): VerificationCacheEntry? {
        val prefix = cacheKeyPrefix(filePath)
        val validKey = "$prefix:valid"
        if (!verificationPrefs.contains(validKey)) {
            return null
        }

        return VerificationCacheEntry(
            size = verificationPrefs.getLong("$prefix:size", -1L),
            lastModified = verificationPrefs.getLong("$prefix:lastModified", -1L),
            expectedSha256 = verificationPrefs.getString("$prefix:expectedSha256", null).orEmpty(),
            valid = verificationPrefs.getBoolean(validKey, false),
        )
    }

    private fun writePersistentCacheEntry(
        filePath: String,
        entry: VerificationCacheEntry,
    ) {
        val prefix = cacheKeyPrefix(filePath)
        verificationPrefs
            .edit()
            .putLong("$prefix:size", entry.size)
            .putLong("$prefix:lastModified", entry.lastModified)
            .putString("$prefix:expectedSha256", entry.expectedSha256)
            .putBoolean("$prefix:valid", entry.valid)
            .apply()
    }

    private fun clearCacheEntry(filePath: String) {
        synchronized(processCacheLock) {
            processVerificationCache.remove(filePath)
        }

        val prefix = cacheKeyPrefix(filePath)
        verificationPrefs
            .edit()
            .remove("$prefix:size")
            .remove("$prefix:lastModified")
            .remove("$prefix:expectedSha256")
            .remove("$prefix:valid")
            .apply()
    }

    private fun cacheKeyPrefix(filePath: String): String {
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(filePath.toByteArray())
                .joinToString("") { "%02x".format(it) }
        return "verification:$digest"
    }

    private fun getAvailableBytes(path: File): Long =
        try {
            val target = if (path.exists()) path else path.parentFile ?: path
            StatFs(target.absolutePath).availableBytes
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Failed to read free storage for ${path.absolutePath}", e)
            0L
        }
}

internal fun hasSufficientStorage(
    availableBytes: Long,
    requiredBytes: Long,
): Boolean = availableBytes >= requiredBytes

internal fun validateFileIntegrity(
    file: File,
    expectedSize: Long,
    expectedSha256: String,
): Boolean {
    if (!file.exists()) return false
    if (file.length() != expectedSize) return false
    return computeSha256(file) == expectedSha256
}

internal suspend fun writeInputStreamToTempFile(
    input: InputStream,
    tempFile: File,
    onTotalBytesWritten: suspend (Long) -> Unit,
): Long {
    var downloaded = 0L

    try {
        FileOutputStream(tempFile).use { output ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
                downloaded += read
                onTotalBytesWritten(downloaded)
            }
        }
        return downloaded
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        tempFile.delete()
        throw e
    }
}

internal fun promoteTempFileAtomically(
    tempFile: File,
    destinationFile: File,
): Boolean {
    return try {
        Files.move(
            tempFile.toPath(),
            destinationFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
        true
    } catch (_: Exception) {
        if (destinationFile.exists() && !destinationFile.delete()) {
            return false
        }
        tempFile.renameTo(destinationFile)
    }
}

internal fun computeSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
