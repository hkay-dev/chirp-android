package dev.chirpboard.app.core.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Section header for settings screens with primary color title and divider.
 *
 * @param title Section title text
 * @param modifier Optional modifier for customization
 */
@Composable
fun SettingsSectionHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(
            thickness = 2.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
        )
    }
}
