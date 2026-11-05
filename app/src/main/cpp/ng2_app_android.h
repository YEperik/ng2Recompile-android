// Ng2App for Android: the original src/ng2_app.h with everything that is a
// Windows desktop concern removed, and nothing else changed in intent.
//
// KEPT from the original (same calls, same order, same reasons):
//   - gpu_plugin = "xenos"                       (OnPreSetup)
//   - license_mask = 1 so DLC is visible          (OnPreSetup)
//   - game_data_root / user_data_root resolution  (OnConfigurePaths)
//   - one guest user for every controller         (UseOneGuestUser)
//   - video mode / refresh rate / internal render size / chapter 12 workaround
//
// DROPPED (no meaning on a phone): window sizing and monitors, DPI, Shift-at-
// launch, the ImGui setup screen (the Kotlin MainActivity replaces it), the
// self-updater (the store updates the app), the relaunch-on-quit trick, the
// texture-pack tooling that shells out to Python, F-key hotkeys, RenderDoc.
//
// NOT VERIFIED: this file has never been compiled - there is no SDK here. It
// is written against the signatures visible in the original repository.
#pragma once

#include <rex/cvar.h>
#include <rex/filesystem.h>
#include <rex/input/device_assignment.h>
#include <rex/input/input_system.h>
#include <rex/logging.h>
#include <rex/rex_app.h>
#include <rex/runtime.h>
#include <rex/ui/flags.h>

#include <algorithm>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <map>
#include <memory>
#include <sstream>
#include <string>

#include "ng2_android_pad_driver.h"

// Defined in the original patch_hooks.cpp.
REXCVAR_DECLARE(int32_t, ng2_render_width);
REXCVAR_DECLARE(int32_t, ng2_render_height);
REXCVAR_DECLARE(bool, ng2_chapter12_workaround);
REXCVAR_DECLARE(int32_t, ng2_video_mode);
extern uint32_t g_ng2_skip_path_va;

namespace ng2::android {
// App-private files dir. Kotlin (GameSdlActivity) exports it as NG2_FILES_DIR
// before SDLActivity starts the SDL_main thread.
inline std::string FilesDir() {
  const char* e = std::getenv("NG2_FILES_DIR");
  return e ? std::string(e) : std::string();
}
}  // namespace ng2::android

class Ng2App : public rex::ReXApp {
 public:
  using rex::ReXApp::ReXApp;

  static std::unique_ptr<rex::ui::WindowedApp> Create(
      rex::ui::WindowedAppContext& ctx) {
    return std::unique_ptr<Ng2App>(new Ng2App(ctx, "ng2", PPCImageConfig));
  }

  void OnPreSetup(rex::RuntimeConfig& config) override {
    // Android needs the Vulkan build of the Xenos plugin. The name is the same
    // one the original passes; whether the SDK ships a Vulkan "xenos" is the
    // main open question (docs/ANDROID_PORT.md, open question 1).
    config.gpu_plugin = "xenos";

    ApplyDisplay();
    if (REXCVAR_GET(license_mask) == 0) REXCVAR_SET(license_mask, 1u);
  }

  void OnConfigurePaths(rex::PathConfig& paths) override {
    LoadCfg();
    if (paths.game_data_root.empty()) {
      const std::string g = Get("game_path", "");
      if (!g.empty()) paths.game_data_root = std::filesystem::path(g);
    }
    // Everything the guest writes (saves, profile, content) lives in the
    // app's private files dir: always writable, removed with the app.
    //
    // ReXApp::SetupEnvironment derives its defaults from the EXECUTABLE folder,
    // which on Android is /system/bin (read-only): config_path would be
    // /system/bin/ng2.toml. Set every path explicitly instead.
    std::error_code ec;
    const std::filesystem::path root(ng2::android::FilesDir());
    const std::filesystem::path user = root / "user";
    std::filesystem::create_directories(user, ec);
    std::filesystem::create_directories(root / "cache", ec);
    std::filesystem::create_directories(root / "logs", ec);
    paths.user_data_root = user;
    paths.cache_root = root / "cache";
    paths.config_path = root / "ng2.toml";
  }

  void OnPostSetup() override {
    UseOneGuestUser();
    REXLOG_INFO("Android: runtime up, game root {}",
                REXCVAR_GET(game_data_root));
  }

  void OnShutdown() override { REXLOG_INFO("Android: shutdown"); }

 private:
  // Same policy and same reason as the original: Ninja Gaiden II polls guest
  // user 0 only, so every controller is assigned to it.
  void UseOneGuestUser() {
    auto* rt = runtime();
    if (!rt) return;
    auto* input = dynamic_cast<rex::input::InputSystem*>(rt->input_system());
    if (!input) {
      REXLOG_WARN("Input: not the expected input system; touch controls will "
                  "not reach the guest");
      return;
    }
    input->SetDeviceAssignment(std::make_unique<rex::input::SharedAssignment>());
    input->AddDriver(ng2::MakeAndroidPadDriver());
    REXLOG_INFO("Input: Android touch/gamepad drives guest user 0");
  }

  void ApplyDisplay() {
    // Phones are a fixed 16:9-ish landscape surface; the guest keeps the
    // original 1120x584 internal size unless the user asks for 720p.
    const int fps = std::clamp(GetInt("fps", 30), 30, 60);
    REXCVAR_SET(video_mode_refresh_rate, double(fps));
    if (GetInt("internal_720p", 0) != 0) {
      REXCVAR_SET(ng2_render_width, 1280);
      REXCVAR_SET(ng2_render_height, 720);
    }
    REXCVAR_SET(ng2_video_mode, GetInt("video_mode", 0));
    REXCVAR_SET(ng2_chapter12_workaround, GetInt("chapter12_workaround", 0) != 0);
    REXCVAR_SET(fullscreen, true);
  }

  // Same key=value format as the desktop ng2_settings.cfg, written by the
  // Kotlin side (Settings.kt) so a config can move between platforms.
  void LoadCfg() {
    cfg_.clear();
    std::ifstream in(std::filesystem::path(ng2::android::FilesDir()) /
                     "ng2_settings.cfg");
    for (std::string line; std::getline(in, line);) {
      const auto eq = line.find('=');
      if (eq == std::string::npos || line[0] == '#') continue;
      cfg_[line.substr(0, eq)] = line.substr(eq + 1);
    }
  }
  std::string Get(const std::string& k, const std::string& d) const {
    auto it = cfg_.find(k);
    return it == cfg_.end() ? d : it->second;
  }
  int GetInt(const std::string& k, int d) const {
    auto it = cfg_.find(k);
    return it == cfg_.end() ? d : std::atoi(it->second.c_str());
  }

  std::map<std::string, std::string> cfg_;
};
