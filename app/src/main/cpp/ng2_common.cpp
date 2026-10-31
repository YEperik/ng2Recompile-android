#include <android/log.h>

#include <cstdarg>

#include "ng2_host.h"
#include "ng2_pad_state.h"

namespace ng2 {

void Log(int prio, const char* fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  __android_log_vprint(prio, "ng2", fmt, ap);
  va_end(ap);
}

namespace pad {
PadMixer& GlobalMixer() {
  static PadMixer m;
  return m;
}
}  // namespace pad

}  // namespace ng2
