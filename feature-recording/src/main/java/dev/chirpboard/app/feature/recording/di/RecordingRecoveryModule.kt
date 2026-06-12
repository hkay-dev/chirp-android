package dev.chirpboard.app.feature.recording.di

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

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class RecordingRecoveryDataStore

@Module
@InstallIn(SingletonComponent::class)
object RecordingRecoveryModule {
    private const val TAG = "RecordingRecovery"

    @Provides
    @Singleton
    @RecordingRecoveryDataStore
    fun provideRecordingRecoveryDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = recoveryCorruptionHandler(),
            produceFile = { context.preferencesDataStoreFile("recording_recovery") },
        )

    /**
     * Reliability load-bearing store (deferred sessions + protected audio paths):
     * without a corruption handler a corrupted file throws CorruptionException on
     * every read forever, permanently killing the recovery path. Resetting to
     * defaults loses defer/protect markers once, which downstream consumers fail
     * safe on (sessions become actionable again; the orphan cleaner still honors
     * journal/DB safelists and grace windows), and is strictly better than a
     * poisoned store. Internal so the corruption behavior is unit-testable.
     */
    internal fun recoveryCorruptionHandler(): ReplaceFileCorruptionHandler<Preferences> =
        ReplaceFileCorruptionHandler { corruption ->
            Log.e(TAG, "recording_recovery corrupted; resetting to defaults", corruption)
            emptyPreferences()
        }
}
