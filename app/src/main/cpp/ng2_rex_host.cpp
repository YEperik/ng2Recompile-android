// RexHost: runs the real game through the ReXGlue runtime. SDK build only.
//
// !! This is the one file that cannot be completed without the SDK's Android
// !! entry point. The Windows build gets WinMain from REX_DEFINE_APP
// !! (src/main.cpp). What REX_DEFINE_APP expands to on Android - and how the
// !! SDK's WindowedAppContext is handed an ANativeWindow - is defined by the
// !! SDK, not by this repository. The two places are marked SDK-ENTRY below.
#include <android/log.h>

#include <atomic>
#include <thread>

#include "ng2_android_pad_driver.h"
#include "ng2_app_android.h"
#include "ng2_host.h"

namespace ng2 {
namespace {

class RexHost final : public Host {
 public:
  bool Start(const HostConfig& cfg) override {
    android::g_files_dir = cfg.files_dir;
    // The game root reaches Ng2App::OnConfigurePaths through ng2_settings.cfg
    // (game_path=), written by Settings.kt before nativeStart().
    th_ = std::thread([this] {
      Log(ANDROID_LOG_INFO, "RexHost: starting runtime");
      // SDK-ENTRY (1): create the windowed app context bound to the surface
      // and run the SDK main loop, with Ng2App::Create as the factory:
      //
      //   rex::ui::AndroidWindowedAppContext ctx(window_, ...);   // name: SDK
      //   auto app = Ng2App::Create(ctx);
      //   return rex::ui::RunWindowedApp(ctx, *app);               // name: SDK
      //
      // Replace with the SDK's real Android equivalents.
      running_ = false;
    });
    running_ = true;
    return true;
  }
  void Stop() override {
    // SDK-ENTRY (2): ask the app context to quit, then join.
    if (th_.joinable()) th_.join();
  }
  void SetPaused(bool) override { /* SDK-ENTRY (3): suspend guest threads */ }
  void SetWindow(ANativeWindow* w, int, int) override { window_ = w; }
  std::string Status() override { return running_ ? "Runtime aktif" : "Runtime berhenti"; }
  bool IsRealRuntime() const override { return true; }

 private:
  std::thread th_;
  std::atomic<bool> running_{false};
  ANativeWindow* window_ = nullptr;
};

}  // namespace

std::unique_ptr<Host> MakeHost() { return std::make_unique<RexHost>(); }

}  // namespace ng2
