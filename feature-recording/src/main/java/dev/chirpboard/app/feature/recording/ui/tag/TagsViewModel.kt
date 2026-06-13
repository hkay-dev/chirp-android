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
import java.util.UUID
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
        // it with its original id, name, and color preserved. The Tag drives the snackbar; its
        // cascaded assignments are snapshotted alongside (see [pendingDeletion]) so the Undo is
        // lossless.
        private val _pendingUndo = MutableStateFlow<Tag?>(null)
        val pendingUndo: StateFlow<Tag?> = _pendingUndo.asStateFlow()

        /** Full deletion snapshot (tag + the recording/profile assignments the delete cascaded). */
        private data class PendingDeletion(
            val tag: Tag,
            val recordingIds: List<UUID>,
            val profileIds: List<UUID>,
        )

        private var pendingDeletion: PendingDeletion? = null

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
            // Snapshot the tag AND its assignments before deletion: deleting a tag cascades its
            // recording_tags and profile_default_tags rows, so an Undo re-inserts the tag (id
            // preserved) and re-links those assignments — a lossless undo.
            _pendingUndo.value = tag
            viewModelScope.launch {
                val recordingIds = tagRepository.getRecordingIdsForTag(tag.id)
                val profileIds = tagRepository.getProfileIdsForTag(tag.id)
                pendingDeletion = PendingDeletion(tag, recordingIds, profileIds)
                tagRepository.delete(tag)
            }
        }

        /** PROP-11: restore the last swipe-deleted tag — id/name/color and its assignments. */
        fun undoDelete() {
            val tag = _pendingUndo.value ?: return
            val snapshot = pendingDeletion
            _pendingUndo.value = null
            pendingDeletion = null
            viewModelScope.launch {
                if (snapshot != null && snapshot.tag.id == tag.id) {
                    tagRepository.restoreTagWithAssignments(snapshot.tag, snapshot.recordingIds, snapshot.profileIds)
                } else {
                    // Undo raced the assignment snapshot (sub-millisecond window) — restore the tag itself.
                    tagRepository.insert(tag)
                }
            }
        }

        /** Clears the pending-undo entity once its snackbar is no longer showing. */
        fun clearPendingUndo() {
            _pendingUndo.value = null
            pendingDeletion = null
        }
    }
