package dev.chirpboard.app.core.modelreadiness

import dev.chirpboard.app.core.transcription.LocalSpeechModelId
import kotlinx.coroutines.flow.StateFlow

/**
 * Snapshot of the app-scoped speech-model download work.
 *
 * The download is executed as WorkManager unique work in the app module (ERR-1): it must
 * survive leaving the settings screen, the host process dying mid-transfer, and reboots.
 * UI layers (SpeechModelManager -> settings ViewModel) only OBSERVE this state; they never
 * own the transfer.
 */
sealed interface SpeechModelDownloadWork {
    /** No download is scheduled or running, and no terminal result is pending. */
    data object Idle : SpeechModelDownloadWork

    /** Work is scheduled but not running: waiting for network or in retry backoff. */
    data object Waiting : SpeechModelDownloadWork

    data class Running(
        val file: String,
        val progress: Float,
    ) : SpeechModelDownloadWork

    data object Succeeded : SpeechModelDownloadWork

    /**
     * The download exhausted its bounded retries (ERR-3). Surfaced persistently until the
     * user explicitly retries; partial files are kept on disk so the retry resumes.
     */
    data class Failed(
        val message: String,
    ) : SpeechModelDownloadWork
}

/**
 * App-module gateway that runs the model download as WorkManager unique work with a
 * foreground progress notification. Implemented over the app's [SpeechModelStore]
 * implementation; feature modules depend only on this contract.
 */
interface SpeechModelDownloadGateway {
    val work: StateFlow<SpeechModelDownloadWork>

    /**
     * Enqueue the unique download work (no-op if it is already scheduled or running).
     *
     * @param preferInternalStorage download into app-private storage instead of the shared
     * Documents location, for users who decline the All-files-access permission (PLT-07).
     */
    fun startDownload(
        modelId: LocalSpeechModelId,
        preferInternalStorage: Boolean = false,
    )

    fun startDownload(preferInternalStorage: Boolean = false) {
        startDownload(LocalSpeechModelId.DEFAULT, preferInternalStorage)
    }

    /** Cancel scheduled/running download work. Partial files are kept for a later resume. */
    fun cancelDownload()
}
