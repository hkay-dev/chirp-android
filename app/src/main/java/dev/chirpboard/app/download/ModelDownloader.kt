package dev.chirpboard.app.download

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ModelDownloader(private val context: Context) {
    companion object {
        private const val TAG = "ModelDownloader"
        private const val MODEL_DIR = "parakeet-tdt-0.6b-v2"
        private const val BASE_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main"

        private val MODEL_FILES = listOf(
            ModelFile("encoder.int8.onnx", 650_000_000L),
            ModelFile("decoder.int8.onnx", 7_000_000L),
            ModelFile("joiner.int8.onnx", 1_700_000L),
            ModelFile("tokens.txt", 9_000L)
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

    data class ModelFile(val name: String, val expectedSize: Long)

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

    fun isModelDownloaded(): Boolean {
        val modelPath = getModelDir(context)
        // Also check legacy internal storage path for backward compatibility
        val legacyPath = File(context.filesDir, "models/$MODEL_DIR")
        Log.d(TAG, "isModelDownloaded check — persistent: ${modelPath.absolutePath} (exists=${modelPath.exists()})")
        Log.d(TAG, "isModelDownloaded check — legacy: ${legacyPath.absolutePath} (exists=${legacyPath.exists()})")
        val result = MODEL_FILES.all { file ->
            val f = File(modelPath, file.name)
            val legacy = File(legacyPath, file.name)
            val persistentOk = f.exists() && f.length() > file.expectedSize * 0.9
            val legacyOk = legacy.exists() && legacy.length() > file.expectedSize * 0.9
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

        for (file in MODEL_FILES) {
            val destFile = File(modelPath, file.name)
            if (destFile.exists() && destFile.length() > file.expectedSize * 0.9) {
                totalDownloaded += destFile.length()
                emit(DownloadState.Progress(file.name, totalDownloaded, totalSize))
                continue
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

                val contentLength = body.contentLength()
                var downloaded = 0L

                FileOutputStream(destFile).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            emit(DownloadState.Progress(
                                file.name,
                                totalDownloaded + downloaded,
                                totalSize
                            ))
                        }
                    }
                }

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
}
