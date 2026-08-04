#include <jni.h>
#include <android/log.h>

#include <mutex>
#include <string>
#include <vector>

#include "transcribe.h"

namespace {

constexpr const char * kTag = "ChirpGgufNative";
std::mutex g_mutex;
transcribe_session * g_session = nullptr;
std::string g_last_error;

void set_error(const char * operation, transcribe_status status) {
    g_last_error = std::string(operation) + ": " + transcribe_status_string(status);
    __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", g_last_error.c_str());
}

void close_locked() {
    if (g_session != nullptr) {
        transcribe_close(g_session);
        g_session = nullptr;
    }
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_chirpboard_app_gguf_GgufNativeRecognizer_nativeLoad(
    JNIEnv * env,
    jobject,
    jstring model_path,
    jint threads) {
    if (model_path == nullptr) return JNI_FALSE;
    const char * path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) return JNI_FALSE;

    std::lock_guard<std::mutex> lock(g_mutex);
    close_locked();
    g_last_error.clear();

    transcribe_session_params session_params;
    transcribe_session_params_init(&session_params);
    session_params.n_threads = threads;

    const transcribe_status status = transcribe_open(path, nullptr, &session_params, &g_session);
    env->ReleaseStringUTFChars(model_path, path);
    if (status != TRANSCRIBE_OK) {
        set_error("transcribe_open", status);
        close_locked();
        return JNI_FALSE;
    }

    const transcribe_model * model = transcribe_get_model(g_session);
    __android_log_print(
        ANDROID_LOG_INFO,
        kTag,
        "loaded model arch=%s variant=%s backend=%s threads=%d",
        transcribe_model_arch_string(model),
        transcribe_model_variant_string(model),
        transcribe_model_backend(model),
        threads);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_chirpboard_app_gguf_GgufNativeRecognizer_nativeIsLoaded(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_session != nullptr ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_chirpboard_app_gguf_GgufNativeRecognizer_nativeTranscribe(
    JNIEnv * env,
    jobject,
    jfloatArray samples) {
    if (samples == nullptr) return nullptr;
    const jsize count = env->GetArrayLength(samples);
    if (count <= 0) return env->NewStringUTF("");

    std::lock_guard<std::mutex> lock(g_mutex);
    jfloat * pcm = env->GetFloatArrayElements(samples, nullptr);
    if (pcm == nullptr) return nullptr;

    if (g_session == nullptr) {
        env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);
        g_last_error = "recognizer is not loaded";
        return nullptr;
    }

    transcribe_run_params run_params;
    transcribe_run_params_init(&run_params);
    run_params.timestamps = TRANSCRIBE_TIMESTAMPS_NONE;
    const transcribe_status status = transcribe_run(g_session, pcm, count, &run_params);
    env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);
    if (status != TRANSCRIBE_OK) {
        set_error("transcribe_run", status);
        return nullptr;
    }

    const char * text = transcribe_full_text(g_session);
    return env->NewStringUTF(text == nullptr ? "" : text);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_chirpboard_app_gguf_GgufNativeRecognizer_nativeTranscribeBatch(
    JNIEnv * env,
    jobject,
    jobjectArray sample_batches) {
    if (sample_batches == nullptr) return nullptr;
    const jsize count = env->GetArrayLength(sample_batches);
    if (count <= 0) return nullptr;

    std::lock_guard<std::mutex> lock(g_mutex);
    std::vector<jfloatArray> arrays;
    std::vector<jfloat *> pcm;
    std::vector<const float *> pcm_const;
    std::vector<int> lengths;
    arrays.reserve(count);
    pcm.reserve(count);
    pcm_const.reserve(count);
    lengths.reserve(count);

    for (jsize i = 0; i < count; ++i) {
        auto array = static_cast<jfloatArray>(env->GetObjectArrayElement(sample_batches, i));
        if (array == nullptr || env->GetArrayLength(array) <= 0) {
            for (size_t j = 0; j < arrays.size(); ++j) {
                env->ReleaseFloatArrayElements(arrays[j], pcm[j], JNI_ABORT);
                env->DeleteLocalRef(arrays[j]);
            }
            g_last_error = "batch contains empty audio";
            return nullptr;
        }
        jfloat * samples = env->GetFloatArrayElements(array, nullptr);
        if (samples == nullptr) {
            for (size_t j = 0; j < arrays.size(); ++j) {
                env->ReleaseFloatArrayElements(arrays[j], pcm[j], JNI_ABORT);
                env->DeleteLocalRef(arrays[j]);
            }
            env->DeleteLocalRef(array);
            return nullptr;
        }
        arrays.push_back(array);
        pcm.push_back(samples);
        pcm_const.push_back(samples);
        lengths.push_back(env->GetArrayLength(array));
    }

    if (g_session == nullptr) {
        for (size_t i = 0; i < arrays.size(); ++i) {
            env->ReleaseFloatArrayElements(arrays[i], pcm[i], JNI_ABORT);
            env->DeleteLocalRef(arrays[i]);
        }
        g_last_error = "recognizer is not loaded";
        return nullptr;
    }

    transcribe_run_params run_params;
    transcribe_run_params_init(&run_params);
    run_params.timestamps = TRANSCRIBE_TIMESTAMPS_NONE;
    const transcribe_status status =
        transcribe_run_batch(g_session, pcm_const.data(), lengths.data(), count, &run_params);

    for (size_t i = 0; i < arrays.size(); ++i) {
        env->ReleaseFloatArrayElements(arrays[i], pcm[i], JNI_ABORT);
        env->DeleteLocalRef(arrays[i]);
    }
    if (status != TRANSCRIBE_OK) {
        set_error("transcribe_run_batch", status);
        return nullptr;
    }

    jclass string_class = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(count, string_class, nullptr);
    for (jsize i = 0; i < count; ++i) {
        const transcribe_status item_status = transcribe_batch_status(g_session, i);
        if (item_status != TRANSCRIBE_OK) {
            set_error("transcribe_batch_status", item_status);
            env->DeleteLocalRef(result);
            env->DeleteLocalRef(string_class);
            return nullptr;
        }
        const char * text = transcribe_batch_full_text(g_session, i);
        jstring item = env->NewStringUTF(text == nullptr ? "" : text);
        env->SetObjectArrayElement(result, i, item);
        env->DeleteLocalRef(item);
    }
    env->DeleteLocalRef(string_class);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_chirpboard_app_gguf_GgufNativeRecognizer_nativeLastError(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return env->NewStringUTF(g_last_error.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_chirpboard_app_gguf_GgufNativeRecognizer_nativeRelease(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    close_locked();
    g_last_error.clear();
}
