/**
 * executorch_jni.cpp
 *
 * JNI bridge between Kotlin and ExecuTorch's C++ Module API.
 * Compiled into libsnapknow_jni.so via CMakeLists.txt.
 *
 * API (called from ExecuTorchModule.kt):
 *   nativeLoadModel(path)           → handle (jlong)
 *   nativeRunInference(handle, ...) → float[] output
 *   nativeDestroyModule(handle)     → void
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <memory>

// ExecuTorch headers (available after building ExecuTorch)
#include "executorch/extension/module/module.h"
#include "executorch/runtime/core/exec_aten/exec_aten.h"

#define LOG_TAG "SnapKnowJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace executorch::extension;
using namespace executorch::runtime;

// ─── Helpers ─────────────────────────────────────────────────────────────────

static std::string jstringToStd(JNIEnv* env, jstring js) {
    const char* chars = env->GetStringUTFChars(js, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(js, chars);
    return result;
}

// ─── JNI exports ─────────────────────────────────────────────────────────────

extern "C" {

/**
 * Load a .pte model from [path] and return an opaque handle.
 * Returns 0 on failure.
 */
JNIEXPORT jlong JNICALL
Java_com_snapknow_app_inference_ExecuTorchModule_nativeLoadModel(
        JNIEnv* env, jobject /*thiz*/, jstring jPath) {

    std::string path = jstringToStd(env, jPath);
    LOGI("Loading model: %s", path.c_str());

    auto* module = new Module(path, Module::MlockConfig::UseMlockIgnoreErrors);
    // Pre-load the method to warm up on-device compilation
    auto load_result = module->load_method("forward");
    if (load_result != Error::Ok) {
        LOGE("Failed to load 'forward' method, error=%d", static_cast<int>(load_result));
        delete module;
        return 0L;
    }

    LOGI("Model loaded successfully");
    return reinterpret_cast<jlong>(module);
}

/**
 * Run a single "forward" inference pass.
 *
 * @param handle     Opaque pointer returned by nativeLoadModel
 * @param jInput     Flat float array of input data (e.g. CHW image pixels)
 * @param jShape     Long array describing tensor shape (e.g. {1,3,112,112})
 * @return           Flat float array of model output, or empty array on error
 */
JNIEXPORT jfloatArray JNICALL
Java_com_snapknow_app_inference_ExecuTorchModule_nativeRunInference(
        JNIEnv* env, jobject /*thiz*/,
        jlong handle, jfloatArray jInput, jlongArray jShape) {

    if (handle == 0L) {
        LOGE("Invalid module handle");
        return env->NewFloatArray(0);
    }

    auto* module = reinterpret_cast<Module*>(handle);

    // ── Unpack input ────────────────────────────────────────────────────────
    jsize inputLen  = env->GetArrayLength(jInput);
    jsize shapeLen  = env->GetArrayLength(jShape);

    jfloat* inputPtr = env->GetFloatArrayElements(jInput, nullptr);
    jlong*  shapePtr = env->GetLongArrayElements(jShape, nullptr);

    std::vector<exec_aten::SizesType> sizes(shapePtr, shapePtr + shapeLen);

    // ExecuTorch tensor wrapping (no copy — uses the JVM-backed memory)
    auto tensorResult = from_blob(
        inputPtr,
        sizes,
        exec_aten::ScalarType::Float
    );

    env->ReleaseFloatArrayElements(jInput, inputPtr, JNI_ABORT);
    env->ReleaseLongArrayElements(jShape, shapePtr, JNI_ABORT);

    if (!tensorResult.ok()) {
        LOGE("Failed to create input tensor");
        return env->NewFloatArray(0);
    }

    // ── Execute model ────────────────────────────────────────────────────────
    auto execResult = module->execute("forward", {EValue(tensorResult.get())});

    if (!execResult.ok()) {
        LOGE("Inference failed, error=%d", static_cast<int>(execResult.error()));
        return env->NewFloatArray(0);
    }

    // ── Extract output ────────────────────────────────────────────────────────
    const auto& outputs = execResult.get();
    if (outputs.empty() || !outputs[0].isTensor()) {
        LOGE("Unexpected output type");
        return env->NewFloatArray(0);
    }

    const auto& outTensor = outputs[0].toTensor();
    int64_t outSize = outTensor.numel();
    const float* outData = outTensor.const_data_ptr<float>();

    jfloatArray result = env->NewFloatArray(static_cast<jsize>(outSize));
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(outSize), outData);

    LOGI("Inference OK, output size=%lld", (long long)outSize);
    return result;
}

/**
 * Free the native module. Must be called when the Kotlin object is GC'd.
 */
JNIEXPORT void JNICALL
Java_com_snapknow_app_inference_ExecuTorchModule_nativeDestroyModule(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {

    if (handle != 0L) {
        delete reinterpret_cast<Module*>(handle);
        LOGI("Module destroyed");
    }
}

} // extern "C"
