plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("androidx.baselineprofile")
}

val releaseStoreFile = providers.environmentVariable("CHIRP_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("CHIRP_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("CHIRP_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("CHIRP_RELEASE_KEY_PASSWORD").orNull
val localDebugStoreFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
val releaseSigningConfigured =
    listOf(
        releaseStoreFile,
        releaseStorePassword,
        releaseKeyAlias,
        releaseKeyPassword,
    ).all { !it.isNullOrBlank() }

android {
    namespace = "dev.chirpboard.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.chirpboard.app"
        minSdk = 36
        targetSdk = 36
        versionCode = 42
        versionName = "4.0.0"
        testInstrumentationRunner = "dev.chirpboard.app.ChirpTestRunner"

        val cloudBaseUrl = providers.gradleProperty("CHIRP_CLOUD_BASE_URL").orElse("").get()
        buildConfigField(
            "String",
            "CHIRP_CLOUD_BASE_URL",
            "\"${cloudBaseUrl.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
        )

        // =====================================================================================
        // REL-01: SINGLE-DEVICE ABI FILTER — arm64-v8a ONLY.
        // This app is sideloaded onto exactly one personal device (Galaxy S25 Ultra, arm64).
        // The sherpa-onnx/onnxruntime/lame native payload is ~100.8 MB across 4 ABIs but only
        // ~25.5 MB for arm64-v8a; the other three ABIs are dead weight (minSdk 36 means no
        // 32-bit devices exist at all). REMOVE this filter (or add "x86_64") if the app ever
        // needs to run on an emulator or be distributed to other devices.
        // =====================================================================================
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (releaseSigningConfigured || localDebugStoreFile.isFile) {
            create("release") {
                if (releaseSigningConfigured) {
                    storeFile = file(checkNotNull(releaseStoreFile))
                    storePassword = checkNotNull(releaseStorePassword)
                    keyAlias = checkNotNull(releaseKeyAlias)
                    keyPassword = checkNotNull(releaseKeyPassword)
                } else {
                    // Keep local release builds compatible with the existing sideloaded app.
                    storeFile = localDebugStoreFile
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
            }
        }
    }

    buildTypes {
        release {
            // START-5/REL-02/REL-04/REL-05: R8 + resource shrinking are ON. The keep set in
            // proguard-rules.pro covers the sherpa-onnx JNI bridge, Hilt, Room, WorkManager,
            // and every Gson-reflection model (LLM clients, presets codec, key backup) — all
            // verified via mapping.txt after assembleRelease. The remaining gate is the
            // on-device smoke test (see ONDEVICE notes): dictation, record+transcribe,
            // OpenAI/Anthropic/Gemini chat, custom presets, key backup restore.
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            isJniDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }

        create("beta") {
            initWith(getByName("release"))
            // Keep the alpha package ID for an in-place upgrade. Changing it would strand the
            // user's selected IME, preferences, API keys, diagnostics, and pending recovery work.
            applicationIdSuffix = ".alpha"
            matchingFallbacks += listOf("release")
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
        buildConfig = true
    }

    androidResources {
        // I18N-17 (PLAN: en-only accepted). Strip the ~85 androidx/Material locale tables so
        // framework-owned UI (pickers, copy/paste menus) matches the app's English-only copy
        // instead of shipping a mixed-language experience, and drop the dead APK weight.
        localeFilters += listOf("en")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // REL-06: the sherpa-onnx AAR bundles three native libs but the Kotlin bridge only
            // ever calls System.loadLibrary("sherpa-onnx-jni") (OfflineRecognizer companion in
            // the AAR), and libsherpa-onnx-jni.so does NOT link against the C/C++ API libs
            // (verified with llvm-readelf: NEEDED = onnxruntime/log/android/c/m/dl only).
            // Excluding them saves ~4.7 MB of uncompressed (stored) arm64 payload.
            excludes += listOf("**/libsherpa-onnx-c-api.so", "**/libsherpa-onnx-cxx-api.so")
        }
    }
}

dependencies {
    baselineProfile(project(":baseline-profile"))

    // Internal modules
    implementation(project(":core-audio"))
    implementation(project(":core-recording-runtime"))
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
    implementation(project(":gguf-backend"))

    // Compose - latest stable
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.leakcanary.android)

    // Material Design Components (for XML theme parent)
    implementation(libs.material)

    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // LOAD-6: branded cold-start splash (AndroidX core-splashscreen).
    implementation(libs.androidx.core.splashscreen)

    // DataStore — backs the "Use system colors (Material You)" appearance preference (DECISIONS).
    implementation(libs.androidx.datastore.preferences)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // WorkManager (version + PLT-03 rationale live in gradle/libs.versions.toml).
    implementation(libs.androidx.work.runtime.ktx)

    // Startup (needed to disable default WorkManager initialization for Hilt)
    implementation(libs.androidx.startup.runtime)

    // START-5: installs the profile generated by :baseline-profile plus profiles bundled by
    // AndroidX on sideloaded release builds, so app and IME startup paths are AOT-ready.
    implementation(libs.androidx.profileinstaller)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // OkHttp for model download
    implementation(libs.okhttp)

    // Gson — chirp-backup envelope (Backup & Restore). Same version as feature-llm; every
    // serialized model is @Keep-annotated per the proguard-rules.pro Gson section.
    implementation(libs.gson)

    // Tests
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(project(":test-support"))
    testImplementation(libs.turbine)
    // Same version as the okhttp implementation dependency; exercises the HTTP Range
    // resume path of ModelDownloader against a real local server (ERR-2).
    testImplementation(libs.mockwebserver)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)

}
