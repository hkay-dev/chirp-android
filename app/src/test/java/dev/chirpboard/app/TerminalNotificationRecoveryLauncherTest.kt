package dev.chirpboard.app

import dev.chirpboard.app.feature.transcription.TerminalRecordingNotificationDelivery
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalNotificationRecoveryLauncherTest {
    @Test
    fun enabledAccess_drainsPendingMarkersInTheLiveProcess() =
        runTest {
            val delivery = mockk<TerminalRecordingNotificationDelivery>()
            coEvery { delivery.recoverPendingNotifications() } returns 1
            val launcher =
                TerminalNotificationRecoveryLauncher(
                    scope = this,
                    delivery = delivery,
                    onFailure = {},
                )

            launcher.onNotificationAccess(enabled = true)
            runCurrent()

            coVerify(exactly = 1) { delivery.recoverPendingNotifications() }
        }

    @Test
    fun disabledAccess_keepsPendingMarkersForLater() =
        runTest {
            val delivery = mockk<TerminalRecordingNotificationDelivery>(relaxed = true)
            val launcher =
                TerminalNotificationRecoveryLauncher(
                    scope = this,
                    delivery = delivery,
                    onFailure = {},
                )

            launcher.onNotificationAccess(enabled = false)

            coVerify(exactly = 0) { delivery.recoverPendingNotifications() }
        }
}
