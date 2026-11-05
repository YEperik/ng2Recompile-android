// Host test: g++ -std=c++20 -Iapp/src/main/cpp tests/stats_test.cpp app/src/main/cpp/ng2_stats.cpp
#include <cassert>
#include <cmath>
#include <cstdio>

#include "ng2_stats.h"

using namespace ng2::stats;
static bool near(double a, double b, double e) { return std::fabs(a - b) <= e; }
constexpr int64_t kMs = 1'000'000;

int main() {
  Reset();
  Snapshot s = GetAt(0);
  assert(s.total_frames == 0 && s.fps == 0 && s.since_last_frame_ms < 0);   // nothing yet

  // Steady 30 fps for 3 seconds.
  const int64_t step = 1000 * kMs / 30;
  int64_t t = 1000 * kMs;
  for (int i = 0; i < 90; ++i, t += step) NoteFrameAt(t);
  s = GetAt(t - step);
  assert(near(s.fps, 30.0, 0.5));
  assert(near(s.avg_ms, 33.3, 0.5) && near(s.p95_ms, 33.3, 0.5) && near(s.max_ms, 33.3, 0.5));
  assert(s.hitches == 0 && s.total_frames == 90);

  // One 200 ms hitch is visible in max/p95 tail and counted.
  NoteFrameAt(t + 200 * kMs);
  s = GetAt(t + 200 * kMs);
  assert(s.max_ms > 190 && s.hitches == 1);

  // A stalled game: fps falls to 0 and the silence is measured (hang detection).
  s = GetAt(t + 200 * kMs + 5000 * kMs);
  assert(s.fps == 0 && s.since_last_frame_ms > 4900 && s.total_frames == 91);

  // 60 fps is not reported as 30 and vice versa.
  Reset();
  const int64_t s60 = 1000 * kMs / 60;
  int64_t u = 0;
  for (int i = 0; i < 200; ++i, u += s60) NoteFrameAt(u);
  assert(near(GetAt(u - s60).fps, 60.0, 1.0));

  // The ring buffer survives wrap-around (cap 512 > frames shown, many more frames pushed).
  for (int i = 0; i < 2000; ++i, u += s60) NoteFrameAt(u);
  assert(near(GetAt(u - s60).fps, 60.0, 1.0) && GetAt(u).total_frames == 2200);

  // Input polling counter is independent of frames.
  Reset();
  assert(Get().input_polls == 0);
  NoteInputPoll(); NoteInputPoll();
  assert(Get().input_polls == 2);

  // Wire format.
  const std::string lines = ToLines(GetAt(0));
  assert(lines.find("fps=") == 0 && lines.find("polls=2") != std::string::npos);
  std::puts("stats_test: OK");
  return 0;
}
