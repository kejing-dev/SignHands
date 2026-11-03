package io.embodit.signhands.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * v0.1.3 profile D9 / D29: the panel is placed from the head's heading only. Head looking 30 degrees down, 30 degrees
 * up and tilted 20 degrees: the panel stands upright (its up within 1 degree of the world's), 10-20 degrees below the
 * eye line, 0.8-1.2 m away; "Bring here" with the head at a given heading puts it within 2 degrees of where it belongs.
 */
class PlacementTest {
    private val head = V3(0.3f, 1.62f, -0.4f)

    private fun headRotation(yaw: Float, pitch: Float, roll: Float): Q4 = Q4.yaw(yaw) * Q4.pitch(pitch) * Q4.axisAngle(V3(0f, 0f, 1f), roll)

    private fun check(yaw: Float, pitch: Float, roll: Float, handed: Handed) {
        val placed = Placement.placeUpright(head, headRotation(yaw, pitch, roll), handed)
        val up = placed.rotation.rotate(V3.UP)
        assertTrue("up off by ${angleDeg(up, V3.UP)} (pitch $pitch roll $roll)", angleDeg(up, V3.UP) < 1f)
        val d = placed.position - head
        val horizontal = sqrt(d.x * d.x + d.z * d.z)
        val below = -Math.toDegrees(atan2(d.y.toDouble(), horizontal.toDouble())).toFloat()
        assertTrue("below eye line $below", below in 10f..20f)
        assertTrue("distance ${d.length}", d.length in 0.8f..1.2f)
        // D29: the content's heading is the head's heading plus the fixed side offset, within 2 degrees
        val side = if (handed == Handed.RIGHT) Placement.SIDE_DEG else -Placement.SIDE_DEG
        assertTrue("heading off ${wrap180(yawOf(d) - (yaw + side))}", abs(wrap180(yawOf(d) - (yaw + side))) < 2f)
        assertTrue("panel turned ${wrap180(placed.yaw - (yaw + side))}", abs(wrap180(placed.yaw - (yaw + side))) < 2f)
    }

    @Test
    fun threeHeadPosesGiveAnUprightPanel() {
        for (handed in Handed.entries) for (yaw in listOf(0f, 40f, -135f)) {
            check(yaw, -30f, 0f, handed)
            check(yaw, 30f, 0f, handed)
            check(yaw, 0f, 20f, handed)
        }
    }

    @Test
    fun lookingStraightDownKeepsTheHeading() {
        // the forward has no horizontal part left: the head's up gives the heading
        assertEquals(60f, Placement.heading(headRotation(60f, -89.5f, 0f)), 2f)
        assertEquals(-20f, Placement.heading(headRotation(-20f, 88f, 0f)), 2f)
    }
}
