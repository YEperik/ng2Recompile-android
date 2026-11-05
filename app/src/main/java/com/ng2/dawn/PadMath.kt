package com.ng2.dawn

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * The control math, kept free of Android types so it is unit-tested on the JVM
 * (tests/kotlin). Used by the touch overlay and the physical-gamepad mapper.
 */
object PadMath {
    const val STICK_DEADZONE = 0.12f

    /**
     * Touch drag -> XInput stick.
     * @param dx,dy finger offset from the stick centre, screen pixels (+y is DOWN)
     * @param maxRadius pixel distance that means "fully pushed" (at sensitivity 1)
     * @return (x, y) in -1..1 with +y UP, radial dead zone removed and rescaled so
     *         the output starts from 0 just outside it (no jump).
     */
    fun stick(
        dx: Float, dy: Float, maxRadius: Float,
        dead: Float = STICK_DEADZONE, sensitivity: Float = 1f,
    ): Pair<Float, Float> {
        if (maxRadius <= 0f) return 0f to 0f
        val mag = hypot(dx, dy)
        if (mag < 1e-3f) return 0f to 0f
        // sensitivity > 1: full deflection is reached after less finger travel.
        val norm = min(1f, mag * sensitivity.coerceIn(0.25f, 4f) / maxRadius)
        val s = if (norm < dead) 0f else (norm - dead) / (1f - dead)
        // "+ 0f" turns -0.0 into 0.0 so equal states compare equal (data class equals
        // distinguishes the two, which would send pointless updates).
        return (dx / mag) * s + 0f to -(dy / mag) * s + 0f
    }

    /**
     * Physical analog stick (Android reports +y DOWN as well; the caller flips).
     * Same radial dead zone, input already in -1..1.
     */
    fun radial(x: Float, y: Float, dead: Float = STICK_DEADZONE): Pair<Float, Float> {
        val m = hypot(x, y)
        if (m < dead) return 0f to 0f
        val s = ((m - dead) / (1f - dead)).coerceIn(0f, 1f)
        return (x / m) * s + 0f to (y / m) * s + 0f
    }

    /**
     * 8-way d-pad from a finger position relative to the d-pad centre
     * (screen pixels, +y DOWN). Diagonals press two directions.
     */
    fun dpad(dx: Float, dy: Float, radius: Float): Int {
        if (hypot(dx, dy) < radius * 0.25f) return 0
        val a = Math.toDegrees(atan2((-dy).toDouble(), dx.toDouble()))   // 0 = right, +90 = up
        var m = 0
        if (a > 22.5 && a < 157.5) m = m or Btn.DPAD_UP
        if (a < -22.5 && a > -157.5) m = m or Btn.DPAD_DOWN
        if (abs(a) < 67.5) m = m or Btn.DPAD_RIGHT
        if (abs(a) > 112.5) m = m or Btn.DPAD_LEFT
        return m
    }
}
