#include "ng2_iso_android.h"

#include <unistd.h>

#include <string>

#ifdef NG2_WITH_REXGLUE
// Reuses the original, SDK-backed implementation unchanged.
#include <atomic>
#include <filesystem>
#include <memory>
#include <thread>

#include "ng2_disc.h"

namespace ng2::android {
namespace {
ng2::ExtractProgress g_progress;
std::thread g_thread;
int g_fd = -1;
std::string FdPath(int fd) { return "/proc/self/fd/" + std::to_string(fd); }
}  // namespace

std::string InspectIsoFd(int fd) {
  const ng2::DiscInfo d = ng2::InspectDisc(FdPath(fd));
  if (!d.Usable()) return "err|" + d.message;
  char head[128];
  std::snprintf(head, sizeof head, "ok|%08X|%d|%llu|%llu|", d.title_id,
                d.IsNg2() ? 1 : 0, (unsigned long long)d.file_count,
                (unsigned long long)d.total_bytes);
  return std::string(head) + d.message;
}

bool ExtractIsoFd(int fd, const std::string& dest) {
  if (g_thread.joinable()) {
    if (g_progress.running) return false;
    g_thread.join();
  }
  g_progress.Reset();
  g_fd = dup(fd);  // the Java side may close its copy; ours outlives the thread
  g_thread = ng2::ExtractDiscAsync(FdPath(g_fd), dest, g_progress);
  return true;
}

std::array<int64_t, 5> ExtractProgressSnapshot() {
  int state = g_progress.failed ? 3 : g_progress.complete ? 2
              : g_progress.running ? 1 : 0;
  return {(int64_t)g_progress.files_done.load(), (int64_t)g_progress.files_total.load(),
          (int64_t)g_progress.bytes_done.load(), (int64_t)g_progress.bytes_total.load(),
          state};
}
std::string ExtractError() { return g_progress.Error(); }
void ExtractCancel() { g_progress.cancel = true; }

}  // namespace ng2::android

#else  // ---- no SDK: report honestly instead of pretending ----

namespace ng2::android {
std::string InspectIsoFd(int) {
  return "err|Impor ISO butuh runtime ReXGlue (DiscImageDevice). "
         "Ekstrak ISO di PC lalu salin foldernya, atau tautkan SDK.";
}
bool ExtractIsoFd(int, const std::string&) { return false; }
std::array<int64_t, 5> ExtractProgressSnapshot() { return {0, 0, 0, 0, 3}; }
std::string ExtractError() { return "runtime tidak ditautkan"; }
void ExtractCancel() {}
}  // namespace ng2::android

#endif
