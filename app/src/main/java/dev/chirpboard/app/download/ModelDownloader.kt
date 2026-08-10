package dev.chirpboard.app.download

import android.content.Context
import android.os.StatFs
import android.util.Log
import androidx.annotation.VisibleForTesting
import dev.chirpboard.app.core.modelreadiness.ModelReadinessEvaluation
import dev.chirpboard.app.core.modelreadiness.ModelReadinessUnavailableReason
import dev.chirpboard.app.core.modelreadiness.ModelReadinessVerificationSource
import dev.chirpboard.app.core.modelreadiness.SpeechModelStore
import dev.chirpboard.app.core.transcription.LocalSpeechModelId
import dev.chirpboard.app.core.transcription.LocalSpeechModelSelectionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ModelDownloader(
    private val context: Context,
    private val modelSelectionStore: LocalSpeechModelSelectionStore? = null,
    private val modelFiles: List<ModelFile>? = null,
    private val modelDirProvider: ((Context) -> File)? = null,
    private val legacyModelDirProvider: ((Context) -> File)? = null,
    private val baseUrl: String? = null,
    private val availableBytesProvider: (File) -> Long = ::defaultAvailableBytes,
) : SpeechModelStore {
    companion object {
        private const val TAG = "ModelDownloader"
        private const val MODEL_DIR = "parakeet-tdt-0.6b-v2"
        private const val BASE_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main"
        internal const val GGUF_MODEL_DIR = "parakeet-tdt-ctc-110m-q8"
        internal const val GGUF_MODEL_FILE = "parakeet-tdt_ctc-110m-Q8_0.gguf"
        internal const val GGUF_Q6_MODEL_DIR = "parakeet-tdt-ctc-110m-q6-k"
        internal const val GGUF_Q6_MODEL_FILE = "parakeet-tdt_ctc-110m-Q6_K.gguf"
        internal const val GGUF_Q4_MODEL_DIR = "parakeet-tdt-ctc-110m-q4-k-m"
        internal const val GGUF_Q4_MODEL_FILE = "parakeet-tdt_ctc-110m-Q4_K_M.gguf"
        private const val GGUF_BASE_URL =
            "https://huggingface.co/handy-computer/parakeet-tdt_ctc-110m-gguf/resolve/" +
                "9d66d34f9e1594075c5dd72c90c0f4c321b29f21"
        internal const val VERIFICATION_PREFS_NAME = "model_verification_cache"

        private const val MIN_STORAGE_BUFFER_BYTES = 50L * 1024L * 1024L

        internal const val TEMP_FILE_SUFFIX = ".download"
        internal const val ETAG_FILE_SUFFIX = ".download.etag"

        private const val HTTP_PARTIAL_CONTENT = 206
        private const val HTTP_REQUEST_TIMEOUT = 408
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416
        private const val HTTP_INTERNAL_SERVER_ERROR = 500

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
        private val GGUF_MODEL_FILES =
            listOf(
                ModelFile(
                    name = GGUF_MODEL_FILE,
                    expectedSize = 135_373_280L,
                    expectedSha256 = "7dd44c74a331d788a4e5f8b16913b3feb29ced22cf5613aad0e0f6cd30516296",
                ),
            )
        private val GGUF_Q6_MODEL_FILES =
            listOf(
                ModelFile(
                    name = GGUF_Q6_MODEL_FILE,
                    expectedSize = 112_311_264L,
                    expectedSha256 = "c20520c245adf82e5166005f599cb3b95e7cf5192117e845be4bbcd39226d483",
                ),
            )
        private val GGUF_Q4_MODEL_FILES =
            listOf(
                ModelFile(
                    name = GGUF_Q4_MODEL_FILE,
                    expectedSize = 89_989_600L,
                    expectedSha256 = "486414fd90185a8c8a4ced7c123cfb133ff4f7958426c6b8bd9049946b56b448",
                ),
            )
        internal val REQUIRED_MODEL_FILE_NAMES = MODEL_FILES.map { it.name }

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

        private fun ensureModelDir(
            context: Context,
            directoryName: String,
        ): File {
            val docsDir =
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOCUMENTS,
                )
            val persistentDir = File(docsDir, ".chirpboard/models/$directoryName")
            if (persistentDir.exists() || persistentDir.mkdirs()) return persistentDir
            return internalModelDir(context, directoryName)
        }

        private fun internalModelDir(
            context: Context,
            directoryName: String,
        ): File = File(context.filesDir, "models/$directoryName")

        internal fun hasCompleteModelDirectory(path: File): Boolean =
            REQUIRED_MODEL_FILE_NAMES.all { name -> File(path, name).exists() }
    }

    data class ModelFile(
        val name: String,
        val expectedSize: Long,
        val expectedSha256: String,
    )

    private data class ModelSpec(
        val directoryName: String,
        val files: List<ModelFile>,
        val baseUrl: String,
    )

    private fun selectedModelId(): LocalSpeechModelId =
        modelSelectionStore?.selectedModel?.value ?: LocalSpeechModelId.DEFAULT

    private fun modelSpec(modelId: LocalSpeechModelId = selectedModelId()): ModelSpec =
        when (modelId) {
            LocalSpeechModelId.PARAKEET_TDT_600M ->
                ModelSpec(
                    directoryName = MODEL_DIR,
                    files = MODEL_FILES,
                    baseUrl = BASE_URL,
                )

            LocalSpeechModelId.PARAKEET_CTC_110M_Q8 ->
                ModelSpec(
                    directoryName = GGUF_MODEL_DIR,
                    files = GGUF_MODEL_FILES,
                    baseUrl = GGUF_BASE_URL,
                )

            LocalSpeechModelId.PARAKEET_TDT_110M_Q6_K ->
                ModelSpec(
                    directoryName = GGUF_Q6_MODEL_DIR,
                    files = GGUF_Q6_MODEL_FILES,
                    baseUrl = GGUF_BASE_URL,
                )

            LocalSpeechModelId.PARAKEET_TDT_110M_Q4_K_M ->
                ModelSpec(
                    directoryName = GGUF_Q4_MODEL_DIR,
                    files = GGUF_Q4_MODEL_FILES,
                    baseUrl = GGUF_BASE_URL,
                )
        }

    private fun activeModelFiles(): List<ModelFile> = modelFiles ?: modelSpec().files

    internal fun modelDirectory(
        modelId: LocalSpeechModelId,
        preferInternalStorage: Boolean = false,
    ): File {
        if (modelId == selectedModelId()) {
            val override = if (preferInternalStorage) legacyModelDirProvider else modelDirProvider
            if (override != null) return override(context)
        }
        val directoryName = modelSpec(modelId).directoryName
        return if (preferInternalStorage) {
            internalModelDir(context, directoryName)
        } else {
            ensureModelDir(context, directoryName)
        }
    }

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
        UNREADABLE,
    }

    private data class FileValidationResult(
        val status: FileValidationStatus,
        val source: ModelReadinessVerificationSource? = null,
    )

    private data class DirectoryValidationResult(
        val allValid: Boolean,
        val hasInvalid: Boolean,
        val hasMissing: Boolean,
        val hasUnreadable: Boolean,
        val sources: Set<ModelReadinessVerificationSource>,
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
            /**
             * Whether the failure is plausibly transient (network drop, server 5xx). The
             * download worker auto-retries retryable errors with bounded exponential
             * backoff (ERR-3); non-retryable errors fail terminally and require an
             * explicit user retry.
             */
            val retryable: Boolean = false,
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

    fun isModelDownloaded(modelId: LocalSpeechModelId): Boolean = evaluateModelReadiness(modelId).isReady

    internal fun resolvedGgufModelFile(
        modelId: LocalSpeechModelId = LocalSpeechModelId.PARAKEET_CTC_110M_Q8,
    ): File? {
        val spec = modelSpec(modelId)
        val ggufFile = spec.files.singleOrNull() ?: return null
        val persistent = File(modelDirectory(modelId), ggufFile.name)
        if (persistent.exists()) return persistent
        val internal =
            File(
                modelDirectory(modelId, preferInternalStorage = true),
                ggufFile.name,
            )
        return internal.takeIf(File::exists)
    }

    internal fun evaluateModelReadiness(modelId: LocalSpeechModelId = selectedModelId()): ModelReadinessEvaluation {
        val files = modelFiles ?: modelSpec(modelId).files
        val modelPath = modelDirectory(modelId)
        val legacyPath = modelDirectory(modelId, preferInternalStorage = true)

        val persistentResult = validateModelDirectory(modelPath, sourceLabel = "persistent", files = files)
        if (persistentResult.allValid) {
            return readyEvaluation(persistentResult.sources, sourceLabel = "persistent")
        }

        val legacyResult = validateModelDirectory(legacyPath, sourceLabel = "legacy", files = files)
        if (legacyResult.allValid) {
            return readyEvaluation(legacyResult.sources, sourceLabel = "legacy")
        }

        val reason =
            when {
                persistentResult.hasUnreadable || legacyResult.hasUnreadable ->
                    ModelReadinessUnavailableReason.STORAGE_ACCESS_DENIED

                persistentResult.hasInvalid || legacyResult.hasInvalid ->
                    ModelReadinessUnavailableReason.INTEGRITY_MISMATCH

                else -> ModelReadinessUnavailableReason.MISSING_MODEL_FILES
            }
        Log.d(TAG, "isModelDownloaded = false (reason=$reason)")
        return ModelReadinessEvaluation(
            isReady = false,
            unavailableReason = reason,
        )
    }

    private fun readyEvaluation(
        sources: Set<ModelReadinessVerificationSource>,
        sourceLabel: String,
    ): ModelReadinessEvaluation {
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

        Log.d(TAG, "isModelDownloaded = true (directory=$sourceLabel, source=$source)")
        return ModelReadinessEvaluation(
            isReady = true,
            verificationSource = source,
        )
    }

    private fun validateModelDirectory(
        path: File,
        sourceLabel: String,
        files: List<ModelFile> = activeModelFiles(),
    ): DirectoryValidationResult {
        val sources = linkedSetOf<ModelReadinessVerificationSource>()
        var hasInvalid = false
        var hasMissing = false
        var hasUnreadable = false

        files.forEach { modelFile ->
            val file = File(path, modelFile.name)
            val result = validateModelCandidate(file, modelFile)

            when (result.status) {
                FileValidationStatus.VALID -> {
                    result.source?.let(sources::add)
                    Log.d(TAG, "  ${modelFile.name}: valid via ${result.source} ($sourceLabel=${file.exists()})")
                }

                FileValidationStatus.INVALID -> {
                    hasInvalid = true
                    Log.d(TAG, "  ${modelFile.name}: invalid ($sourceLabel=${file.exists()})")
                }

                FileValidationStatus.MISSING -> {
                    hasMissing = true
                    Log.d(TAG, "  ${modelFile.name}: missing ($sourceLabel=false)")
                }

                FileValidationStatus.UNREADABLE -> {
                    hasUnreadable = true
                    Log.w(TAG, "  ${modelFile.name}: exists but unreadable ($sourceLabel) — storage access denied")
                }
            }
        }

        return DirectoryValidationResult(
            allValid = !hasInvalid && !hasMissing && !hasUnreadable,
            hasInvalid = hasInvalid,
            hasMissing = hasMissing,
            hasUnreadable = hasUnreadable,
            sources = sources,
        )
    }

    override suspend fun evaluateReadiness(): ModelReadinessEvaluation = evaluateModelReadiness()

    override suspend fun evaluateReadiness(modelId: LocalSpeechModelId): ModelReadinessEvaluation =
        evaluateModelReadiness(modelId)

    /**
     * Streams the model files into the target directory, resuming partial downloads via
     * HTTP Range requests (ERR-2). Partial `.download` temp files are deliberately KEPT on
     * failure and cancellation (ERR-1) so a later attempt — WorkManager retry, process
     * restart, manual retry — continues from the last byte written; a `.download.etag`
     * sidecar pins the server entity the partial belongs to and is sent as `If-Range` so a
     * changed remote file restarts cleanly instead of producing a corrupt splice. The
     * SHA-256 + exact-size verification chain is unchanged: every file is fully validated
     * before being promoted into place, and a failed validation discards the temp file.
     *
     * @param preferInternalStorage download into app-private storage instead of the shared
     * Documents location, for users who decline the All-files-access permission (PLT-07).
     */
    fun downloadModelFlow(
        modelId: LocalSpeechModelId = selectedModelId(),
        preferInternalStorage: Boolean = false,
    ): Flow<DownloadState> =
        flow {
            val spec = modelSpec(modelId)
            val currentModelFiles = modelFiles ?: spec.files
            val modelPath = modelDirectory(modelId, preferInternalStorage)
            modelPath.mkdirs()
            val downloadBaseUrl = baseUrl ?: spec.baseUrl

            val unreadable =
                currentModelFiles.filter { file ->
                    validateModelCandidate(File(modelPath, file.name), file).status == FileValidationStatus.UNREADABLE
                }
            if (unreadable.isNotEmpty()) {
                Log.w(TAG, "Download blocked: ${unreadable.size} model file(s) exist but are unreadable in $modelPath")
                emit(
                    DownloadState.Error(
                        "Model files from a previous install were found but this install cannot access them. " +
                            "Allow \"All files access\" for Chirp in system settings to reuse the existing model, " +
                            "then try again.",
                        retryable = false,
                    ),
                )
                return@flow
            }

            var totalDownloaded = 0L
            val totalSize = currentModelFiles.sumOf { it.expectedSize }

            val requiredDownloadBytes =
                currentModelFiles.sumOf { file ->
                    val existing = File(modelPath, file.name)
                    if (isValidDownloadedFile(existing, file)) 0L else file.expectedSize - resumableBytes(modelPath, file)
                }

            val availableBytes = availableBytesProvider(modelPath)
            val requiredWithBuffer = requiredDownloadBytes + MIN_STORAGE_BUFFER_BYTES
            if (!hasSufficientStorage(availableBytes, requiredWithBuffer)) {
                emit(
                    DownloadState.Error(
                        "Insufficient storage. Need about ${requiredWithBuffer / (1024 * 1024)} MB free.",
                        retryable = false,
                    ),
                )
                return@flow
            }

            for (file in currentModelFiles) {
                val destFile = File(modelPath, file.name)
                if (isValidDownloadedFile(destFile, file)) {
                    totalDownloaded += file.expectedSize
                    emit(DownloadState.Progress(file.name, totalDownloaded, totalSize))
                    continue
                }

                // A superseded destFile (e.g. the spec's checksum changed in an app update) is
                // deliberately left in place: promoteModelCandidateAtomically renames it to
                // .last-working so a failed native init can roll back to it. Deleting it here
                // made that rollback machinery unreachable for these downloads.
                if (!downloadSingleFile(file, modelPath, downloadBaseUrl, totalDownloaded, totalSize)) {
                    return@flow
                }
                totalDownloaded += file.expectedSize
            }

            emit(DownloadState.Complete)
        }.flowOn(Dispatchers.IO)

    /**
     * Downloads (or resumes) one model file. Returns true when the file is validated and
     * promoted into place; emits a terminal [DownloadState.Error] and returns false otherwise.
     */
    private suspend fun FlowCollector<DownloadState>.downloadSingleFile(
        file: ModelFile,
        modelPath: File,
        downloadBaseUrl: String,
        totalDownloadedBefore: Long,
        totalSize: Long,
    ): Boolean {
        val destFile = File(modelPath, file.name)
        val tempFile = File(modelPath, "${file.name}$TEMP_FILE_SUFFIX")
        val etagFile = File(modelPath, "${file.name}$ETAG_FILE_SUFFIX")

        var plan = resolveResumePlan(tempFile, etagFile, file.expectedSize)
        if (plan is ResumePlan.PromoteCompleted) {
            // Process died (or promote failed) after the full file landed: finish without network.
            if (validateFileIntegrity(tempFile, file.expectedSize, file.expectedSha256) &&
                promoteModelCandidateAtomically(tempFile, destFile)
            ) {
                etagFile.delete()
                cacheValidationResult(destFile, file, valid = true)
                emit(DownloadState.Progress(file.name, totalDownloadedBefore + file.expectedSize, totalSize))
                Log.i(TAG, "Promoted previously completed temp file for ${file.name}")
                return true
            }
            discardPartialDownload(tempFile, etagFile)
            plan = ResumePlan.Fresh
        }
        if (plan is ResumePlan.Fresh) {
            discardPartialDownload(tempFile, etagFile)
        }

        val url = "$downloadBaseUrl/${file.name}"
        val resumePlan = plan as? ResumePlan.Resume
        Log.i(TAG, "Downloading $url (resumeFrom=${resumePlan?.offset ?: 0L})")

        try {
            val requestBuilder = Request.Builder().url(url)
            if (resumePlan != null) {
                requestBuilder.header("Range", "bytes=${resumePlan.offset}-")
                requestBuilder.header("If-Range", resumePlan.etag)
            }
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (response.code == HTTP_RANGE_NOT_SATISFIABLE) {
                    // Stale/oversized partial the server refuses to extend: restart cleanly.
                    discardPartialDownload(tempFile, etagFile)
                    emit(
                        DownloadState.Error(
                            "The download could not resume and will restart. Try again.",
                            retryable = true,
                        ),
                    )
                    return false
                }
                if (!response.isSuccessful) {
                    emit(
                        DownloadState.Error(
                            "The download server returned an error (${response.code}). Try again later.",
                            retryable =
                                response.code >= HTTP_INTERNAL_SERVER_ERROR ||
                                    response.code == HTTP_TOO_MANY_REQUESTS ||
                                    response.code == HTTP_REQUEST_TIMEOUT,
                        ),
                    )
                    return false
                }

                val resuming = resumePlan != null && response.code == HTTP_PARTIAL_CONTENT
                if (!resuming) {
                    // Full entity: either a fresh start, or the If-Range validator no longer
                    // matched (server sent 200) — restart from byte zero against the new entity.
                    discardPartialDownload(tempFile, etagFile)
                    writeEtagSidecar(etagFile, response.header("ETag"))
                }
                val startOffset = if (resuming) resumePlan?.offset ?: 0L else 0L

                val body =
                    response.body ?: run {
                        emit(DownloadState.Error("Empty response for ${file.name}", retryable = true))
                        return false
                    }

                val downloaded =
                    body.byteStream().use { input ->
                        writeInputStreamToTempFile(input, tempFile, append = resuming) { bytesRead ->
                            emit(
                                DownloadState.Progress(
                                    file.name,
                                    totalDownloadedBefore + startOffset + bytesRead,
                                    totalSize,
                                ),
                            )
                        }
                    }

                if (!validateFileIntegrity(tempFile, file.expectedSize, file.expectedSha256)) {
                    discardPartialDownload(tempFile, etagFile)
                    emit(
                        DownloadState.Error(
                            "Checksum validation failed for ${file.name}. Try the download again.",
                            retryable = false,
                        ),
                    )
                    return false
                }

                if (!promoteModelCandidateAtomically(tempFile, destFile)) {
                    emit(DownloadState.Error("Failed to finalize ${file.name}", retryable = true))
                    return false
                }

                etagFile.delete()
                cacheValidationResult(destFile, file, valid = true)
                Log.i(TAG, "Downloaded ${file.name}: $downloaded bytes (resumedFrom=$startOffset)")
            }
        } catch (e: Exception) {
            // Cancellation must propagate; the partial temp file is intentionally kept so
            // the next attempt resumes instead of restarting the 652MB transfer (ERR-1/2).
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Download failed for ${file.name}", e)
            emit(classifyDownloadError(e))
            return false
        }
        return true
    }

    private fun resumableBytes(
        modelPath: File,
        file: ModelFile,
    ): Long {
        val tempFile = File(modelPath, "${file.name}$TEMP_FILE_SUFFIX")
        val etagFile = File(modelPath, "${file.name}$ETAG_FILE_SUFFIX")
        return when (val plan = resolveResumePlan(tempFile, etagFile, file.expectedSize)) {
            is ResumePlan.Resume -> plan.offset
            is ResumePlan.PromoteCompleted -> file.expectedSize
            ResumePlan.Fresh -> 0L
        }
    }

    override suspend fun deleteModel(): Boolean =
        deleteModel(selectedModelId())

    override suspend fun deleteModel(modelId: LocalSpeechModelId): Boolean =
        withContext(Dispatchers.IO) {
            var success = true
            val modelPath = modelDirectory(modelId)
            if (modelPath.exists()) {
                success = modelPath.deleteRecursively() && success
            }
            val legacyPath = modelDirectory(modelId, preferInternalStorage = true)
            if (legacyPath.exists()) {
                success = legacyPath.deleteRecursively() && success
            }
            // Scrub only this model's entries: a blanket clear would force the other models'
            // next readiness check into a full multi-hundred-MB re-hash for no reason.
            clearVerificationCacheFor(modelId)
            success
        }

    private fun clearVerificationCacheFor(modelId: LocalSpeechModelId) {
        val files = modelFiles ?: modelSpec(modelId).files
        val directories =
            listOf(modelDirectory(modelId), modelDirectory(modelId, preferInternalStorage = true))
        for (directory in directories) {
            files.forEach { file -> clearCacheEntry(File(directory, file.name).absolutePath) }
        }
    }

    override suspend fun getDownloadedSize(): Long =
        getDownloadedSize(selectedModelId())

    override suspend fun getDownloadedSize(modelId: LocalSpeechModelId): Long =
        withContext(Dispatchers.IO) {
            val modelPath = modelDirectory(modelId)
            val legacyPath = modelDirectory(modelId, preferInternalStorage = true)
            val files = modelFiles ?: modelSpec(modelId).files
            files.sumOf { file ->
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

        // A file left behind by a previous install stats fine but cannot be opened
        // (scoped-storage ownership does not survive reinstall without All-files access).
        if (!file.canRead()) {
            return FileValidationResult(FileValidationStatus.UNREADABLE)
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

    private fun discardPartialDownload(
        tempFile: File,
        etagFile: File,
    ) {
        if (tempFile.exists()) tempFile.delete()
        if (etagFile.exists()) etagFile.delete()
    }

    private fun writeEtagSidecar(
        etagFile: File,
        etag: String?,
    ) {
        // Weak validators (W/"...") must not be used with If-Range; without a strong ETag
        // the partial cannot be safely resumed, so no sidecar is written and a later
        // attempt restarts from byte zero.
        val strongEtag = etag?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("W/") } ?: return
        try {
            etagFile.writeText(strongEtag)
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Could not persist resume validator for ${etagFile.name}", e)
        }
    }
}

internal sealed interface ResumePlan {
    /** No usable partial: start (or restart) the transfer from byte zero. */
    data object Fresh : ResumePlan

    /** Resume the partial from [offset] with `If-Range: [etag]`. */
    data class Resume(
        val offset: Long,
        val etag: String,
    ) : ResumePlan

    /** The temp file is already complete: validate and promote without network. */
    data object PromoteCompleted : ResumePlan
}

internal fun resolveResumePlan(
    tempFile: File,
    etagFile: File,
    expectedSize: Long,
): ResumePlan {
    if (!tempFile.exists()) return ResumePlan.Fresh
    val length = tempFile.length()
    if (length == expectedSize) return ResumePlan.PromoteCompleted
    val etag = readEtagSidecar(etagFile)
    return if (length in 1 until expectedSize && etag != null) {
        ResumePlan.Resume(offset = length, etag = etag)
    } else {
        ResumePlan.Fresh
    }
}

internal fun readEtagSidecar(etagFile: File): String? =
    try {
        if (etagFile.exists()) {
            etagFile.readText().trim().takeIf { it.isNotEmpty() && !it.startsWith("W/") }
        } else {
            null
        }
    } catch (e: java.io.IOException) {
        Log.w("ModelDownloader", "Cannot read resume validator ${etagFile.absolutePath}", e)
        null
    }

/**
 * Maps download exceptions to user-facing copy (ERR-4): connectivity failures get an
 * actionable hint instead of raw "Unable to resolve host…" text, and disk-full gets the
 * storage message. Raw details stay in the log only.
 */
internal fun classifyDownloadError(error: Exception): ModelDownloader.DownloadState.Error {
    val isConnectivity =
        error is java.net.UnknownHostException ||
            error is java.net.ConnectException ||
            error is java.net.SocketTimeoutException ||
            error is java.net.SocketException ||
            error is javax.net.ssl.SSLException
    val isDiskFull = error is java.io.IOException && error.message?.contains("ENOSPC") == true
    return when {
        isConnectivity ->
            ModelDownloader.DownloadState.Error(
                "No internet connection. Check your network and try again.",
                retryable = true,
            )

        isDiskFull ->
            ModelDownloader.DownloadState.Error(
                "Not enough storage to finish the download. Free up space and try again.",
                retryable = false,
            )

        error is java.io.IOException ->
            ModelDownloader.DownloadState.Error(
                "The download was interrupted. Retrying will resume where it left off.",
                retryable = true,
            )

        else ->
            // I18N-05: never interpolate raw exception text into user copy; callers log it.
            ModelDownloader.DownloadState.Error(
                "The download failed. Try again.",
                retryable = false,
            )
    }
}

private fun defaultAvailableBytes(path: File): Long =
    try {
        val target = if (path.exists()) path else path.parentFile ?: path
        StatFs(target.absolutePath).availableBytes
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.w("ModelDownloader", "Failed to read free storage for ${path.absolutePath}", e)
        0L
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
    return try {
        computeSha256(file) == expectedSha256
    } catch (e: java.io.IOException) {
        Log.w("ModelDownloader", "Cannot read ${file.absolutePath} for integrity check", e)
        false
    } catch (e: SecurityException) {
        Log.w("ModelDownloader", "Access denied reading ${file.absolutePath} for integrity check", e)
        false
    }
}

/**
 * Streams [input] into [tempFile], optionally appending to an existing partial (HTTP Range
 * resume). The temp file is intentionally KEPT when the stream fails or the coroutine is
 * cancelled: everything written so far is valid entity bytes, so a later attempt resumes
 * from `tempFile.length()` instead of re-downloading (ERR-1/ERR-2).
 */
internal suspend fun writeInputStreamToTempFile(
    input: InputStream,
    tempFile: File,
    append: Boolean = false,
    onTotalBytesWritten: suspend (Long) -> Unit,
): Long {
    var downloaded = 0L

    FileOutputStream(tempFile, append).use { output ->
        val buffer = ByteArray(8192)
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            output.write(buffer, 0, read)
            downloaded += read
            onTotalBytesWritten(downloaded)
        }
        // Force the bytes to disk before the caller checksums the file and records the
        // size+mtime verification cache entry: without this, power loss after "verified"
        // could leave a corrupt file that the cache keeps reporting as valid.
        output.fd.sync()
    }
    return downloaded
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
    } catch (_: AtomicMoveNotSupportedException) {
        try {
            Files.move(
                tempFile.toPath(),
                destinationFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            true
        } catch (_: Exception) {
            return false
        }
    } catch (_: Exception) {
        false
    }
}

internal const val LAST_WORKING_MODEL_SUFFIX = ".last-working"
internal const val MODEL_ROLLBACK_MARKER = ".rollback-in-progress"
private const val MODEL_ROLLBACK_TEMP_SUFFIX = ".rollback-copy"

/** Keeps the prior artifact beside a candidate until native recognizer initialization confirms it. */
internal fun promoteModelCandidateAtomically(
    candidateFile: File,
    destinationFile: File,
): Boolean {
    val modelDirectory = destinationFile.parentFile ?: return false
    if (!recoverInterruptedModelRollback(modelDirectory)) return false
    val backup = File(modelDirectory, "${destinationFile.name}$LAST_WORKING_MODEL_SUFFIX")
    if (destinationFile.exists() && !backup.exists()) {
        if (!promoteTempFileAtomically(destinationFile, backup)) return false
    }
    if (promoteTempFileAtomically(candidateFile, destinationFile)) return true
    if (backup.exists()) rollbackModelActivation(modelDirectory)
    return false
}

internal fun confirmModelActivation(modelDirectory: File) {
    modelDirectory.listFiles { file -> file.name.endsWith(LAST_WORKING_MODEL_SUFFIX) }
        .orEmpty()
        .forEach(File::delete)
}

internal fun hasPendingModelActivation(modelDirectory: File): Boolean =
    modelDirectory.listFiles { file -> file.name.endsWith(LAST_WORKING_MODEL_SUFFIX) }
        .orEmpty()
        .isNotEmpty()

internal fun rollbackModelActivation(modelDirectory: File): Boolean {
    val backups =
        modelDirectory.listFiles { file -> file.name.endsWith(LAST_WORKING_MODEL_SUFFIX) }
            .orEmpty()
    val marker = File(modelDirectory, MODEL_ROLLBACK_MARKER)
    if (backups.isEmpty()) {
        marker.delete()
        return true
    }
    if (!writeRollbackMarker(marker)) return false

    val restored =
        backups.all { backup ->
            val destination = File(modelDirectory, backup.name.removeSuffix(LAST_WORKING_MODEL_SUFFIX))
            val rollbackCopy = File(modelDirectory, "${destination.name}$MODEL_ROLLBACK_TEMP_SUFFIX")
            runCatching {
                Files.copy(backup.toPath(), rollbackCopy.toPath(), StandardCopyOption.REPLACE_EXISTING)
                promoteTempFileAtomically(rollbackCopy, destination)
            }.getOrDefault(false).also { rollbackCopy.delete() }
        }
    if (!restored) return false

    backups.forEach(File::delete)
    if (backups.any(File::exists)) return false
    if (!marker.delete() && marker.exists()) return false
    return restored
}

internal fun recoverInterruptedModelRollback(modelDirectory: File): Boolean {
    val marker = File(modelDirectory, MODEL_ROLLBACK_MARKER)
    val interruptedPromotion =
        modelDirectory.listFiles { file -> file.name.endsWith(LAST_WORKING_MODEL_SUFFIX) }
            .orEmpty()
            .any { backup ->
                !File(modelDirectory, backup.name.removeSuffix(LAST_WORKING_MODEL_SUFFIX)).exists()
            }
    return (!marker.exists() && !interruptedPromotion) || rollbackModelActivation(modelDirectory)
}

private fun writeRollbackMarker(marker: File): Boolean =
    runCatching {
        marker.parentFile?.mkdirs()
        FileOutputStream(marker).use { output ->
            output.write(1)
            output.fd.sync()
        }
        true
    }.getOrDefault(false)

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
