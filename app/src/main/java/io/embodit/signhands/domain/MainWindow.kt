package io.embodit.signhands.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * v0.1.4 (profile D8 / D21, factory native-window-move §3.6): the lesson panel is one system Planar window, its pages
 * switched by the SpatialUI TabBar. Which page a state belongs to.
 */
enum class Tab {
    LESSONS,
    ALPHABET,
    WORDS,
    SETTINGS;

    companion object {
        fun of(s: UiState): Tab =
            when (s.screen) {
                Screen.S13 -> ALPHABET
                Screen.S16 -> WORDS
                Screen.S14, Screen.S15 -> SETTINGS
                Screen.S02, Screen.S05, Screen.S06, Screen.S07, Screen.S08 ->
                    when {
                        s.course?.mode == CourseMode.SIGNS -> WORDS
                        s.free -> ALPHABET
                        else -> LESSONS
                    }
                else -> LESSONS
            }

        /** No pages before a hand is chosen (S00 loading, S01 welcome): every tab would skip the choice. */
        fun shown(s: UiState): Boolean = s.screen != Screen.S00 && s.screen != Screen.S01
    }
}

/**
 * Where the system window is, as far as the app can tell. SDK 6.1 cannot read or set a window's pose (§4): the system
 * opens it straight ahead of the head at [DIST], square to the line of sight; the app opens it only while the head is
 * level ([LevelGate]), so it stands upright at eye height. A system drag after that is not seen.
 *
 * The 3D content keeps out of the window's way instead (§4): the demo hand stands beside it on the dominant side and
 * nearer, the hand label is pushed off it when it would sit in front of or behind it (D8).
 */
object MainWindowLayout {
    const val W_DP = 620f
    const val H_DP = 500f

    /** Metres per window dp: `WINDOW dp=0.00080 m` on the emulator and the headset (drone-sight v0.1.2 / v0.1.3). */
    const val DP_M = 0.0008f
    const val DIST = 1.75f

    val halfW: Float
        get() = W_DP * DP_M / 2f
    val halfH: Float
        get() = H_DP * DP_M / 2f

    /** The demo hand: [DEMO_AHEAD] in front of the eye, [DEMO_SIDE] to the dominant side, [DEMO_DOWN] below it. */
    const val DEMO_AHEAD = 0.8f
    const val DEMO_SIDE = 0.48f
    const val DEMO_DOWN = 0.18f

    /** Reach of the demo round its centre: the 2x letter hand ~0.22 m, a word demo (head ring + shoulders) ~0.3 m. */
    const val DEMO_REACH = 0.3f

    /**
     * v0.1.5 (QA #3 q3-1): the window as the system draws it. The converter says [DP_M], but on the emulator a window
     * opened 1.75 m ahead of a level head spans 455 x 364 px at 741 px per unit tangent (the hand label and the demo hand
     * fit that scale, front and head 30 degrees left agree): 0.00173 m per dp at [DIST], 2.2x the converter. The demo
     * hand's check keeps [DP_M] (QA #3 passed it); the hand label's uses this.
     */
    const val DRAWN_DP_M = 0.00173f

    /**
     * The system's control strip under the window (the dot, opening into x / drag handle / -), window-wide (profile D8
     * addendum, native-window-move §2: about 60 dp). The dot's lower edge was measured 77 dp under the edge.
     */
    const val CONTROLS_DP = 80f

    /** The SpatialUI TabBar capsule over the window (its top measured 88 dp above the edge), window-wide. */
    const val TABBAR_DP = 96f

    /** Half the distance between the eyes: a near label shifts against the far window by this over each depth. */
    const val EYE_HALF_IPD = 0.032f

    /** Angular gap kept between 3D content and the window's edge. */
    const val MARGIN_DEG = 3f

    /** The window as the system opens it from a level head at [headPos] facing [headYaw]. */
    fun estimate(headPos: V3, headYaw: Float): Placement.Placed = Placement.Placed(headPos + dirOf(headYaw) * DIST, headYaw)

    /** The eye the window was opened in front of. */
    fun eyeOf(w: Placement.Placed): V3 = w.position - dirOf(w.yaw) * DIST

    fun demoSpot(w: Placement.Placed, handed: Handed): V3 {
        val side = if (handed == Handed.RIGHT) DEMO_SIDE else -DEMO_SIDE
        return eyeOf(w) + dirOf(w.yaw) * DEMO_AHEAD + dirOf(w.yaw - 90f) * side + V3(0f, -DEMO_DOWN, 0f)
    }

    /** A point seen from [eye] in the window's frame: tangents right / up and depth along the window's forward. */
    private class View(val x: Float, val y: Float, val depth: Float)

    private fun view(eye: V3, w: Placement.Placed, p: V3): View? {
        val f = dirOf(w.yaw)
        val right = dirOf(w.yaw - 90f)
        val a = p - eye
        val depth = a.dot(f)
        if (depth < 0.05f) return null
        return View(a.dot(right) / depth, a.y / depth, depth)
    }

    /** A window-sized rect seen from [eye]: (left, right, bottom, top, depth); null when the window is not in front. */
    private fun rect(eye: V3, w: Placement.Placed, hw: Float, hh: Float, below: Float, above: Float): FloatArray? {
        val c = view(eye, w, w.position) ?: return null
        return floatArrayOf(c.x - hw / c.depth, c.x + hw / c.depth, c.y - (hh + below) / c.depth, c.y + (hh + above) / c.depth, c.depth)
    }

    /** The window's extent as tangents seen from [eye] ([DP_M]): (left, right, bottom, top, depth). */
    private fun extent(eye: V3, w: Placement.Placed): FloatArray? = rect(eye, w, halfW, halfH, 0f, 0f)

    /**
     * v0.1.5: what the hand label keeps off, as tangents seen from [eye]: the window as drawn ([DRAWN_DP_M]) with the
     * control strip under it and the TabBar over it, (left, right, bottom, top, depth).
     */
    fun footprint(eye: V3, w: Placement.Placed): FloatArray? =
        rect(eye, w, W_DP * DRAWN_DP_M / 2f, H_DP * DRAWN_DP_M / 2f, CONTROLS_DP * DRAWN_DP_M, TABBAR_DP * DRAWN_DP_M)

    /** Tangents (right, up) of [p] seen from [eye] in the window's frame, as [footprint] uses them; null behind the eye. */
    fun tangents(eye: V3, w: Placement.Placed, p: V3): FloatArray? = view(eye, w, p)?.let { floatArrayOf(it.x, it.y) }

    /** Is a ball of radius [r] at [p] in front of or behind the window, seen from [eye] (with [MARGIN_DEG])? */
    fun overlaps(eye: V3, w: Placement.Placed, p: V3, r: Float): Boolean {
        val e = extent(eye, w) ?: return false
        val v = view(eye, w, p) ?: return false
        val k = r / v.depth + tan(MARGIN_DEG.toRad())
        return v.x + k > e[0] && v.x - k < e[1] && v.y + k > e[2] && v.y - k < e[3]
    }

    /** How far either eye sees a point at [depth] shift sideways against the window at [windowDepth], as a tangent. */
    private fun parallax(depth: Float, windowDepth: Float): Float = EYE_HALF_IPD * abs(1f / depth - 1f / windowDepth)

    /** Is the hand label (ball of radius [r] at [p]) on the [footprint] for either eye (with [MARGIN_DEG])? */
    fun labelOverlaps(eye: V3, w: Placement.Placed, p: V3, r: Float): Boolean {
        val e = footprint(eye, w) ?: return false
        val v = view(eye, w, p) ?: return false
        val ky = r / v.depth + tan(MARGIN_DEG.toRad())
        val kx = ky + parallax(v.depth, e[4])
        return v.x + kx > e[0] && v.x - kx < e[1] && v.y + ky > e[2] && v.y - ky < e[3]
    }

    /**
     * The hand label at [p] (ball of radius [r]) moved off the [footprint] seen from [eye], at the same depth. Already
     * beside the window (its centre past a side edge): further out that side. Otherwise out past the non-dominant edge
     * (the teacher's hand is on the dominant side) or above the TabBar, whichever is the shorter move. Never down: under
     * the window is the system's dot (v0.1.5, QA #3 q3-1). Unchanged when it does not overlap.
     */
    fun clearOf(eye: V3, w: Placement.Placed, p: V3, r: Float, handed: Handed): V3 {
        if (!labelOverlaps(eye, w, p, r)) return p
        val e = footprint(eye, w) ?: return p
        val v = view(eye, w, p) ?: return p
        val ky = r / v.depth + tan((MARGIN_DEG + 0.5f).toRad())
        val kx = ky + parallax(v.depth, e[4])
        val right = dirOf(w.yaw - 90f)
        if (v.x < e[0]) return p + right * (((e[0] - kx) - v.x) * v.depth)
        if (v.x > e[1]) return p + right * (((e[1] + kx) - v.x) * v.depth)
        val dy = (e[3] + ky) - v.y // > 0: up
        val dx = if (handed == Handed.RIGHT) (e[0] - kx) - v.x else (e[1] + kx) - v.x
        return if (dy <= abs(dx)) p + V3(0f, dy * v.depth, 0f) else p + right * (dx * v.depth)
    }
}

/** "平视前方 / Look straight ahead": on the line of sight (pitch clamped to +-[MAX_PITCH]), [DIST] away, upright. */
object LevelHintPlacement {
    const val DIST = 0.75f
    const val MAX_PITCH = 40f

    fun place(headPos: V3, headYaw: Float, pitchDeg: Float): Placement.Placed {
        val p = pitchDeg.coerceIn(-MAX_PITCH, MAX_PITCH).toRad()
        return Placement.Placed(headPos + dirOf(headYaw) * (DIST * cos(p)) + V3(0f, DIST * sin(p), 0f), headYaw)
    }
}
