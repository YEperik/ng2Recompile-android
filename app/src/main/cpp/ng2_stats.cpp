#include "ng2_stats.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <mutex>
#include <vector>

namespace ng2::stats {
namespace {

constexpr size_t kCap = 512;                 // > 2 s at 240 fps
constexpr int64_t kWindowNs = 2'000'000'000;
constexpr int64_t kFpsNs = 1'000'000'000;

std::mutex g_mu;
int64_t g_ts[kCap];
size_t g_head = 0;     // next write slot
size_t g_count = 0;    // valid entries (<= kCap)
uint64_t g_total = 0;
std::atomic<uint64_t> g_polls{0};

int64_t NowNs() {
  return std::chrono::duration_cast<std::chrono::nanoseconds>(
             std::chrono::steady_clock::now().time_since_epoch()).count();
}

}  // namespace

void NoteFrameAt(int64_t now_ns) {
  std::lock_guard lock(g_mu);
  g_ts[g_head] = now_ns;
  g_head = (g_head + 1) % kCap;
  if (g_count < kCap) ++g_count;
  ++g_total;
}

void NoteFrame() { NoteFrameAt(NowNs()); }
void NoteInputPoll() { g_polls.fetch_add(1, std::memory_order_relaxed); }

Snapshot GetAt(int64_t now_ns) {
  Snapshot s;
  s.input_polls = g_polls.load(std::memory_order_relaxed);
  std::vector<int64_t> ts;
  {
    std::lock_guard lock(g_mu);
    s.total_frames = g_total;
    ts.reserve(g_count);
    for (size_t i = 0; i < g_count; ++i) {            // oldest -> newest
      const size_t idx = (g_head + kCap - g_count + i) % kCap;
      if (now_ns - g_ts[idx] <= kWindowNs) ts.push_back(g_ts[idx]);
    }
  }
  if (g_total == 0) return s;
  {
    std::lock_guard lock(g_mu);
    const size_t newest = (g_head + kCap - 1) % kCap;
    s.since_last_frame_ms = (now_ns - g_ts[newest]) / 1e6;
  }
  if (ts.empty()) return s;                           // stalled: fps stays 0

  // fps: frames in the last second, divided by the span they cover.
  size_t in_sec = 0;
  int64_t first_in_sec = ts.back();
  for (auto it = ts.rbegin(); it != ts.rend(); ++it) {
    if (now_ns - *it > kFpsNs) break;
    ++in_sec;
    first_in_sec = *it;
  }
  if (in_sec >= 2) {
    const double span = (ts.back() - first_in_sec) / 1e9;
    if (span > 0) s.fps = static_cast<double>(in_sec - 1) / span;
  }

  if (ts.size() >= 2) {
    std::vector<double> ms;
    ms.reserve(ts.size() - 1);
    for (size_t i = 1; i < ts.size(); ++i) ms.push_back((ts[i] - ts[i - 1]) / 1e6);
    double sum = 0;
    for (double v : ms) sum += v;
    s.avg_ms = sum / ms.size();
    std::vector<double> sorted = ms;
    std::sort(sorted.begin(), sorted.end());
    const double median = sorted[sorted.size() / 2];
    s.p95_ms = sorted[std::min(sorted.size() - 1, static_cast<size_t>(sorted.size() * 0.95))];
    s.max_ms = sorted.back();
    for (double v : ms) if (v > median * 2.0) ++s.hitches;
  }
  return s;
}

Snapshot Get() { return GetAt(NowNs()); }

void Reset() {
  std::lock_guard lock(g_mu);
  g_head = g_count = 0;
  g_total = 0;
  g_polls = 0;
}

std::string ToLines(const Snapshot& s) {
  char buf[512];
  std::snprintf(buf, sizeof buf,
                "fps=%.2f\navg_ms=%.2f\np95_ms=%.2f\nmax_ms=%.2f\nsince_ms=%.1f\nframes=%llu\nhitches=%u\npolls=%llu\n",
                s.fps, s.avg_ms, s.p95_ms, s.max_ms, s.since_last_frame_ms,
                static_cast<unsigned long long>(s.total_frames), s.hitches,
                static_cast<unsigned long long>(s.input_polls));
  return buf;
}

}  // namespace ng2::stats
