// Stub host: no SDK. Draws a software-rendered controller monitor so the shell
// and the controls can be verified on a real device before the runtime exists.
#include <android/log.h>
#include <android/native_window.h>

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>

#include "ng2_host.h"
#include "ng2_pad_state.h"
#include "ng2_stats.h"

namespace ng2 {

#ifndef NG2_WITH_REXGLUE
namespace {

void Fill(uint32_t* px, int stride, int W, int H, int x, int y, int w, int h,
          uint32_t c) {
  for (int j = std::max(0, y); j < std::min(H, y + h); ++j)
    for (int i = std::max(0, x); i < std::min(W, x + w); ++i)
      px[j * stride + i] = c;
}

class StubHost final : public Host {
 public:
  bool Start(const HostConfig& cfg) override {
    cfg_ = cfg;
    run_ = true;
    th_ = std::thread([this] { Loop(); });
    Log(ANDROID_LOG_INFO, "StubHost started (no runtime linked). game_dir=%s",
        cfg.game_dir.c_str());
    return true;
  }
  void Stop() override {
    run_ = false;
    if (th_.joinable()) th_.join();
    SetWindow(nullptr, 0, 0);
  }
  void SetPaused(bool p) override { paused_ = p; }
  void SetWindow(ANativeWindow* w, int, int) override {
    std::lock_guard lock(mu_);
    if (win_) ANativeWindow_release(win_);
    win_ = w;
    if (win_) ANativeWindow_acquire(win_);
  }
  std::string Status() override {
    return "Mode uji: runtime ReXGlue belum ditautkan (lihat docs/ANDROID_PORT.md)";
  }
  bool IsRealRuntime() const override { return false; }

 private:
  void Loop() {
    using namespace std::chrono;
    while (run_) {
      if (!paused_) stats::NoteFrame();   // the test "game" ticks once per drawn frame
      Draw();
      std::this_thread::sleep_for(milliseconds(paused_ ? 100 : 16));
    }
  }
  void Draw() {
    std::lock_guard lock(mu_);
    if (!win_) return;
    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(win_, &buf, nullptr) != 0) return;
    if (buf.format != 1 /*RGBA_8888*/ && buf.format != 2 /*RGBX_8888*/) {
      ANativeWindow_unlockAndPost(win_);
      return;
    }
    auto* px = static_cast<uint32_t*>(buf.bits);
    const int W = buf.width, H = buf.height, S = buf.stride;
    Fill(px, S, W, H, 0, 0, W, H, 0xFF14100E);  // ABGR: dark background
    const auto p = pad::GlobalMixer().Mixed();
    const int u = std::max(8, H / 24);
    struct B { uint16_t m; int x, y; } bt[] = {
        {pad::kA, 14, 14}, {pad::kB, 16, 12}, {pad::kX, 12, 12}, {pad::kY, 14, 10},
        {pad::kLeftShoulder, 2, 4}, {pad::kRightShoulder, 16, 4},
        {pad::kDpadUp, 4, 10}, {pad::kDpadDown, 4, 14}, {pad::kDpadLeft, 2, 12},
        {pad::kDpadRight, 6, 12}, {pad::kStart, 10, 12}, {pad::kBack, 8, 12}};
    const int ox = W / 2 - 10 * u, oy = H / 3;
    for (auto& b : bt)
      Fill(px, S, W, H, ox + b.x * u, oy + b.y * u / 2, u, u,
           (p.buttons & b.m) ? 0xFF2B37D8 : 0xFF3A3A44);
    auto stick = [&](int cx, int cy, float x, float y) {
      Fill(px, S, W, H, cx - 3 * u, cy - 3 * u, 6 * u, 6 * u, 0xFF262229);
      Fill(px, S, W, H, cx + int(x * 2 * u) - u / 2, cy - int(y * 2 * u) - u / 2,
           u, u, 0xFFE0E0E0);
    };
    stick(ox + 4 * u, oy + 24 * u / 2 + 6 * u, p.lx, p.ly);
    stick(ox + 14 * u, oy + 24 * u / 2 + 6 * u, p.rx, p.ry);
    Fill(px, S, W, H, ox, oy - 2 * u, int(p.lt * 6 * u), u / 2, 0xFF2B37D8);
    Fill(px, S, W, H, ox + 14 * u, oy - 2 * u, int(p.rt * 6 * u), u / 2, 0xFF2B37D8);
    ANativeWindow_unlockAndPost(win_);
  }

  HostConfig cfg_;
  std::atomic<bool> run_{false}, paused_{false};
  std::thread th_;
  std::mutex mu_;
  ANativeWindow* win_ = nullptr;
};

}  // namespace

std::unique_ptr<Host> MakeHost() { return std::make_unique<StubHost>(); }
#endif

}  // namespace ng2
