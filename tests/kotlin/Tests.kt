package com.ng2.dawn

// JVM tests for the pure logic. Run with tests/run_kotlin_tests.sh.
// Fixture below is REAL output of ng2_gpu_probe.cpp against Mesa lavapipe.

private var failures = 0
private var checks = 0
private fun check(name: String, cond: Boolean) { checks++; if (!cond) { failures++; println("FAIL: $name") } }
private fun near(a: Float, b: Float, eps: Float = 1e-4f) = kotlin.math.abs(a - b) <= eps

private const val LAVAPIPE = """loaded=1
instance=1.3
devices=1
d0.name=llvmpipe (LLVM 20.1.2, 256 bits)
d0.type=cpu
d0.api=1.4.318
d0.driver=104865800
d0.vendor=0x10005
d0.f.independentBlend=1
d0.f.fragmentStoresAndAtomics=1
d0.f.vertexPipelineStoresAndAtomics=1
d0.f.geometryShader=1
d0.f.fillModeNonSolid=1
d0.f.sparseBinding=1
d0.f.shaderStorageImageExtendedFormats=1
d0.swapchain=1
d0.gfxcompute=1
d0.localmb=3995
"""

private fun device(vararg missing: String, swapchain: Boolean = true, gfx: Boolean = true, api: String = "1.1.0") =
    VkDeviceInfo("TestGPU", "integrated", api,
        listOf("independentBlend", "fragmentStoresAndAtomics", "vertexPipelineStoresAndAtomics",
               "geometryShader", "fillModeNonSolid").associateWith { it !in missing },
        swapchain, gfx, 2048)

private fun info(vk: VulkanReport?, ram: Long = 8000, parts: List<Int> = listOf(0xd0b), free: Long = 50000,
                 abis: List<String> = listOf("arm64-v8a"), sdk: Int = 30) =
    DeviceInfo(sdk, abis, ram, free, parts, vk)

fun main() {
    // ---------------- PadMath.stick ----------------
    run {
        val (x, y) = PadMath.stick(0f, 0f, 100f)
        check("stick at centre is zero", x == 0f && y == 0f)
        val (rx, ry) = PadMath.stick(100f, 0f, 100f)
        check("full right = (1,0)", near(rx, 1f) && near(ry, 0f))
        val (ux, uy) = PadMath.stick(0f, -100f, 100f)          // finger ABOVE centre (screen y negative)
        check("finger up -> stick +Y (XInput up)", near(ux, 0f) && near(uy, 1f))
        val (dx, dy) = PadMath.stick(0f, 100f, 100f)
        check("finger down -> stick -Y", near(dy, -1f))
        val (cx, cy) = PadMath.stick(1000f, 0f, 100f)
        check("overshoot clamps to 1", near(cx, 1f) && near(cy, 0f))
        val (ex, ey) = PadMath.stick(11f, 0f, 100f)              // 0.11 < 0.12 dead zone
        check("inside dead zone is zero", ex == 0f && ey == 0f)
        val (fx, _) = PadMath.stick(13f, 0f, 100f)               // just outside: starts near 0, no jump
        check("just outside dead zone is small (no jump)", fx > 0f && fx < 0.05f)
        val (dgx, dgy) = PadMath.stick(70.7f, -70.7f, 100f)
        check("diagonal magnitude ~ (1.0 - dead) scaled", near(kotlin.math.hypot(dgx, dgy), 1f, 0.01f) && dgx > 0 && dgy > 0)
        val (nx, ny) = PadMath.stick(-50f, 0f, 0f)
        check("zero radius is safe", nx == 0f && ny == 0f)
        check("no negative zero (data class equality)", PadState(0, PadMath.stick(50f, 0f, 100f).second) == PadState(0, 0f))
    }
    // ---------------- PadMath.radial ----------------
    run {
        val (a, b) = PadMath.radial(0.05f, 0.05f)
        check("radial dead zone", a == 0f && b == 0f)
        val (c, d) = PadMath.radial(1f, 0f)
        check("radial full", near(c, 1f) && near(d, 0f))
        val (e, f) = PadMath.radial(0.9f, 0.9f)
        check("radial corner clamps to unit circle", kotlin.math.hypot(e, f) <= 1.0001f)
    }
    // ---------------- PadMath.dpad ----------------
    run {
        val r = 100f
        check("dpad centre = none", PadMath.dpad(0f, 0f, r) == 0)
        check("dpad up", PadMath.dpad(0f, -80f, r) == Btn.DPAD_UP)
        check("dpad down", PadMath.dpad(0f, 80f, r) == Btn.DPAD_DOWN)
        check("dpad left", PadMath.dpad(-80f, 0f, r) == Btn.DPAD_LEFT)
        check("dpad right", PadMath.dpad(80f, 0f, r) == Btn.DPAD_RIGHT)
        check("dpad up-right", PadMath.dpad(60f, -60f, r) == (Btn.DPAD_UP or Btn.DPAD_RIGHT))
        check("dpad down-left", PadMath.dpad(-60f, 60f, r) == (Btn.DPAD_DOWN or Btn.DPAD_LEFT))
        check("dpad never opposite pair", (0..359 step 5).all {
            val rad = Math.toRadians(it.toDouble())
            val m = PadMath.dpad((80 * Math.cos(rad)).toFloat(), (-80 * Math.sin(rad)).toFloat(), r)
            !(m and Btn.DPAD_UP != 0 && m and Btn.DPAD_DOWN != 0) && !(m and Btn.DPAD_LEFT != 0 && m and Btn.DPAD_RIGHT != 0)
        })
    }
    // ---------------- XInput masks (must match the desktop project) ----------------
    check("button masks", Btn.A == 0x1000 && Btn.B == 0x2000 && Btn.X == 0x4000 && Btn.Y == 0x8000 &&
        Btn.START == 0x10 && Btn.BACK == 0x20 && Btn.LB == 0x100 && Btn.RB == 0x200)

    // ---------------- GameStorage.parseXex ----------------
    run {
        fun be(b: ByteArray, o: Int, v: Long) { for (k in 0..3) b[o + k] = (v shr (24 - 8 * k)).toByte() }
        val h = ByteArray(0x200)
        "XEX2".forEachIndexed { i, ch -> h[i] = ch.code.toByte() }
        be(h, 0x14, 2); be(h, 0x18, 0x000103FF); be(h, 0x1C, 0x1234); be(h, 0x20, 0x00040006); be(h, 0x24, 0x100)
        be(h, 0x100 + 12, TITLE_ID_NG2); h[0x112] = 1; h[0x113] = 2
        val x = GameStorage.parseXex(h)
        check("xex title id", x != null && x.titleId == 0x544307D5L && x.isNinjaGaiden2)
        check("xex disc fields", x != null && x.discNumber == 1 && x.discCount == 2)
        check("not xex", GameStorage.parseXex(ByteArray(64)) == null)
        check("truncated xex", GameStorage.parseXex(h.copyOf(0x1C)) == null)
        val bad = h.copyOf(); be(bad, 0x14, 9999)
        check("absurd header count rejected", GameStorage.parseXex(bad) == null)
        val oob = h.copyOf(); be(oob, 0x24, 0x7FFFFFF0)
        check("out-of-range offset rejected", GameStorage.parseXex(oob) == null)
    }

    // ---------------- /proc/cpuinfo ----------------
    run {
        // The Realme C21's SoC (Helio G35) is 8x Cortex-A53, i.e. CPU part 0xd03 on every core.
        val c21 = (0 until 8).joinToString("\n") { "processor\t: $it\nCPU implementer\t: 0x41\nCPU part\t: 0xd03\n" }
        val parts = DeviceCheck.parseCpuParts(c21)
        check("cpuinfo: 8 cores parsed", parts.size == 8 && parts.all { it == 0xd03 })
        check("cpuinfo: garbage tolerated", DeviceCheck.parseCpuParts("CPU part : zz\nfoo").isEmpty())
    }

    // ---------------- Vulkan report parser (real probe output) ----------------
    val vk = VulkanReportParser.parse(LAVAPIPE)
    check("parser: loaded", vk.loaded && vk.devices.size == 1)
    check("parser: device fields", vk.devices[0].name.startsWith("llvmpipe") && vk.devices[0].type == "cpu" && vk.devices[0].api.startsWith("1."))
    check("parser: feature map", vk.devices[0].features["fragmentStoresAndAtomics"] == true && vk.devices[0].features.size >= 7)
    check("parser: swapchain/queues", vk.devices[0].swapchain && vk.devices[0].gfxCompute && vk.devices[0].localMb > 0)
    val none = VulkanReportParser.parse("loaded=0\nerror=libvulkan.so tidak ada")
    check("parser: not loaded", !none.loaded && none.error!!.contains("libvulkan") && none.devices.isEmpty())
    check("parser: empty input", !VulkanReportParser.parse("").loaded)

    // ---------------- DeviceVerdict ----------------
    run {
        val ok = DeviceVerdict.evaluate(info(vk))
        check("verdict: capable device has no BLOCK", DeviceVerdict.overall(ok) != Level.BLOCK)

        val noAtomics = DeviceVerdict.evaluate(info(VulkanReport(true, null, "1.1", listOf(device("fragmentStoresAndAtomics")))))
        check("verdict: missing fragmentStoresAndAtomics blocks", noAtomics.any { it.level == Level.BLOCK && "fragmentStoresAndAtomics" in it.text })

        val noGeom = DeviceVerdict.evaluate(info(VulkanReport(true, null, "1.1", listOf(device("geometryShader")))))
        check("verdict: missing geometryShader only warns (relaxable)", DeviceVerdict.overall(noGeom) == Level.WARN && noGeom.any { "geometryShader" in it.text })

        check("verdict: no swapchain blocks", DeviceVerdict.evaluate(info(VulkanReport(true, null, "1.1", listOf(device(swapchain = false))))).any { it.level == Level.BLOCK })
        check("verdict: no Vulkan blocks", DeviceVerdict.evaluate(info(none)).any { it.level == Level.BLOCK })
        check("verdict: null probe blocks", DeviceVerdict.evaluate(info(null)).any { it.level == Level.BLOCK })
        check("verdict: Vulkan 1.0 warns", DeviceVerdict.evaluate(info(VulkanReport(true, null, "1.0", listOf(device(api = "1.0.97"))))).any { it.level == Level.WARN && "1.0" in it.text })
        check("verdict: 32-bit ABI blocks", DeviceVerdict.evaluate(info(vk, abis = listOf("armeabi-v7a"))).any { it.level == Level.BLOCK })
        check("verdict: old Android blocks", DeviceVerdict.evaluate(info(vk, sdk = 28)).any { it.level == Level.BLOCK })
        check("verdict: low RAM warns", DeviceVerdict.evaluate(info(vk, ram = 3600)).any { it.level == Level.WARN && "RAM" in it.text })
        check("verdict: little-core-only CPU warns", DeviceVerdict.evaluate(info(vk, parts = List(8) { 0xd03 })).any { it.level == Level.WARN && "Cortex-A53" in it.text })
        check("verdict: big cores do not warn", DeviceVerdict.evaluate(info(vk, parts = listOf(0xd0b, 0xd05))).none { it.level == Level.WARN && "CPU" in it.text })
        check("verdict: low storage warns", DeviceVerdict.evaluate(info(vk, free = 4000)).any { it.level == Level.WARN })
        check("verdict: overall of empty is OK", DeviceVerdict.overall(emptyList()) == Level.OK)
    }


    // ---------------- ControlLayout ----------------
    run {
        val d = ControlLayout.defaults()
        check("layout: unique ids", d.map { it.id }.toSet().size == d.size)
        check("layout: all on screen", d.all { it.x in 0.02f..0.98f && it.y in 0.02f..0.98f })
        // every XInput control must be reachable from the overlay
        val masks = d.filter { it.type == ElementType.BUTTON }.fold(0) { a, e -> a or e.mask }
        val want = Btn.A or Btn.B or Btn.X or Btn.Y or Btn.LB or Btn.RB or Btn.START or Btn.BACK or Btn.LTHUMB or Btn.RTHUMB
        check("layout: all face/shoulder/system buttons present", masks == want)
        check("layout: both sticks, d-pad and both triggers present",
            d.count { it.type == ElementType.STICK } == 2 && d.any { it.type == ElementType.DPAD } &&
            d.count { it.type == ElementType.TRIGGER } == 2)
        // no two controls may visually overlap on common phone aspect ratios
        for ((w, h) in listOf(1280 to 720, 1600 to 720, 960 to 720, 2400 to 1080)) {
            for (i in d.indices) for (j in i + 1 until d.size) {
                val a = d[i]; val b = d[j]
                val dist = kotlin.math.hypot((a.x - b.x) * w, (a.y - b.y) * h)
                check("layout $w x $h: ${a.id} / ${b.id} do not overlap", dist >= (a.size + b.size) * h)
            }
        }
        // JSON round trip keeps edits
        val edited = ControlLayout.defaults().also { it.first { e -> e.id == "a" }.apply { x = 0.5f; y = 0.4f; size = 0.1f } }
        val back = ControlLayout.fromJson(ControlLayout.toJson(edited))
        val a = back.first { it.id == "a" }
        check("layout: round trip", near(a.x, 0.5f) && near(a.y, 0.4f) && near(a.size, 0.1f) && back.size == d.size)
        // never brick the app on bad data
        check("layout: null -> defaults", ControlLayout.fromJson(null).size == d.size)
        check("layout: garbage -> defaults", ControlLayout.fromJson("not json {{").size == d.size)
        check("layout: wrong shape -> defaults", ControlLayout.fromJson("{\"a\":1}").size == d.size)
        val hostile = """[{"id":"a","type":"BUTTON","x":99,"y":-5,"size":50,"label":"A","mask":0,"side":0},{"id":"nope","x":0.1,"y":0.1,"size":0.1}]"""
        val h = ControlLayout.fromJson(hostile)
        val ha = h.first { it.id == "a" }
        check("layout: out-of-range values are clamped", ha.x <= 0.98f && ha.y >= 0.02f && ha.size <= 0.30f)
        check("layout: unknown ids are ignored", h.size == d.size && h.none { it.id == "nope" })
        val partial = ControlLayout.fromJson("""[{"id":"a","x":0.3}]""")
        check("layout: partial entry falls back to defaults", partial.size == d.size)
    }

    // ---------------- PadMath sensitivity ----------------
    run {
        val (x1, _) = PadMath.stick(50f, 0f, 100f, sensitivity = 1f)
        val (x2, _) = PadMath.stick(50f, 0f, 100f, sensitivity = 2f)
        check("sensitivity 2x reaches full at half travel", near(x2, 1f) && x1 < 0.6f)
        val (x3, _) = PadMath.stick(50f, 0f, 100f, dead = 0f, sensitivity = 0.5f)
        check("sensitivity 0.5x needs more travel", near(x3, 0.25f, 0.02f))
        val (x4, _) = PadMath.stick(20f, 0f, 100f, dead = 0.3f)
        check("larger dead zone swallows small drags", x4 == 0f)
        val (x5, _) = PadMath.stick(50f, 0f, 100f, sensitivity = 99f)
        check("absurd sensitivity is clamped, not exploding", x5 <= 1f)
    }

    // ---------------- PadSpec (adb 'pad' command) ----------------
    run {
        val a = PadSpec.parse("A+X lx=0.5 ly=-1 lt=1 for=500")
        check("padspec: buttons", a.error == null && a.state.buttons == (Btn.A or Btn.X))
        check("padspec: axes", near(a.state.lx, 0.5f) && near(a.state.ly, -1f) && near(a.state.lt, 1f) && a.durationMs == 500L)
        check("padspec: release", PadSpec.parse("release").state == PadState() && PadSpec.parse("").state == PadState())
        check("padspec: case-insensitive, comma list", PadSpec.parse("a,start").state.buttons == (Btn.A or Btn.START))
        check("padspec: dpad names", PadSpec.parse("UP+RIGHT").state.buttons == (Btn.DPAD_UP or Btn.DPAD_RIGHT))
        check("padspec: values clamped", PadSpec.parse("lx=9 lt=-3").state.let { near(it.lx, 1f) && it.lt == 0f })
        check("padspec: duration capped at 60 s", PadSpec.parse("A for=999999").durationMs == 60_000L)
        check("padspec: unknown button is an error", PadSpec.parse("Z").error != null)
        check("padspec: unknown key is an error", PadSpec.parse("foo=1").error != null)
        check("padspec: non-numeric value is an error", PadSpec.parse("lx=abc").error != null)
        check("padspec: every name maps to a distinct bit", PadSpec.names().map { PadSpec.parse(it).state.buttons }.toSet().size == PadSpec.names().size)
    }

    // ---------------- StatsFormat ----------------
    run {
        val nat = StatsFormat.parseNative("fps=29.97\navg_ms=33.4\np95_ms=41.0\nmax_ms=90.1\nsince_ms=12.5\nframes=900\nhitches=2\npolls=1800\npad=1010\nbogus\n")
        check("stats: parseNative values", near(nat["fps"]!!.toFloat(), 29.97f) && nat["frames"] == 900.0 && nat["polls"] == 1800.0)
        check("stats: pad is hex", nat["pad"] == 0x1010.toDouble())
        check("stats: parseNative tolerates junk", StatsFormat.parseNative("garbage\n=\nx=y").getOrDefault("x", 0.0) == 0.0)

        val s = StatsSample(1000, 5000, 29.5, 33.9, 41.0, 90.0, 12.0, 900, 2, 1800, 59.9, 812, 400, false, 2, 36.5, Btn.A)
        val json = StatsFormat.toJson(s)
        val o = org.json.JSONObject(json)
        check("stats: json is valid and complete", o.getDouble("fps") == 29.5 && o.getLong("frames") == 900L && o.getString("thermal") == "MODERATE" &&
            o.getString("pad") == "1000" && o.getBoolean("low_mem") == false && o.getInt("pss_mb") == 812)
        check("stats: json survives NaN", org.json.JSONObject(StatsFormat.toJson(s.copy(fps = Double.NaN))).getDouble("fps") == 0.0)
        val nasty = "quote\" back\\ nl\n tab\t ctl\u0001 é"
        check("stats: jsonEscape round-trips through a real JSON parser",
            org.json.JSONObject("{\"m\":\"" + StatsFormat.jsonEscape(nasty) + "\"}").getString("m") == nasty)

        // overlay: colours are relative to the target frame rate
        fun ov(fps: Double, target: Int = 30, since: Double = 10.0, frames: Long = 500, active: Long = 60_000, real: Boolean = true) =
            StatsFormat.overlay(s.copy(fps = fps, sinceFrameMs = since, frames = frames, activeMs = active), target, real)
        check("overlay: null waits", StatsFormat.overlay(null, 30, true).severity == StatsFormat.Severity.WAIT)
        check("overlay: 30 fps @ target 30 is OK", ov(30.0).severity == StatsFormat.Severity.OK && "30.0 FPS" in ov(30.0).text)
        check("overlay: 28.5 fps @ target 30 is still OK", ov(28.5).severity == StatsFormat.Severity.OK)
        check("overlay: 22 fps @ target 30 warns", ov(22.0).severity == StatsFormat.Severity.WARN)
        check("overlay: 12 fps @ target 30 is bad", ov(12.0).severity == StatsFormat.Severity.BAD)
        check("overlay: 30 fps @ target 60 is bad", ov(30.0, 60).severity == StatsFormat.Severity.BAD)
        check("overlay: 58 fps @ target 60 is OK", ov(58.0, 60).severity == StatsFormat.Severity.OK)
        check("overlay: stall is flagged with its duration", ov(0.0, since = 5200.0).let { it.severity == StatsFormat.Severity.BAD && "TIDAK ADA FRAME 5s" in it.text })
        check("overlay: no frames yet, early = waiting", ov(0.0, frames = 0, active = 4000).severity == StatsFormat.Severity.WAIT)
        check("overlay: no frames after 20 s with the real runtime = bad", ov(0.0, frames = 0, active = 20_000).severity == StatsFormat.Severity.BAD)
        check("overlay: no frames after 20 s in the test build is NOT an error", ov(0.0, frames = 0, active = 20_000, real = false).severity == StatsFormat.Severity.WAIT)
        check("overlay: hot phone is shown", "SEVERE" in StatsFormat.overlay(s.copy(thermal = 3, sinceFrameMs = 10.0), 30, true).text)

        check("target: host:port", Telemetry.parseTarget("192.168.1.5:47321") == ("192.168.1.5" to 47321))
        check("target: hostname", Telemetry.parseTarget("my-pc.local:9000") == ("my-pc.local" to 9000))
        check("target: rejects bad", Telemetry.parseTarget("nocolon") == null && Telemetry.parseTarget(":80") == null &&
            Telemetry.parseTarget("h:0") == null && Telemetry.parseTarget("h:70000") == null && Telemetry.parseTarget("h:x") == null)
    }

    // ---------------- DiagLog / CrashReporter / ExitReasons ----------------
    run {
        val line = DiagLog.format(1_700_000_000_123L, 'I', "stage", "native library loaded")
        check("log line format", Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.123 I/stage: native library loaded""").matches(line))
        val crash = CrashReporter.render("main", IllegalStateException("boom"), 42L, listOf("l1", "l2"))
        check("crash report content", "IllegalStateException: boom" in crash && "thread: main" in crash && "l2" in crash && "at " in crash)
        check("exit: names", ExitReasons.name(3) == "LOW_MEMORY" && ExitReasons.name(5) == "CRASH_NATIVE" && ExitReasons.name(6) == "ANR" && ExitReasons.name(99) == "REASON_99")
        check("exit: low memory hint shows MB", ExitReasons.hint(3, 1_048_576L)!!.contains("1024 MB"))
        check("exit: native crash hint", ExitReasons.hint(5, 0)!!.contains("SIGSEGV"))
        check("exit: normal exits have no hint", ExitReasons.hint(1, 0) == null && ExitReasons.hint(10, 0) == null)
    }

    // ---------------- Preflight ----------------
    run {
        val good = PreflightInput(nativeLoaded = true, runtimeLinked = true, gameDirExists = true, gameDirWritable = true,
            hasXex = true, xex = XexInfo(TITLE_ID_NG2, 1, 1), fileCount = 400, totalBytes = 6_000_000_000, partFiles = 0,
            freeStorageMb = 20_000, cfgGamePath = "/g", expectedGamePath = "/g", logsWritable = true, deviceFindings = emptyList())
        fun lv(i: PreflightInput) = Preflight.evaluate(i)
        check("preflight: all good -> no FAIL, no WARN", lv(good).none { it.level != PfLevel.PASS })
        check("preflight: native missing fails", lv(good.copy(nativeLoaded = false)).any { it.id == "native" && it.level == PfLevel.FAIL })
        check("preflight: no xex fails with the real runtime", lv(good.copy(hasXex = false, xex = null)).any { it.id == "xex" && it.level == PfLevel.FAIL })
        check("preflight: no xex only warns in the test build", lv(good.copy(runtimeLinked = false, hasXex = false, xex = null)).none { it.level == PfLevel.FAIL })
        check("preflight: corrupt xex", lv(good.copy(xex = null)).any { it.id == "xex" && it.level == PfLevel.FAIL && "valid" in it.text })
        check("preflight: wrong game", lv(good.copy(xex = XexInfo(0x4D5307E6, 1, 1))).any { it.id == "xex" && it.level == PfLevel.FAIL && "4D5307E6" in it.text })
        check("preflight: interrupted copy", lv(good.copy(partFiles = 3)).any { it.id == "partial_copy" && it.level == PfLevel.FAIL })
        check("preflight: tiny folder warns", lv(good.copy(fileCount = 2)).any { it.id == "files" && it.level == PfLevel.WARN })
        check("preflight: storage thresholds", lv(good.copy(freeStorageMb = 500)).any { it.id == "storage" && it.level == PfLevel.FAIL } &&
            lv(good.copy(freeStorageMb = 3000)).any { it.id == "storage" && it.level == PfLevel.WARN } &&
            lv(good.copy(freeStorageMb = -1)).none { it.id == "storage" })
        check("preflight: cfg mismatch warns", lv(good.copy(cfgGamePath = "/other")).any { it.id == "cfg" && it.level == PfLevel.WARN })
        check("preflight: unwritable game dir fails", lv(good.copy(gameDirWritable = false)).any { it.id == "game_dir" && it.level == PfLevel.FAIL })
        val blocked = listOf(Finding(Level.BLOCK, "GPU tidak punya fitur"))
        check("preflight: device BLOCK fails only with the real runtime",
            lv(good.copy(deviceFindings = blocked)).any { it.id == "device" && it.level == PfLevel.FAIL } &&
            lv(good.copy(runtimeLinked = false, deviceFindings = blocked)).none { it.level == PfLevel.FAIL })
        check("preflight: every non-PASS check on the real runtime says what to do",
            lv(good.copy(hasXex = false, xex = null, partFiles = 1, freeStorageMb = 100)).filter { it.level == PfLevel.FAIL }.all { it.fix != null })
        check("preflight: json is valid", org.json.JSONArray(Preflight.toJson(lv(good.copy(hasXex = false, xex = null)))).length() > 3)
    }

    // ---------------- Remappable buttons ----------------
    run {
        val l = ControlLayout.defaults()
        l.first { it.id == "a" }.apply { mask = Btn.Y; label = ControlLayout.labelFor(Btn.Y) }
        val back = ControlLayout.fromJson(ControlLayout.toJson(l))
        val a = back.first { it.id == "a" }
        check("remap: persisted", a.mask == Btn.Y && a.label == "Y")
        check("remap: other buttons untouched", back.first { it.id == "b" }.mask == Btn.B)
        val bad = ControlLayout.fromJson("""[{"id":"a","x":0.5,"y":0.5,"size":0.1,"mask":12345}]""")
        check("remap: invalid mask ignored", bad.first { it.id == "a" }.mask == Btn.A)
        val stickMask = ControlLayout.fromJson("""[{"id":"ls","x":0.5,"y":0.5,"size":0.1,"mask":4096}]""")
        check("remap: sticks cannot be remapped", stickMask.first { it.id == "ls" }.mask == 0)
        check("remap: every choice has a label and is valid", ControlLayout.BUTTON_CHOICES.all { ControlLayout.isValidMask(it.second) && ControlLayout.labelFor(it.second) != "?" })
        check("remap: choices have unique masks", ControlLayout.BUTTON_CHOICES.map { it.second }.toSet().size == ControlLayout.BUTTON_CHOICES.size)
    }

    println("kotlin tests: $checks checks, $failures failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
