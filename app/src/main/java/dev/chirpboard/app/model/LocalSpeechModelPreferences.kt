package dev.chirpboard.app.model

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.core.transcription.LocalSpeechModelCatalog
import dev.chirpboard.app.core.transcription.LocalSpeechComputeBackend
import dev.chirpboard.app.core.transcription.LocalSpeechModelId
import dev.chirpboard.app.core.transcription.LocalSpeechModelInfo
import dev.chirpboard.app.core.transcription.LocalSpeechModelSelectionStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

@Singleton
class LocalSpeechModelPreferences
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : LocalSpeechModelSelectionStore {
        private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        private val _selectedModel =
            MutableStateFlow(
                LocalSpeechModelId.fromPersistedValue(preferences.getString(KEY_SELECTED_MODEL, null)),
            )
        private val _selectedComputeBackend =
            MutableStateFlow(
                LocalSpeechComputeBackend.fromPersistedValue(
                    preferences.getString(KEY_SELECTED_COMPUTE_BACKEND, null),
                ),
            )

        override val selectedModel: StateFlow<LocalSpeechModelId> = _selectedModel.asStateFlow()
        override val selectedComputeBackend: StateFlow<LocalSpeechComputeBackend> =
            _selectedComputeBackend.asStateFlow()

        override val availableModels: List<LocalSpeechModelInfo> = LocalSpeechModelCatalog.models

        override fun modelInfo(modelId: LocalSpeechModelId): LocalSpeechModelInfo =
            LocalSpeechModelCatalog.requireModel(modelId)

        override suspend fun selectModel(modelId: LocalSpeechModelId) {
            if (_selectedModel.value == modelId) return
            val persisted =
                withContext(Dispatchers.IO) {
                    preferences.edit().putString(KEY_SELECTED_MODEL, modelId.persistedValue).commit()
                }
            check(persisted) { "Could not persist the selected local speech model" }
            _selectedModel.value = modelId
        }

        override suspend fun selectComputeBackend(backend: LocalSpeechComputeBackend) {
            if (_selectedComputeBackend.value == backend) return
            val persisted =
                withContext(Dispatchers.IO) {
                    preferences
                        .edit()
                        .putString(KEY_SELECTED_COMPUTE_BACKEND, backend.persistedValue)
                        .commit()
                }
            check(persisted) { "Could not persist the selected speech compute backend" }
            _selectedComputeBackend.value = backend
        }

        private companion object {
            const val PREFERENCES_NAME = "local_speech_model"
            const val KEY_SELECTED_MODEL = "selected_model"
            const val KEY_SELECTED_COMPUTE_BACKEND = "selected_compute_backend"
        }
    }
