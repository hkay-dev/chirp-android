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

rootProject.name = "Parakeeboard"
include(":app")
include(":core")
include(":data")
include(":feature-recording")
include(":feature-transcription")
include(":feature-llm")
include(":feature-keyboard")
include(":feature-obsidian")
include(":feature-widget")
