package dev.chirpboard.app

import dev.chirpboard.app.feature.transcription.TerminalRecordingNotificationDelivery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Replays Room-backed notification markers whenever notification access may have returned. */
internal class TerminalNotificationRecoveryLauncher(
    private val scope: CoroutineScope,
    private val delivery: TerminalRecordingNotificationDelivery,
    private val onFailure: (Throwable) -> Unit,
) {
    private var recoveryJob: Job? = null

    fun onNotificationAccess(enabled: Boolean) {
        if (!enabled || recoveryJob?.isActive == true) {
            return
        }
        recoveryJob =
            scope.launch {
                try {
                    delivery.recoverPendingNotifications()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onFailure(e)
                }
            }
    }
}
