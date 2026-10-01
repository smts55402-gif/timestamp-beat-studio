cmake_minimum_required(VERSION 3.22.1)

# JNI bridge for on-device transcription.
#
# whisper.cpp is fetched at *configure* time and pinned to v1.7.4.
# NOTE: configuring this module requires network access to github.com.

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

include(FetchContent)

# Disable everything we don't need BEFORE making the dependency available.
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2           OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS      OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    whispercpp
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp
    GIT_TAG v1.7.4
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(whispercpp)

add_library(tbs_whisper_jni SHARED whisper_jni.cpp)

target_link_libraries(tbs_whisper_jni PRIVATE whisper)
