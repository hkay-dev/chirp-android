package dev.chirpboard.app.feature.llm.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.chirpboard.app.feature.llm.client.LlmClient
import dev.chirpboard.app.feature.llm.client.LlmClientImpl
import dev.chirpboard.app.feature.llm.settings.LlmPreferences
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LlmModule {
    @Provides
    @Singleton
    fun provideLlmClient(preferences: LlmPreferences): LlmClient = LlmClientImpl(preferences)
}
