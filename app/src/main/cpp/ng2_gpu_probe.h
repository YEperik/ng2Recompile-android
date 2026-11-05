// Asks the device's own Vulkan driver whether it can host the ReXGlue Xenos
// plugin. Uses dlopen("libvulkan.so"), so it needs neither the SDK nor linking
// against Vulkan, and runs in the test build on a real phone.
#pragma once

#include <string>

namespace ng2 {

// Returns "key=value" lines:
//   loaded=0|1            error=<text when loaded=0>
//   instance=<major>.<minor>
//   devices=<n>
//   d<i>.name d<i>.type d<i>.api d<i>.driver d<i>.vendor d<i>.localmb
//   d<i>.swapchain d<i>.gfxcompute
//   d<i>.f.<feature>=0|1   (see kFeatures in the .cpp)
std::string ProbeVulkan();

}  // namespace ng2
