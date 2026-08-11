package dev.chirpboard.app.feature.obsidian.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chirpboard.app.feature.obsidian.ObsidianManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Obsidian settings screen.
 */
@HiltViewModel
class ObsidianSettingsViewModel @Inject constructor(
    private val preferences: ObsidianPreferences,
    private val obsidianManager: ObsidianManager
) : ViewModel() {

    /**
     * UI state for the Obsidian settings screen.
     */
@Stable
    data class UiState(
        /** The stored vault URI as a string, or null if not configured */
        val vaultUri: String? = null,
        /** Display-friendly name of the vault folder */
        val vaultName: String? = null,
        /** Whether auto-export is enabled */
        val autoExportEnabled: Boolean = false,
        /** Whether we currently have SAF access to the vault */
        val hasAccess: Boolean = false,
        /** Whether the initial data has loaded */
        val isLoading: Boolean = true,
        /** The last vault pick couldn't be kept (the provider refused a persistable grant) */
        val vaultSelectionFailed: Boolean = false
    )
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                preferences.globalVaultUri,
                preferences.autoExportEnabled,
            ) { vaultUri, autoExport ->
                val uri = vaultUri?.let { Uri.parse(it) }
                val hasAccess = uri?.let { currentUri -> obsidianManager.hasVaultAccess(currentUri) } ?: false
                val vaultName = uri?.let { currentUri -> obsidianManager.getVaultDisplayName(currentUri) }

                UiState(
                    vaultUri = vaultUri,
                    vaultName = vaultName,
                    autoExportEnabled = autoExport,
                    hasAccess = hasAccess,
                    isLoading = false,
                )
            }.collect { state ->
                // Preserve transient flags that don't derive from preferences.
                _uiState.update { state.copy(vaultSelectionFailed = it.vaultSelectionFailed) }
            }
        }
    }

    /**
     * Set the vault URI from a SAF folder picker result.
     *
     * @param uri The URI from the folder picker
     */
    fun setVaultUri(uri: Uri) {
        viewModelScope.launch {
            // Read the previous vault from preferences, not UI state: during the initial
            // load window the state hasn't caught up yet and its grant would leak.
            val previous = preferences.globalVaultUri.first()?.let { Uri.parse(it) }
            // Persist the grant before storing the URI: a vault we can't reopen after a
            // restart must never be saved as configured.
            if (!obsidianManager.takeVaultPermission(uri)) {
                _uiState.update { it.copy(vaultSelectionFailed = true) }
                return@launch
            }
            _uiState.update { it.copy(vaultSelectionFailed = false) }
            preferences.setGlobalVaultUri(uri.toString())
            // Give back the replaced vault's grant only after the new one is stored:
            // Android caps persisted URI grants per app, so stale grants must not
            // accumulate — but releasing first would leave the app with no working vault
            // if the process died in between.
            if (previous != null && previous != uri) {
                obsidianManager.releaseVaultPermission(previous)
            }
        }
    }

    /**
     * Clear the configured vault.
     */
    fun clearVault() {
        viewModelScope.launch {
            val current = preferences.globalVaultUri.first()?.let { Uri.parse(it) }
            preferences.setGlobalVaultUri(null)
            // Also disable auto-export when vault is cleared
            preferences.setAutoExportEnabled(false)
            _uiState.update { it.copy(vaultSelectionFailed = false) }
            current?.let { obsidianManager.releaseVaultPermission(it) }
        }
    }

    /**
     * Set auto-export on/off. Takes the value the switch moved to rather than re-reading and
     * inverting the stored one: two fast taps would otherwise both read the pre-tap value and
     * write the same result, leaving the setting on when the user meant to end up back off.
     */
    fun setAutoExport(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setAutoExportEnabled(enabled)
        }
    }

    /**
     * Refresh the access status for the current vault.
     * Useful when returning to the settings screen.
     */
    fun refreshAccessStatus() {
        val currentUri = _uiState.value.vaultUri?.let { Uri.parse(it) } ?: return
        viewModelScope.launch {
            val hasAccess = obsidianManager.hasVaultAccess(currentUri)
            _uiState.update { it.copy(hasAccess = hasAccess) }
        }
    }
}
