package dev.chirpboard.app.feature.recording.service

import android.content.Intent
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingServiceCommands
import java.util.UUID

internal sealed interface RecordingServiceCommand {
    data class Start(
        val origin: RecordingOrigin,
        val profileId: UUID?,
    ) : RecordingServiceCommand

    data object Pause : RecordingServiceCommand

    data object Resume : RecordingServiceCommand

    data object Stop : RecordingServiceCommand

    data object Cancel : RecordingServiceCommand

    data class Restart(
        val origin: RecordingOrigin,
        val profileId: UUID?,
    ) : RecordingServiceCommand
}

internal object RecordingServiceCommandRouter {
    fun commandFor(intent: Intent?): RecordingServiceCommand? =
        commandFor(
            action = intent?.action,
            originName = intent?.getStringExtra(RecordingServiceCommands.EXTRA_ORIGIN),
            profileIdRaw = intent?.getStringExtra(RecordingServiceCommands.EXTRA_PROFILE_ID),
        )

    internal fun commandFor(
        action: String?,
        originName: String? = null,
        profileIdRaw: String? = null,
    ): RecordingServiceCommand? =
        when (action) {
            RecordingServiceCommands.ACTION_START_RECORDING ->
                RecordingServiceCommand.Start(
                    origin = recordingOrigin(originName),
                    profileId = profileId(profileIdRaw),
                )

            RecordingServiceCommands.ACTION_PAUSE_RECORDING -> RecordingServiceCommand.Pause

            RecordingServiceCommands.ACTION_RESUME_RECORDING -> RecordingServiceCommand.Resume

            RecordingServiceCommands.ACTION_STOP_RECORDING -> RecordingServiceCommand.Stop

            RecordingServiceCommands.ACTION_CANCEL_RECORDING -> RecordingServiceCommand.Cancel

            RecordingServiceCommands.ACTION_RESTART_RECORDING ->
                RecordingServiceCommand.Restart(
                    origin = recordingOrigin(originName),
                    profileId = profileId(profileIdRaw),
                )

            else -> null
        }

    private fun recordingOrigin(originName: String?): RecordingOrigin =
        runCatching { RecordingOrigin.valueOf(originName ?: RecordingOrigin.APP.name) }
            .getOrDefault(RecordingOrigin.APP)

    private fun profileId(raw: String?): UUID? =
        raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }
}
