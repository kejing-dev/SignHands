package io.embodit.signhands.domain

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Angle into (-180, 180]. */
fun wrap180(a: Float): Float {
    var x = a % 360f
    if (x > 180f) x -= 360f
    if (x <= -180f) x += 360f
    return x
}

/** Angle into [0, 360). */
fun wrap360(a: Float): Float {
    var x = a % 360f
    if (x < 0f) x += 360f
    return x
}

fun Float.toRad(): Float = this * (PI.toFloat() / 180f)

fun Float.toDeg(): Float = this * (180f / PI.toFloat())

/** Small vector type of the domain, in Stage metres (x right, y up, -z ahead). */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)

    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)

    operator fun times(k: Float) = V3(x * k, y * k, z * k)

    operator fun unaryMinus() = V3(-x, -y, -z)

    val length: Float
        get() = sqrt(x * x + y * y + z * z)

    fun normalized(): V3 {
        val l = length
        return if (l < 1e-6f) this else V3(x / l, y / l, z / l)
    }

    fun dot(o: V3): Float = x * o.x + y * o.y + z * o.z

    fun cross(o: V3): V3 = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    fun isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

    companion object {
        val ZERO = V3(0f, 0f, 0f)
        val UP = V3(0f, 1f, 0f)
        val AHEAD = V3(0f, 0f, -1f)
    }
}

/**
 * Unit horizontal direction of a heading. Heading 0 is -Z (straight ahead at start); a positive
 * heading turns left (towards -X), i.e. counter-clockwise seen from above, the way the Moon goes.
 */
fun dirOf(yawDeg: Float): V3 {
    val r = yawDeg.toRad()
    return V3(-sin(r), 0f, -cos(r))
}

/** Heading of a direction, same convention as [dirOf]. */
fun yawOf(d: V3): Float = atan2(-d.x, -d.z).toDeg()

/** Unit quaternion; rotations compose as `a * b` = apply b first, then a. */
data class Q4(val x: Float, val y: Float, val z: Float, val w: Float) {
    operator fun times(o: Q4): Q4 =
        Q4(
            w * o.x + x * o.w + y * o.z - z * o.y,
            w * o.y - x * o.z + y * o.w + z * o.x,
            w * o.z + x * o.y - y * o.x + z * o.w,
            w * o.w - x * o.x - y * o.y - z * o.z,
        )

    fun rotate(v: V3): V3 {
        // v' = v + 2 w (q x v) + 2 q x (q x v)
        val qv = V3(x, y, z)
        val t = qv.cross(v) * 2f
        return v + t * w + qv.cross(t)
    }

    val forward: V3
        get() = rotate(V3.AHEAD)

    companion object {
        val IDENTITY = Q4(0f, 0f, 0f, 1f)

        /** Rotation about Y: maps -Z to [dirOf] (deg) and +Z to the opposite. */
        fun yaw(deg: Float): Q4 {
            val h = deg.toRad() / 2f
            return Q4(0f, sin(h), 0f, cos(h))
        }

        /** Rotation about X: a positive angle tilts -Z upwards (and +Z downwards). */
        fun pitch(deg: Float): Q4 {
            val h = deg.toRad() / 2f
            return Q4(sin(h), 0f, 0f, cos(h))
        }

        fun axisAngle(axis: V3, deg: Float): Q4 {
            val a = axis.normalized()
            val h = deg.toRad() / 2f
            val s = sin(h)
            return Q4(a.x * s, a.y * s, a.z * s, cos(h))
        }

        /** three.js `Euler(x, y, z, "XYZ")`: qx * qy * qz (the sample's orientation codes). */
        fun eulerXyz(xDeg: Float, yDeg: Float, zDeg: Float): Q4 =
            axisAngle(V3(1f, 0f, 0f), xDeg) * axisAngle(V3(0f, 1f, 0f), yDeg) * axisAngle(V3(0f, 0f, 1f), zDeg)

        /** Rotation whose columns are the given orthonormal axes (maps +X to [x], +Y to [y], +Z to [z]). */
        fun fromBasis(x: V3, y: V3, z: V3): Q4 {
            val m00 = x.x
            val m10 = x.y
            val m20 = x.z
            val m01 = y.x
            val m11 = y.y
            val m21 = y.z
            val m02 = z.x
            val m12 = z.y
            val m22 = z.z
            val t = m00 + m11 + m22
            val q =
                if (t > 0f) {
                    val s = 0.5f / sqrt(t + 1f)
                    Q4((m21 - m12) * s, (m02 - m20) * s, (m10 - m01) * s, 0.25f / s)
                } else if (m00 > m11 && m00 > m22) {
                    val s = 2f * sqrt(1f + m00 - m11 - m22)
                    Q4(0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
                } else if (m11 > m22) {
                    val s = 2f * sqrt(1f + m11 - m00 - m22)
                    Q4((m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s)
                } else {
                    val s = 2f * sqrt(1f + m22 - m00 - m11)
                    Q4((m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s)
                }
            return q.normalized()
        }
    }

    val inverse: Q4
        get() = Q4(-x, -y, -z, w)

    fun normalized(): Q4 {
        val l = sqrt(x * x + y * y + z * z + w * w)
        return if (l < 1e-6f) IDENTITY else Q4(x / l, y / l, z / l, w / l)
    }

    fun dot(o: Q4): Float = x * o.x + y * o.y + z * o.z + w * o.w

    fun slerp(o: Q4, t: Float): Q4 {
        var b = o
        var d = dot(o)
        if (d < 0f) {
            b = Q4(-o.x, -o.y, -o.z, -o.w)
            d = -d
        }
        if (d > 0.9995f) return Q4(x + (b.x - x) * t, y + (b.y - y) * t, z + (b.z - z) * t, w + (b.w - w) * t).normalized()
        val th = kotlin.math.acos(d)
        val s = sin(th)
        val ka = sin((1f - t) * th) / s
        val kb = sin(t * th) / s
        return Q4(x * ka + b.x * kb, y * ka + b.y * kb, z * ka + b.z * kb, w * ka + b.w * kb)
    }
}

/** Unsigned angle between two directions, degrees. */
fun angleDeg(a: V3, b: V3): Float {
    val la = a.length
    val lb = b.length
    if (la < 1e-6f || lb < 1e-6f) return 0f
    return kotlin.math.acos((a.dot(b) / (la * lb)).coerceIn(-1f, 1f)).toDeg()
}

/** Yaw (heading) of a head rotation, degrees. */
fun headYawOf(q: Q4): Float = Placement.heading(q)

/** Pitch of a head rotation, degrees, positive when looking up. */
fun headPitchOf(q: Q4): Float = asin(q.forward.y.coerceIn(-1f, 1f)).toDeg()

/**
 * Rotation that turns a panel standing at [at] so that its +Z (the face) points at [head]: a yaw
 * about the vertical, then a pitch towards the head, never a roll.
 */
fun faceHead(at: V3, head: V3): Q4 {
    val d = head - at
    val h = sqrt(d.x * d.x + d.z * d.z)
    val yaw = atan2(d.x, d.z).toDeg()
    val pitch = -atan2(d.y, h).toDeg()
    return Q4.yaw(yaw) * Q4.pitch(pitch)
}

/** Critically damped spring towards zero error, semi-implicit Euler. Returns (error, velocity). */
fun springStep(error: Float, velocity: Float, tau: Float, dt: Float): Pair<Float, Float> {
    val v = velocity + (-(2f / tau) * velocity - error / (tau * tau)) * dt
    val e = error + v * dt
    return e to v
}

fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

fun smoothstep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}
