cmake_minimum_required(VERSION 3.22.1)
project(chirp_gguf LANGUAGES C CXX)

include(FetchContent)

# KleidiAI is the measured default. GGML keeps its generic CPU buffer type alongside KleidiAI, so
# unsupported operations and CPUs still fall back safely.
option(CHIRP_GGUF_KLEIDIAI "Enable GGML KleidiAI CPU kernels" ON)

# OFF in shipped builds: no Vulkan transcribe.cpp backend is compiled in, and the app hides the
# Vulkan compute choice when nativeSupportsVulkan() reports false. Turning this ON requires
# transcribe.cpp to be built with its Vulkan backend as well.
option(TRANSCRIBE_VULKAN "Enable the Vulkan compute backend" OFF)
set(GGML_CPU_KLEIDIAI ${CHIRP_GGUF_KLEIDIAI} CACHE BOOL "Enable GGML KleidiAI CPU kernels" FORCE)

set(FETCHCONTENT_QUIET OFF)
FetchContent_Declare(
    transcribe_cpp
    GIT_REPOSITORY https://github.com/handy-computer/transcribe.cpp.git
    GIT_TAG 553f1099a2b3a5bc4421894be171f09960fc0f3a
    # No GIT_SHALLOW: a depth-1 clone only contains the default branch head, so
    # checking out this pinned commit fails once upstream moves past it.
    GIT_PROGRESS TRUE
)
FetchContent_MakeAvailable(transcribe_cpp)

# transcribe.cpp currently uses CMAKE_SOURCE_DIR for its public include path.
# Restore the correct path when it is embedded under this Android library.
target_include_directories(transcribe PUBLIC ${transcribe_cpp_SOURCE_DIR}/include)

add_library(chirp_gguf SHARED chirp_gguf_jni.cpp)

target_compile_features(chirp_gguf PRIVATE cxx_std_17)
# -O3 -DNDEBUG -march=armv8.2-a+dotprod+fp16 come from the Gradle cppFlags, which apply to
# the whole native tree (transcribe.cpp and GGML included). NDEBUG is forced even in debug
# variants on purpose: GGML's asserts make unoptimized decode unusably slow on-device.
target_compile_definitions(
    chirp_gguf
    PRIVATE
        CHIRP_GGUF_KLEIDIAI=$<BOOL:${CHIRP_GGUF_KLEIDIAI}>
        CHIRP_GGUF_VULKAN=$<BOOL:${TRANSCRIBE_VULKAN}>
)
target_include_directories(chirp_gguf PRIVATE ${transcribe_cpp_SOURCE_DIR}/include)
target_link_libraries(chirp_gguf PRIVATE transcribe android log)
