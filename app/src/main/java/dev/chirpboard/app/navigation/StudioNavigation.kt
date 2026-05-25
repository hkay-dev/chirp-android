package dev.chirpboard.app.navigation

import androidx.navigation.NavController
import java.util.UUID

/**
 * Navigate to Processing Studio with single-top semantics so repeated taps on the same
 * recording do not stack duplicate back stack entries.
 */
fun NavController.navigateToStudio(
    recordingId: UUID,
    configure: androidx.navigation.NavOptionsBuilder.() -> Unit = {},
) {
    navigate(Screen.ProcessingStudio.createRoute(recordingId.toString())) {
        launchSingleTop = true
        restoreState = true
        configure()
    }
}

fun NavController.navigateToStudio(
    recordingId: String,
    configure: androidx.navigation.NavOptionsBuilder.() -> Unit = {},
) {
    navigate(Screen.ProcessingStudio.createRoute(recordingId)) {
        launchSingleTop = true
        restoreState = true
        configure()
    }
}
