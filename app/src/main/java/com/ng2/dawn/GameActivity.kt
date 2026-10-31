package com.ng2.dawn

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The game screen: one SurfaceView for the runtime, the touch overlay on top,
 * a small menu button, and (when editing) a toolbar. Physical gamepads are
 * handled at the activity level so they work regardless of focus.
 */
class GameActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var settings: Settings
    private lateinit var surface: SurfaceView
    private lateinit var controls: TouchControlsView
    private lateinit var editBar: LinearLayout
    private lateinit var menuButton: Button
    private val gamepad = GamepadMapper { NativeBridge.nativeSetPad(NativeBridge.SRC_GAMEPAD, it.buttons, it.lx, it.ly, it.rx, it.ry, it.lt, it.rt) }
    private var started = false
    private var menuOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        surface = SurfaceView(this).apply { holder.addCallback(this@GameActivity) }
        // The stub host (and any CPU-composited path) needs RGBA_8888.
        surface.holder.setFormat(PixelFormat.RGBA_8888)
        root.addView(surface, match())

        controls = TouchControlsView(this).apply {
            layout = ControlLayout.fromJson(settings.layoutJson)
            onPad = { NativeBridge.nativeSetPad(NativeBridge.SRC_TOUCH, it.buttons, it.lx, it.ly, it.rx, it.ry, it.lt, it.rt) }
            onLayoutChanged = { settings.layoutJson = ControlLayout.toJson(it) }
        }
        root.addView(controls, match())
        applyControlSettings()

        menuButton = Button(this).apply {
            text = "☰"; textSize = 18f; setTextColor(Color.WHITE)
            setBackgroundColor(0x66000000); alpha = 0.7f
            setOnClickListener { showMenu() }
        }
        root.addView(menuButton, FrameLayout.LayoutParams(dp(56), dp(44)).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = dp(4)
        })

        editBar = buildEditBar().also { it.visibility = View.GONE }
        root.addView(editBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8) })

        setContentView(root)
        hideSystemBars()
    }

    private fun match() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun applyControlSettings() {
        controls.opacity = settings.controlsOpacity
        controls.sizeScale = settings.controlsScale
        controls.haptics = settings.haptics
        controls.floatingStick = settings.floatingStick
    }

    private fun hideSystemBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    // ---- lifecycle -----------------------------------------------------------
    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (started && !menuOpen) NativeBridge.nativeSetPaused(false)
    }

    override fun onPause() {
        // Release everything so a finger or button never stays "held" in the guest.
        NativeBridge.nativeSetPad(NativeBridge.SRC_TOUCH, 0, 0f, 0f, 0f, 0f, 0f, 0f)
        gamepad.reset()
        if (started) NativeBridge.nativeSetPaused(true)
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing && started) { NativeBridge.nativeStop(); started = false }
        super.onDestroy()
    }

    // ---- surface -------------------------------------------------------------
    override fun surfaceCreated(holder: SurfaceHolder) {
        if (Build.VERSION.SDK_INT >= 30) {
            val fps = settings.fps.toFloat()
            holder.surface.setFrameRate(fps, android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        }
        if (!started) startGame()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        NativeBridge.nativeSurfaceChanged(holder.surface, w, h)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        NativeBridge.nativeSurfaceDestroyed()
    }

    private fun startGame() {
        if (!NativeBridge.load()) {
            Toast.makeText(this, "libng2android.so tidak ditemukan", Toast.LENGTH_LONG).show()
            finish(); return
        }
        val game = GameStorage.gameDir(this)
        settings.gamePath = game.absolutePath
        settings.writeNativeCfg()
        NativeBridge.nativeConfigure(filesDir.absolutePath, cacheDir.absolutePath, game.absolutePath)
        started = NativeBridge.nativeStart()
        if (!started) {
            Toast.makeText(this, "Runtime gagal dimulai", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // ---- input ---------------------------------------------------------------
    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (ev.keyCode == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP) {
            if (menuOpen) return super.dispatchKeyEvent(ev)
            showMenu(); return true
        }
        // The pad's Guide/Mode button opens the menu rather than reaching the game.
        if (ev.keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (ev.action == KeyEvent.ACTION_UP && !menuOpen) showMenu()
            return true
        }
        if (gamepad.onKey(ev)) { onGamepadActive(); return true }
        return super.dispatchKeyEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (gamepad.onMotion(ev)) { onGamepadActive(); return true }
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun onGamepadActive() {
        if (settings.hideWithGamepad && !editMode() && controls.visibility == View.VISIBLE) {
            controls.visibility = View.INVISIBLE
        }
    }

    private fun editMode() = controls.editMode

    // ---- menu ----------------------------------------------------------------
    private fun showMenu() {
        menuOpen = true
        NativeBridge.nativeSetPaused(true)
        val status = NativeBridge.nativeStatus()
        val items = arrayOf("Lanjutkan", "Atur posisi kontrol", "Tampilan kontrol…",
            if (controls.visibility == View.VISIBLE) "Sembunyikan kontrol sentuh" else "Tampilkan kontrol sentuh",
            "Keluar dari game")
        AlertDialog.Builder(this)
            .setTitle("Menu" + if (status.isNotEmpty()) "\n$status" else "")
            .setItems(items) { _, which ->
                when (which) {
                    1 -> enterEdit()
                    2 -> showControlSettings()
                    3 -> controls.visibility =
                        if (controls.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE
                    4 -> { NativeBridge.nativeStop(); started = false; finish() }
                }
            }
            .setOnDismissListener {
                menuOpen = false
                if (!controls.editMode) NativeBridge.nativeSetPaused(false)
            }
            .show()
    }

    private fun showControlSettings() {
        val pad = dp(20)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0) }
        fun slider(label: String, value: Int, max: Int, on: (Int) -> Unit) {
            box.addView(TextView(this).apply { text = label })
            box.addView(SeekBar(this).apply {
                this.max = max; progress = value
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { on(p) }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            })
        }
        fun toggle(label: String, on: Boolean, change: (Boolean) -> Unit) {
            box.addView(Switch(this).apply {
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
        toggle("Getar saat tombol ditekan", settings.haptics) { settings.haptics = it; applyControlSettings() }
        toggle("Stik kiri melayang", settings.floatingStick) { settings.floatingStick = it; applyControlSettings() }
        toggle("Sembunyikan kontrol saat gamepad dipakai", settings.hideWithGamepad) { settings.hideWithGamepad = it }
        AlertDialog.Builder(this).setTitle("Tampilan kontrol").setView(box)
            .setPositiveButton("Selesai", null).show()
    }

    // ---- edit mode -----------------------------------------------------------
    private fun buildEditBar(): LinearLayout {
        fun b(t: String, f: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { f() }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xCC000000.toInt())
            addView(b("−") { controls.scaleSelected(0.9f) })
            addView(b("+") { controls.scaleSelected(1.1f) })
            addView(b("Reset") { controls.resetLayout() })
            addView(b("Selesai") { exitEdit() })
        }
    }

    private fun enterEdit() {
        controls.visibility = View.VISIBLE
        controls.editMode = true
        editBar.visibility = View.VISIBLE
        menuButton.visibility = View.GONE
        Toast.makeText(this, "Seret kontrol untuk memindahkan. Ketuk untuk memilih, lalu − / +.", Toast.LENGTH_LONG).show()
    }

    private fun exitEdit() {
        controls.editMode = false
        editBar.visibility = View.GONE
        menuButton.visibility = View.VISIBLE
        settings.layoutJson = ControlLayout.toJson(controls.layout)
        NativeBridge.nativeSetPaused(false)
    }
}
