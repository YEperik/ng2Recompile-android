package com.ng2.dawn

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.hypot
import kotlin.math.max

/**
 * Physical controllers (Xbox, PlayStation, 8BitDo, ... over Bluetooth/USB)
 * mapped to the Xbox 360 pad the game expects. Android already normalizes
 * most pads to the Xbox layout, so A/B/X/Y keep their positions.
 */
class GamepadMapper(private val onPad: (PadState) -> Unit) {

    private var buttons = 0
    private var lx = 0f; private var ly = 0f; private var rx = 0f; private var ry = 0f
    private var lt = 0f; private var rt = 0f
    private var hatX = 0f; private var hatY = 0f
    private var dpadKeys = 0
    var seen = false
        private set
    var deadzone = PadMath.STICK_DEADZONE

    companion object {
        fun isGamepad(source: Int) =
            source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD

        private fun mask(key: Int): Int = when (key) {
            KeyEvent.KEYCODE_BUTTON_A -> Btn.A
            KeyEvent.KEYCODE_BUTTON_B -> Btn.B
            KeyEvent.KEYCODE_BUTTON_X -> Btn.X
            KeyEvent.KEYCODE_BUTTON_Y -> Btn.Y
            KeyEvent.KEYCODE_BUTTON_L1 -> Btn.LB
            KeyEvent.KEYCODE_BUTTON_R1 -> Btn.RB
            KeyEvent.KEYCODE_BUTTON_THUMBL -> Btn.LTHUMB
            KeyEvent.KEYCODE_BUTTON_THUMBR -> Btn.RTHUMB
            KeyEvent.KEYCODE_BUTTON_START -> Btn.START
            KeyEvent.KEYCODE_BUTTON_SELECT -> Btn.BACK
            else -> 0
        }
        private fun dpad(key: Int): Int = when (key) {
            KeyEvent.KEYCODE_DPAD_UP -> Btn.DPAD_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> Btn.DPAD_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> Btn.DPAD_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> Btn.DPAD_RIGHT
            else -> 0
        }
    }

    /** @return true if the event was a gamepad event and was consumed. */
    fun onKey(ev: KeyEvent): Boolean {
        if (!isGamepad(ev.source)) return false
        val down = ev.action == KeyEvent.ACTION_DOWN
        val m = mask(ev.keyCode)
        val d = dpad(ev.keyCode)
        // Triggers on pads that report them as buttons rather than axes.
        when (ev.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2 -> { lt = if (down) 1f else 0f; push(); return true }
            KeyEvent.KEYCODE_BUTTON_R2 -> { rt = if (down) 1f else 0f; push(); return true }
        }
        if (m == 0 && d == 0) return false
        if (m != 0) buttons = if (down) buttons or m else buttons and m.inv()
        if (d != 0) dpadKeys = if (down) dpadKeys or d else dpadKeys and d.inv()
        push()
        return true
    }

    fun onMotion(ev: MotionEvent): Boolean {
        if (ev.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK ||
            ev.action != MotionEvent.ACTION_MOVE) return false
        // Left stick. Android Y is down-positive: flip to XInput.
        val (a, b) = PadMath.radial(ev.getAxisValue(MotionEvent.AXIS_X), ev.getAxisValue(MotionEvent.AXIS_Y), deadzone)
        lx = a; ly = -b
        // Right stick: Z/RZ on Xbox-style pads, RX/RY on others - take whichever moves.
        val (zx, zy) = PadMath.radial(ev.getAxisValue(MotionEvent.AXIS_Z), ev.getAxisValue(MotionEvent.AXIS_RZ), deadzone)
        val (px, py) = PadMath.radial(ev.getAxisValue(MotionEvent.AXIS_RX), ev.getAxisValue(MotionEvent.AXIS_RY), deadzone)
        if (hypot(zx, zy) >= hypot(px, py)) { rx = zx; ry = -zy } else { rx = px; ry = -py }
        lt = max(ev.getAxisValue(MotionEvent.AXIS_LTRIGGER), ev.getAxisValue(MotionEvent.AXIS_BRAKE))
            .coerceIn(0f, 1f).let { if (it < 0.05f) 0f else it }
        rt = max(ev.getAxisValue(MotionEvent.AXIS_RTRIGGER), ev.getAxisValue(MotionEvent.AXIS_GAS))
            .coerceIn(0f, 1f).let { if (it < 0.05f) 0f else it }
        hatX = ev.getAxisValue(MotionEvent.AXIS_HAT_X)
        hatY = ev.getAxisValue(MotionEvent.AXIS_HAT_Y)
        push()
        return true
    }

    private fun push() {
        seen = true
        var b = buttons or dpadKeys
        if (hatX < -0.5f) b = b or Btn.DPAD_LEFT
        if (hatX > 0.5f) b = b or Btn.DPAD_RIGHT
        if (hatY < -0.5f) b = b or Btn.DPAD_UP
        if (hatY > 0.5f) b = b or Btn.DPAD_DOWN
        onPad(PadState(b, lx, ly, rx, ry, lt, rt))
    }

    /** Called when the activity loses focus so nothing stays held. */
    fun reset() {
        buttons = 0; dpadKeys = 0; lx = 0f; ly = 0f; rx = 0f; ry = 0f
        lt = 0f; rt = 0f; hatX = 0f; hatY = 0f
        if (seen) push()
    }
}
