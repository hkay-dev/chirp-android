package dev.chirpboard.app.core.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.ui.R
import dev.chirpboard.app.core.ui.motion.ChirpMotion
import dev.chirpboard.app.core.recording.RecordingStatus

data class TranscriptionProgressCopy(
    val title: String,
    val subtitle: String,
)

enum class TranscriptionProgressKind {
    Finalizing,
    Queued,
    Transcribing,
    Enhancing,
}

@Composable
fun TranscriptionProgressBanner(
    copy: TranscriptionProgressCopy,
    kind: TranscriptionProgressKind? = null,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ProgressLeadingIndicator(kind = kind, leadingIcon = leadingIcon, spinnerSize = 22.dp)
            AnimatedProgressCopy(copy = copy)
        }
    }
}

@Composable
private fun ProgressLeadingIndicator(
    kind: TranscriptionProgressKind?,
    spinnerSize: Dp,
    leadingIcon: ImageVector? = null,
) {
    if (leadingIcon != null) {
        Icon(
            imageVector = leadingIcon,
            contentDescription = null,
            modifier = Modifier.size(spinnerSize),
            tint = MaterialTheme.colorScheme.primary,
        )
        return
    }
    if (kind == null) {
        CircularProgressIndicator(
            modifier = Modifier.size(spinnerSize),
            strokeWidth = 2.dp,
        )
        return
    }
    AnimatedContent(
        targetState = kind,
        transitionSpec = { ChirpMotion.studioContentCrossfade },
        label = "progress_phase_icon",
    ) { phase ->
        when (phase) {
            TranscriptionProgressKind.Finalizing ->
                Icon(
                    imageVector = Icons.Outlined.GraphicEq,
                    contentDescription = null,
                    modifier = Modifier.size(spinnerSize),
                    tint = MaterialTheme.colorScheme.primary,
                )

            TranscriptionProgressKind.Queued ->
                Icon(
                    imageVector = Icons.Outlined.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(spinnerSize),
                    tint = MaterialTheme.colorScheme.primary,
                )

            TranscriptionProgressKind.Transcribing ->
                Icon(
                    imageVector = Icons.Outlined.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(spinnerSize),
                    tint = MaterialTheme.colorScheme.primary,
                )

            TranscriptionProgressKind.Enhancing ->
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(spinnerSize),
                    tint = MaterialTheme.colorScheme.primary,
                )
        }
    }
}

@Composable
private fun AnimatedProgressCopy(copy: TranscriptionProgressCopy) {
    AnimatedContent(
        targetState = copy,
        transitionSpec = { ChirpMotion.studioContentCrossfade },
        contentKey = { "${it.title}\u0000${it.subtitle}" },
        label = "progress_copy",
    ) { currentCopy ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = currentCopy.title,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = currentCopy.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun RecordingStatus?.transcriptionProgressCopy(): TranscriptionProgressCopy? =
    when (transcriptionProgressKind()) {
        TranscriptionProgressKind.Finalizing ->
            TranscriptionProgressCopy(
                title = stringResource(R.string.rec_recording_finalize_title),
                subtitle = stringResource(R.string.rec_recording_finalize_subtitle),
            )

        TranscriptionProgressKind.Queued ->
            when (this) {
                RecordingStatus.PENDING_ENHANCEMENT ->
                    TranscriptionProgressCopy(
                        title = stringResource(R.string.rec_enhancement_queued_title),
                        subtitle = stringResource(R.string.rec_enhancement_queued_subtitle),
                    )

                else ->
                    TranscriptionProgressCopy(
                        title = stringResource(R.string.rec_transcription_queued_title),
                        subtitle = stringResource(R.string.rec_transcription_queued_subtitle),
                    )
            }

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

fun RecordingStatus?.transcriptionProgressKind(): TranscriptionProgressKind? =
    when (this) {
        RecordingStatus.RECORDING -> TranscriptionProgressKind.Finalizing

        RecordingStatus.PENDING_TRANSCRIPTION,
        RecordingStatus.PENDING_ENHANCEMENT,
        -> TranscriptionProgressKind.Queued

        RecordingStatus.TRANSCRIBING -> TranscriptionProgressKind.Transcribing

        RecordingStatus.ENHANCING -> TranscriptionProgressKind.Enhancing

        else -> null
    }
