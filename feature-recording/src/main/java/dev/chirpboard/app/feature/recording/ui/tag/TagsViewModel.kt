package dev.chirpboard.app.feature.recording.ui.tag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chirpboard.app.data.entity.Tag
import dev.chirpboard.app.data.repository.TagRepository
import dev.chirpboard.app.data.repository.unwrapRepositoryFlow
import dev.chirpboard.app.feature.recording.ui.launchRepositoryMutation
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
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

        // The in-flight delete. An Undo must wait for it: restoring before the delete commits
        // would either hit the still-present row or be wiped again when the delete lands.
        private var deleteJob: Job? = null

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
            launchRepositoryMutation(TAG, { _errorMessage.value = it }) {
                tagRepository.createTag(name, color)
            }
        }

        fun updateTag(tag: Tag) {
            launchRepositoryMutation(TAG, { _errorMessage.value = it }) {
                tagRepository.update(tag)
            }
        }

        fun deleteTag(tag: Tag) {
            // Snapshot the tag AND its assignments before deletion: deleting a tag cascades its
            // recording_tags and profile_default_tags rows, so an Undo re-inserts the tag (id
            // preserved) and re-links those assignments — a lossless undo.
            _pendingUndo.value = tag
            deleteJob =
                launchRepositoryMutation(TAG, { _errorMessage.value = it }) {
                    val recordingIds = tagRepository.getRecordingIdsForTag(tag.id)
                    val profileIds = tagRepository.getProfileIdsForTag(tag.id)
                    pendingDeletion = PendingDeletion(tag, recordingIds, profileIds)
                    tagRepository.delete(tag)
                }
        }

        /** PROP-11: restore the last swipe-deleted tag — id/name/color and its assignments. */
        fun undoDelete() {
            val tag = _pendingUndo.value ?: return
            _pendingUndo.value = null
            launchRepositoryMutation(TAG, { _errorMessage.value = it }) {
                // An immediate Undo can arrive while the delete coroutine is still snapshotting:
                // restoring now would be undone by the still-pending delete. Wait it out.
                deleteJob?.join()
                val snapshot = pendingDeletion
                pendingDeletion = null
                if (snapshot != null && snapshot.tag.id == tag.id) {
                    tagRepository.restoreTagWithAssignments(snapshot.tag, snapshot.recordingIds, snapshot.profileIds)
                } else {
                    // The delete coroutine died before snapshotting — restore the tag itself.
                    // (Assignment-less restore; the IGNORE insert tolerates a still-present row.)
                    tagRepository.restoreTagWithAssignments(tag, emptyList(), emptyList())
                }
            }
        }

        /** Clears the pending-undo entity once its snackbar is no longer showing. */
        fun clearPendingUndo() {
            _pendingUndo.value = null
            pendingDeletion = null
        }

        private companion object {
            const val TAG = "TagsVM"
        }
    }
