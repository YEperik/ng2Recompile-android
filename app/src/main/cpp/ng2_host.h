// The seam between the Android shell and whatever actually runs the game.
//
//   * StubHost (default)      draws a live input monitor so the whole shell -
//                             setup, storage, touch controls, gamepad,
//                             lifecycle - can be run and tested on a device
//                             with no game and no SDK.
//   * RexHost (NG2_WITH_REXGLUE) hands the surface and settings to the
//                             ReXGlue runtime and the original ng2 hooks.
#pragma once

#include <android/native_window.h>

#include <memory>
#include <string>

namespace ng2 {

struct HostConfig {
  std::string files_dir;   // app-private: ng2_settings.cfg, logs, user/
  std::string cache_dir;
  std::string game_dir;    // real filesystem path holding default.xex
};

class Host {
 public:
  virtual ~Host() = default;
  virtual bool Start(const HostConfig& cfg) = 0;
  virtual void Stop() = 0;
  virtual void SetPaused(bool paused) = 0;
  virtual void SetWindow(ANativeWindow* window, int w, int h) = 0;  // null = lost
  virtual std::string Status() = 0;   // one-line, shown in the pause menu
  virtual bool IsRealRuntime() const = 0;
};

std::unique_ptr<Host> MakeHost();

// Android log helper (logcat tag "ng2").
void Log(int prio, const char* fmt, ...) __attribute__((format(printf, 2, 3)));

}  // namespace ng2
