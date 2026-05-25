package dev.chirpboard.app.feature.studio.tabs

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.ui.motion.ChirpMotion
import dev.chirpboard.app.core.ui.motion.PushDownReveal

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
        PushDownReveal(visible = progressCopy != null && progressKind != null) {
            val copy = progressCopy ?: return@PushDownReveal
            val kind = progressKind ?: return@PushDownReveal
            AnimatedContent(
                targetState = kind,
                transitionSpec = { ChirpMotion.studioContentCrossfade },
                label = "studio_progress_phase",
            ) {
                MorphingTranscriptionProgress(
                    compact = true,
                    copy = copy,
                    kind = it,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
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
