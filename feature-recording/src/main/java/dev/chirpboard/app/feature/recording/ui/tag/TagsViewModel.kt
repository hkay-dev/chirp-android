package dev.chirpboard.app.feature.recording.ui.tag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chirpboard.app.data.entity.Tag
import dev.chirpboard.app.data.repository.TagRepository
import dev.chirpboard.app.data.repository.unwrapRepositoryFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TagsViewModel
    @Inject
    constructor(
        private val tagRepository: TagRepository,
    ) : ViewModel() {
        private val _errorMessage = MutableStateFlow<String?>(null)
        val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

        // PROP-11: the most recently swipe-deleted tag, captured in full so an Undo can re-insert
        // it with its original id, name, and color preserved.
        private val _pendingUndo = MutableStateFlow<Tag?>(null)
        val pendingUndo: StateFlow<Tag?> = _pendingUndo.asStateFlow()

        val tags: StateFlow<List<Tag>> =
            tagRepository
                .getAllTags()
                .unwrapRepositoryFlow { _errorMessage.value = it }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

        fun clearError() {
            _errorMessage.value = null
        }

        fun createTag(
            name: String,
            color: String?,
        ) {
            viewModelScope.launch {
                tagRepository.createTag(name, color)
            }
        }

        fun updateTag(tag: Tag) {
            viewModelScope.launch {
                tagRepository.update(tag)
            }
        }

        fun deleteTag(tag: Tag) {
            // Capture the whole entity before deletion so Undo can re-insert it verbatim. Deleting
            // a tag cascades its recording_tags rows; the available repository API can re-create the
            // tag (id preserved) but cannot restore those assignments, so the UI's undo snackbar is
            // worded to say so honestly (see rec_tag_deleted_undo_note).
            _pendingUndo.value = tag
            viewModelScope.launch {
                tagRepository.delete(tag)
            }
        }

        /** PROP-11: re-insert the last swipe-deleted tag, preserving its original id/name/color. */
        fun undoDelete() {
            val tag = _pendingUndo.value ?: return
            _pendingUndo.value = null
            viewModelScope.launch {
                tagRepository.insert(tag)
            }
        }

        /** Clears the pending-undo entity once its snackbar is no longer showing. */
        fun clearPendingUndo() {
            _pendingUndo.value = null
        }
    }
