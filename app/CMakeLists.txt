cmake_minimum_required(VERSION 3.22.1)
project(snapknow_jni)

# ─── ExecuTorch include path ────────────────────────────────────────────────
# Set EXECUTORCH_ROOT to the root of your executorch checkout, e.g.:
#   cmake ... -DEXECUTORCH_ROOT=/path/to/executorch
# If not set, falls back to a default. The AAR ships the prebuilt .so files;
# these headers are only needed to compile the JNI bridge.
if (NOT DEFINED EXECUTORCH_ROOT)
    set(EXECUTORCH_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../executorch")
endif ()

# ─── Prebuilt ExecuTorch shared libraries (from the AAR) ────────────────────
set(EXECUTORCH_LIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/libs/${ANDROID_ABI}")

add_library(executorch SHARED IMPORTED)
set_target_properties(executorch PROPERTIES
    IMPORTED_LOCATION "${EXECUTORCH_LIBS_DIR}/libexecutorch.so"
)

add_library(executorch_core SHARED IMPORTED)
set_target_properties(executorch_core PROPERTIES
    IMPORTED_LOCATION "${EXECUTORCH_LIBS_DIR}/libexecutorch_core.so"
)

# ─── JNI bridge shared library ──────────────────────────────────────────────
add_library(snapknow_jni SHARED
    src/main/cpp/executorch_jni.cpp
)

target_include_directories(snapknow_jni PRIVATE
    "${EXECUTORCH_ROOT}"
    "${EXECUTORCH_ROOT}/extension/module"
)

target_compile_options(snapknow_jni PRIVATE -std=c++17 -O2)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(snapknow_jni
    executorch
    executorch_core
    ${log-lib}
    ${android-lib}
)
