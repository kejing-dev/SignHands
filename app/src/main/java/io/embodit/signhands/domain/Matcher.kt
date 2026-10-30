package io.embodit.signhands.domain

import kotlin.math.abs
import kotlin.math.max

/** What one compared item is about, for the hint line and the finger tint. */
enum class ItemKind { FINGER, SPREAD_APART, SPREAD_TOGETHER, THUMB_POS, THUMB_CLOSED, PALM, POINT }

/** Which way the finger is off, for the hint ("straighten" / "bend"). */
enum class Want { STRAIGHTER, BENT, NONE }

/** One compared item of a letter: [ratio] = deviation / threshold, so 1.0 is the edge. */
data class CareItem(val key: String, val kind: ItemKind, val finger: Finger?, val threshold: Float, private val eval: (Features) -> Pair<Float, Want>) {
    fun ratio(f: Features): Pair<Float, Want> = eval(f).let { (dev, want) -> dev / threshold to want }
}

data class ItemResult(val item: CareItem, val ratio: Float, val want: Want, val inside: Boolean)

data class FrameVerdict(val pass: Boolean, val items: List<ItemResult>) {
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
 * v0.1.1 loose judging (user on the headset: "手势追踪不行，需要模糊一点"): only each finger's straight / bent class and
 * the rough palm direction count; thumb, spread and pointing are not compared. One finger may be off; two "close"
 * frames within one second pass; nothing is ever marked wrong by the judge (the user can always press "Next").
 */
object Tolerance {
    /** Template curl at or below this = the finger should be straight; at or above BENT_TARGET = bent; between = not compared (C, X). */
    const val STRAIGHT_TARGET = 30f
    const val BENT_TARGET = 85f
    /** Measured curl that still counts as straight / bent (a fist is ~140, a straight finger < 25). */
    const val STRAIGHT_OK = 55f
    const val BENT_OK = 60f
    const val PALM = 75f
    const val MISSES_ALLOWED = 1
    const val WINDOW_MS = 1000L
    const val CLOSE_FRAMES = 2
}

object CareMask {
    private val FINGERS = listOf(Finger.INDEX, Finger.MIDDLE, Finger.RING, Finger.PINKY)

    /** The items compared for [letter]: straight / bent per finger where the shape is clear, and the palm direction. */
    fun items(letter: Letter): List<CareItem> {
        val t = HandMath.templateFeatures(letter.joints.getValue("ok"), letter.orient)
        val out = ArrayList<CareItem>()
        for (i in 0 until 4) {
            val target = t.flex[i]
            when {
                target <= Tolerance.STRAIGHT_TARGET ->
                    out += CareItem("f$i", ItemKind.FINGER, FINGERS[i], Tolerance.STRAIGHT_OK) { f -> f.flex[i] to Want.STRAIGHTER }
                target >= Tolerance.BENT_TARGET ->
                    out += CareItem("f$i", ItemKind.FINGER, FINGERS[i], 180f - Tolerance.BENT_OK) { f -> max(0f, 180f - f.flex[i]) to Want.BENT }
            }
        }
        val palmTarget = t.palmNormal
        out += CareItem("p", ItemKind.PALM, null, Tolerance.PALM) { f -> angleDeg(f.palmNormal, palmTarget) to Want.NONE }
        return out
    }
}

/**
 * Judges one letter frame by frame: a frame is "close" when the palm is roughly right and at most one finger is off;
 * the letter passes once two close frames fall within one second.
 */
class Judge(val letter: Letter) {
    private val items = CareMask.items(letter)
    private val closeAt = ArrayDeque<Long>()
    private var frames = 0
    private var closeFrames = 0

    var last: FrameVerdict? = null
        private set

    fun reset() {
        closeAt.clear()
        frames = 0
        closeFrames = 0
        last = null
    }

    fun frame(f: Features): FrameVerdict {
        val results =
            items.map { item ->
                val (ratio, want) = item.ratio(f)
                ItemResult(item, ratio, want, ratio <= 1f)
            }
        val palmOk = results.filter { it.item.kind == ItemKind.PALM }.all { it.inside }
        val misses = results.count { it.item.kind == ItemKind.FINGER && !it.inside }
        val v = FrameVerdict(palmOk && misses <= Tolerance.MISSES_ALLOWED, results)
        last = v
        return v
    }

    /** Adds a frame's verdict at [nowMs]; true once two close frames fall within one second. */
    fun push(nowMs: Long, verdict: FrameVerdict): Boolean {
        frames++
        if (verdict.pass) {
            closeFrames++
            closeAt.addLast(nowMs)
        }
        while (closeAt.isNotEmpty() && nowMs - closeAt.first() > Tolerance.WINDOW_MS) closeAt.removeFirst()
        return closeAt.size >= Tolerance.CLOSE_FRAMES
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

    fun reset() {
        current = null
        sinceMs = 0L
    }

    companion object {
        const val HOLD_MS = 1000L

        /** "finger:index:straighter", "thumb:side", "palm:front", "point", "spread:apart:1" ... */
        fun keyOf(r: ItemResult, letter: Letter): String =
            when (r.item.kind) {
                ItemKind.FINGER -> "finger:${r.item.finger!!.name.lowercase()}:${if (r.want == Want.BENT) "bend" else "straight"}"
                ItemKind.SPREAD_APART -> "spread:apart"
                ItemKind.SPREAD_TOGETHER -> "spread:together"
                ItemKind.THUMB_POS -> "thumb:${letter.thumb.substringBefore(':')}"
                ItemKind.THUMB_CLOSED -> "thumb:closed"
                ItemKind.PALM -> "palm:${letter.orientName}"
                ItemKind.POINT -> "point"
            }
    }
}
