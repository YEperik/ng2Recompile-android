package com.ng2.dawn

/**
 * Text form of a controller state, used by `ng2dev.py pad` to test the game
 * from the PC:   "A+X lx=0.5 ly=-1 lt=1 for=500"   or   "release".
 * Pure; unit-tested.
 */
object PadSpec {
    data class Parsed(val state: PadState, val durationMs: Long, val error: String? = null)

    private val BUTTONS = mapOf(
        "A" to Btn.A, "B" to Btn.B, "X" to Btn.X, "Y" to Btn.Y,
        "LB" to Btn.LB, "RB" to Btn.RB, "START" to Btn.START, "BACK" to Btn.BACK,
        "L3" to Btn.LTHUMB, "R3" to Btn.RTHUMB,
        "UP" to Btn.DPAD_UP, "DOWN" to Btn.DPAD_DOWN, "LEFT" to Btn.DPAD_LEFT, "RIGHT" to Btn.DPAD_RIGHT,
    )

    fun parse(spec: String): Parsed {
        val tokens = spec.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty() || tokens[0].equals("release", true)) return Parsed(PadState(), 0)
        var buttons = 0
        var lx = 0f; var ly = 0f; var rx = 0f; var ry = 0f; var lt = 0f; var rt = 0f
        var dur = 0L
        for (tok in tokens) {
            if ('=' in tok) {
                val (k, v) = tok.split('=', limit = 2)
                val f = v.toFloatOrNull() ?: return Parsed(PadState(), 0, "nilai tidak valid: $tok")
                when (k.lowercase()) {
                    "lx" -> lx = f.coerceIn(-1f, 1f); "ly" -> ly = f.coerceIn(-1f, 1f)
                    "rx" -> rx = f.coerceIn(-1f, 1f); "ry" -> ry = f.coerceIn(-1f, 1f)
                    "lt" -> lt = f.coerceIn(0f, 1f); "rt" -> rt = f.coerceIn(0f, 1f)
                    "for" -> dur = f.toLong().coerceIn(0L, 60_000L)
                    else -> return Parsed(PadState(), 0, "kunci tidak dikenal: $k")
                }
            } else {
                for (name in tok.split('+', ',').filter { it.isNotEmpty() }) {
                    val m = BUTTONS[name.uppercase()] ?: return Parsed(PadState(), 0, "tombol tidak dikenal: $name")
                    buttons = buttons or m
                }
            }
        }
        return Parsed(PadState(buttons, lx, ly, rx, ry, lt, rt), dur)
    }

    fun names(): List<String> = BUTTONS.keys.toList()
}
