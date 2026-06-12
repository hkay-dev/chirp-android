package dev.chirpboard.app.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.ui.R
import dev.chirpboard.app.core.recording.RecordingSource
import dev.chirpboard.app.core.util.formatAsDuration
import dev.chirpboard.app.core.util.formatRelative
import java.util.Date

/**
 * Per-recording metadata pills: relative date, duration, and source.
 *
 * Complements [StatsPillRow], which shows aggregate home-screen stats (count, total duration,
 * processing filter). Use this component for individual recording surfaces such as the home list
 * and studio header. Both rows render the shared [ChirpPill] capsule so all home pills are
 * visually consistent (VIS-1 / VIS-3).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetadataPillRow(
    durationMs: Long,
    source: RecordingSource,
    createdAtMs: Long? = null,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        // A11Y-10: name each pill's role so TalkBack hears "Duration, 03:42" instead of a bare
        // value, matching the descriptions StatsPillRow already passes.
        createdAtMs?.let { createdAt ->
            ChirpPill(
                label = remember(createdAt) { Date(createdAt).formatRelative() },
                icon = Icons.Filled.Schedule,
                contentDescription = stringResource(R.string.rec_pill_recorded),
            )
        }

        ChirpPill(
            label = durationMs.formatAsDuration(),
            icon = Icons.Filled.Timer,
            contentDescription = stringResource(R.string.rec_pill_duration),
        )

        ChirpPill(
            label = source.label(),
            icon = source.icon(),
            contentDescription = stringResource(R.string.rec_pill_source),
        )
    }
}
