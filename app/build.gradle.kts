plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "dev.chirpboard.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.chirpboard.app"
        minSdk = 36
        targetSdk = 36
        versionCode = 30
        versionName = "3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Internal modules
    implementation(project(":core-audio"))
    implementation(project(":core-ui"))
    implementation(project(":core-playback"))
    implementation(project(":data"))
    implementation(project(":feature-recording"))
    implementation(project(":feature-studio"))
    implementation(project(":feature-transcription"))
    implementation(project(":feature-llm"))
    implementation(project(":feature-keyboard"))
    implementation(project(":feature-obsidian"))
    implementation(project(":feature-widget"))

    // Sherpa-ONNX for speech recognition (local AAR)
    implementation(files("libs/sherpa-onnx-1.12.19.aar"))

    // Compose - latest stable
    implementation(platform("androidx.compose:compose-bom:2025.01.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")

    // Material Design Components (for XML theme parent)
    implementation("com.google.android.material:material:1.12.0")

    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.51.1")
    ksp("com.google.dagger:hilt-android-compiler:2.51.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Startup (needed to disable default WorkManager initialization for Hilt)
    implementation("androidx.startup:startup-runtime:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // OkHttp for model download
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation(libs.mockk)
    testImplementation("app.cash.turbine:turbine:1.0.0")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)

}
