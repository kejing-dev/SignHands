package io.embodit.signhands.domain

import kotlin.math.abs
import kotlin.math.max

/** What one compared item is about, for the hint line and the finger tint. */
enum class ItemKind { FINGER, SPREAD_APART, SPREAD_TOGETHER, THUMB_POS, THUMB_CLOSED, PALM, POINT }

/** Which way the finger is off, for the hint ("straighten" / "bend"). */
enum class Want { STRAIGHTER, BENT, NONE }

/** One compared item of a target: [ratio] = deviation / (threshold x tier factor), so 1.0 is the edge. */
data class CareItem(val key: String, val kind: ItemKind, val finger: Finger?, val threshold: Float, val essential: Boolean = false, private val eval: (Features) -> Pair<Float, Want>) {
    fun ratio(f: Features, mul: Float = 1f): Pair<Float, Want> = eval(f).let { (dev, want) -> dev / (threshold * mul) to want }
}

data class ItemResult(val item: CareItem, val ratio: Float, val want: Want, val inside: Boolean)

/**
 * [effective] is the ratio that decides the frame (after the one forgiven finger): <= 1 passes. [like] (v0.2.0) is the
 * letter (or "OPEN") the hand looks more like than the target, null when the target is the nearest.
 */
data class FrameVerdict(val pass: Boolean, val items: List<ItemResult>, val effective: Float = if (pass) 1f else 2f, val like: String? = null) {
    /** The item furthest out (ratio / threshold), null when all are in. */
    val worst: ItemResult?
        get() = items.filter { !it.inside }.maxByOrNull { it.ratio }

    /** Finger tints: jade for a finger whose items all pass, coral for the worst one, null (teacher white) otherwise. */
    fun tints(): Map<Finger, Tint> {
        val out = HashMap<Finger, Tint>()
        val byFinger = items.filter { it.item.finger != null }.groupBy { it.item.finger!! }
        for ((f, rs) in byFinger) if (rs.all { it.inside }) out[f] = Tint.JADE
        worst?.item?.finger?.let { out[it] = Tint.CORAL }
        return out
    }
}

enum class Tint { JADE, CORAL }

/**
 * v0.1.3 forgiving judging (profile D27, the user's second "too strict": "握拳 M N，移动的比如 J 需要更模糊的判定正确"):
 * - every target has its own items: straight / bent / curved per finger where the shape is clear, the rough palm
 *   direction for still letters, and for the fist letters (A E M N S T) only the one thing that tells them apart, where
 *   the thumb is (beside / across / tucked). M, N and T are not told apart (the tracker cannot see a thumb under the
 *   fingers); one finger may be off;
 * - a still letter passes once it is held for [HOLD_MS] (well under 0.5 s);
 * - J, Z and the word signs compare the shape of the path only ([Trajectory]): not the speed, not how big, not the exact
 *   spot (word signs only need to start roughly in the right place, [PLACE_M]);
 * - two "almost" tries in a row loosen the target one step ([TIER_MUL]); the hint says what is off.
 *
 * v0.2.0 (polish M1, reviewers on v0.1.5: "错误的手势也能通过" / "我随便张手或者握拳，就过了"): forgiving for a right hand
 * done sloppily, strict for a wrong hand:
 * - rival check ([Rivals]): the hand must look more like the target than like any other letter or an open hand (letters
 *   the tracker cannot tell apart, e.g. M / N / T, count as one); never loosened; the label then says "这更像 X";
 * - the one forgiven finger is never a key finger (the one or two fingers that make the letter, e.g. D's index);
 * - the thumb counts for every target ([THUMB_NEAR]), not only the fist letters;
 * - loosening stops after one step; word signs need the handshape in 60 % of the frames.
 */
object Tolerance {
    /** Template curl at or below this = the finger should be straight; at or above BENT_TARGET = bent; between = curved (C, X, K). */
    const val STRAIGHT_TARGET = 30f
    const val BENT_TARGET = 80f
    /** Measured curl that still counts as straight / bent (a fist is ~140, a straight finger < 25). */
    const val STRAIGHT_OK = 55f
    const val BENT_OK = 60f
    /** A curved finger: within this many degrees of the template's curl. */
    const val CURVE_OK = 45f
    const val PALM = 75f
    /** Thumb tip (hand frame / hand length): how far outside its band it may still be. */
    const val THUMB_SLACK = 0.08f
    const val MISSES_ALLOWED = 1

    /** Still letters: held this long (with [HOLD_SHARE] of the frames in the last [WINDOW_MS] close). */
    const val HOLD_MS = 300L
    const val WINDOW_MS = 500L
    const val HOLD_SHARE = 0.6f

    /** Moving targets: windows of the last 0.3 .. 3.2 s are tried (a move done twice as slowly still fits). */
    const val MOVE_MIN_MS = 300L
    const val MOVE_MAX_MS = 3200L
    const val MOVE_STEP_MS = 100L
    /** Mean DTW distance (normalized path) that still counts as the same path. */
    const val MOVE_OK = 0.15f
    /** The hand shape must be close in this share of the window's frames (v0.2.0: 0.5 -> 0.6, rival check included). */
    const val SHAPE_SHARE = 0.6f
    /** A move must be at least this share of the template's size (and >= MIN_MOVE_M). */
    const val MIN_MOVE_SHARE = 0.35f
    const val MIN_MOVE_M = 0.02f
    /** Word signs: the path's centre within this of the template's (metres). */
    const val PLACE_M = 0.2f

    /**
     * Tier factors: thresholds x 1.0, after two almost-tries x 1.3. v0.2.0 (reviewers: "错误的手势也能通过"): one step
     * only (x 1.6 accepted nearly any hand), and the rival check ([Rivals]) is never loosened.
     */
    val TIER_MUL = floatArrayOf(1f, 1.3f)

    /** v0.2.0: every target's thumb tip within this of the template's (hand lengths; fist letters keep their band). */
    const val THUMB_NEAR = 0.25f
    /** "Almost": the best effective ratio of a try is within this. */
    const val NEAR = 1.6f
    /** One try = this long with the hand in view. */
    const val TRY_MS = 1500L
}

/** The point of the hand a moving target follows. */
enum class Track { PALM, INDEX_TIP, PINKY_TIP }

/** What is judged: a letter (still, or J / Z), or a word sign (always moving). [shape] is the hand pose compared. */
class Target private constructor(val letter: Letter?, val sign: Sign?, val shape: Letter) {
    val key: String = sign?.id ?: shape.l
    val moving: Boolean = sign != null || letter?.motion != null
    val track: Track =
        when {
            sign != null -> Track.PALM
            letter?.motion == "J" -> Track.PINKY_TIP
            else -> Track.INDEX_TIP
        }

    /** The path (head frame, right hand, metres, absolute for signs / relative for J and Z). */
    val path: List<V3> =
        when {
            sign != null -> Trajectory.polyline(sign.path.map { sign.at + it })
            letter?.motion != null -> (0..32).map { Motion.offset(letter.motion, it / 32f) }
            else -> emptyList()
        }
    val template: List<V3> = if (moving) Trajectory.template(path) else emptyList()
    val minMove: Float = if (moving) max(Tolerance.MIN_MOVE_M, Trajectory.extent(path) * Tolerance.MIN_MOVE_SHARE) else 0f
    val placeCentre: V3? = sign?.let { path.fold(V3.ZERO) { a, p -> a + p } * (1f / path.size) }

    companion object {
        fun of(letter: Letter): Target = Target(letter, null, letter)

        fun of(sign: Sign, book: LetterBook): Target = Target(null, sign, book[sign.shape])
    }
}

object CareMask {
    private val FINGERS = listOf(Finger.INDEX, Finger.MIDDLE, Finger.RING, Finger.PINKY)

    /** The fist letters: all four fingers folded; only where the thumb is tells them apart. */
    val FIST = setOf("A", "E", "M", "N", "S", "T")

    /** Thumb tip X band (hand frame / hand length; the thumb side is -X) of the fist letters. */
    private fun thumbBand(l: String): Triple<String, Float, Float> =
        when (l) {
            "A" -> Triple("side", -0.30f, -0.08f)
            "S" -> Triple("across", -0.05f, 0.25f)
            else -> Triple("tuck", -0.12f, 0.25f)
        }

    fun items(target: Target): List<CareItem> {
        val shape = target.shape
        val t = HandMath.templateFeatures(shape.joints.getValue("ok"), shape.orient)
        val out = ArrayList<CareItem>()
        val key = keyFingers(t.flex)
        for (i in 0 until 4) {
            val want = t.flex[i]
            val ess = i in key
            out +=
                when {
                    want <= Tolerance.STRAIGHT_TARGET -> CareItem("f$i", ItemKind.FINGER, FINGERS[i], Tolerance.STRAIGHT_OK, ess) { f -> f.flex[i] to Want.STRAIGHTER }
                    want >= Tolerance.BENT_TARGET -> CareItem("f$i", ItemKind.FINGER, FINGERS[i], 180f - Tolerance.BENT_OK, ess) { f -> max(0f, 180f - f.flex[i]) to Want.BENT }
                    else -> CareItem("f$i", ItemKind.FINGER, FINGERS[i], Tolerance.CURVE_OK) { f -> abs(f.flex[i] - want) to if (f.flex[i] > want) Want.STRAIGHTER else Want.BENT }
                }
        }
        if (target.sign == null && shape.l in FIST) {
            val (name, lo, hi) = thumbBand(shape.l)
            out += CareItem("t:$name", ItemKind.THUMB_POS, Finger.THUMB, Tolerance.THUMB_SLACK) { f -> max(0f, max(lo - f.thumbTip.x, f.thumbTip.x - hi)) to Want.NONE }
        } else {
            // v0.2.0: the thumb counts everywhere (an open hand is a B with the thumb out)
            val tip = t.thumbTip
            out += CareItem("t:pos", ItemKind.THUMB_POS, Finger.THUMB, Tolerance.THUMB_NEAR) { f -> (f.thumbTip - tip).length to Want.NONE }
        }
        // still letters only: a moving hand turns (J), and a word sign's palm faces every way along its path
        if (!target.moving) {
            val palmTarget = t.palmNormal
            out += CareItem("p", ItemKind.PALM, null, Tolerance.PALM) { f -> angleDeg(f.palmNormal, palmTarget) to Want.NONE }
        }
        return out
    }

    fun items(letter: Letter): List<CareItem> = items(Target.of(letter))

    /**
     * v0.2.0: the key fingers of a template, never forgiven: the fingers in the minority state when one or two fingers
     * differ from the rest (D's index, I's pinky, L's index, V's index and middle, Y's pinky).
     */
    fun keyFingers(flex: FloatArray): Set<Int> {
        val straight = (0 until 4).filter { flex[it] <= Tolerance.STRAIGHT_TARGET }
        val bent = (0 until 4).filter { flex[it] >= Tolerance.BENT_TARGET }
        return when {
            straight.size in 1..2 && bent.size >= 2 -> straight.toSet()
            bent.size in 1..2 && straight.size >= 2 -> bent.toSet()
            else -> emptySet()
        }
    }
}

/** One sample of the followed point (head frame, right-hand terms) and whether the hand shape was close then. */
private data class PathSample(val t: Long, val p: V3, val shapeOk: Boolean)

/**
 * Judges one target frame by frame. Still letters: a frame is "close" when the palm is roughly right, the thumb is in
 * its band (fist letters) and at most one finger is off; the letter passes once it is held [Tolerance.HOLD_MS].
 * Moving targets: [push] with the followed point; the path of a recent window must match (see [Tolerance]).
 */
class Judge(val target: Target, private val rivals: Rivals? = Rivals.current) {
    constructor(letter: Letter) : this(Target.of(letter))

    /** The letter whose pose is compared (for a sign: its handshape). */
    val letter: Letter
        get() = target.shape

    private val items = CareMask.items(target)
    private val fingerItems = items.count { it.kind == ItemKind.FINGER }
    private val recent = ArrayDeque<Pair<Long, Boolean>>()
    private val buffer = ArrayDeque<PathSample>()
    private var lastEval = Long.MIN_VALUE
    private var frames = 0
    private var closeFrames = 0
    private var passed = false

    // tiers
    var tier = 0
        private set
    /** When the target was last loosened (the label says so for a moment); -1 = never. */
    var loosenedAt = -1L
        private set
    private var tryStart = -1L
    private var tryBest = Float.MAX_VALUE
    private var tryPassed = false
    private var nearInRow = 0

    /** Moving targets: what is off with the path ("move:bigger", "move:path", "place:<dir>"), null = nothing / not moving. */
    var moveHint: String? = null
        private set
    /** Moving targets: the best effective ratio of the last evaluation (debug page / log). */
    var moveRatio = Float.NaN
        private set

    var last: FrameVerdict? = null
        private set

    val mul: Float
        get() = Tolerance.TIER_MUL[tier]

    fun reset() {
        recent.clear()
        buffer.clear()
        frames = 0
        closeFrames = 0
        last = null
        passed = false
        moveHint = null
        tryStart = -1L
        tryBest = Float.MAX_VALUE
        tryPassed = false
    }

    fun frame(f: Features): FrameVerdict {
        val m = mul
        val results =
            items.map { item ->
                val (ratio, want) = item.ratio(f, m)
                ItemResult(item, ratio, want, ratio <= 1f)
            }
        val fingerResults = results.filter { it.item.kind == ItemKind.FINGER }
        // v0.2.0: the forgiven finger is one of the others, never a key finger
        val essEff = fingerResults.filter { it.item.essential }.maxOfOrNull { it.ratio } ?: 0f
        val fingers = fingerResults.filter { !it.item.essential }.map { it.ratio }.sortedDescending()
        val forgiven = if (fingerItems >= 3) Tolerance.MISSES_ALLOWED else 0
        val fingerEff = max(essEff, fingers.drop(forgiven).maxOrNull() ?: 0f)
        val otherEff = results.filter { it.item.kind != ItemKind.FINGER }.maxOfOrNull { it.ratio } ?: 0f
        // v0.2.0: the rival check, never loosened
        val rival = rivals?.check(target, f)
        val eff = max(max(fingerEff, otherEff), rival?.ratio ?: 0f)
        val v = FrameVerdict(eff <= 1f, results, eff, rival?.like)
        last = v
        return v
    }

    /**
     * Adds a frame's verdict at [nowMs] (and, for a moving target, the followed point [point] in the head frame, right-
     * hand terms); true once the target is done.
     */
    fun push(nowMs: Long, verdict: FrameVerdict, point: V3? = null): Boolean {
        frames++
        if (verdict.pass) closeFrames++
        val done = if (target.moving) pushMoving(nowMs, verdict, point) else pushStill(nowMs, verdict)
        if (done) passed = true
        return done
    }

    private fun pushStill(now: Long, v: FrameVerdict): Boolean {
        recent.addLast(now to v.pass)
        while (recent.isNotEmpty() && now - recent.first().first > Tolerance.WINDOW_MS) recent.removeFirst()
        val close = recent.filter { it.second }
        val held = close.isNotEmpty() && now - close.first().first >= Tolerance.HOLD_MS && close.size >= recent.size * Tolerance.HOLD_SHARE
        noteTry(now, v.effective, held)
        return held
    }

    private fun pushMoving(now: Long, v: FrameVerdict, point: V3?): Boolean {
        if (point == null) return false
        buffer.addLast(PathSample(now, point, v.pass))
        while (buffer.isNotEmpty() && now - buffer.first().t > Tolerance.MOVE_MAX_MS) buffer.removeFirst()
        if (lastEval != Long.MIN_VALUE && now - lastEval < Tolerance.MOVE_STEP_MS) return false
        lastEval = now
        val (eff, hint) = evaluate()
        moveRatio = eff
        moveHint = if (eff <= 1f) null else hint
        val done = eff <= 1f
        noteTry(now, eff, done)
        return done
    }

    /** Best effective ratio over the recent windows, and the hint of that best window. */
    private fun evaluate(): Pair<Float, String?> {
        val m = mul
        val turns = if (tier == 0) floatArrayOf(-25f, 0f, 25f) else floatArrayOf(-40f, -20f, 0f, 20f, 40f)
        val end = buffer.last()
        var best = Float.MAX_VALUE
        var bestHint: String? = "move:path"
        var lastDur = -Tolerance.MOVE_STEP_MS
        for (i in buffer.size - 1 downTo 0) {
            val dur = end.t - buffer[i].t
            if (dur < Tolerance.MOVE_MIN_MS) continue
            if (dur - lastDur < Tolerance.MOVE_STEP_MS && i > 0) continue
            lastDur = dur
            val window = buffer.subList(i, buffer.size)
            val pts = Trajectory.smooth(window.map { it.p })
            val share = window.count { it.shapeOk } / window.size.toFloat()
            val shapeR = (Tolerance.SHAPE_SHARE / m) / share.coerceAtLeast(0.01f)
            val ext = Trajectory.extent(pts)
            val bigR = (target.minMove / m) / ext.coerceAtLeast(1e-4f)
            var placeR = 0f
            var placeHint: String? = null
            target.placeCentre?.let { c ->
                val centre = pts.fold(V3.ZERO) { a, p -> a + p } * (1f / pts.size)
                val d = centre - c
                placeR = d.length / (Tolerance.PLACE_M * m)
                placeHint = placeKey(d)
            }
            val moveR = if (bigR <= 1f) Trajectory.match(pts, target.template, turns) / (Tolerance.MOVE_OK * m) else bigR
            val eff = max(max(shapeR, bigR), max(placeR, moveR))
            if (eff < best) {
                best = eff
                bestHint =
                    when (eff) {
                        shapeR -> "shape"
                        bigR -> "move:bigger"
                        placeR -> placeHint
                        else -> "move:path"
                    }
            }
            if (dur >= Tolerance.MOVE_MAX_MS) break
        }
        return best to bestHint
    }

    private fun placeKey(d: V3): String {
        val ax = abs(d.x)
        val ay = abs(d.y)
        val az = abs(d.z)
        return when {
            ay >= ax && ay >= az -> if (d.y > 0) "place:lower" else "place:higher"
            az >= ax -> if (d.z > 0) "place:out" else "place:closer"
            else -> if (d.x > 0) "place:in" else "place:side"
        }
    }

    /** "Two almost-tries in a row loosen one step": a try is [Tolerance.TRY_MS] of frames. */
    private fun noteTry(now: Long, eff: Float, done: Boolean) {
        if (tryStart < 0) tryStart = now
        if (eff < tryBest) tryBest = eff
        if (done) tryPassed = true
        if (now - tryStart < Tolerance.TRY_MS) return
        val almost = !tryPassed && tryBest <= Tolerance.NEAR
        nearInRow = if (almost) nearInRow + 1 else 0
        if (nearInRow >= 2 && tier < Tolerance.TIER_MUL.size - 1) {
            tier++
            nearInRow = 0
            loosenedAt = now
        }
        tryStart = now
        tryBest = Float.MAX_VALUE
        tryPassed = false
    }

    /** Share of close frames so far (the SPIKE page and the auto log). */
    fun share(): Float = if (frames == 0) 0f else closeFrames.toFloat() / frames
}

/** Hint keys for the hand label (design `matching.worstFinger`): the same hint stays at least [HOLD_MS]. */
class HintHolder {
    var current: String? = null
        private set
    private var sinceMs = 0L

    fun offer(key: String?, nowMs: Long): String? {
        if (key == current) return current
        if (current == null || nowMs - sinceMs >= HOLD_MS) {
            current = key
            sinceMs = nowMs
        }
        return current
    }

    /** Shows [key] now whatever is held ("loosened" note). */
    fun force(key: String, nowMs: Long) {
        current = key
        sinceMs = nowMs
    }

    fun reset() {
        current = null
        sinceMs = 0L
    }

    companion object {
        const val HOLD_MS = 1000L

        /** "finger:index:straight", "thumb:tuck", "palm:front", "point", "spread:apart" ... */
        fun keyOf(r: ItemResult, letter: Letter): String =
            when (r.item.kind) {
                ItemKind.FINGER -> "finger:${r.item.finger!!.name.lowercase()}:${if (r.want == Want.BENT) "bend" else "straight"}"
                ItemKind.SPREAD_APART -> "spread:apart"
                ItemKind.SPREAD_TOGETHER -> "spread:together"
                ItemKind.THUMB_POS -> "thumb:${r.item.key.removePrefix("t:")}"
                ItemKind.THUMB_CLOSED -> "thumb:closed"
                ItemKind.PALM -> "palm:${letter.orientName}"
                ItemKind.POINT -> "point"
            }
    }
}

/**
 * v0.2.0 (polish M1, reviewers: "我随便张手或者握拳，就过了"): the hand must look more like the target than like any other
 * hand shape the app knows (the 26 letters and the open hand). Shapes the tracker cannot tell apart from the target
 * (templates within [SAME] of it, M / N / T, and for moving targets the letters that differ only by the palm) form the
 * target's group and never count as rivals. Distance = sum of finger curl differences / [FLEX], thumb tip distance /
 * [THUMB], index-middle spread difference / [SPREAD] (when both are measured), palm angle / [PALM] (still targets only).
 */
class Rivals(book: LetterBook) {
    private val shapes: List<Pair<String, Features>> =
        book.letters.values.map { it.l to HandMath.templateFeatures(it.joints.getValue("ok"), it.orient) }

    data class Result(val ratio: Float, val like: String?)

    private val groups = HashMap<String, Set<String>>()

    /** The target shape's group (the same shape for the tracker); [palm] = whether the palm tells shapes apart. */
    @Synchronized
    fun group(shape: String, palm: Boolean): Set<String> =
        groups.getOrPut("$shape/$palm") {
            val t = shapes.first { it.first == shape }.second
            shapes.filter { (l, f) -> l == shape || distance(t, f, palm) < SAME || TRACKER_SAME.any { shape in it && l in it } }.map { it.first }.toSet()
        }

    /** ratio = distance to the target's group / distance to the nearest rival (<= 1: the target is the nearest). */
    fun check(target: Target, f: Features): Result {
        val palm = !target.moving
        val grp = group(target.shape.l, palm)
        var dTarget = Float.MAX_VALUE
        var best = Float.MAX_VALUE
        var like: String? = null
        for ((l, s) in shapes) {
            val d = distance(f, s, palm)
            if (l in grp) {
                if (d < dTarget) dTarget = d
            } else if (d < best) {
                best = d
                like = l
            }
        }
        if (like == null) return Result(0f, null)
        val ratio = dTarget / best.coerceAtLeast(1e-3f)
        return Result(ratio, if (ratio > 1f) like else null)
    }

    /** The nearest rivals of a shape's template (confusion table, tests). */
    fun nearest(shape: String, palm: Boolean, n: Int): List<String> {
        val t = shapes.first { it.first == shape }.second
        val grp = group(shape, palm)
        return shapes.filter { it.first !in grp }.sortedBy { distance(t, it.second, palm) }.take(n).map { it.first }
    }

    companion object {
        /** Set when the letter book is parsed ([LetterBook.parse]); the judge uses it by default. */
        @Volatile var current: Rivals? = null

        const val FLEX = 40f
        const val THUMB = 0.12f
        const val SPREAD = 20f
        const val PALM = 60f
        const val SAME = 0.8f

        /**
         * Shapes the tracker cannot tell apart: the fist letters with the thumb under the fingers (v0.1.3 finding), and
         * R (crossed fingers) / U (side by side), where the crossing hides one finger behind the other.
         */
        val TRACKER_SAME = listOf(setOf("M", "N", "T"), setOf("R", "U"))

        fun distance(a: Features, b: Features, palm: Boolean): Float {
            var d = 0f
            for (i in 0 until 4) d += abs(a.flex[i] - b.flex[i]) / FLEX
            d += (a.thumbTip - b.thumbTip).length / THUMB
            val sa = a.spread[0]
            val sb = b.spread[0]
            if (!sa.isNaN() && !sb.isNaN()) d += abs(sa - sb) / SPREAD
            if (palm) d += angleDeg(a.palmNormal, b.palmNormal) / PALM
            return d
        }
    }
}
