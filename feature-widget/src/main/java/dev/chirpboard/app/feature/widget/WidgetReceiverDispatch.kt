package dev.chirpboard.app.feature.widget

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal object WidgetReceiverDispatch {
    fun dispatchToggle(
        scope: CoroutineScope,
        toggleRecording: suspend () -> Unit,
        finish: () -> Unit,
    ) {
        scope.launch {
            try {
                toggleRecording()
            } finally {
                finish()
            }
        }
    }
}
