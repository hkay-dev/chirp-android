package dev.chirpboard.app.ui.settings
import androidx.compose.material3.ListItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.graphics.Color

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.TopAppBarDefaults
import dev.chirpboard.app.core.ui.components.SettingsSwitchItem
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Star
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chirpboard.app.R

private val KeyboardProcessingModeIds = listOf(null, "proofread", "formal", "casual", "email", "code", "smart")

@Composable
private fun keyboardProcessingModeLabel(modeId: String?): String =
    when (modeId) {
        null -> stringResource(R.string.keyboard_settings_mode_global)
        "proofread" -> stringResource(R.string.keyboard_settings_mode_proofread)
        "formal" -> stringResource(R.string.keyboard_settings_mode_formal)
        "casual" -> stringResource(R.string.keyboard_settings_mode_casual)
        "email" -> stringResource(R.string.keyboard_settings_mode_email)
        "code" -> stringResource(R.string.keyboard_settings_mode_code)
        "smart" -> stringResource(R.string.keyboard_settings_mode_smart)
        else -> stringResource(R.string.keyboard_settings_mode_global)
    }

/**
 * Settings screen for keyboard-specific options.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardSettingsScreen(
    viewModel: KeyboardSettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(dev.chirpboard.app.R.string.keyboard_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.desc_back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
        ) {

            // Save recordings toggle
            item {
                SettingsSwitchItem(
                    icon = Icons.Default.Mic,
                    title = stringResource(R.string.keyboard_settings_save_recordings_title),
                    subtitle = stringResource(R.string.keyboard_settings_save_recordings_description),
                    checked = uiState.saveKeyboardRecordings,
                    onCheckedChange = { viewModel.toggleSaveRecordings() }
                )
            }

            // LLM processing toggle
            item {
                SettingsSwitchItem(
                    icon = Icons.Default.Star,
                    title = stringResource(R.string.keyboard_settings_enable_llm_title),
                    subtitle = stringResource(R.string.keyboard_settings_enable_llm_description),
                    checked = uiState.llmEnabled,
                    onCheckedChange = { viewModel.toggleLlmEnabled() }
                )
            }

            // Processing mode dropdown
            item {
                ProcessingModeListItem(
                    currentMode = uiState.defaultProcessingMode,
                    enabled = uiState.llmEnabled,
                    onModeSelected = viewModel::setProcessingMode,
                )
            }

            // System keyboard settings link
            item {
                HorizontalDivider(modifier = Modifier.padding(start = 72.dp))
                ListItem(
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.keyboard_settings_system_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    supportingContent = {
                        Column {
                            Text(
                                text = stringResource(R.string.keyboard_settings_system_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(
                                    onClick = {
                                        try {
                                            context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                                        } catch (e: android.content.ActivityNotFoundException) {
                                            // Ignore
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 8.dp).size(18.dp)
                                    )
                                    Text(stringResource(dev.chirpboard.app.R.string.enable_keyboard))
                                }
                                FilledTonalButton(
                                    onClick = {
                                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                        imm.showInputMethodPicker()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Keyboard,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 8.dp).size(18.dp)
                                    )
                                    Text(stringResource(dev.chirpboard.app.R.string.select_keyboard))
                                }
                            }
                        }
                    },
                    leadingContent = {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(40.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                    shape = androidx.compose.foundation.shape.CircleShape,
                                ),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Keyboard,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}

@Composable
private fun ProcessingModeListItem(
    currentMode: String?,
    enabled: Boolean,
    onModeSelected: (String?) -> Unit,
) {
    var isDropdownExpanded by remember { mutableStateOf(false) }
    val currentModeName = keyboardProcessingModeLabel(currentMode)

    val textColor by animateColorAsState(
        targetValue = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "keyboard_text_color",
    )
    val iconTint by animateColorAsState(
        targetValue = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "keyboard_icon_tint",
    )

    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .clickable(enabled = enabled) { isDropdownExpanded = true },
        headlineContent = {
            Text(
                text = stringResource(R.string.keyboard_settings_processing_mode_title),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        leadingContent = {
            Box(Modifier.size(40.dp))
        },
        supportingContent = {
            Column {
                Text(
                    text = stringResource(R.string.keyboard_settings_processing_mode_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!enabled) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.keyboard_settings_processing_mode_disabled),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        trailingContent = {
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (enabled) 1f else 0.5f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = currentModeName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                    )
                    Icon(
                        imageVector = Icons.Default.ArrowDropDown,
                        contentDescription = stringResource(R.string.desc_select_mode),
                        tint = iconTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
                DropdownMenu(
                    expanded = isDropdownExpanded,
                    onDismissRequest = { isDropdownExpanded = false },
                ) {
                    KeyboardProcessingModeIds.forEach { modeId ->
                        DropdownMenuItem(
                            text = { Text(keyboardProcessingModeLabel(modeId)) },
                            onClick = {
                                onModeSelected(modeId)
                                isDropdownExpanded = false
                            },
                            trailingIcon = if (currentMode == modeId) {
                                { Icon(Icons.Default.Check, contentDescription = stringResource(R.string.desc_selected)) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
