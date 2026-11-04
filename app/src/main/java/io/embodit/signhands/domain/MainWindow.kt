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

    /** The window's extent as tangents seen from [eye]: (left, right, bottom, top); null when it is not in front. */
    private fun extent(eye: V3, w: Placement.Placed): FloatArray? {
        val c = view(eye, w, w.position) ?: return null
        val hw = halfW / c.depth
        val hh = halfH / c.depth
        return floatArrayOf(c.x - hw, c.x + hw, c.y - hh, c.y + hh)
    }

    /** Is a ball of radius [r] at [p] in front of or behind the window, seen from [eye] (with [MARGIN_DEG])? */
    fun overlaps(eye: V3, w: Placement.Placed, p: V3, r: Float): Boolean {
        val e = extent(eye, w) ?: return false
        val v = view(eye, w, p) ?: return false
        val k = r / v.depth + tan(MARGIN_DEG.toRad())
        return v.x + k > e[0] && v.x - k < e[1] && v.y + k > e[2] && v.y - k < e[3]
    }

    /**
     * The hand label at [p] (ball of radius [r]) moved off the window seen from [eye]: below its bottom edge or out past
     * its non-dominant edge, whichever is the shorter move, at the same depth. Unchanged when it does not overlap.
     */
    fun clearOf(eye: V3, w: Placement.Placed, p: V3, r: Float, handed: Handed): V3 {
        if (!overlaps(eye, w, p, r)) return p
        val e = extent(eye, w) ?: return p
        val v = view(eye, w, p) ?: return p
        val k = r / v.depth + tan((MARGIN_DEG + 0.5f).toRad())
        val dy = (e[2] - k) - v.y // < 0: down
        val dx = if (handed == Handed.RIGHT) (e[0] - k) - v.x else (e[1] + k) - v.x
        val right = dirOf(w.yaw - 90f)
        return if (abs(dy) <= abs(dx)) p + V3(0f, dy * v.depth, 0f) else p + right * (dx * v.depth)
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
