// Frame-rate and liveness statistics for the game, readable from Kotlin (FPS
// overlay, telemetry, diagnostics). Pure C++ so it is unit-tested on the host.
//
// "A frame" means one pass of the GAME's main loop, not one host present. The
// desktop build measures it the same way (PerfFrameTick called from the
// per-frame hook, see tools/hooks/android_frame.toml): on a phone that vsyncs
// at 60 Hz a host-present counter would read 60 even if the game ran at 15.
#pragma once

#include <cstdint>
#include <string>

namespace ng2::stats {

struct Snapshot {
  double fps = 0;                  // frames per second over the last ~1 s
  double avg_ms = 0;               // mean frame time over the last ~2 s
  double p95_ms = 0;               // 95th percentile frame time, last ~2 s
  double max_ms = 0;               // worst frame time, last ~2 s
  double since_last_frame_ms = -1; // time since the newest frame; -1 = none yet
  uint64_t total_frames = 0;
  uint32_t hitches = 0;            // frames over 2x the median, last ~2 s
  uint64_t input_polls = 0;        // times the guest asked for controller state
};

void NoteFrame();                      // call once per game frame, any thread
void NoteInputPoll();                  // call whenever the guest polls input
Snapshot Get();
void Reset();

// "key=value" lines for JNI: fps, avg_ms, p95_ms, max_ms, since_ms, frames,
// hitches, polls.
std::string ToLines(const Snapshot& s);

// Test seams: explicit clock (nanoseconds, monotonic).
void NoteFrameAt(int64_t now_ns);
Snapshot GetAt(int64_t now_ns);

}  // namespace ng2::stats
