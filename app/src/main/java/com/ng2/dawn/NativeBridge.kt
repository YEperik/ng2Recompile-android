package com.ng2.dawn

/** JNI surface. Every method maps 1:1 to a function in cpp/ng2_jni.cpp. */
object NativeBridge {
    @Volatile private var loaded = false

    /** Loads libng2android.so once. False means the .so is missing (build problem). */
    @Synchronized
    fun load(): Boolean {
        if (!loaded) {
            loaded = try { System.loadLibrary("ng2android"); true }
            catch (_: UnsatisfiedLinkError) { false }
        }
        return loaded
    }

    const val SRC_TOUCH = 0
    const val SRC_GAMEPAD = 1

    @JvmStatic external fun nativeRuntimeLinked(): Boolean
    @JvmStatic external fun nativeConfigure(filesDir: String, cacheDir: String, gameDir: String)
    @JvmStatic external fun nativeStart(): Boolean
    @JvmStatic external fun nativeStop()
    @JvmStatic external fun nativeSetPaused(paused: Boolean)
    @JvmStatic external fun nativeSurfaceChanged(surface: android.view.Surface?, w: Int, h: Int)
    @JvmStatic external fun nativeSurfaceDestroyed()
    @JvmStatic external fun nativeSetPad(
        source: Int, buttons: Int,
        lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float
    )
    @JvmStatic external fun nativeStatus(): String

    // Disc image import (needs the SDK-backed build)
    @JvmStatic external fun nativeInspectIso(fd: Int): String
    @JvmStatic external fun nativeExtractIso(fd: Int, dest: String): Boolean
    /** [filesDone, filesTotal, bytesDone, bytesTotal, state] state: 0 idle 1 running 2 done 3 failed */
    @JvmStatic external fun nativeExtractProgress(): LongArray
    @JvmStatic external fun nativeExtractError(): String
    @JvmStatic external fun nativeExtractCancel()
}

/** XInput button masks - identical to the ones the desktop project uses. */
object Btn {
    const val DPAD_UP = 0x0001
    const val DPAD_DOWN = 0x0002
    const val DPAD_LEFT = 0x0004
    const val DPAD_RIGHT = 0x0008
    const val START = 0x0010
    const val BACK = 0x0020
    const val LTHUMB = 0x0040
    const val RTHUMB = 0x0080
    const val LB = 0x0100
    const val RB = 0x0200
    const val A = 0x1000
    const val B = 0x2000
    const val X = 0x4000
    const val Y = 0x8000
}

/** One controller reading. Y axes are +up (XInput), triggers 0..1. */
data class PadState(
    val buttons: Int = 0,
    val lx: Float = 0f, val ly: Float = 0f,
    val rx: Float = 0f, val ry: Float = 0f,
    val lt: Float = 0f, val rt: Float = 0f,
)
