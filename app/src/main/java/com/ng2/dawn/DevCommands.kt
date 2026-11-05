package com.ng2.dawn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Commands from the PC, for debuggable builds only (the receiver is not even
 * registered in a release build):
 *
 *   adb shell am broadcast -a com.ng2.dawn.CMD --es cmd "pad A+X for=300"
 *
 * Every command answers twice: as one JSON line on logcat tag "ng2.cmd" (what
 * tools/ng2dev.py reads) and as the broadcast result data (what `am` prints).
 */
class DevCommands(private val app: Context) : BroadcastReceiver() {

    private val main = Handler(Looper.getMainLooper())
    private var releaseTask: Runnable? = null

    override fun onReceive(context: Context, intent: Intent) {
        val line = intent.getStringExtra("cmd") ?: ""
        // `report` and `preflight` read many files and the log: far too slow for the main thread
        // (a broadcast receiver that blocks it is an ANR). goAsync() lets us answer from a worker.
        val pending = goAsync()
        Thread({
            val reply = try { run(line) } catch (e: Throwable) {
                DiagLog.e("cmd", "command '$line' failed", e); Reply(false, "exception: $e")
            }
            val json = "{\"cmd\":\"${StatsFormat.jsonEscape(line)}\",\"ok\":${reply.ok}," +
                "\"msg\":\"${StatsFormat.jsonEscape(reply.msg)}\"}"
            Log.i("ng2.cmd", json)
            DiagLog.d("cmd", json)
            try {
                pending?.setResultCode(if (reply.ok) 1 else 0)
                pending?.setResultData(reply.msg)
            } finally {
                pending?.finish()
            }
        }, "ng2-cmd").start()
    }

    data class Reply(val ok: Boolean, val msg: String)

    fun run(line: String): Reply {
        val parts = line.trim().split(Regex("\\s+"), limit = 2)
        val cmd = parts.getOrNull(0)?.lowercase() ?: ""
        val arg = parts.getOrNull(1) ?: ""
        val settings = Settings(app)
        return when (cmd) {
            "ping" -> Reply(true, "pong v${versionName()} sdk=${Build.VERSION.SDK_INT} native=${NativeBridge.load()}")
            "pad" -> pad(arg)
            "fps" -> when (arg.lowercase()) {
                "on" -> { settings.showFps = true; Reply(true, "fps overlay on") }
                "off" -> { settings.showFps = false; Reply(true, "fps overlay off") }
                else -> Reply(false, "pakai: fps on|off")
            }
            "stats" -> Telemetry.latest()?.let { Reply(true, StatsFormat.toJson(it)) } ?: Reply(false, "belum ada sampel (game belum aktif)")
            "preflight" -> {
                val checks = Preflight.evaluate(Preflight.gather(app))
                Reply(checks.none { it.level == PfLevel.FAIL }, Preflight.toJson(checks))
            }
            "probe" -> Reply(true, if (NativeBridge.load()) NativeBridge.nativeProbeVulkan().replace("\n", ";") else "native tidak termuat")
            "report" -> { val f = Report.build(app); Reply(true, f.absolutePath) }
            "loglevel" -> if (arg in setOf("info", "debug", "trace")) { settings.logLevel = arg; Reply(true, "log level $arg (berlaku saat game dimulai ulang)") }
                          else Reply(false, "pakai: loglevel info|debug|trace")
            "state" -> Reply(true, "game_dir=${GameStorage.gameDir(app)} has_game=${GameStorage.hasGame(GameStorage.gameDir(app))} " +
                "runtime=${NativeBridge.load() && NativeBridge.nativeRuntimeLinked()} fps_overlay=${settings.showFps}")
            else -> Reply(false, "perintah tidak dikenal: '$cmd'. Ada: ping pad fps stats preflight probe report loglevel state")
        }
    }

    private fun pad(arg: String): Reply {
        val p = PadSpec.parse(arg)
        if (p.error != null) return Reply(false, p.error)
        if (!NativeBridge.load()) return Reply(false, "native tidak termuat")
        releaseTask?.let { main.removeCallbacks(it) }
        send(p.state)
        if (p.durationMs > 0) {
            val r = Runnable { send(PadState()) }
            releaseTask = r
            main.postDelayed(r, p.durationMs)
        }
        return Reply(true, "pad buttons=%04x lx=%.2f ly=%.2f rx=%.2f ry=%.2f lt=%.2f rt=%.2f for=%dms".format(
            p.state.buttons, p.state.lx, p.state.ly, p.state.rx, p.state.ry, p.state.lt, p.state.rt, p.durationMs))
    }

    private fun send(s: PadState) =
        NativeBridge.nativeSetPad(NativeBridge.SRC_INJECT, s.buttons, s.lx, s.ly, s.rx, s.ry, s.lt, s.rt)

    private fun versionName(): String = try {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    companion object {
        const val ACTION = "com.ng2.dawn.CMD"

        fun isDebuggable(context: Context) =
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

        fun register(context: Context) {
            if (!isDebuggable(context)) return
            val r = DevCommands(context.applicationContext)
            val f = IntentFilter(ACTION)
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(r, f, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(r, f)
            DiagLog.i("cmd", "dev commands registered (debuggable build)")
        }
    }
}
