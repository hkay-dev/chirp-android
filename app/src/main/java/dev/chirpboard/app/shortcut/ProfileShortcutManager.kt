package dev.chirpboard.app.shortcut

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chirpboard.app.MainActivity
import dev.chirpboard.app.R
import dev.chirpboard.app.data.entity.Profile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Publishes recording-profile launcher shortcuts.
 *
 * - Dynamic shortcuts mirror the current profile set (long-press the launcher icon to start a
 *   recording with a given profile). They are refreshed whenever the profile set changes.
 * - Pinned shortcuts back the per-profile "Add to Home screen" affordance.
 *
 * The static "start_recording" shortcut (res/xml/shortcuts.xml) is intentionally left untouched;
 * dynamic and pinned shortcuts live alongside it.
 *
 * Both shortcut kinds launch [MainActivity] with an explicit component and the
 * [MainActivity.ACTION_RECORD_WITH_PROFILE] action carrying the profile id, so no manifest
 * intent-filter is required and the recording foreground service is never exported.
 */
@Singleton
class ProfileShortcutManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /**
         * Replaces the dynamic per-profile shortcuts with one entry per profile, capped at the
         * launcher's per-activity maximum. Profiles are ordered pinned-then-recent first (the same
         * ordering the home quick-start row uses) so the most useful entries survive the cap.
         */
        fun pushDynamicShortcuts(profiles: List<Profile>) {
            val maxCount = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
            if (maxCount <= 0) {
                ShortcutManagerCompat.removeAllDynamicShortcuts(context)
                return
            }

            val shortcuts = orderedForShortcuts(profiles, maxCount).map { buildShortcut(it) }
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        }

        /**
         * Requests the launcher pin a shortcut for [profile] ("Add to Home screen"). No-ops on
         * launchers that do not support pin requests (the caller should guard with
         * [isRequestPinShortcutSupported] before surfacing the affordance).
         */
        fun requestPinShortcut(profile: Profile): Boolean {
            if (!isRequestPinShortcutSupported()) {
                return false
            }
            return ShortcutManagerCompat.requestPinShortcut(
                context,
                buildShortcut(profile),
                null,
            )
        }

        fun isRequestPinShortcutSupported(): Boolean =
            ShortcutManagerCompat.isRequestPinShortcutSupported(context)

        private fun buildShortcut(profile: Profile): ShortcutInfoCompat {
            val name = profile.name.ifBlank { context.getString(R.string.shortcut_record_short) }
            val intent =
                Intent(context, MainActivity::class.java).apply {
                    action = MainActivity.ACTION_RECORD_WITH_PROFILE
                    putExtra(MainActivity.EXTRA_PROFILE_ID, profile.id.toString())
                    // The launcher resolves a pinned/dynamic shortcut's intent as a fresh task; a
                    // shortcut tap is a direct user interaction, so the resulting recording is
                    // FGS-microphone eligible.
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
            return ShortcutInfoCompat.Builder(context, shortcutId(profile))
                .setShortLabel(name)
                .setLongLabel(context.getString(R.string.shortcut_record_with_profile_long, name))
                // The profile emoji can't be a launcher icon reliably, so reuse the generic record
                // glyph the static shortcut uses.
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_record))
                .setIntent(intent)
                .build()
        }

        companion object {
            internal fun shortcutId(profile: Profile): String = "profile_${profile.id}"

            /**
             * Orders profiles pinned-then-recent (the home quick-start ordering) and caps the list
             * at [maxCount] so the most useful entries survive the launcher's per-activity limit.
             * Extracted as a pure function so the ordering/cap is unit-testable without a Context.
             */
            internal fun orderedForShortcuts(
                profiles: List<Profile>,
                maxCount: Int,
            ): List<Profile> {
                if (maxCount <= 0) {
                    return emptyList()
                }
                return profiles
                    .sortedWith(
                        compareByDescending<Profile> { it.isQuickStartPinned }
                            .thenBy { it.sortOrder }
                            .thenBy { it.name },
                    )
                    .take(maxCount)
            }
        }
    }
