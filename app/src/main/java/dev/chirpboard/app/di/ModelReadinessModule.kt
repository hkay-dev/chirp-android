package dev.chirpboard.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.chirpboard.app.download.ModelDownloader
import dev.chirpboard.app.download.ModelReadinessGate
import dev.chirpboard.app.download.ModelReadinessVerifier
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ModelReadinessModule {

    @Provides
    @Singleton
    fun provideModelDownloader(@ApplicationContext context: Context): ModelDownloader {
        return ModelDownloader(context)
    }

    @Provides
    @Singleton
    fun provideModelReadinessVerifier(
        modelDownloader: ModelDownloader
    ): ModelReadinessVerifier {
        return ModelReadinessVerifier {
            modelDownloader.evaluateModelReadiness()
        }
    }

    @Provides
    @Singleton
    fun provideModelReadinessGate(
        verifier: ModelReadinessVerifier
    ): ModelReadinessGate {
        return ModelReadinessGate(verifier)
    }
}
