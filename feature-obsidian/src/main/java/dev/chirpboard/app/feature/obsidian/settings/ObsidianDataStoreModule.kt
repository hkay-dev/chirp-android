package dev.chirpboard.app.feature.obsidian.settings

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
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ObsidianDataStoreModule {
    private const val TAG = "ObsidianDataStore"

    @Provides
    @Singleton
    fun provideObsidianDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create(
            // A corrupted preferences file would otherwise throw CorruptionException
            // on every read forever; resetting to defaults is strictly better.
            corruptionHandler =
                ReplaceFileCorruptionHandler { corruption ->
                    Log.e(TAG, "obsidian_settings corrupted; resetting to defaults", corruption)
                    emptyPreferences()
                },
            produceFile = { context.preferencesDataStoreFile("obsidian_settings") }
        )
    }
}
