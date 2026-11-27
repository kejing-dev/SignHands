package io.embodit.signhands.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** v0.5.1: the hand label stands front-centre, under the eye line, inside the view, and follows the heading lazily. */
class LabelPlacementTest {
    private val head = V3(0f, 1.6f, 0f)

    @Test
    fun standsStraightAheadUnderTheEyes() {
        for (yaw in listOf(0f, 45f, -120f, 179f)) {
            val p = LabelPlacement.spot(head, yaw)
            val d = p - head
            val horiz = sqrt(d.x * d.x + d.z * d.z)
            val dir = wrap180(yawOf(V3(d.x, 0f, d.z)) - yaw)
            val elev = Math.toDegrees(atan2(d.y.toDouble(), horiz.toDouble()))
            assertTrue("dir $dir", abs(dir) < 0.5f)
            assertEquals(-LabelPlacement.DOWN_DEG.toDouble(), elev, 0.5)
            assertEquals(LabelPlacement.DIST, d.length, 0.001f)
        }
    }

    @Test
    fun followsOnlyPastTheDeadZone() {
        assertEquals(10f, LabelPlacement.follow(null, 10f), 0f)
        assertEquals(0f, LabelPlacement.follow(0f, 15f), 0f)
        assertEquals(0f, LabelPlacement.follow(0f, -19f), 0f)
        assertEquals(25f, LabelPlacement.follow(0f, 25f), 0f)
        assertEquals(175f, LabelPlacement.follow(175f, -170f), 0f) // across the seam: 15 degrees, stays
    }
}
