package dev.chirpboard.app.di

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
import dev.chirpboard.app.cloud.CloudAuthTokenProvider
import dev.chirpboard.app.cloud.CloudServiceConfiguration
import dev.chirpboard.app.cloud.GoogleCloudFileTranscriptionProvider
import dev.chirpboard.app.cloud.TranscriptionRoutingPreferences
import dev.chirpboard.app.cloud.UnconfiguredCloudAuthTokenProvider
import dev.chirpboard.app.BuildConfig
import dev.chirpboard.app.core.transcription.CloudFileTranscriptionProvider
import dev.chirpboard.app.core.transcription.TranscriptionRoutingStore
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TranscriptionRoutingDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CloudDictationCheckpointDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CloudTranscriptionHttpClient

@Module
@InstallIn(SingletonComponent::class)
object CloudTranscriptionModule {
    private const val TAG = "CloudTranscription"

    @Provides
    @Singleton
    @TranscriptionRoutingDataStore
    fun provideTranscriptionRoutingDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler =
                ReplaceFileCorruptionHandler { corruption ->
                    Log.e(TAG, "transcription_routing corrupted; resetting to local", corruption)
                    emptyPreferences()
                },
            produceFile = { context.preferencesDataStoreFile("transcription_routing") },
        )

    @Provides
    @Singleton
    @CloudDictationCheckpointDataStore
    fun provideCloudDictationCheckpointDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler =
                ReplaceFileCorruptionHandler { corruption ->
                    Log.e(TAG, "cloud_dictation_checkpoints corrupted; resetting", corruption)
                    emptyPreferences()
                },
            produceFile = { context.preferencesDataStoreFile("cloud_dictation_checkpoints") },
        )

    @Provides
    @Singleton
    fun provideTranscriptionRoutingStore(
        preferences: TranscriptionRoutingPreferences,
    ): TranscriptionRoutingStore = preferences

    @Provides
    @Singleton
    fun provideCloudAuthTokenProvider(): CloudAuthTokenProvider =
        UnconfiguredCloudAuthTokenProvider()

    @Provides
    @Singleton
    fun provideCloudServiceConfiguration(): CloudServiceConfiguration =
        CloudServiceConfiguration(baseUrl = BuildConfig.CHIRP_CLOUD_BASE_URL)

    @Provides
    @Singleton
    fun provideCloudFileTranscriptionProvider(
        provider: GoogleCloudFileTranscriptionProvider,
    ): CloudFileTranscriptionProvider = provider

    @Provides
    @Singleton
    @CloudTranscriptionHttpClient
    fun provideCloudTranscriptionHttpClient(): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            // Cloud Run and Gunicorn stop a Vertex request at 300 seconds. Keep the socket
            // alive long enough to receive that response instead of discarding a billed result.
            .readTimeout(330, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.MINUTES)
            .build()
}
