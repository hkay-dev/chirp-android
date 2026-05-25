package dev.chirpboard.app.navigation

/**
 * Navigation routes for the app.
 */
sealed class Screen(
    val route: String,
) {
    object Home : Screen("home")

    object Record : Screen("record?autoStart={autoStart}&profileId={profileId}") {
        fun createRoute(
            autoStart: Boolean = true,
            profileId: String? = null,
        ) =
            if (profileId != null) {
                "record?autoStart=$autoStart&profileId=$profileId"
            } else {
                "record?autoStart=$autoStart"
            }
    }

    object RecordingDetail : Screen("recording/{recordingId}") {
        fun createRoute(recordingId: String) = "recording/$recordingId"
    }

    object Settings : Screen("settings")

    object TranscriptionSettings : Screen("settings/transcription?autoDownload={autoDownload}") {
        fun createRoute(autoDownload: Boolean = false) = "settings/transcription?autoDownload=$autoDownload"
    }

    object LlmSettings : Screen("settings/llm")

    object AudioSettings : Screen("settings/audio")

    object ObsidianSettings : Screen("settings/obsidian")

    object KeyboardSettings : Screen("settings/keyboard")

    object Profiles : Screen("profiles")

    object ProfileEditor : Screen("profiles/edit?profileId={profileId}") {
        fun createRoute(profileId: String? = null) =
            if (profileId != null) {
                "profiles/edit?profileId=$profileId"
            } else {
                "profiles/edit"
            }
    }

    object Tags : Screen("tags")

    object WordReplacements : Screen("word-replacements")

    object About : Screen("about")

    object DevMenu : Screen("dev-menu")

    object ProcessingStudio : Screen("processing_studio/{recordingId}") {
        fun createRoute(recordingId: String) = "processing_studio/$recordingId"
    }
}
