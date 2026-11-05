#!/usr/bin/env python3
"""Derive ng2_manifest_android.toml from the original ng2_manifest.toml.

Two changes:
  * drop config/hooks/diagnostics.toml. Its hooks call into src/diag_hooks.cpp,
    which is Win32-only (windows.h, GetProcAddress, RenderDoc) and is not
    compiled for Android; leaving the include would make codegen emit calls to
    symbols that do not exist and fail at link time.
  * add config/hooks/android_frame.toml (copied next to the other hooks): the
    one per-frame hook the FPS overlay and telemetry need.

usage: make_android_manifest.py <repo>/ng2_manifest.toml [out]
"""
import pathlib
import re
import shutil
import sys

src = pathlib.Path(sys.argv[1])
out = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else src.with_name("ng2_manifest_android.toml")
hook_src = pathlib.Path(__file__).resolve().parent / "hooks" / "android_frame.toml"

text = src.read_text(encoding="utf-8")
new, n = re.subn(r'^(\s*)"config/hooks/diagnostics\.toml",?[ \t]*\n',
                 r'\1"config/hooks/android_frame.toml",\n', text, flags=re.M)
if n != 1:
    sys.exit(f"expected exactly one diagnostics.toml include, found {n}; upstream changed")
out.write_text(new, encoding="utf-8")

dest = src.parent / "config" / "hooks" / "android_frame.toml"
if dest.parent.is_dir():
    shutil.copyfile(hook_src, dest)
    print(f"copied {hook_src.name} -> {dest}")
else:
    print(f"NOTE: {dest.parent} not found; copy {hook_src} there before running codegen")
print(f"wrote {out} (diagnostics.toml -> android_frame.toml)")
