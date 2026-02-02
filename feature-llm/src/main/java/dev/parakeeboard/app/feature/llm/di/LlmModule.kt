package dev.parakeeboard.app.feature.llm.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.parakeeboard.app.feature.llm.client.GeminiClient
import dev.parakeeboard.app.feature.llm.client.LlmClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object LlmModule {

    // TODO: Move to settings/BuildConfig in a future phase
    private const val DEFAULT_API_KEY = ""
    private const val DEFAULT_MODEL = "gemini-2.0-flash"

    @Provides
    @Singleton
    fun provideLlmClient(): LlmClient {
        return GeminiClient(
            apiKey = DEFAULT_API_KEY,
            modelName = DEFAULT_MODEL
        )
    }
}
