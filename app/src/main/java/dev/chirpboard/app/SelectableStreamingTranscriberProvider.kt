package dev.chirpboard.app

import android.util.Log
import dev.chirpboard.app.core.transcription.LocalSpeechModelId
import dev.chirpboard.app.core.transcription.LocalSpeechModelSelectionStore
import dev.chirpboard.app.core.transcription.StreamingTranscriberProvider
import dev.chirpboard.app.core.transcription.StreamingTranscriptionSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Keeps the optional Sherpa streaming preview out of GGUF sessions.
 *
 * GGUF is the authoritative continuous decoder and does not support incremental preview. The
 * separate Sherpa preview stays available only when the Sherpa model is selected, avoiding an
 * unexpected second resident recognizer after switching to the smaller GGUF model.
 */
class SelectableStreamingTranscriberProvider(
    private val selectionStore: LocalSpeechModelSelectionStore,
    private val sherpa: StreamingTranscriberProvider,
) : StreamingTranscriberProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            selectionStore.selectedModel.drop(1).collect { selected ->
                if (selected == LocalSpeechModelId.PARAKEET_CTC_110M_Q8) {
                    runCatching { sherpa.release() }
                        .onFailure { Log.w(TAG, "Could not release the inactive streaming preview", it) }
                }
            }
        }
    }

    override suspend fun prepare(): Boolean =
        if (selectionStore.selectedModel.value == LocalSpeechModelId.PARAKEET_TDT_600M) {
            sherpa.prepare()
        } else {
            sherpa.release()
            false
        }

    override suspend fun openSession(sampleRate: Int): StreamingTranscriptionSession? =
        if (selectionStore.selectedModel.value == LocalSpeechModelId.PARAKEET_TDT_600M) {
            sherpa.openSession(sampleRate)
        } else {
            sherpa.release()
            null
        }

    override suspend fun release() = sherpa.release()

    private companion object {
        const val TAG = "SelectableStreaming"
    }
}
