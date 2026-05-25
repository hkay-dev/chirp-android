package dev.chirpboard.app.feature.studio.tabs

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import dev.chirpboard.app.core.ui.components.AnimatedTranscriptionProgress as CoreAnimatedTranscriptionProgress
import dev.chirpboard.app.core.ui.components.MorphingTranscriptionProgress as CoreMorphingTranscriptionProgress
import dev.chirpboard.app.core.ui.components.TranscriptionProgressBanner as CoreTranscriptionProgressBanner
import dev.chirpboard.app.core.ui.components.TranscriptionProgressCopy as CoreTranscriptionProgressCopy
import dev.chirpboard.app.core.ui.components.TranscriptionProgressKind as CoreTranscriptionProgressKind
import dev.chirpboard.app.core.ui.components.TranscriptionProgressPanel as CoreTranscriptionProgressPanel
import dev.chirpboard.app.core.ui.components.progressEnterTransition as coreProgressEnterTransition
import dev.chirpboard.app.core.ui.components.progressExitTransition as coreProgressExitTransition
import dev.chirpboard.app.core.ui.components.transcriptionProgressCopy as coreTranscriptionProgressCopy
import dev.chirpboard.app.core.ui.components.transcriptionProgressKind as coreTranscriptionProgressKind
import dev.chirpboard.app.data.model.RecordingStatus

internal typealias TranscriptionProgressCopy = CoreTranscriptionProgressCopy
internal typealias TranscriptionProgressKind = CoreTranscriptionProgressKind

internal val progressEnterTransition = coreProgressEnterTransition
internal val progressExitTransition = coreProgressExitTransition

@Composable
internal fun TranscriptionProgressPanel(
    copy: TranscriptionProgressCopy,
    kind: TranscriptionProgressKind? = null,
    modifier: Modifier = Modifier,
) {
    CoreTranscriptionProgressPanel(copy = copy, kind = kind, modifier = modifier)
}

@Composable
internal fun TranscriptionProgressBanner(
    copy: TranscriptionProgressCopy,
    modifier: Modifier = Modifier,
) {
    CoreTranscriptionProgressBanner(copy = copy, modifier = modifier)
}

@Composable
internal fun AnimatedTranscriptionProgress(
    visible: Boolean,
    compact: Boolean,
    copy: TranscriptionProgressCopy?,
    modifier: Modifier = Modifier,
    fullscreen: Boolean = false,
) {
    CoreAnimatedTranscriptionProgress(
        visible = visible,
        compact = compact,
        copy = copy,
        modifier = modifier,
        fullscreen = fullscreen,
    )
}

@Composable
internal fun MorphingTranscriptionProgress(
    compact: Boolean,
    copy: TranscriptionProgressCopy,
    kind: TranscriptionProgressKind? = null,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
) {
    CoreMorphingTranscriptionProgress(
        compact = compact,
        copy = copy,
        kind = kind,
        modifier = modifier,
        leadingIcon = leadingIcon,
    )
}

@Composable
internal fun RecordingStatus?.transcriptionProgressCopy(): TranscriptionProgressCopy? = coreTranscriptionProgressCopy()

internal fun RecordingStatus?.transcriptionProgressKind(): TranscriptionProgressKind? = coreTranscriptionProgressKind()
