package dev.chirpboard.app.di

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.chirpboard.app.core.ui.theme.DynamicColorPreference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppearancePreferencesDataStore

/**
 * DataStore-backed implementation of the [DynamicColorPreference] contract (DECISIONS Color/brand).
 *
 * core-ui declares the interface (the seam between the Settings toggle and [ChirpTheme]) but keeps
 * no storage dependency; this concrete implementation, supplied by the app module, persists the
 * choice in the existing DataStore preferences mechanism. The brand lavender palette stays the
 * default ([DynamicColorPreference.DEFAULT_USE_DYNAMIC_COLOR] == false); the keyboard IME (which
 * shares this process and Hilt graph) reads the same singleton so both surfaces stay in sync.
 */
@Singleton
class DataStoreDynamicColorPreference(
    private val dataStore: DataStore<Preferences>,
) : DynamicColorPreference {
    override val useDynamicColor: Flow<Boolean> =
        dataStore.data.map { preferences ->
            preferences[USE_DYNAMIC_COLOR_KEY] ?: DynamicColorPreference.DEFAULT_USE_DYNAMIC_COLOR
        }

    override suspend fun setUseDynamicColor(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[USE_DYNAMIC_COLOR_KEY] = enabled
        }
    }

    private companion object {
        val USE_DYNAMIC_COLOR_KEY = booleanPreferencesKey("use_dynamic_color")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppearancePreferencesModule {
    private const val TAG = "AppearancePrefs"

    @Provides
    @Singleton
    @AppearancePreferencesDataStore
    fun provideAppearancePreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            // A corrupted preferences file would otherwise throw CorruptionException on
            // every read forever (the theme flow is collected at the Compose root, so
            // that would crash-loop the app); resetting to defaults is strictly better.
            corruptionHandler =
                ReplaceFileCorruptionHandler { corruption ->
                    Log.e(TAG, "appearance_preferences corrupted; resetting to defaults", corruption)
                    emptyPreferences()
                },
            produceFile = { context.preferencesDataStoreFile("appearance_preferences") },
        )

    @Provides
    @Singleton
    fun provideDynamicColorPreference(
        @AppearancePreferencesDataStore dataStore: DataStore<Preferences>,
    ): DynamicColorPreference = DataStoreDynamicColorPreference(dataStore)
}
