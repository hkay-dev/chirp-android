package dev.chirpboard.app.feature.recording.service

import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.recording.RecordingServiceCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class RecordingServiceCommandRouterTest {
    @Test
    fun `start intent maps origin and profile id`() {
        val profileId = UUID.randomUUID()
        val command =
            RecordingServiceCommandRouter.commandFor(
                action = RecordingServiceCommands.ACTION_START_RECORDING,
                originName = RecordingOrigin.WIDGET.name,
                profileIdRaw = profileId.toString(),
            )

        assertEquals(
            RecordingServiceCommand.Start(
                origin = RecordingOrigin.WIDGET,
                profileId = profileId,
            ),
            command,
        )
    }

    @Test
    fun `invalid origin falls back to app`() {
        val command =
            RecordingServiceCommandRouter.commandFor(
                action = RecordingServiceCommands.ACTION_RESTART_RECORDING,
                originName = "bad-origin",
            )

        assertEquals(
            RecordingServiceCommand.Restart(
                origin = RecordingOrigin.APP,
                profileId = null,
            ),
            command,
        )
    }

    @Test
    fun `unknown intent maps to null`() {
        assertNull(RecordingServiceCommandRouter.commandFor(action = "unknown"))
    }
}
