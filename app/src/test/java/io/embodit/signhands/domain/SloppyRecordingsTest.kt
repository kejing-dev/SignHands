package io.embodit.signhands.domain

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * v0.1.3 hand-over gate (profile D27, lessons build): deliberately sloppy recordings must all pass, on the first try,
 * without the automatic loosening. Each recording is 30 fps of a hand doing the target the way a hurried beginner
 * does: turned 15-20 degrees, turned half to the side 25-30 degrees, twice as slow, 10 cm off the spot, with 3 mm of
 * tracking noise. The recordings are synthetic (no headset on this machine): the template hand moved by [Performer]
 * and written to build/sloppy-recordings/ so they can be replayed or inspected.
 */
class SloppyRecordingsTest {
    private val book = TestBook.book

    data class Rec(val name: String, val target: Target, val sloppy: Sloppy, val handed: Handed = Handed.RIGHT, val seconds: Float = 5f)

    private val forward = V3(0f, 0f, 1f)
    private val vertical = V3(0f, 1f, 0f)
    private val across = V3(1f, 0f, 0f)

    private fun letter(l: String) = Target.of(book[l])

    private fun sign(id: String) = Target.of(Signs[id]!!, book)

    private val recordings =
        listOf(
            Rec("01-M-turned-18", letter("M"), Sloppy(turnDeg = 18f, axis = forward, noiseMm = 3f)),
            Rec("02-N-half-side-28", letter("N"), Sloppy(turnDeg = 28f, axis = vertical, noiseMm = 3f)),
            Rec("03-T-off-10cm-turned-15", letter("T"), Sloppy(turnDeg = 15f, axis = across, offset = V3(0.1f, 0f, 0f), noiseMm = 3f)),
            Rec("04-A-half-side-25-low-10cm", letter("A"), Sloppy(turnDeg = -25f, axis = vertical, offset = V3(0f, -0.1f, 0f), noiseMm = 3f)),
            Rec("05-J-twice-as-slow", letter("J"), Sloppy(slow = 2f, noiseMm = 3f)),
            Rec("06-J-turned-20-off-10cm", letter("J"), Sloppy(turnDeg = 20f, axis = forward, offset = V3(-0.1f, 0f, 0f), noiseMm = 3f)),
            Rec("07-Z-slow-half-side-25", letter("Z"), Sloppy(turnDeg = 25f, axis = vertical, slow = 2f, noiseMm = 3f)),
            Rec("08-THANK_YOU-slow-off-10cm", sign("THANK_YOU"), Sloppy(slow = 2f, offset = V3(0f, -0.1f, 0f), noiseMm = 3f)),
            Rec("09-HELLO-turned-18", sign("HELLO"), Sloppy(turnDeg = -18f, axis = forward, noiseMm = 3f)),
            Rec("10-MOTHER-half-side-28-off-10cm", sign("MOTHER"), Sloppy(turnDeg = 28f, axis = vertical, offset = V3(0.1f, 0f, 0f), noiseMm = 3f)),
            Rec("11-PLEASE-slow-turned-15", sign("PLEASE"), Sloppy(turnDeg = 15f, axis = forward, slow = 2f, noiseMm = 3f)),
            Rec("12-WHERE-left-hand-off-10cm", sign("WHERE"), Sloppy(offset = V3(0f, 0f, -0.1f), turnDeg = 20f, axis = across, noiseMm = 3f), Handed.LEFT),
        )

    private fun gauss(r: Random): Float {
        val u = r.nextFloat().coerceAtLeast(1e-6f)
        val v = r.nextFloat()
        return (sqrt(-2f * kotlin.math.ln(u)) * kotlin.math.cos(2f * Math.PI.toFloat() * v))
    }

    private fun record(rec: Rec, seed: Int): List<Pair<Long, List<V3>>> {
        val r = Random(seed)
        val n = (rec.seconds * 30f).toInt()
        return (0 until n).map { i ->
            val ms = i * 33L
            ms to Performer.joints(book, rec.target, rec.handed, TestBook.head, 0f, ms, rec.sloppy) { gauss(r) }
        }
    }

    /** Replays a recording through a fresh judge; the time it passed, or -1. */
    private fun replay(rec: Rec, frames: List<Pair<Long, List<V3>>>): Pair<Long, Judge> {
        val j = Judge(rec.target)
        for ((ms, joints) in frames) if (TestBook.judgeFrame(j, joints, rec.handed, ms)) return ms to j
        return -1L to j
    }

    private fun save(rec: Rec, frames: List<Pair<Long, List<V3>>>) {
        val dir = File("build/sloppy-recordings").apply { mkdirs() }
        val sb = StringBuilder("{\"name\":\"${rec.name}\",\"target\":\"${rec.target.key}\",\"handed\":\"${rec.handed}\",\"sloppy\":\"${rec.sloppy}\",\"head\":[0,1.6,0],\"frames\":[")
        frames.forEachIndexed { i, (ms, joints) ->
            if (i > 0) sb.append(',')
            sb.append("{\"ms\":$ms,\"joints\":[").append(joints.joinToString(",") { "[%.4f,%.4f,%.4f]".format(it.x, it.y, it.z) }).append("]}")
        }
        sb.append("]}")
        File(dir, "${rec.name}.json").writeText(sb.toString())
    }

    @Test
    fun everySloppyRecordingPassesFirstTry() {
        val lines = ArrayList<String>()
        var failed = 0
        for (rec in recordings) {
            val frames = record(rec, 11)
            save(rec, frames)
            val (at, j) = replay(rec, frames)
            val ok = at >= 0 && j.tier == 0
            if (!ok) failed++
            lines += "SLOPPY ${rec.name} target=${rec.target.key} ${if (ok) "PASS" else "FAIL"} at=${at}ms tier=${j.tier} moveRatio=${"%.2f".format(j.moveRatio)} hint=${j.moveHint}"
        }
        lines.forEach { println(it) }
        File("build/sloppy-recordings/summary.txt").writeText(lines.joinToString("\n") + "\n")
        assertTrue("at least 10 sloppy recordings", recordings.size >= 10)
        assertTrue("$failed sloppy recordings failed:\n" + lines.joinToString("\n"), failed == 0)
    }

    /** The same kinds of sloppiness with other noise draws: still >= 80 % first-try passes for each recording kind. */
    @Test
    fun sloppyKindsPassWithOtherNoise() {
        for (rec in recordings) {
            val ok = (1..20).count { seed -> replay(rec, record(rec, 100 + seed)).first >= 0 }
            assertTrue("${rec.name} $ok / 20", ok >= 16)
        }
    }
}
