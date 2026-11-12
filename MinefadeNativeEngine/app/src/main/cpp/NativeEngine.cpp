#include "engine/Engine.h"

#include <jni.h>
#include <android/log.h>

#include <string>
#include <vector>
#include <cstdint>

namespace {
constexpr const char* kTag = "MinefadeNativeJNI";
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_minefade_nativeengine_NativeBridge_nativeInit(JNIEnv* env, jclass, jfloat density,
                                                           jbyteArray atlas, jint width, jint height) {
    if (atlas == nullptr || width <= 0 || height <= 0) return JNI_FALSE;
    const jsize length = env->GetArrayLength(atlas);
    if (length <= 0) return JNI_FALSE;
    std::vector<std::uint8_t> pixels(static_cast<std::size_t>(length));
    env->GetByteArrayRegion(atlas, 0, length, reinterpret_cast<jbyte*>(pixels.data()));
    if (env->ExceptionCheck()) { env->ExceptionClear(); return JNI_FALSE; }
    const bool result = minefade::GetEngine().Initialise(static_cast<float>(density), pixels.data(), pixels.size(),
                                                          static_cast<int>(width), static_cast<int>(height));
    __android_log_print(result ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, kTag,
                        "Engine initialisation %s", result ? "succeeded" : "failed");
    return result ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_minefade_nativeengine_NativeBridge_nativeResize(JNIEnv*, jclass, jint width, jint height) {
    minefade::GetEngine().Resize(static_cast<int>(width), static_cast<int>(height));
}

extern "C" JNIEXPORT void JNICALL
Java_com_minefade_nativeengine_NativeBridge_nativeRender(JNIEnv*, jclass) {
    minefade::GetEngine().Render();
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
