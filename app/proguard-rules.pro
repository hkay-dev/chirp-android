# Chirpboard R8 / ProGuard keep rules.
#
# R8 IS ENABLED for release (isMinifyEnabled + isShrinkResources in build.gradle.kts,
# REL-02/04/05). The keep set below covers every reflection surface in the app:
# sherpa-onnx JNI, Hilt, Room, WorkManager, and ALL Gson-(de)serialized models.
# Verified via mapping.txt inspection after :app:assembleRelease — if you add a new
# Gson model class anywhere, annotate it with @androidx.annotation.Keep (see
# GeminiModels.kt / LlmChatService.kt for the pattern).

# ---------------------------------------------------------------------------
# sherpa-onnx JNI bridge. The native library accesses these Kotlin classes,
# their fields, and constructors reflectively from C++; shrinking/renaming any
# of them breaks recognizer construction at runtime with no compile-time signal.
# REL-07: the sherpa-onnx AAR's own proguard.txt is EMPTY (verified 1.12.19) —
# these app-side rules are the ONLY protection for the JNI bridge. Do not
# "clean them up" on the assumption the AAR protects itself.
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

# ---------------------------------------------------------------------------
# Gson (REL-02/REL-05). Gson 2.10.1 ships NO embedded R8 rules (those arrived
# in 2.11.0), and R8 full mode otherwise (a) strips/merges model classes whose
# fields are only touched reflectively, and (b) erases the generic Signature
# attribute that anonymous `object : TypeToken<...>` subclasses depend on,
# crashing fromJson with "TypeToken must be created with a type argument".
#
# Every Gson model class in this app additionally carries @androidx.annotation.Keep
# (GeminiModels, LlmChatService request/response models, StructuredOutcome-
# ExtractionResponse, ProcessingModeStoreCodec envelopes, LlmSettingsSnapshot);
# the rules below are the library-level backstop.
# ---------------------------------------------------------------------------
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# TypeToken subclasses (incl. anonymous ones) need their generic signature alive.
-keep class com.google.gson.reflect.TypeToken { *; }
-keep,allowobfuscation class * extends com.google.gson.reflect.TypeToken

# Defense in depth: never strip fields annotated for Gson, wherever they live.
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}

-dontwarn sun.misc.Unsafe
