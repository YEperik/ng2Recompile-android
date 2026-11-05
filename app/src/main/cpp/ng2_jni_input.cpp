// JNI that must exist in EVERY build of the native library, including the
// real runtime (libmain.so): controller input, the "is the runtime linked"
// probe, and disc-image import. The host lifecycle (start/stop/surface) lives
// in ng2_jni.cpp and exists only in the stub build; with the real runtime
// SDLActivity owns the surface and the lifecycle.
#include <jni.h>

#include <cstdio>
#include <string>

#include "ng2_gpu_probe.h"
#include "ng2_iso_android.h"
#include "ng2_pad_state.h"
#include "ng2_stats.h"

namespace {
std::string Str(JNIEnv* env, jstring s) {
  if (!s) return {};
  const char* c = env->GetStringUTFChars(s, nullptr);
  std::string r(c);
  env->ReleaseStringUTFChars(s, c);
  return r;
}
}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_ng2_dawn_NativeBridge_nativeProbeVulkan(JNIEnv* env, jclass) {
  return env->NewStringUTF(ng2::ProbeVulkan().c_str());
}

JNIEXPORT jstring JNICALL
Java_com_ng2_dawn_NativeBridge_nativeGetStats(JNIEnv* env, jclass) {
  std::string lines = ng2::stats::ToLines(ng2::stats::Get());
  char pad[32];
  std::snprintf(pad, sizeof pad, "pad=%04x\n", ng2::pad::GlobalMixer().Mixed().buttons);
  lines += pad;
  return env->NewStringUTF(lines.c_str());
}

JNIEXPORT void JNICALL
Java_com_ng2_dawn_NativeBridge_nativeResetStats(JNIEnv*, jclass) {
  ng2::stats::Reset();
}

JNIEXPORT jboolean JNICALL
Java_com_ng2_dawn_NativeBridge_nativeRuntimeLinked(JNIEnv*, jclass) {
#ifdef NG2_WITH_REXGLUE
  return JNI_TRUE;
#else
  return JNI_FALSE;
#endif
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
