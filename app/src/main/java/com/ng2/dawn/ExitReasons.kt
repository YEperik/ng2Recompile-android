package com.ng2.dawn

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/**
 * Why did the previous run of this app end? Android 11 (API 30) keeps that
 * answer: a native crash, the low-memory killer, an ANR, ... A game that "just
 * disappears" is almost always one of these, and this is the only place the
 * distinction is visible to the app itself.
 */
object ExitReasons {
    data class Record(
        val timeMs: Long, val reason: Int, val name: String, val description: String,
        val importance: Int, val rssKb: Long, val pssKb: Long,
    )

    /** Numeric values of ApplicationExitInfo.REASON_*. Pure; unit-tested. */
    fun name(reason: Int): String = when (reason) {
        0 -> "UNKNOWN"; 1 -> "EXIT_SELF"; 2 -> "SIGNALED"; 3 -> "LOW_MEMORY"; 4 -> "CRASH"
        5 -> "CRASH_NATIVE"; 6 -> "ANR"; 7 -> "INITIALIZATION_FAILURE"; 8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"; 10 -> "USER_REQUESTED"; 11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"; 13 -> "OTHER"; else -> "REASON_$reason"
    }

    /** What it usually means for this app, or null when it is a normal exit. */
    fun hint(reason: Int, rssKb: Long): String? = when (reason) {
        3 -> "Dimatikan sistem karena memori habis (RSS terakhir ${rssKb / 1024} MB). Kurangi texture_cache_mb / resolusi, tutup app lain."
        5 -> "Crash native (SIGSEGV/abort) di library game atau driver. Lihat 'adb logcat -s DEBUG libc' dan tombstone; sertakan laporan diagnosa."
        4 -> "Exception Kotlin/Java tidak tertangani. Lihat berkas crash-*.txt di laporan."
        6 -> "App tidak responsif (ANR): thread utama terblokir."
        9 -> "Memakai sumber daya berlebihan (CPU/baterai) sehingga dihentikan sistem."
        7 -> "Proses gagal dimulai (initialization failure); biasanya library native tidak termuat."
        2 -> "Dihentikan oleh sinyal (mis. SIGKILL dari penghemat baterai/vendor). Cek pengaturan baterai Realme UI."
        else -> null
    }

    fun collect(context: Context, max: Int = 8): List<Record> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getHistoricalProcessExitReasons(context.packageName, 0, max).map {
                Record(it.timestamp, it.reason, name(it.reason), it.description ?: "",
                    it.importance, it.rss, it.pss)
            }
        } catch (_: Exception) { emptyList() }
    }

    fun logRecent(context: Context) {
        val recent = collect(context, 5)
        if (recent.isEmpty()) { DiagLog.i("exit", "no previous exit records"); return }
        for (r in recent) {
            val h = hint(r.reason, r.rssKb)
            DiagLog.i("exit", "prev exit t=${r.timeMs} ${r.name} rss=${r.rssKb / 1024}MB pss=${r.pssKb / 1024}MB" +
                (if (r.description.isNotEmpty()) " desc=${r.description}" else "") + (if (h != null) " -> $h" else ""))
        }
    }
}
