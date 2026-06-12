package dev.chirpboard.app.core.di

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
annotation class KeyboardPendingStopDataStore

@Module
@InstallIn(SingletonComponent::class)
object RecordingControlModule {
    private const val TAG = "RecordingControl"

    @Provides
    @Singleton
    @KeyboardPendingStopDataStore
    fun provideKeyboardPendingStopDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            // Reliability load-bearing store (stop-command handoff): a corrupted file
            // would otherwise poison every read forever. Resetting to defaults only
            // drops an in-flight pending stop, which the TTL/reconcile path already
            // tolerates; a permanently throwing store would kill the handoff entirely.
            corruptionHandler =
                ReplaceFileCorruptionHandler { corruption ->
                    Log.e(TAG, "keyboard_pending_stop corrupted; resetting to defaults", corruption)
                    emptyPreferences()
                },
            produceFile = { context.preferencesDataStoreFile("keyboard_pending_stop") },
        )
}
