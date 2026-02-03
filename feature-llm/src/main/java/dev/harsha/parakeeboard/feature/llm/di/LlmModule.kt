package dev.harsha.parakeeboard.feature.llm.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.harsha.parakeeboard.feature.llm.client.DynamicLlmClient
import dev.harsha.parakeeboard.feature.llm.client.LlmClient
import dev.harsha.parakeeboard.feature.llm.settings.LlmPreferences
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LlmModule {

    @Provides
    @Singleton
    fun provideLlmClient(preferences: LlmPreferences): LlmClient {
        return DynamicLlmClient(preferences)
    }
}
