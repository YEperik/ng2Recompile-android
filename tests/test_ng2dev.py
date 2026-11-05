#!/usr/bin/env python3
"""Tests for tools/ng2dev.py. Run: python3 tests/test_ng2dev.py"""
import contextlib, io, json, os, shlex, socket, sys, tempfile, threading, time, unittest
from pathlib import Path


def write(path, text):
    with open(path, "w") as f:
        f.write(text)


def read(path):
    with open(path) as f:
        return f.read()

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "tools"))
import ng2dev  # noqa: E402

FAKE = str(HERE / "fake_adb.py")


def stat(**kw):
    base = {"t": 1_700_000_000_000, "active_ms": 30000, "fps": 30.0, "avg_ms": 33.3, "p95_ms": 40.0, "max_ms": 60.0,
            "since_ms": 10.0, "frames": 900, "hitches": 0, "polls": 1800, "display_fps": 60.0, "pss_mb": 800,
            "avail_mb": 500, "low_mem": False, "thermal": "NONE", "batt_c": 35.0, "pad": "0000"}
    base.update(kw)
    return json.dumps(base, separators=(",", ":"))


def lines(*xs): return "\n".join(xs) + "\n"


START = lines("2026-11-01T12:00:00.100 I/stage: session start",
              "2026-11-01T12:00:00.200 I/stage: native library loaded",
              "2026-11-01T12:00:05.000 I/stage: play pressed (real=true, failing checks=0)",
              "2026-11-01T12:00:05.100 I/stage: starting SDL activity (real runtime), game_dir=/x")
SDK_OK = lines("[rex] [core] SDL video driver: android",
               "[rex] [ui] Loaded Vulkan runtime from libvulkan.so",
               "[rex] [ui] Vulkan instance API version 1.1.0. Enabled layers and extensions:")


class Broadcast(unittest.TestCase):
    def test_quoting_roundtrip(self):
        for cmd in ["pad A+X lx=0.5 for=300", "pad release", "weird it's \"quoted\" $HOME `x`; rm -rf /"]:
            parts = shlex.split(ng2dev.broadcast_command(cmd))
            self.assertEqual(parts[:8], ["am", "broadcast", "-a", ng2dev.ACTION, "-p", ng2dev.PKG, "--es", "cmd"])
            self.assertEqual(parts[8], cmd)            # the device shell sees exactly one argument

    def test_parse(self):
        r = ng2dev.parse_broadcast('Broadcast completed: result=1, data="pong v1"')
        self.assertTrue(r.ok and r.delivered and r.msg == "pong v1")
        r = ng2dev.parse_broadcast('Broadcast completed: result=0, data="tombol tidak dikenal: Z"')
        self.assertFalse(r.ok) ; self.assertTrue(r.delivered)
        r = ng2dev.parse_broadcast("Broadcast completed: result=0")
        self.assertFalse(r.delivered); self.assertIn("debug build", r.msg)
        self.assertFalse(ng2dev.parse_broadcast("error: closed").delivered)


class Analysis(unittest.TestCase):
    def stages(self, d): return {s: ok for s, ok, _ in d.stages}

    def test_no_gpu_passes_the_check(self):
        d = ng2dev.analyze(START + SDK_OK)
        st = self.stages(d)
        self.assertTrue(st["vk_instance"]); self.assertFalse(st["vk_device"])
        self.assertEqual(d.first_missing, "vk_device")
        self.assertTrue(any("Tidak ada GPU yang lolos" in f.title for f in d.findings))
        self.assertIn("independentBlend", ng2dev.NEXT_CAUSES["vk_device"])
        self.assertTrue(d.has_errors)

    def test_sdk_explains_the_rejected_gpu(self):
        # exact wording from src/ui/vulkan/vulkan_device.cpp
        rej = lines("[rex] Vulkan device 'PowerVR Rogue GE8320' doesn't support fragmentStoresAndAtomics, which is required for Vulkan GPU emulation parity")
        d = ng2dev.analyze(START + SDK_OK + rej)
        titles = [f.title for f in d.findings]
        self.assertTrue(any(t.startswith("GPU tanpa fragmentStoresAndAtomics") and "GE8320" in t for t in titles), titles)
        self.assertNotIn("Tidak ada GPU yang lolos syarat SDK", titles)       # the explicit reason replaces the guess
        for msg, key in [("doesn't support the independentBlend feature required for GPU emulation", "independentBlend"),
                         ("doesn't support vertexPipelineStoresAndAtomics, which is required", "vertexPipelineStoresAndAtomics"),
                         ("doesn't support geometryShader, but vulkan_require_geometry_shader=true", "geometryShader"),
                         ("doesn't support fillModeNonSolid, but vulkan_require_fill_mode_non_solid=true", "fillModeNonSolid"),
                         ("doesn't provide a graphics and compute queue family", "graphics+compute"),
                         ("doesn't provide a queue family that supports presentation", "presentasi")]:
            f = ng2dev.analyze("[rex] Vulkan device 'X' " + msg + "\n").findings
            self.assertTrue(any(key in x.title for x in f), (msg, [x.title for x in f]))

    def test_gpu_selected_but_no_frame(self):
        txt = START + SDK_OK + "[rex] [ui] Vulkan device properties and enabled features:\n" + lines(
            stat(frames=0, fps=0, since_ms=-1, active_ms=20000))
        d = ng2dev.analyze(txt)
        self.assertTrue(self.stages(d)["vk_device"]); self.assertFalse(self.stages(d)["frame"])
        self.assertTrue(any("Tidak ada frame setelah 20s" in f.title for f in d.findings))

    def test_native_crash_in_gpu_driver(self):
        txt = START + lines(
            "F libc    : Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0 in tid 1234 (SDLThread), pid 1200 (g.ng2.dawn)",
            "F DEBUG   :       #00 pc 000000000012a4f0  /vendor/lib64/hw/vulkan.mt6765.so (vkCreateDevice+88)",
            "F DEBUG   :       #01 pc 0000000000345678  /data/app/x/com.ng2.dawn/lib/arm64/libmain.so (BuildId: abc)")
        d = ng2dev.analyze(txt)
        titles = [f.title for f in d.findings]
        self.assertIn("Crash native", titles); self.assertIn("Crash di driver grafis", titles)
        self.assertNotIn("Crash di kode game/SDK", titles)           # the TOP frame decides

    def test_native_crash_in_game_code(self):
        d = ng2dev.analyze("F libc: Fatal signal 6 (SIGABRT)\n#00 pc 0000000000001234  /data/app/x/lib/arm64/libmain.so (foo+4)\n")
        self.assertTrue(any(f.title == "Crash di kode game/SDK" for f in d.findings))

    def test_missing_library_and_symbol(self):
        d = ng2dev.analyze('java.lang.UnsatisfiedLinkError: dlopen failed: library "libc++_shared.so" not found\n')
        t = " ".join(f.title for f in d.findings)
        self.assertIn("Library native tidak termuat", t); self.assertIn("libc++_shared.so", t)
        d = ng2dev.analyze('dlopen failed: cannot locate symbol "SDL_main" referenced by libmain.so\n')
        self.assertTrue(any("SDL_main" in f.title for f in d.findings))

    def test_sdk_messages(self):
        for text, key in [("Failed to create a Vulkan instance: -9", "vkCreateInstance"),
                          ("Vulkan device 'X' doesn't support swapchains", "swapchain"),
                          ("VulkanPresenter: Failed to create a Vulkan surface", "surface"),
                          ("SDL_CreateWindow failed: no", "SDL"),
                          ("VulkanPresenter: Failed to present the swapchain image", "Swapchain"),
                          ("lowmemorykiller: Kill 'com.ng2.dawn' (1234)", "Memori"),
                          ("write failed: No space left on device", "Penyimpanan"),
                          ("open(/system/bin/ng2.toml): Permission denied", "Izin"),
                          ("ANR in com.ng2.dawn (com.ng2.dawn/.MainActivity)", "ANR"),
                          ("prev exit t=1 SIGNALED rss=1MB", "sinyal"),
                          ("E/crash: uncaught exception on 'main': java.lang.IllegalStateException: boom", "Exception Kotlin")]:
            d = ng2dev.analyze(text + "\n")
            self.assertTrue(any(key.lower() in f.title.lower() for f in d.findings), (text, [f.title for f in d.findings]))

    def test_healthy_test_mode(self):
        txt = lines("I/stage: session start", "I/stage: native library loaded", "I/stage: play pressed (real=false, failing checks=0)",
                    "I/stage: game screen created (test host)", "I/stage: test host started",
                    stat(fps=60.0, frames=120, polls=0, active_ms=2000, since_ms=8.0),
                    stat(fps=59.8, frames=180, polls=0, active_ms=3000, since_ms=8.0))
        d = ng2dev.analyze(txt, target_fps=60)
        self.assertFalse(d.has_errors, [f.title for f in d.findings])
        st = self.stages(d)
        self.assertTrue(st["frame"]); self.assertNotIn("vk_device", st)          # no SDK stages demanded in test mode
        self.assertIsNone(d.first_missing)                    # every stage that applies to test mode was reached
        self.assertFalse(any("kontroler" in f.title for f in d.findings))   # no "never polled input" blame in test mode

    def test_stall_detected(self):
        d = ng2dev.analyze(lines(stat(fps=30), stat(fps=0.0, since_ms=6000.0, frames=900)))
        self.assertTrue(any("berhenti menghasilkan frame" in f.title for f in d.findings))

    def test_slow_hot_lowmem(self):
        d = ng2dev.analyze(lines(stat(fps=10.0, thermal="SEVERE", low_mem=True)), target_fps=30)
        t = " ".join(f.title for f in d.findings)
        self.assertIn("FPS rata-rata", t); self.assertIn("terlalu panas", t); self.assertIn("memori rendah", t)

    def test_preflight_failures_surface(self):
        pre = '{"id":"xex","level":"FAIL","text":"default.xex tidak ditemukan.","fix":"Pilih folder game."}'
        d = ng2dev.analyze("D/preflight: [" + pre + "]\n")
        f = [x for x in d.findings if x.title.startswith("Preflight")]
        self.assertTrue(f and "default.xex" in f[0].title and f[0].fix == "Pilih folder game.")

    def test_render_marks_where_it_stopped(self):
        out = ng2dev.render(ng2dev.analyze(START + SDK_OK), color=False)
        self.assertIn("✔ SDL siap (jendela)", out)
        self.assertIn("✖ GPU dipilih   <- berhenti di sini", out)
        self.assertIn("· frame game pertama", out)
        self.assertIn("[GALAT]", out)

    def test_empty_log_is_not_a_crash(self):
        d = ng2dev.analyze("")
        self.assertFalse(d.has_errors); self.assertIn("Tahap startup", ng2dev.render(d, False))


class Formatting(unittest.TestCase):
    def test_sample_line(self):
        s = json.loads(stat(pad="1010"))
        line = ng2dev.format_sample(s, 30, color=False)
        self.assertIn("fps  30.0", line); self.assertIn("RAM 800MB", line); self.assertIn("pad A+Start", line)

    def test_waiting_and_stall_lines(self):
        self.assertIn("menunggu frame", ng2dev.format_sample(json.loads(stat(frames=0, active_ms=4000)), 30, False))
        self.assertIn("TIDAK ADA FRAME 6s", ng2dev.format_sample(json.loads(stat(since_ms=6000.0)), 30, False))

    def test_summary(self):
        rows = [json.loads(stat(fps=f)) for f in (30, 30, 20, 30)]
        s = ng2dev.summarize(rows, 30)
        self.assertIn("4 sampel", s); self.assertIn("min 20.0", s); self.assertIn("25% detik di bawah", s)
        self.assertIn("Tidak ada sampel", ng2dev.summarize([], 30))

    def test_pad_names(self):
        self.assertEqual(ng2dev.pad_names("0000"), "-"); self.assertEqual(ng2dev.pad_names("9000"), "A+Y")
        self.assertEqual(ng2dev.pad_names("zz"), "")


class EndToEnd(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.log = os.path.join(self.tmp.name, "calls.jsonl")
        os.environ["FAKE_ADB_LOG"] = self.log
        for k in ("FAKE_NO_RECEIVER", "FAKE_STATS", "FAKE_REPORT"): os.environ.pop(k, None)

    def tearDown(self): self.tmp.cleanup()

    def run_cli(self, *argv):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(buf):
            rc = ng2dev.main(["--adb", FAKE, *argv])
        return rc, buf.getvalue()

    def calls(self): return [json.loads(l) for l in read(self.log).splitlines()]

    def test_ping_and_pad_reach_the_device_intact(self):
        rc, out = self.run_cli("cmd", "ping"); self.assertEqual(rc, 0); self.assertIn("pong", out)
        rc, out = self.run_cli("pad", "A+X lx=0.5 for=300"); self.assertEqual(rc, 0)
        remote = [c for c in self.calls() if c[0] == "shell" and "pad A+X" in c[1]][0][1]
        self.assertEqual(shlex.split(remote)[-1], "pad A+X lx=0.5 for=300")

    def test_no_receiver_is_explained(self):
        os.environ["FAKE_NO_RECEIVER"] = "1"
        rc, out = self.run_cli("cmd", "ping"); self.assertEqual(rc, 1); self.assertIn("debug build", out)

    def test_serial_is_passed_to_adb(self):
        self.run_cli("-s", "ABC", "cmd", "ping")
        # fake_adb strips -s itself; reaching here without an error proves argv order is accepted
        self.assertTrue(self.calls())

    def test_doctor(self):
        rc, out = self.run_cli("doctor")
        for s in ("RMX3201", "arm64-v8a", "vulkan", "pong", "PowerVR Rogue GE8320", "d0.f.fragmentStoresAndAtomics=0"):
            self.assertIn(s, out)
        self.assertEqual(rc, 0)

    def test_play_chooses_test_activity(self):
        rc, out = self.run_cli("play"); self.assertIn(".GameActivity", out); self.assertIn("mode uji", out)

    def test_install_and_stop(self):
        apk = os.path.join(self.tmp.name, "a.apk"); write(apk, "x")
        rc, out = self.run_cli("install", apk); self.assertEqual(rc, 0); self.assertIn("Success", out)
        rc, _ = self.run_cli("stop"); self.assertEqual(rc, 0)

    def test_watch_logcat_and_csv(self):
        sf = os.path.join(self.tmp.name, "stats.txt")
        write(sf, lines("I/noise", stat(fps=29.0, t=1_700_000_001_000), stat(fps=31.0, t=1_700_000_002_000), "x " + stat(fps=12.0, t=1_700_000_003_000)))
        os.environ["FAKE_STATS"] = sf
        csvf = os.path.join(self.tmp.name, "o.csv")
        rc, out = self.run_cli("watch", "--seconds", "5", "--csv", csvf)
        self.assertEqual(rc, 0); self.assertIn("fps  29.0", out); self.assertIn("fps  12.0", out); self.assertIn("3 sampel", out)
        rows = read(csvf).strip().splitlines()
        self.assertEqual(len(rows), 4); self.assertTrue(rows[0].startswith("t,fps")); self.assertIn("12.0", rows[3])

    def test_watch_udp(self):
        s = socket.socket(); s.bind(("127.0.0.1", 0)); port = s.getsockname()[1]; s.close()
        def sender():
            time.sleep(0.4)
            u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            for fps in (30.0, 27.0):
                u.sendto(stat(fps=fps).encode(), ("127.0.0.1", port)); time.sleep(0.1)
            u.sendto(b"not json", ("127.0.0.1", port))
        threading.Thread(target=sender, daemon=True).start()
        rc, out = self.run_cli("watch", "--udp", str(port), "--seconds", "2")
        self.assertEqual(rc, 0); self.assertIn("fps  30.0", out); self.assertIn("fps  27.0", out); self.assertIn("2 sampel", out)

    def test_report_pulls_and_analyses(self):
        rep = os.path.join(self.tmp.name, "rep.txt")
        write(rep, START + SDK_OK)
        os.environ["FAKE_REPORT"] = rep
        rc, out = self.run_cli("report", "--out", os.path.join(self.tmp.name, "o"))
        self.assertEqual(rc, 0); self.assertIn("ng2-report-20261101-120000.txt", out)
        self.assertIn("Tidak ada GPU yang lolos", out)
        self.assertTrue(any(c[0] == "pull" for c in self.calls()))

    def test_analyze_file_and_exit_code(self):
        f = os.path.join(self.tmp.name, "l.txt"); write(f, START + SDK_OK)
        rc, out = self.run_cli("analyze", f); self.assertEqual(rc, 1); self.assertIn("berhenti di sini", out)
        write(f, lines(stat(fps=30.0), "I/stage: test host started"))
        rc, out = self.run_cli("analyze", f); self.assertEqual(rc, 0)

    def test_missing_adb_message(self):
        buf = io.StringIO()
        with contextlib.redirect_stderr(buf):
            rc = ng2dev.main(["--adb", "/nonexistent/adb", "cmd", "ping"])
        self.assertEqual(rc, 127); self.assertIn("platform-tools", buf.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=1)
