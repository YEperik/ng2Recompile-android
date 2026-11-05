package com.ng2.dawn

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Writes crash-<time>.txt into the logs dir for any uncaught Kotlin/Java
 * exception, then hands over to the previous handler (so the system still shows
 * its usual behaviour). NATIVE crashes cannot be caught here; ExitReasons
 * reports them on the next launch.
 */
object CrashReporter {
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val now = System.currentTimeMillis()
                val f = File(DiagLog.logsDir(app), "crash-$now.txt")
                f.writeText(render(thread.name, e, now, DiagLog.tail(80)))
                DiagLog.e("crash", "uncaught exception on '${thread.name}': ${e.javaClass.name}: ${e.message}")
            } catch (_: Throwable) {}
            previous?.uncaughtException(thread, e)
        }
    }

    /** Pure; unit-tested. */
    fun render(thread: String, e: Throwable, epochMs: Long, tail: List<String>): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        return buildString {
            append("ng2 crash report\n")
            append("time_ms: ").append(epochMs).append('\n')
            append("thread: ").append(thread).append('\n')
            append("exception: ").append(e.javaClass.name).append(": ").append(e.message).append("\n\n")
            append(sw).append("\n--- last log lines ---\n")
            tail.forEach { append(it).append('\n') }
        }
    }

    fun list(context: Context): List<File> =
        (DiagLog.logsDir(context).listFiles { f -> f.name.startsWith("crash-") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
}
