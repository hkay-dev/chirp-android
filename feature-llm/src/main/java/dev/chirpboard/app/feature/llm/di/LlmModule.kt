package dev.chirpboard.app.feature.llm.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.chirpboard.app.core.llm.RecordingTextEnrichment
import dev.chirpboard.app.feature.llm.client.LlmChatService
import dev.chirpboard.app.feature.llm.client.LlmClient
import dev.chirpboard.app.feature.llm.client.LlmClientImpl
import dev.chirpboard.app.feature.llm.client.LlmRecordingTextEnrichment
import dev.chirpboard.app.feature.llm.settings.LlmPreferences
import dev.chirpboard.app.feature.llm.settings.LlmSettingsStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LlmModule {
    @Provides
    @Singleton
    fun provideLlmClient(chatService: LlmChatService): LlmClient = LlmClientImpl(chatService)

    @Provides
    @Singleton
    fun provideRecordingTextEnrichment(impl: LlmRecordingTextEnrichment): RecordingTextEnrichment = impl

    @Provides
    @Singleton
    fun provideLlmSettingsStore(preferences: LlmPreferences): LlmSettingsStore = preferences
}
