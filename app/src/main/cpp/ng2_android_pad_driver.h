// Touch + gamepad as one guest controller. Compiled only with the SDK.
#pragma once
#include <memory>
#include <rex/input/input_driver.h>
namespace ng2 {
std::unique_ptr<rex::input::InputDriver> MakeAndroidPadDriver();
}
