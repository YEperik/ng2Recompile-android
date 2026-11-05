#!/usr/bin/env python3
"""ng2dev - drive and diagnose Ninja's Dawn on a phone from the PC.

Needs only Python 3.8+ and `adb` on PATH. The app must be a DEBUG build for the
`pad`, `fps`, `report` ... commands (it registers its command receiver only
when debuggable); `watch`, `logs` and `analyze` work with any build.

  ng2dev.py doctor                 is the phone/app ready? (adb, Android, app, GPU, checklist)
  ng2dev.py install [APK]          adb install -r -d  (default: newest debug APK under app/build)
  ng2dev.py run                    open the setup screen
  ng2dev.py play                   open the game screen directly
  ng2dev.py stop                   force-stop the app
  ng2dev.py watch [--udp PORT] [--csv FILE] [--seconds N] [--target 30]
                                   live FPS / frame time / memory / thermal, one line per second
  ng2dev.py pad "A+X lx=0.5 for=300"   inject controller input   (also: pad release)
  ng2dev.py fps on|off             FPS overlay on the phone
  ng2dev.py cmd "<command>"        any app command: ping pad fps stats preflight probe report loglevel state
  ng2dev.py report [--out DIR]     make a diagnostic report on the phone, pull it, analyse it
  ng2dev.py logs [--errors] [--save FILE] [--seconds N]
                                   live, filtered logcat (app + game runtime + crashes)
  ng2dev.py analyze FILE|-         explain a report or a logcat dump: where startup stopped and why
"""
import argparse
import csv
import json
import os
import re
import shlex
import socket
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple

PKG = "com.ng2.dawn"
ACTION = "com.ng2.dawn.CMD"

# ------------------------------------------------------------------------------------------
# adb plumbing
# ------------------------------------------------------------------------------------------


class Adb:
    def __init__(self, adb: str = "adb", serial: Optional[str] = None):
        self.adb = adb
        self.serial = serial

    def base(self) -> List[str]:
        cmd = shlex.split(self.adb) if " " in self.adb else [self.adb]
        if self.serial:
            cmd += ["-s", self.serial]
        return cmd

    def run(self, *args: str, timeout: int = 60, check: bool = False) -> subprocess.CompletedProcess:
        return subprocess.run(self.base() + list(args), capture_output=True, text=True,
                              timeout=timeout, check=check)

    def shell(self, remote: str, timeout: int = 60) -> str:
        """`remote` is ONE string for the device shell (quote it yourself)."""
        r = self.run("shell", remote, timeout=timeout)
        return (r.stdout or "") + (r.stderr or "")

    def popen(self, *args: str) -> subprocess.Popen:
        return subprocess.Popen(self.base() + list(args), stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, bufsize=1)


def broadcast_command(cmd: str) -> str:
    """The remote shell line that sends one command to the app."""
    return " ".join(["am", "broadcast", "-a", ACTION, "-p", PKG, "--es", "cmd", shlex.quote(cmd)])


_BC = re.compile(r"Broadcast completed: result=(-?\d+)(?:, data=\"(.*)\")?", re.S)


@dataclass
class Reply:
    ok: bool
    msg: str
    delivered: bool = True


def parse_broadcast(output: str) -> Reply:
    m = _BC.search(output)
    if not m:
        return Reply(False, "no 'Broadcast completed' line: " + output.strip()[:200], delivered=False)
    code, data = int(m.group(1)), m.group(2)
    if data is None:
        return Reply(False, "no receiver answered: app not running, or not a debug build", delivered=False)
    return Reply(code == 1, data)


def send(adb: Adb, cmd: str) -> Reply:
    return parse_broadcast(adb.shell(broadcast_command(cmd)))


# ------------------------------------------------------------------------------------------
# Log analysis
# ------------------------------------------------------------------------------------------

# Pipeline stages in the order a healthy start passes through them. A stage is "seen" when a
# marker matches. Markers 1-4,10 are written by the app (DiagLog.stage / ng2_android_pad_driver);
# 5-8 are the SDK's own INFO lines (src/ui/window_sdl.cpp, vulkan_instance.cpp, vulkan_device.cpp).
STAGES: List[Tuple[str, str, str]] = [
    ("app", r"stage: session start", "proses dimulai"),
    ("native", r"stage: native library loaded", "library native termuat"),
    ("play", r"stage: play pressed", "tombol Main ditekan"),
    ("activity", r"stage: (starting SDL activity|game screen created|test host started)", "layar game dibuat"),
    ("sdl", r"SDL video driver:", "SDL siap (jendela)"),
    ("vk_loader", r"Loaded Vulkan runtime from", "loader Vulkan ditemukan"),
    ("vk_instance", r"Vulkan instance API version", "instance Vulkan dibuat"),
    ("vk_device", r"Vulkan device properties and enabled features", "GPU dipilih"),
    ("frame", None, "frame game pertama"),          # decided from telemetry (frames > 0)
    ("input", r"guest polled input for the first time", "game membaca kontroler"),
]

NEXT_CAUSES: Dict[str, str] = {
    "native": "Library native gagal dimuat (ABI/pengemasan APK). Cek `unzip -l app.apk | grep .so`.",
    "play": "Tombol Main belum ditekan, atau app berhenti sebelum itu.",
    "activity": "Layar game tidak terbentuk: lihat crash Kotlin (crash-*.txt) / UnsatisfiedLinkError.",
    "sdl": "SDL tidak sempat membuat jendela: libmain.so/SDL_main tidak berjalan. Cari 'UnsatisfiedLinkError' atau 'SDL_main'.",
    "vk_loader": "Loader Vulkan tidak ditemukan/dimuat oleh SDK. Perangkat tanpa libvulkan.so?",
    "vk_instance": "vkCreateInstance gagal. Cari 'Failed to create a Vulkan instance'.",
    "vk_device": "Tidak ada GPU yang memenuhi syarat SDK (fitur wajib: independentBlend, fragmentStoresAndAtomics, "
                 "vertexPipelineStoresAndAtomics, VK_KHR_swapchain, antrean graphics+compute). SDK mencatat alasannya sebagai \"doesn't support ...\"; jalankan juga `ng2dev.py cmd probe`.",
    "frame": "Runtime hidup tetapi game tidak menghasilkan frame: macet saat memuat XEX/boot, atau hook frame tidak terpasang.",
    "input": "Game berjalan tetapi belum membaca kontroler (mungkin masih di video intro/loading).",
}


@dataclass
class Finding:
    severity: str          # "error" | "warn" | "info"
    title: str
    detail: str = ""
    fix: str = ""


@dataclass
class Diagnosis:
    stages: List[Tuple[str, bool, str]] = field(default_factory=list)   # (id, seen, label)
    findings: List[Finding] = field(default_factory=list)
    stats: Dict[str, float] = field(default_factory=dict)

    @property
    def last_stage(self) -> Optional[str]:
        seen = [s for s, ok, _ in self.stages if ok]
        return seen[-1] if seen else None

    @property
    def first_missing(self) -> Optional[str]:
        for s, ok, _ in self.stages:
            if not ok:
                return s
        return None

    @property
    def has_errors(self) -> bool:
        return any(f.severity == "error" for f in self.findings)


# (regex, severity, title, fix)  - the first capture group, if any, is appended to the title.
RULES: List[Tuple[str, str, str, str]] = [
    (r"UnsatisfiedLinkError.*?(dlopen failed: [^\n]*)", "error", "Library native tidak termuat",
     "Pastikan APK memuat lib/arm64-v8a/lib<nama>.so (libmain.so untuk runtime, libng2android.so untuk mode uji) dan HP arm64."),
    (r"dlopen failed: library \"([^\"]+)\" not found", "error", "Library tidak ditemukan:",
     "Dependensi .so hilang dari APK (mis. libc++_shared.so: pakai ANDROID_STL=c++_shared dan sertakan)."),
    (r"cannot locate symbol \"([^\"]+)\"", "error", "Simbol native tidak ditemukan:",
     "Simbol JNI SDL dibuang linker -> pastikan --whole-archive pada SDL3-static; atau versi NDK/SDK tidak cocok."),
    (r"Failed to get Vulkan loader function pointers", "error", "Loader Vulkan gagal dimuat",
     "Perangkat tanpa libvulkan.so yang bisa dipakai. Cek `ng2dev.py cmd probe`."),
    (r"Failed to create a Vulkan instance: ([^\n]*)", "error", "vkCreateInstance gagal:",
     "VK_ERROR_INCOMPATIBLE_DRIVER = driver terlalu lama; VK_ERROR_EXTENSION_NOT_PRESENT = ekstensi surface Android tidak ada."),
    (r"The Vulkan instance doesn't support surface types", "error", "Instance Vulkan tanpa VK_KHR_android_surface",
     "Driver tidak mendukung surface Android; game tidak bisa menampilkan gambar di perangkat ini."),
    (r"Vulkan device '([^']*)' doesn't support swapchains", "error", "GPU tanpa VK_KHR_swapchain:",
     "GPU ini tidak dapat menampilkan ke layar lewat Vulkan."),
    (r"Vulkan device '([^']*)' doesn't support the independentBlend feature", "error", "GPU tanpa independentBlend:",
     "Fitur wajib emulasi dan tidak bisa ditiru (SDK menyebut hanya driver sangat tua, mis. Adreno 4xx, yang tidak punya). Game tidak bisa jalan di GPU ini."),
    (r"Vulkan device '([^']*)' doesn't support fragmentStoresAndAtomics", "error", "GPU tanpa fragmentStoresAndAtomics:",
     "Fitur wajib SDK. GPU/driver ini tidak bisa menjalankan emulasi Xenos SDK; mode uji tetap bisa dipakai."),
    (r"Vulkan device '([^']*)' doesn't support vertexPipelineStoresAndAtomics", "error", "GPU tanpa vertexPipelineStoresAndAtomics:",
     "Fitur wajib SDK. GPU/driver ini tidak bisa menjalankan emulasi Xenos SDK."),
    (r"Vulkan device '([^']*)' doesn't support geometryShader, but vulkan_require_geometry_shader=true", "error",
     "Syarat geometryShader masih aktif untuk:", "Aplikasi seharusnya mematikannya lewat REX_VULKAN_REQUIRE_GEOMETRY_SHADER=false sebelum SDL_main; cek GameSdlActivity.onCreate."),
    (r"Vulkan device '([^']*)' doesn't support fillModeNonSolid, but vulkan_require_fill_mode_non_solid=true", "error",
     "Syarat fillModeNonSolid masih aktif untuk:", "Aplikasi seharusnya mematikannya lewat REX_VULKAN_REQUIRE_FILL_MODE_NON_SOLID=false."),
    (r"Vulkan device '([^']*)' doesn't provide a graphics and compute queue family", "error", "GPU tanpa antrean graphics+compute:",
     "GPU ini tidak bisa dipakai oleh SDK."),
    (r"Vulkan device '([^']*)' doesn't provide a queue family that supports presentation", "error", "GPU tanpa antrean presentasi:",
     "Tidak ada antrean yang bisa menampilkan ke layar."),
    (r"SDL_CreateWindow failed: ([^\n]*)", "error", "SDL gagal membuat jendela:",
     "Cek orientasi/surface; lihat log SDL (adb logcat -s SDL SDL/APP)."),
    (r"SDL_InitSubSystem\(SDL_INIT_VIDEO\) failed: ([^\n]*)", "error", "SDL video gagal diinisialisasi:",
     "SDLActivity tidak aktif atau libmain.so bukan dimuat lewat SDLActivity."),
    (r"VulkanPresenter: Failed to create a Vulkan surface", "error", "Gagal membuat surface Vulkan dari jendela Android",
     "SDL_PROP_WINDOW_ANDROID_WINDOW_POINTER kosong (risiko #3 di docs/ANDROID_PORT.md) atau jendela belum siap."),
    (r"VulkanPresenter: Failed to create a swapchain", "error", "Gagal membuat swapchain",
     "Format/ukuran surface tidak didukung driver; cek ukuran jendela dan VkSurfaceCapabilities."),
    (r"VulkanPresenter: Failed to (present the swapchain image|acquire the swapchain image)", "warn",
     "Swapchain usang/hilang", "Biasanya setelah app ke latar belakang (surface dihancurkan): presenter perlu membuat ulang swapchain (risiko #2)."),
    (r"Fatal signal (\d+) \((SIG\w+)\)", "error", "Crash native",
     "Lihat 'adb logcat -b crash' dan tombstone; frame teratas di bawah menunjukkan pelaku (libmain.so = game/SDK, libvulkan/driver = GPU)."),
    (r"Abort message: '([^']*)'", "error", "abort():", "Pesan abort berasal dari library native; cari teksnya di kode SDK/SDL."),
    (r"lowmemorykiller|Out of memory|OutOfMemoryError|LOW_MEMORY", "error", "Memori habis",
     "Turunkan texture_cache_mb / resolusi, tutup app lain; RAM HP mungkin tidak cukup untuk game ini."),
    (r"No space left on device|ENOSPC", "error", "Penyimpanan penuh", "Kosongkan ruang; cache shader/tekstur dan save butuh ruang."),
    (r"(EACCES|Permission denied)[^\n]*", "error", "Izin ditolak saat mengakses berkas",
     "Path di luar penyimpanan app tidak bisa ditulis. Semua path harus di bawah filesDir/Android/data/<pkg>."),
    (r"ANR in com\.ng2\.dawn", "error", "App tidak responsif (ANR)", "Thread utama terblokir; lihat /data/anr/traces atau laporan."),
    (r"uncaught exception on '([^']*)': ([^\n]*)", "error", "Exception Kotlin/Java tidak tertangani",
     "Detail dan stack ada di crash-*.txt di dalam laporan diagnosa."),
    (r"FAILED to (load|start)", "error", "Tahap startup gagal", "Lihat baris sebelumnya di log untuk penyebabnya."),
    (r"CRASH_NATIVE", "error", "Proses sebelumnya mati karena crash native", "Ambil logcat -b crash dan laporan; kemungkinan driver GPU atau runtime."),
    (r"prev exit .*SIGNALED", "warn", "Proses sebelumnya dimatikan sinyal",
     "Sering penghemat baterai vendor (Realme UI). Setelan > Baterai > izinkan Ninja's Dawn berjalan di latar belakang."),
]

_NATIVE_FRAME = re.compile(r"#\d+\s+pc\s+[0-9a-f]+\s+(\S+)(?:\s+\(([^)]*)\))?")
_STATS = re.compile(r"\{\"t\":\d+,.*?\}")
_PRE_FAIL = re.compile(r"\{\"id\":\"(\w+)\",\"level\":\"FAIL\",\"text\":\"((?:[^\"\\]|\\.)*)\"(?:,\"fix\":\"((?:[^\"\\]|\\.)*)\")?")


def parse_stats_lines(text: str) -> List[dict]:
    out = []
    for m in _STATS.finditer(text):
        try:
            out.append(json.loads(m.group(0)))
        except ValueError:
            pass
    return out


def analyze(text: str, target_fps: int = 30) -> Diagnosis:
    d = Diagnosis()
    stats = parse_stats_lines(text)
    frames_seen = any(s.get("frames", 0) > 0 for s in stats)

    for sid, pattern, label in STAGES:
        seen = frames_seen if sid == "frame" else bool(re.search(pattern, text))
        d.stages.append((sid, seen, label))

    # Test-mode logs have no SDK stages: don't blame a runtime that was never meant to run.
    test_host = "test host started" in text and "Loaded Vulkan runtime" not in text and "SDL video driver" not in text
    if test_host:
        # The stub host has no game, so there is no SDK pipeline and nothing ever polls input.
        d.stages = [(s, ok, l) for s, ok, l in d.stages if s in ("app", "native", "play", "activity", "frame")]

    seen_titles = set()
    for pattern, sev, title, fix in RULES:
        m = re.search(pattern, text)
        if not m:
            continue
        detail = ""
        if m.groups():
            detail = " / ".join(g for g in m.groups() if g)
        if title in seen_titles:
            continue
        seen_titles.add(title)
        d.findings.append(Finding(sev, title if not title.endswith(":") else title + " " + detail,
                                  detail if not title.endswith(":") else "", fix))

    # native crash backtrace: name the module of the top frames
    if re.search(r"Fatal signal|SIGSEGV|SIGABRT", text):
        frames = _NATIVE_FRAME.findall(text)[:5]
        if frames:
            d.findings.append(Finding("info", "Frame teratas crash:",
                                      "\n".join(f"{mod} {sym}".strip() for mod, sym in frames)))
            top = frames[0][0]
            if re.search(r"libvulkan|vulkan\.|libVK|libGLES|libEGL|PowerVR|mali|adreno", top, re.I):
                d.findings.append(Finding("error", "Crash di driver grafis", top,
                                          "Bug driver GPU; coba matikan fitur yang dipakai (resolusi 720p, cache lebih kecil) dan laporkan modelnya."))
            elif "libmain.so" in top or "libng2android.so" in top:
                d.findings.append(Finding("error", "Crash di kode game/SDK", top, "Butuh simbol (build dengan debug info) untuk menunjuk baris."))

    # No GPU chosen although the instance came up, and no explicit "doesn't support ..." warning:
    # the SDK normally logs why it rejects a device, so the log is probably incomplete.
    names = {s: ok for s, ok, _ in d.stages}
    explicit = any(f.title.startswith(("GPU tanpa", "Syarat ")) for f in d.findings)
    if names.get("vk_instance") and not names.get("vk_device") and not explicit and not any(f.title.startswith("Gagal") for f in d.findings):
        d.findings.append(Finding("error", "Tidak ada GPU yang lolos syarat SDK",
                                  "Instance Vulkan terbentuk, tetapi tidak ada baris 'Vulkan device properties and enabled features' "
                                  "dan tidak ada peringatan \"doesn't support ...\" di log ini.",
                                  "Log SDK mungkin tidak lengkap (naikkan detail log) atau GPU gagal di tahap lain. Jalankan `ng2dev.py cmd probe` dan lihat d0.f.*."))

    # preflight failures logged by the app
    for m in _PRE_FAIL.finditer(text):
        d.findings.append(Finding("error", "Preflight: " + m.group(2).replace('\\"', '"'), "", (m.group(3) or "").replace('\\"', '"')))

    # telemetry-based findings
    if stats:
        last = stats[-1]
        d.stats = {k: v for k, v in last.items() if isinstance(v, (int, float))}
        active = last.get("active_ms", 0)
        if not frames_seen and active >= 15000:
            d.findings.append(Finding("error", f"Tidak ada frame setelah {active // 1000}s",
                                      "Runtime hidup tetapi game tidak mencapai loop utamanya.",
                                      "Lihat tahap yang terlewat di atas; naikkan detail log: `ng2dev.py cmd 'loglevel debug'` lalu jalankan ulang."))
        elif frames_seen:
            since = last.get("since_ms", -1)
            if since > 3000:
                d.findings.append(Finding("error", f"Game berhenti menghasilkan frame ({since / 1000:.0f}s)", "",
                                          "Hang: ambil laporan dan logcat saat ini; cek thread yang macet."))
            fps = [s["fps"] for s in stats if s.get("frames", 0) > 0 and s.get("since_ms", 0) < 2000]
            if fps:
                avg = sum(fps) / len(fps)
                d.stats["avg_fps"] = avg
                if avg < target_fps * 0.66:
                    d.findings.append(Finding("warn", f"FPS rata-rata {avg:.1f} (target {target_fps})", "",
                                              "Terlalu lambat: turunkan resolusi/cache, atau perangkat memang kurang kuat."))
            if last.get("polls", 0) == 0 and not test_host:
                d.findings.append(Finding("warn", "Game belum membaca kontroler", "",
                                          "Normal selama video intro; jika menetap, driver input tidak terpasang."))
        if any(s.get("low_mem") for s in stats):
            d.findings.append(Finding("warn", "Android melaporkan memori rendah selama sesi", "", "Risiko dimatikan sistem (lowmemorykiller)."))
        hot = [s for s in stats if s.get("thermal") in ("SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN")]
        if hot:
            d.findings.append(Finding("warn", f"Perangkat terlalu panas ({hot[-1]['thermal']})", "", "Throttling menurunkan FPS; beri jeda/dinginkan."))

    order = {"error": 0, "warn": 1, "info": 2}
    d.findings.sort(key=lambda f: order[f.severity])
    return d


def render(d: Diagnosis, color: bool = True) -> str:
    def c(code: str, s: str) -> str:
        return f"\033[{code}m{s}\033[0m" if color else s

    out = [c("1", "Tahap startup")]
    blocked = False
    for sid, ok, label in d.stages:
        if ok:
            out.append("  " + c("32", "✔") + " " + label)
        else:
            out.append("  " + c("31" if not blocked else "90", "✖" if not blocked else "·") + " " + label +
                       ("" if blocked else c("31", "   <- berhenti di sini")))
            blocked = True
    miss = d.first_missing
    if miss and miss in NEXT_CAUSES and any(ok for _, ok, _ in d.stages):
        out.append("")
        out.append(c("33", "Kemungkinan penyebab: ") + NEXT_CAUSES[miss])
    out.append("")
    if not d.findings:
        out.append(c("32", "Tidak ada masalah yang dikenali di log ini."))
    for f in d.findings:
        tag = {"error": c("31", "[GALAT]"), "warn": c("33", "[PERINGATAN]"), "info": c("36", "[INFO]")}[f.severity]
        out.append(f"{tag} {f.title}")
        if f.detail:
            out += ["        " + line for line in f.detail.splitlines()]
        if f.fix:
            out.append("        -> " + f.fix)
    if d.stats:
        keys = ["fps", "avg_ms", "p95_ms", "frames", "polls", "pss_mb", "avail_mb"]
        out.append("")
        out.append("Sampel terakhir: " + "  ".join(f"{k}={d.stats[k]:.1f}" if isinstance(d.stats[k], float) else f"{k}={d.stats[k]}"
                                                    for k in keys if k in d.stats))
    return "\n".join(out)


# ------------------------------------------------------------------------------------------
# Live watch
# ------------------------------------------------------------------------------------------

BUTTON_BITS = [(0x1000, "A"), (0x2000, "B"), (0x4000, "X"), (0x8000, "Y"), (0x0100, "LB"), (0x0200, "RB"),
               (0x0010, "Start"), (0x0020, "Back"), (0x0040, "L3"), (0x0080, "R3"),
               (0x0001, "↑"), (0x0002, "↓"), (0x0004, "←"), (0x0008, "→")]


def pad_names(hexstr: str) -> str:
    try:
        v = int(hexstr, 16)
    except (TypeError, ValueError):
        return ""
    return "+".join(n for b, n in BUTTON_BITS if v & b) or "-"


def format_sample(s: dict, target: int, color: bool = True) -> str:
    def c(code, t):
        return f"\033[{code}m{t}\033[0m" if color else t
    frames = s.get("frames", 0)
    if frames == 0:
        body = c("33", f"menunggu frame… ({s.get('active_ms', 0) // 1000}s)")
    elif s.get("since_ms", 0) > 2000:
        body = c("31", f"TIDAK ADA FRAME {s['since_ms'] / 1000:.0f}s")
    else:
        fps = s.get("fps", 0)
        col = "32" if fps >= target * 0.93 else ("33" if fps >= target * 0.66 else "31")
        body = (c(col, f"fps {fps:5.1f}") + f"  frame {s.get('avg_ms', 0):5.1f}ms p95 {s.get('p95_ms', 0):5.1f} "
                f"max {s.get('max_ms', 0):6.1f}  hitch {s.get('hitches', 0)}")
    hot = s.get("thermal", "NONE")
    th = c("31", hot) if hot in ("SEVERE", "CRITICAL", "EMERGENCY") else (c("33", hot) if hot in ("MODERATE",) else hot)
    mem = f"RAM {s.get('pss_mb', 0)}MB (bebas {s.get('avail_mb', 0)}{' RENDAH' if s.get('low_mem') else ''})"
    return (f"{time.strftime('%H:%M:%S', time.localtime(s.get('t', 0) / 1000.0))}  {body} | disp {s.get('display_fps', 0):4.1f} | "
            f"{mem} | {th} {s.get('batt_c', 0):.1f}°C | polls {s.get('polls', 0)} | pad {pad_names(s.get('pad', '0'))}")


def summarize(samples: List[dict], target: int) -> str:
    live = [s for s in samples if s.get("frames", 0) > 0 and s.get("since_ms", 0) < 2000]
    if not samples:
        return "Tidak ada sampel diterima."
    lines = [f"{len(samples)} sampel"]
    if live:
        f = sorted(s["fps"] for s in live)
        below = sum(1 for s in live if s["fps"] < target * 0.93) * 100.0 / len(live)
        lines.append(f"fps min {f[0]:.1f}  median {f[len(f) // 2]:.1f}  maks {f[-1]:.1f}  rata-rata {sum(f) / len(f):.1f}; "
                     f"{below:.0f}% detik di bawah target {target}")
        lines.append(f"frame p95 terburuk {max(s['p95_ms'] for s in live):.1f} ms; tersendat {sum(s['hitches'] for s in live)} kali")
    stalls = [s for s in samples if s.get("frames", 0) > 0 and s.get("since_ms", 0) > 2000]
    if stalls:
        lines.append(f"macet: {len(stalls)} detik tanpa frame (terlama {max(s['since_ms'] for s in stalls) / 1000:.0f}s)")
    lines.append(f"RAM maks {max(s.get('pss_mb', 0) for s in samples)} MB; suhu baterai maks {max(s.get('batt_c', 0) for s in samples):.1f}°C")
    return "\n".join(lines)


def udp_samples(port: int, seconds: Optional[float]) -> Iterable[dict]:
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind(("0.0.0.0", port))
    sock.settimeout(1.0)
    end = time.time() + seconds if seconds else None
    while end is None or time.time() < end:
        try:
            data, _ = sock.recvfrom(4096)
        except socket.timeout:
            continue
        try:
            yield json.loads(data.decode("utf-8"))
        except ValueError:
            continue


def logcat_samples(adb: Adb, seconds: Optional[float]) -> Iterable[dict]:
    p = adb.popen("logcat", "-v", "raw", "-s", "ng2.stats:I")
    end = time.time() + seconds if seconds else None
    try:
        assert p.stdout is not None
        for line in p.stdout:
            m = _STATS.search(line)
            if m:
                try:
                    yield json.loads(m.group(0))
                except ValueError:
                    pass
            if end and time.time() >= end:
                break
    finally:
        p.terminate()


def local_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except OSError:
        return "IP-PC-ANDA"


# ------------------------------------------------------------------------------------------
# commands
# ------------------------------------------------------------------------------------------

def cmd_doctor(a: Adb, args) -> int:
    ok = True

    def line(good: Optional[bool], text: str):
        mark = {True: "✔", False: "✖", None: "·"}[good]
        print(f"  {mark} {text}")

    print("Perangkat")
    r = a.run("devices")
    devs = [l.split("\t")[0] for l in (r.stdout or "").splitlines()[1:] if l.strip().endswith("device")]
    line(bool(devs), f"adb: {', '.join(devs) if devs else 'tidak ada perangkat (aktifkan USB debugging / adb connect)'}")
    if not devs:
        return 1
    props = {k: a.shell(f"getprop {k}").strip() for k in
             ["ro.product.model", "ro.build.version.release", "ro.build.version.sdk", "ro.product.cpu.abi", "ro.hardware", "ro.board.platform"]}
    line(None, f"{props['ro.product.model']}  Android {props['ro.build.version.release']} (API {props['ro.build.version.sdk']})  "
               f"{props['ro.product.cpu.abi']}  {props['ro.hardware']}/{props['ro.board.platform']}")
    arm64 = props["ro.product.cpu.abi"] == "arm64-v8a"
    line(arm64, "arm64-v8a" if arm64 else "bukan arm64: game tidak bisa dijalankan")
    ok &= arm64
    vk = a.shell("pm list features | grep -i vulkan").strip().replace("\n", "; ")
    line(bool(vk), "fitur Vulkan: " + (vk or "tidak dilaporkan"))
    inst = f"package:{PKG}" in a.shell(f"pm list packages {PKG}")
    line(inst, "app terpasang" if inst else "app belum terpasang (ng2dev.py install)")
    if not inst:
        return 1
    r = send(a, "ping")
    line(r.ok, "perintah app: " + r.msg)
    if not r.delivered:
        print("    Jalankan app sekali (ng2dev.py run). Perintah dev hanya ada di build debug.")
        return 1
    r = send(a, "preflight")
    try:
        checks = json.loads(r.msg)
        print("Kesiapan")
        for c in checks:
            mark = {"PASS": "✔", "WARN": "⚠", "FAIL": "✖"}[c["level"]]
            print(f"  {mark} {c['text']}")
            if c["level"] != "PASS" and c.get("fix"):
                print(f"       -> {c['fix']}")
        ok &= not any(c["level"] == "FAIL" for c in checks)
    except ValueError:
        line(False, "preflight: " + r.msg)
    probe = send(a, "probe")
    print("GPU (probe langsung)")
    for kv in probe.msg.split(";"):
        if kv.startswith(("d0.name", "d0.api", "d0.f.independentBlend", "d0.f.fragmentStoresAndAtomics",
                          "d0.f.vertexPipelineStoresAndAtomics", "d0.f.geometryShader", "loaded", "error", "d0.swapchain", "d0.gfxcompute")):
            print("   ", kv)
    return 0 if ok else 1


def default_apk() -> Optional[Path]:
    root = Path(__file__).resolve().parent.parent
    apks = sorted(root.glob("app/build/outputs/apk/**/*.apk"), key=lambda p: p.stat().st_mtime, reverse=True)
    return apks[0] if apks else None


def cmd_install(a: Adb, args) -> int:
    apk = Path(args.apk) if args.apk else default_apk()
    if not apk or not apk.exists():
        print("APK tidak ditemukan. Bangun dulu (./gradlew assembleDebug) atau berikan path.", file=sys.stderr)
        return 1
    r = a.run("install", "-r", "-d", str(apk), timeout=300)
    print((r.stdout + r.stderr).strip())
    return 0 if "Success" in (r.stdout + r.stderr) else 1


def cmd_run(a: Adb, args) -> int:
    print(a.shell(f"am start -n {PKG}/.MainActivity").strip())
    return 0


def cmd_play(a: Adb, args) -> int:
    st = send(a, "state").msg
    real = "runtime=true" in st
    act = ".GameSdlActivity" if real else ".GameActivity"
    print(f"({'runtime nyata' if real else 'mode uji'}) -> {act}")
    print(a.shell(f"am start -n {PKG}/{act}").strip())
    return 0


def cmd_stop(a: Adb, args) -> int:
    a.shell(f"am force-stop {PKG}")
    print("dihentikan")
    return 0


def cmd_cmd(a: Adb, args) -> int:
    r = send(a, args.text)
    print(r.msg)
    return 0 if r.ok else 1


def cmd_pad(a: Adb, args) -> int:
    return cmd_cmd(a, argparse.Namespace(text="pad " + args.spec))


def cmd_fps(a: Adb, args) -> int:
    return cmd_cmd(a, argparse.Namespace(text="fps " + args.state))


def cmd_watch(a: Adb, args) -> int:
    use_color = sys.stdout.isatty()
    if args.udp:
        print(f"Mendengarkan UDP :{args.udp}. Di HP isi 'IP:port' = {local_ip()}:{args.udp} (setup > Diagnostik).")
        src = udp_samples(args.udp, args.seconds)
    else:
        print("Membaca logcat (ng2.stats). Mulai game di HP. Ctrl-C untuk berhenti.")
        src = logcat_samples(a, args.seconds)
    rows: List[dict] = []
    writer = None
    fh = None
    if args.csv:
        fh = open(args.csv, "w", newline="")
        writer = csv.writer(fh)
        writer.writerow(["t", "fps", "avg_ms", "p95_ms", "max_ms", "since_ms", "frames", "hitches", "polls",
                         "display_fps", "pss_mb", "avail_mb", "low_mem", "thermal", "batt_c", "pad"])
    try:
        for s in src:
            rows.append(s)
            print(format_sample(s, args.target, use_color), flush=True)
            if writer:
                writer.writerow([s.get(k) for k in ["t", "fps", "avg_ms", "p95_ms", "max_ms", "since_ms", "frames", "hitches",
                                                    "polls", "display_fps", "pss_mb", "avail_mb", "low_mem", "thermal", "batt_c", "pad"]])
                fh.flush()
    except KeyboardInterrupt:
        pass
    finally:
        if fh:
            fh.close()
    print("\n" + summarize(rows, args.target))
    return 0


def cmd_report(a: Adb, args) -> int:
    r = send(a, "report")
    if not r.ok:
        print("Gagal: " + r.msg)
        return 1
    remote = r.msg
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    p = a.run("pull", remote, str(out), timeout=120)
    print((p.stdout + p.stderr).strip())
    local = out / Path(remote).name
    if not local.exists():
        print("Berkas laporan tidak berhasil ditarik; salin manual dari " + remote)
        return 1
    print(f"\nLaporan: {local}\n")
    print(render(analyze(local.read_text(errors="replace")), sys.stdout.isatty()))
    return 0


LOG_TAGS = ["ng2:V", "ng2.cmd:I", "rex:V", "SDL:V", "SDL/APP:V", "AndroidRuntime:E", "DEBUG:V", "libc:F", "art:W", "vulkan:W"]


def cmd_logs(a: Adb, args) -> int:
    p = a.popen("logcat", "-v", "time", "-s", *LOG_TAGS)
    out = open(args.save, "w") if args.save else None
    end = time.time() + args.seconds if args.seconds else None
    pat = re.compile(r"(\bE/|\bF/|\bW/|Fatal|Failed|error|exception|signal|abort)", re.I) if args.errors else None
    try:
        assert p.stdout is not None
        for line in p.stdout:
            if pat is None or pat.search(line):
                print(line, end="", flush=True)
            if out:
                out.write(line)
            if end and time.time() >= end:
                break
    except KeyboardInterrupt:
        pass
    finally:
        p.terminate()
        if out:
            out.close()
    return 0


def cmd_analyze(a: Optional[Adb], args) -> int:
    text = sys.stdin.read() if args.file == "-" else Path(args.file).read_text(errors="replace")
    d = analyze(text, args.target)
    print(render(d, sys.stdout.isatty()))
    return 1 if d.has_errors else 0


def main(argv: Optional[List[str]] = None) -> int:
    ap = argparse.ArgumentParser(prog="ng2dev", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--adb", default=os.environ.get("ADB", "adb"), help="path ke adb (default: adb)")
    ap.add_argument("-s", "--serial", help="serial perangkat bila lebih dari satu")
    sub = ap.add_subparsers(dest="command", required=True)
    sub.add_parser("doctor")
    p = sub.add_parser("install"); p.add_argument("apk", nargs="?")
    sub.add_parser("run"); sub.add_parser("play"); sub.add_parser("stop")
    p = sub.add_parser("watch"); p.add_argument("--udp", type=int); p.add_argument("--csv"); p.add_argument("--seconds", type=float)
    p.add_argument("--target", type=int, default=30)
    p = sub.add_parser("pad"); p.add_argument("spec")
    p = sub.add_parser("fps"); p.add_argument("state", choices=["on", "off"])
    p = sub.add_parser("cmd"); p.add_argument("text")
    p = sub.add_parser("report"); p.add_argument("--out", default="ng2-reports")
    p = sub.add_parser("logs"); p.add_argument("--errors", action="store_true"); p.add_argument("--save"); p.add_argument("--seconds", type=float)
    p = sub.add_parser("analyze"); p.add_argument("file"); p.add_argument("--target", type=int, default=30)
    args = ap.parse_args(argv)
    adb = Adb(args.adb, args.serial)
    handlers = {"doctor": cmd_doctor, "install": cmd_install, "run": cmd_run, "play": cmd_play, "stop": cmd_stop,
                "watch": cmd_watch, "pad": cmd_pad, "fps": cmd_fps, "cmd": cmd_cmd, "report": cmd_report,
                "logs": cmd_logs, "analyze": cmd_analyze}
    try:
        return handlers[args.command](adb, args)
    except FileNotFoundError as e:
        print(f"Tidak bisa menjalankan '{adb.adb}': {e}. Pasang Android platform-tools dan pastikan adb ada di PATH.", file=sys.stderr)
        return 127


if __name__ == "__main__":
    sys.exit(main())
