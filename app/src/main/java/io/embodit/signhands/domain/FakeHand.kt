package io.embodit.signhands.domain

import kotlin.math.floor

/** Positions of the design package (`spatialLayout`), practice frame, metres; X towards the dominant side. */
object Layout {
    /** Where the teacher's hand waits when no hand is tracked (and where a debug hand appears). */
    val TEACHING_SPOT = V3(0.12f, -0.28f, -0.38f)
    val WATCH_SPOT = V3(0f, -0.18f, -0.65f)
    const val WATCH_SCALE = 2f
    const val SIDE_OFFSET = 0.15f
    const val LABEL_SIDE = 0.12f
    const val LABEL_UP = 0.16f
    const val LABEL_MOVE_AT = 0.08f
    const val PANEL_DISTANCE = 0.60f
    const val PANEL_YAW = 32f
    const val PANEL_DOWN = 0.12f
    const val PANEL_TILT = 10f
    const val PANEL_FOLLOW_AT = 35f

    /** A practice-frame point with X pointing to the dominant side, for this hand. */
    fun sided(p: V3, handed: Handed): V3 = if (handed == Handed.RIGHT) p else p.mirrorX()
}

/**
 * The debug hand (design `verification.debugHooks` `fakeHand`): a synthetic dominant hand built from the letter's
 * ideal joints, optionally with one finger / the thumb / the palm off, scaled bones, moved, and with a share of
 * frames ([jitter]) replaced by the off pose so that the 0.8 s window can be tested.
 */
data class FakeHand(
    val letter: String,
    val off: String? = null,
    val offset: V3 = V3.ZERO,
    val bone: Float = 1f,
    val jitter: Float = 0f,
    /** v0.1.3: perform this word sign ([Performer]) instead of holding a letter. */
    val sign: String? = null,
    /** v0.1.3: draw the letter's move (J / Z) over and over. */
    val move: Boolean = false,
    val slow: Float = 1f,
    val turnDeg: Float = 0f,
    val startMs: Long = 0L,
) {
    /** The performed target, or null for a held letter. */
    fun target(book: LetterBook): Target? {
        sign?.let { id -> return Signs[id]?.let { Target.of(it, book) } }
        val l = book.letters[letter] ?: return null
        return if (move && l.motion != null) Target.of(l) else null
    }

    fun sloppy(): Sloppy = Sloppy(turnDeg = turnDeg, slow = slow, offset = offset)

    /** Joints in space for frame [frameIndex] (practice frame [practice], dominant hand [handed]). */
    fun joints(book: LetterBook, practice: PracticeFrame, handed: Handed, frameIndex: Long): List<V3> {
        val l = book.letters[letter] ?: book["OPEN"]
        val jittered = jitter > 0f && floor((frameIndex + 1) * jitter) > floor(frameIndex * jitter)
        val variant = if (jittered) (off?.takeIf { it != "palm" } ?: jitterVariant(l)) else (off?.takeIf { it != "palm" } ?: "ok")
        val local = l.joints[variant] ?: l.joints.getValue("ok")
        var orient = l.orient
        if (off == "palm" || (jittered && off == "palm")) orient = Q4.yaw(70f) * orient
        val wristLocal = Layout.sided(Layout.TEACHING_SPOT, handed) + offset
        val rotation = practice.rotation * HandMath.handedOrient(orient, handed)
        return HandMath.place(local, handed, practice.toSpace(wristLocal), rotation, bone)
    }

    private fun jitterVariant(l: Letter): String = if (l.joints.containsKey("index")) "index" else "ok"
}

/** J and Z paths of the fingertip (design `letters.table` motion), practice frame, right hand, metres. */
object Motion {
    fun offset(motion: String, t: Float): V3 {
        val u = t.coerceIn(0f, 1f)
        return when (motion) {
            "J" -> {
                // down 6 cm, then towards the non-dominant side and a little towards the body (4 cm)
                if (u < 0.55f) {
                    V3(0f, -0.06f * smoothstep(u / 0.55f), 0f)
                } else {
                    val k = smoothstep((u - 0.55f) / 0.45f)
                    V3(-0.04f * k, -0.06f + 0.015f * kotlin.math.sin(k * kotlin.math.PI.toFloat()), 0.015f * k)
                }
            }
            "Z" -> {
                // top left -> top right -> bottom left -> bottom right, as you see it
                val pts = listOf(V3(-0.03f, 0.03f, 0f), V3(0.03f, 0.03f, 0f), V3(-0.03f, -0.03f, 0f), V3(0.03f, -0.03f, 0f))
                val seg = (u * 3f).coerceAtMost(2.9999f)
                val i = seg.toInt()
                val k = seg - i
                val p = pts[i] + (pts[i + 1] - pts[i]) * k
                p - pts[0]
            }
            else -> V3.ZERO
        }
    }

    /** Seconds the demo spends on the move. */
    fun seconds(motion: String?): Float = when (motion) { "J" -> 1.2f; "Z" -> 1.5f; null -> 0f; else -> 1.2f }
}

/**
 * How sloppily a synthetic person does a target (v0.1.3 sloppy recordings, profile D27): the whole hand and path turned
 * [turnDeg] about [axis] (head frame), [slow] times the demo's speed, moved by [offset] (metres, head frame), and
 * [noiseMm] of tracking noise per joint and frame.
 */
data class Sloppy(
    val turnDeg: Float = 0f,
    val axis: V3 = V3(0f, 0f, 1f),
    val slow: Float = 1f,
    val offset: V3 = V3.ZERO,
    val noiseMm: Float = 0f,
)

/**
 * A synthetic person doing a target in front of a head at [Performer.joints]'s `head` facing `headYaw`: still letters
 * at the teaching spot, J / Z drawn there, word signs at their body spot along their path. The move repeats with a
 * pause ([PAUSE_MS]). Used by the debug hand (`fakeHand sign=…` / `move=1`) and by the sloppy-recording unit tests.
 */
object Performer {
    const val SIGN_MS = 1000L
    const val PAUSE_MS = 500L

    fun moveMs(target: Target): Long = if (target.sign != null) SIGN_MS else (Motion.seconds(target.letter?.motion) * 1000f).toLong()

    /** Path fraction at [ms] after the start (0 during the pause). */
    fun phase(target: Target, ms: Long, slow: Float): Float {
        val move = (moveMs(target) * slow).toLong().coerceAtLeast(1L)
        val t = ms % (move + PAUSE_MS)
        return (t / move.toFloat()).coerceAtMost(1f)
    }

    fun joints(book: LetterBook, target: Target, handed: Handed, head: V3, headYaw: Float, ms: Long, sloppy: Sloppy = Sloppy(), noise: (() -> Float)? = null): List<V3> {
        val shape = target.shape
        val local = shape.joints.getValue("ok")
        val headRot = Q4.yaw(headYaw)
        val turn = Q4.axisAngle(headRot.rotate(sloppy.axis), sloppy.turnDeg)
        val u = if (target.moving) phase(target, ms, sloppy.slow) else 0f
        val placed: List<V3>
        if (target.sign != null) {
            val s = target.sign
            val start = head + headRot.rotate(Layout.sided(s.at, handed) + sloppy.offset)
            val move = headRot.rotate(Layout.sided(s.palmAt(u) - s.at, handed))
            val palm = start + turn.rotate(move)
            val rotation = turn * headRot * HandMath.handedOrient(s.orient, handed)
            val centre = local[J.prox(1)] * 0.5f
            val wrist = palm - rotation.rotate(if (handed == Handed.LEFT) centre.mirrorX() else centre)
            placed = HandMath.place(local, handed, wrist, rotation)
        } else {
            // the practice frame of the teaching spot (as the scene builds it), then the letter's own orientation
            val headPractice = PracticeFrame(head, headYaw)
            val spot = headPractice.toSpace(Layout.sided(Layout.TEACHING_SPOT, handed) + sloppy.offset)
            val p = PracticeFrame(head, yawOf(V3(spot.x - head.x, 0f, spot.z - head.z)))
            val rotation = turn * p.rotation * HandMath.handedOrient(shape.orient, handed)
            val motion = target.letter?.motion
            val move = if (motion != null) turn.rotate(headRot.rotate(Layout.sided(Motion.offset(motion, u), handed))) else V3.ZERO
            placed = HandMath.place(local, handed, spot + move, rotation)
        }
        if (noise == null || sloppy.noiseMm <= 0f) return placed
        val k = sloppy.noiseMm / 1000f
        return placed.map { it + V3(noise(), noise(), noise()) * k }
    }
}
