// Entry TU for the real runtime build. Mirrors src/main.cpp of the desktop
// project: pull in the generated image config, the app, and register it.
// REXGLUE's windowed_app_main_sdl.cpp (added by rexglue_configure_target)
// provides SDL_main() on Android and calls the creator registered here.
#include "generated/default/ng2_init.h"

#include "ng2_app_android.h"

REX_DEFINE_APP(ng2, Ng2App::Create)
