plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "dev.chirpboard.app.feature.transcription"
    compileSdk = 36

    defaultConfig {
        minSdk = 36
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // Internal modules
    implementation(project(":core-audio"))
    implementation(project(":core-contracts"))
    implementation(project(":core-recording-runtime"))
    implementation(project(":core-ui"))
    implementation(project(":data"))

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // WorkManager (version + PLT-03 rationale live in gradle/libs.versions.toml).
    implementation(libs.androidx.work.runtime.ktx)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.androidx.hilt.compiler)

    // OkHttp removed: downloads delegate to app SpeechModelStore implementation

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Core
    implementation(libs.androidx.core.ktx)

    // Testing
    testImplementation(libs.junit4)
    testImplementation(project(":test-support"))
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.guava)
}
