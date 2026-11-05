# Ninja's Dawn – Android

Android port for the *Ninja Gaiden II* recompilation project ("Ninja's Dawn", ReXGlue SDK).
**Read `docs/ANDROID_PORT.md` first:** it contains an honest assessment of the current status, what has been verified, and the steps for building the runtime environment.

- `app/` — Android Studio project (Kotlin + NDK, arm64): setup, touch controls,
  gamepad support, menus, and layout editor.
- `sdk-patch/` — Android patches for the ReXGlue SDK (see `docs/SDK_ANDROID_PATCH.md`).
- `tools/` — `ng2dev.py` (PC-side tool: installation, real-time FPS monitoring, input injection, reporting, and log analysis), `apply_sdk_patch.sh`, and `make_android_manifest.py`.
- `docs/DEBUGGING.md` — telemetry, logging, `adb` commands, and how to diagnose issues.
- `tests/` — host-side tests (non-Android).

Quick start (test mode, without the game/SDK)—choose one of the following:

- **Without Android Studio:** Push this folder to a GitHub repository, go to the *Actions* tab, run
  `android-test-apk`, download the APK from *Artifacts*, and install it on your phone (ensure you allow installation from unknown sources).
- **With Android Studio:** Open this folder (requires NDK 27, CMake 3.25) or run `./gradlew assembleDebug`.

On your phone: The *Check Device* card verifies your GPU, CPU, and RAM; tap *Play* to test the controls.

Run all checks: `tests/run_all.sh` (tests that cannot be executed will be marked as SKIP, not PASS). Host test:

    g++ -std=c++20 -Iapp/src/main/cpp tests/pad_state_test.cpp -o /tmp/t && /tmp/t
    python3 tests/xex_parse_check.py
    # Kotlin logic (requires kotlinc, android.jar, org.json): see tests/run_kotlin_tests.sh
