package dev.chirpboard.app.feature.studio.tabs

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.ui.motion.ChirpMotion

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
        AnimatedVisibility(
            visible = progressCopy != null && progressKind != null,
            enter = progressEnterTransition,
            exit = progressExitTransition,
        ) {
            val copy = progressCopy ?: return@AnimatedVisibility
            val kind = progressKind ?: return@AnimatedVisibility
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

        AnimatedVisibility(
            visible = showPlayer,
            enter = progressEnterTransition,
            exit = progressExitTransition,
        ) {
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
