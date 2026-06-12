package dev.chirpboard.app.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.ui.theme.ChirpShapes

/**
 * One filled, fully-rounded pill — the single capsule style for the app (VIS-3 / VIS-1).
 *
 * Replaces both the home [StatsPillRow] outlined `SuggestionChip`s and the per-recording
 * [MetadataPillRow] capsules so the three home pill roles read as one design language: a filled
 * [ChirpShapes.Full] capsule on `surfaceContainerHigh`, no border.
 *
 * Pass [onClick] for an interactive pill (e.g. the home processing filter); leave it null for a
 * display-only pill (e.g. count/duration/metadata) so there is no dead ripple affordance.
 *
 * @param label pill text.
 * @param icon optional leading glyph.
 * @param onClick when non-null the pill is clickable (ripple + role); null = static display.
 * @param containerColor capsule fill; defaults to `surfaceContainerHigh`.
 * @param contentColor text + icon tint; defaults to `onSurfaceVariant`.
 */
@Composable
fun ChirpPill(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    contentDescription: String? = null,
) {
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(18.dp),
                    tint = contentColor,
                )
            }
            Text(
                text = label,
                color = contentColor,
                style = MaterialTheme.typography.labelMedium,
                // Long labels (e.g. Bluetooth device names in the input-device chip)
                // ellipsize instead of wrapping the capsule onto a second line.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier,
            color = containerColor,
            contentColor = contentColor,
            shape = ChirpShapes.Full,
            content = content,
        )
    } else {
        Surface(
            // A11Y-10: merge the icon description + label into one TalkBack stop ("Duration,
            // 03:42") for display-only pills; clickable pills already merge via their click node.
            modifier = modifier.semantics(mergeDescendants = true) {},
            color = containerColor,
            contentColor = contentColor,
            shape = ChirpShapes.Full,
            content = content,
        )
    }
}
