package dev.chirpboard.app.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.chirpboard.app.data.repository.ProfileRepository
import dev.chirpboard.app.shortcut.ProfileShortcutManager

/**
 * Exposes the singletons the Profiles screen needs to back the "Add to Home screen" action from a
 * navigation composable (which has no @Inject site of its own).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ProfileShortcutEntryPoint {
    fun profileShortcutManager(): ProfileShortcutManager

    fun profileRepository(): ProfileRepository
}
