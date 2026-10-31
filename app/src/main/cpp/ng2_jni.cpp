// JNI surface of com.ng2.dawn.NativeBridge.
#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <memory>
#include <mutex>
#include <string>

#include "ng2_host.h"
#include "ng2_iso_android.h"
#include "ng2_pad_state.h"

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

JNIEXPORT jboolean JNICALL
Java_com_ng2_dawn_NativeBridge_nativeRuntimeLinked(JNIEnv*, jclass) {
#ifdef NG2_WITH_REXGLUE
  return JNI_TRUE;
#else
  return JNI_FALSE;
#endif
}

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

// source: 0 touch, 1 gamepad. Axes -1..1 (+Y up), triggers 0..1.
JNIEXPORT void JNICALL Java_com_ng2_dawn_NativeBridge_nativeSetPad(
    JNIEnv*, jclass, jint source, jint buttons, jfloat lx, jfloat ly,
    jfloat rx, jfloat ry, jfloat lt, jfloat rt) {
  ng2::pad::PadSnapshot p;
  p.buttons = static_cast<uint16_t>(buttons & 0xFFFF);
  p.lx = lx; p.ly = ly; p.rx = rx; p.ry = ry; p.lt = lt; p.rt = rt;
  auto& m = ng2::pad::GlobalMixer();
  if (source == ng2::pad::kGamepad) m.NoteGamepadSeen();
  m.Set(static_cast<ng2::pad::Source>(source), p);
}

JNIEXPORT jstring JNICALL
Java_com_ng2_dawn_NativeBridge_nativeStatus(JNIEnv* env, jclass) {
  std::lock_guard lock(g_mu);
  return env->NewStringUTF(g_host ? g_host->Status().c_str() : "");
}

// --- Disc image import (SDK-backed; stub reports that it cannot) ------------

JNIEXPORT jstring JNICALL Java_com_ng2_dawn_NativeBridge_nativeInspectIso(
    JNIEnv* env, jclass, jint fd) {
  return env->NewStringUTF(ng2::android::InspectIsoFd(fd).c_str());
}

JNIEXPORT jboolean JNICALL Java_com_ng2_dawn_NativeBridge_nativeExtractIso(
    JNIEnv* env, jclass, jint fd, jstring dest) {
  return ng2::android::ExtractIsoFd(fd, Str(env, dest)) ? JNI_TRUE : JNI_FALSE;
}

// {files_done, files_total, bytes_done, bytes_total, state}  state: 0 idle,
// 1 running, 2 complete, 3 failed
JNIEXPORT jlongArray JNICALL
Java_com_ng2_dawn_NativeBridge_nativeExtractProgress(JNIEnv* env, jclass) {
  const auto v = ng2::android::ExtractProgressSnapshot();
  jlongArray arr = env->NewLongArray(5);
  jlong tmp[5] = {v[0], v[1], v[2], v[3], v[4]};
  env->SetLongArrayRegion(arr, 0, 5, tmp);
  return arr;
}

JNIEXPORT jstring JNICALL
Java_com_ng2_dawn_NativeBridge_nativeExtractError(JNIEnv* env, jclass) {
  return env->NewStringUTF(ng2::android::ExtractError().c_str());
}

JNIEXPORT void JNICALL
Java_com_ng2_dawn_NativeBridge_nativeExtractCancel(JNIEnv*, jclass) {
  ng2::android::ExtractCancel();
}

}  // extern "C"
