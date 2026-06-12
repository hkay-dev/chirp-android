# Chirpboard R8 / ProGuard keep rules.
#
# NOTE: R8 is NOT yet enabled (isMinifyEnabled = false in build.gradle.kts). These rules are
# staged so a release build can be turned on once it has been verified end-to-end on device.
# See START-5: enabling R8 requires a verified assembleRelease run (signing config + a smoke
# test that recognition, recording, Hilt graph, Room, and the IME all work shrunk). Until then
# these rules document the conservative keep set R8 will need.

# ---------------------------------------------------------------------------
# sherpa-onnx JNI bridge. The native library accesses these Kotlin classes,
# their fields, and constructors reflectively from C++; shrinking/renaming any
# of them breaks recognizer construction at runtime with no compile-time signal.
# ---------------------------------------------------------------------------
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }

# Keep all native method bindings and any class that declares one.
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---------------------------------------------------------------------------
# Hilt / Dagger generated graph. Hilt relies on generated components,
# annotations, and reflective entry points.
# ---------------------------------------------------------------------------
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper
-keep,allowobfuscation @interface dagger.hilt.android.AndroidEntryPoint
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keepclasseswithmembers class * {
    @javax.inject.Inject <init>(...);
}

# ---------------------------------------------------------------------------
# WorkManager workers are instantiated reflectively (by name) via the
# HiltWorkerFactory; keep ListenableWorker subclasses and their constructors.
# ---------------------------------------------------------------------------
-keep class * extends androidx.work.ListenableWorker {
    public <init>(...);
}

# ---------------------------------------------------------------------------
# Room: entities, DAOs, and the generated *_Impl database classes are wired
# reflectively at runtime. Keep the generated Room runtime artifacts.
# ---------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-dontwarn androidx.room.paging.**

# ---------------------------------------------------------------------------
# Kotlin coroutines / metadata. Keep coroutine internals and Kotlin metadata
# so reflection-based libraries (Hilt, Room) keep working.
# ---------------------------------------------------------------------------
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-keepclassmembers class kotlin.coroutines.SafeContinuation { volatile <fields>; }
-dontwarn kotlinx.coroutines.**
-keep class kotlin.Metadata { *; }

# ---------------------------------------------------------------------------
# Speech recognition contract surface: ChirpRecognitionService and
# VoiceRecognitionActivity are referenced by the framework via the manifest /
# RECOGNIZE_SPEECH intents; the AndroidEntryPoint rule above keeps the service,
# but keep the public API surface defensively.
# ---------------------------------------------------------------------------
-keep class dev.chirpboard.app.ChirpRecognitionService { *; }
-keep class dev.chirpboard.app.VoiceRecognitionActivity { *; }

# Keep parcelable creators and enum valueOf/values (used reflectively).
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
