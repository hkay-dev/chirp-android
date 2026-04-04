package dev.chirpboard.app.feature.recording.ui.tag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chirpboard.app.data.entity.Tag
import dev.chirpboard.app.data.repository.TagRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TagsViewModel
    @Inject
    constructor(
        private val tagRepository: TagRepository,
    ) : ViewModel() {
        val tags: StateFlow<List<Tag>> =
            tagRepository
                .getAllTags()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
            viewModelScope.launch {
                tagRepository.delete(tag)
            }
        }
    }
