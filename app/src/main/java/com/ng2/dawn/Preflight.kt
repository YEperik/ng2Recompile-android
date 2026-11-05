package com.ng2.dawn

import android.content.Context
import java.io.File

/**
 * "Why won't the game start?" as a checklist. Each check says what was found
 * and, when it is not fine, what to do. Pure evaluate() is unit-tested; gather()
 * reads the phone.
 */
enum class PfLevel { PASS, WARN, FAIL }

data class Check(val id: String, val level: PfLevel, val text: String, val fix: String? = null)

data class PreflightInput(
    val nativeLoaded: Boolean,
    val runtimeLinked: Boolean,
    val gameDirExists: Boolean,
    val gameDirWritable: Boolean,
    val hasXex: Boolean,
    val xex: XexInfo?,
    val fileCount: Int,
    val totalBytes: Long,
    val partFiles: Int,
    val freeStorageMb: Long,
    val cfgGamePath: String?,       // game_path= in ng2_settings.cfg, null if no file
    val expectedGamePath: String,
    val logsWritable: Boolean,
    val deviceFindings: List<Finding>,
)

object Preflight {

    fun evaluate(i: PreflightInput): List<Check> {
        val out = ArrayList<Check>()
        // Without the runtime the game is not needed; its absence only warns.
        val gameFail = if (i.runtimeLinked) PfLevel.FAIL else PfLevel.WARN
        val note = if (i.runtimeLinked) "" else " (tidak wajib di mode uji)"

        if (!i.nativeLoaded)
            out += Check("native", PfLevel.FAIL, "Library native tidak termuat.",
                "Build NDK gagal atau ABI salah: pastikan APK berisi lib/arm64-v8a/ (unzip -l app.apk | grep .so) dan perangkat arm64.")
        else out += Check("native", PfLevel.PASS, "Library native termuat.")

        out += if (i.runtimeLinked) Check("runtime", PfLevel.PASS, "Runtime ReXGlue tertaut.")
               else Check("runtime", PfLevel.WARN, "Mode uji: runtime ReXGlue belum tertaut, game tidak akan berjalan.",
                   "Bangun dengan -Png2.withRexglue=ON (docs/ANDROID_PORT.md, langkah 3).")

        if (!i.gameDirExists)
            out += Check("game_dir", gameFail, "Folder game belum ada$note.", "Pilih folder game di layar setup.")
        else if (!i.gameDirWritable)
            out += Check("game_dir", PfLevel.FAIL, "Folder game tidak bisa ditulis.", "Hapus data app (Setelan > Aplikasi > Ninja's Dawn > Hapus data) lalu salin ulang.")
        else out += Check("game_dir", PfLevel.PASS, "Folder game ada dan bisa ditulis.")

        if (i.partFiles > 0)
            out += Check("partial_copy", PfLevel.FAIL, "Salinan game belum selesai: ${i.partFiles} berkas .part tersisa.",
                "Pilih folder game lagi; penyalinan dilanjutkan dari berkas yang belum selesai.")

        if (!i.hasXex)
            out += Check("xex", gameFail, "default.xex tidak ditemukan$note.",
                "Folder yang dipilih harus langsung berisi default.xex (hasil ekstrak disc), bukan satu tingkat di atasnya.")
        else if (i.xex == null)
            out += Check("xex", gameFail, "default.xex ada tetapi bukan XEX yang valid$note.",
                "Berkas rusak atau salinan terpotong. Salin ulang dari sumber dan bandingkan ukurannya.")
        else if (!i.xex.isNinjaGaiden2)
            out += Check("xex", gameFail, "Title ID %08X bukan Ninja Gaiden II$note.".format(i.xex.titleId),
                "Rekompilasi ini hanya untuk Ninja Gaiden II (title ID 544307D5). Pakai disc yang benar.")
        else out += Check("xex", PfLevel.PASS, "default.xex valid: Ninja Gaiden II (disc ${i.xex.discNumber}/${i.xex.discCount}).")

        if (i.hasXex && i.fileCount < 10)
            out += Check("files", PfLevel.WARN, "Hanya ${i.fileCount} berkas di folder game (disc penuh biasanya ratusan).",
                "Mungkin hanya default.xex yang tersalin. Salin seluruh isi disc.")
        else if (i.hasXex)
            out += Check("files", PfLevel.PASS, "${i.fileCount} berkas, ${i.totalBytes shr 20} MB.")

        if (i.freeStorageMb in 0..2047)
            out += Check("storage", PfLevel.FAIL, "Ruang kosong hanya ${i.freeStorageMb} MB.", "Kosongkan penyimpanan; game menulis cache dan save.")
        else if (i.freeStorageMb in 2048..5119)
            out += Check("storage", PfLevel.WARN, "Ruang kosong ${i.freeStorageMb} MB, tipis untuk cache shader/tekstur.", "Sisakan beberapa GB.")
        else if (i.freeStorageMb >= 5120) out += Check("storage", PfLevel.PASS, "Ruang kosong ${i.freeStorageMb} MB.")

        if (i.cfgGamePath == null)
            out += Check("cfg", PfLevel.WARN, "ng2_settings.cfg belum ditulis.", "Ditulis otomatis saat game dimulai; jika tetap kosong, cek izin tulis filesDir.")
        else if (i.cfgGamePath != i.expectedGamePath)
            out += Check("cfg", PfLevel.WARN, "game_path di ng2_settings.cfg (${i.cfgGamePath}) berbeda dari folder game (${i.expectedGamePath}).", "Mulai game sekali lagi; berkas ditulis ulang.")
        else out += Check("cfg", PfLevel.PASS, "ng2_settings.cfg sesuai.")

        out += if (i.logsWritable) Check("logs", PfLevel.PASS, "Folder log bisa ditulis.")
               else Check("logs", PfLevel.WARN, "Folder log tidak bisa ditulis: diagnosa terbatas ke logcat.")

        for (f in i.deviceFindings) when (f.level) {
            Level.BLOCK -> out += Check("device", if (i.runtimeLinked) PfLevel.FAIL else PfLevel.WARN, f.text,
                "Perangkat ini tidak memenuhi syarat game; mode uji tetap bisa dipakai.")
            Level.WARN -> out += Check("device", PfLevel.WARN, f.text)
            Level.OK -> {}
        }
        return out
    }

    fun failures(c: List<Check>) = c.filter { it.level == PfLevel.FAIL }

    /** JSON array, for `ng2dev.py doctor` and the report. */
    fun toJson(c: List<Check>): String = c.joinToString(",", "[", "]") {
        "{\"id\":\"${it.id}\",\"level\":\"${it.level}\",\"text\":\"${StatsFormat.jsonEscape(it.text)}\"" +
            (it.fix?.let { f -> ",\"fix\":\"${StatsFormat.jsonEscape(f)}\"" } ?: "") + "}"
    }

    fun gather(context: Context): PreflightInput {
        val loaded = NativeBridge.load()
        val linked = loaded && NativeBridge.nativeRuntimeLinked()
        val dir = GameStorage.gameDir(context)
        val exists = dir.isDirectory
        var writable = false
        if (exists) writable = try { File(dir, ".ng2_write_test").apply { writeText("x"); delete() }; true } catch (_: Exception) { false }
        val xexFile = File(dir, "default.xex")
        var count = 0; var bytes = 0L; var part = 0
        if (exists) dir.walkTopDown().forEach { f ->
            if (f.isFile) { count++; bytes += f.length(); if (f.name.endsWith(".part")) part++ }
        }
        val cfg = File(context.filesDir, "ng2_settings.cfg")
        val cfgPath = if (cfg.isFile) cfg.readLines().firstOrNull { it.startsWith("game_path=") }?.removePrefix("game_path=") else null
        val logsOk = try { File(DiagLog.logsDir(context), ".w").apply { writeText("x"); delete() }; true } catch (_: Exception) { false }
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val free = try { android.os.StatFs(base.absolutePath).availableBytes shr 20 } catch (_: Exception) { -1L }
        return PreflightInput(
            nativeLoaded = loaded, runtimeLinked = linked, gameDirExists = exists, gameDirWritable = writable,
            hasXex = xexFile.isFile, xex = if (xexFile.isFile) GameStorage.readXex(xexFile) else null,
            fileCount = count, totalBytes = bytes, partFiles = part, freeStorageMb = free,
            cfgGamePath = cfgPath, expectedGamePath = dir.absolutePath, logsWritable = logsOk,
            deviceFindings = DeviceVerdict.evaluate(DeviceCheck.gather(context)),
        )
    }
}
