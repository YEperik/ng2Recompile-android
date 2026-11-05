package com.ng2.dawn

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque

/**
 * The app's own log. Two jobs: it survives the process (file in filesDir/logs,
 * rotated at 256 KB) so a crash can be analysed after the fact, and it mirrors
 * every line to logcat (tag "ng2") so `ng2dev.py logs` sees it live.
 *
 * Plain text, one line each:  2026-11-01T12:00:00.123 I/stage: message
 */
object DiagLog {
    private const val MAX_BYTES = 256 * 1024
    private const val MEM_LINES = 800
    private val lock = Any()
    private val mem = ArrayDeque<String>()
    @Volatile private var file: File? = null

    private val fmt: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    /** Pure, so it is unit-tested. */
    fun format(epochMs: Long, level: Char, tag: String, msg: String): String =
        "${fmt.format(Instant.ofEpochMilli(epochMs))} $level/$tag: $msg"

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        file = File(dir, "ng2-app.log")
    }

    fun logsDir(context: Context): File = File(context.filesDir, "logs").apply { mkdirs() }

    fun d(tag: String, msg: String) = write('D', tag, msg, Log.DEBUG)
    fun i(tag: String, msg: String) = write('I', tag, msg, Log.INFO)
    fun w(tag: String, msg: String) = write('W', tag, msg, Log.WARN)
    fun e(tag: String, msg: String, t: Throwable? = null) =
        write('E', tag, if (t == null) msg else msg + "\n" + Log.getStackTraceString(t), Log.ERROR)

    /** Newest [n] lines held in memory (also available after a rotation). */
    fun tail(n: Int): List<String> = synchronized(lock) { mem.toList().takeLast(n) }

    fun fileOrNull(): File? = file

    /** A pipeline marker the analyser keys on: "stage: <name>". */
    fun stage(name: String) = i("stage", name)

    private fun write(level: Char, tag: String, msg: String, prio: Int) {
        val line = format(System.currentTimeMillis(), level, tag, msg)
        Log.println(prio, "ng2", "[$tag] $msg")
        synchronized(lock) {
            if (mem.size >= MEM_LINES) mem.removeFirst()
            mem.addLast(line)
            val f = file ?: return
            try {
                if (f.length() > MAX_BYTES) {
                    val old = File(f.path + ".1")
                    old.delete()
                    f.renameTo(old)
                }
                f.appendText(line + "\n")
            } catch (_: Exception) { /* logging must never crash the app */ }
        }
    }
}
