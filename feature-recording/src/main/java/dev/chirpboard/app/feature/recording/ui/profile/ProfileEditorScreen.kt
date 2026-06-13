package dev.chirpboard.app.feature.recording.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.core.ui.components.ChirpPrimaryExtendedFab
import dev.chirpboard.app.core.ui.components.ChirpSettingsDetailScaffold
import dev.chirpboard.app.core.ui.components.RepositoryErrorSnackbarEffect
import dev.chirpboard.app.core.ui.components.SettingsDropdownListItem
import dev.chirpboard.app.core.ui.components.SettingsSectionHeader
import dev.chirpboard.app.core.ui.components.SettingsSwitchItem
import dev.chirpboard.app.core.ui.theme.ChirpSpacing
import dev.chirpboard.app.core.ui.R as CoreR
import dev.chirpboard.app.feature.recording.R

private val ProfileProcessingModeIds = listOf(null, "enhance", "summarize", "meeting_notes", "action_items")

/**
 * Maps a persisted processing-mode id to its localized, human-friendly label. Shared by the
 * editor dropdown and the [ProfileCard] chip so the list and editor never diverge.
 */
@Composable
internal fun profileProcessingModeLabel(modeId: String?): String =
    when (modeId) {
        null -> stringResource(R.string.rec_profile_mode_none)
        "enhance" -> stringResource(R.string.rec_profile_mode_enhance)
        "summarize" -> stringResource(R.string.rec_profile_mode_summarize)
        "meeting_notes" -> stringResource(R.string.rec_profile_mode_meeting_notes)
        "action_items" -> stringResource(R.string.rec_profile_mode_action_items)
        else -> stringResource(R.string.rec_profile_mode_none)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorScreen(
    viewModel: ProfileEditorViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Navigate back when saved
    LaunchedEffect(uiState.isSaved) {
        if (uiState.isSaved) {
            onSaved()
        }
    }

    // Show error messages
    RepositoryErrorSnackbarEffect(
        errorMessage = uiState.error,
        snackbarHostState = snackbarHostState,
        onDismiss = viewModel::clearError,
    )

    val canSave = !uiState.isLoading && uiState.name.isNotBlank()

    ChirpSettingsDetailScaffold(
        title = if (viewModel.isEditing) stringResource(R.string.rec_edit_profile) else stringResource(R.string.rec_new_profile),
        onNavigateBack = onNavigateBack,
        snackbarHostState = snackbarHostState,
        floatingActionButton = {
            if (canSave) {
                ChirpPrimaryExtendedFab(
                    onClick = { viewModel.save() },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                        )
                    },
                    text = { Text(stringResource(CoreR.string.rec_save)) },
                )
            }
        },
    ) { paddingValues ->
        if (uiState.isLoading && viewModel.isEditing && uiState.name.isEmpty()) {
            // Loading state for edit mode
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .verticalScroll(rememberScrollState()),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = ChirpSpacing.ScreenHorizontal),
                    verticalArrangement = Arrangement.spacedBy(ChirpSpacing.Large),
                ) {
                    // Name field. I18N-18: "Required" is supporting text instead of a "*" baked
                    // into the label, so TalkBack no longer reads "Name star".
                    OutlinedTextField(
                        value = uiState.name,
                        onValueChange = { viewModel.updateName(it) },
                        label = { Text(stringResource(R.string.rec_profile_name)) },
                        placeholder = { Text(stringResource(R.string.rec_profile_name_placeholder)) },
                        supportingText = { Text(stringResource(R.string.rec_profile_name_required)) },
                        singleLine = true,
                        keyboardOptions =
                            KeyboardOptions(
                                capitalization = KeyboardCapitalization.Words,
                                imeAction = ImeAction.Next,
                            ),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    // Icon field
                    OutlinedTextField(
                        value = uiState.icon,
                        onValueChange = { viewModel.updateIcon(it) },
                        label = { Text(stringResource(R.string.rec_profile_icon)) },
                        placeholder = { Text(stringResource(R.string.rec_profile_icon_placeholder)) },
                        singleLine = true,
                        keyboardOptions =
                            KeyboardOptions(
                                imeAction = ImeAction.Next,
                            ),
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text(stringResource(R.string.rec_profile_icon_desc)) },
                    )
                }

                // PROP-9: pinning is a per-profile automation toggle; it renders as a shared flat
                // switch row (its own padding) rather than inside the padded text-field column.
                SettingsSwitchItem(
                    icon = Icons.Default.PushPin,
                    title = stringResource(R.string.rec_profile_quick_start_title),
                    subtitle = stringResource(R.string.rec_profile_quick_start_description),
                    checked = uiState.quickStartPinned,
                    onCheckedChange = { viewModel.updateQuickStartPinned(it) },
                )

                SettingsSectionHeader(title = stringResource(R.string.rec_profile_automation_settings))

                SettingsSwitchItem(
                    icon = Icons.Default.RecordVoiceOver,
                    title = stringResource(R.string.rec_profile_auto_transcribe_title),
                    subtitle = stringResource(R.string.rec_profile_auto_transcribe_description),
                    checked = uiState.autoTranscribe,
                    onCheckedChange = { viewModel.updateAutoTranscribe(it) },
                )

                SettingsSwitchItem(
                    icon = Icons.Default.Title,
                    title = stringResource(R.string.rec_profile_auto_title_title),
                    subtitle = stringResource(R.string.rec_profile_auto_title_description),
                    checked = uiState.autoTitle,
                    onCheckedChange = { viewModel.updateAutoTitle(it) },
                )

                SettingsSwitchItem(
                    icon = Icons.Default.Summarize,
                    title = stringResource(R.string.rec_profile_auto_summary_title),
                    subtitle = stringResource(R.string.rec_profile_auto_summary_description),
                    checked = uiState.autoSummary,
                    onCheckedChange = { viewModel.updateAutoSummary(it) },
                )

                SettingsSectionHeader(title = stringResource(R.string.rec_profile_processing_section))

                // Processing mode dropdown
                SettingsDropdownListItem(
                    title = stringResource(R.string.rec_profile_mode),
                    supportingText = stringResource(R.string.rec_profile_mode_desc),
                    options = ProfileProcessingModeIds,
                    selectedOption = uiState.defaultProcessingMode,
                    optionLabel = { mode -> profileProcessingModeLabel(mode) },
                    onOptionSelected = { viewModel.updateDefaultProcessingMode(it) },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )

                SettingsSectionHeader(title = stringResource(R.string.rec_profile_obsidian_integration))

                SettingsSwitchItem(
                    icon = Icons.Default.Upload,
                    title = stringResource(R.string.rec_profile_auto_export_title),
                    subtitle = stringResource(R.string.rec_profile_auto_export_description),
                    checked = uiState.autoExportToObsidian,
                    onCheckedChange = { viewModel.updateAutoExportToObsidian(it) },
                )

                // PLH-5: the free-text vault path override was removed — exports go through
                // SAF, so a typed filesystem path can never be used; the export destination
                // is the vault chosen in Obsidian settings.

                Spacer(modifier = Modifier.height(ChirpSpacing.MiniPlayerClearance))
            }
        }
    }
}
