// Modeled line for line on AutoSkipDriver in the original src/ng2_autoskip.cpp,
// which is the project's own proof of how a synthetic rex::input::InputDriver
// is written and registered. The only differences: the state comes from the
// Android PadMixer instead of a script, and the device reports as a real pad.
#include "ng2_android_pad_driver.h"

#include <cstring>

#include <rex/logging.h>

#include <android/log.h>

#include "ng2_host.h"
#include "ng2_pad_state.h"
#include "ng2_stats.h"

namespace ng2 {

using rex::X_RESULT;
using rex::X_STATUS;
using rex::input::X_INPUT_CAPABILITIES;
using rex::input::X_INPUT_KEYSTROKE;
using rex::input::X_INPUT_STATE;
using rex::input::X_INPUT_VIBRATION;

namespace {

constexpr rex::input::DeviceId kAndroidPad =
    static_cast<rex::input::DeviceId>(0x4E473241);  // 'NG2A'

class AndroidPadDriver final : public rex::input::InputDriver {
 public:
  AndroidPadDriver() : InputDriver(nullptr, 0) {}
  ~AndroidPadDriver() override = default;

  X_STATUS Setup() override { return X_STATUS_SUCCESS; }

  void EnumerateDevices(std::vector<rex::input::DeviceInfo>& out) override {
    rex::input::DeviceInfo info;
    info.id = kAndroidPad;
    info.name = "Android controls";
    // NOT synthetic here: this is the player's controller. With `synthetic`
    // set the assignment logic would not count it as "a controller is plugged
    // in" and the game would keep prompting for one. (Verify against the
    // SDK build in use - see docs/ANDROID_PORT.md, open question 4.)
    info.synthetic = false;
    out.push_back(info);
  }

  X_RESULT GetDeviceState(rex::input::DeviceId id, X_INPUT_STATE* out) override {
    if (id != kAndroidPad) return X_ERROR_DEVICE_NOT_CONNECTED;
    if (!out) return X_ERROR_BAD_ARGUMENTS;
    if (stats::Get().input_polls == 0) {
      Log(ANDROID_LOG_INFO, "stage: guest polled input for the first time");
    }
    stats::NoteInputPoll();
    std::memset(out, 0, sizeof(*out));
    // Only bump the packet number when the state really changed: the guest
    // treats a moving number as "new data".
    const uint32_t rev = pad::GlobalMixer().Revision();
    if (rev != last_rev_) { last_rev_ = rev; ++packet_; }
    out->packet_number = packet_;
    const pad::XPad p = pad::ToXPad(pad::GlobalMixer().Mixed());
    out->gamepad.buttons = p.buttons;
    out->gamepad.left_trigger = p.left_trigger;
    out->gamepad.right_trigger = p.right_trigger;
    out->gamepad.thumb_lx = p.thumb_lx;
    out->gamepad.thumb_ly = p.thumb_ly;
    out->gamepad.thumb_rx = p.thumb_rx;
    out->gamepad.thumb_ry = p.thumb_ry;
    return X_ERROR_SUCCESS;
  }

  X_RESULT GetDeviceCapabilities(rex::input::DeviceId id, uint32_t,
                                 X_INPUT_CAPABILITIES* caps) override {
    if (id != kAndroidPad) return X_ERROR_DEVICE_NOT_CONNECTED;
    if (caps) {
      std::memset(caps, 0, sizeof(*caps));
      caps->type = 0x01;      // gamepad
      caps->sub_type = 0x01;
      caps->gamepad.buttons = 0xF3FF;
      caps->gamepad.left_trigger = 0xFF;
      caps->gamepad.right_trigger = 0xFF;
      caps->gamepad.thumb_lx = int16_t(0x7FFF);
      caps->gamepad.thumb_ly = int16_t(0x7FFF);
      caps->gamepad.thumb_rx = int16_t(0x7FFF);
      caps->gamepad.thumb_ry = int16_t(0x7FFF);
    }
    return X_ERROR_SUCCESS;
  }

  // Rumble is forwarded to Java by ng2_rex_host.cpp (VibrationEffect).
  X_RESULT SetDeviceVibration(rex::input::DeviceId, X_INPUT_VIBRATION* v) override {
    if (v && g_vibrate) g_vibrate(v->left_motor_speed, v->right_motor_speed);
    return X_ERROR_SUCCESS;
  }

  X_RESULT GetDeviceKeystroke(rex::input::DeviceId, uint32_t,
                              X_INPUT_KEYSTROKE*) override {
    return X_ERROR_EMPTY;
  }

 public:
  static inline void (*g_vibrate)(uint16_t left, uint16_t right) = nullptr;

 private:
  uint32_t packet_ = 0, last_rev_ = 0;
};

}  // namespace

std::unique_ptr<rex::input::InputDriver> MakeAndroidPadDriver() {
  return std::make_unique<AndroidPadDriver>();
}

}  // namespace ng2
