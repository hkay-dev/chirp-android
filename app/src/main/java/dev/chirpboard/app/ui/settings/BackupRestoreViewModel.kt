package dev.chirpboard.app.ui.settings

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.R
import dev.chirpboard.app.backup.BackupApiKeysExportException
import dev.chirpboard.app.backup.BackupFormatException
import dev.chirpboard.app.backup.BackupImportMode
import dev.chirpboard.app.backup.BackupSection
import dev.chirpboard.app.backup.ChirpBackupContents
import dev.chirpboard.app.backup.ChirpBackupManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BackupRestoreViewModel
    @Inject
    constructor(
        // I18N: status/error copy comes from resources, never raw exception text.
        @ApplicationContext private val appContext: Context,
        private val backupManager: ChirpBackupManager,
    ) : ViewModel() {
        data class UiState(
            val counts: ChirpBackupManager.SectionCounts? = null,
            val exportSelection: Set<BackupSection> = emptySet(),
            val isExporting: Boolean = false,
            val exportMessage: StatusMessage? = null,
            val passphrasePrompt: PassphrasePromptMode? = null,
            val importState: ImportState = ImportState.Idle,
            val importMessage: StatusMessage? = null,
        )

        enum class PassphrasePromptMode {
            /** Choosing a passphrase to encrypt the exported keys (requires confirmation). */
            EXPORT,

            /** Entering the passphrase the backup's keys were encrypted with. */
            IMPORT,
        }

        sealed interface StatusMessage {
            val text: String

            data class Success(
                override val text: String,
            ) : StatusMessage

            data class Error(
                override val text: String,
            ) : StatusMessage
        }

        sealed interface ImportState {
            data object Idle : ImportState

            data object Inspecting : ImportState

            data class Ready(
                val contents: ChirpBackupContents,
                val selection: Set<BackupSection>,
                val mode: BackupImportMode,
                val showConfirmDialog: Boolean = false,
            ) : ImportState

            data object Applying : ImportState

            data class Complete(
                val summary: ChirpBackupManager.ImportSummary,
            ) : ImportState
        }

        sealed interface FilePickerRequest {
            data class CreateBackupFile(
                val suggestedName: String,
            ) : FilePickerRequest

            data object OpenBackupFile : FilePickerRequest
        }

        private val _uiState = MutableStateFlow(UiState())
        val uiState: StateFlow<UiState> = _uiState.asStateFlow()

        private val _filePickerRequests = Channel<FilePickerRequest>(Channel.BUFFERED)
        val filePickerRequests = _filePickerRequests.receiveAsFlow()

        private var pendingPassphrase: CharArray? = null

        /**
         * The exact sections (and passphrase) the user launched the SAF picker for. The picker
         * result must export THIS, not whatever the UI state holds when the result arrives: after
         * process death behind the picker the ViewModel is rebuilt with the default selection
         * (which deliberately excludes API keys) and no passphrase, and exporting that silently
         * would write a backup missing the very section the user opted into.
         */
        private class PendingExport(
            val sections: Set<BackupSection>,
            val passphrase: CharArray?,
        ) {
            fun clear() {
                passphrase?.fill('\u0000')
            }
        }

        private var pendingExport: PendingExport? = null

        init {
            refreshCounts(initializeSelection = true)
        }

        private fun refreshCounts(initializeSelection: Boolean) {
            viewModelScope.launch {
                val counts = backupManager.sectionCounts()
                _uiState.update { state ->
                    state.copy(
                        counts = counts,
                        exportSelection =
                            if (initializeSelection) defaultExportSelection(counts) else state.exportSelection,
                    )
                }
            }
        }

        /**
         * Everything with content is preselected EXCEPT API keys: exporting secrets stays an
         * explicit opt-in (it also adds the passphrase step to the happy path).
         */
        private fun defaultExportSelection(counts: ChirpBackupManager.SectionCounts): Set<BackupSection> =
            buildSet {
                add(BackupSection.SETTINGS)
                if (counts.tags > 0) add(BackupSection.TAGS)
                if (counts.profiles > 0) add(BackupSection.PROFILES)
                if (counts.wordReplacements > 0) add(BackupSection.WORD_REPLACEMENTS)
                if (counts.processingPresets > 0) add(BackupSection.PROCESSING_PRESETS)
            }

        // region Export

        fun toggleExportSection(section: BackupSection) {
            _uiState.update { state ->
                val selection =
                    if (section in state.exportSelection) {
                        state.exportSelection - section
                    } else {
                        state.exportSelection + section
                    }
                state.copy(exportSelection = selection, exportMessage = null)
            }
        }

        fun startExport() {
            val state = _uiState.value
            if (state.exportSelection.isEmpty() || state.isExporting) return
            if (BackupSection.API_KEYS in state.exportSelection) {
                _uiState.update { it.copy(passphrasePrompt = PassphrasePromptMode.EXPORT, exportMessage = null) }
            } else {
                pendingExport = PendingExport(sections = state.exportSelection, passphrase = null)
                requestExportFile()
            }
        }

        private fun requestExportFile() {
            viewModelScope.launch {
                _filePickerRequests.send(
                    FilePickerRequest.CreateBackupFile(backupManager.suggestedBackupFileName()),
                )
            }
        }

        /** SAF result for the export destination; null means the picker was cancelled. */
        fun onExportFileChosen(uri: Uri?) {
            val pending = pendingExport
            pendingExport = null
            if (uri == null) {
                pending?.clear()
                return
            }
            if (pending == null) {
                // The process died while the SAF picker was in the foreground, so the selection
                // and passphrase the user launched the picker with are gone. Abort honestly
                // instead of exporting the rebuilt default selection, and delete the empty file
                // the picker already created.
                viewModelScope.launch { backupManager.discardBackupFile(uri) }
                _uiState.update {
                    it.copy(
                        exportMessage =
                            StatusMessage.Error(appContext.getString(R.string.backup_export_interrupted)),
                    )
                }
                return
            }
            _uiState.update { it.copy(isExporting = true) }
            viewModelScope.launch {
                val result = backupManager.exportToUri(uri, pending.sections, pending.passphrase)
                pending.clear()
                _uiState.update { state ->
                    state.copy(
                        isExporting = false,
                        exportMessage =
                            result.fold(
                                onSuccess = { sectionCount ->
                                    StatusMessage.Success(
                                        appContext.resources.getQuantityString(
                                            R.plurals.backup_export_success,
                                            sectionCount,
                                            sectionCount,
                                        ),
                                    )
                                },
                                onFailure = { error ->
                                    Log.w(TAG, "Backup export failed", error)
                                    StatusMessage.Error(appContext.getString(exportErrorText(error)))
                                },
                            ),
                    )
                }
            }
        }

        private fun exportErrorText(error: Throwable): Int =
            if (error is BackupApiKeysExportException) {
                R.string.backup_export_keys_failed
            } else {
                R.string.backup_export_failed
            }

        fun dismissExportMessage() {
            _uiState.update { it.copy(exportMessage = null) }
        }

        // endregion

        // region Passphrase prompt (shared by export + import)

        fun submitPassphrase(passphrase: String) {
            val mode = _uiState.value.passphrasePrompt ?: return
            _uiState.update { it.copy(passphrasePrompt = null) }
            when (mode) {
                PassphrasePromptMode.EXPORT -> {
                    pendingExport =
                        PendingExport(
                            sections = _uiState.value.exportSelection,
                            passphrase = passphrase.toCharArray(),
                        )
                    requestExportFile()
                }
                PassphrasePromptMode.IMPORT -> {
                    pendingPassphrase = passphrase.toCharArray()
                    applyImport()
                }
            }
        }

        fun cancelPassphrasePrompt() {
            clearPendingPassphrase()
            _uiState.update { it.copy(passphrasePrompt = null) }
        }

        private fun clearPendingPassphrase() {
            pendingPassphrase?.fill('\u0000')
            pendingPassphrase = null
            pendingExport?.clear()
            pendingExport = null
        }

        // endregion

        // region Import

        fun chooseImportFile() {
            if (_uiState.value.importState is ImportState.Applying) return
            viewModelScope.launch {
                _filePickerRequests.send(FilePickerRequest.OpenBackupFile)
            }
        }

        /** SAF result for the backup file to inspect; null means the picker was cancelled. */
        fun onImportFileChosen(uri: Uri?) {
            if (uri == null) return
            _uiState.update { it.copy(importState = ImportState.Inspecting, importMessage = null) }
            viewModelScope.launch {
                backupManager.inspect(uri).fold(
                    onSuccess = { contents ->
                        _uiState.update { state ->
                            state.copy(
                                importState =
                                    ImportState.Ready(
                                        contents = contents,
                                        selection = contents.availableSections,
                                        mode = BackupImportMode.MERGE,
                                    ),
                            )
                        }
                    },
                    onFailure = { error ->
                        Log.w(TAG, "Backup inspection rejected the file", error)
                        _uiState.update { state ->
                            state.copy(
                                importState = ImportState.Idle,
                                importMessage = StatusMessage.Error(inspectionErrorText(error)),
                            )
                        }
                    },
                )
            }
        }

        private fun inspectionErrorText(error: Throwable): String =
            when ((error as? BackupFormatException)?.reason) {
                BackupFormatException.Reason.NOT_A_CHIRP_BACKUP,
                BackupFormatException.Reason.UNREADABLE,
                -> appContext.getString(R.string.backup_import_error_not_backup)

                BackupFormatException.Reason.UNSUPPORTED_VERSION ->
                    appContext.getString(R.string.backup_import_error_newer_version)

                BackupFormatException.Reason.EMPTY ->
                    appContext.getString(R.string.backup_import_error_empty)

                BackupFormatException.Reason.TOO_LARGE ->
                    appContext.getString(R.string.backup_import_error_too_large)

                null -> appContext.getString(R.string.backup_import_error_unreadable)
            }

        fun toggleImportSection(section: BackupSection) {
            updateReady { ready ->
                val selection =
                    if (section in ready.selection) ready.selection - section else ready.selection + section
                ready.copy(selection = selection)
            }
        }

        fun setImportMode(mode: BackupImportMode) {
            updateReady { ready -> ready.copy(mode = mode) }
        }

        fun requestApplyImport() {
            updateReady { ready ->
                if (ready.selection.isEmpty()) ready else ready.copy(showConfirmDialog = true)
            }
        }

        fun dismissConfirmDialog() {
            updateReady { ready -> ready.copy(showConfirmDialog = false) }
        }

        fun confirmImport() {
            val ready = _uiState.value.importState as? ImportState.Ready ?: return
            updateReady { it.copy(showConfirmDialog = false) }
            if (BackupSection.API_KEYS in ready.selection) {
                _uiState.update { it.copy(passphrasePrompt = PassphrasePromptMode.IMPORT) }
            } else {
                applyImport()
            }
        }

        private fun applyImport() {
            val ready = _uiState.value.importState as? ImportState.Ready ?: return
            val passphrase = pendingPassphrase
            pendingPassphrase = null
            _uiState.update { it.copy(importState = ImportState.Applying) }
            viewModelScope.launch {
                val summary =
                    backupManager.applyImport(
                        contents = ready.contents,
                        sections = ready.selection,
                        mode = ready.mode,
                        passphrase = passphrase,
                    )
                passphrase?.fill('\u0000')
                _uiState.update { it.copy(importState = ImportState.Complete(summary)) }
                refreshCounts(initializeSelection = false)
            }
        }

        fun resetImport() {
            clearPendingPassphrase()
            _uiState.update { it.copy(importState = ImportState.Idle, importMessage = null) }
        }

        fun dismissImportMessage() {
            _uiState.update { it.copy(importMessage = null) }
        }

        private fun updateReady(transform: (ImportState.Ready) -> ImportState.Ready) {
            _uiState.update { state ->
                val ready = state.importState as? ImportState.Ready ?: return@update state
                state.copy(importState = transform(ready))
            }
        }

        // endregion

        override fun onCleared() {
            clearPendingPassphrase()
            super.onCleared()
        }

        companion object {
            private const val TAG = "BackupRestoreVM"
        }
    }
