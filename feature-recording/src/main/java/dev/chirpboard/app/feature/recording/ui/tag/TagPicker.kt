package dev.chirpboard.app.feature.recording.ui.tag

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.ui.theme.ChirpShapes
import dev.chirpboard.app.data.entity.Tag
import java.util.UUID

/**
 * Component for selecting tags on a recording.
 *
 * @param availableTags All available tags to choose from
 * @param selectedTagIds Set of currently selected tag IDs
 * @param onTagToggle Called when a tag is toggled on/off
 * @param onCreateTag Called with the name when a new tag is created
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagPicker(
    availableTags: List<Tag>,
    selectedTagIds: Set<UUID>,
    onTagToggle: (UUID) -> Unit,
    onCreateTag: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCreateDialog by remember { mutableStateOf(false) }

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        availableTags.forEach { tag ->
            TagChip(
                tag = tag,
                selected = tag.id in selectedTagIds,
                onClick = { onTagToggle(tag.id) },
            )
        }

        // Add tag chip
        AddTagChip(onClick = { showCreateDialog = true })
    }

    if (showCreateDialog) {
        TagEditorDialog(
            tag = null,
            onDismiss = { showCreateDialog = false },
            onSave = { name, _ ->
                onCreateTag(name)
                showCreateDialog = false
            },
        )
    }
}

@Composable
private fun AddTagChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = ChirpShapes.Large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 4.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Add tag",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
