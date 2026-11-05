package com.ng2.dawn

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.Choreographer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One second of everything worth knowing about how the game is doing. */
data class StatsSample(
    val timeMs: Long,
    val activeMs: Long,            // since a game screen became active
    val fps: Double,               // GAME frames/s (not display refresh)
    val avgMs: Double,
    val p95Ms: Double,
    val maxMs: Double,
    val sinceFrameMs: Double,      // -1 = no frame yet
    val frames: Long,
    val hitches: Int,
    val polls: Long,               // guest input polls
    val displayFps: Double,        // what the screen refreshes at (Choreographer)
    val pssMb: Long,
    val availMb: Long,
    val lowMemory: Boolean,
    val thermal: Int,              // PowerManager.THERMAL_STATUS_*
    val batteryTempC: Double,
    val padButtons: Int,           // what the guest currently sees pressed
)

object StatsFormat {
    /** Parses the "key=value" lines from nativeGetStats(). Missing keys become 0 / -1. Never throws. */
    fun parseNative(text: String): Map<String, Double> {
        val m = HashMap<String, Double>()
        for (line in text.lineSequence()) {
            val i = line.indexOf('=')
            if (i <= 0) continue
            val k = line.substring(0, i)
            val v = line.substring(i + 1).trim()
            m[k] = if (k == "pad") (v.toLongOrNull(16)?.toDouble() ?: 0.0) else (v.toDoubleOrNull() ?: 0.0)
        }
        return m
    }

    fun jsonEscape(s: String): String = buildString {
        for (c in s) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }

    private fun n(v: Double) = if (v.isNaN() || v.isInfinite()) "0" else "%.2f".format(java.util.Locale.US, v)

    /** One JSON object per line; this is the wire format of `ng2dev.py watch`. */
    fun toJson(s: StatsSample): String =
        "{\"t\":${s.timeMs},\"active_ms\":${s.activeMs},\"fps\":${n(s.fps)},\"avg_ms\":${n(s.avgMs)}," +
        "\"p95_ms\":${n(s.p95Ms)},\"max_ms\":${n(s.maxMs)},\"since_ms\":${n(s.sinceFrameMs)}," +
        "\"frames\":${s.frames},\"hitches\":${s.hitches},\"polls\":${s.polls},\"display_fps\":${n(s.displayFps)}," +
        "\"pss_mb\":${s.pssMb},\"avail_mb\":${s.availMb},\"low_mem\":${s.lowMemory}," +
        "\"thermal\":\"${thermalName(s.thermal)}\",\"batt_c\":${n(s.batteryTempC)},\"pad\":\"%04x\"}".format(s.padButtons)

    fun thermalName(t: Int): String = when (t) {
        0 -> "NONE"; 1 -> "LIGHT"; 2 -> "MODERATE"; 3 -> "SEVERE"; 4 -> "CRITICAL"
        5 -> "EMERGENCY"; 6 -> "SHUTDOWN"; else -> "UNKNOWN"
    }

    enum class Severity { OK, WARN, BAD, WAIT }

    data class Overlay(val text: String, val severity: Severity)

    /**
     * What the FPS overlay shows. Pure; unit-tested.
     * @param targetFps the setting (30 or 60); colours are relative to it.
     * @param realRuntime false in the test build, where "frames" are the stub's own.
     */
    fun overlay(s: StatsSample?, targetFps: Int, realRuntime: Boolean): Overlay {
        if (s == null) return Overlay("…", Severity.WAIT)
        if (s.frames == 0L) {
            val sec = s.activeMs / 1000
            return if (realRuntime && sec >= 15)
                Overlay("Belum ada frame setelah ${sec}s\n(lihat log / laporan diagnosa)", Severity.BAD)
            else Overlay("Menunggu frame pertama… ${sec}s", Severity.WAIT)
        }
        if (s.sinceFrameMs > 2000)
            return Overlay("TIDAK ADA FRAME ${(s.sinceFrameMs / 1000).toInt()}s\nterakhir ${s.frames} frame · RAM ${s.pssMb} MB",
                Severity.BAD)
        val sev = when {
            s.fps >= targetFps * 0.93 -> Severity.OK
            s.fps >= targetFps * 0.66 -> Severity.WARN
            else -> Severity.BAD
        }
        val temp = if (s.thermal >= 2) " · ${thermalName(s.thermal)}" else ""
        val mem = if (s.lowMemory) " · MEMORI RENDAH" else ""
        return Overlay("%.1f FPS · %.1f ms (p95 %.0f)\nRAM %d MB%s%s".format(java.util.Locale.US, s.fps, s.avgMs, s.p95Ms, s.pssMb, temp, mem), sev)
    }
}

/**
 * Samples once a second while a game screen is active, publishes the result to
 * (a) the FPS overlay, (b) logcat tag "ng2.stats" as one JSON line, (c) the app
 * log every 5 s, and (d) optionally a UDP socket on the PC.
 */
object Telemetry {
    private const val HISTORY = 300
    private val exec = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ng2-telemetry").apply { isDaemon = true } }
    private var task: ScheduledFuture<*>? = null
    private lateinit var app: Context
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(StatsSample) -> Unit>()
    private val history = ArrayDeque<StatsSample>()
    @Volatile private var latest: StatsSample? = null
    @Volatile private var activeSince = 0L
    private val displayFrames = AtomicLong()
    private var lastDisplayFrames = 0L
    private var lastDisplayAt = 0L
    @Volatile private var choreographerGen = 0     // bumped on every start/stop; stale callback chains end themselves
    private var tick = 0
    private var socket: DatagramSocket? = null
    private var socketTarget = ""
    private var udpErrorLogged = false

    fun init(context: Context) { app = context.applicationContext }

    fun latest(): StatsSample? = latest
    fun history(n: Int = HISTORY): List<StatsSample> = synchronized(history) { history.toList().takeLast(n) }
    fun addListener(l: (StatsSample) -> Unit) { listeners.add(l) }
    fun removeListener(l: (StatsSample) -> Unit) { listeners.remove(l) }

    /** Sampling runs only while a game screen is open (it is not free). */
    @Synchronized
    fun setActive(active: Boolean) {
        if (active && task == null) {
            activeSince = System.currentTimeMillis()
            if (NativeBridge.load()) NativeBridge.nativeResetStats()
            startChoreographer()
            task = exec.scheduleWithFixedDelay({ safeSample() }, 1000, 1000, TimeUnit.MILLISECONDS)
            DiagLog.i("telemetry", "sampling started")
        } else if (!active && task != null) {
            task?.cancel(false); task = null
            choreographerGen++                                  // ends the display-fps callback chain
            DiagLog.i("telemetry", "sampling stopped")
        }
    }

    private fun startChoreographer() {
        val gen = ++choreographerGen
        Handler(Looper.getMainLooper()).post {
            val cb = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (gen != choreographerGen) return          // a newer chain (or stop) superseded this one
                    displayFrames.incrementAndGet()
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }
            Choreographer.getInstance().postFrameCallback(cb)
        }
    }

    private fun safeSample() {
        try { sample() } catch (e: Throwable) { DiagLog.w("telemetry", "sample failed: $e") }
    }

    private fun sample() {
        val now = System.currentTimeMillis()
        val nat = if (NativeBridge.load()) StatsFormat.parseNative(NativeBridge.nativeGetStats()) else emptyMap()

        val df = displayFrames.get()
        val dt = if (lastDisplayAt == 0L) 1000L else (now - lastDisplayAt).coerceAtLeast(1)
        val displayFps = (df - lastDisplayFrames) * 1000.0 / dt
        lastDisplayFrames = df; lastDisplayAt = now

        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val pssKb = try { am.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid())).firstOrNull()?.totalPss ?: 0 } catch (_: Exception) { 0 }
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermal = try { pm.currentThermalStatus } catch (_: Throwable) { -1 }
        val batt = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val battC = (batt?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0

        val s = StatsSample(
            timeMs = now, activeMs = now - activeSince,
            fps = nat["fps"] ?: 0.0, avgMs = nat["avg_ms"] ?: 0.0, p95Ms = nat["p95_ms"] ?: 0.0,
            maxMs = nat["max_ms"] ?: 0.0, sinceFrameMs = nat["since_ms"] ?: -1.0,
            frames = (nat["frames"] ?: 0.0).toLong(), hitches = (nat["hitches"] ?: 0.0).toInt(),
            polls = (nat["polls"] ?: 0.0).toLong(), displayFps = displayFps,
            pssMb = pssKb / 1024L, availMb = mi.availMem shr 20, lowMemory = mi.lowMemory,
            thermal = thermal, batteryTempC = battC, padButtons = (nat["pad"] ?: 0.0).toInt(),
        )
        latest = s
        synchronized(history) { if (history.size >= HISTORY) history.removeFirst(); history.addLast(s) }
        val json = StatsFormat.toJson(s)
        Log.i("ng2.stats", json)
        if (++tick % 5 == 0) DiagLog.d("stats", json)
        sendUdp(json)
        for (l in listeners) try { l(s) } catch (_: Throwable) {}
    }

    private fun sendUdp(json: String) {
        val target = try { Settings(app).telemetryTarget } catch (_: Throwable) { "" }
        if (target.isEmpty()) { socket?.close(); socket = null; socketTarget = ""; return }
        try {
            if (socket == null || socketTarget != target) {
                socket?.close(); socket = DatagramSocket(); socketTarget = target; udpErrorLogged = false
            }
            val (host, port) = parseTarget(target) ?: run {
                if (!udpErrorLogged) { DiagLog.w("telemetry", "bad telemetry target '$target' (use host:port)"); udpErrorLogged = true }
                return
            }
            val data = json.toByteArray(Charsets.UTF_8)
            socket!!.send(DatagramPacket(data, data.size, InetAddress.getByName(host), port))
        } catch (e: Exception) {
            if (!udpErrorLogged) { DiagLog.w("telemetry", "UDP send to $target failed: $e"); udpErrorLogged = true }
        }
    }

    /** "192.168.1.5:47321" -> ("192.168.1.5", 47321); null when malformed. Pure; unit-tested. */
    fun parseTarget(t: String): Pair<String, Int>? {
        val i = t.lastIndexOf(':')
        if (i <= 0) return null
        val port = t.substring(i + 1).toIntOrNull() ?: return null
        val host = t.substring(0, i).trim()
        return if (host.isNotEmpty() && port in 1..65535) host to port else null
    }
}
