package dev.parakeeboard.app.core.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Section header for settings screens and list sections.
 * Provides consistent typography and padding for section titles.
 *
 * @param title Section title text
 * @param modifier Optional modifier for customization
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = 24.dp,
                bottom = 8.dp
            )
    )
}
