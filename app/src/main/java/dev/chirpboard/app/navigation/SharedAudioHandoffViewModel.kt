package dev.chirpboard.app.navigation

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chirpboard.app.feature.recording.importing.AudioImportOrchestrator
import dev.chirpboard.app.feature.recording.importing.AudioImportResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

internal data class SharedAudioRequest(
    val token: String,
    val uri: Uri,
)

internal sealed interface SharedAudioIntakeState {
    data object Idle : SharedAudioIntakeState

    data class Loading(
        val request: SharedAudioRequest,
    ) : SharedAudioIntakeState

    data class Failure(
        val request: SharedAudioRequest,
        val message: String,
    ) : SharedAudioIntakeState
}

private const val EXTRA_SHARED_AUDIO_REQUEST_TOKEN = "dev.chirpboard.app.extra.SHARED_AUDIO_REQUEST_TOKEN"
private const val LAST_HANDLED_SHARED_AUDIO_TOKEN = "last_handled_shared_audio_token"

internal fun Intent.toSharedAudioRequestOrNull(): SharedAudioRequest? {
    if (action != Intent.ACTION_SEND) {
        return null
    }

    val mimeType = type ?: return null
    if (!mimeType.startsWith("audio/")) {
        return null
    }

    val clipItemCount = clipData?.itemCount ?: 0
    if (clipItemCount > 1) {
        return null
    }

    val streamUri = extractSharedAudioUri() ?: return null
    val token =
        getStringExtra(EXTRA_SHARED_AUDIO_REQUEST_TOKEN)
            ?: UUID.randomUUID().toString().also { putExtra(EXTRA_SHARED_AUDIO_REQUEST_TOKEN, it) }

    return SharedAudioRequest(token = token, uri = streamUri)
}

private fun Intent.extractSharedAudioUri(): Uri? =
    getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        ?: clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri

@HiltViewModel
internal class SharedAudioHandoffViewModel
    @Inject
    constructor(
        private val audioImportOrchestrator: AudioImportOrchestrator,
        private val savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow<SharedAudioIntakeState>(SharedAudioIntakeState.Idle)
        internal val uiState: StateFlow<SharedAudioIntakeState> = _uiState.asStateFlow()

        private val _navigationTarget = MutableStateFlow<UUID?>(null)
        internal val navigationTarget: StateFlow<UUID?> = _navigationTarget.asStateFlow()

        private var activeRequest: SharedAudioRequest? = null
        private var importJob: Job? = null

        internal fun onIncomingRequest(request: SharedAudioRequest?) {
            if (request == null) {
                return
            }

            val lastHandledToken = savedStateHandle.get<String>(LAST_HANDLED_SHARED_AUDIO_TOKEN)
            if (request.token == lastHandledToken || request.token == activeRequest?.token) {
                return
            }

            if (importJob?.isActive == true) {
                return
            }

            beginImport(request)
        }

        internal fun retry() {
            val failedRequest = (uiState.value as? SharedAudioIntakeState.Failure)?.request ?: return
            beginImport(failedRequest)
        }

        internal fun dismissFailure() {
            val failedRequest = (uiState.value as? SharedAudioIntakeState.Failure)?.request ?: return
            savedStateHandle[LAST_HANDLED_SHARED_AUDIO_TOKEN] = failedRequest.token
            activeRequest = null
            _uiState.value = SharedAudioIntakeState.Idle
        }

        internal fun onNavigationHandled() {
            _navigationTarget.value = null
        }

        private fun beginImport(request: SharedAudioRequest) {
            activeRequest = request
            _uiState.value = SharedAudioIntakeState.Loading(request)
            importJob =
                viewModelScope.launch {
                    when (val result = audioImportOrchestrator.import(request.uri)) {
                        is AudioImportResult.FailedBeforePersistence -> {
                            _uiState.value = SharedAudioIntakeState.Failure(request, result.message)
                        }

                        is AudioImportResult.SavedAndQueued -> {
                            finishSuccess(request, result.recordingId)
                        }

                        is AudioImportResult.SavedPendingRecovery -> {
                            finishSuccess(request, result.recordingId)
                        }
                    }
                }
        }

        private fun finishSuccess(
            request: SharedAudioRequest,
            recordingId: UUID,
        ) {
            savedStateHandle[LAST_HANDLED_SHARED_AUDIO_TOKEN] = request.token
            activeRequest = null
            _uiState.value = SharedAudioIntakeState.Idle
            _navigationTarget.value = recordingId
        }
    }
