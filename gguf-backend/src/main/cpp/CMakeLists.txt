cmake_minimum_required(VERSION 3.22.1)
project(chirp_gguf LANGUAGES C CXX)

include(FetchContent)

set(FETCHCONTENT_QUIET OFF)
FetchContent_Declare(
    transcribe_cpp
    GIT_REPOSITORY https://github.com/handy-computer/transcribe.cpp.git
    GIT_TAG 553f1099a2b3a5bc4421894be171f09960fc0f3a
    GIT_SHALLOW TRUE
    GIT_PROGRESS TRUE
)
FetchContent_MakeAvailable(transcribe_cpp)

# transcribe.cpp currently uses CMAKE_SOURCE_DIR for its public include path.
# Restore the correct path when it is embedded under this Android library.
target_include_directories(transcribe PUBLIC ${transcribe_cpp_SOURCE_DIR}/include)

add_library(chirp_gguf SHARED chirp_gguf_jni.cpp)

target_compile_features(chirp_gguf PRIVATE cxx_std_17)
target_compile_options(chirp_gguf PRIVATE -O3 -DNDEBUG -march=armv8.2-a+dotprod+fp16)
target_include_directories(chirp_gguf PRIVATE ${transcribe_cpp_SOURCE_DIR}/include)
target_link_libraries(chirp_gguf PRIVATE transcribe android log)
