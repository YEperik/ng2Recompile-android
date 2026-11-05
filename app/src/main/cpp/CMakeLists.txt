cmake_minimum_required(VERSION 3.25)
project(ng2android LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(NG2_WITH_REXGLUE "Build the real runtime (ReXGlue SDK + recompiled game)" OFF)
set(NG2_REPO_DIR "" CACHE PATH "Original Ninja's Dawn repository (src/, generated/)")
set(REXGLUE_SDK_DIR "" CACHE PATH "ReXGlue SDK source tree with sdk-patch/rexglue-android.patch applied")

# Present in every build: pad mixer, controller/ISO/GPU-probe JNI, ISO import.
set(NG2_COMMON_SOURCES ng2_common.cpp ng2_jni_input.cpp ng2_iso_android.cpp ng2_gpu_probe.cpp ng2_stats.cpp)

if(NOT NG2_WITH_REXGLUE)
  # ---- Test mode ---------------------------------------------------------------
  # No SDK, no game. StubHost draws a live controller monitor, so the whole
  # shell (setup, storage, touch controls, gamepad, menu) is testable on a phone.
  add_library(ng2android SHARED ${NG2_COMMON_SOURCES} ng2_jni.cpp ng2_host_stub.cpp)
  set(NG2_TARGET ng2android)
else()
  # ---- Real runtime ------------------------------------------------------------
  foreach(v NG2_REPO_DIR REXGLUE_SDK_DIR)
    if(NOT ${v} OR NOT EXISTS "${${v}}")
      message(FATAL_ERROR "${v} must point to an existing directory (got '${${v}}')")
    endif()
  endforeach()
  if(NOT EXISTS "${REXGLUE_SDK_DIR}/include/rex/ui/surface_android.h")
    message(FATAL_ERROR
      "REXGLUE_SDK_DIR has no Android support. Apply it first:\n"
      "  tools/apply_sdk_patch.sh ${REXGLUE_SDK_DIR}")
  endif()
  if(NOT EXISTS "${REXGLUE_SDK_DIR}/thirdparty/sdl3/CMakeLists.txt")
    message(FATAL_ERROR
      "SDK submodules are empty. Run: git -C ${REXGLUE_SDK_DIR} submodule update --init --recursive")
  endif()
  if(NOT EXISTS "${NG2_REPO_DIR}/generated/default/ng2_init.h")
    message(FATAL_ERROR
      "${NG2_REPO_DIR}/generated/default is missing. Run codegen on a PC with a HOST build of "
      "the SDK (see docs/ANDROID_PORT.md, step 2). The Android build cannot run codegen.")
  endif()

  # The SDK as a subproject. Host tools (codegen library, rexglue CLI) cannot run
  # on the phone and are not built; tracy and tests are off.
  set(REXGLUE_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
  set(REXGLUE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
  set(REXGLUE_ENABLE_TRACY OFF CACHE BOOL "" FORCE)
  set(REXGLUE_USE_VULKAN ON CACHE BOOL "" FORCE)
  add_subdirectory(${REXGLUE_SDK_DIR} rexglue-sdk)   # also defines REXGLUE_SHARE_DIR and the helpers

  # Original sources with no Windows API inside, reused verbatim.
  set(NG2_REUSED
      ${NG2_REPO_DIR}/src/patch_hooks.cpp
      ${NG2_REPO_DIR}/src/ng2_chapter_fix.cpp
      ${NG2_REPO_DIR}/src/ng2_audio_fix.cpp
      ${NG2_REPO_DIR}/src/ng2_disc.cpp
      ${NG2_REPO_DIR}/src/third_party/stb_image.cpp)

  # ng2_saveimport.cpp has exactly one Windows call (_wfopen_s). Patch a copy
  # at configure time; the original file is never modified. (Nothing in the
  # Android build calls it today; it is kept so the save-import feature can be
  # enabled without another porting step.)
  file(READ ${NG2_REPO_DIR}/src/ng2_saveimport.cpp _si)
  string(REPLACE
    "_wfopen_s(&f, path.c_str(), L\"rb\") != 0 || f == nullptr"
    "(f = std::fopen(path.string().c_str(), \"rb\")) == nullptr"
    _si_patched "${_si}")
  if(_si STREQUAL _si_patched)
    message(WARNING "ng2_saveimport.cpp: _wfopen_s pattern not found; upstream changed")
  endif()
  set(_si_out ${CMAKE_BINARY_DIR}/ng2_patched/ng2_saveimport.cpp)
  file(WRITE ${_si_out} "${_si_patched}")

  # Generated code. Prefer the list codegen wrote; fall back to a glob.
  if(EXISTS ${NG2_REPO_DIR}/generated/default/sources.cmake)
    include(${NG2_REPO_DIR}/generated/default/sources.cmake)   # sets GENERATED_SOURCES
  else()
    file(GLOB_RECURSE GENERATED_SOURCES CONFIGURE_DEPENDS ${NG2_REPO_DIR}/generated/default/*.cpp)
  endif()

  # SDLActivity loads "libmain.so" and calls SDL_main in it, so the app is a
  # shared library named `main`, not an executable.
  add_library(main SHARED
      ${NG2_COMMON_SOURCES} ng2_main_android.cpp ng2_android_pad_driver.cpp ng2_android_frame.cpp
      ${NG2_REUSED} ${_si_out})
  set(NG2_TARGET main)

  target_compile_definitions(main PRIVATE NG2_WITH_REXGLUE=1 NG2_VERSION="android")
  target_include_directories(main PRIVATE
      ${CMAKE_CURRENT_SOURCE_DIR} ${NG2_REPO_DIR} ${NG2_REPO_DIR}/src
      ${NG2_REPO_DIR}/generated/default)

  # The recompiled game as its own object library, as the desktop template does.
  add_library(main_recomp OBJECT ${GENERATED_SOURCES})
  target_include_directories(main_recomp PRIVATE
      ${NG2_REPO_DIR} ${NG2_REPO_DIR}/src ${NG2_REPO_DIR}/generated/default)
  target_link_libraries(main_recomp PRIVATE rex::runtime)
  rexglue_apply_target_settings(main_recomp)
  # Guest SEH scopes: the desktop template adds -fasync-exceptions only on
  # Windows. On Android the guest faults are handled by the SDK's signal-based
  # exception handler, so no extra flag is added here.
  target_compile_options(main_recomp PRIVATE -g0)   # 563 MB of generated C++: no debug info on a phone build
  set_source_files_properties(${GENERATED_SOURCES} PROPERTIES COMPILE_OPTIONS "-g0")
  target_link_libraries(main PRIVATE main_recomp rex::runtime android log)

  # Adds windowed_app_main_sdl.cpp (SDL_main) + rex_app.cpp, and builds and
  # stages the Vulkan GPU plugin (librexgpu-xenos.so) beside libmain.so, where
  # Gradle packages both.
  rexglue_configure_target(main GPU_PLUGINS xenos)

  # SDLActivity's JNI entry points live in the static SDL library; keep them.
  if(TARGET SDL3-static)
    target_link_libraries(main PRIVATE -Wl,--whole-archive $<TARGET_FILE:SDL3-static> -Wl,--no-whole-archive)
  elseif(TARGET SDL3::SDL3-static)
    target_link_libraries(main PRIVATE -Wl,--whole-archive $<TARGET_FILE:SDL3::SDL3-static> -Wl,--no-whole-archive)
  else()
    message(WARNING "SDL static target not found by name; verify SDLActivity JNI symbols are retained")
  endif()
endif()

target_include_directories(${NG2_TARGET} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(${NG2_TARGET} PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(${NG2_TARGET} PRIVATE log android nativewindow)
