// JNI surface of com.ng2.dawn.NativeBridge.
#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <memory>
#include <mutex>
#include <string>

#include "ng2_host.h"

namespace {

std::mutex g_mu;
std::unique_ptr<ng2::Host> g_host;
ng2::HostConfig g_cfg;
ANativeWindow* g_window = nullptr;
int g_w = 0, g_h = 0;

std::string Str(JNIEnv* env, jstring s) {
  if (!s) return {};
  const char* c = env->GetStringUTFChars(s, nullptr);
  std::string r(c);
  env->ReleaseStringUTFChars(s, c);
  return r;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL Java_com_ng2_dawn_NativeBridge_nativeConfigure(
    JNIEnv* env, jclass, jstring filesDir, jstring cacheDir, jstring gameDir) {
  std::lock_guard lock(g_mu);
  g_cfg.files_dir = Str(env, filesDir);
  g_cfg.cache_dir = Str(env, cacheDir);
  g_cfg.game_dir = Str(env, gameDir);
}

JNIEXPORT jboolean JNICALL
Java_com_ng2_dawn_NativeBridge_nativeStart(JNIEnv*, jclass) {
  std::lock_guard lock(g_mu);
  if (g_host) return JNI_TRUE;
  g_host = ng2::MakeHost();
  if (!g_host->Start(g_cfg)) {
    g_host.reset();
    return JNI_FALSE;
  }
  if (g_window) g_host->SetWindow(g_window, g_w, g_h);
  return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_ng2_dawn_NativeBridge_nativeStop(JNIEnv*, jclass) {
  std::lock_guard lock(g_mu);
  if (g_host) {
    g_host->Stop();
    g_host.reset();
  }
}

JNIEXPORT void JNICALL
Java_com_ng2_dawn_NativeBridge_nativeSetPaused(JNIEnv*, jclass, jboolean p) {
  std::lock_guard lock(g_mu);
  if (g_host) g_host->SetPaused(p == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_ng2_dawn_NativeBridge_nativeSurfaceChanged(
    JNIEnv* env, jclass, jobject surface, jint w, jint h) {
  std::lock_guard lock(g_mu);
  if (g_window) {
    ANativeWindow_release(g_window);
    g_window = nullptr;
  }
  if (surface) g_window = ANativeWindow_fromSurface(env, surface);
  g_w = w;
  g_h = h;
  if (g_host) g_host->SetWindow(g_window, w, h);
}

JNIEXPORT void JNICALL
Java_com_ng2_dawn_NativeBridge_nativeSurfaceDestroyed(JNIEnv*, jclass) {
  std::lock_guard lock(g_mu);
  if (g_host) g_host->SetWindow(nullptr, 0, 0);
  if (g_window) {
    ANativeWindow_release(g_window);
    g_window = nullptr;
  }
}

JNIEXPORT jstring JNICALL
Java_com_ng2_dawn_NativeBridge_nativeStatus(JNIEnv* env, jclass) {
  std::lock_guard lock(g_mu);
  return env->NewStringUTF(g_host ? g_host->Status().c_str() : "");
}

}  // extern "C"
