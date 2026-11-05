package com.ng2.dawn

import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * TEST-MODE game screen (no ReXGlue runtime linked): a SurfaceView drawn by the
 * stub host, plus the shared overlay. With the real runtime MainActivity starts
 * [GameSdlActivity] instead and this class is not used.
 */
class GameActivity : AppCompatActivity(), SurfaceHolder.Callback, GameOverlay.Host {

    private lateinit var settings: Settings
    private lateinit var root: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var overlay: GameOverlay
    private val gamepad = GamepadMapper {
        NativeBridge.nativeSetPad(NativeBridge.SRC_GAMEPAD, it.buttons, it.lx, it.ly, it.rx, it.ry, it.lt, it.rt)
    }
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this).apply { holder.addCallback(this@GameActivity) }
        surface.holder.setFormat(PixelFormat.RGBA_8888)   // the stub host composites on the CPU
        root.addView(surface, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        overlay = GameOverlay(this, settings, this).also { it.attach() }
        gamepad.deadzone = settings.stickDeadzone
        setContentView(root)
        DiagLog.stage("game screen created (test host)")
        hideSystemBars()
    }

    // ---- GameOverlay.Host ------------------------------------------------------
    override fun addOverlayView(view: View, params: FrameLayout.LayoutParams) = root.addView(view, params)
    override fun onMenuVisibility(open: Boolean) { if (started) NativeBridge.nativeSetPaused(open) }
    override fun onQuit() { Telemetry.setActive(false); NativeBridge.nativeStop(); started = false; finish() }
    override fun status(): String = NativeBridge.nativeStatus()

    private fun hideSystemBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    // ---- lifecycle ---------------------------------------------------------------
    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (started && !overlay.menuOpen) NativeBridge.nativeSetPaused(false)
    }

    override fun onPause() {
        overlay.releaseTouch()
        gamepad.reset()
        if (started) NativeBridge.nativeSetPaused(true)
        super.onPause()
    }

    override fun onDestroy() {
        overlay.detach()
        if (isFinishing) Telemetry.setActive(false)
        if (isFinishing && started) { NativeBridge.nativeStop(); started = false }
        super.onDestroy()
    }

    // ---- surface -------------------------------------------------------------------
    override fun surfaceCreated(holder: SurfaceHolder) {
        if (Build.VERSION.SDK_INT >= 30) {
            holder.surface.setFrameRate(settings.fps.toFloat(), android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        }
        if (!started) startGame()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        NativeBridge.nativeSurfaceChanged(holder.surface, w, h)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) { NativeBridge.nativeSurfaceDestroyed() }

    private fun startGame() {
        if (!NativeBridge.load()) {
            Toast.makeText(this, "Library native tidak ditemukan", Toast.LENGTH_LONG).show()
            finish(); return
        }
        val game = GameStorage.gameDir(this)
        settings.gamePath = game.absolutePath
        settings.writeNativeCfg()
        NativeBridge.nativeConfigure(filesDir.absolutePath, cacheDir.absolutePath, game.absolutePath)
        started = NativeBridge.nativeStart()
        DiagLog.stage(if (started) "test host started" else "test host FAILED to start")
        if (started) Telemetry.setActive(true)
        if (!started) {
            Toast.makeText(this, "Host gagal dimulai", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // ---- input ---------------------------------------------------------------------
    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (ev.keyCode == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP) {
            if (overlay.menuOpen) return super.dispatchKeyEvent(ev)
            overlay.showMenu(); return true
        }
        if (ev.keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {      // pad Guide button → menu
            if (ev.action == KeyEvent.ACTION_UP && !overlay.menuOpen) overlay.showMenu()
            return true
        }
        if (gamepad.onKey(ev)) { overlay.onGamepadActive(); return true }
        return super.dispatchKeyEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (gamepad.onMotion(ev)) { overlay.onGamepadActive(); return true }
        return super.dispatchGenericMotionEvent(ev)
    }
}
