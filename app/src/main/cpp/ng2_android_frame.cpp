// The per-frame hook of the real runtime. The recompiled game calls this once
// per pass of its main loop: tools/hooks/android_frame.toml binds it to guest
// address 0x822F3A8C, the same instruction the desktop project's diagnostics
// hook uses for its FPS readout (config/hooks/diagnostics.toml, `ng2DiagFrameTick`).
#include "ng2_stats.h"

void ng2AndroidFrameTick() { ng2::stats::NoteFrame(); }
