package com.ng2.dawn

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max

/**
 * Full-screen virtual Xbox 360 controller.
 *
 *  - Real multi-touch: every finger is bound to at most one control, keyed by
 *    pointer id, so you can hold the stick, jump and attack at once.
 *  - Sliding: a finger that drifts from one button onto a neighbouring one
 *    releases the first and presses the second (needed for combos).
 *  - Floating left stick: touching an empty spot in the left part of the
 *    screen puts the stick centre there.
 *  - Edit mode: drag to move, [scaleSelected] to resize, layout is persisted.
 *
 * The view only produces a [PadState]; it knows nothing about the game.
 */
class TouchControlsView(context: Context) : View(context) {

    var onPad: ((PadState) -> Unit)? = null
    var onLayoutChanged: ((List<Element>) -> Unit)? = null

    var layout: MutableList<Element> = ControlLayout.defaults()
        set(v) { field = v; releaseAll(); invalidate() }
    var opacity = 0.55f
        set(v) { field = v; invalidate() }
    var sizeScale = 1.0f
        set(v) { field = v; invalidate() }
    var haptics = true
    var floatingStick = true
    var deadzone = PadMath.STICK_DEADZONE
    var sensitivity = 1.0f

    var editMode = false
        set(v) { field = v; releaseAll(); if (!v) selectedId = null; invalidate() }
    var selectedId: String? = null
        private set

    private class Binding(val id: String, var x: Float, var y: Float)

    private val bindings = HashMap<Int, Binding>()          // pointerId -> binding
    private val floatCenters = HashMap<String, PointF>()    // floating stick centres
    private var last = PadState()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * resources.displayMetrics.density
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val sel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFFD8372B.toInt()
        strokeWidth = 3f * resources.displayMetrics.density
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }

    // ---- geometry ----------------------------------------------------------
    private fun cx(e: Element) = floatCenters[e.id]?.x ?: (e.x * width)
    private fun cy(e: Element) = floatCenters[e.id]?.y ?: (e.y * height)
    private fun radius(e: Element) = e.size * height * sizeScale

    private fun hitRadius(e: Element) = when (e.type) {
        ElementType.STICK -> radius(e) * 1.5f
        ElementType.DPAD -> radius(e) * 1.25f
        else -> radius(e) * 1.2f           // a little slop: thumbs are bigger than glyphs
    }

    private fun isBound(id: String) = bindings.values.any { it.id == id }

    /** Closest control under (x,y) that is not already held by another finger. */
    private fun findElement(x: Float, y: Float, ignoreBound: Boolean = false): Element? {
        var best: Element? = null
        var bestScore = Float.MAX_VALUE
        for (e in layout) {
            if (!ignoreBound && isBound(e.id)) continue
            val d = hypot(x - cx(e), y - cy(e))
            if (d > hitRadius(e)) continue
            val score = d / radius(e)
            if (score < bestScore) { bestScore = score; best = e }
        }
        return best
    }

    // Gesture navigation (Android 10+) turns a swipe from either screen edge into
    // "Back". The B / RB / LB buttons sit near those edges, so ask the system not to
    // treat touches here as gestures. (The system caps the excluded height.)
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val strip = (32 * resources.displayMetrics.density).toInt()
            systemGestureExclusionRects = listOf(
                android.graphics.Rect(0, 0, strip, h),
                android.graphics.Rect(w - strip, 0, w, h),
            )
        }
    }

    // ---- touch -------------------------------------------------------------
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (editMode) return handleEdit(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                press(ev.getPointerId(i), ev.getX(i), ev.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until ev.pointerCount) move(ev.getPointerId(i), ev.getX(i), ev.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                release(ev.getPointerId(ev.actionIndex))
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        emit()
        invalidate()
        return true
    }

    private fun press(pid: Int, x: Float, y: Float) {
        var e = findElement(x, y)
        if (e == null && floatingStick && x < width * 0.45f) {
            val ls = layout.firstOrNull { it.id == "ls" }
            if (ls != null && !isBound("ls")) {
                val r = radius(ls)
                floatCenters["ls"] = PointF(
                    x.coerceIn(r, max(r, width - r)),
                    y.coerceIn(r, max(r, height - r)),
                )
                e = ls
            }
        }
        if (e == null) return
        bindings[pid] = Binding(e.id, x, y)
        if (e.type != ElementType.STICK) buzz()
    }

    private fun move(pid: Int, x: Float, y: Float) {
        val b = bindings[pid] ?: return
        b.x = x; b.y = y
        val e = layout.firstOrNull { it.id == b.id } ?: return
        if (e.type != ElementType.BUTTON && e.type != ElementType.TRIGGER) return
        if (hypot(x - cx(e), y - cy(e)) <= hitRadius(e)) return
        // Slid off. Hand over to a neighbouring button if there is one.
        bindings.remove(pid)
        val n = findElement(x, y)
        if (n != null && (n.type == ElementType.BUTTON || n.type == ElementType.TRIGGER)) {
            bindings[pid] = Binding(n.id, x, y)
            buzz()
        }
    }

    private fun release(pid: Int) {
        val b = bindings.remove(pid) ?: return
        floatCenters.remove(b.id)
    }

    private fun releaseAll() {
        bindings.clear(); floatCenters.clear(); emit()
    }

    private fun buzz() {
        if (haptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    // ---- state -------------------------------------------------------------
    private fun stickVector(e: Element, b: Binding): Pair<Float, Float> =
        PadMath.stick(b.x - cx(e), b.y - cy(e), radius(e) * 0.9f, deadzone, sensitivity)

    private fun dpadMask(e: Element, b: Binding): Int =
        PadMath.dpad(b.x - cx(e), b.y - cy(e), radius(e))

    private fun computeState(): PadState {
        var buttons = 0
        var lx = 0f; var ly = 0f; var rx = 0f; var ry = 0f; var lt = 0f; var rt = 0f
        for (b in bindings.values) {
            val e = layout.firstOrNull { it.id == b.id } ?: continue
            when (e.type) {
                ElementType.BUTTON -> buttons = buttons or e.mask
                ElementType.TRIGGER -> if (e.side == 0) lt = 1f else rt = 1f
                ElementType.DPAD -> buttons = buttons or dpadMask(e, b)
                ElementType.STICK -> {
                    val (sx, sy) = stickVector(e, b)
                    if (e.side == 0) { lx = sx; ly = sy } else { rx = sx; ry = sy }
                }
            }
        }
        return PadState(buttons, lx, ly, rx, ry, lt, rt)
    }

    private fun emit() {
        val s = computeState()
        if (s != last) { last = s; onPad?.invoke(s) }
    }

    // ---- edit mode ---------------------------------------------------------
    private var dragPointer = -1

    private fun handleEdit(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val e = findElement(ev.x, ev.y, ignoreBound = true)
                selectedId = e?.id
                dragPointer = if (e != null) ev.getPointerId(0) else -1
            }
            MotionEvent.ACTION_MOVE -> {
                val i = ev.findPointerIndex(dragPointer)
                val e = layout.firstOrNull { it.id == selectedId }
                if (i >= 0 && e != null && width > 0 && height > 0) {
                    e.x = (ev.getX(i) / width).coerceIn(0.02f, 0.98f)
                    e.y = (ev.getY(i) / height).coerceIn(0.04f, 0.96f)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragPointer != -1) onLayoutChanged?.invoke(layout)
                dragPointer = -1
            }
        }
        invalidate()
        return true
    }

    fun selectedElement(): Element? = layout.firstOrNull { it.id == selectedId }

    /** Makes the selected on-screen button press [mask] instead. False if nothing suitable is selected. */
    fun remapSelected(mask: Int): Boolean {
        val e = selectedElement() ?: return false
        if (e.type != ElementType.BUTTON || !ControlLayout.isValidMask(mask)) return false
        e.mask = mask
        e.label = ControlLayout.labelFor(mask)
        onLayoutChanged?.invoke(layout)
        invalidate()
        return true
    }

    fun scaleSelected(factor: Float) {
        val e = layout.firstOrNull { it.id == selectedId } ?: return
        e.size = (e.size * factor).coerceIn(0.03f, 0.30f)
        onLayoutChanged?.invoke(layout)
        invalidate()
    }

    fun resetLayout() {
        layout = ControlLayout.defaults()
        onLayoutChanged?.invoke(layout)
    }

    // ---- drawing -----------------------------------------------------------
    override fun onDraw(c: Canvas) {
        val a = if (editMode) max(opacity, 0.6f) else opacity
        for (e in layout) {
            val held = bindings.values.firstOrNull { it.id == e.id }
            val x = cx(e); val y = cy(e); val r = radius(e)
            fill.color = if (held != null) 0xFFD8372B.toInt() else 0xFFFFFFFF.toInt()
            fill.alpha = ((if (held != null) a else a * 0.35f) * 255).toInt()
            stroke.color = 0xFFFFFFFF.toInt(); stroke.alpha = (a * 255).toInt()
            when (e.type) {
                ElementType.BUTTON, ElementType.TRIGGER -> {
                    c.drawCircle(x, y, r, fill); c.drawCircle(x, y, r, stroke)
                    drawLabel(c, e.label, x, y, r, a)
                }
                ElementType.STICK -> {
                    c.drawCircle(x, y, r, fill); c.drawCircle(x, y, r, stroke)
                    var kx = x; var ky = y
                    if (held != null) {
                        val (sx, sy) = stickVector(e, held)
                        kx += sx * r * 0.9f; ky -= sy * r * 0.9f
                    }
                    fill.color = 0xFFFFFFFF.toInt(); fill.alpha = (a * 255 * 0.8f).toInt()
                    c.drawCircle(kx, ky, r * 0.45f, fill)
                }
                ElementType.DPAD -> drawDpad(c, e, held, x, y, r, a)
            }
            if (editMode && e.id == selectedId) {
                c.drawRect(RectF(x - r - 8, y - r - 8, x + r + 8, y + r + 8), sel)
            }
        }
    }

    private fun drawLabel(c: Canvas, s: String, x: Float, y: Float, r: Float, a: Float) {
        if (s.isEmpty()) return
        text.color = 0xFFFFFFFF.toInt(); text.alpha = (a * 255).toInt()
        text.textSize = r * if (s.length > 1) 0.7f else 0.95f
        c.drawText(s, x, y - (text.descent() + text.ascent()) / 2f, text)
    }

    private fun drawDpad(c: Canvas, e: Element, held: Binding?, x: Float, y: Float, r: Float, a: Float) {
        val arm = r * 0.34f
        fill.color = 0xFFFFFFFF.toInt(); fill.alpha = (a * 255 * 0.35f).toInt()
        c.drawRoundRect(RectF(x - r, y - arm, x + r, y + arm), arm, arm, fill)
        c.drawRoundRect(RectF(x - arm, y - r, x + arm, y + r), arm, arm, fill)
        if (held != null) {
            val m = dpadMask(e, held)
            fill.color = 0xFFD8372B.toInt(); fill.alpha = (a * 255).toInt()
            val o = r * 0.68f; val k = arm * 0.8f
            if (m and Btn.DPAD_UP != 0) c.drawCircle(x, y - o, k, fill)
            if (m and Btn.DPAD_DOWN != 0) c.drawCircle(x, y + o, k, fill)
            if (m and Btn.DPAD_LEFT != 0) c.drawCircle(x - o, y, k, fill)
            if (m and Btn.DPAD_RIGHT != 0) c.drawCircle(x + o, y, k, fill)
        }
    }
}
