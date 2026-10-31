// Host test (no Android): g++ -std=c++20 -I../app/src/main/cpp pad_state_test.cpp
#include <cassert>
#include <cstdio>

#include "ng2_pad_state.h"

using namespace ng2::pad;

namespace ng2::pad { PadMixer& GlobalMixer() { static PadMixer m; return m; } }

int main() {
  // Button masks must match the XInput values the original project uses.
  assert(kA == 0x1000 && kB == 0x2000 && kX == 0x4000 && kY == 0x8000);
  assert(kStart == 0x0010 && kBack == 0x0020);
  assert(kLeftShoulder == 0x0100 && kRightShoulder == 0x0200);

  // Axis / trigger scaling, same formulas as AutoSkipDriver.
  PadSnapshot p; p.lx = 1.0f; p.ly = -1.0f; p.lt = 1.0f; p.rt = 0.5f;
  XPad x = ToXPad(p);
  assert(x.thumb_lx == 32767 && x.thumb_ly == -32767);
  assert(x.left_trigger == 255 && x.right_trigger == 128);
  p.lx = 5.0f;  // out of range is clamped, never wrapped
  assert(ToXPad(p).thumb_lx == 32767);

  // Mixing: buttons OR, strongest stick wins, triggers max.
  PadMixer m;
  PadSnapshot t, g;
  t.buttons = kA; t.lx = 0.2f; t.lt = 0.3f;
  g.buttons = kX | kA; g.lx = -0.9f; g.lt = 0.1f; g.rx = 0.4f;
  m.Set(kTouch, t); m.Set(kGamepad, g);
  PadSnapshot o = m.Mixed();
  assert(o.buttons == (kA | kX));
  assert(o.lx == -0.9f);          // gamepad stick is further out
  assert(o.rx == 0.4f && o.ry == 0.0f);
  assert(o.lt == 0.3f);

  // A touch stick pushed further than an idle pad wins; a resting touch loses.
  t.lx = 0.8f; m.Set(kTouch, t); g.lx = 0.1f; m.Set(kGamepad, g);
  assert(m.Mixed().lx == 0.8f);

  // Revision moves on every Set so the driver can bump the packet number.
  const auto r = m.Revision(); m.Set(kTouch, t); assert(m.Revision() == r + 1);

  // Bad source index is ignored.
  m.Set(static_cast<Source>(7), t); assert(m.Revision() == r + 1);

  puts("pad_state_test: OK");
  return 0;
}
