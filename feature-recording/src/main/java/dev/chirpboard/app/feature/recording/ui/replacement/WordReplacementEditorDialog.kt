package dev.chirpboard.app.feature.recording.ui.replacement

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.res.stringResource
import dev.chirpboard.app.feature.recording.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.core.ui.components.AnimatedAlertDialog
import dev.chirpboard.app.data.entity.WordReplacement

@Composable
fun WordReplacementEditorDialog(
    replacement: WordReplacement? = null,
    onDismiss: () -> Unit,
    onSave: (original: String, replacement: String, caseSensitive: Boolean) -> Unit,
) {
    var original by remember { mutableStateOf(replacement?.original ?: "") }
    var replacementText by remember { mutableStateOf(replacement?.replacement ?: "") }
    var caseSensitive by remember { mutableStateOf(replacement?.caseSensitive ?: false) }

    val isEditing = replacement != null
    val title = if (isEditing) "Edit Replacement" else "Add Replacement"
    val canSave = original.isNotBlank()

    AnimatedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = original,
                    onValueChange = { original = it },
                    label = { Text(stringResource(R.string.rec_original_word)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = {
                        Text(stringResource(R.string.rec_original_word_desc))
                    },
                )

                OutlinedTextField(
                    value = replacementText,
                    onValueChange = { replacementText = it },
                    label = { Text(stringResource(R.string.rec_replacement)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = {
                        Text(stringResource(R.string.rec_replacement_desc))
                    },
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = caseSensitive,
                        onCheckedChange = { caseSensitive = it },
                    )
                    Text(
                        text = "Case sensitive",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(original, replacementText, caseSensitive) },
                enabled = canSave,
            ) {
                Text(stringResource(R.string.rec_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.rec_cancel))
            }
        },
    )
}
