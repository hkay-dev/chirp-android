plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "dev.chirpboard.app.feature.llm"
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
    implementation(project(":core-ui"))

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Networking for the LLM providers: plain OkHttp + Gson. (REL-03: retrofit and
    // converter-gson were declared but never imported anywhere — removed.)
    implementation(libs.gson)
    implementation(libs.okhttp)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Core
    implementation(libs.androidx.core.ktx)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Lifecycle compose integration
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Encrypted SharedPreferences for secure API key storage
    implementation(libs.androidx.security.crypto)

    // Testing
    testImplementation(libs.junit4)
    testImplementation(project(":test-support"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}
