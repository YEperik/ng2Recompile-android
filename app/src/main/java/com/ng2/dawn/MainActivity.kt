package com.ng2.dawn

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.app.AlertDialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.Toast
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Setup screen - the Android counterpart of the desktop setup window: pick the
 * game (folder or ISO), set a few options, press Play. No game data is ever
 * bundled; it is copied from the user's own files.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var settings: Settings
    private lateinit var banner: TextView
    private lateinit var gameStatus: TextView
    private lateinit var notice: TextView
    private lateinit var deviceText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var btnFolder: Button
    private lateinit var btnIso: Button
    private lateinit var btnCancel: Button
    private lateinit var btnPlay: Button
    private lateinit var preflightText: TextView
    private lateinit var btnLogLevel: Button
    private val cancel = AtomicBoolean(false)
    private var busy = false

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        it?.let(::importFolder)
    }
    private val pickIso = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(::importIso)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        setContentView(buildUi())
        refresh()
        runDeviceCheck()
        runPreflight()
    }

    override fun onResume() { super.onResume(); refresh() }

    // ---- UI ------------------------------------------------------------------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(t: String, size: Float = 14f, color: Int = 0xFFECECEC.toInt()) =
        TextView(this).apply { text = t; textSize = size; setTextColor(color) }

    private fun card(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(0xFF181A21.toInt()); setPadding(dp(16), dp(14), dp(16), dp(14))
        views.forEach { addView(it) }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) }
    }

    private fun toggle(t: String, on: Boolean, change: (Boolean) -> Unit) = Switch(this).apply {
        text = t; isChecked = on; setTextColor(0xFFECECEC.toInt())
        setOnCheckedChangeListener { _, v -> change(v) }
    }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        col.addView(label("Ninja's Dawn", 26f))
        col.addView(label("Ninja Gaiden II – rekompilasi untuk Android", 13f, 0xFF9AA0AC.toInt()))

        banner = label("", 12f, 0xFFFFC857.toInt()).apply { setPadding(0, dp(10), 0, 0) }
        col.addView(banner)

        gameStatus = label("")
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; visibility = View.GONE
        }
        progressText = label("", 12f, 0xFF9AA0AC.toInt()).apply { visibility = View.GONE }
        btnFolder = Button(this).apply {
            text = "Pilih folder game (berisi default.xex)"
            setOnClickListener { if (!busy) pickFolder.launch(null) }
        }
        btnIso = Button(this).apply {
            text = "Pilih file ISO"
            setOnClickListener { if (!busy) pickIso.launch(arrayOf("*/*")) }
        }
        btnCancel = Button(this).apply {
            text = "Batalkan"; visibility = View.GONE
            setOnClickListener { cancel.set(true); if (NativeBridge.load()) NativeBridge.nativeExtractCancel() }
        }
        deviceText = label("Memeriksa…", 13f)
        val btnDevice = Button(this).apply { text = "Periksa ulang"; setOnClickListener { runDeviceCheck() } }
        col.addView(card(label("Cek perangkat", 16f), deviceText, btnDevice))

        notice = label("", 13f, 0xFFFF8A80.toInt())
        val safHint = label(
            "Android 11+: folder utama penyimpanan, folder Download itu sendiri, dan Android/data tidak bisa dipilih. " +
            "Pindahkan game ke subfolder (mis. Download/NG2) lalu pilih subfolder itu.",
            12f, 0xFF9AA0AC.toInt()).apply { setPadding(0, dp(6), 0, dp(6)) }
        col.addView(card(label("Game", 16f), gameStatus, notice, btnFolder, btnIso, safHint, progress, progressText, btnCancel))

        preflightText = label("Memeriksa…", 13f)
        val btnPre = Button(this).apply { text = "Periksa kesiapan"; setOnClickListener { runPreflight() } }
        val btnReport = Button(this).apply { text = "Buat laporan diagnosa"; setOnClickListener { makeReport() } }
        col.addView(card(label("Kesiapan & diagnosa", 16f), preflightText, btnPre, btnReport,
            label("Laporan berisi perangkat, GPU, log, dan alasan proses sebelumnya berhenti. " +
                "Dari PC: adb pull /sdcard/Android/data/com.ng2.dawn/files/reports/", 12f, 0xFF9AA0AC.toInt())
                .apply { setPadding(0, dp(6), 0, 0) }))

        col.addView(card(
            label("Kinerja", 16f),
            toggle("60 fps (default 30; butuh perangkat kuat)", settings.fps >= 60) { settings.fps = if (it) 60 else 30 },
            toggle("Resolusi internal 720p", settings.internal720p) { settings.internal720p = it },
            toggle("Lewati video intro", settings.skipIntros) { settings.skipIntros = it },
        ))

        btnLogLevel = Button(this).apply {
            text = "Detail log game: ${settings.logLevel}"
            setOnClickListener {
                val order = listOf("info", "debug", "trace")
                settings.logLevel = order[(order.indexOf(settings.logLevel) + 1) % order.size]
                text = "Detail log game: ${settings.logLevel}"
            }
        }
        val target = EditText(this).apply {
            hint = "IP:port PC untuk telemetri (mis. 192.168.1.5:47321)"
            setText(settings.telemetryTarget); setTextColor(0xFFECECEC.toInt()); setHintTextColor(0xFF6A707C.toInt())
            textSize = 13f; setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { settings.telemetryTarget = s?.toString() ?: "" }
            })
        }
        col.addView(card(
            label("Diagnostik", 16f),
            toggle("Tampilkan FPS di dalam game", settings.showFps) { settings.showFps = it },
            btnLogLevel,
            target,
            label("Telemetri selalu dikirim ke logcat (tag ng2.stats). Isi IP:port untuk juga mengirimnya " +
                "lewat Wi-Fi ke PC (python tools/ng2dev.py watch --udp PORT).", 12f, 0xFF9AA0AC.toInt())
                .apply { setPadding(0, dp(6), 0, 0) },
        ))
        col.addView(card(
            label("Kontrol", 16f),
            toggle("Getar saat tombol ditekan", settings.haptics) { settings.haptics = it },
            toggle("Stik kiri melayang", settings.floatingStick) { settings.floatingStick = it },
            toggle("Sembunyikan kontrol sentuh saat gamepad dipakai", settings.hideWithGamepad) { settings.hideWithGamepad = it },
            label("Posisi dan ukuran tombol diatur dari menu di dalam game (☰ → Atur posisi kontrol).",
                12f, 0xFF9AA0AC.toInt()).apply { setPadding(0, dp(8), 0, 0) },
        ))

        btnPlay = Button(this).apply {
            text = "Main"; textSize = 18f
            setOnClickListener { play() }
        }
        col.addView(btnPlay, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(16) })

        return ScrollView(this).apply {
            setBackgroundColor(0xFF0E0F13.toInt()); addView(col)
        }
    }

    // ---- device check ----------------------------------------------------------
    private fun runDeviceCheck() {
        deviceText.text = "Memeriksa…"
        lifecycleScope.launch {
            val findings = withContext(Dispatchers.Default) {
                DeviceVerdict.evaluate(DeviceCheck.gather(this@MainActivity))
            }
            val overall = DeviceVerdict.overall(findings)
            val head = when (overall) {
                Level.BLOCK -> "✖ Perangkat ini belum memenuhi syarat untuk menjalankan game. Mode uji kontrol tetap bisa dipakai."
                Level.WARN -> "⚠ Mungkin tidak cukup kuat. Hasil di bawah perlu dibaca."
                Level.OK -> "✔ Memenuhi syarat dasar."
            }
            val lines = findings.joinToString("\n") {
                (when (it.level) { Level.OK -> "✔ "; Level.WARN -> "⚠ "; Level.BLOCK -> "✖ " }) + it.text
            }
            deviceText.text = head + "\n\n" + lines
            deviceText.setTextColor(when (overall) {
                Level.BLOCK -> 0xFFFF8A80.toInt(); Level.WARN -> 0xFFFFC857.toInt(); Level.OK -> 0xFF7BD88F.toInt()
            })
        }
    }

    // ---- preflight / report / play ------------------------------------------------
    private fun renderChecks(checks: List<Check>): String =
        checks.joinToString("\n") {
            (when (it.level) { PfLevel.PASS -> "✔ "; PfLevel.WARN -> "⚠ "; PfLevel.FAIL -> "✖ " }) + it.text +
                (it.fix?.let { f -> "\n     → $f" } ?: "")
        }

    private fun runPreflight() {
        preflightText.text = "Memeriksa…"
        lifecycleScope.launch {
            val checks = withContext(Dispatchers.Default) { Preflight.evaluate(Preflight.gather(this@MainActivity)) }
            val fails = Preflight.failures(checks).size
            val warns = checks.count { it.level == PfLevel.WARN }
            preflightText.text = (if (fails > 0) "✖ $fails masalah menghalangi game.\n\n" else if (warns > 0) "⚠ Siap dengan $warns catatan.\n\n" else "✔ Semua siap.\n\n") + renderChecks(checks)
            preflightText.setTextColor(if (fails > 0) 0xFFFF8A80.toInt() else if (warns > 0) 0xFFFFC857.toInt() else 0xFF7BD88F.toInt())
            DiagLog.i("preflight", Preflight.toJson(checks))
        }
    }

    private fun makeReport() {
        Toast.makeText(this, "Membuat laporan…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val f = withContext(Dispatchers.IO) { Report.build(this@MainActivity) }
                Report.share(this@MainActivity, f)
            } catch (e: Throwable) {
                DiagLog.e("report", "failed", e)
                Toast.makeText(this@MainActivity, "Gagal membuat laporan: $e", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Runs the checklist first; with the real runtime a failing check asks before starting. */
    private fun play() {
        lifecycleScope.launch {
            val real = NativeBridge.load() && NativeBridge.nativeRuntimeLinked()
            val checks = withContext(Dispatchers.Default) { Preflight.evaluate(Preflight.gather(this@MainActivity)) }
            val fails = Preflight.failures(checks)
            DiagLog.stage("play pressed (real=$real, failing checks=${fails.size})")
            if (real && fails.isNotEmpty()) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Ada masalah")
                    .setMessage(renderChecks(fails) + "\n\nGame kemungkinan tidak akan berjalan.")
                    .setPositiveButton("Tetap main") { _, _ -> launchGame(real) }
                    .setNegativeButton("Batal", null)
                    .show()
            } else launchGame(real)
        }
    }

    private fun launchGame(real: Boolean) =
        startActivity(Intent(this, if (real) GameSdlActivity::class.java else GameActivity::class.java))

    // ---- state ---------------------------------------------------------------
    private fun refresh() {
        val loaded = NativeBridge.load()
        banner.text = when {
            !loaded -> "Library native tidak termuat – periksa build NDK dan ABI (harus arm64-v8a)."
            !NativeBridge.nativeRuntimeLinked() ->
                "Mode uji: runtime ReXGlue belum ditautkan. Kontrol dan alur setup bisa dicoba, " +
                "tetapi game belum berjalan. Lihat docs/ANDROID_PORT.md."
            else -> ""
        }
        banner.visibility = if (banner.text.isEmpty()) View.GONE else View.VISIBLE

        val dir = GameStorage.gameDir(this)
        if (GameStorage.hasGame(dir)) {
            val xex = GameStorage.readXex(java.io.File(dir, "default.xex"))
            gameStatus.text = when {
                xex == null -> "default.xex ada tetapi tidak terbaca sebagai XEX yang valid."
                xex.isNinjaGaiden2 -> "Siap: Ninja Gaiden II (disc ${xex.discNumber}/${xex.discCount})"
                else -> "Peringatan: title ID %08X bukan Ninja Gaiden II.".format(xex.titleId)
            }
            gameStatus.setTextColor(if (xex?.isNinjaGaiden2 == true) 0xFF7BD88F.toInt() else 0xFFFFC857.toInt())
        } else {
            gameStatus.text = "Belum ada game. Pilih folder hasil ekstrak disc (atau ISO) milik Anda."
            gameStatus.setTextColor(0xFFECECEC.toInt())
        }
        // The test build needs no game (that is its whole purpose); the real runtime does.
        val realRuntime = loaded && NativeBridge.nativeRuntimeLinked()
        btnPlay.isEnabled = !busy && (GameStorage.hasGame(dir) || !realRuntime)
    }

    private fun setBusy(b: Boolean) {
        busy = b
        btnFolder.isEnabled = !b; btnIso.isEnabled = !b
        btnCancel.visibility = if (b) View.VISIBLE else View.GONE
        progress.visibility = if (b) View.VISIBLE else View.GONE
        progressText.visibility = if (b) View.VISIBLE else View.GONE
        if (b) cancel.set(false)
        refresh()
    }

    private fun fmt(b: Long): String =
        if (b >= 1L shl 30) "%.1f GB".format(b / (1L shl 30).toDouble()) else "%d MB".format(b shr 20)

    // ---- folder import -------------------------------------------------------
    private fun importFolder(uri: Uri) {
        val dest = GameStorage.gameDir(this)
        lifecycleScope.launch {
            notice.text = ""
            setBusy(true)
            var lastPct = -1
            try {
                GameStorage.copyTree(this@MainActivity, uri, dest, cancel) { p ->
                    val pct = if (p.totalBytes > 0) (p.doneBytes * 1000 / p.totalBytes).toInt() else 0
                    if (pct != lastPct) {
                        lastPct = pct
                        runOnUiThread {
                            progress.progress = pct
                            progressText.text = "${fmt(p.doneBytes)} / ${fmt(p.totalBytes)}  ${p.file}"
                        }
                    }
                }
            } catch (e: GameStorage.InsufficientSpace) {
                notice.text = "Ruang kosong kurang: butuh ${fmt(e.need)}, tersedia ${fmt(e.have)}."
            } catch (e: IOException) {
                notice.text = "Gagal menyalin: ${e.message}"
            } finally {
                setBusy(false)
                runPreflight()
            }
        }
    }

    // ---- ISO import (SDK-backed) ---------------------------------------------
    private fun importIso(uri: Uri) {
        if (!NativeBridge.load()) { notice.text = "Library native tidak termuat."; return }
        val dest = GameStorage.gameDir(this)
        lifecycleScope.launch {
            notice.text = ""
            setBusy(true)
            val pfd = try { contentResolver.openFileDescriptor(uri, "r") } catch (_: Exception) { null }
            if (pfd == null) { notice.text = "File ISO tidak bisa dibuka."; setBusy(false); return@launch }
            try {
                val info = withContext(Dispatchers.IO) { NativeBridge.nativeInspectIso(pfd.fd) }
                if (info.startsWith("err|")) { notice.text = info.removePrefix("err|"); return@launch }
                // ok|<title id hex>|<is ng2>|<files>|<bytes>|<message>
                val f = info.split("|")
                if (f.getOrNull(2) != "1") {
                    notice.text = "Disc ini bukan Ninja Gaiden II (title ID ${f.getOrNull(1)})."
                    return@launch
                }
                if (!NativeBridge.nativeExtractIso(pfd.fd, dest.absolutePath)) {
                    notice.text = "Ekstraksi tidak bisa dimulai."; return@launch
                }
                while (true) {
                    delay(300)
                    val v = NativeBridge.nativeExtractProgress()
                    if (v[3] > 0) {
                        progress.progress = (v[2] * 1000 / v[3]).toInt()
                        progressText.text = "${fmt(v[2])} / ${fmt(v[3])}"
                    }
                    if (v[4] == 2L) break
                    if (v[4] == 3L) {
                        notice.text = "Ekstraksi gagal: ${NativeBridge.nativeExtractError()}"
                        return@launch
                    }
                }
            } finally {
                pfd.close()
                setBusy(false)
            }
        }
    }
}
