package com.ng2.dawn

import org.json.JSONArray
import org.json.JSONObject

enum class ElementType { BUTTON, STICK, DPAD, TRIGGER }

/**
 * One on-screen control. Position is normalized (x by view width, y by view
 * height); size is the radius as a fraction of view HEIGHT, so a control stays
 * round on any aspect ratio.
 */
data class Element(
    val id: String,
    val type: ElementType,
    var x: Float,
    var y: Float,
    var size: Float,
    var label: String,
    var mask: Int = 0,          // BUTTON: XInput mask (remappable). STICK/TRIGGER: see `side`
    val side: Int = 0,          // STICK: 0 left, 1 right.  TRIGGER: 0 left, 1 right
)

object ControlLayout {
    /** What an on-screen button can be made to press. Order = order in the dialog. */
    val BUTTON_CHOICES: List<Pair<String, Int>> = listOf(
        "A" to Btn.A, "B" to Btn.B, "X" to Btn.X, "Y" to Btn.Y,
        "LB" to Btn.LB, "RB" to Btn.RB, "Start" to Btn.START, "Back" to Btn.BACK,
        "L3 (klik stik kiri)" to Btn.LTHUMB, "R3 (klik stik kanan)" to Btn.RTHUMB,
        "D-pad atas" to Btn.DPAD_UP, "D-pad bawah" to Btn.DPAD_DOWN,
        "D-pad kiri" to Btn.DPAD_LEFT, "D-pad kanan" to Btn.DPAD_RIGHT,
    )

    /** The glyph drawn on the button. */
    fun labelFor(mask: Int): String = when (mask) {
        Btn.A -> "A"; Btn.B -> "B"; Btn.X -> "X"; Btn.Y -> "Y"; Btn.LB -> "LB"; Btn.RB -> "RB"
        Btn.START -> "▶"; Btn.BACK -> "◀"; Btn.LTHUMB -> "L3"; Btn.RTHUMB -> "R3"
        Btn.DPAD_UP -> "↑"; Btn.DPAD_DOWN -> "↓"; Btn.DPAD_LEFT -> "←"; Btn.DPAD_RIGHT -> "→"
        else -> "?"
    }

    fun isValidMask(mask: Int) = BUTTON_CHOICES.any { it.second == mask }

    /** Default landscape layout. Matches Xbox 360 positions people already know. */
    fun defaults(): MutableList<Element> = mutableListOf(
        Element("ls", ElementType.STICK, 0.13f, 0.68f, 0.17f, "", side = 0),
        Element("rs", ElementType.STICK, 0.66f, 0.80f, 0.12f, "", side = 1),
        Element("dpad", ElementType.DPAD, 0.36f, 0.80f, 0.12f, ""),
        Element("a", ElementType.BUTTON, 0.88f, 0.80f, 0.075f, "A", Btn.A),
        Element("b", ElementType.BUTTON, 0.94f, 0.65f, 0.075f, "B", Btn.B),
        Element("x", ElementType.BUTTON, 0.82f, 0.65f, 0.075f, "X", Btn.X),
        Element("y", ElementType.BUTTON, 0.88f, 0.50f, 0.075f, "Y", Btn.Y),
        Element("lb", ElementType.BUTTON, 0.07f, 0.26f, 0.065f, "LB", Btn.LB),
        Element("rb", ElementType.BUTTON, 0.93f, 0.26f, 0.065f, "RB", Btn.RB),
        Element("lt", ElementType.TRIGGER, 0.07f, 0.10f, 0.065f, "LT", side = 0),
        Element("rt", ElementType.TRIGGER, 0.93f, 0.10f, 0.065f, "RT", side = 1),
        Element("start", ElementType.BUTTON, 0.57f, 0.09f, 0.045f, "▶", Btn.START),
        Element("back", ElementType.BUTTON, 0.43f, 0.09f, 0.045f, "◀", Btn.BACK),
        Element("l3", ElementType.BUTTON, 0.13f, 0.40f, 0.045f, "L3", Btn.LTHUMB),
        Element("r3", ElementType.BUTTON, 0.66f, 0.60f, 0.045f, "R3", Btn.RTHUMB),
    )

    fun toJson(list: List<Element>): String {
        val arr = JSONArray()
        for (e in list) arr.put(JSONObject().apply {
            put("id", e.id); put("type", e.type.name)
            put("x", e.x.toDouble()); put("y", e.y.toDouble()); put("size", e.size.toDouble())
            put("label", e.label); put("mask", e.mask); put("side", e.side)
        })
        return arr.toString()
    }

    /** Falls back to defaults on any problem; a corrupt layout must never brick the app. */
    fun fromJson(json: String?): MutableList<Element> {
        if (json.isNullOrBlank()) return defaults()
        return try {
            val saved = JSONArray(json)
            val byId = defaults().associateBy { it.id }.toMutableMap()
            for (i in 0 until saved.length()) {
                val o = saved.getJSONObject(i)
                val base = byId[o.getString("id")] ?: continue
                base.x = o.getDouble("x").toFloat().coerceIn(0.02f, 0.98f)
                base.y = o.getDouble("y").toFloat().coerceIn(0.02f, 0.98f)
                base.size = o.getDouble("size").toFloat().coerceIn(0.03f, 0.30f)
                // A remapped button keeps its new function; an invalid mask is ignored.
                if (base.type == ElementType.BUTTON && o.has("mask")) {
                    val m = o.getInt("mask")
                    if (isValidMask(m)) { base.mask = m; base.label = labelFor(m) }
                }
            }
            defaults().map { byId.getValue(it.id) }.toMutableList()
        } catch (_: Exception) {
            defaults()
        }
    }
}
