package dev.chirpboard.app.core.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.chirpboard.app.core.recording.RecordingSource
import dev.chirpboard.app.core.ui.R

fun RecordingSource.icon(): ImageVector =
    when (this) {
        RecordingSource.APP -> Icons.Filled.PhoneAndroid
        RecordingSource.KEYBOARD -> Icons.Filled.Keyboard
        RecordingSource.WIDGET -> Icons.Filled.Widgets
        RecordingSource.IMPORTED -> Icons.Filled.FileOpen
    }

fun RecordingSource.labelRes(): Int =
    when (this) {
        RecordingSource.APP -> R.string.rec_source_app
        RecordingSource.KEYBOARD -> R.string.rec_source_keyboard
        RecordingSource.WIDGET -> R.string.rec_source_widget
        RecordingSource.IMPORTED -> R.string.rec_source_imported
    }

@Composable
fun RecordingSource.label(): String = stringResource(labelRes())
