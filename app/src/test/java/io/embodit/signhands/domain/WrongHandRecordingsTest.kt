package io.embodit.signhands.domain

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * v0.2.0 hand-over gate (polish M1, reviewers on v0.1.5: "错误的手势也能通过" / "我随便张手或者握拳，就过了"): the other half
 * of [SloppyRecordingsTest]. A wrong hand must not pass, not even after the automatic loosening: for every target the
 * app asks for, a synthetic person makes an open hand, a fist and the target's nearest look-alikes (in their own
 * natural orientation; moving targets and word signs: along the right path at the right place), with the same
 * sloppiness and 3 mm of tracking noise, for 6 s (four tries: loosening has its chance). Writes the confusion table
 * to build/wrong-hand/confusion.md.
 */
class WrongHandRecordingsTest {
    private val book = TestBook.book
    private val rivals = Rivals(book)

    private fun gauss(r: Random): Float {
        val u = r.nextFloat().coerceAtLeast(1e-6f)
        val v = r.nextFloat()
        return (sqrt(-2f * kotlin.math.ln(u)) * kotlin.math.cos(2f * Math.PI.toFloat() * v))
    }

    /** True when the wrong shape passed as [target] within [seconds]. */
    private fun passes(target: Target, wrong: Letter, seed: Int, handed: Handed = Handed.RIGHT, seconds: Float = 6f): Boolean {
        val r = Random(seed)
        val axis = V3(gauss(r), gauss(r), gauss(r)).normalized()
        val sloppy = Sloppy(turnDeg = r.nextFloat() * 15f, axis = axis, noiseMm = 3f)
        val j = Judge(target, rivals)
        val n = (seconds * 30f).toInt()
        for (i in 0 until n) {
            val ms = i * 33L
            val joints = Performer.joints(book, target, handed, TestBook.head, 0f, ms, sloppy, wrong) { gauss(r) }
            if (TestBook.judgeFrame(j, joints, handed, ms)) return true
        }
        return false
    }

    /** The wrong shapes tried on [target]: open hand, fist (A and S), and the two nearest look-alikes. */
    private fun wrongShapes(target: Target): List<String> {
        val grp = rivals.group(target.shape.l, !target.moving)
        val near = rivals.nearest(target.shape.l, !target.moving, 2)
        return (listOf("OPEN", "A", "S") + near).distinct().filter { it !in grp }
    }

    private val targets: List<Target> by lazy {
        book.alphabet.map { Target.of(book[it]) } + Signs.all.map { Target.of(it, book) }
    }

    @Test
    fun openHandAndFistNeverPass() {
        val fails = ArrayList<String>()
        for (t in targets) for (w in listOf("OPEN", "A", "S").filter { it !in rivals.group(t.shape.l, !t.moving) }) {
            val n = (1..3).count { seed -> passes(t, book[w], seed * 31 + t.key.hashCode()) }
            if (n > 0) fails += "$w as ${t.key}: $n / 3"
        }
        assertTrue("an open hand or a fist passed:\n" + fails.joinToString("\n"), fails.isEmpty())
    }

    @Test
    fun lookAlikesRarelyPassAndTheTableIsWritten() {
        val rows = ArrayList<String>()
        val fails = ArrayList<String>()
        var total = 0
        var passed = 0
        for (t in targets) {
            val cells = ArrayList<String>()
            for (w in wrongShapes(t)) {
                val n = (1..5).count { seed -> passes(t, book[w], seed * 17 + w.hashCode() + t.key.hashCode()) }
                total += 5
                passed += n
                cells += "$w ${if (n == 0) "拦住" else "过了 $n/5"}"
                if (n > 1) fails += "$w as ${t.key}: $n / 5"
            }
            val grp = rivals.group(t.shape.l, !t.moving) - t.shape.l
            rows += "| ${t.key} | ${t.shape.l} | ${if (grp.isEmpty()) "—" else grp.joinToString(" ")} | ${cells.joinToString("；")} |"
        }
        val dir = File("build/wrong-hand").apply { mkdirs() }
        File(dir, "confusion.md").writeText(
            "# 错手型对照（v0.2.0 WrongHandRecordingsTest）\n\n" +
                "每格：错手型 + 5 次（6 秒、可自动放宽）里过了几次。同组 = 追踪器分不清、互相算对的手型。\n\n" +
                "总计 $passed / $total 次错手型通过。\n\n| 目标 | 手型 | 同组 | 错手型结果 |\n| --- | --- | --- | --- |\n" + rows.joinToString("\n") + "\n",
        )
        println("WRONG-HAND passed $passed / $total")
        assertTrue("look-alikes passed more than once in five:\n" + fails.joinToString("\n"), fails.isEmpty())
        assertTrue("look-alikes passed $passed / $total (> 5 %)", passed * 20 <= total)
    }
}
