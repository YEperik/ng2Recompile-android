#!/usr/bin/env python3
"""Derive ng2_manifest_android.toml from the original ng2_manifest.toml.

Only one change: drop config/hooks/diagnostics.toml. Its hooks call into
src/diag_hooks.cpp, which is Win32-only (windows.h, GetProcAddress, RenderDoc)
and is not compiled for Android. Leaving the include in would make codegen emit
calls to symbols that do not exist and fail at link time.

usage: make_android_manifest.py <repo>/ng2_manifest.toml [out]
"""
import re, sys, pathlib

src = pathlib.Path(sys.argv[1])
out = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else src.with_name("ng2_manifest_android.toml")
text = src.read_text(encoding="utf-8")
new, n = re.subn(r'^\s*"config/hooks/diagnostics\.toml",?\s*\n', "", text, flags=re.M)
if n != 1:
    sys.exit(f"expected exactly one diagnostics.toml include, found {n}; upstream changed")
out.write_text(new, encoding="utf-8")
print(f"wrote {out} (removed diagnostics.toml include)")
