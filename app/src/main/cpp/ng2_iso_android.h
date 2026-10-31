// ISO import for Android.
//
// A document-picker ISO arrives as a file descriptor, not a path. The original
// ng2_disc.cpp takes a std::filesystem::path and reads through the SDK's
// DiscImageDevice, so the fd is handed to it as /proc/self/fd/<n>, which the
// kernel resolves to the open (seekable) file. The caller keeps the fd open
// for the duration of the call.
#pragma once

#include <array>
#include <cstdint>
#include <string>

namespace ng2::android {

// "ok|<title id hex>|<is ng2 0/1>|<files>|<bytes>|<message>" or "err|<message>"
std::string InspectIsoFd(int fd);

// Starts a background extraction into `dest` (a real directory path).
bool ExtractIsoFd(int fd, const std::string& dest);

std::array<int64_t, 5> ExtractProgressSnapshot();
std::string ExtractError();
void ExtractCancel();

}  // namespace ng2::android
