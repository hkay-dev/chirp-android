package dev.chirpboard.app.core.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The shared primary voice-trigger affordance — the idle "start dictation/record" mic (VIS-2).
 *
 * One control used by BOTH the keyboard idle mic and (next wave) the recognition dialog idle
 * state, so the gesture looks identical across surfaces: a `primaryContainer` M3 FAB (16.dp shape),
 * 56.dp by default, 2.dp elevation, a rounded mic glyph.
 *
 * @param onClick start-recording callback.
 * @param contentDescription accessible label for the mic (e.g. "Start recording").
 * @param size FAB size; defaults to 56.dp (the keyboard's size).
 * @param containerColor background; defaults to `primaryContainer`.
 * @param contentColor glyph tint; defaults to `onPrimaryContainer`.
 * @param iconSize the mic glyph size.
 */
@Composable
fun ChirpVoiceTriggerButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    icon: ImageVector = Icons.Rounded.Mic,
    iconSize: Dp = 28.dp,
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier.size(size),
        containerColor = containerColor,
        contentColor = contentColor,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** Default size of the sparkle glyph inside [ChirpLlmToggle]. */
private val LlmToggleIconSize = 20.dp

/**
 * The shared AI/LLM sparkle toggle (VIS-2 / DLG-7), resolving the unlabeled-sparkle a11y gap
 * (KBD-5).
 *
 * One control used by BOTH the keyboard and (next wave) the recognition dialog, so the AI
 * affordance is consistent: a `FilledTonalIconButton` whose container becomes the AI accent when
 * enabled (`tertiaryContainer`) and `surfaceContainerHighest` when off, with a rounded sparkle.
 *
 * The caller supplies a state-aware [contentDescription] (e.g. "AI enhancement" + an
 * enabled/disabled [stateDescription]) so screen readers announce both the control and its current
 * on/off state — the missing accessible label called out in KBD-5.
 *
 * @param enabled true when AI post-processing is currently ON (drives the accent + state).
 * @param onClick toggle / open-menu callback.
 * @param contentDescription accessible label for the control (state-independent).
 * @param interactionEnabled false disables the button (e.g. while recording-controls are locked).
 * @param onStateDescription announced when [enabled] is true (e.g. "Enabled").
 * @param offStateDescription announced when [enabled] is false (e.g. "Disabled").
 */
@Composable
fun ChirpLlmToggle(
    enabled: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    interactionEnabled: Boolean = true,
    onStateDescription: String? = null,
    offStateDescription: String? = null,
) {
    val stateLabel = if (enabled) onStateDescription else offStateDescription
    // No fixed 40dp size: the M3 default keeps the 40dp visual container while restoring
    // the 48dp minimum interactive bounds (a11y touch-target audit, A11Y-6).
    FilledTonalIconButton(
        onClick = onClick,
        enabled = interactionEnabled,
        modifier =
            modifier
                .semantics {
                    this.contentDescription = contentDescription
                    stateLabel?.let { this.stateDescription = it }
                },
        colors =
            IconButtonDefaults.filledTonalIconButtonColors(
                containerColor =
                    if (enabled) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                contentColor =
                    if (enabled) {
                        MaterialTheme.colorScheme.onTertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            ),
    ) {
        Icon(
            imageVector = Icons.Rounded.AutoAwesome,
            // Description carried by the parent semantics block above; avoid double announcement.
            contentDescription = null,
            modifier = Modifier.size(LlmToggleIconSize),
        )
    }
}
