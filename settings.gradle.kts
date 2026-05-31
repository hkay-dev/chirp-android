pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Chirp"
include(":app")
include(":core-contracts")
include(":core-recording-runtime")
include(":core-audio")
include(":core-ui")
include(":core-playback")
include(":test-support")
include(":data")
include(":feature-recording")
include(":feature-studio")
include(":feature-transcription")
include(":feature-llm")
include(":feature-keyboard")
include(":feature-obsidian")
include(":feature-widget")
