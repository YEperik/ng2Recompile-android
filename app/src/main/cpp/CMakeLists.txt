cmake_minimum_required(VERSION 3.22)
project(ng2android LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(NG2_WITH_REXGLUE "Link the ReXGlue runtime and the recompiled game" OFF)
set(NG2_REPO_DIR "" CACHE PATH "Original Ninja's Dawn repository (src/, generated/)")
set(REXGLUE_ANDROID_DIR "" CACHE PATH "ReXGlue SDK built for android-arm64")

# ---- Always: the Android shell (touch, gamepad, JNI, lifecycle) -------------
set(NG2_SHELL_SOURCES ng2_jni.cpp ng2_common.cpp ng2_iso_android.cpp)

if(NOT NG2_WITH_REXGLUE)
  # No runtime: StubHost draws a live controller monitor. This is a complete,
  # installable app for testing everything except the game itself.
  add_library(ng2android SHARED ${NG2_SHELL_SOURCES} ng2_host_stub.cpp)
else()
  # ---- With the runtime ------------------------------------------------------
  foreach(v NG2_REPO_DIR REXGLUE_ANDROID_DIR)
    if(NOT ${v} OR NOT EXISTS "${${v}}")
      message(FATAL_ERROR "${v} must point to an existing directory (got '${${v}}')")
    endif()
  endforeach()
  if(NOT EXISTS "${NG2_REPO_DIR}/generated/default")
    message(FATAL_ERROR
      "${NG2_REPO_DIR}/generated/default is missing. It is produced by "
      "`rexglue codegen` from YOUR default.xex and is not in the repository.")
  endif()

  # Original sources that contain no Windows API and are reused verbatim.
  set(NG2_REUSED
      ${NG2_REPO_DIR}/src/patch_hooks.cpp
      ${NG2_REPO_DIR}/src/ng2_chapter_fix.cpp
      ${NG2_REPO_DIR}/src/ng2_audio_fix.cpp
      ${NG2_REPO_DIR}/src/ng2_disc.cpp
      ${NG2_REPO_DIR}/src/third_party/stb_image.cpp)

  # ng2_saveimport.cpp has exactly one Windows call (_wfopen_s). Patch a copy
  # at configure time; the original file is never modified.
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

  file(GLOB_RECURSE NG2_GENERATED CONFIGURE_DEPENDS
       ${NG2_REPO_DIR}/generated/default/*.cpp)

  add_library(ng2android SHARED
      ${NG2_SHELL_SOURCES} ng2_rex_host.cpp ng2_android_pad_driver.cpp
      ${NG2_REUSED} ${_si_out} ${NG2_GENERATED})

  target_compile_definitions(ng2android PRIVATE NG2_WITH_REXGLUE=1
      NG2_VERSION="android")
  target_include_directories(ng2android PRIVATE
      ${NG2_REPO_DIR}/src ${NG2_REPO_DIR} ${NG2_REPO_DIR}/generated/default
      ${REXGLUE_ANDROID_DIR}/include)
  # ASSUMPTION: the SDK installs static libs here. Adjust to the SDK's real
  # layout / its CMake package once an Android build of it exists.
  file(GLOB _rex_libs ${REXGLUE_ANDROID_DIR}/lib/*.a)
  target_link_libraries(ng2android PRIVATE -Wl,--start-group ${_rex_libs} -Wl,--end-group)
endif()

target_include_directories(ng2android PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(ng2android PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(ng2android PRIVATE log android nativewindow)
