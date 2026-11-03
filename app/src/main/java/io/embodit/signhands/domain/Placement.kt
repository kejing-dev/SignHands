package io.embodit.signhands.domain

import kotlin.math.cos
import kotlin.math.sin

/**
 * v0.1.3 (profile D9): the one way the lesson panel (and the demo hand that stands beside it) is put in front of the
 * user: start-up, "Bring here" and the 70-degree re-place. Only the head's heading counts; pitch and roll are dropped,
 * so the panel always stands upright, [DOWN_DEG] below the eye line, [DIST] away, [SIDE_DEG] to the non-dominant side.
 */
object Placement {
    const val DIST = 0.9f
    const val DOWN_DEG = 15f
    const val SIDE_DEG = 14f

    /** Where the panel stands and the heading it faces away from (the user stands at [yaw] + 180). */
    data class Placed(val position: V3, val yaw: Float) {
        val rotation: Q4
            get() = Q4.yaw(yaw)
    }

    /**
     * Heading of a head rotation from its forward direction; looking almost straight down (or up), the forward has no
     * horizontal part left, so the head's up (or down) direction gives the heading instead.
     */
    fun heading(rotation: Q4): Float {
        val f = rotation.forward
        if (V3(f.x, 0f, f.z).length >= 0.3f) return yawOf(f)
        val up = rotation.rotate(V3.UP)
        return yawOf(if (f.y < 0f) up else -up)
    }

    fun placeUpright(headPos: V3, headRotation: Q4, handed: Handed): Placed = placeUpright(headPos, heading(headRotation), handed)

    fun placeUpright(headPos: V3, headYaw: Float, handed: Handed): Placed {
        val side = if (handed == Handed.RIGHT) SIDE_DEG else -SIDE_DEG
        val dir = dirOf(headYaw + side)
        val down = DOWN_DEG.toRad()
        return Placed(headPos + dir * (DIST * cos(down)) + V3(0f, -DIST * sin(down), 0f), headYaw + side)
    }
}
