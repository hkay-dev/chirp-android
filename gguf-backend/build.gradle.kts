plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

val kleidiAiEnabled =
    providers
        .gradleProperty("chirp.gguf.kleidiai")
        .map { it.equals("true", ignoreCase = true) }
        .orElse(false)

android {
    namespace = "dev.chirpboard.app.gguf"
    compileSdk = 36

    defaultConfig {
        minSdk = 36
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DTRANSCRIBE_BUILD_TESTS=OFF",
                    "-DTRANSCRIBE_BUILD_EXAMPLES=OFF",
                    "-DTRANSCRIBE_BUILD_TOOLS=OFF",
                    "-DTRANSCRIBE_BUILD_SHARED=OFF",
                    "-DTRANSCRIBE_USE_SYSTEM_BLAS=OFF",
                    "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16",
                    "-DCHIRP_GGUF_KLEIDIAI=${if (kleidiAiEnabled.get()) "ON" else "OFF"}",
                )
                cppFlags += listOf("-O3", "-DNDEBUG", "-march=armv8.2-a+dotprod+fp16")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs.useLegacyPackaging = false
    }
}
