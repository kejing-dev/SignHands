package io.embodit.signhands.domain

/**
 * The hand frame of a 25-joint hand (design `followGhost.orientation` "finger layer"): origin at the wrist,
 * +Y towards the middle knuckle, +Z out of the back of the hand, thumb on -X for a right hand and +X for a
 * left one. [rotation] maps hand-frame vectors into the joints' space.
 */
data class HandFrame(val wrist: V3, val rotation: Q4) {
    fun toLocal(p: V3): V3 = rotation.inverse.rotate(p - wrist)

    fun toSpace(local: V3): V3 = wrist + rotation.rotate(local)

    /** Palm side normal (the palm faces -Z of the hand frame). */
    val palmNormal: V3
        get() = rotation.rotate(V3(0f, 0f, -1f))

    companion object {
        fun of(joints: List<V3>, handed: Handed): HandFrame? {
            if (joints.size < J.COUNT) return null
            val w = joints[J.WRIST]
            val y = (joints[J.prox(1)] - w).normalized()
            var v = joints[J.prox(0)] - joints[J.prox(3)] // towards the thumb
            v = v - y * v.dot(y)
            if (v.length < 1e-5f || y.length < 0.5f) return null
            v = v.normalized()
            val x = if (handed == Handed.RIGHT) -v else v
            val z = x.cross(y).normalized()
            return HandFrame(w, Q4.fromBasis(x, y, z))
        }
    }
}

/**
 * The practice frame (design `spatialLayout.headFrame`): origin at the head, -Z = the horizontal direction
 * from the head to the dominant wrist (or the smoothed head heading), +Y up.
 */
data class PracticeFrame(val origin: V3, val yawDeg: Float) {
    val rotation: Q4
        get() = Q4.yaw(yawDeg)

    fun toLocal(v: V3): V3 = rotation.inverse.rotate(v)

    fun toSpaceDir(v: V3): V3 = rotation.rotate(v)

    fun toSpace(p: V3): V3 = origin + rotation.rotate(p)
}

/** Mirror across the hand frame's X: a left hand's local coordinates become a right hand's. */
fun V3.mirrorX(): V3 = V3(-x, y, z)

/**
 * What the matcher compares (design `matching.features`), always in "right hand" terms: a left hand's
 * local joints and directions are mirrored first.
 */
data class Features(
    /** Curl of index, middle, ring, pinky: knuckle-to-tip chord against the metacarpal, degrees. */
    val flex: FloatArray,
    /** Index-middle, middle-ring, ring-pinky spread in the palm plane, degrees; NaN when a finger is folded out of the plane. */
    val spread: FloatArray,
    /** Thumb tip in the hand frame divided by the hand length. */
    val thumbTip: V3,
    /** Palm side normal in the practice frame. */
    val palmNormal: V3,
    /** Index knuckle to tip direction in the practice frame. */
    val pointing: V3,
    val handLength: Float,
) {
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    fun line(): String =
        "flex=" + flex.joinToString(",") { it.toInt().toString() } + " spread=" + spread.joinToString(",") { if (it.isNaN()) "-" else it.toInt().toString() } +
            " thumb=(%.2f,%.2f,%.2f) palm=(%.2f,%.2f,%.2f) len=%.3f".format(thumbTip.x, thumbTip.y, thumbTip.z, palmNormal.x, palmNormal.y, palmNormal.z, handLength)
}

object HandMath {
    const val DEFAULT_HAND_LENGTH = 0.185f

    /** Wrist -> middle knuckle -> middle segments. */
    fun handLength(local: List<V3>): Float {
        var len = local[J.prox(1)].length
        for (k in 1 until 4) len += (local[J.base(1) + k + 1] - local[J.base(1) + k]).length
        return len
    }

    /** Joints in the hand frame, mirrored for a left hand (right-hand terms). */
    fun rightLocal(joints: List<V3>, frame: HandFrame, handed: Handed): List<V3> =
        joints.map { p -> frame.toLocal(p).let { if (handed == Handed.LEFT) it.mirrorX() else it } }

    fun features(joints: List<V3>, handed: Handed, practice: PracticeFrame, handLength: Float? = null): Features? {
        val frame = HandFrame.of(joints, handed) ?: return null
        val local = rightLocal(joints, frame, handed)
        val len = handLength ?: handLength(local)
        val flex = FloatArray(4) { f -> flexOf(local, f) }
        val spread = FloatArray(3) { i -> spreadOf(local, i) }
        fun dir(v: V3): V3 = practice.toLocal(v).let { if (handed == Handed.LEFT) it.mirrorX() else it }.normalized()
        val pointing = dir(joints[J.tip(0)] - joints[J.prox(0)])
        return Features(flex, spread, local[J.THUMB_TIP] * (1f / len), dir(frame.palmNormal), pointing, len)
    }

    /** Features of a template pose (right hand, hand frame) held in the letter's orientation. */
    fun templateFeatures(local: List<V3>, orient: Q4): Features {
        val len = handLength(local)
        val flex = FloatArray(4) { f -> flexOf(local, f) }
        val spread = FloatArray(3) { i -> spreadOf(local, i) }
        val pointing = orient.rotate(local[J.tip(0)] - local[J.prox(0)]).normalized()
        return Features(flex, spread, local[J.THUMB_TIP] * (1f / len), orient.rotate(V3(0f, 0f, -1f)), pointing, len)
    }

    /**
     * v0.1.1 finger curl: angle between the metacarpal direction and the knuckle-to-fingertip chord, degrees (0 =
     * straight, ~180 = tip folded back into the palm). Long vectors, so tracking noise on the short finger segments
     * hardly moves it (the old sum of three joint angles read a straight pinky as 57-103 degrees under 3 mm noise).
     */
    fun flexOf(local: List<V3>, finger: Int): Float {
        val b = J.base(finger)
        return angleDeg(local[b + 1] - local[b], local[b + 4] - local[b + 1])
    }

    fun spreadOf(local: List<V3>, pair: Int): Float {
        fun planar(f: Int): V3? {
            val d = local[J.prox(f) + 1] - local[J.prox(f)]
            val p = V3(d.x, d.y, 0f)
            return if (p.length < d.length * 0.3f) null else p
        }
        val a = planar(pair) ?: return Float.NaN
        val b = planar(pair + 1) ?: return Float.NaN
        return angleDeg(a, b)
    }

    /**
     * Places a hand-frame pose (right-hand terms) into space: mirrored for a left hand, rotated by [rotation]
     * (hand frame -> space), scaled by [scale] about the wrist, wrist at [wrist].
     */
    fun place(local: List<V3>, handed: Handed, wrist: V3, rotation: Q4, scale: Float = 1f): List<V3> =
        local.map { p -> wrist + rotation.rotate((if (handed == Handed.LEFT) p.mirrorX() else p) * scale) }

    /** A right-hand orientation (practice frame) as the dominant hand's: mirrored for a left hand. */
    fun handedOrient(orient: Q4, handed: Handed): Q4 = if (handed == Handed.RIGHT) orient else Q4(orient.x, -orient.y, -orient.z, orient.w)
}
