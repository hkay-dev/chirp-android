package dev.chirpboard.app.feature.recording.ui.tag

import androidx.compose.runtime.remember
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import dev.chirpboard.app.feature.recording.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.ui.theme.ChirpShapes
import dev.chirpboard.app.data.entity.Tag

/**
 * Reusable tag display component.
 *
 * @param tag The tag to display
 * @param selected Whether the chip is in selected state (filled vs outlined)
 * @param onClick Optional click handler for the whole chip
 * @param onRemove Optional handler for remove (X) button
 */
@Composable
fun TagChip(
    tag: Tag,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val defaultColor = MaterialTheme.colorScheme.primary
    val tagColor = remember(tag.color, defaultColor) {
        tag.color?.let { parseColor(it) } ?: defaultColor
    }
    val backgroundColor by animateColorAsState(
        targetValue = if (selected) tagColor else Color.Transparent,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "tag_background",
    )
    val contentColor by animateColorAsState(
        targetValue =
            if (selected) {
                if (tagColor.luminance() > 0.5f) Color.Black else Color.White
            } else {
                tagColor
            },
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "tag_content",
    )

    val shape = ChirpShapes.Large

    Box(
        modifier =
            modifier
                .clip(shape)
                .then(
                    if (selected) {
                        Modifier.background(backgroundColor)
                    } else {
                        Modifier.border(1.dp, tagColor, shape)
                    },
                ).then(
                    if (onClick != null) {
                        Modifier.clickable(onClick = onClick)
                    } else {
                        Modifier
                    },
                ).padding(
                    start = 12.dp,
                    end = if (onRemove != null) 4.dp else 12.dp,
                    top = 6.dp,
                    bottom = 6.dp,
                ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = tag.name,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
            )

            if (onRemove != null) {
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(20.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.desc_remove_tag),
                        tint = contentColor,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

/**
 * Parse a hex color string to a Compose Color.
 */
private fun parseColor(hexColor: String): Color =
    try {
        Color(android.graphics.Color.parseColor(hexColor))
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Color.Gray
    }
