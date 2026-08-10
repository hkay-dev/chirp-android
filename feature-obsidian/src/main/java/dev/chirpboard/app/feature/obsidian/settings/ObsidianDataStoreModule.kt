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
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifies the Obsidian settings DataStore. Without it this module would claim the
 * app-wide unqualified `DataStore<Preferences>` binding in SingletonComponent, colliding
 * with any other module that wants one.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ObsidianDataStore

@Module
@InstallIn(SingletonComponent::class)
object ObsidianDataStoreModule {
    private const val TAG = "ObsidianDataStore"

    @Provides
    @Singleton
    @ObsidianDataStore
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
