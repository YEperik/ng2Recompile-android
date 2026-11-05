#!/bin/sh
# Runs every check that works without an Android device. Missing tools are reported as SKIP, not PASS.
#   optional env: ANDROID_JAR (android.jar, API 34)  EXTRA_CP (dir with a real org.json)  NDK_STUBS (stub NDK headers)
cd "$(dirname "$0")/.." || exit 2
fail=0
row() { printf "%-60s %s\n" "$1" "$2"; if [ "$2" = FAIL ]; then fail=1; fi; }
run() { name="$1"; shift; if "$@" >/tmp/ng2_check.log 2>&1; then row "$name" PASS; else row "$name" FAIL; sed 's/^/    /' /tmp/ng2_check.log | tail -15; fi; }

if command -v g++ >/dev/null; then
  run "C++ pad mixer (3 sources)"  sh -c 'g++ -std=c++20 -Wall -Wextra -Iapp/src/main/cpp tests/pad_state_test.cpp -o /tmp/ng2_t1 && /tmp/ng2_t1'
  run "C++ frame statistics"       sh -c 'g++ -std=c++20 -Wall -Wextra -Iapp/src/main/cpp tests/stats_test.cpp app/src/main/cpp/ng2_stats.cpp -o /tmp/ng2_t2 && /tmp/ng2_t2'
else row "C++ tests (g++ missing)" SKIP; fi
run "JNI signatures Kotlin <-> C++" python3 tests/check_jni.py
run "XEX title-id parser (reference)" python3 tests/xex_parse_check.py
run "ng2dev.py (analysis, commands, watch, UDP)" python3 -W error::ResourceWarning tests/test_ng2dev.py
run "manifest/resources/CI well-formed" sh -c 'for f in app/src/main/AndroidManifest.xml app/src/main/res/*/*.xml; do xmllint --noout $f || exit 1; done; python3 -c "import yaml;yaml.safe_load(open(\".github/workflows/android-test-apk.yml\"))"'
run "make_android_manifest.py on the original manifest" sh -c 'T=$(mktemp -d); mkdir -p $T/config/hooks; [ -f "$NG2_REPO/ng2_manifest.toml" ] && cp "$NG2_REPO/ng2_manifest.toml" $T/ || printf "x = [\n  \"config/hooks/diagnostics.toml\",\n]\n" > $T/ng2_manifest.toml; python3 tools/make_android_manifest.py $T/ng2_manifest.toml $T/out.toml && grep -q android_frame $T/out.toml && ! grep -q diagnostics.toml $T/out.toml && test -f $T/config/hooks/android_frame.toml'
if [ -n "$NDK_STUBS" ] && command -v g++ >/dev/null; then
  for f in ng2_jni ng2_jni_input ng2_common ng2_iso_android ng2_host_stub ng2_stats ng2_android_frame ng2_gpu_probe; do
    run "C++ syntax: $f.cpp" g++ -std=c++20 -fsyntax-only -Wall -Wextra -Wno-unused-parameter -I"$NDK_STUBS" ${VULKAN_INC:+-I"$VULKAN_INC"} -Iapp/src/main/cpp app/src/main/cpp/$f.cpp
  done
else row "C++ syntax of the Android shell (set NDK_STUBS)" SKIP; fi
if command -v kotlinc >/dev/null && [ -n "$ANDROID_JAR" ]; then
  run "Kotlin: full compile + 500+ logic checks" sh -c './tests/run_kotlin_tests.sh 2>&1 | tail -3 | grep -q "0 failures"'
else row "Kotlin tests (need kotlinc + ANDROID_JAR)" SKIP; fi
[ $fail = 0 ] && echo "ALL OK" || echo "SOMETHING FAILED"
exit $fail
