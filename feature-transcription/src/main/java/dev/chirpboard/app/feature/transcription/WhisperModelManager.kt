package dev.chirpboard.app.feature.transcription

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the Whisper/Parakeet speech recognition model.
 * 
 * Provides model status information, download/delete operations,
 * and progress tracking for the settings UI.
 */
@Singleton
class WhisperModelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val MODEL_DIR = "parakeet-tdt-0.6b-v2"
        const val MODEL_DISPLAY_NAME = "Parakeet TDT 0.6B"
        
        // Model files with expected sizes
        private val MODEL_FILES = listOf(
            ModelFile("encoder.int8.onnx", 650_000_000L),
            ModelFile("decoder.int8.onnx", 7_000_000L),
            ModelFile("joiner.int8.onnx", 1_700_000L),
            ModelFile("tokens.txt", 9_000L)
        )
        
        val TOTAL_MODEL_SIZE: Long = MODEL_FILES.sumOf { it.expectedSize }
        const val MODEL_SIZE_MB = 659 // Approximate total in MB
    }
    
    private data class ModelFile(val name: String, val expectedSize: Long)
    
    sealed interface ModelStatus {
        data object NotDownloaded : ModelStatus
        data object Ready : ModelStatus
        data class Downloading(val progress: Float) : ModelStatus
        data class Error(val message: String) : ModelStatus
    }
    
    sealed interface DownloadState {
        data class Progress(val file: String, val bytesDownloaded: Long, val totalBytes: Long) : DownloadState
        data object Complete : DownloadState
        data class Error(val message: String) : DownloadState
    }
    
    private val _modelStatus = MutableStateFlow<ModelStatus>(ModelStatus.NotDownloaded)
    val modelStatus: StateFlow<ModelStatus> = _modelStatus.asStateFlow()
    
    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()
    
    init {
        refreshStatus()
    }
    
    /**
     * Check and update current model status.
     */
    fun refreshStatus() {
        // Offload disk I/O to IO thread to prevent main thread blocking
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            val status = if (isModelDownloaded()) {
                ModelStatus.Ready
            } else {
                ModelStatus.NotDownloaded
            }
            _modelStatus.value = status
        }
    }
    
    /**
     * Whether all model files are downloaded.
     */
    suspend fun isModelDownloaded(): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val modelPath = getModelDir()
        val legacyPath = File(context.filesDir, "models/$MODEL_DIR")
        
        MODEL_FILES.all { file ->
            val persistent = File(modelPath, file.name)
            val legacy = File(legacyPath, file.name)
            val persistentOk = persistent.exists() && persistent.length() > file.expectedSize * 0.9
            val legacyOk = legacy.exists() && legacy.length() > file.expectedSize * 0.9
            persistentOk || legacyOk
        }
    }
    
    /**
     * Get the actual downloaded model size in bytes.
     */
    suspend fun getDownloadedSize(): Long = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val modelPath = getModelDir()
        val legacyPath = File(context.filesDir, "models/$MODEL_DIR")
        
        MODEL_FILES.sumOf { file ->
            val persistent = File(modelPath, file.name)
            val legacy = File(legacyPath, file.name)
            when {
                persistent.exists() -> persistent.length()
                legacy.exists() -> legacy.length()
                else -> 0L
            }
        }
    }
    
    /**
     * Delete the downloaded model files.
     * @return true if deletion was successful
     */
    suspend fun deleteModel(): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        var success = true
        
        // Delete from persistent storage
        val modelPath = getModelDir()
        if (modelPath.exists()) {
            success = modelPath.deleteRecursively() && success
        }
        
        // Delete from legacy internal storage
        val legacyPath = File(context.filesDir, "models/$MODEL_DIR")
        if (legacyPath.exists()) {
            success = legacyPath.deleteRecursively() && success
        }
        
        refreshStatus()
        success
    }
    
    /**
     * Update download progress (called by external downloader).
     * Thread-safe: only updates status if currently downloading.
     */
    fun updateDownloadProgress(progress: Float) {
        _downloadProgress.value = progress
        _modelStatus.update { current ->
            if (current is ModelStatus.Downloading) {
                ModelStatus.Downloading(progress)
            } else {
                // Start downloading if not already
                ModelStatus.Downloading(progress)
            }
        }
    }

    /**
     * Mark download as complete.
     * Thread-safe via StateFlow.update{}.
     */
    fun markDownloadComplete() {
        _modelStatus.update { ModelStatus.Ready }
        _downloadProgress.value = 0f
    }

    /**
     * Mark download as failed.
     * Thread-safe via StateFlow.update{}.
     */
    fun markDownloadError(message: String) {
        _modelStatus.update { ModelStatus.Error(message) }
        _downloadProgress.value = 0f
    }
    
    /**
     * Get the persistent model directory.
     */
    fun getModelDir(): File {
        val docsDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOCUMENTS
        )
        val persistentDir = File(docsDir, ".chirpboard/models/$MODEL_DIR")
        
        if (persistentDir.exists() || persistentDir.mkdirs()) {
            return persistentDir
        }
        
        return File(context.filesDir, "models/$MODEL_DIR")
    }
}
