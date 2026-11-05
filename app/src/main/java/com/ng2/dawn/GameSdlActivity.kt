package com.ng2.dawn

import android.os.Bundle
import android.system.Os
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.libsdl.app.SDLActivity

/**
 * REAL-RUNTIME game screen. SDLActivity (SDL3's own Java glue, bundled under
 * org/libsdl/app) owns the surface, the lifecycle and physical gamepads and
 * runs SDL_main - which the patched ReXGlue SDK implements - on its own
 * thread. This class adds what SDL does not have: the touch pad and menu.
 *
 * Physical pads are NOT handled here: SDL reports them through its gamepad API
 * and the SDK's SDL input driver feeds them to the guest. We only watch for
 * them to hide the on-screen pad.
 */
class GameSdlActivity : SDLActivity(), GameOverlay.Host {

    private lateinit var settings: Settings
    private var overlay: GameOverlay? = null

    /** The recompiled game + SDK + SDL are one library, libmain.so. */
    override fun getLibraries(): Array<String> = arrayOf("main")

    override fun onCreate(savedInstanceState: Bundle?) {
        // Everything native needs must be in place BEFORE SDLActivity starts the
        // SDL_main thread from its own onCreate.
        settings = Settings(this)
        val game = GameStorage.gameDir(this)
        settings.gamePath = game.absolutePath
        settings.writeNativeCfg()
        try {
            Os.setenv("NG2_FILES_DIR", filesDir.absolutePath, true)
            // REX_<CVAR> environment variables are applied by RunWindowedApp before the
            // GPU device is chosen. Mobile GPUs frequently lack geometryShader and
            // fillModeNonSolid, which the SDK requires by default; its own code offers
            // fallback paths when these two cvars are false (as it does on macOS).
            Os.setenv("REX_VULKAN_REQUIRE_GEOMETRY_SHADER", "false", true)
            Os.setenv("REX_VULKAN_REQUIRE_FILL_MODE_NON_SOLID", "false", true)
            // The SDK derives its default log folder from the executable's folder, which on
            // Android is /system/bin (read-only). Point it at the app's logs dir instead, and
            // pass the verbosity chosen in the setup screen.
            Os.setenv("REX_LOG_FILE", java.io.File(DiagLog.logsDir(this), "rex.log").absolutePath, true)
            Os.setenv("REX_LOG_LEVEL", settings.logLevel, true)
        } catch (_: Exception) {}
        NativeBridge.load()
        DiagLog.stage("starting SDL activity (real runtime), game_dir=${game.absolutePath}")

        super.onCreate(savedInstanceState)

        overlay = GameOverlay(this, settings, this).also { it.attach() }
        Telemetry.setActive(true)
        DiagLog.stage("SDL activity created, overlay attached")
    }

    // ---- GameOverlay.Host --------------------------------------------------------
    override fun addOverlayView(view: View, params: FrameLayout.LayoutParams) = addContentView(view, params)
    // The guest cannot be suspended mid-frame from here; the menu overlays a running game.
    override fun onMenuVisibility(open: Boolean) {}
    override fun onQuit() { Telemetry.setActive(false); finish() }
    override fun status(): String = ""

    override fun onPause() {
        overlay?.releaseTouch()
        DiagLog.stage("activity paused")
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        DiagLog.stage("activity resumed")
    }

    override fun onDestroy() {
        overlay?.detach()
        if (isFinishing) Telemetry.setActive(false)
        DiagLog.stage("activity destroyed (finishing=$isFinishing)")
        super.onDestroy()
    }

    // ---- input -----------------------------------------------------------------------
    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        val o = overlay
        if (o != null) {
            if (ev.keyCode == KeyEvent.KEYCODE_BACK && ev.action == KeyEvent.ACTION_UP) {
                if (!o.menuOpen) o.showMenu(); return true
            }
            if (ev.keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
                if (ev.action == KeyEvent.ACTION_UP && !o.menuOpen) o.showMenu()
                return true
            }
            if (GamepadMapper.isGamepad(ev.source)) o.onGamepadActive()
        }
        return super.dispatchKeyEvent(ev)       // SDL consumes the pad
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (GamepadMapper.isGamepad(ev.source)) overlay?.onGamepadActive()
        return super.dispatchGenericMotionEvent(ev)
    }
}
