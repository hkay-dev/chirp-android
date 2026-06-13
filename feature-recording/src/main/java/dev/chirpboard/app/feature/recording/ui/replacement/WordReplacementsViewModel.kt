package dev.chirpboard.app.feature.recording.ui.replacement

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chirpboard.app.data.entity.WordReplacement
import dev.chirpboard.app.data.repository.WordReplacementRepository
import dev.chirpboard.app.data.repository.unwrapRepositoryFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WordReplacementsViewModel
    @Inject
    constructor(
        private val repository: WordReplacementRepository,
    ) : ViewModel() {
        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

        // PROP-11: the most recently swipe-deleted replacement, captured in full so an Undo can
        // re-insert it verbatim (id, original, replacement, case-sensitivity, enabled all preserved).
        private val _pendingUndo = MutableStateFlow<WordReplacement?>(null)
        val pendingUndo: StateFlow<WordReplacement?> = _pendingUndo.asStateFlow()

        val replacements: StateFlow<List<WordReplacement>> =
            repository
                .getAllReplacements()
                .unwrapRepositoryFlow { _errorMessage.value = it }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        fun clearError() {
            _errorMessage.value = null
        }

        fun create(
            original: String,
            replacement: String,
            caseSensitive: Boolean,
        ) {
            viewModelScope.launch {
                repository.createReplacement(
                    original = original,
                    replacement = replacement,
                    caseSensitive = caseSensitive,
                    enabled = true,
                )
            }
        }

        fun update(item: WordReplacement) {
            viewModelScope.launch {
                repository.update(item)
            }
        }

        fun delete(item: WordReplacement) {
            // Capture the whole entity before deletion so Undo can re-insert it verbatim.
            _pendingUndo.value = item
            viewModelScope.launch {
                repository.delete(item)
            }
        }

        /** PROP-11: re-insert the last swipe-deleted replacement, preserving its original id. */
        fun undoDelete() {
            val item = _pendingUndo.value ?: return
            _pendingUndo.value = null
            viewModelScope.launch {
                repository.insert(item)
            }
        }

        /** Clears the pending-undo entity once its snackbar is no longer showing. */
        fun clearPendingUndo() {
            _pendingUndo.value = null
        }

        fun toggleEnabled(item: WordReplacement) {
            viewModelScope.launch {
                repository.setEnabled(item.id, !item.enabled)
            }
        }
    }
