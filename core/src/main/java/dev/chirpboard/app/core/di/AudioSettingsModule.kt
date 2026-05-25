package dev.chirpboard.app.core.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.chirpboard.app.core.audio.AudioSettingsMigrationSource
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AudioSettingsDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class KeyboardPreferencesDataStore

@Singleton
class ContextAudioSettingsMigrationSource
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        @KeyboardPreferencesDataStore private val keyboardPreferencesDataStore: DataStore<Preferences>,
    ) : AudioSettingsMigrationSource {
        override suspend fun readLegacyKeyboardMicrophoneGain(): Float? =
            keyboardPreferencesDataStore.data.first()[floatPreferencesKey(LEGACY_MICROPHONE_GAIN_KEY)]

        override fun readLegacyAppMicrophoneGain(): Float? {
            val sharedPreferences = context.getSharedPreferences(LEGACY_APP_PREFERENCES_NAME, Context.MODE_PRIVATE)
            return if (sharedPreferences.contains(LEGACY_MICROPHONE_GAIN_KEY)) {
                sharedPreferences.getFloat(LEGACY_MICROPHONE_GAIN_KEY, 1.0f)
            } else {
                null
            }
        }

        private companion object {
            const val LEGACY_APP_PREFERENCES_NAME = "chirp"
            const val LEGACY_MICROPHONE_GAIN_KEY = "microphone_gain"
        }
    }

@Module
@InstallIn(SingletonComponent::class)
object AudioSettingsModule {
    @Provides
    @Singleton
    @AudioSettingsDataStore
    fun provideAudioSettingsDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile("audio_settings") },
        )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AudioSettingsBindingsModule {
    @Binds
    @Singleton
    abstract fun bindAudioSettingsMigrationSource(impl: ContextAudioSettingsMigrationSource): AudioSettingsMigrationSource
}
