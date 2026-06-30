/**
 * executorch_jni.cpp
 *
 * JNI bridge between Kotlin and ExecuTorch's C++ Module API.
 * Compiled into libsnapknow_jni.so via CMakeLists.txt.
 *
 * API (called from ExecuTorchModule.kt):
 *   nativeLoadModel(path)           → handle (jlong)
 *   nativeRunNamedInference(handle, ...) → float[] output
 *   nativeHasMethod(handle, name)   → boolean
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

static Module* loadForwardModule(const std::string& path) {
    auto* module = new Module(path, Module::MlockConfig::UseMlockIgnoreErrors);
    auto load_result = module->load_method("forward");
    if (load_result != Error::Ok) {
        LOGE("Failed to load 'forward' method, error=%d", static_cast<int>(load_result));
        delete module;
        return nullptr;
    }
    return module;
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

    auto* module = loadForwardModule(path);
    if (module == nullptr) {
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
Java_com_snapknow_app_inference_ExecuTorchModule_nativeRunNamedInference(
        JNIEnv* env, jobject /*thiz*/,
        jlong handle, jstring jMethodName, jfloatArray jInput, jlongArray jShape) {

    if (handle == 0L) {
        LOGE("Invalid module handle");
        return env->NewFloatArray(0);
    }

    auto* module = reinterpret_cast<Module*>(handle);
    std::string method_name = jstringToStd(env, jMethodName);

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
    auto execResult = module->execute(method_name, {EValue(tensorResult.get())});

    if (!execResult.ok()) {
        LOGE("Inference failed for method '%s', error=%d", method_name.c_str(), static_cast<int>(execResult.error()));
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

JNIEXPORT jboolean JNICALL
Java_com_snapknow_app_inference_ExecuTorchModule_nativeHasMethod(
        JNIEnv* env, jobject /*thiz*/, jlong handle, jstring jMethodName) {

    if (handle == 0L) {
        return JNI_FALSE;
    }

    auto* module = reinterpret_cast<Module*>(handle);
    std::string method_name = jstringToStd(env, jMethodName);
    auto load_result = module->load_method(method_name);
    return load_result == Error::Ok ? JNI_TRUE : JNI_FALSE;
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

JNIEXPORT jlong JNICALL
Java_com_snapknow_app_voice_WhisperDecoderModule_nativeLoadModel(
        JNIEnv* env, jobject /*thiz*/, jstring jPath) {
    std::string path = jstringToStd(env, jPath);
    LOGI("Loading Whisper decoder: %s", path.c_str());
    auto* module = loadForwardModule(path);
    return reinterpret_cast<jlong>(module);
}

JNIEXPORT jlong JNICALL
Java_com_snapknow_app_voice_WhisperDecoderModule_nativeRunStep(
        JNIEnv* env, jobject /*thiz*/,
        jlong handle,
        jlong token_id,
        jfloatArray jAttentionMask,
        jfloatArray jEncoderOutput,
        jlongArray jEncoderShape,
        jlong position) {

    if (handle == 0L) {
        LOGE("Invalid Whisper decoder handle");
        return 0L;
    }

    auto* module = reinterpret_cast<Module*>(handle);
    auto token_value = static_cast<int64_t>(token_id);
    auto position_value = static_cast<int64_t>(position);

    jfloat* attention_ptr = env->GetFloatArrayElements(jAttentionMask, nullptr);
    jsize attention_len = env->GetArrayLength(jAttentionMask);

    jfloat* encoder_ptr = env->GetFloatArrayElements(jEncoderOutput, nullptr);
    jlong* encoder_shape_ptr = env->GetLongArrayElements(jEncoderShape, nullptr);
    jsize encoder_shape_len = env->GetArrayLength(jEncoderShape);

    std::vector<exec_aten::SizesType> encoder_sizes(
        encoder_shape_ptr, encoder_shape_ptr + encoder_shape_len);

    auto token_tensor = from_blob(&token_value, {1, 1}, exec_aten::ScalarType::Long);
    auto attention_tensor = from_blob(
        attention_ptr,
        {1, 1, 1, static_cast<exec_aten::SizesType>(attention_len)},
        exec_aten::ScalarType::Float
    );
    auto encoder_tensor = from_blob(
        encoder_ptr,
        encoder_sizes,
        exec_aten::ScalarType::Float
    );
    auto position_tensor = from_blob(&position_value, {1}, exec_aten::ScalarType::Long);

    env->ReleaseFloatArrayElements(jAttentionMask, attention_ptr, JNI_ABORT);
    env->ReleaseFloatArrayElements(jEncoderOutput, encoder_ptr, JNI_ABORT);
    env->ReleaseLongArrayElements(jEncoderShape, encoder_shape_ptr, JNI_ABORT);

    if (!token_tensor.ok() || !attention_tensor.ok() || !encoder_tensor.ok() || !position_tensor.ok()) {
        LOGE("Failed to prepare Whisper decoder inputs");
        return 0L;
    }

    auto execResult = module->execute(
        "forward",
        {
            EValue(token_tensor.get()),
            EValue(attention_tensor.get()),
            EValue(encoder_tensor.get()),
            EValue(position_tensor.get())
        }
    );

    if (!execResult.ok()) {
        LOGE("Whisper decoder inference failed, error=%d", static_cast<int>(execResult.error()));
        return 0L;
    }

    const auto& outputs = execResult.get();
    if (outputs.empty() || !outputs[0].isTensor()) {
        LOGE("Unexpected Whisper decoder output");
        return 0L;
    }

    const auto& logits = outputs[0].toTensor();
    const float* logits_data = logits.const_data_ptr<float>();
    int64_t logits_size = logits.numel();
    if (logits_size <= 0) {
        LOGE("Empty Whisper decoder logits");
        return 0L;
    }

    int64_t best_index = 0;
    float best_value = logits_data[0];
    for (int64_t index = 1; index < logits_size; ++index) {
        if (logits_data[index] > best_value) {
            best_value = logits_data[index];
            best_index = index;
        }
    }

    return static_cast<jlong>(best_index);
}

JNIEXPORT void JNICALL
Java_com_snapknow_app_voice_WhisperDecoderModule_nativeDestroyModel(
        JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle != 0L) {
        delete reinterpret_cast<Module*>(handle);
    }
}

} // extern "C"
