#include <jni.h>
#include <android/log.h>

#include <atomic>
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
std::atomic<jlong> g_next_operation_id{0};
std::atomic<jlong> g_active_operation_id{0};
std::atomic<jlong> g_cancelled_operation_id{0};
std::string g_loaded_backend;
bool g_used_cpu_fallback = false;

struct decode_telemetry {
    jlong operation_id = 0;
    float load_ms = 0.0F;
    float mel_ms = 0.0F;
    float encode_ms = 0.0F;
    float decode_ms = 0.0F;
    bool aborted = false;
    transcribe_status status = TRANSCRIBE_OK;
};

decode_telemetry g_last_telemetry;

bool should_abort(void *) {
    const jlong active = g_active_operation_id.load(std::memory_order_acquire);
    return active != 0 &&
        g_cancelled_operation_id.load(std::memory_order_acquire) == active;
}

void set_error(const char * operation, transcribe_status status) {
    g_last_error = std::string(operation) + ": " + transcribe_status_string(status);
    __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", g_last_error.c_str());
}

void set_errno_error(const char * operation, int error_number = errno) {
    g_last_error = std::string(operation) + ": " + std::strerror(error_number);
    __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", g_last_error.c_str());
}

jlong begin_decode_locked() {
    if (g_session == nullptr) return 0;
    transcribe_reset_timings(g_session);
    const jlong operation_id = g_next_operation_id.fetch_add(1, std::memory_order_relaxed) + 1;
    g_cancelled_operation_id.store(0, std::memory_order_release);
    g_active_operation_id.store(operation_id, std::memory_order_release);
    return operation_id;
}

void capture_telemetry_locked(transcribe_status status) {
    transcribe_timings timings;
    transcribe_timings_init(&timings);
    if (g_session != nullptr) {
        transcribe_get_timings(g_session, &timings);
    }
    g_last_telemetry = {
        g_active_operation_id.load(std::memory_order_acquire),
        timings.load_ms,
        timings.mel_ms,
        timings.encode_ms,
        timings.decode_ms,
        g_session != nullptr && transcribe_was_aborted(g_session),
        status,
    };
    g_active_operation_id.store(0, std::memory_order_release);
}

jstring run_locked(JNIEnv * env, const float * pcm, int count, const char * operation) {
    if (g_session == nullptr) {
        g_last_error = "recognizer is not loaded";
        return nullptr;
    }

    transcribe_run_params run_params;
    transcribe_run_params_init(&run_params);
    run_params.timestamps = TRANSCRIBE_TIMESTAMPS_NONE;
    if (g_active_operation_id.load(std::memory_order_acquire) == 0) {
        begin_decode_locked();
    }
    const transcribe_status status = transcribe_run(g_session, pcm, count, &run_params);
    capture_telemetry_locked(status);
    if (status != TRANSCRIBE_OK) {
        set_error(operation, status);
        return nullptr;
    }

    const char * text = transcribe_full_text(g_session);
    return env->NewStringUTF(text == nullptr ? "" : text);
}

void close_locked() {
    g_active_operation_id.store(0, std::memory_order_release);
    if (g_session != nullptr) {
        transcribe_close(g_session);
        g_session = nullptr;
    }
    g_loaded_backend.clear();
    g_used_cpu_fallback = false;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_chirpboard_app_gguf_GgufNativeRecognizer_nativeSupportsVulkan(JNIEnv *, jobject) {
#if CHIRP_GGUF_VULKAN
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_chirpboard_app_gguf_GgufNativeRecognizer_nativeUsesKleidiAi(JNIEnv *, jobject) {
#if CHIRP_GGUF_KLEIDIAI
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_chirpboard_app_gguf_GgufNativeRecognizer_nativeLoad(
    JNIEnv * env,
    jobject,
    jstring model_path,
    jint threads,
    jint backend_code) {
    if (model_path == nullptr) return JNI_FALSE;
    const char * path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) return JNI_FALSE;

    std::lock_guard<std::mutex> lock(g_mutex);
    g_last_error.clear();

    transcribe_session_params session_params;
    transcribe_session_params_init(&session_params);
    session_params.n_threads = threads;

    transcribe_session * candidate = nullptr;
    const auto open_with_backend = [&](transcribe_backend_request backend) {
        transcribe_model_load_params load_params;
        transcribe_model_load_params_init(&load_params);
        load_params.backend = backend;
        return transcribe_open(path, &load_params, &session_params, &candidate);
    };

    const bool requested_vulkan = backend_code == 1;
    bool used_cpu_fallback = false;
    transcribe_status status = open_with_backend(
        requested_vulkan ? TRANSCRIBE_BACKEND_VULKAN : TRANSCRIBE_BACKEND_CPU);
    if (status != TRANSCRIBE_OK && requested_vulkan) {
        __android_log_print(
            ANDROID_LOG_WARN,
            kTag,
            "Vulkan load failed (%s); retrying on CPU",
            transcribe_status_string(status));
        if (candidate != nullptr) {
            transcribe_close(candidate);
            candidate = nullptr;
        }
        status = open_with_backend(TRANSCRIBE_BACKEND_CPU);
        used_cpu_fallback = status == TRANSCRIBE_OK;
    }
    env->ReleaseStringUTFChars(model_path, path);
    if (status != TRANSCRIBE_OK) {
        set_error("transcribe_open", status);
        if (candidate != nullptr) transcribe_close(candidate);
        return JNI_FALSE;
    }

    if (g_session != nullptr) transcribe_close(g_session);
    g_session = candidate;
    g_used_cpu_fallback = used_cpu_fallback;
    transcribe_set_abort_callback(g_session, should_abort, nullptr);

    const transcribe_model * model = transcribe_get_model(g_session);
    const char * loaded_backend = transcribe_model_backend(model);
    g_loaded_backend = loaded_backend == nullptr ? "unknown" : loaded_backend;
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
        g_loaded_backend.c_str(),
        threads,
        capabilities_loaded && capabilities.supports_streaming ? "true" : "false");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_chirpboard_app_gguf_GgufNativeRecognizer_nativeBeginDecode(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return begin_decode_locked();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_chirpboard_app_gguf_GgufNativeRecognizer_nativeCancelDecode(
    JNIEnv *,
    jobject,
    jlong operation_id) {
    if (operation_id <= 0 ||
        g_active_operation_id.load(std::memory_order_acquire) != operation_id) {
        return JNI_FALSE;
    }
    g_cancelled_operation_id.store(operation_id, std::memory_order_release);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_dev_chirpboard_app_gguf_GgufNativeRecognizer_nativeDecodeTelemetry(
    JNIEnv * env,
    jobject,
    jlong operation_id) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (operation_id <= 0 || g_last_telemetry.operation_id != operation_id) return nullptr;
    const jfloat values[] = {
        g_last_telemetry.load_ms,
        g_last_telemetry.mel_ms,
        g_last_telemetry.encode_ms,
        g_last_telemetry.decode_ms,
        g_last_telemetry.aborted ? 1.0F : 0.0F,
        static_cast<jfloat>(g_last_telemetry.status),
    };
    jfloatArray result = env->NewFloatArray(6);
    if (result != nullptr) env->SetFloatArrayRegion(result, 0, 6, values);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_chirpboard_app_gguf_GgufNativeRecognizer_nativeLoadedBackend(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return env->NewStringUTF(g_loaded_backend.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_chirpboard_app_gguf_GgufNativeRecognizer_nativeUsedCpuFallback(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    return g_used_cpu_fallback ? JNI_TRUE : JNI_FALSE;
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
    if (g_active_operation_id.load(std::memory_order_acquire) == 0) {
        begin_decode_locked();
    }
    const transcribe_status status =
        transcribe_run_batch(g_session, pcm_const.data(), lengths.data(), count, &run_params);
    capture_telemetry_locked(status);

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
