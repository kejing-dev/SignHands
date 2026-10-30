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
) {
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
