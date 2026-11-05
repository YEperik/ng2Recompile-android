#!/bin/sh
# Compiles the pure-logic Kotlin sources against a real android.jar (API 30) and
# runs tests/kotlin on the plain JVM. Needs kotlinc on PATH and ANDROID_JAR set;
# EXTRA_CP must hold a real org.json (android.jar only has stubs of it) for the ControlLayout tests.
set -e
HERE="$(cd "$(dirname "$0")/.." && pwd)"
J="$HERE/app/src/main/java/com/ng2/dawn"
KLIB="$(dirname "$(dirname "$(command -v kotlinc)")")/lib"
OUT="$(mktemp -d)"
STUBS="${STUBS:-$HERE/tests/stubs}"
# Everything under com/ng2/dawn plus SDL's Java (resolved by kotlinc) plus the androidx stubs:
# so the activities are type-checked too, not just the pure logic.
kotlinc -nowarn -jvm-target 17 -cp "$ANDROID_JAR:$KLIB/kotlinx-coroutines-core-jvm.jar" -d "$OUT" \
  "$J" "$HERE"/app/src/main/java/org/libsdl/app/*.java "$STUBS" "$HERE/tests/kotlin/Tests.kt"
java -cp "$OUT:${EXTRA_CP:+$EXTRA_CP:}$ANDROID_JAR:$KLIB/kotlin-stdlib.jar:$KLIB/kotlinx-coroutines-core-jvm.jar" com.ng2.dawn.TestsKt
