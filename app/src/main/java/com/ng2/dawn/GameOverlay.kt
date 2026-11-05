package com.ng2.dawn

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Everything drawn over the game: the touch pad, the ☰ button, the FPS readout,
 * the layout-edit toolbar and the in-game menu. It is independent of who owns
 * the game surface (the test host's SurfaceView or SDLActivity), which is
 * expressed by [Host].
 */
class GameOverlay(
    private val activity: Activity,
    private val settings: Settings,
    private val host: Host,
) {
    interface Host {
        fun addOverlayView(view: View, params: FrameLayout.LayoutParams)
        /** The menu opened/closed. Hosts that can pause the game do so here. */
        fun onMenuVisibility(open: Boolean)
        /** Menu → "Keluar dari game". */
        fun onQuit()
        fun status(): String
    }

    lateinit var controls: TouchControlsView
        private set
    private lateinit var editBar: LinearLayout
    private lateinit var menuButton: Button
    private lateinit var fpsView: TextView
    var menuOpen = false
        private set

    private val telemetryListener: (StatsSample) -> Unit = { s -> activity.runOnUiThread { updateFps(s) } }

    private fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

    fun attach() {
        controls = TouchControlsView(activity).apply {
            layout = ControlLayout.fromJson(settings.layoutJson)
            onPad = { NativeBridge.nativeSetPad(NativeBridge.SRC_TOUCH, it.buttons, it.lx, it.ly, it.rx, it.ry, it.lt, it.rt) }
            onLayoutChanged = { settings.layoutJson = ControlLayout.toJson(it) }
        }
        host.addOverlayView(controls, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        applyControlSettings()

        menuButton = Button(activity).apply {
            text = "☰"; textSize = 18f; setTextColor(Color.WHITE)
            setBackgroundColor(0x66000000); alpha = 0.7f
            setOnClickListener { showMenu() }
        }
        host.addOverlayView(menuButton, FrameLayout.LayoutParams(dp(56), dp(44)).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = dp(4)
        })

        editBar = buildEditBar().also { it.visibility = View.GONE }
        host.addOverlayView(editBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) })

        // Added last = drawn on top, but a non-clickable TextView passes touches through.
        fpsView = TextView(activity).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f; setTextColor(Color.WHITE)
            setBackgroundColor(0x88000000.toInt()); setPadding(dp(6), dp(3), dp(6), dp(3))
            isClickable = false; isFocusable = false
        }
        host.addOverlayView(fpsView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.START; topMargin = dp(4); leftMargin = dp(4) })
        applyFpsVisibility()
        Telemetry.addListener(telemetryListener)
    }

    /** Call from the activity's onDestroy. */
    fun detach() { Telemetry.removeListener(telemetryListener) }

    // ---- FPS readout -----------------------------------------------------------
    private fun applyFpsVisibility() {
        fpsView.visibility = if (settings.showFps) View.VISIBLE else View.GONE
    }

    private fun updateFps(s: StatsSample) {
        applyFpsVisibility()   // `adb ... fps off` takes effect within a second
        if (!settings.showFps) return
        val real = NativeBridge.load() && NativeBridge.nativeRuntimeLinked()
        val o = StatsFormat.overlay(s, settings.fps, real)
        fpsView.text = o.text
        fpsView.setTextColor(when (o.severity) {
            StatsFormat.Severity.OK -> 0xFF7BD88F.toInt()
            StatsFormat.Severity.WARN -> 0xFFFFC857.toInt()
            StatsFormat.Severity.BAD -> 0xFFFF8A80.toInt()
            StatsFormat.Severity.WAIT -> Color.WHITE
        })
    }

    fun applyControlSettings() {
        controls.opacity = settings.controlsOpacity
        controls.sizeScale = settings.controlsScale
        controls.haptics = settings.haptics
        controls.floatingStick = settings.floatingStick
        controls.deadzone = settings.stickDeadzone
        controls.sensitivity = settings.stickSensitivity
    }

    /** Releases every touch so nothing stays "held" in the guest. */
    fun releaseTouch() {
        NativeBridge.nativeSetPad(NativeBridge.SRC_TOUCH, 0, 0f, 0f, 0f, 0f, 0f, 0f)
    }

    /** A physical pad was used: hide the on-screen one if the user wants that. */
    fun onGamepadActive() {
        if (settings.hideWithGamepad && !controls.editMode && controls.visibility == View.VISIBLE) {
            controls.visibility = View.INVISIBLE
        }
    }

    // ---- menu ----------------------------------------------------------------
    fun showMenu() {
        if (menuOpen) return
        menuOpen = true
        host.onMenuVisibility(true)
        val status = host.status()
        val items = arrayOf(
            "Lanjutkan",
            "Atur posisi / fungsi tombol",
            "Tampilan & stik…",
            if (controls.visibility == View.VISIBLE) "Sembunyikan kontrol sentuh" else "Tampilkan kontrol sentuh",
            if (settings.showFps) "Matikan tampilan FPS" else "Nyalakan tampilan FPS",
            "Laporan diagnosa (kirim/simpan)",
            "Keluar dari game",
        )
        AlertDialog.Builder(activity)
            .setTitle("Menu" + if (status.isNotEmpty()) "\n$status" else "")
            .setItems(items) { _, which ->
                when (which) {
                    1 -> enterEdit()
                    2 -> showControlSettings()
                    3 -> controls.visibility =
                        if (controls.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE
                    4 -> { settings.showFps = !settings.showFps; applyFpsVisibility() }
                    5 -> makeReport()
                    6 -> host.onQuit()
                }
            }
            .setOnDismissListener {
                menuOpen = false
                if (!controls.editMode) host.onMenuVisibility(false)
            }
            .show()
    }

    private fun makeReport() {
        Toast.makeText(activity, "Membuat laporan…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val f = Report.build(activity)
                activity.runOnUiThread { Report.share(activity, f) }
            } catch (e: Throwable) {
                DiagLog.e("report", "failed", e)
                activity.runOnUiThread { Toast.makeText(activity, "Gagal membuat laporan: $e", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun showControlSettings() {
        val pad = dp(20)
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
        fun slider(label: String, value: Int, max: Int, on: (Int) -> Unit) {
            box.addView(TextView(activity).apply { text = label })
            box.addView(SeekBar(activity).apply {
                this.max = max; progress = value
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { on(p) }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            })
        }
        fun toggle(label: String, on: Boolean, change: (Boolean) -> Unit) {
            box.addView(Switch(activity).apply {
                text = label; isChecked = on
                setOnCheckedChangeListener { _, v -> change(v) }
            })
        }
        slider("Transparansi", ((settings.controlsOpacity - 0.1f) / 0.9f * 100).toInt(), 100) {
            settings.controlsOpacity = 0.1f + it / 100f * 0.9f; applyControlSettings()
        }
        slider("Ukuran", ((settings.controlsScale - 0.6f) / 1.0f * 100).toInt(), 100) {
            settings.controlsScale = 0.6f + it / 100f * 1.0f; applyControlSettings()
        }
        slider("Zona mati stik (${"%.2f".format(settings.stickDeadzone)})", (settings.stickDeadzone / 0.4f * 100).toInt(), 100) {
            settings.stickDeadzone = it / 100f * 0.4f; applyControlSettings()
        }
        slider("Sensitivitas stik (${"%.1f".format(settings.stickSensitivity)}×)", ((settings.stickSensitivity - 0.5f) / 1.5f * 100).toInt(), 100) {
            settings.stickSensitivity = 0.5f + it / 100f * 1.5f; applyControlSettings()
        }
        toggle("Getar saat tombol ditekan", settings.haptics) { settings.haptics = it; applyControlSettings() }
        toggle("Stik kiri melayang", settings.floatingStick) { settings.floatingStick = it; applyControlSettings() }
        toggle("Sembunyikan kontrol saat gamepad dipakai", settings.hideWithGamepad) { settings.hideWithGamepad = it }
        AlertDialog.Builder(activity).setTitle("Tampilan & stik").setView(box)
            .setPositiveButton("Selesai", null).show()
    }

    // ---- edit mode -----------------------------------------------------------
    private fun buildEditBar(): LinearLayout {
        fun b(t: String, f: () -> Unit) = Button(activity).apply { text = t; setOnClickListener { f() } }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC000000.toInt())
            addView(b("−") { controls.scaleSelected(0.9f) })
            addView(b("+") { controls.scaleSelected(1.1f) })
            addView(b("Fungsi") { chooseFunction() })
            addView(b("Reset") { controls.resetLayout() })
            addView(b("Selesai") { exitEdit() })
        }
    }

    /** Remap: choose what the selected on-screen button presses. */
    private fun chooseFunction() {
        val e = controls.selectedElement()
        if (e == null || e.type != ElementType.BUTTON) {
            Toast.makeText(activity, "Ketuk salah satu tombol bulat dulu (A/B/X/Y/LB/RB/Start/Back/L3/R3).", Toast.LENGTH_LONG).show()
            return
        }
        val names = ControlLayout.BUTTON_CHOICES.map { it.first }.toTypedArray()
        val current = ControlLayout.BUTTON_CHOICES.indexOfFirst { it.second == e.mask }
        AlertDialog.Builder(activity).setTitle("Fungsi tombol ini")
            .setSingleChoiceItems(names, current) { d, which ->
                controls.remapSelected(ControlLayout.BUTTON_CHOICES[which].second)
                DiagLog.i("controls", "button ${e.id} now presses ${names[which]}")
                d.dismiss()
            }.show()
    }

    private fun enterEdit() {
        controls.visibility = View.VISIBLE
        controls.editMode = true
        editBar.visibility = View.VISIBLE
        menuButton.visibility = View.GONE
        Toast.makeText(activity, "Seret untuk memindahkan. Ketuk untuk memilih, lalu − / + / Fungsi.",
            Toast.LENGTH_LONG).show()
    }

    private fun exitEdit() {
        controls.editMode = false
        editBar.visibility = View.GONE
        menuButton.visibility = View.VISIBLE
        settings.layoutJson = ControlLayout.toJson(controls.layout)
        host.onMenuVisibility(false)
    }
}
