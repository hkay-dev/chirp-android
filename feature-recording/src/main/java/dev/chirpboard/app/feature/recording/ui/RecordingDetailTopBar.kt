package dev.chirpboard.app.feature.recording.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.chirpboard.app.core.R as CoreR
import dev.chirpboard.app.feature.recording.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordingDetailTopBar(
    displayTitle: String,
    dateTimeText: String,
    isEditing: Boolean,
    editedTitle: String,
    collapsedFractionProvider: () -> Float,
    onEditedTitleChange: (String) -> Unit,
    onCancelEditing: () -> Unit,
    onSaveTitle: () -> Unit,
    onStartEditing: () -> Unit,
    onDeleteRequested: () -> Unit,
    onBackClick: () -> Unit,
) {
    var showOverflowMenu by remember { mutableStateOf(false) }

    LargeTopAppBar(
        title = {
            if (isEditing) {
                TextField(
                    value = editedTitle,
                    onValueChange = onEditedTitleChange,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                )
            } else {
                Column {
                    Text(
                        text = displayTitle,
                        maxLines = if (collapsedFractionProvider() > 0.5f) 1 else 3,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Bold,
                    )
                    if (collapsedFractionProvider() < 0.5f) {
                        Text(
                            text = dateTimeText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(CoreR.string.desc_back),
                )
            }
        },
        actions = {
            if (isEditing) {
                IconButton(onClick = onCancelEditing) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(CoreR.string.desc_cancel))
                }
                IconButton(onClick = onSaveTitle) {
                    Icon(Icons.Default.Check, contentDescription = stringResource(CoreR.string.desc_save))
                }
            } else {
                Box {
                    IconButton(onClick = { showOverflowMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(CoreR.string.desc_more_options))
                    }

                    DropdownMenu(
                        expanded = showOverflowMenu,
                        onDismissRequest = { showOverflowMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.rec_edit_title)) },
                            onClick = {
                                showOverflowMenu = false
                                onStartEditing()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Edit, contentDescription = null)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(CoreR.string.rec_delete)) },
                            onClick = {
                                showOverflowMenu = false
                                onDeleteRequested()
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                        )
                    }
                }
            }
        },
    )
}
