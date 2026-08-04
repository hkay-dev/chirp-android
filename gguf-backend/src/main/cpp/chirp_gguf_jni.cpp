#include <jni.h>
#include <android/log.h>

#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <limits>
#include <mutex>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
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

void set_errno_error(const char * operation, int error_number = errno) {
    g_last_error = std::string(operation) + ": " + std::strerror(error_number);
    __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", g_last_error.c_str());
}

jstring run_locked(JNIEnv * env, const float * pcm, int count, const char * operation) {
    if (g_session == nullptr) {
        g_last_error = "recognizer is not loaded";
        return nullptr;
    }

    transcribe_run_params run_params;
    transcribe_run_params_init(&run_params);
    run_params.timestamps = TRANSCRIBE_TIMESTAMPS_NONE;
    const transcribe_status status = transcribe_run(g_session, pcm, count, &run_params);
    if (status != TRANSCRIBE_OK) {
        set_error(operation, status);
        return nullptr;
    }

    const char * text = transcribe_full_text(g_session);
    return env->NewStringUTF(text == nullptr ? "" : text);
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
    transcribe_capabilities capabilities;
    transcribe_capabilities_init(&capabilities);
    const bool capabilities_loaded =
        transcribe_model_get_capabilities(model, &capabilities) == TRANSCRIBE_OK;
    __android_log_print(
        ANDROID_LOG_INFO,
        kTag,
        "loaded model arch=%s variant=%s backend=%s threads=%d streaming=%s",
        transcribe_model_arch_string(model),
        transcribe_model_variant_string(model),
        transcribe_model_backend(model),
        threads,
        capabilities_loaded && capabilities.supports_streaming ? "true" : "false");
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

    jstring result = run_locked(env, pcm, count, "transcribe_run");
    env->ReleaseFloatArrayElements(samples, pcm, JNI_ABORT);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_chirpboard_app_gguf_GgufNativeRecognizer_nativeTranscribePcmFloatFile(
    JNIEnv * env,
    jobject,
    jstring file_path,
    jlong sample_count) {
    if (file_path == nullptr || sample_count <= 0 ||
        sample_count > std::numeric_limits<int>::max()) {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_last_error = "invalid float PCM file request";
        return nullptr;
    }

    std::lock_guard<std::mutex> lock(g_mutex);
    const char * path = env->GetStringUTFChars(file_path, nullptr);
    if (path == nullptr) return nullptr;
    const int fd = open(path, O_RDONLY | O_CLOEXEC);
    env->ReleaseStringUTFChars(file_path, path);
    if (fd < 0) {
        set_errno_error("open PCM file");
        return nullptr;
    }

    const uint64_t expected_bytes = static_cast<uint64_t>(sample_count) * sizeof(float);
    struct stat file_stat {};
    if (fstat(fd, &file_stat) != 0) {
        set_errno_error("stat PCM file");
        close(fd);
        return nullptr;
    }
    if (file_stat.st_size < 0 || static_cast<uint64_t>(file_stat.st_size) != expected_bytes) {
        g_last_error = "float PCM file size does not match its declared sample count";
        __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", g_last_error.c_str());
        close(fd);
        return nullptr;
    }

    void * mapping = mmap(nullptr, expected_bytes, PROT_READ, MAP_PRIVATE, fd, 0);
    const int mmap_error = errno;
    close(fd);
    if (mapping == MAP_FAILED) {
        set_errno_error("map PCM file", mmap_error);
        return nullptr;
    }
    madvise(mapping, expected_bytes, MADV_SEQUENTIAL);

    jstring result = run_locked(
        env,
        static_cast<const float *>(mapping),
        static_cast<int>(sample_count),
        "transcribe_run_file");
    munmap(mapping, expected_bytes);
    return result;
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
