package io.embodit.signhands.domain

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * v0.5.2 (user on device: "E 五个都绿灯了还是不过，要调整很久"): real hands differ from the template hand in finger and
 * thumb length and in how far each finger curls. A correct letter on such a hand passes every care item but can still sit
 * nearer another letter's template (the rival check). This test makes "people": each finger (and the thumb) 0.85..1.15
 * long and each finger curled a little more or less, does the right letter, and needs it to pass on the first try.
 */
class HandProportionsTest {
    private val book = TestBook.book
    private val letters = book.letters.values.filter { it.scored && it.l.length == 1 && it.l[0].isLetter() && it.motion == null }

    private fun gauss(r: Random): Float {
        val u = r.nextFloat().coerceAtLeast(1e-6f)
        val v = r.nextFloat()
        return sqrt(-2f * kotlin.math.ln(u)) * kotlin.math.cos(2f * Math.PI.toFloat() * v)
    }

    /** A person: per chain (thumb, index..pinky) a length factor and a curl factor (1 = the template's curl). */
    class Person(val len: FloatArray, val curl: FloatArray)

    private fun person(r: Random, spreadLen: Float, spreadCurl: Float) =
        Person(FloatArray(5) { 1f + (r.nextFloat() * 2f - 1f) * spreadLen }, FloatArray(5) { 1f + (r.nextFloat() * 2f - 1f) * spreadCurl })

    private fun chain(c: Int): IntArray = if (c == 0) intArrayOf(J.THUMB_MC, 2, 3, J.THUMB_TIP) else IntArray(5) { J.base(c - 1) + it }

    /** Re-shapes a hand: each chain's segments scaled by len, each bend (between successive segments) scaled by curl. */
    fun reshape(joints: List<V3>, p: Person): List<V3> {
        val out = joints.toMutableList()
        for (c in 0 until 5) {
            val ch = chain(c)
            val segs = (0 until ch.size - 1).map { joints[ch[it + 1]] - joints[ch[it]] }
            // the first segment (metacarpal) stays; each later bend (around its own axis) is scaled by curl, carried down the chain
            out[ch[1]] = out[ch[0]] + segs[0] * (if (c == 0) p.len[c] else 1f)
            var acc = Q4.axisAngle(V3(0f, 1f, 0f), 0f)
            for (k in 1 until segs.size) {
                val prev = segs[k - 1].normalized()
                val s = segs[k]
                val axis = prev.cross(s.normalized())
                var dir = s.normalized()
                if (axis.length > 1e-5f) {
                    val ang = angleDeg(prev, s)
                    val ax = axis.normalized()
                    val step = Q4.axisAngle(ax, ang * p.curl[c]) * Q4.axisAngle(ax, -ang)
                    acc = acc * step
                }
                dir = acc.rotate(dir)
                out[ch[k + 1]] = out[ch[k]] + dir * (s.length * p.len[c])
            }
        }
        return out
    }

    private data class Outcome(val pass: Boolean, val rivalBlocked: Boolean, val like: String?)

    private fun tryOnce(target: Target, r: Random, p: Person, frames: Int = 30, flick: Float = 0f): Outcome {
        val judge = Judge(target)
        val head = TestBook.head
        val axis = V3(gauss(r), gauss(r), gauss(r)).normalized()
        val sloppy = Sloppy(turnDeg = r.nextFloat() * 25f, axis = axis, noiseMm = 3f)
        var blocked = 0
        var like: String? = null
        for (i in 0 until frames) {
            val ms = i * 33L
            val pf = if (flick <= 0f) p else Person(p.len, FloatArray(5) { p.curl[it] + gauss(r) * flick })
            val joints = reshape(Performer.joints(book, target, Handed.RIGHT, head, 0f, ms, sloppy) { gauss(r) }, pf)
            val w = joints[J.WRIST]
            val practice = PracticeFrame(head, yawOf(V3(w.x - head.x, 0f, w.z - head.z)))
            val f = HandMath.features(joints, Handed.RIGHT, practice) ?: continue
            val v = judge.frame(f)
            if (!v.pass && v.items.all { it.inside }) {
                blocked++
                like = v.like ?: like
            }
            if (judge.push(ms, v)) return Outcome(true, false, null)
        }
        return Outcome(false, blocked > frames / 2, like)
    }

    private fun rates(spreadLen: Float, spreadCurl: Float, people: Int = 60, flick: Float = 0f): List<Triple<String, Float, String>> =
        letters.map { l ->
            val r = Random(11 + l.l.hashCode())
            val t = Target.of(l)
            val outs = (0 until people).map { tryOnce(t, r, person(r, spreadLen, spreadCurl), flick = flick) }
            val likes = outs.filter { it.rivalBlocked }.groupingBy { it.like }.eachCount().entries.joinToString(",") { "${it.key}:${it.value}" }
            Triple(l.l, outs.count { it.pass } / people.toFloat(), "${outs.count { it.rivalBlocked }} $likes")
        }

    @Test
    fun reportProportions() {
        for ((len, curl, flick) in listOf(Triple(0.15f, 0.15f, 0f), Triple(0.15f, 0.25f, 0f), Triple(0.15f, 0.15f, 0.12f), Triple(0.15f, 0.2f, 0.2f))) {
            val rs = rates(len, curl, flick = flick)
            println("PROPORTIONS len=$len curl=$curl flick=$flick min=${rs.minOf { it.second }} " + rs.joinToString(" ") { "${it.first}=${"%.2f".format(it.second)}" })
            println("  rival-blocked: " + rs.filter { !it.third.startsWith("0 ") }.joinToString(" | ") { "${it.first} ${it.third}" })
        }
    }

    @Test
    fun differentHandsPassFirstTry() {
        for ((l, rate, why) in rates(0.15f, 0.15f)) assertTrue("$l rate $rate (rival-blocked $why)", rate >= 0.8f)
    }
}
