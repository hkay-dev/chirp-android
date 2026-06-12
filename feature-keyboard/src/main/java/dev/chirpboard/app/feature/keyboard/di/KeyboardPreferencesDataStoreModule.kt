package dev.chirpboard.app.feature.keyboard.di

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.chirpboard.app.core.di.KeyboardPreferencesDataStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object KeyboardPreferencesDataStoreModule {
    private const val TAG = "KeyboardPrefs"

    @Provides
    @Singleton
    @KeyboardPreferencesDataStore
    fun provideKeyboardPreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            // A corrupted preferences file would otherwise throw CorruptionException
            // on every IME settings read (i.e. every keyboard open), crash-looping the
            // shared app/IME process; resetting to defaults is strictly better.
            corruptionHandler =
                ReplaceFileCorruptionHandler { corruption ->
                    Log.e(TAG, "keyboard_preferences corrupted; resetting to defaults", corruption)
                    emptyPreferences()
                },
            produceFile = { context.preferencesDataStoreFile("keyboard_preferences") },
        )
}
