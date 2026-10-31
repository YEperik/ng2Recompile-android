// Pad state shared by every Android input source (touch overlay, physical
// gamepad). Pure C++ with no Android or SDK dependency, so it is unit-tested
// on the host (tests/pad_state_test.cpp).
//
// Button masks are the XInput values. They are the same ones the original
// project uses in src/ng2_autoskip.cpp (A=0x1000, Start=0x0010, ...), so the
// guest sees exactly what it saw from the Windows build.
#pragma once

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <mutex>

namespace ng2::pad {

constexpr uint16_t kDpadUp = 0x0001, kDpadDown = 0x0002, kDpadLeft = 0x0004,
                   kDpadRight = 0x0008, kStart = 0x0010, kBack = 0x0020,
                   kLeftThumb = 0x0040, kRightThumb = 0x0080,
                   kLeftShoulder = 0x0100, kRightShoulder = 0x0200,
                   kA = 0x1000, kB = 0x2000, kX = 0x4000, kY = 0x8000;

enum Source : int { kTouch = 0, kGamepad = 1, kSourceCount = 2 };

// Axes are -1..1 with +Y up (XInput convention). Triggers are 0..1.
struct PadSnapshot {
  uint16_t buttons = 0;
  float lx = 0, ly = 0, rx = 0, ry = 0, lt = 0, rt = 0;
  bool Idle() const {
    return buttons == 0 && lx == 0 && ly == 0 && rx == 0 && ry == 0 &&
           lt == 0 && rt == 0;
  }
};

// What X_INPUT_GAMEPAD holds.
struct XPad {
  uint16_t buttons;
  uint8_t left_trigger, right_trigger;
  int16_t thumb_lx, thumb_ly, thumb_rx, thumb_ry;
};

inline float Clamp1(float v) { return std::clamp(v, -1.0f, 1.0f); }

inline int16_t AxisToI16(float v) {
  return static_cast<int16_t>(std::lround(Clamp1(v) * 32767.0f));
}
inline uint8_t TriggerToU8(float v) {
  return static_cast<uint8_t>(std::lround(std::clamp(v, 0.0f, 1.0f) * 255.0f));
}

inline XPad ToXPad(const PadSnapshot& p) {
  XPad o{};
  o.buttons = p.buttons;
  o.left_trigger = TriggerToU8(p.lt);
  o.right_trigger = TriggerToU8(p.rt);
  o.thumb_lx = AxisToI16(p.lx);
  o.thumb_ly = AxisToI16(p.ly);
  o.thumb_rx = AxisToI16(p.rx);
  o.thumb_ry = AxisToI16(p.ry);
  return o;
}

// Combines the sources the way the SDK combines devices assigned to one guest
// user: buttons are ORed, and for each stick the source pushed furthest wins,
// so a thumb resting on the touch overlay never cancels a real stick.
class PadMixer {
 public:
  void Set(Source s, const PadSnapshot& p) {
    if (s < 0 || s >= kSourceCount) return;
    std::lock_guard lock(mu_);
    src_[s] = p;
    ++revision_;
  }

  PadSnapshot Mixed() const {
    std::lock_guard lock(mu_);
    PadSnapshot out;
    for (const auto& p : src_) {
      out.buttons |= p.buttons;
      out.lt = std::max(out.lt, p.lt);
      out.rt = std::max(out.rt, p.rt);
    }
    PickStick(out.lx, out.ly, src_[kTouch].lx, src_[kTouch].ly,
              src_[kGamepad].lx, src_[kGamepad].ly);
    PickStick(out.rx, out.ry, src_[kTouch].rx, src_[kTouch].ry,
              src_[kGamepad].rx, src_[kGamepad].ry);
    return out;
  }

  // True once a physical pad has reported anything, so the overlay can hide.
  bool GamepadSeen() const { return gamepad_seen_.load(); }
  void NoteGamepadSeen() { gamepad_seen_ = true; }

  uint32_t Revision() const {
    std::lock_guard lock(mu_);
    return revision_;
  }

 private:
  static void PickStick(float& ox, float& oy, float ax, float ay, float bx,
                        float by) {
    const float ma = ax * ax + ay * ay, mb = bx * bx + by * by;
    if (ma >= mb) { ox = ax; oy = ay; } else { ox = bx; oy = by; }
  }
  mutable std::mutex mu_;
  PadSnapshot src_[kSourceCount];
  uint32_t revision_ = 0;
  std::atomic<bool> gamepad_seen_{false};
};

PadMixer& GlobalMixer();

}  // namespace ng2::pad
