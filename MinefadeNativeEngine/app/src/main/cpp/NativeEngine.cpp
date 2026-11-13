#include "engine/Engine.h"

#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>
#include <cstdint>
#include <exception>
#include <atomic>

namespace {
constexpr const char* kTag = "MinefadeNativeJNI";
std::atomic_bool gEngineFailure{false};
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_minefade_nativeengine_NativeBridge_nativeInit(JNIEnv* env, jclass, jfloat density,
                                                           jbyteArray atlas, jint width, jint height) {
    if (atlas == nullptr || width <= 0 || height <= 0) return JNI_FALSE;
    const jsize length = env->GetArrayLength(atlas);
    if (length <= 0) return JNI_FALSE;
    auto& engine = minefade::GetEngine();
    gEngineFailure.store(false, std::memory_order_release);
    try {
        std::vector<std::uint8_t> pixels(static_cast<std::size_t>(length));
        env->GetByteArrayRegion(atlas, 0, length, reinterpret_cast<jbyte*>(pixels.data()));
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            engine.Shutdown();
            return JNI_FALSE;
        }
        const bool result = engine.Initialise(static_cast<float>(density), pixels.data(), pixels.size(),
                                               static_cast<int>(width), static_cast<int>(height));
        __android_log_print(result ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, kTag,
                            "Engine initialisation %s", result ? "succeeded" : "failed");
        return result ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& error) {
        const std::string detail = std::string("Excepción C++ durante el arranque: ") + error.what();
        engine.SetLastInitError(detail);
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Engine initialisation threw: %s", error.what());
    } catch (...) {
        engine.SetLastInitError("Excepción C++ desconocida durante el arranque del motor.");
        __android_log_print(ANDROID_LOG_ERROR, kTag,
                            "Engine initialisation threw an unknown native exception");
    }
    // Never allow a C++ exception to cross the JNI boundary or leave a half-built
    // GL world around for the Retry button to inherit.
    engine.Shutdown();
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_minefade_nativeengine_NativeBridge_nativeGetLastInitError(JNIEnv* env, jclass) {
    const std::string message = minefade::GetEngine().LastInitError();
    return env->NewStringUTF(message.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_minefade_nativeengine_NativeBridge_nativeResize(JNIEnv*, jclass, jint width, jint height) {
    minefade::GetEngine().Resize(static_cast<int>(width), static_cast<int>(height));
}

extern "C" JNIEXPORT void JNICALL
Java_com_minefade_nativeengine_NativeBridge_nativeRender(JNIEnv*, jclass) {
    if (gEngineFailure.load(std::memory_order_acquire)) return;
    auto& engine = minefade::GetEngine();
    try {
        engine.Render();
    } catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Render failed: %s", error.what());
        gEngineFailure.store(true, std::memory_order_release);
        engine.Shutdown();
    } catch (...) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Render failed with an unknown native exception");
        gEngineFailure.store(true, std::memory_order_release);
        engine.Shutdown();
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_minefade_nativeengine_NativeBridge_nativeConsumeEngineFailure(JNIEnv*, jclass) {
    return gEngineFailure.exchange(false, std::memory_order_acq_rel) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_minefade_nativeengine_NativeBridge_nativeTouch(JNIEnv*, jclass, jint action, jint pointerId,
                                                         jfloat x, jfloat y) {
    minefade::GetEngine().Touch(static_cast<int>(action), static_cast<int>(pointerId),
                                static_cast<float>(x), static_cast<float>(y));
}

extern "C" JNIEXPORT void JNICALL
Java_com_minefade_nativeengine_NativeBridge_nativePause(JNIEnv*, jclass) {
    minefade::GetEngine().Pause();
}

extern "C" JNIEXPORT void JNICALL
Java_com_minefade_nativeengine_NativeBridge_nativeResume(JNIEnv*, jclass) {
    minefade::GetEngine().Resume();
}

extern "C" JNIEXPORT void JNICALL
Java_com_minefade_nativeengine_NativeBridge_nativeSetStoragePath(JNIEnv* env, jclass, jstring path) {
    if (path == nullptr) return;
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) return;
    minefade::GetEngine().SetStoragePath(chars);
    env->ReleaseStringUTFChars(path, chars);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_minefade_nativeengine_NativeBridge_nativeConsumeExitRequest(JNIEnv*, jclass) {
    return minefade::GetEngine().ConsumeExitRequest() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_minefade_nativeengine_NativeBridge_nativeConsumeSoundEvents(JNIEnv*, jclass) {
    return static_cast<jint>(minefade::GetEngine().ConsumeSoundEvents());
}

extern "C" JNIEXPORT void JNICALL
Java_com_minefade_nativeengine_NativeBridge_nativeSetMultiplayerMode(JNIEnv*, jclass, jboolean enabled, jint slot) {
    minefade::GetEngine().SetMultiplayerMode(enabled == JNI_TRUE, static_cast<int>(slot));
}

extern "C" JNIEXPORT void JNICALL
Java_com_minefade_nativeengine_NativeBridge_nativeSetGraphicsOptions(
        JNIEnv*, jclass, jint quality, jint renderDistanceChunks, jboolean waterWaves,
        jfloat fieldOfView, jboolean contactShadows) {
    minefade::GetEngine().SetGraphicsOptions(static_cast<int>(quality),
        static_cast<int>(renderDistanceChunks), waterWaves == JNI_TRUE,
        static_cast<float>(fieldOfView), contactShadows == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_minefade_nativeengine_NativeBridge_nativeSetInputOptions(
        JNIEnv*, jclass, jfloat sensitivity, jboolean invertLookY) {
    minefade::GetEngine().SetInputOptions(static_cast<float>(sensitivity),
        invertLookY == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_minefade_nativeengine_NativeBridge_nativeSetAvatarAppearance(
        JNIEnv* env, jclass, jintArray choices) {
    if (choices == nullptr || env->GetArrayLength(choices) < 6) return;
    jint values[6]{};
    env->GetIntArrayRegion(choices,0,6,values);
    if(env->ExceptionCheck()) { env->ExceptionClear(); return; }
    int nativeValues[6]{};
    for(int i=0;i<6;++i) nativeValues[i]=static_cast<int>(values[i]);
    minefade::GetEngine().SetAvatarAppearance(nativeValues,6u);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_minefade_nativeengine_NativeBridge_nativeGetPlayerState(JNIEnv* env, jclass) {
    float state[4]{};
    minefade::GetEngine().GetPlayerState(state);
    jfloatArray result = env->NewFloatArray(4);
    if (result != nullptr) env->SetFloatArrayRegion(result, 0, 4, state);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_minefade_nativeengine_NativeBridge_nativeSetRemotePlayers(JNIEnv* env, jclass, jfloatArray values) {
    if (values == nullptr) return;
    const jsize length = env->GetArrayLength(values);
    if (length < 4) return;
    std::vector<jfloat> data(static_cast<std::size_t>(length));
    env->GetFloatArrayRegion(values, 0, length, data.data());
    if (env->ExceptionCheck()) { env->ExceptionClear(); return; }
    minefade::GetEngine().SetRemotePlayers(data.data(), data.size());
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_minefade_nativeengine_NativeBridge_nativeConsumeBlockEdits(JNIEnv* env, jclass) {
    const std::vector<int> edits = minefade::GetEngine().ConsumeLocalBlockEdits();
    if (edits.empty()) return env->NewIntArray(0);
    jintArray result = env->NewIntArray(static_cast<jsize>(edits.size()));
    if (result != nullptr) env->SetIntArrayRegion(result, 0, static_cast<jsize>(edits.size()),
                                                   reinterpret_cast<const jint*>(edits.data()));
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_minefade_nativeengine_NativeBridge_nativeApplyRemoteBlockEdits(JNIEnv* env, jclass, jintArray values) {
    if (values == nullptr) return;
    const jsize length = env->GetArrayLength(values);
    if (length < 4) return;
    std::vector<jint> edits(static_cast<std::size_t>(length));
    env->GetIntArrayRegion(values, 0, length, edits.data());
    if (env->ExceptionCheck()) { env->ExceptionClear(); return; }
    minefade::GetEngine().ApplyRemoteBlockEdits(reinterpret_cast<const int*>(edits.data()), edits.size());
}
