#!/usr/bin/env python3
"""A stand-in for `adb` for tests/test_ng2dev.py. Records every call in $FAKE_ADB_LOG.
Behaviour switches (env): FAKE_NO_RECEIVER=1, FAKE_STATS=<file with JSON lines>, FAKE_REPORT=<file>."""
import json, os, shlex, sys

args = sys.argv[1:]
if args[:2] == ["-s", args[1] if len(args) > 1 else ""]:
    args = args[2:]
log = os.environ.get("FAKE_ADB_LOG")
if log:
    with open(log, "a") as f:
        f.write(json.dumps(args) + "\n")

def out(s): sys.stdout.write(s if s.endswith("\n") or not s else s + "\n")

if args[:1] == ["devices"]:
    out("List of devices attached\nFAKE123\tdevice\n")
elif args[:1] == ["install"]:
    out("Performing Streamed Install\nSuccess")
elif args[:1] == ["pull"]:
    src = os.environ.get("FAKE_REPORT")
    dest = os.path.join(args[2], os.path.basename(args[1]))
    if src:
        with open(src) as i, open(dest, "w") as o:
            o.write(i.read())
    out(f"{args[1]}: 1 file pulled.")
elif args[:1] == ["logcat"]:
    path = os.environ.get("FAKE_STATS")
    if path:
        with open(path) as fh:
            for line in fh: out(line.rstrip("\n"))
elif args[:1] == ["shell"]:
    remote = args[1]
    if remote.startswith("getprop "):
        out({"ro.product.model": "RMX3201", "ro.build.version.release": "11", "ro.build.version.sdk": "30",
             "ro.product.cpu.abi": "arm64-v8a", "ro.hardware": "mt6765", "ro.board.platform": "mt6765"}.get(remote.split()[1], ""))
    elif remote.startswith("pm list features"):
        out("feature:android.hardware.vulkan.version=4194304")
    elif remote.startswith("pm list packages"):
        out("package:com.ng2.dawn")
    elif remote.startswith("am start"):
        out("Starting: Intent { cmp=" + remote.split()[3] + " }")
    elif remote.startswith("am force-stop"):
        out("")
    elif remote.startswith("am broadcast"):
        if os.environ.get("FAKE_NO_RECEIVER"):
            out("Broadcasting: Intent { act=com.ng2.dawn.CMD }\nBroadcast completed: result=0")
        else:
            cmd = shlex.split(remote)[shlex.split(remote).index("--es") + 2]
            word = cmd.split()[0]
            data = {
                "ping": "pong v1.0.24-android0 sdk=30 native=true",
                "state": "game_dir=/x has_game=false runtime=false fps_overlay=true",
                "preflight": json.dumps([{"id": "native", "level": "PASS", "text": "Library native termuat."},
                                         {"id": "xex", "level": "WARN", "text": "default.xex tidak ditemukan (tidak wajib di mode uji).", "fix": "Pilih folder game."}]),
                "probe": "loaded=1;instance=1.1;devices=1;d0.name=PowerVR Rogue GE8320;d0.api=1.0.0;d0.f.independentBlend=1;d0.f.fragmentStoresAndAtomics=0;d0.swapchain=1;d0.gfxcompute=1",
                "report": "/sdcard/Android/data/com.ng2.dawn/files/reports/ng2-report-20261101-120000.txt",
            }.get(word, "ok " + cmd)
            out(f'Broadcasting: Intent {{ act=com.ng2.dawn.CMD }}\nBroadcast completed: result=1, data="{data}"')
    else:
        out("")
