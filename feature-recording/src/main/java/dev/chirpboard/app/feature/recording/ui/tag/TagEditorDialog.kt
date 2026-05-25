package dev.chirpboard.app.feature.recording.ui.tag

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.ui.components.AnimatedAlertDialog
import dev.chirpboard.app.data.entity.Tag
import dev.chirpboard.app.core.ui.R as CoreR
import dev.chirpboard.app.feature.recording.R

/**
 * Preset colors for tag color picker.
 */
val TagColorPresets =
    listOf(
        "#F44336",
        "#E91E63",
        "#9C27B0",
        "#673AB7",
        "#3F51B5",
        "#2196F3",
        "#03A9F4",
        "#00BCD4",
        "#009688",
        "#4CAF50",
        "#8BC34A",
        "#CDDC39",
        "#FFC107",
        "#FF9800",
        "#FF5722",
    )

/**
 * Dialog for creating or editing a tag.
 *
 * @param tag Existing tag to edit, or null to create a new tag
 * @param onDismiss Called when dialog is dismissed
 * @param onSave Called with the tag name and color when saved
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagEditorDialog(
    tag: Tag? = null,
    onDismiss: () -> Unit,
    onSave: (name: String, color: String?) -> Unit,
) {
    var name by remember { mutableStateOf(tag?.name ?: "") }
    var selectedColor by remember { mutableStateOf(tag?.color) }

    val isEditing = tag != null
    val title = if (isEditing) stringResource(R.string.rec_edit_tag_title) else stringResource(R.string.rec_create_tag_title)

    AnimatedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.rec_tag_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.rec_color),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(8.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TagColorPresets.forEach { colorHex ->
                        ColorCircle(
                            colorHex = colorHex,
                            isSelected = selectedColor == colorHex,
                            onClick = {
                                selectedColor = if (selectedColor == colorHex) null else colorHex
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank()) {
                        onSave(name.trim(), selectedColor)
                    }
                },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(CoreR.string.rec_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(CoreR.string.rec_cancel))
            }
        },
    )
}

@Composable
private fun ColorCircle(
    colorHex: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = parseColor(colorHex, MaterialTheme.colorScheme.outline)
    val checkColor = if (color.luminance() > 0.5f) Color.Black else Color.White

    Box(
        modifier =
            modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color)
                    .then(
                        if (isSelected) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        } else {
                            Modifier
                        },
                    ),
            contentAlignment = Alignment.Center,
        ) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.desc_selected),
                    tint = checkColor,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private fun parseColor(
    hexColor: String,
    fallbackColor: Color,
): Color =
    try {
        Color(android.graphics.Color.parseColor(hexColor))
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        fallbackColor
    }
