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
    fun labelAtTheTeachingSpotGoesBesideTheWindowNotOntoItsDot() {
        // v0.1.5 (QA #3 q3-1): S02 with no hand put the label just under the window's bottom edge, on the system dot.
        // Front and head turned 30 degrees left / right, both hands, both eyes: beside the window (front: moved to the
        // non-dominant side) or above it, never under it.
        for (windowYaw in yaws) for (turn in listOf(0f, 30f, -30f)) for (handed in Handed.entries) {
            val w = MainWindowLayout.estimate(head, windowYaw)
            val frame = PracticeFrame(head, windowYaw + turn)
            val p = labelAt(frame, V3.ZERO, handed)
            val q = MainWindowLayout.clearOf(head, w, p, LABEL_R, handed)
            val why = "window=$windowYaw turn=$turn $handed"
            // turned 30 degrees, the label is already beside the window: a nudge out that side, not across or over it
            if (turn != 0f) assertTrue("$why: moved ${(q - p).length}", (q - p).length < 0.05f)
            for (eye in eyes(frame)) {
                assertLabelClear(eye, w, q, why)
                val (x0, x1, y0, _) = labelRect(eye, w, q)
                val s = seenWindow(eye, w)
                assertTrue("$why: beside or above [$x0,$x1] y0=$y0 ${s.toList()}", x1 < s[0] || x0 > s[1] || y0 > s[3])
                if (turn == 0f) assertTrue("$why: non-dominant side [$x0,$x1]", if (handed == Handed.RIGHT) x1 < s[0] else x0 > s[1])
            }
        }
    }

    @Test
    fun labelNeverOnTheWindowItsDotOrTheTabBar() {
        // the wrist anywhere round the teaching spot (a seen hand), front and +-30 degrees, both hands, both eyes; a moved
        // label is beside or above the window, never under it
        for (windowYaw in listOf(0f, 90f, -120f)) for (turn in listOf(0f, 30f, -30f)) for (handed in Handed.entries) {
            val w = MainWindowLayout.estimate(head, windowYaw)
            val frame = PracticeFrame(head, windowYaw + turn)
            for (ix in -5..5) for (iy in -4..8) for (iz in -2..2) {
                val p = labelAt(frame, V3(ix * 0.04f, iy * 0.04f, iz * 0.06f), handed)
                val q = MainWindowLayout.clearOf(head, w, p, LABEL_R, handed)
                val why = "window=$windowYaw turn=$turn $handed ix=$ix iy=$iy iz=$iz"
                for (eye in eyes(frame)) {
                    assertLabelClear(eye, w, q, why)
                    if (q != p) {
                        val (x0, x1, _, y1) = labelRect(eye, w, q)
                        val s = seenWindow(eye, w)
                        assertFalse("$why: moved under the window", x1 > s[0] && x0 < s[1] && y1 < s[4])
                    }
                }
            }
        }
    }

    @Test
    fun controlStripIsInTheLabelsFootprint() {
        // a small label 40 dp under the window's bottom edge (on the dot) at the window's depth counts as overlapping
        val w = MainWindowLayout.estimate(head, 0f)
        val onDot = w.position + V3(0f, -(SEEN_HALF_H + 40f * MainWindowLayout.DRAWN_DP_M), 0f)
        assertTrue(MainWindowLayout.labelOverlaps(head, w, onDot, 0.01f))
        val q = MainWindowLayout.clearOf(head, w, onDot, 0.01f, Handed.RIGHT)
        assertTrue("not pushed down", q.y >= onDot.y)
        assertFalse(MainWindowLayout.labelOverlaps(head, w, q, 0.01f))
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
                assertFalse("still overlaps at ix=$ix iy=$iy d=$dist", MainWindowLayout.labelOverlaps(head, w, q, LABEL_R))
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

    /** Where SignScene puts the label: beside the wrist (teaching spot + [offset]) in [frame]. */
    private fun labelAt(frame: PracticeFrame, offset: V3, handed: Handed): V3 {
        val wrist = frame.toSpace(Layout.sided(Layout.TEACHING_SPOT, handed) + offset)
        return wrist + frame.toSpaceDir(Layout.sided(V3(-Layout.LABEL_SIDE, Layout.LABEL_UP, 0f), handed))
    }

    /** The two eyes of a head facing along [frame]. */
    private fun eyes(frame: PracticeFrame): List<V3> = listOf(-IPD_HALF, IPD_HALF).map { head + frame.toSpaceDir(V3(it, 0f, 0f)) }

    /** Tangents (right, up) of a point seen from [eye] in the window's frame, worked out here, not by the layout. */
    private fun proj(eye: V3, w: Placement.Placed, p: V3): Pair<Float, Float> {
        val a = p - eye
        val depth = a.dot(dirOf(w.yaw))
        assertTrue("in front of the eye", depth > 0.05f)
        return a.dot(dirOf(w.yaw - 90f)) / depth to a.y / depth
    }

    /** The window as the emulator draws it, seen from [eye]: (left, right, dot's lower edge, TabBar's top, window's bottom). */
    private fun seenWindow(eye: V3, w: Placement.Placed): FloatArray {
        val right = dirOf(w.yaw - 90f)
        val (l, t) = proj(eye, w, w.position - right * SEEN_HALF_W + V3(0f, SEEN_HALF_H + TABBAR_TOP, 0f))
        val (r, b) = proj(eye, w, w.position + right * SEEN_HALF_W - V3(0f, SEEN_HALF_H + DOT_BOTTOM, 0f))
        val (_, wb) = proj(eye, w, w.position - V3(0f, SEEN_HALF_H, 0f))
        return floatArrayOf(l, r, b, t, wb)
    }

    /** The label's rectangle (its four corners, posed with faceHead as SignScene does) as (left, right, bottom, top). */
    private fun labelRect(eye: V3, w: Placement.Placed, q: V3): FloatArray {
        val rot = faceHead(q, head)
        val corners = listOf(-1f to -1f, -1f to 1f, 1f to -1f, 1f to 1f).map { (sx, sy) -> proj(eye, w, q + rot.rotate(V3(sx * LABEL_W / 2f, sy * LABEL_H / 2f, 0f))) }
        return floatArrayOf(corners.minOf { it.first }, corners.maxOf { it.first }, corners.minOf { it.second }, corners.maxOf { it.second })
    }

    /** From [eye], the label rectangle does not cross the window, the dot under it or the TabBar over it (1 degree to spare). */
    private fun assertLabelClear(eye: V3, w: Placement.Placed, q: V3, why: String) {
        val (wl, wr, wb, wt) = seenWindow(eye, w)
        val (x0, x1, y0, y1) = labelRect(eye, w, q)
        val gap = 0.0175f
        assertTrue("$why: label [$x0,$x1]x[$y0,$y1] on window [$wl,$wr]x[$wb,$wt]", x1 < wl - gap || x0 > wr + gap || y1 < wb - gap || y0 > wt + gap)
    }

    private companion object {
        /** The hand label: 260 x 88 dp at scale 0.5 on the Stage (0.8 mm / dp), as a ball. */
        const val LABEL_R = 0.06f
        const val LABEL_W = 260f * 0.5f * 0.0008f
        const val LABEL_H = 88f * 0.5f * 0.0008f
        const val IPD_HALF = 0.032f

        /**
         * The window as the emulator drew it 1.75 m ahead of a level head (v0.1.5 build shots, 741 px per unit tangent):
         * 454 x 364 px, the dot's lower edge 56 px under it, the TabBar's top 64 px over it; here as metres at 1.75 m.
         */
        const val SEEN_HALF_W = 227f / 741f * 1.75f
        const val SEEN_HALF_H = 182f / 741f * 1.75f
        const val DOT_BOTTOM = 56f / 741f * 1.75f
        const val TABBAR_TOP = 64f / 741f * 1.75f
    }
}
