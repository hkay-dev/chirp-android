package dev.chirpboard.app.feature.obsidian.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chirpboard.app.feature.obsidian.ObsidianManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        val isLoading: Boolean = true
    )
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                preferences.globalVaultUri,
                preferences.autoExportEnabled
            ) { vaultUri, autoExport ->
                // SAF operations (DocumentFile.fromTreeUri, canRead, canWrite) are I/O
                // and handled via flowOn(Dispatchers.IO) below
                val uri = vaultUri?.let { Uri.parse(it) }
                val hasAccess = uri?.let { obsidianManager.hasVaultAccess(it) } ?: false
                val vaultName = uri?.let { obsidianManager.getVaultDisplayName(it) }

                UiState(
                    vaultUri = vaultUri,
                    vaultName = vaultName,
                    autoExportEnabled = autoExport,
                    hasAccess = hasAccess,
                    isLoading = false
                )
            }
            .flowOn(Dispatchers.IO)
            .collect { state ->
                _uiState.value = state
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
            preferences.setGlobalVaultUri(uri.toString())
        }
    }

    /**
     * Clear the configured vault.
     */
    fun clearVault() {
        viewModelScope.launch {
            preferences.setGlobalVaultUri(null)
            // Also disable auto-export when vault is cleared
            preferences.setAutoExportEnabled(false)
        }
    }

    /**
     * Toggle auto-export on/off.
     */
    fun toggleAutoExport() {
        viewModelScope.launch {
            val currentValue = _uiState.value.autoExportEnabled
            preferences.setAutoExportEnabled(!currentValue)
        }
    }

    /**
     * Refresh the access status for the current vault.
     * Useful when returning to the settings screen.
     */
    fun refreshAccessStatus() {
        val currentUri = _uiState.value.vaultUri?.let { Uri.parse(it) } ?: return
        viewModelScope.launch {
            val hasAccess = withContext(Dispatchers.IO) {
                obsidianManager.hasVaultAccess(currentUri)
            }
            _uiState.update { it.copy(hasAccess = hasAccess) }
        }
    }
}
