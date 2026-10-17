package com.abololo.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class GameView(
    context: Context,
    private val sfx: Sfx,
    private val onQuit: () -> Unit
) : View(context), Choreographer.FrameCallback {

    companion object {
        const val S_MENU = 0
        const val S_SEL = 1
        const val S_BRIEF = 2
        const val S_PLAY = 3
        const val S_PAUSE = 4
        const val S_WIN = 5
        const val S_OVER = 6
        const val S_END = 7

        const val B_PLAY = 1
        const val B_MISS = 2
        const val B_SOUND = 3
        const val B_LANG = 4
        const val B_BACK = 5
        const val B_PAUSE = 6
        const val B_RESUME = 7
        const val B_RESTART = 8
        const val B_MENU = 9
        const val B_NEXT = 10
        const val B_START = 11
        const val B_CUT = 12
        const val B_DANCE = 13
        const val B_M0 = 100

        const val KY = World.KY
        const val PI_F = 3.14159265f
        const val BEAT = World.BEAT
    }

    private class Hit(val id: Int, val l: Float, val t: Float, val r: Float, val b: Float)
    private class DI(@JvmField var y: Float, @JvmField var k: Int, @JvmField var o: Any?)

    private val prefs = context.getSharedPreferences("abololo", Context.MODE_PRIVATE)
    private val world = World(sfx)
    private val hits = ArrayList<Hit>()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rf = RectF()
    private val pth = Path()
    private val drawList = ArrayList<DI>()
    private val diPool = ArrayList<DI>()
    private val diCmp = Comparator<DI> { a, b -> a.y.compareTo(b.y) }
    private val confetti = ArrayList<FloatArray>()
    private val confCols = intArrayOf(Color.rgb(255, 210, 60), Color.rgb(230, 70, 70), Color.rgb(80, 200, 120), Color.rgb(90, 160, 255))

    // layout
    private var sc = 1f
    private var offX = 0f
    private var wh = 800f
    private var camSY = 440f

    // state
    private var state = S_MENU
    private var pendingState = -1
    private var fade = 1f
    private var mission = 1
    private var unlocked = 1
    private var menuT = 0f
    private var running = false
    private var last = 0L
    private var camX = 450f
    private var camY = 1800f
    private var camInit = true
    private var curCut = 0
    private var cutIdx = 0
    private var cutTime = 0f

    // input
    private var joyId = -1
    private var joyOx = 0f
    private var joyOy = 0f
    private var joyKx = 0f
    private var joyKy = 0f
    private var joyVX = 0f
    private var joyVY = 0f
    private var fireId = -1
    private var fireHeld = false
    private var swId = -1
    private var supId = -1
    private var pressedId = -1
    private var pressedPtr = -1

    init {
        unlocked = prefs.getInt("unlocked", 1).coerceIn(1, 5)
        L.ar = prefs.getBoolean("ar", true)
        sfx.enabled = prefs.getBoolean("sound", true)
        mission = unlocked
    }

    // ------------------------------------------------------------------ lifecycle
    fun resumeLoop() {
        running = true
        last = 0L
        Choreographer.getInstance().removeFrameCallback(this)
        Choreographer.getInstance().postFrameCallback(this)
    }

    fun pauseLoop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        if (state == S_PLAY && !world.waitCut && !world.dancing) state = S_PAUSE
        clearInput()
    }

    fun handleBack(): Boolean {
        when (state) {
            S_PLAY -> {
                if (!world.waitCut && !world.dancing) {
                    state = S_PAUSE
                    clearInput()
                }
            }
            S_PAUSE -> state = S_PLAY
            S_BRIEF -> go(S_SEL)
            S_SEL, S_WIN, S_OVER, S_END -> go(S_MENU)
            else -> return false
        }
        return true
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (last == 0L) last = frameTimeNanos
        var dt = (frameTimeNanos - last) / 1_000_000_000f
        last = frameTimeNanos
        if (dt > 0.05f) dt = 0.05f
        if (dt < 0f) dt = 0f
        update(dt)
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        sc = min(w / 540f, h / 800f)
        val ww = w / sc
        offX = (ww - 540f) / 2f
        wh = h / sc
        camSY = wh * 0.56f
    }

    private fun clearInput() {
        joyId = -1
        joyVX = 0f
        joyVY = 0f
        fireId = -1
        fireHeld = false
        swId = -1
        supId = -1
        pressedId = -1
        pressedPtr = -1
    }

    // ------------------------------------------------------------------ states
    private fun go(s: Int) {
        if (pendingState != -1) return
        pendingState = s
    }

    private fun applyState(s: Int) {
        clearInput()
        when (s) {
            S_PLAY -> {
                sfx.stopMusic()
                world.loadMission(mission)
                camInit = true
                curCut = 0
                state = S_PLAY
            }
            S_WIN, S_OVER, S_MENU, S_SEL, S_BRIEF -> {
                sfx.stopMusic()
                state = s
            }
            S_END -> {
                sfx.stopMusic()
                sfx.startMusic(Sfx.MUSIC_END)
                menuT = 0f
                state = S_END
            }
            else -> state = s
        }
    }

    private fun saveProgress() {
        prefs.edit().putInt("unlocked", unlocked).apply()
    }

    private fun onButton(id: Int) {
        sfx.play(Sfx.CLICK, 0.7f)
        when {
            id == B_PLAY -> {
                mission = unlocked
                go(S_BRIEF)
            }
            id == B_MISS -> go(S_SEL)
            id == B_START -> go(S_PLAY)
            id == B_SOUND -> {
                sfx.enabled = !sfx.enabled
                prefs.edit().putBoolean("sound", sfx.enabled).apply()
            }
            id == B_LANG -> {
                L.ar = !L.ar
                prefs.edit().putBoolean("ar", L.ar).apply()
            }
            id == B_BACK -> go(if (state == S_BRIEF) S_SEL else S_MENU)
            id == B_PAUSE -> {
                if (state == S_PLAY && !world.waitCut && !world.dancing) {
                    state = S_PAUSE
                    clearInput()
                }
            }
            id == B_RESUME -> state = S_PLAY
            id == B_RESTART -> go(S_PLAY)
            id == B_MENU -> go(S_MENU)
            id == B_NEXT -> {
                mission = min(5, mission + 1)
                go(S_BRIEF)
            }
            id == B_CUT -> advanceCut()
            id == B_DANCE -> world.finishDance()
            id >= B_M0 -> {
                val n = id - B_M0
                if (n in 1..unlocked) {
                    mission = n
                    go(S_BRIEF)
                }
            }
        }
    }

    private fun advanceCut() {
        if (cutTime < 0.25f) return
        val lines = Story.cut(curCut)
        cutIdx++
        cutTime = 0f
        if (cutIdx >= lines.size) {
            curCut = 0
            world.cutDone()
        }
    }

    // ------------------------------------------------------------------ input
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val action = e.actionMasked
        val idx = e.actionIndex
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                touchDown(e.getPointerId(idx), e.getX(idx) / sc - offX, e.getY(idx) / sc)
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) touchMove(e.getPointerId(i), e.getX(i) / sc - offX, e.getY(i) / sc)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                touchUp(e.getPointerId(idx), e.getX(idx) / sc - offX, e.getY(idx) / sc, false)
            MotionEvent.ACTION_CANCEL -> {
                for (i in 0 until e.pointerCount) touchUp(e.getPointerId(i), e.getX(i) / sc - offX, e.getY(i) / sc, true)
            }
        }
        return true
    }

    private fun hitAt(x: Float, y: Float): Hit? {
        var i = hits.size - 1
        while (i >= 0) {
            val h = hits[i]
            if (x >= h.l && x <= h.r && y >= h.t && y <= h.b) return h
            i--
        }
        return null
    }

    private fun fireX() = 540f - 82f
    private fun fireY() = wh - 110f
    private fun swX() = 540f - 176f
    private fun swY() = wh - 70f
    private fun supX() = 540f - 52f
    private fun supY() = wh - 236f

    private fun touchDown(ptr: Int, x: Float, y: Float) {
        if (pendingState != -1) return
        val h = hitAt(x, y)
        if (h != null) {
            if (pressedPtr == -1) {
                pressedId = h.id
                pressedPtr = ptr
            }
            return
        }
        if (state != S_PLAY || world.waitCut || world.dancing) return
        if (fireId == -1 && hypot(x - fireX(), y - fireY()) < 64f) {
            fireId = ptr
            fireHeld = true
            return
        }
        if (swId == -1 && hypot(x - swX(), y - swY()) < 44f) {
            swId = ptr
            world.switchWeapon()
            return
        }
        if (supId == -1 && hypot(x - supX(), y - supY()) < 44f) {
            supId = ptr
            world.callSupport()
            return
        }
        if (joyId == -1 && y > 80f && x < 380f) {
            joyId = ptr
            joyOx = x; joyOy = y; joyKx = x; joyKy = y
            joyVX = 0f; joyVY = 0f
        }
    }

    private fun touchMove(ptr: Int, x: Float, y: Float) {
        if (ptr == joyId) {
            var dx = x - joyOx
            var dy = y - joyOy
            val d = hypot(dx, dy)
            val rad = 56f
            if (d > rad) {
                dx = dx / d * rad
                dy = dy / d * rad
            }
            joyKx = joyOx + dx
            joyKy = joyOy + dy
            joyVX = dx / rad
            joyVY = dy / rad
        }
    }

    private fun touchUp(ptr: Int, x: Float, y: Float, cancel: Boolean) {
        if (ptr == joyId) { joyId = -1; joyVX = 0f; joyVY = 0f }
        if (ptr == fireId) { fireId = -1; fireHeld = false }
        if (ptr == swId) swId = -1
        if (ptr == supId) supId = -1
        if (ptr == pressedPtr) {
            val id = pressedId
            pressedId = -1
            pressedPtr = -1
            if (!cancel && pendingState == -1) {
                val h = hitAt(x, y)
                if (h != null && h.id == id) onButton(id)
            }
        }
    }

    // ------------------------------------------------------------------ update
    private fun update(dt: Float) {
        menuT += dt
        if (pendingState != -1) {
            fade += dt * 4.5f
            if (fade >= 1f) {
                fade = 1f
                val s = pendingState
                pendingState = -1
                applyState(s)
            }
        } else if (fade > 0f) {
            fade = max(0f, fade - dt * 3.5f)
        }
        if (state != S_PLAY) return
        cutTime += dt
        if (world.waitCut) {
            if (world.cutId != curCut) {
                curCut = world.cutId
                cutIdx = 0
                cutTime = 0f
            }
        }
        world.moveX = joyVX
        world.moveY = joyVY
        world.fireHeld = fireHeld
        world.update(dt)
        updateCamera(dt)
        if (world.dancing) {
            while (confetti.size < 140) {
                confetti.add(floatArrayOf(Random.nextFloat() * 540f, -10f - Random.nextFloat() * 200f, Random.nextFloat() * 40f - 20f, 60f + Random.nextFloat() * 90f, Random.nextInt(4).toFloat()))
            }
            var i = 0
            while (i < confetti.size) {
                val c = confetti[i]
                c[0] += c[2] * dt
                c[1] += c[3] * dt
                if (c[1] > wh + 10f) { c[1] = -10f; c[0] = Random.nextFloat() * 540f }
                i++
            }
        }
        if (pendingState != -1) return
        if (world.result == 1) {
            if (mission >= unlocked && mission < 5) {
                unlocked = mission + 1
                saveProgress()
            }
            sfx.play(Sfx.WIN, 1f)
            go(if (mission >= 5) S_END else S_WIN)
        } else if (world.result == 2) {
            sfx.play(Sfx.LOSE, 1f)
            go(S_OVER)
        }
    }

    private fun updateCamera(dt: Float) {
        val v = world.pveh
        val pl = world.player
        var tx = 450f
        var ty = 1000f
        if (v != null && !v.dead) { tx = v.x; ty = v.y }
        else if (pl != null) { tx = pl.x; ty = pl.y }
        val minX = 270f
        val maxX = max(minX, world.W - 270f)
        tx = tx.coerceIn(minX, maxX)
        // keep the player a bit below the centre so more of what lies ahead is visible
        val yMin = camSY / KY - 60f
        val yMax = world.D - (wh - camSY) / KY + 60f
        ty = if (yMin > yMax) (yMin + yMax) / 2f else (ty - 60f).coerceIn(yMin, yMax)
        if (camInit) {
            camX = tx; camY = ty; camInit = false
        } else {
            val k = min(1f, dt * 6f)
            camX += (tx - camX) * k
            camY += (ty - camY) * k
        }
    }

    // ------------------------------------------------------------------ helpers
    private fun fillRect(c: Canvas, l: Float, tt: Float, r: Float, b: Float, color: Int, alpha: Float = 1f) {
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = color
        p.alpha = (Color.alpha(color) * alpha).toInt().coerceIn(0, 255)
        c.drawRect(l, tt, r, b, p)
    }

    private fun fullRect(c: Canvas, color: Int, alpha: Float) {
        fillRect(c, -offX - 4f, -4f, 544f + offX, wh + 4f, color, alpha)
    }

    private fun txt(
        c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int,
        align: Paint.Align = Paint.Align.CENTER, alpha: Float = 1f, shadow: Boolean = true
    ) {
        tp.shader = null
        tp.textSize = size
        tp.typeface = Typeface.DEFAULT_BOLD
        tp.textAlign = align
        tp.letterSpacing = 0f
        if (shadow) {
            tp.color = Color.BLACK
            tp.alpha = (150 * alpha).toInt().coerceIn(0, 255)
            c.drawText(s, x + 1.5f, y + 2.2f, tp)
        }
        tp.color = color
        tp.alpha = (Color.alpha(color) * alpha).toInt().coerceIn(0, 255)
        c.drawText(s, x, y, tp)
    }

    private fun fitTxt(c: Canvas, s: String, x: Float, y: Float, size: Float, maxW: Float, color: Int) {
        tp.textSize = size
        tp.typeface = Typeface.DEFAULT_BOLD
        val w = tp.measureText(s)
        val k = if (w > maxW) size * maxW / w else size
        txt(c, s, x, y, k, color)
    }

    private fun wrapLines(s: String, size: Float, maxW: Float): ArrayList<String> {
        tp.textSize = size
        tp.typeface = Typeface.DEFAULT_BOLD
        val out = ArrayList<String>()
        for (para in s.split("\n")) {
            if (para.isEmpty()) { out.add(""); continue }
            var cur = ""
            for (w in para.split(" ")) {
                val t = if (cur.isEmpty()) w else cur + " " + w
                if (tp.measureText(t) > maxW && cur.isNotEmpty()) {
                    out.add(cur)
                    cur = w
                } else {
                    cur = t
                }
            }
            if (cur.isNotEmpty()) out.add(cur)
        }
        return out
    }

    private fun paragraph(c: Canvas, s: String, cx: Float, y: Float, size: Float, maxW: Float, color: Int): Float {
        val lines = wrapLines(s, size, maxW)
        var yy = y
        for (ln in lines) {
            if (ln.isNotEmpty()) txt(c, ln, cx, yy, size, color)
            yy += size * 1.45f
        }
        return yy
    }

    private fun heart(c: Canvas, x: Float, y: Float, s: Float, color: Int) {
        pth.reset()
        pth.moveTo(x, y + s * 0.95f)
        pth.cubicTo(x - s * 1.5f, y + s * 0.1f, x - s * 0.85f, y - s * 0.95f, x, y - s * 0.3f)
        pth.cubicTo(x + s * 0.85f, y - s * 0.95f, x + s * 1.5f, y + s * 0.1f, x, y + s * 0.95f)
        pth.close()
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = color
        p.alpha = 255
        c.drawPath(pth, p)
    }

    private fun star(c: Canvas, x: Float, y: Float, rad: Float, color: Int) {
        pth.reset()
        for (i in 0 until 10) {
            val rr = if (i % 2 == 0) rad else rad * 0.45f
            val a = (-90f + i * 36f) * PI_F / 180f
            val px = x + cos(a) * rr
            val py = y + sin(a) * rr
            if (i == 0) pth.moveTo(px, py) else pth.lineTo(px, py)
        }
        pth.close()
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = color
        p.alpha = 255
        c.drawPath(pth, p)
    }

    private fun bar(c: Canvas, x: Float, y: Float, w: Float, h: Float, frac: Float, color: Int) {
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = Color.BLACK
        p.alpha = 130
        rf.set(x, y, x + w, y + h)
        c.drawRoundRect(rf, h / 2f, h / 2f, p)
        val f = frac.coerceIn(0f, 1f)
        if (f > 0.01f) {
            p.color = color
            p.alpha = 255
            rf.set(x, y, x + max(h, w * f), y + h)
            c.drawRoundRect(rf, h / 2f, h / 2f, p)
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.2f
        p.color = Color.WHITE
        p.alpha = 90
        rf.set(x, y, x + w, y + h)
        c.drawRoundRect(rf, h / 2f, h / 2f, p)
    }

    /** kind 0 = gold primary, 1 = dark secondary, 2 = red */
    private fun button(
        c: Canvas, id: Int, cx: Float, cy: Float, w: Float, h: Float,
        label: String, kind: Int, size: Float = 24f, enabled: Boolean = true
    ) {
        val pressed = pressedId == id && enabled
        val s = if (pressed) 0.95f else 1f
        c.save()
        c.scale(s, s, cx, cy)
        val rad = h / 2f
        rf.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = Color.BLACK
        p.alpha = 90
        rf.offset(0f, 4f)
        c.drawRoundRect(rf, rad, rad, p)
        rf.offset(0f, -4f)
        var top = Color.rgb(255, 198, 66)
        var bot = Color.rgb(232, 112, 30)
        var tc = Color.rgb(54, 22, 6)
        if (kind == 1) {
            top = Color.rgb(78, 78, 98); bot = Color.rgb(40, 40, 54); tc = Color.WHITE
        } else if (kind == 2) {
            top = Color.rgb(226, 74, 64); bot = Color.rgb(150, 30, 36); tc = Color.WHITE
        }
        p.shader = LinearGradient(0f, rf.top, 0f, rf.bottom, top, bot, Shader.TileMode.CLAMP)
        p.alpha = if (enabled) 255 else 110
        c.drawRoundRect(rf, rad, rad, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f
        p.color = Color.WHITE
        p.alpha = if (kind == 0) 130 else 70
        c.drawRoundRect(rf, rad, rad, p)
        tp.textSize = size
        tp.typeface = Typeface.DEFAULT_BOLD
        val tw = tp.measureText(label)
        val fs = if (tw > w - 24f) size * (w - 24f) / tw else size
        txt(c, label, cx, cy + fs * 0.35f, fs, tc, shadow = kind != 0, alpha = if (enabled) 1f else 0.5f)
        c.restore()
        if (enabled) hits.add(Hit(id, cx - w / 2f - 4f, cy - h / 2f - 4f, cx + w / 2f + 4f, cy + h / 2f + 4f))
    }

    private fun panel(c: Canvas, cx: Float, cy: Float, w: Float, h: Float) {
        rf.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, rf.top, 0f, rf.bottom, Color.rgb(42, 36, 56), Color.rgb(20, 18, 30), Shader.TileMode.CLAMP)
        p.alpha = 245
        c.drawRoundRect(rf, 26f, 26f, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2.5f
        p.color = Color.rgb(255, 196, 80)
        p.alpha = 150
        c.drawRoundRect(rf, 26f, 26f, p)
    }

    private fun drawTitle(c: Canvas, cx: Float, y: Float, size: Float) {
        tp.textSize = size
        tp.typeface = Typeface.DEFAULT_BOLD
        tp.textAlign = Paint.Align.CENTER
        tp.letterSpacing = 0f
        tp.shader = null
        tp.color = Color.BLACK
        tp.alpha = 170
        c.drawText("AboLolo", cx + 3f, y + 5f, tp)
        tp.shader = LinearGradient(
            0f, y - size * 0.75f, 0f, y + size * 0.05f,
            Color.rgb(255, 232, 130), Color.rgb(240, 110, 30), Shader.TileMode.CLAMP
        )
        tp.alpha = 255
        c.drawText("AboLolo", cx, y, tp)
        tp.shader = null
    }

    private fun weaponName(w: Int): String = when (w) {
        0 -> L.t("مسدس", "Pistol")
        1 -> "AK-47"
        2 -> "G3"
        3 -> "Magnus"
        else -> L.t("آر بي جي", "RPG")
    }

    // ------------------------------------------------------------------ world projection
    private fun sxf(x: Float): Float = x - camX + 270f
    private fun syf(y: Float): Float = (y - camY) * KY + camSY

    // ------------------------------------------------------------------ drawing
    override fun onDraw(canvas: Canvas) {
        hits.clear()
        canvas.drawColor(Color.BLACK)
        canvas.save()
        canvas.scale(sc, sc)
        canvas.translate(offX, 0f)
        when (state) {
            S_MENU -> drawMenu(canvas)
            S_SEL -> drawSelect(canvas)
            S_BRIEF -> drawBrief(canvas)
            S_PLAY -> drawPlay(canvas)
            S_PAUSE -> { drawWorld(canvas); drawHud(canvas); drawPause(canvas) }
            S_WIN -> { drawWorld(canvas); drawWin(canvas) }
            S_OVER -> { drawWorld(canvas); drawOver(canvas) }
            S_END -> drawEnd(canvas)
        }
        if (fade > 0f) fullRect(canvas, Color.BLACK, fade)
        canvas.restore()
    }

    private fun drawBackdrop(c: Canvas) {
        p.shader = LinearGradient(0f, 0f, 0f, wh, Color.rgb(24, 22, 40), Color.rgb(140, 96, 52), Shader.TileMode.CLAMP)
        p.style = Paint.Style.FILL
        p.alpha = 255
        c.drawRect(-offX - 4f, -4f, 544f + offX, wh + 4f, p)
        p.shader = null
        // dunes
        p.color = Color.rgb(96, 68, 40)
        p.alpha = 255
        pth.reset()
        pth.moveTo(-offX - 4f, wh * 0.78f)
        var x = -offX - 4f
        while (x <= 544f + offX) {
            pth.lineTo(x, wh * 0.78f - 26f * sin(x * 0.013f + 1f) - 12f * sin(x * 0.04f))
            x += 12f
        }
        pth.lineTo(544f + offX, wh + 4f)
        pth.lineTo(-offX - 4f, wh + 4f)
        pth.close()
        c.drawPath(pth, p)
    }

    private fun drawMenu(c: Canvas) {
        drawBackdrop(c)
        val ph = menuT * 3f
        Sprites.soldier(c, 150f, wh * 0.74f, 2.6f, Looks.SAVANNA, ph, false, 1f, 0f, 1, 0f, 0f, 1f)
        Sprites.soldier(c, 400f, wh * 0.76f, 2.6f, Looks.QUBBA, ph, false, -1f, 0f, 2, 0f, 0f, 1f)
        Sprites.vehicle(c, 270f, wh * 0.84f, 1.5f, 1, true, menuT, 1f, -0.3f, 0f, 0f)
        drawTitle(c, 270f, wh * 0.2f, 92f)
        txt(c, L.t("معارك تكتيكية", "Tactical Combat"), 270f, wh * 0.2f + 40f, 24f, Color.rgb(255, 226, 160))
        val by = wh * 0.34f
        button(c, B_PLAY, 270f, by, 300f, 64f, if (unlocked > 1) L.t("تابع - المعركة $unlocked", "Continue - Battle $unlocked") else L.t("ابدأ", "Start"), 0, 26f)
        button(c, B_MISS, 270f, by + 82f, 300f, 56f, L.t("المعارك", "Battles"), 1, 22f)
        button(c, B_SOUND, 270f - 80f, by + 156f, 140f, 46f, if (sfx.enabled) L.t("الصوت: تشغيل", "Sound: On") else L.t("الصوت: إيقاف", "Sound: Off"), 1, 17f)
        button(c, B_LANG, 270f + 80f, by + 156f, 140f, 46f, if (L.ar) "English" else "العربية", 1, 17f)
    }

    private fun drawSelect(c: Canvas) {
        drawBackdrop(c)
        txt(c, L.t("المعارك", "Battles"), 270f, 66f, 38f, Color.rgb(255, 226, 160))
        val top = 120f
        val step = min(120f, (wh - 240f) / 5f)
        for (n in 1..5) {
            val y = top + (n - 1) * step + step * 0.45f
            val ok = n <= unlocked
            panel(c, 270f, y, 470f, step - 12f)
            txt(c, "$n", 70f, y + 14f, 44f, if (ok) Color.rgb(255, 196, 80) else Color.GRAY)
            fitTxt(c, Story.missionTitle(n), 290f, y - 4f, 24f, 300f, if (ok) Color.WHITE else Color.GRAY)
            fitTxt(c, Story.missionHero(n), 290f, y + 24f, 17f, 300f, Color.rgb(200, 190, 170))
            if (ok) {
                hits.add(Hit(B_M0 + n, 35f, y - step / 2f, 505f, y + step / 2f))
            }
            if (!ok) txt(c, L.t("مقفلة", "Locked"), 470f, y + 8f, 16f, Color.GRAY)
        }
        button(c, B_BACK, 270f, wh - 50f, 220f, 52f, L.t("رجوع", "Back"), 1, 20f)
    }

    private fun drawBrief(c: Canvas) {
        drawBackdrop(c)
        val ph = menuT * 3f
        panel(c, 270f, wh * 0.46f, 500f, wh * 0.76f)
        txt(c, L.t("المعركة", "Battle") + " " + mission, 270f, wh * 0.46f - wh * 0.38f + 44f, 20f, Color.rgb(255, 196, 80))
        txt(c, Story.missionTitle(mission), 270f, wh * 0.46f - wh * 0.38f + 82f, 32f, Color.WHITE)
        txt(c, Story.missionHero(mission), 270f, wh * 0.46f - wh * 0.38f + 112f, 18f, Color.rgb(200, 190, 170))
        val lk = when (mission) {
            2, 4 -> Looks.QUBBA
            3 -> Looks.APOLLO
            else -> Looks.SAVANNA
        }
        Sprites.soldier(c, 270f, wh * 0.46f - wh * 0.38f + 200f, 1.5f, lk, ph, false, 1f, 0f, 1, 0f, 0f, 1f)
        paragraph(c, Story.brief(mission), 270f, wh * 0.46f - wh * 0.38f + 240f, 17f, 440f, Color.WHITE)
        button(c, B_START, 270f, wh * 0.46f + wh * 0.38f - 36f, 280f, 58f, L.t("انطلق", "Go"), 0, 26f)
        button(c, B_BACK, 76f, 40f, 110f, 42f, L.t("رجوع", "Back"), 1, 16f)
    }

    private fun drawPlay(c: Canvas) {
        if (world.dancing) {
            drawDance(c)
            return
        }
        drawWorld(c)
        drawHud(c)
        if (world.waitCut) drawCut(c) else drawControls(c)
    }

    // ---- terrain ----
    private fun drawGround(c: Canvas) {
        val x0 = sxf(0f)
        val x1 = sxf(world.W)
        val y0 = syf(0f)
        val y1 = syf(world.D)
        fillRect(c, -offX - 4f, -4f, 544f + offX, wh + 4f, Color.rgb(78, 66, 54))
        p.shader = LinearGradient(0f, max(-4f, y0), 0f, min(wh + 4f, y1), Color.rgb(214, 186, 134), Color.rgb(194, 160, 108), Shader.TileMode.CLAMP)
        p.style = Paint.Style.FILL
        p.alpha = 255
        c.drawRect(max(-offX - 4f, x0), max(-4f, y0), min(544f + offX, x1), min(wh + 4f, y1), p)
        p.shader = null
        // stones and tufts
        val cell = 70f
        val wx0 = camX - 270f - offX - 40f
        val wx1 = camX + 270f + offX + 40f
        val wy0 = camY - camSY / KY - 40f
        val wy1 = camY + (wh - camSY) / KY + 40f
        var cx = floor(wx0 / cell).toInt()
        val cxe = floor(wx1 / cell).toInt()
        while (cx <= cxe) {
            var cy = floor(wy0 / cell).toInt()
            val cye = floor(wy1 / cell).toInt()
            while (cy <= cye) {
                val h = (cx * 73856093) xor (cy * 19349663)
                val m = (h and 0x7fffffff) % 7
                val wx = cx * cell + ((h shr 3) and 63).toFloat()
                val wy = cy * cell + ((h shr 9) and 63).toFloat()
                if (wx > 0f && wx < world.W && wy > 0f && wy < world.D) {
                    val px = sxf(wx)
                    val py = syf(wy)
                    if (m == 0 || m == 1) {
                        p.style = Paint.Style.FILL
                        p.color = Color.rgb(140, 124, 104)
                        p.alpha = 255
                        c.drawOval(px - 7f, py - 3.5f, px + 7f, py + 3.5f, p)
                        p.color = Color.rgb(176, 160, 138)
                        c.drawOval(px - 5f, py - 4.5f, px + 4f, py + 1f, p)
                    } else if (m == 2) {
                        p.style = Paint.Style.STROKE
                        p.strokeWidth = 1.6f
                        p.color = Color.rgb(110, 120, 70)
                        p.alpha = 255
                        c.drawLine(px, py, px - 3f, py - 7f, p)
                        c.drawLine(px, py, px + 3f, py - 8f, p)
                        c.drawLine(px, py, px, py - 6f, p)
                    } else if (m == 3) {
                        p.style = Paint.Style.FILL
                        p.color = Color.rgb(184, 154, 106)
                        p.alpha = 160
                        c.drawOval(px - 18f, py - 5f, px + 18f, py + 5f, p)
                    }
                }
                cy++
            }
            cx++
        }
        // world edge
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f
        p.color = Color.rgb(90, 70, 50)
        p.alpha = 200
        c.drawRect(x0, y0, x1, y1, p)
    }

    private fun addDI(y: Float, k: Int, o: Any?) {
        val idx = drawList.size
        val d: DI
        if (idx < diPool.size) {
            d = diPool[idx]
            d.y = y; d.k = k; d.o = o
        } else {
            d = DI(y, k, o)
            diPool.add(d)
        }
        drawList.add(d)
    }

    private fun gunOf(s: Soldier): Int = if (!s.armed) 6 else s.weapon

    private fun drawWorld(c: Canvas) {
        c.save()
        if (world.shake > 0f) c.translate((Random.nextFloat() - 0.5f) * world.shake * 12f, (Random.nextFloat() - 0.5f) * world.shake * 12f)
        drawWorldInner(c)
        c.restore()
        if (world.hurtV > 0f) fullRect(c, Color.rgb(200, 0, 0), min(0.4f, world.hurtV * 0.5f))
    }

    private fun drawWorldInner(c: Canvas) {
        drawGround(c)
        val t = world.t
        // shells (targets on the ground)
        for (s in world.shells) {
            val px = sxf(s.x)
            val py = syf(s.y)
            if (py < -80f || py > wh + 80f) continue
            val pulse = 0.5f + 0.5f * sin(t * 12f)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2.5f
            p.color = if (s.srcSide == 0) Color.rgb(255, 230, 90) else Color.rgb(255, 70, 60)
            p.alpha = (110 + 120 * pulse).toInt()
            c.drawOval(px - s.r, py - s.r * KY, px + s.r, py + s.r * KY, p)
            if (s.t < 1.4f) {
                val z = s.t * 280f
                p.style = Paint.Style.FILL
                p.color = Color.rgb(40, 40, 44)
                p.alpha = 255
                c.drawCircle(px, py - z, 4f, p)
                p.color = Color.rgb(255, 160, 40)
                c.drawCircle(px, py - z - 5f, 2.4f, p)
            }
        }
        // depth sorted things
        drawList.clear()
        for (b in world.buildings) addDI(b.y + b.d / 2f, 2, b)
        for (cv in world.covers) addDI(cv.y + cv.d / 2f, 3, cv)
        for (s in world.soldiers) if (!s.inVeh) addDI(s.y, 0, s)
        for (v in world.vehicles) addDI(v.y, 1, v)
        java.util.Collections.sort(drawList, diCmp)
        for (d in drawList) {
            val o = d.o
            when (d.k) {
                0 -> drawSoldier(c, o as Soldier)
                1 -> drawVehicle(c, o as Vehicle)
                2 -> drawBuilding(c, o as Building, t)
                else -> drawCoverPiece(c, o as Cover)
            }
        }
        // player marker
        val pl = world.player
        if (pl != null && !pl.dead) {
            val px = sxf(pl.x)
            val py = syf(pl.y)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2.2f
            p.color = Color.rgb(120, 255, 140)
            p.alpha = 220
            c.drawOval(px - 20f, py - 8f, px + 20f, py + 8f, p)
        }
        // bullets
        for (b in world.bullets) {
            val px = sxf(b.x)
            val py = syf(b.y) - 18f
            if (px < -40f || px > 580f || py < -40f || py > wh + 40f) continue
            val ex = px - b.vx * 0.03f
            val ey = py - b.vy * KY * 0.03f
            p.style = Paint.Style.STROKE
            if (b.kind == 1) {
                p.strokeWidth = 3.4f
                p.color = Color.rgb(255, 190, 70)
            } else if (b.kind == 2) {
                p.strokeWidth = 4.5f
                p.color = Color.rgb(255, 130, 50)
            } else {
                p.strokeWidth = 2.2f
                p.color = if (World.sideOf(b.team) == 0) Color.rgb(255, 240, 150) else Color.rgb(255, 120, 100)
            }
            p.alpha = 255
            c.drawLine(ex, ey, px, py, p)
        }
        // aircraft and their shadows
        for (a in world.planes) {
            val px = sxf(a.x)
            val py = syf(a.y)
            p.style = Paint.Style.FILL
            p.color = Color.BLACK
            p.alpha = 70
            c.drawOval(px - 40f, py - 6f, px + 40f, py + 6f, p)
        }
        for (a in world.planes) {
            Sprites.plane(c, sxf(a.x), syf(a.y) - 120f, 1.3f, a.vx > 0f, a.flash, t)
        }
        // explosions, particles
        for (q in world.parts) {
            val px = sxf(q.x)
            val py = syf(q.y) - q.z
            val f = q.life / q.maxLife
            p.style = Paint.Style.FILL
            p.color = q.color
            if (q.kind == 1) {
                p.alpha = (150 * f).toInt().coerceIn(0, 255)
                c.drawCircle(px, py, q.size * (1.8f - f), p)
            } else {
                p.alpha = (255 * min(1f, f * 2f)).toInt().coerceIn(0, 255)
                if (q.kind == 3) c.drawRect(px - q.size * 0.5f, py - q.size * 0.5f, px + q.size * 0.5f, py + q.size * 0.5f, p)
                else c.drawCircle(px, py, q.size, p)
            }
        }
        for (b in world.booms) {
            val f = b.t / 0.6f
            val r = b.r * (0.4f + f * 0.8f)
            val px = sxf(b.x)
            val py = syf(b.y) - 10f
            p.style = Paint.Style.FILL
            p.color = Color.rgb(255, 150, 40)
            p.alpha = (230 * (1f - f)).toInt().coerceIn(0, 255)
            c.drawOval(px - r, py - r * 0.8f, px + r, py + r * 0.8f, p)
            p.color = Color.rgb(255, 240, 170)
            p.alpha = (255 * (1f - f * 1.6f)).toInt().coerceIn(0, 255)
            c.drawOval(px - r * 0.55f, py - r * 0.45f, px + r * 0.55f, py + r * 0.45f, p)
        }
    }

    private fun drawSoldier(c: Canvas, s: Soldier) {
        val px = sxf(s.x)
        val py = syf(s.y)
        if (px < -60f || px > 600f || py < -80f || py > wh + 80f) return
        val alpha = if (s.dead) max(0f, s.deadT / 0.6f) else 1f
        var ax = s.aimX
        var ay = s.aimY * KY
        val l = max(0.001f, hypot(ax, ay))
        ax /= l; ay /= l
        Sprites.soldier(c, px, py, 1f, s.look, s.phase, s.moving, ax, ay, gunOf(s), s.muzzle, s.flash, alpha)
        if (!s.dead && s.tagAr.isNotEmpty()) {
            txt(c, L.t(s.tagAr, s.tagEn), px, py - 62f, 14f, if (s.isPlayer) Color.rgb(140, 255, 150) else Color.rgb(255, 226, 160))
        }
        if (!s.dead && s.expert) {
            bar(c, px - 14f, py - 52f, 28f, 5f, s.hp / s.maxHp, Color.rgb(255, 170, 60))
        }
    }

    private fun drawVehicle(c: Canvas, v: Vehicle) {
        val px = sxf(v.x)
        val py = syf(v.y)
        if (px < -80f || px > 620f || py < -80f || py > wh + 100f) return
        if (v.dead) {
            if (v.deadT > 0f) Sprites.vehicle(c, px, py, 1f, v.type, v.faceR, v.wheel, 1f, 0f, 0f, 1f)
            return
        }
        var ax = v.aimX
        var ay = v.aimY * KY
        val l = max(0.001f, hypot(ax, ay))
        ax /= l; ay /= l
        Sprites.vehicle(c, px, py, 1f, v.type, v.faceR, v.wheel, ax, ay, v.muzzle, v.flash)
        if (v.hp < v.maxHp || v.isPlayer) bar(c, px - 26f, py - 52f, 52f, 6f, v.hp / v.maxHp, if (v.isPlayer) Color.rgb(120, 230, 130) else Color.rgb(230, 80, 70))
    }

    private fun drawBuilding(c: Canvas, b: Building, t: Float) {
        val l = sxf(b.x - b.w / 2f)
        val r = sxf(b.x + b.w / 2f)
        val back = syf(b.y - b.d / 2f)
        val front = syf(b.y + b.d / 2f)
        if (r < -60f || l > 600f || front < -20f || back - b.h > wh + 60f) return
        if (b.dead && b.side == 1) {
            Sprites.box(c, l, r, back, front, b.h * 0.14f, Color.rgb(70, 66, 62), Color.rgb(54, 50, 48), Color.rgb(34, 32, 30))
            p.style = Paint.Style.FILL
            p.color = Color.rgb(50, 50, 52)
            p.alpha = (110 + 60 * sin(t * 3f)).toInt()
            val cx = (l + r) / 2f
            c.drawCircle(cx, front - 24f - 8f * sin(t * 2f), 18f, p)
            return
        }
        val frac = if (b.side == 1) b.hp / b.maxHp else 1f
        Sprites.building(c, b.type, l, r, back, front, b.h, frac, t, b.flash)
    }

    private fun drawCoverPiece(c: Canvas, cv: Cover) {
        val l = sxf(cv.x - cv.w / 2f)
        val r = sxf(cv.x + cv.w / 2f)
        val back = syf(cv.y - cv.d / 2f)
        val front = syf(cv.y + cv.d / 2f)
        if (r < -40f || l > 580f || front < -10f || back > wh + 60f) return
        Sprites.cover(c, l, r, back, front, cv.h, cv.kind)
    }

    // ---- HUD ----
    private fun targetPos(): FloatArray? {
        return when (world.objId) {
            1, 2 -> floatArrayOf(world.tankX(), world.tankY() + 60f)
            3 -> if (world.phase == 0) floatArrayOf(world.surveyX(), world.surveyY() + 30f) else null
            7, 9 -> floatArrayOf(world.goalX(), world.goalY())
            4, 10 -> {
                val pl = world.player
                var best: FloatArray? = null
                var bd = 1e9f
                if (pl != null) {
                    for (b in world.buildings) {
                        if (b.type == 1 && b.side == 1 && !b.dead) {
                            val d = hypot(b.x - pl.x, b.y - pl.y)
                            if (d < bd) { bd = d; best = floatArrayOf(b.x, b.y) }
                        }
                    }
                }
                best
            }
            else -> null
        }
    }

    private fun drawHud(c: Canvas) {
        val pl = world.player ?: return
        // objective
        val ob = Story.objective(world.objId)
        rf.set(10f, 8f, 470f, 62f)
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = Color.BLACK
        p.alpha = 110
        c.drawRoundRect(rf, 14f, 14f, p)
        fitTxt(c, ob, 240f, 30f, 19f, 430f, Color.rgb(255, 226, 160))
        var sub = ""
        var frac = -1f
        when (world.objId) {
            2, 3 -> frac = world.progress
            5, 6, 8, 11, 4 -> sub = L.t("الأعداء: ", "Enemies: ") + world.remaining()
            10 -> sub = L.t("غرف التحكم: ", "Control rooms: ") + world.controlRoomsLeft() + "   " + L.t("الأعداء: ", "Enemies: ") + world.remaining()
            7, 9 -> sub = (world.distToGoal() / 10f).toInt().toString() + " m"
            else -> {}
        }
        if (world.objId == 3) sub = L.t("الخبراء: ", "Experts: ") + world.expertsAlive()
        if (frac >= 0f) bar(c, 20f, 44f, 200f, 10f, frac, Color.rgb(120, 220, 140))
        if (sub.isNotEmpty()) txt(c, sub, if (frac >= 0f) 340f else 240f, 54f, 15f, Color.WHITE)
        button(c, B_PAUSE, 506f, 36f, 52f, 46f, "II", 1, 22f)
        // health
        val v = world.pveh
        if (v != null && !v.dead) {
            bar(c, 16f, 74f, 180f, 12f, v.hp / v.maxHp, Color.rgb(120, 220, 130))
            txt(c, L.t("المركبة - مدفع 23 ملم", "Vehicle - 23mm cannon"), 204f, 85f, 14f, Color.WHITE, Paint.Align.LEFT)
        } else {
            bar(c, 16f, 74f, 180f, 12f, pl.hp / pl.maxHp, if (pl.hp / pl.maxHp > 0.35f) Color.rgb(120, 220, 130) else Color.rgb(230, 70, 60))
            val w = world.weapon
            val rl = world.reloadT
            val am = if (rl > 0f) L.t("تعبئة...", "reloading...") else world.ammo[w].toString() + "/" + World.W_MAG[w]
            txt(c, weaponName(w) + "  " + am, 204f, 85f, 14f, Color.WHITE, Paint.Align.LEFT)
        }
        // hearts at the trench
        if (world.objId == 5) {
            for (i in 0 until 6) heart(c, 26f + i * 26f, 108f, 9f, if (i < world.hearts) Color.rgb(230, 60, 70) else Color.rgb(80, 60, 66))
        }
        // arrow to the goal
        val tg = targetPos()
        if (tg != null && !world.waitCut) {
            val sx = sxf(pl.x)
            val sy = syf(pl.y) - 20f
            val dx = sxf(tg[0]) - sx
            val dy = (syf(tg[1]) - 20f) - sy
            val d = hypot(dx, dy)
            if (d > 170f) {
                val ux = dx / d
                val uy = dy / d
                val ax = sx + ux * 90f
                val ay = sy + uy * 90f
                pth.reset()
                pth.moveTo(ax + ux * 14f, ay + uy * 14f)
                pth.lineTo(ax - ux * 8f - uy * 10f, ay - uy * 8f + ux * 10f)
                pth.lineTo(ax - ux * 8f + uy * 10f, ay - uy * 8f - ux * 10f)
                pth.close()
                p.style = Paint.Style.FILL
                p.color = Color.rgb(255, 214, 70)
                p.alpha = 230
                c.drawPath(pth, p)
            } else {
                val bob = 4f * sin(world.t * 6f)
                pth.reset()
                pth.moveTo(sx + dx, sy + dy - 50f + bob)
                pth.lineTo(sx + dx - 9f, sy + dy - 68f + bob)
                pth.lineTo(sx + dx + 9f, sy + dy - 68f + bob)
                pth.close()
                p.style = Paint.Style.FILL
                p.color = Color.rgb(255, 214, 70)
                p.alpha = 230
                c.drawPath(pth, p)
            }
        }
    }

    private fun drawControls(c: Canvas) {
        // joystick
        if (joyId != -1) {
            p.style = Paint.Style.FILL
            p.shader = null
            p.color = Color.WHITE
            p.alpha = 40
            c.drawCircle(joyOx, joyOy, 58f, p)
            p.alpha = 110
            c.drawCircle(joyKx, joyKy, 26f, p)
        } else {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2f
            p.color = Color.WHITE
            p.alpha = 50
            c.drawCircle(100f, wh - 120f, 54f, p)
            txt(c, L.t("حرّك", "Move"), 100f, wh - 114f, 16f, Color.WHITE, alpha = 0.5f)
        }
        // fire
        val fx = fireX()
        val fy = fireY()
        p.style = Paint.Style.FILL
        p.color = if (fireHeld) Color.rgb(255, 110, 70) else Color.rgb(210, 60, 50)
        p.alpha = 190
        c.drawCircle(fx, fy, 56f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 3f
        p.color = Color.WHITE
        p.alpha = 170
        c.drawCircle(fx, fy, 56f, p)
        txt(c, L.t("نار", "FIRE"), fx, fy + 8f, 22f, Color.WHITE)
        // weapon switch
        if (world.pveh == null) {
            p.style = Paint.Style.FILL
            p.color = Color.rgb(50, 50, 70)
            p.alpha = 200
            c.drawCircle(swX(), swY(), 38f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 2f
            p.color = Color.WHITE
            p.alpha = 150
            c.drawCircle(swX(), swY(), 38f, p)
            txt(c, weaponName((world.weapon + 1) % World.NW), swX(), swY() + 5f, 13f, Color.WHITE)
        }
        // fire support
        val sx = supX()
        val sy = supY()
        val ready = world.supportCd <= 0f
        p.style = Paint.Style.FILL
        p.color = if (ready) Color.rgb(220, 160, 40) else Color.rgb(60, 56, 50)
        p.alpha = 210
        c.drawCircle(sx, sy, 38f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2.5f
        p.color = Color.WHITE
        p.alpha = 160
        c.drawCircle(sx, sy, 38f, p)
        if (ready) txt(c, L.t("إسناد", "Fire"), sx, sy + 6f, 17f, Color.rgb(50, 24, 6), shadow = false)
        else txt(c, world.supportCd.toInt().toString(), sx, sy + 8f, 22f, Color.WHITE)
    }

    private fun drawCut(c: Canvas) {
        val lines = Story.cut(curCut)
        if (lines.isEmpty() || cutIdx >= lines.size) return
        val ln = lines[cutIdx]
        val key = ln[0]
        fullRect(c, Color.BLACK, 0.35f)
        val py = wh - 250f
        panel(c, 270f, py + 90f, 510f, 210f)
        val lk: Look? = when (key) {
            "savanna" -> Looks.SAVANNA
            "qubba" -> Looks.QUBBA
            "apollo" -> Looks.APOLLO
            "elite" -> Looks.ELITE_UNI
            "defector" -> Looks.DEFECTOR
            "falan" -> Looks.FALAN
            "dinari" -> Looks.DINARI
            else -> null
        }
        if (lk != null) Sprites.soldier(c, 74f, py + 70f, 1.6f, lk, 0f, false, 1f, 0f, 6, 0f, 0f, 1f)
        txt(c, Story.speaker(key), 300f, py + 4f, 20f, Color.rgb(255, 196, 80))
        paragraph(c, L.t(ln[1], ln[2]), 300f, py + 34f, 18f, 360f, Color.WHITE)
        txt(c, L.t("المس للمتابعة", "Tap to continue"), 270f, py + 188f, 14f, Color.rgb(200, 190, 170))
        hits.add(Hit(B_CUT, -offX - 10f, -10f, 550f + offX, wh + 10f))
    }

    private fun drawDance(c: Canvas) {
        drawBackdrop(c)
        val m = world.mission
        val ph = world.sceneT / BEAT * PI_F
        val sn = sin(ph)
        val list: Array<Look> = if (m == 4) arrayOf(Looks.SAVANNA, Looks.ELITE_SUIT, Looks.QUBBA, Looks.ELITE_UNI, Looks.JANJA_HELMET, Looks.ELITE_SUIT)
        else arrayOf(Looks.SAVANNA, Looks.DEFECTOR, Looks.QUBBA, Looks.JANJA, Looks.DINARI, Looks.DEFECTOR)
        val baseY = wh * 0.7f
        for (i in list.indices) {
            val x = 60f + i * 84f
            val alt = if (i % 2 == 0) sn else -sn
            val up = 0.5f + 0.5f * alt
            val y = baseY + (i % 2) * 46f
            Sprites.soldier(c, x, y, 1.25f, list[i], 0f, false, 1f, 0f, 6, 0f, 0f, 1f, up, max(0f, alt), max(0f, -alt), abs(alt) * 5f)
        }
        txt(c, L.t("النصر!", "Victory!"), 270f, wh * 0.18f, 54f, Color.rgb(255, 214, 90))
        txt(c, if (m == 4) L.t("احتفال دار صباح", "Dar Sabah celebrates") else L.t("احتفال السلام", "Celebration of peace"), 270f, wh * 0.18f + 36f, 22f, Color.WHITE)
        for (f in confetti) {
            p.style = Paint.Style.FILL
            p.color = confCols[f[4].toInt().coerceIn(0, 3)]
            p.alpha = 255
            c.drawRect(f[0], f[1], f[0] + 6f, f[1] + 9f, p)
        }
        if (world.sceneT > 12f) button(c, B_DANCE, 270f, wh - 60f, 260f, 56f, L.t("متابعة", "Continue"), 0, 24f)
    }

    private fun drawPause(c: Canvas) {
        fullRect(c, Color.BLACK, 0.55f)
        panel(c, 270f, wh / 2f, 360f, 330f)
        txt(c, L.t("إيقاف مؤقت", "Paused"), 270f, wh / 2f - 100f, 34f, Color.WHITE)
        button(c, B_RESUME, 270f, wh / 2f - 30f, 260f, 56f, L.t("استمر", "Resume"), 0, 24f)
        button(c, B_RESTART, 270f, wh / 2f + 40f, 260f, 52f, L.t("إعادة المعركة", "Restart"), 1, 20f)
        button(c, B_MENU, 270f, wh / 2f + 104f, 260f, 52f, L.t("القائمة", "Menu"), 1, 20f)
    }

    private fun drawWin(c: Canvas) {
        fullRect(c, Color.BLACK, 0.6f)
        panel(c, 270f, wh / 2f, 460f, 420f)
        txt(c, L.t("نصر!", "Victory!"), 270f, wh / 2f - 140f, 44f, Color.rgb(255, 214, 90))
        for (i in 0 until 3) star(c, 190f + i * 80f, wh / 2f - 90f, 26f, Color.rgb(255, 200, 60))
        paragraph(c, Story.winText(mission), 270f, wh / 2f - 30f, 20f, 400f, Color.WHITE)
        button(c, B_NEXT, 270f, wh / 2f + 100f, 280f, 58f, L.t("المعركة التالية", "Next battle"), 0, 24f)
        button(c, B_MENU, 270f, wh / 2f + 168f, 280f, 50f, L.t("القائمة", "Menu"), 1, 20f)
    }

    private fun drawOver(c: Canvas) {
        fullRect(c, Color.rgb(40, 0, 0), 0.6f)
        panel(c, 270f, wh / 2f, 460f, 380f)
        txt(c, L.t("فشلت المهمة", "Mission failed"), 270f, wh / 2f - 110f, 36f, Color.rgb(255, 110, 100))
        paragraph(c, Story.loseText(world.loseReason), 270f, wh / 2f - 50f, 20f, 400f, Color.WHITE)
        button(c, B_RESTART, 270f, wh / 2f + 50f, 280f, 58f, L.t("حاول مجدداً", "Try again"), 0, 24f)
        button(c, B_MENU, 270f, wh / 2f + 120f, 280f, 50f, L.t("القائمة", "Menu"), 1, 20f)
    }

    private fun drawEnd(c: Canvas) {
        drawBackdrop(c)
        val ph = menuT / BEAT * PI_F
        val sn = sin(ph)
        val list = arrayOf(Looks.SAVANNA, Looks.QUBBA, Looks.JANJA, Looks.DINARI, Looks.DEFECTOR)
        for (i in list.indices) {
            val alt = if (i % 2 == 0) sn else -sn
            Sprites.soldier(c, 60f + i * 105f, wh * 0.84f, 1.2f, list[i], 0f, false, 1f, 0f, 6, 0f, 0f, 1f, 0.5f + 0.5f * alt, max(0f, alt), max(0f, -alt), abs(alt) * 5f)
        }
        drawTitle(c, 270f, wh * 0.14f, 64f)
        panel(c, 270f, wh * 0.46f, 480f, wh * 0.5f)
        paragraph(c, Story.epilogue(), 270f, wh * 0.46f - wh * 0.25f + 56f, 20f, 420f, Color.WHITE)
        button(c, B_MENU, 270f, wh * 0.46f + wh * 0.25f - 32f, 240f, 54f, L.t("القائمة", "Menu"), 0, 22f)
    }
}
