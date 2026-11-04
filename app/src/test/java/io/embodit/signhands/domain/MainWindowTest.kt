package io.embodit.signhands.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2

/**
 * v0.1.4 (profile D8 / D9 / D21): one system window. The demo hand stands beside it, never in front of or behind it;
 * the hand label is moved off it; each state shows the right TabBar page; the "平视前方" hint stays upright on the
 * line of sight.
 */
class MainWindowTest {
    private val head = V3(0.3f, 1.62f, -0.4f)
    private val yaws = listOf(0f, 37f, 90f, -120f, 180f)

    @Test
    fun windowEstimateIsStraightAheadAtEyeHeight() {
        for (yaw in yaws) {
            val w = MainWindowLayout.estimate(head, yaw)
            val d = w.position - head
            assertEquals(MainWindowLayout.DIST, d.length, 1e-4f)
            assertEquals(0f, d.y, 1e-5f)
            assertTrue(abs(wrap180(yawOf(d) - yaw)) < 0.01f)
            assertEquals(head.x, MainWindowLayout.eyeOf(w).x, 1e-4f)
        }
    }

    @Test
    fun demoHandNeverInFrontOfOrBehindTheWindow() {
        for (yaw in yaws) for (handed in Handed.entries) {
            val w = MainWindowLayout.estimate(head, yaw)
            val demo = MainWindowLayout.demoSpot(w, handed)
            assertFalse("demo overlaps window yaw=$yaw $handed", MainWindowLayout.overlaps(head, w, demo, MainWindowLayout.DEMO_REACH))
        }
    }

    @Test
    fun demoHandIsOnTheDominantSideAndCloserThanTheWindow() {
        for (yaw in yaws) for (handed in Handed.entries) {
            val w = MainWindowLayout.estimate(head, yaw)
            val d = MainWindowLayout.demoSpot(w, handed) - head
            val side = d.dot(dirOf(yaw - 90f))
            assertTrue("side $side for $handed", if (handed == Handed.RIGHT) side > 0.3f else side < -0.3f)
            assertTrue("closer than the window", d.dot(dirOf(yaw)) < MainWindowLayout.DIST - 0.5f)
            assertTrue("below the eye line", d.y < 0f)
        }
    }

    @Test
    fun labelAtTheTeachingSpotStaysNearTheWristAndClear() {
        // at the teaching spot the label's top touched the window's bottom edge (3 degree margin): a nudge, not a jump
        for (handed in Handed.entries) {
            val w = MainWindowLayout.estimate(head, 0f)
            val wrist = head + Layout.sided(Layout.TEACHING_SPOT, handed)
            val label = wrist + Layout.sided(V3(-Layout.LABEL_SIDE, Layout.LABEL_UP, 0f), handed)
            val q = MainWindowLayout.clearOf(head, w, label, LABEL_R, handed)
            assertTrue("moved ${(q - label).length}", (q - label).length < 0.02f)
            assertFalse(MainWindowLayout.overlaps(head, w, q, LABEL_R))
        }
    }

    @Test
    fun labelInFrontOfTheWindowIsMovedOff() {
        // a hand raised to eye height straight ahead puts the label right in front of the window
        for (yaw in yaws) for (handed in Handed.entries) {
            val w = MainWindowLayout.estimate(head, yaw)
            var moved = 0
            for (ix in -6..6) for (iy in -6..6) for (dist in listOf(0.3f, 0.45f, 0.6f, 2.4f)) {
                val p = head + dirOf(yaw) * dist + dirOf(yaw - 90f) * (ix * 0.03f * dist) + V3(0f, iy * 0.025f * dist, 0f)
                val q = MainWindowLayout.clearOf(head, w, p, LABEL_R, handed)
                assertFalse("still overlaps at ix=$ix iy=$iy d=$dist", MainWindowLayout.overlaps(head, w, q, LABEL_R))
                if (q != p) {
                    moved++
                    assertTrue("moved too far ${(q - p).length}", (q - p).length < dist)
                }
            }
            assertTrue(moved > 0)
        }
    }

    @Test
    fun tabsFollowTheState() {
        val lesson = Course(CourseMode.LESSON, 1, listOf(Step("A")))
        val free = Course(CourseMode.FREE, 0, listOf(Step("A")))
        val signs = Course(CourseMode.SIGNS, 0, listOf(Step(Signs.all.first().id)))
        assertEquals(Tab.LESSONS, Tab.of(UiState(screen = Screen.S04)))
        assertEquals(Tab.LESSONS, Tab.of(UiState(screen = Screen.S06, course = lesson)))
        assertEquals(Tab.LESSONS, Tab.of(UiState(screen = Screen.S09)))
        assertEquals(Tab.LESSONS, Tab.of(UiState(screen = Screen.S11)))
        assertEquals(Tab.ALPHABET, Tab.of(UiState(screen = Screen.S13)))
        assertEquals(Tab.ALPHABET, Tab.of(UiState(screen = Screen.S05, course = free)))
        assertEquals(Tab.WORDS, Tab.of(UiState(screen = Screen.S16)))
        assertEquals(Tab.WORDS, Tab.of(UiState(screen = Screen.S06, course = signs)))
        assertEquals(Tab.WORDS, Tab.of(UiState(screen = Screen.S02, course = signs, lostFrom = Screen.S06)))
        assertEquals(Tab.SETTINGS, Tab.of(UiState(screen = Screen.S14)))
        assertEquals(Tab.SETTINGS, Tab.of(UiState(screen = Screen.S14, variant = "exit")))
        assertFalse(Tab.shown(UiState(screen = Screen.S00)))
        assertFalse(Tab.shown(UiState(screen = Screen.S01)))
        assertTrue(Tab.shown(UiState(screen = Screen.S03)))
    }

    @Test
    fun levelHintIsUprightOnTheLineOfSight() {
        for (pitch in listOf(-30f, 0f, 30f, -70f)) {
            val h = LevelHintPlacement.place(head, 20f, pitch)
            val d = h.position - head
            assertEquals(LevelHintPlacement.DIST, d.length, 1e-4f)
            val elev = Math.toDegrees(atan2(d.y.toDouble(), kotlin.math.sqrt((d.x * d.x + d.z * d.z).toDouble()))).toFloat()
            assertEquals(pitch.coerceIn(-40f, 40f), elev, 0.01f)
            assertEquals(1f, h.rotation.rotate(V3.UP).y, 1e-4f)
        }
    }

    private companion object {
        /** The hand label: 260 x 88 dp at scale 0.5 on the Stage (0.8 mm / dp), as a ball. */
        const val LABEL_R = 0.06f
    }
}
