plugins {
    id("com.android.application") version "8.10.0" apply false
    id("com.android.library") version "8.10.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
    id("com.google.dagger.hilt.android") version "2.51.1" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
}

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.GradleException
import org.gradle.api.artifacts.ProjectDependency

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

val forbiddenProjectEdges =
    mapOf(
        ":feature-keyboard" to
            setOf(
                ":data",
                ":feature-transcription",
                ":feature-llm",
                ":feature-obsidian",
            ),
        ":feature-transcription" to setOf(":feature-llm"),
        ":feature-llm" to
            setOf(
                ":data",
                ":feature-keyboard",
                ":feature-transcription",
                ":feature-obsidian",
            ),
        ":feature-obsidian" to
            setOf(
                ":data",
                ":feature-keyboard",
                ":feature-transcription",
                ":feature-llm",
            ),
        ":core-ui" to setOf(":data"),
        ":core-contracts" to
            setOf(
                ":data",
                ":core-ui",
                ":core-recording-runtime",
                ":feature-keyboard",
                ":feature-transcription",
                ":feature-llm",
                ":feature-obsidian",
                ":feature-recording",
                ":feature-studio",
                ":feature-widget",
            ),
    )

val forbiddenPackagePatterns =
    mapOf(
        ":feature-keyboard" to
            setOf(
                "dev.chirpboard.app.data",
                "dev.chirpboard.app.feature.transcription",
                "dev.chirpboard.app.feature.llm",
                "dev.chirpboard.app.feature.obsidian",
            ),
        ":feature-transcription" to setOf("dev.chirpboard.app.feature.llm"),
        ":feature-llm" to
            setOf(
                "dev.chirpboard.app.data",
                "dev.chirpboard.app.feature.keyboard",
                "dev.chirpboard.app.feature.transcription",
                "dev.chirpboard.app.feature.obsidian",
            ),
        ":feature-obsidian" to
            setOf(
                "dev.chirpboard.app.data",
                "dev.chirpboard.app.feature.keyboard",
                "dev.chirpboard.app.feature.transcription",
                "dev.chirpboard.app.feature.llm",
            ),
        ":core-ui" to
            setOf(
                "dev.chirpboard.app.data",
                "androidx.room",
            ),
        ":core-contracts" to
            setOf(
                "dev.chirpboard.app.data",
                "dev.chirpboard.app.feature",
                "androidx.datastore",
                "dagger",
                "javax.inject",
            ),
    )

val checkModuleBoundaries by tasks.registering {
    group = "verification"
    description = "Checks architectural module boundaries and forbidden implementation imports."

    doLast {
        val violations = mutableListOf<String>()

        subprojects.forEach { candidate ->
            val forbiddenEdges = forbiddenProjectEdges[candidate.path].orEmpty()
            if (forbiddenEdges.isNotEmpty()) {
                candidate.configurations.forEach { configuration ->
                    configuration.dependencies.withType(ProjectDependency::class.java).forEach { dependency ->
                        val target = dependency.path
                        if (target in forbiddenEdges) {
                            violations +=
                                "${candidate.path}:${configuration.name} must not depend on $target"
                        }
                    }
                }
            }

            val forbiddenPackages = forbiddenPackagePatterns[candidate.path].orEmpty()
            if (forbiddenPackages.isNotEmpty()) {
                candidate.fileTree(candidate.projectDir) {
                    include("src/**/*.kt")
                    include("build.gradle.kts")
                    exclude("build/**")
                }.files.forEach { file ->
                    val sourceLines =
                        file
                            .readLines()
                            .map { it.trim() }
                            .filterNot { line ->
                                line.startsWith("*") ||
                                    line.startsWith("//") ||
                                    line.startsWith("/*")
                            }
                    forbiddenPackages.forEach { pattern ->
                        val hasImport = sourceLines.any { it.startsWith("import $pattern") }
                        val hasQualifiedReference =
                            sourceLines.any { line ->
                                "$pattern." in line && "\"$pattern." !in line
                            }
                        if (hasImport || hasQualifiedReference) {
                            violations +=
                                "${candidate.path}:${file.relativeTo(rootDir)} imports forbidden package $pattern"
                        }
                    }
                }
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Module boundary violations:")
                    violations.sorted().forEach { appendLine(" - $it") }
                },
            )
        }
    }
}

tasks.register("check") {
    group = "verification"
    description = "Runs root verification checks."
    dependsOn(checkModuleBoundaries)
}

subprojects {
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(rootProject.tasks.named("checkModuleBoundaries"))
    }
}
