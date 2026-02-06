cmake_minimum_required(VERSION 3.22.1)
project(similarity_native)

set(SIMILARITY_ROOT ${CMAKE_CURRENT_LIST_DIR}/../../../..)

# Optional: libjpeg-turbo for true lossless JPEG DCT transforms.
# To enable, add the source at: third_party/libjpeg-turbo
set(LIBJPEG_TURBO_ROOT ${SIMILARITY_ROOT}/third_party/libjpeg-turbo)
include(ExternalProject)

# SentencePiece (Apache-2.0)
# We build SentencePiece as a *static* library and link it into our JNI .so.
# This avoids shipping extra dependent .so files (which can cause startup crashes
# if they aren't packaged/loaded).
set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(SPM_BUILD_TEST OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
set(SPM_TCMALLOC_STATIC OFF CACHE BOOL "" FORCE)
set(SPM_PROTOBUF_PROVIDER "internal" CACHE STRING "" FORCE)
set(SPM_ABSL_PROVIDER "internal" CACHE STRING "" FORCE)

set(SENTENCEPIECE_ROOT ${SIMILARITY_ROOT}/third_party/sentencepiece)
if (NOT EXISTS ${SENTENCEPIECE_ROOT}/CMakeLists.txt)
    message(FATAL_ERROR "Missing third_party/sentencepiece. Run: git submodule update --init --recursive")
endif()
add_subdirectory(${SENTENCEPIECE_ROOT} ${CMAKE_CURRENT_BINARY_DIR}/sentencepiece_build)

# When building SentencePiece statically on Android, protobuf-lite logs via
# __android_log_write, so ensure the final link includes liblog.
if (ANDROID)
    target_link_libraries(sentencepiece-static PUBLIC log)
    target_link_libraries(sentencepiece_train-static PUBLIC log)
endif()

add_library(sentencepiece_jni SHARED sentencepiece_jni.cpp)

target_include_directories(sentencepiece_jni PRIVATE
    ${SENTENCEPIECE_ROOT}/src
)

target_compile_features(sentencepiece_jni PRIVATE cxx_std_17)

target_link_libraries(sentencepiece_jni
    sentencepiece
    log
    android
)

add_library(imageedit_jni SHARED)

if (EXISTS ${LIBJPEG_TURBO_ROOT}/CMakeLists.txt)
    # libjpeg-turbo explicitly forbids integration via add_subdirectory(), so build it as an
    # ExternalProject and link the resulting static lib into our JNI .so.
    set(LIBJPEG_TURBO_BINARY_DIR ${CMAKE_CURRENT_BINARY_DIR}/libjpeg_turbo_build)
    set(LIBTURBOJPEG_A ${LIBJPEG_TURBO_BINARY_DIR}/libturbojpeg.a)

    ExternalProject_Add(libturbojpeg_ep
        SOURCE_DIR ${LIBJPEG_TURBO_ROOT}
        BINARY_DIR ${LIBJPEG_TURBO_BINARY_DIR}
        CMAKE_GENERATOR ${CMAKE_GENERATOR}
        CMAKE_ARGS
            -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
            -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
            -DANDROID_ABI=${ANDROID_ABI}
            -DANDROID_PLATFORM=${ANDROID_PLATFORM}
            -DANDROID_NDK=${ANDROID_NDK}
            -DCMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE}
            -DCMAKE_POSITION_INDEPENDENT_CODE=ON
            -DENABLE_SHARED=OFF
            -DENABLE_STATIC=ON
            -DWITH_TURBOJPEG=ON
            -DWITH_SIMD=OFF
            -DWITH_TESTS=OFF
            -DWITH_TOOLS=OFF
        BUILD_BYPRODUCTS ${LIBTURBOJPEG_A}
        INSTALL_COMMAND ""
    )

    add_library(turbojpeg_static STATIC IMPORTED GLOBAL)
    set_target_properties(turbojpeg_static PROPERTIES IMPORTED_LOCATION ${LIBTURBOJPEG_A})
    add_dependencies(turbojpeg_static libturbojpeg_ep)

    target_sources(imageedit_jni PRIVATE imageedit_jni_turbo.cpp)
    target_compile_definitions(imageedit_jni PRIVATE SIMILARITY_WITH_LIBJPEG_TURBO=1)
    target_include_directories(imageedit_jni PRIVATE
        ${LIBJPEG_TURBO_ROOT}/src
        ${LIBJPEG_TURBO_BINARY_DIR}
    )
    add_dependencies(imageedit_jni libturbojpeg_ep)
    target_link_libraries(imageedit_jni turbojpeg_static)
else()
    # Keep a stub around for dev builds where libjpeg-turbo isn't present.
    target_sources(imageedit_jni PRIVATE imageedit_jni_stub.cpp)
    target_compile_definitions(imageedit_jni PRIVATE SIMILARITY_WITH_LIBJPEG_TURBO=0)
endif()

target_compile_features(imageedit_jni PRIVATE cxx_std_17)
target_link_libraries(imageedit_jni log android)
