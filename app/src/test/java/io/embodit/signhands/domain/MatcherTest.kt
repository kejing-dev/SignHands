package io.embodit.signhands.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * v0.1.3 forgiving judging (profile D27). The bar: with tracking jitter, a beginner copying the demo passes on the first
 * try at least 80 % of the time, for every letter and every word sign. Synthetic noise: 3 mm per joint per frame, the
 * whole hand turned up to 25 degrees off target, 5 degrees of frame-to-frame wobble.
 */
class MatcherTest {
    private val book = TestBook.book
    private val letters = book.letters.values.filter { it.l != "OPEN" }

    @Test
    fun everyLetterIsJudged() {
        assertEquals(26, letters.count { it.scored })
        assertEquals(setOf("J", "Z"), letters.filter { Target.of(it).moving }.map { it.l }.toSet())
    }

    private fun gauss(r: Random): Float {
        val u = r.nextFloat().coerceAtLeast(1e-6f)
        val v = r.nextFloat()
        return (sqrt(-2f * kotlin.math.ln(u)) * kotlin.math.cos(2f * Math.PI.toFloat() * v))
    }

    /** One try: [frames] frames at 30 fps of a noisy hand making [shape] while the app asks for [target]. */
    private fun tryOnce(target: Target, shape: Target, handed: Handed, r: Random, jointMm: Float = 3f, offDeg: Float = 25f, frames: Int = 30): Boolean {
        val judge = Judge(target)
        val head = TestBook.head
        val axis = V3(gauss(r), gauss(r), gauss(r)).normalized()
        val sloppy = Sloppy(turnDeg = r.nextFloat() * offDeg, axis = axis, noiseMm = jointMm)
        for (i in 0 until frames) {
            val ms = i * 33L
            val joints = Performer.joints(book, shape, handed, head, 0f, ms, sloppy) { gauss(r) }
            val wobble = Q4.axisAngle(V3(gauss(r), gauss(r), gauss(r)).normalized(), gauss(r) * 5f)
            val w = joints[J.WRIST]
            val j2 = joints.map { w + wobble.rotate(it - w) }
            if (TestBook.judgeFrame(judge, j2, handed, ms)) return true
        }
        return false
    }

    private fun framesFor(t: Target) = if (t.moving) 75 else 30

    private fun rate(target: Target, shape: Target, handed: Handed, tries: Int = 100, seed: Int = 7, jointMm: Float = 3f): Float {
        val r = Random(seed + target.key.hashCode())
        return (0 until tries).count { tryOnce(target, shape, handed, r, jointMm, frames = framesFor(target)) } / tries.toFloat()
    }

    /** Not an assertion: logs the first-try pass rates at 3 mm and at a harsh 6 mm (build.log / report). */
    @Test
    fun reportRates() {
        for (mm in listOf(3f, 6f)) {
            val rates = letters.map { l -> l.l to rate(Target.of(l), Target.of(l), Handed.RIGHT, tries = 50, jointMm = mm) }
            println("RATES letters jointMm=$mm min=${rates.minOf { it.second }} " + rates.joinToString(" ") { "${it.first}=${"%.2f".format(it.second)}" })
        }
        val words = Signs.all.map { s -> s.id to rate(Target.of(s, book), Target.of(s, book), Handed.RIGHT, tries = 30) }
        println("RATES signs jointMm=3 min=${words.minOf { it.second }} " + words.joinToString(" ") { "${it.first}=${"%.2f".format(it.second)}" })
    }

    @Test
    fun beginnersPassLettersFirstTryAtLeast80Percent() {
        for (handed in Handed.entries) for (l in letters) {
            val t = Target.of(l)
            val pr = rate(t, t, handed, tries = 60)
            assertTrue("${l.l} $handed first-try pass rate $pr", pr >= 0.8f)
        }
    }

    @Test
    fun beginnersPassSignsFirstTryAtLeast80Percent() {
        for (handed in Handed.entries) for (s in Signs.all) {
            val t = Target.of(s, book)
            val pr = rate(t, t, handed, tries = 20)
            assertTrue("${s.id} $handed first-try pass rate $pr", pr >= 0.8f)
        }
    }

    @Test
    fun clearlyDifferentShapesDoNotPass() {
        val pairs = listOf("A" to "B", "B" to "S", "L" to "I", "I" to "L", "B" to "A", "W" to "A", "Y" to "B", "D" to "B", "M" to "B", "N" to "L", "S" to "Y", "A" to "L")
        for ((target, shape) in pairs) {
            val pr = rate(Target.of(book[target]), Target.of(book[shape]), Handed.RIGHT, tries = 60)
            assertTrue("$shape as $target pass rate $pr", pr <= 0.2f)
        }
    }

    @Test
    fun fistLettersOnlyLookAtTheThumb() {
        // A: thumb beside; S: across; E / M / N / T: tucked. M, N and T pass each other (the tracker cannot see under the fingers)
        val pass = listOf("M" to "N", "N" to "M", "M" to "T", "T" to "N", "E" to "M", "S" to "M")
        for ((target, shape) in pass) {
            val v = Judge(book[target]).frame(TestBook.features(FakeHand(shape), Handed.RIGHT))
            assertTrue("$shape as $target ${v.items.map { it.item.key to it.ratio }}", v.pass)
        }
        // a thumb sticking out (L / Y thumb) is never a tucked-thumb letter
        val y = HandMath.templateFeatures(book["L"].joints.getValue("ok"), book["L"].orient)
        val fist = TestBook.features(FakeHand("M"), Handed.RIGHT)
        val outThumb = fist.copy(thumbTip = y.thumbTip)
        assertFalse(Judge(book["M"]).frame(outThumb).pass)
        assertEquals(ItemKind.THUMB_POS, Judge(book["M"]).frame(outThumb).worst?.item?.kind)
    }

    @Test
    fun oneFingerOffStillPassesTwoDoNot() {
        val l = book["B"]
        val v = Judge(l).frame(TestBook.features(FakeHand("B", off = "index"), Handed.RIGHT))
        assertTrue(v.pass)
        assertEquals(Finger.INDEX, v.worst?.item?.finger)
        assertEquals(Tint.CORAL, v.tints()[Finger.INDEX])
        assertFalse(Judge(book["B"]).frame(TestBook.features(FakeHand("S"), Handed.RIGHT)).pass)
    }

    @Test
    fun aStillLetterPassesWhenHeldUnderHalfASecond() {
        val j = Judge(book["M"])
        val good = TestBook.features(FakeHand("M"), Handed.RIGHT)
        var passedAt = -1L
        for (i in 0 until 30) {
            val t = i * 33L
            if (j.push(t, j.frame(good))) {
                passedAt = t
                break
            }
        }
        assertTrue("passed at $passedAt", passedAt in Tolerance.HOLD_MS..500L)
    }

    @Test
    fun aFlickerDoesNotPass() {
        val j = Judge(book["B"])
        val good = TestBook.features(FakeHand("B"), Handed.RIGHT)
        val bad = TestBook.features(FakeHand("S"), Handed.RIGHT)
        // 1 close frame in 3 for a second
        var passed = false
        for (i in 0 until 30) passed = passed || j.push(i * 33L, j.frame(if (i % 3 == 0) good else bad))
        assertFalse(passed)
    }

    @Test
    fun twoAlmostTriesLoosenOneStep() {
        // palm 85 degrees off: just outside 75, inside 75 x 1.3
        val l = book["B"]
        val f0 = TestBook.features(FakeHand("B"), Handed.RIGHT)
        val want = HandMath.templateFeatures(l.joints.getValue("ok"), l.orient).palmNormal
        val turned = f0.copy(palmNormal = Q4.axisAngle(V3(0f, 1f, 0f), 85f).rotate(want))
        val j = Judge(l)
        assertFalse(j.frame(turned).pass)
        var passedAt = -1L
        for (i in 0 until 200) {
            val t = i * 33L
            if (j.push(t, j.frame(turned))) {
                passedAt = t
                break
            }
        }
        assertEquals(1, j.tier)
        assertTrue("passed at $passedAt (two tries of ${Tolerance.TRY_MS} ms first)", passedAt in 2 * Tolerance.TRY_MS..2 * Tolerance.TRY_MS + 600)
        assertTrue(j.loosenedAt >= 0)
    }

    @Test
    fun farOffTriesDoNotLoosen() {
        val j = Judge(book["B"])
        val bad = TestBook.features(FakeHand("S"), Handed.RIGHT)
        for (i in 0 until 200) j.push(i * 33L, j.frame(bad))
        assertEquals(0, j.tier)
    }

    @Test
    fun aStillHandIsNotAMove() {
        for (t in listOf(Target.of(book["J"]), Target.of(Signs["HELLO"]!!, book), Target.of(Signs["MOTHER"]!!, book))) {
            val j = Judge(t)
            var passed = false
            val r = Random(3)
            for (i in 0 until 120) {
                val ms = i * 33L
                // the right shape at the right spot, never moving
                val joints = Performer.joints(book, t, Handed.RIGHT, TestBook.head, 0f, 0L, Sloppy(noiseMm = 3f)) { gauss(r) }
                passed = passed || TestBook.judgeFrame(j, joints, Handed.RIGHT, ms)
            }
            assertFalse("${t.key} passed without moving", passed)
            assertEquals("move:bigger", j.moveHint)
        }
    }

    @Test
    fun theWrongPathDoesNotPass() {
        // Z drawn when J is asked, J when Z is asked, HELLO's path when THANK YOU is asked (same B hand)
        val pairs = listOf(Target.of(book["J"]) to Target.of(book["Z"]), Target.of(book["Z"]) to Target.of(book["J"]))
        for ((want, done) in pairs) {
            val j = Judge(want)
            var passed = false
            for (i in 0 until 90) {
                val ms = i * 33L
                val joints = Performer.joints(book, done, Handed.RIGHT, TestBook.head, 0f, ms)
                // the shape is right for the asked letter: only the path differs
                val asked = Performer.joints(book, Target.of(want.shape), Handed.RIGHT, TestBook.head, 0f, 0L)
                val shift = joints[J.WRIST] - Performer.joints(book, done, Handed.RIGHT, TestBook.head, 0f, 0L)[J.WRIST]
                passed = passed || TestBook.judgeFrame(j, asked.map { it + shift }, Handed.RIGHT, ms)
            }
            assertFalse("${done.key} passed as ${want.key}", passed)
        }
        val thanks = Judge(Target.of(Signs["THANK_YOU"]!!, book))
        val hello = Target.of(Signs["HELLO"]!!, book)
        var passed = false
        for (i in 0 until 90) passed = passed || TestBook.judgeFrame(thanks, Performer.joints(book, hello, Handed.RIGHT, TestBook.head, 0f, i * 33L), Handed.RIGHT, i * 33L)
        assertFalse("HELLO passed as THANK_YOU", passed)
    }

    @Test
    fun wrongPlaceGivesAPlaceHint() {
        // MOTHER (chin) done at the chest
        val t = Target.of(Signs["MOTHER"]!!, book)
        val j = Judge(t)
        var passed = false
        for (i in 0 until 90) {
            val ms = i * 33L
            passed = passed || TestBook.judgeFrame(j, Performer.joints(book, t, Handed.RIGHT, TestBook.head, 0f, ms, Sloppy(offset = V3(0f, -0.3f, -0.05f))), Handed.RIGHT, ms)
        }
        assertFalse(passed)
        assertEquals("place:higher", j.moveHint)
    }

    @Test
    fun hintHoldsOneSecond() {
        val h = HintHolder()
        assertEquals("a", h.offer("a", 0))
        assertEquals("a", h.offer("b", 500))
        assertEquals("b", h.offer("b", 1001))
        h.force("loosen", 1100)
        assertEquals("loosen", h.current)
    }
}
