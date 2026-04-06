package dev.chirpboard.app.feature.transcription.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chirpboard.app.feature.transcription.WhisperModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class TranscriptionSettingsViewModel @Inject constructor(
    private val modelManager: WhisperModelManager
) : ViewModel() {

    data class UiState(
        val modelName: String = WhisperModelManager.MODEL_DISPLAY_NAME,
        val modelSizeMb: Int = WhisperModelManager.MODEL_SIZE_MB,
        val isDownloaded: Boolean = false,
        val isLoading: Boolean = false,
        val downloadProgress: Float = 0f,
        val currentFile: String = "",
        val errorMessage: String? = null,
        val showDeleteConfirmation: Boolean = false
    )
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    init {
        viewModelScope.launch {
            modelManager.modelStatus.collect { status ->
                when (status) {
                    is WhisperModelManager.ModelStatus.Ready -> {
                        _uiState.update {
                            it.copy(
                                isDownloaded = true,
                                isLoading = false,
                                downloadProgress = 0f,
                                errorMessage = null
                            )
                        }
                    }
                    is WhisperModelManager.ModelStatus.NotDownloaded -> {
                        _uiState.update {
                            it.copy(
                                isDownloaded = false,
                                isLoading = false,
                                downloadProgress = 0f
                            )
                        }
                    }
                    is WhisperModelManager.ModelStatus.Downloading -> {
                        _uiState.update {
                            it.copy(
                                isLoading = true,
                                downloadProgress = status.progress
                            )
                        }
                    }
                    is WhisperModelManager.ModelStatus.Error -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = status.message
                            )
                        }
                    }
                }
            }
        }
        
        // Initial status check
        modelManager.refreshStatus()
    }

    fun downloadModel() {
        if (_uiState.value.isLoading) return
        
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null, downloadProgress = 0f) }
            
            try {
                withContext(Dispatchers.IO) {
                    downloadModelFiles()
                }
                modelManager.markDownloadComplete()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val message = e.message ?: "Download failed"
                modelManager.markDownloadError(message)
                _uiState.update { it.copy(errorMessage = message) }
            }
        }
    }

    private suspend fun downloadModelFiles() {
        val modelDir = modelManager.getModelDir()
        modelDir.mkdirs()
        
        val files = listOf(
            ModelFile("encoder.int8.onnx", 650_000_000L),
            ModelFile("decoder.int8.onnx", 7_000_000L),
            ModelFile("joiner.int8.onnx", 1_700_000L),
            ModelFile("tokens.txt", 9_000L)
        )
        
        val baseUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/resolve/main"
        val totalSize = files.sumOf { it.expectedSize }
        var totalDownloaded = 0L
        
        for (file in files) {
            val destFile = File(modelDir, file.name)
            
            // Skip if already downloaded
            if (destFile.exists() && destFile.length() > file.expectedSize * 0.9) {
                totalDownloaded += destFile.length()
                updateProgress(file.name, totalDownloaded, totalSize)
                continue
            }
            
            val url = "$baseUrl/${file.name}"
            _uiState.update { it.copy(currentFile = file.name) }
            
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Failed to download ${file.name}: ${response.code}")
                }
                
                val body = response.body ?: throw Exception("Empty response for ${file.name}")
                var downloaded = 0L
                
                val tempFile = File(modelDir, file.name + ".download")
                try {
                    FileOutputStream(tempFile).use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(8192)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                                downloaded += read
                                updateProgress(file.name, totalDownloaded + downloaded, totalSize)
                            }
                        }
                    }
                    if (!tempFile.renameTo(destFile)) {
                        throw Exception("Failed to rename temp file to ${file.name}")
                    }
                } finally {
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                }
                
                totalDownloaded += downloaded
            }
        }
    }
    
    private fun updateProgress(file: String, downloaded: Long, total: Long) {
        val progress = downloaded.toFloat() / total.toFloat()
        modelManager.updateDownloadProgress(progress)
        _uiState.update { it.copy(currentFile = file, downloadProgress = progress) }
    }
    

    fun showDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteConfirmation = true) }
    }

    fun dismissDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteConfirmation = false) }
    }

    fun deleteModel() {
        viewModelScope.launch {
            _uiState.update { it.copy(showDeleteConfirmation = false) }
            
            val success = withContext(Dispatchers.IO) {
                modelManager.deleteModel()
            }
            
            if (!success) {
                _uiState.update { it.copy(errorMessage = "Failed to delete model files") }
            }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
    
    private data class ModelFile(val name: String, val expectedSize: Long)
}
