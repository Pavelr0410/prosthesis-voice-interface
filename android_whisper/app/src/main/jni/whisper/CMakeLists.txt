cmake_minimum_required(VERSION 3.10)
project(whisper_jni C CXX)
set(CMAKE_CXX_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Debug-сборка AGP не добавляет -O -> whisper работает в ~100 раз медленнее.
# Форсируем оптимизацию независимо от типа сборки.
if (NOT MSVC)
    add_compile_options(-O3)
endif()

set(WHISPER_LIB_DIR ${CMAKE_SOURCE_DIR}/../whisper.cpp)

# whisper.cpp: строим whisper и ggml как статические библиотеки
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# OpenMP на Android/NDK часто вызывает катастрофические тормоза — отключаем
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_LIB_DIR} ${CMAKE_CURRENT_BINARY_DIR}/whisper.cpp)

# JNI shared library, имя файла libwhisper.so (его грузит WhisperLib)
add_library(whisper_jni SHARED jni.c)
set_target_properties(whisper_jni PROPERTIES OUTPUT_NAME "whisper")

target_include_directories(whisper_jni PRIVATE
    ${WHISPER_LIB_DIR}/include
    ${WHISPER_LIB_DIR}/ggml/include
    ${WHISPER_LIB_DIR}/src)

target_link_libraries(whisper_jni PRIVATE whisper log android)

