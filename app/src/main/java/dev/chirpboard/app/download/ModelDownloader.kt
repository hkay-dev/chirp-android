package dev.chirpboard.app.download

import android.content.Context
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ModelDownloader(private val context: Context) {
    companion object {
        private const val TAG = "ModelDownloader"
        private const val MODEL_DIR = "parakeet-tdt-0.6b-v2"
        private const val BASE_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main"

        private const val MIN_STORAGE_BUFFER_BYTES = 50L * 1024L * 1024L

        private val MODEL_FILES = listOf(
            ModelFile(
                name = "encoder.int8.onnx",
                expectedSize = 652_184_296L,
                expectedSha256 = "a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab"
            ),
            ModelFile(
                name = "decoder.int8.onnx",
                expectedSize = 7_257_753L,
                expectedSha256 = "b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e"
            ),
            ModelFile(
                name = "joiner.int8.onnx",
                expectedSize = 1_739_080L,
                expectedSha256 = "7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2"
            ),
            ModelFile(
                name = "tokens.txt",
                expectedSize = 9_384L,
                expectedSha256 = "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d"
            )
        )
        
        /**
         * Get the persistent model directory that survives "Clear Data".
         * Uses Documents/.chirpboard/ in shared storage which requires MANAGE_EXTERNAL_STORAGE
         * on Android 11+, but is not touched by pm clear.
         * Falls back to internal storage if the persistent path is not writable.
         */
        fun getModelDir(context: Context): File {
            val docsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOCUMENTS
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
        val expectedSha256: String
    )

    private data class VerificationCacheEntry(
        val size: Long,
        val lastModified: Long,
        val expectedSha256: String,
        val valid: Boolean
    )

    sealed interface DownloadState {
        data class Progress(val file: String, val bytesDownloaded: Long, val totalBytes: Long) : DownloadState
        data object Complete : DownloadState
        data class Error(val message: String) : DownloadState
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val verificationCache = mutableMapOf<String, VerificationCacheEntry>()

    fun isModelDownloaded(): Boolean {
        val modelPath = getModelDir(context)
        // Also check legacy internal storage path for backward compatibility
        val legacyPath = File(context.filesDir, "models/$MODEL_DIR")
        Log.d(TAG, "isModelDownloaded check — persistent: ${modelPath.absolutePath} (exists=${modelPath.exists()})")
        Log.d(TAG, "isModelDownloaded check — legacy: ${legacyPath.absolutePath} (exists=${legacyPath.exists()})")
        val result = MODEL_FILES.all { file ->
            val f = File(modelPath, file.name)
            val legacy = File(legacyPath, file.name)
            val persistentOk = isValidDownloadedFile(f, file)
            val legacyOk = isValidDownloadedFile(legacy, file)
            Log.d(TAG, "  ${file.name}: persistent=${f.exists()}(${f.length()}), legacy=${legacy.exists()}, ok=${persistentOk || legacyOk}")
            persistentOk || legacyOk
        }
        Log.d(TAG, "isModelDownloaded = $result")
        return result
    }

    fun downloadModel(): Flow<DownloadState> = flow {
        val modelPath = getModelDir(context)
        modelPath.mkdirs()

        var totalDownloaded = 0L
        val totalSize = MODEL_FILES.sumOf { it.expectedSize }

        val requiredDownloadBytes = MODEL_FILES.sumOf { file ->
            val existing = File(modelPath, file.name)
            if (isValidDownloadedFile(existing, file)) 0L else file.expectedSize
        }

        val availableBytes = getAvailableBytes(modelPath)
        val requiredWithBuffer = requiredDownloadBytes + MIN_STORAGE_BUFFER_BYTES
        if (!hasSufficientStorage(availableBytes, requiredWithBuffer)) {
            emit(
                DownloadState.Error(
                    "Insufficient storage. Need about ${requiredWithBuffer / (1024 * 1024)} MB free."
                )
            )
            return@flow
        }

        for (file in MODEL_FILES) {
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
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    emit(DownloadState.Error("Failed to download ${file.name}: ${response.code}"))
                    return@flow
                }

                val body = response.body ?: run {
                    emit(DownloadState.Error("Empty response for ${file.name}"))
                    return@flow
                }

                val downloaded = body.byteStream().use { input ->
                    writeInputStreamToTempFile(input, tempFile) { bytesRead ->
                        emit(
                            DownloadState.Progress(
                                file.name,
                                totalDownloaded + bytesRead,
                                totalSize
                            )
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

                verificationCache[destFile.absolutePath] = VerificationCacheEntry(
                    size = destFile.length(),
                    lastModified = destFile.lastModified(),
                    expectedSha256 = file.expectedSha256,
                    valid = true
                )

                totalDownloaded += downloaded
                Log.i(TAG, "Downloaded ${file.name}: $downloaded bytes")

            } catch (e: Exception) {
                Log.e(TAG, "Download failed for ${file.name}", e)
                emit(DownloadState.Error("Download failed: ${e.message}"))
                return@flow
            }
        }

        emit(DownloadState.Complete)
    }.flowOn(Dispatchers.IO)

    private fun isValidDownloadedFile(file: File, modelFile: ModelFile): Boolean {
        if (!file.exists()) return false

        val size = file.length()
        val lastModified = file.lastModified()
        val cached = verificationCache[file.absolutePath]
        if (
            cached != null &&
            cached.size == size &&
            cached.lastModified == lastModified &&
            cached.expectedSha256 == modelFile.expectedSha256
        ) {
            return cached.valid
        }

        val valid = validateFileIntegrity(file, modelFile.expectedSize, modelFile.expectedSha256)
        verificationCache[file.absolutePath] = VerificationCacheEntry(
            size = size,
            lastModified = lastModified,
            expectedSha256 = modelFile.expectedSha256,
            valid = valid
        )
        return valid
    }

    private fun getAvailableBytes(path: File): Long {
        return try {
            val target = if (path.exists()) path else path.parentFile ?: path
            StatFs(target.absolutePath).availableBytes
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read free storage for ${path.absolutePath}", e)
            0L
        }
    }
}

internal fun hasSufficientStorage(availableBytes: Long, requiredBytes: Long): Boolean {
    return availableBytes >= requiredBytes
}

internal fun validateFileIntegrity(
    file: File,
    expectedSize: Long,
    expectedSha256: String
): Boolean {
    if (!file.exists()) return false
    if (file.length() != expectedSize) return false
    return computeSha256(file) == expectedSha256
}

internal suspend fun writeInputStreamToTempFile(
    input: InputStream,
    tempFile: File,
    onTotalBytesWritten: suspend (Long) -> Unit
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
        tempFile.delete()
        throw e
    }
}

internal fun promoteTempFileAtomically(tempFile: File, destinationFile: File): Boolean {
    return try {
        Files.move(
            tempFile.toPath(),
            destinationFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
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
