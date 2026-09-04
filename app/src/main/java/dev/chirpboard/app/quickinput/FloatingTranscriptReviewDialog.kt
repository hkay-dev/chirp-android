package dev.chirpboard.app.quickinput

import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.chirpboard.app.R
import dev.chirpboard.app.core.llm.ProcessingModeDefaults
import dev.chirpboard.app.core.llm.ProcessingModeListItem
import dev.chirpboard.app.core.ui.components.AnimatedAlertDialog

/** Compact editor shown between floating-mic recognition and the clipboard write. */
@Composable
internal fun FloatingTranscriptReviewDialog(
    value: TextFieldValue,
    copyStarted: Boolean,
    initialCopySucceeded: Boolean = false,
    selectableModes: List<ProcessingModeListItem> = emptyList(),
    selectedModeId: String = ProcessingModeDefaults.DEFAULT_MODE_ID,
    aiProcessing: Boolean = false,
    aiFailed: Boolean = false,
    onValueChange: (TextFieldValue) -> Unit,
    onModeSelected: (String) -> Unit = {},
    onApplyPreset: (String) -> Unit = {},
    onCopy: () -> Unit,
    onCancel: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val touchExplorationEnabled =
        remember(context) {
            context
                .getSystemService(AccessibilityManager::class.java)
                ?.isTouchExplorationEnabled == true
        }
    val modes =
        selectableModes.ifEmpty {
            ProcessingModeDefaults.builtInSelectableIds.map { modeId ->
                ProcessingModeListItem(modeId, ProcessingModeDefaults.displayName(modeId))
            }
        }
    val selectedMode = modes.firstOrNull { it.id == selectedModeId } ?: modes.first()
    var modeMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!touchExplorationEnabled) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    AnimatedAlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.widthIn(max = 560.dp).imePadding(),
        title = {
            Text(
                text = stringResource(R.string.floating_mic_review_title),
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text =
                        stringResource(
                            if (initialCopySucceeded) {
                                R.string.floating_mic_review_description
                            } else {
                                R.string.floating_mic_review_copy_needed_description
                            },
                        ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 96.dp, max = 180.dp)
                            .focusRequester(focusRequester),
                    label = { Text(stringResource(R.string.floating_mic_review_field_label)) },
                    supportingText =
                        if (value.text.isBlank()) {
                            { Text(stringResource(R.string.floating_mic_review_blank)) }
                        } else {
                            null
                        },
                    isError = value.text.isBlank(),
                    enabled = !copyStarted && !aiProcessing,
                    minLines = 3,
                    maxLines = 8,
                    keyboardOptions =
                        KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                )

                Spacer(modifier = Modifier.size(12.dp))
                Text(
                    text = stringResource(R.string.floating_mic_review_ai_title),
                    style = MaterialTheme.typography.labelLarge,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { modeMenuExpanded = true },
                            enabled = !copyStarted && !aiProcessing,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = selectedMode.name,
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                imageVector = Icons.Rounded.ArrowDropDown,
                                contentDescription = null,
                            )
                        }
                        DropdownMenu(
                            expanded = modeMenuExpanded,
                            onDismissRequest = { modeMenuExpanded = false },
                        ) {
                            modes.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.name) },
                                    onClick = {
                                        onModeSelected(mode.id)
                                        modeMenuExpanded = false
                                    },
                                    leadingIcon =
                                        if (mode.id == selectedMode.id) {
                                            { Icon(Icons.Rounded.Check, contentDescription = null) }
                                        } else {
                                            null
                                        },
                                )
                            }
                        }
                    }
                    FilledTonalButton(
                        onClick = { onApplyPreset(selectedMode.id) },
                        enabled = value.text.isNotBlank() && !copyStarted && !aiProcessing,
                    ) {
                        if (aiProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                            )
                        }
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(
                            stringResource(
                                if (aiProcessing) {
                                    R.string.floating_mic_review_ai_running
                                } else {
                                    R.string.floating_mic_review_ai_apply
                                },
                            ),
                        )
                    }
                }
                if (aiFailed) {
                    Text(
                        text = stringResource(R.string.floating_mic_review_ai_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier =
                            Modifier
                                .padding(top = 6.dp)
                                .semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onCopy,
                enabled = value.text.isNotBlank() && !copyStarted && !aiProcessing,
            ) {
                Text(stringResource(R.string.floating_mic_review_copy))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancel,
                enabled = !copyStarted,
            ) {
                Text(stringResource(R.string.floating_mic_review_cancel))
            }
        },
    )
}
