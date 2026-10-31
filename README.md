### Android port scaffold for the Ninja Gaiden II recompilation project

Quick start (test mode, no game or SDK needed):

1. Open this folder in Android Studio (Koala or newer; it creates the Gradle
   wrapper). Let it install NDK 26.3 and CMake 3.25.
2. Run on an arm64 device (API 29+). Landscape only.
3. Press *Main* to try the touch controls; the screen shows a live controller
   monitor.

Host tests (no Android needed):

    g++ -std=c++20 -Iapp/src/main/cpp tests/pad_state_test.cpp -o /tmp/t && /tmp/t
    python3 tests/xex_parse_check.py
