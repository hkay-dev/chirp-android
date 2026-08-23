package dev.chirpboard.app.feature.studio.tabs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.ui.motion.PushDownReveal
import dev.chirpboard.app.core.ui.components.TranscriptionProgressBanner
import dev.chirpboard.app.core.ui.components.TranscriptionProgressCopy
import dev.chirpboard.app.core.ui.components.TranscriptionProgressKind

@Composable
internal fun StudioProcessingHeader(
    progressCopy: TranscriptionProgressCopy?,
    progressKind: TranscriptionProgressKind?,
    showPlayer: Boolean,
    playerContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth(),
    ) {
        // Latch the last non-null banner content: when progress finishes, the values go
        // null in the same frame the exit starts, and returning early would leave the
        // shrink/fade animating an empty box instead of the departing banner.
        var latchedCopy by remember { mutableStateOf(progressCopy) }
        var latchedKind by remember { mutableStateOf(progressKind) }
        if (progressCopy != null && progressKind != null) {
            latchedCopy = progressCopy
            latchedKind = progressKind
        }
        PushDownReveal(visible = progressCopy != null && progressKind != null) {
            val copy = latchedCopy ?: return@PushDownReveal
            val kind = latchedKind ?: return@PushDownReveal
            // The banner already crossfades its icon on kind and its copy on text change;
            // wrapping it in another AnimatedContent double-animated every phase switch.
            TranscriptionProgressBanner(
                copy = copy,
                kind = kind,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        PushDownReveal(visible = showPlayer) {
            Column(modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(
                    modifier = Modifier.padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                )
                playerContent()
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                )
            }
        }
    }
}
