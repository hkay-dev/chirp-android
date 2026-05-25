package dev.chirpboard.app.feature.studio.tabs

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.ui.motion.ChirpMotion
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.feature.studio.R

internal data class TranscriptionProgressCopy(
    val title: String,
    val subtitle: String,
)

internal enum class TranscriptionProgressKind {
    Finalizing,
    Transcribing,
    Enhancing,
}

internal val progressEnterTransition = ChirpMotion.studioRevealTransition
internal val progressExitTransition = ChirpMotion.studioHideTransition
internal val studioLayoutMotionSpring = ChirpMotion.layoutMotionSpring
internal val studioContentAlphaTween = ChirpMotion.studioAlphaTween

@Composable
internal fun TranscriptionProgressPanel(
    copy: TranscriptionProgressCopy,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        MorphingTranscriptionProgress(
            compact = false,
            copy = copy,
        )
    }
}

@Composable
internal fun TranscriptionProgressBanner(
    copy: TranscriptionProgressCopy,
    modifier: Modifier = Modifier,
) {
    MorphingTranscriptionProgress(
        compact = true,
        copy = copy,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
internal fun AnimatedTranscriptionProgress(
    visible: Boolean,
    compact: Boolean,
    copy: TranscriptionProgressCopy?,
    modifier: Modifier = Modifier,
    fullscreen: Boolean = false,
) {
    AnimatedVisibility(
        visible = visible && copy != null,
        modifier = modifier,
        enter = progressEnterTransition,
        exit = progressExitTransition,
    ) {
        val progressCopy = copy ?: return@AnimatedVisibility
        Box(
            modifier =
                Modifier
                    .then(
                        if (fullscreen) {
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 32.dp, vertical = 24.dp)
                        } else {
                            Modifier.fillMaxWidth()
                        },
                    ),
            contentAlignment = if (compact) Alignment.TopStart else Alignment.Center,
        ) {
            MorphingTranscriptionProgress(
                compact = compact,
                copy = progressCopy,
            )
        }
    }
}

@Composable
internal fun MorphingTranscriptionProgress(
    compact: Boolean,
    copy: TranscriptionProgressCopy,
    modifier: Modifier = Modifier,
) {
    val cornerRadius by animateDpAsState(
        targetValue = if (compact) 16.dp else 28.dp,
        animationSpec = studioLayoutMotionSpring,
        label = "progress_corner_radius",
    )
    val spinnerSize by animateDpAsState(
        targetValue = if (compact) 22.dp else 36.dp,
        animationSpec = studioLayoutMotionSpring,
        label = "progress_spinner_size",
    )
    val horizontalPadding by animateDpAsState(
        targetValue = if (compact) 16.dp else 28.dp,
        animationSpec = studioLayoutMotionSpring,
        label = "progress_horizontal_padding",
    )
    val verticalPadding by animateDpAsState(
        targetValue = if (compact) 14.dp else 32.dp,
        animationSpec = studioLayoutMotionSpring,
        label = "progress_vertical_padding",
    )

    Surface(
        modifier =
            modifier.then(if (compact) Modifier.fillMaxWidth() else Modifier.fillMaxWidth(0.9f)),
        shape = RoundedCornerShape(cornerRadius),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
    ) {
        if (compact) {
            ProgressRowContent(
                copy = copy,
                spinnerSize = spinnerSize,
                horizontalPadding = horizontalPadding,
                verticalPadding = verticalPadding,
            )
        } else {
            ProgressColumnContent(
                copy = copy,
                spinnerSize = spinnerSize,
                horizontalPadding = horizontalPadding,
                verticalPadding = verticalPadding,
            )
        }
    }
}

@Composable
private fun ProgressColumnContent(
    copy: TranscriptionProgressCopy,
    spinnerSize: androidx.compose.ui.unit.Dp,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    verticalPadding: androidx.compose.ui.unit.Dp,
) {
    Column(
        modifier =
            Modifier.padding(
                horizontal = horizontalPadding,
                vertical = verticalPadding,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(spinnerSize))
        AnimatedProgressCopy(copy = copy, centered = true)
    }
}

@Composable
private fun ProgressRowContent(
    copy: TranscriptionProgressCopy,
    spinnerSize: androidx.compose.ui.unit.Dp,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    verticalPadding: androidx.compose.ui.unit.Dp,
) {
    Row(
        modifier =
            Modifier.padding(
                horizontal = horizontalPadding,
                vertical = verticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(spinnerSize),
            strokeWidth = 2.dp,
        )
        AnimatedProgressCopy(copy = copy, centered = false)
    }
}

@Composable
private fun AnimatedProgressCopy(
    copy: TranscriptionProgressCopy,
    centered: Boolean,
) {
    AnimatedContent(
        targetState = copy,
        transitionSpec = { ChirpMotion.studioContentCrossfade },
        contentKey = { it.title },
        label = "progress_copy",
    ) { currentCopy ->
        Column(
            modifier = if (centered) Modifier.fillMaxWidth() else Modifier,
            verticalArrangement = Arrangement.spacedBy(if (centered) 4.dp else 2.dp),
            horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            Text(
                text = currentCopy.title,
                style = if (centered) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            )
            Text(
                text = currentCopy.subtitle,
                style = if (centered) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
            )
        }
    }
}

@Composable
internal fun RecordingStatus?.transcriptionProgressCopy(): TranscriptionProgressCopy? =
    when (transcriptionProgressKind()) {
        TranscriptionProgressKind.Finalizing ->
            TranscriptionProgressCopy(
                title = stringResource(R.string.rec_recording_finalize_title),
                subtitle = stringResource(R.string.rec_recording_finalize_subtitle),
            )

        TranscriptionProgressKind.Transcribing ->
            TranscriptionProgressCopy(
                title = stringResource(R.string.rec_transcription_progress_title),
                subtitle = stringResource(R.string.rec_transcription_progress_subtitle),
            )

        TranscriptionProgressKind.Enhancing ->
            TranscriptionProgressCopy(
                title = stringResource(R.string.rec_enhancement_progress_title),
                subtitle = stringResource(R.string.rec_enhancement_progress_subtitle),
            )

        null -> null
    }

internal fun RecordingStatus?.transcriptionProgressKind(): TranscriptionProgressKind? =
    when (this) {
        RecordingStatus.RECORDING -> TranscriptionProgressKind.Finalizing

        RecordingStatus.PENDING_TRANSCRIPTION,
        RecordingStatus.TRANSCRIBING,
        -> TranscriptionProgressKind.Transcribing

        RecordingStatus.ENHANCING,
        RecordingStatus.PENDING_ENHANCEMENT,
        -> TranscriptionProgressKind.Enhancing

        else -> null
    }
