package dev.chirpboard.app.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.chirpboard.app.core.ui.theme.ChirpSpacing

/**
 * Section header for settings screens with a primary-color title; sections are separated by
 * whitespace, no divider is rendered.
 *
 * @param title Section title text
 * @param modifier Optional modifier for customization
 */
@Composable
fun SettingsSectionHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier.padding(
                    start = ChirpSpacing.ScreenHorizontal,
                    end = ChirpSpacing.ScreenHorizontal,
                    top = ChirpSpacing.ExtraLarge,
                    bottom = ChirpSpacing.Small,
                )
        )
    }
}
