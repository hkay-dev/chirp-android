plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
    id("com.google.dagger.hilt.android") version "2.51.1" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
}

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension

detekt {
    toolVersion = "1.23.7"
    config.setFrom(files("$rootDir/detekt.yml"))
    buildUponDefaultConfig = false
    parallel = true
}

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension>("detekt") {
        config.setFrom(files("$rootDir/detekt.yml"))
        buildUponDefaultConfig = false
        parallel = true
        val baselineFile = file("detekt-baseline.xml")
        if (baselineFile.exists()) {
            baseline = baselineFile
        }
    }

    plugins.withId("com.android.library") {
        extensions.configure<LibraryExtension>("android") {
            lint {
                abortOnError = true
                warningsAsErrors = false
            }
        }
    }
    plugins.withId("com.android.application") {
        extensions.configure<ApplicationExtension>("android") {
            lint {
                abortOnError = true
                warningsAsErrors = false
            }
        }
    }
}
