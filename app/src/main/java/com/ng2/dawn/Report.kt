package com.ng2.dawn

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One text file with everything needed to work out why the game does not run:
 * device, GPU probe, the preflight checklist, previous exit reasons, memory,
 * recent telemetry, the app log and this app's own logcat. Saved where
 * `adb pull` can reach it and shareable from the phone.
 */
object Report {

    fun dir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "reports").apply { mkdirs() }

    fun build(context: Context): File {
        val now = System.currentTimeMillis()
        val name = "ng2-report-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now)) + ".txt"
        val f = File(dir(context), name)
        f.writeText(render(context, now))
        DiagLog.i("report", "wrote ${f.absolutePath} (${f.length()} bytes)")
        // keep the folder small: newest 10
        dir(context).listFiles()?.sortedByDescending { it.lastModified() }?.drop(10)?.forEach { it.delete() }
        return f
    }

    private fun section(sb: StringBuilder, title: String, body: String) {
        sb.append("\n===== ").append(title).append(" =====\n").append(body.trimEnd()).append('\n')
    }

    fun render(context: Context, now: Long): String {
        val sb = StringBuilder()
        val s = Settings(context)
        sb.append("Ninja's Dawn Android - laporan diagnosa\n")
        sb.append("waktu: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now))).append('\n')

        val pkg = try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (_: Exception) { null }
        section(sb, "APP", "package=${context.packageName} version=${pkg?.versionName} debuggable=${DevCommands.isDebuggable(context)}\n" +
            "native_loaded=${NativeBridge.load()} runtime_linked=${NativeBridge.load() && NativeBridge.nativeRuntimeLinked()}")

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        section(sb, "PERANGKAT",
            "model=${Build.MANUFACTURER} ${Build.MODEL} device=${Build.DEVICE} hardware=${Build.HARDWARE} soc=${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "?"}\n" +
            "android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} abis=${Build.SUPPORTED_ABIS.joinToString()}\n" +
            "ram_total_mb=${mi.totalMem shr 20} ram_avail_mb=${mi.availMem shr 20} low_memory=${mi.lowMemory} threshold_mb=${mi.threshold shr 20}\n" +
            "cpu_parts=${DeviceCheck.cpuParts().joinToString { DeviceVerdict.cpuName(it) }}\n" +
            "fingerprint=${Build.FINGERPRINT}")

        val probe = if (NativeBridge.load()) NativeBridge.nativeProbeVulkan() else "native tidak termuat"
        section(sb, "VULKAN (probe langsung ke driver)", probe)

        val dev = DeviceVerdict.evaluate(DeviceCheck.gather(context))
        section(sb, "PENILAIAN PERANGKAT", dev.joinToString("\n") { "[${it.level}] ${it.text}" })

        val checks = try { Preflight.evaluate(Preflight.gather(context)) } catch (e: Throwable) { emptyList() }
        section(sb, "PREFLIGHT", checks.joinToString("\n") { "[${it.level}] ${it.id}: ${it.text}" + (it.fix?.let { f -> "\n        -> $f" } ?: "") })

        val exits = ExitReasons.collect(context, 8)
        section(sb, "PROSES SEBELUMNYA BERAKHIR KARENA", if (exits.isEmpty()) "(tidak ada catatan)" else exits.joinToString("\n") {
            "t=${it.timeMs} ${it.name} rss=${it.rssKb / 1024}MB pss=${it.pssKb / 1024}MB ${it.description}" +
                (ExitReasons.hint(it.reason, it.rssKb)?.let { h -> "\n        -> $h" } ?: "")
        })

        section(sb, "PENGATURAN", "fps=${s.fps} internal_720p=${s.internal720p} skip_intros=${s.skipIntros} texture_cache_mb=${s.textureCacheMb}\n" +
            "log_level=${s.logLevel} show_fps=${s.showFps} telemetry_target='${s.telemetryTarget}'\n" +
            "stick_deadzone=${s.stickDeadzone} stick_sensitivity=${s.stickSensitivity} haptics=${s.haptics} floating=${s.floatingStick}")

        val cfg = File(context.filesDir, "ng2_settings.cfg")
        section(sb, "ng2_settings.cfg", if (cfg.isFile) cfg.readText() else "(belum ada)")

        val hist = Telemetry.history(60)
        section(sb, "TELEMETRI (60 detik terakhir)", if (hist.isEmpty()) "(game belum aktif)" else hist.joinToString("\n") { StatsFormat.toJson(it) })

        val crashes = CrashReporter.list(context).take(3)
        section(sb, "CRASH KOTLIN", if (crashes.isEmpty()) "(tidak ada)" else crashes.joinToString("\n\n") { it.name + "\n" + it.readText().take(6000) })

        section(sb, "LOG APP (800 baris terakhir)", DiagLog.tail(800).joinToString("\n"))
        section(sb, "LOGCAT app ini (1500 baris terakhir)", readLogcat())
        return sb.toString()
    }

    /** An app can only read its own log lines, which is exactly what is needed. */
    private fun readLogcat(): String = try {
        val p = ProcessBuilder("logcat", "-d", "-t", "1500", "-v", "time").redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().use { it.readText() }
        p.destroy()
        text.takeLast(400_000)
    } catch (e: Exception) { "(tidak bisa membaca logcat: $e)" }

    fun share(activity: Activity, file: File) {
        val uri = FileProvider.getUriForFile(activity, activity.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Laporan diagnosa Ninja's Dawn")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(send, "Kirim laporan"))
    }
}
