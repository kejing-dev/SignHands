package io.embodit.signhands.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * v0.5.1: where the in-practice hand label (hint / "对了" / tip) stands. It used to sit beside the wrist and be pushed
 * out past the window, which put it outside the view whenever the hand was low or to the side. Now it stands straight
 * in front of the head's heading, [DIST] away and [DOWN_DEG] under the eye line (between the eyes and the hands), and
 * follows the heading lazily: it re-centres only once the head has turned more than [FOLLOW_DEG] away.
 * Only the heading counts (pitch dropped), like [Placement]: nothing is shown or hidden by where the head points.
 */
object LabelPlacement {
    const val DIST = 0.6f
    const val DOWN_DEG = 18f
    const val FOLLOW_DEG = 20f

    fun spot(headPos: V3, headYaw: Float): V3 {
        val down = Math.toRadians(DOWN_DEG.toDouble()).toFloat()
        return headPos + dirOf(headYaw) * (DIST * cos(down)) + V3(0f, -DIST * sin(down), 0f)
    }

    /** The heading the label should be anchored to, given the one it has now (null = not placed yet). */
    fun follow(anchorYaw: Float?, headYaw: Float): Float =
        if (anchorYaw == null || abs(wrap180(headYaw - anchorYaw)) > FOLLOW_DEG) headYaw else anchorYaw
}
