plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("androidx.baselineprofile")
}

android {
    namespace = "dev.chirpboard.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.chirpboard.app"
        minSdk = 36
        targetSdk = 36
        versionCode = 31
        versionName = "3.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

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

    // =========================================================================================
    // REL-08: Release signing uses the LOCAL DEBUG KEYSTORE on purpose.
    // This is a personal, sideloaded, single-device app. Signing release with the debug key
    // means the release APK can UPDATE the currently-installed debug build in place (same
    // signature + same applicationId), preserving Room chirp.db, DataStore prefs, and the
    // encrypted LLM API keys. If this is ever switched to a real release keystore, the first
    // install of the re-keyed APK REQUIRES a full uninstall/reinstall, which DESTROYS all
    // app-private data (recordings metadata, transcripts, API keys) — only the model under
    // Documents/.chirpboard survives. Back up ~/.android/debug.keystore; it IS the app key.
    // =========================================================================================
    signingConfigs {
        create("release") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }

        create("ggufTrial") {
            initWith(getByName("release"))
            applicationIdSuffix = ".gguftrial"
            versionNameSuffix = "-gguf-trial"
            matchingFallbacks += listOf("release")
            buildConfigField("boolean", "GGUF_TRIAL", "true")
        }

        getByName("debug") {
            buildConfigField("boolean", "GGUF_TRIAL", "false")
        }

        getByName("release") {
            buildConfigField("boolean", "GGUF_TRIAL", "false")
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

androidComponents {
    onVariants(selector().withBuildType("ggufTrial")) { variant ->
        variant.packaging.jniLibs.excludes.add("**/libsherpa-onnx-jni.so")
        variant.packaging.jniLibs.excludes.add("**/libonnxruntime.so")
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
    "ggufTrialImplementation"(project(":gguf-backend"))

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

    // LOAD-6: branded cold-start splash (AndroidX core-splashscreen).
    implementation("androidx.core:core-splashscreen:1.0.1")

    // DataStore — backs the "Use system colors (Material You)" appearance preference (DECISIONS).
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.51.1")
    ksp("com.google.dagger:hilt-android-compiler:2.51.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // WorkManager (version + PLT-03 rationale live in gradle/libs.versions.toml).
    implementation(libs.androidx.work.runtime.ktx)

    // Startup (needed to disable default WorkManager initialization for Hilt)
    implementation("androidx.startup:startup-runtime:1.1.1")

    // START-5: installs the profile generated by :baseline-profile plus profiles bundled by
    // AndroidX on sideloaded release builds, so app and IME startup paths are AOT-ready.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // OkHttp for model download
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Gson — chirp-backup envelope (Backup & Restore). Same version as feature-llm; every
    // serialized model is @Keep-annotated per the proguard-rules.pro Gson section.
    implementation("com.google.code.gson:gson:2.10.1")

    // Tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation(libs.mockk)
    testImplementation(project(":test-support"))
    testImplementation("app.cash.turbine:turbine:1.0.0")
    // Same version as the okhttp implementation dependency; exercises the HTTP Range
    // resume path of ModelDownloader against a real local server (ERR-2).
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)

}
