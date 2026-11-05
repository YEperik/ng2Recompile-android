package com.ng2.dawn

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * App settings. Two stores:
 *  - SharedPreferences: everything, including the Android-only touch options.
 *  - ng2_settings.cfg in filesDir: the subset the native side understands, in
 *    the SAME key=value format as the desktop project's Ng2Settings, so the
 *    runtime (Ng2App::OnConfigurePaths) reads it without any Android code.
 */
class Settings(context: Context) {
    private val p: SharedPreferences =
        context.applicationContext.getSharedPreferences("ng2", Context.MODE_PRIVATE)
    private val filesDir = context.applicationContext.filesDir

    var gamePath: String
        get() = p.getString("game_path", "") ?: ""
        set(v) = p.edit().putString("game_path", v).apply()

    var fps: Int                       // 30 or 60 (the original clamps to 30..60)
        get() = p.getInt("fps", 30)
        set(v) = p.edit().putInt("fps", if (v >= 60) 60 else 30).apply()

    var internal720p: Boolean
        get() = p.getBoolean("internal_720p", false)
        set(v) = p.edit().putBoolean("internal_720p", v).apply()

    var skipIntros: Boolean            // original: video_mode 2 = skip intro videos
        get() = p.getBoolean("skip_intros", false)
        set(v) = p.edit().putBoolean("skip_intros", v).apply()

    var textureCacheMb: Int
        get() = p.getInt("texture_cache_mb", 768)
        set(v) = p.edit().putInt("texture_cache_mb", v.coerceIn(128, 4096)).apply()

    // ---- diagnostics ----
    var showFps: Boolean
        get() = p.getBoolean("show_fps", true)
        set(v) = p.edit().putBoolean("show_fps", v).apply()

    /** "host:port" of a PC running `ng2dev.py watch --udp PORT`; empty = off. */
    var telemetryTarget: String
        get() = p.getString("telemetry_target", "") ?: ""
        set(v) = p.edit().putString("telemetry_target", v.trim()).apply()

    /** Native (SDK) log verbosity: info | debug | trace. Applied at the next game start. */
    var logLevel: String
        get() = p.getString("log_level", "info") ?: "info"
        set(v) = p.edit().putString("log_level", if (v in setOf("info", "debug", "trace")) v else "info").apply()

    // ---- touch controls (Android only) ----

    var stickDeadzone: Float
        get() = p.getFloat("stick_dead", PadMath.STICK_DEADZONE)
        set(v) = p.edit().putFloat("stick_dead", v.coerceIn(0.0f, 0.4f)).apply()

    /** >1 reaches full deflection with less finger travel. */
    var stickSensitivity: Float
        get() = p.getFloat("stick_sens", 1.0f)
        set(v) = p.edit().putFloat("stick_sens", v.coerceIn(0.5f, 2.0f)).apply()

    var controlsOpacity: Float
        get() = p.getFloat("ctl_opacity", 0.55f)
        set(v) = p.edit().putFloat("ctl_opacity", v.coerceIn(0.1f, 1f)).apply()

    var controlsScale: Float
        get() = p.getFloat("ctl_scale", 1.0f)
        set(v) = p.edit().putFloat("ctl_scale", v.coerceIn(0.6f, 1.6f)).apply()

    var haptics: Boolean
        get() = p.getBoolean("ctl_haptics", true)
        set(v) = p.edit().putBoolean("ctl_haptics", v).apply()

    var floatingStick: Boolean
        get() = p.getBoolean("ctl_floating", true)
        set(v) = p.edit().putBoolean("ctl_floating", v).apply()

    var hideWithGamepad: Boolean
        get() = p.getBoolean("ctl_hide_gamepad", true)
        set(v) = p.edit().putBoolean("ctl_hide_gamepad", v).apply()

    var layoutJson: String?
        get() = p.getString("ctl_layout", null)
        set(v) = p.edit().putString("ctl_layout", v).apply()

    /** Writes ng2_settings.cfg. Keys are the ones Ng2Settings::Load() parses. */
    fun writeNativeCfg() {
        val lines = listOf(
            "# written by the Android shell; same format as the desktop build",
            "game_path=$gamePath",
            "fps=$fps",
            "vsync=1",
            "fullscreen=1",
            "internal_720p=${if (internal720p) 1 else 0}",
            "video_mode=${if (skipIntros) 2 else 0}",
            "texture_cache_mb=$textureCacheMb",
            "hardware_detected=1",         // the desktop detection is DXGI-only
            "check_for_updates=0",         // the store updates the app
            "skip_cinematics=0",
            "configured=1",
        )
        File(filesDir, "ng2_settings.cfg").writeText(lines.joinToString("\n") + "\n")
    }
}
