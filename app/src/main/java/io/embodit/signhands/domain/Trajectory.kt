package io.embodit.signhands.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * v0.1.3 (profile D27): moving targets (J, Z and the word signs) are judged by the shape of the path only. A path is
 * resampled by arc length (speed does not count), moved to its centroid and scaled to its largest side (where and how
 * big does not count), then compared with dynamic time warping. Depth is weighted half: the tracker is least sure of it.
 */
object Trajectory {
    const val N = 24
    const val Z_WEIGHT = 0.5f
    const val BAND = 4

    /** Polyline through [keys] (metres), densified so that every key is kept. */
    fun polyline(keys: List<V3>, perSegment: Int = 8): List<V3> {
        if (keys.size < 2) return keys
        val out = ArrayList<V3>()
        for (i in 0 until keys.size - 1) for (k in 0 until perSegment) out += keys[i] + (keys[i + 1] - keys[i]) * (k / perSegment.toFloat())
        out += keys.last()
        return out
    }

    fun length(pts: List<V3>): Float {
        var s = 0f
        for (i in 1 until pts.size) s += (pts[i] - pts[i - 1]).length
        return s
    }

    /** Largest side of the bounding box, metres. */
    fun extent(pts: List<V3>): Float {
        if (pts.isEmpty()) return 0f
        var lo = pts[0]
        var hi = pts[0]
        for (p in pts) {
            lo = V3(min(lo.x, p.x), min(lo.y, p.y), min(lo.z, p.z))
            hi = V3(max(hi.x, p.x), max(hi.y, p.y), max(hi.z, p.z))
        }
        return max(hi.x - lo.x, max(hi.y - lo.y, hi.z - lo.z))
    }

    /** [n] points evenly spaced along the path; null for a path with no length. */
    fun resample(pts: List<V3>, n: Int = N): List<V3>? {
        val total = length(pts)
        if (pts.size < 2 || total < 1e-5f) return null
        val step = total / (n - 1)
        val out = ArrayList<V3>(n)
        out += pts[0]
        var acc = 0f
        var i = 1
        var prev = pts[0]
        while (out.size < n - 1 && i < pts.size) {
            val seg = (pts[i] - prev).length
            if (acc + seg >= step && seg > 0f) {
                val k = (step - acc) / seg
                val p = prev + (pts[i] - prev) * k
                out += p
                prev = p
                acc = 0f
            } else {
                acc += seg
                prev = pts[i]
                i++
            }
        }
        while (out.size < n) out += pts.last()
        return out
    }

    /** Centroid at the origin, largest side = 1. */
    fun normalize(pts: List<V3>): List<V3> {
        var c = V3.ZERO
        for (p in pts) c += p
        c = c * (1f / pts.size)
        val e = extent(pts).coerceAtLeast(1e-4f)
        return pts.map { (it - c) * (1f / e) }
    }

    /** Rotation in the plane facing the user (about the depth axis), degrees. */
    fun rotateFacing(pts: List<V3>, deg: Float): List<V3> {
        val r = deg.toRad()
        val c = cos(r)
        val s = sin(r)
        return pts.map { V3(it.x * c - it.y * s, it.x * s + it.y * c, it.z) }
    }

    private fun d(a: V3, b: V3): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = (a.z - b.z) * Z_WEIGHT
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Mean cost along the best warping path (normalized units; 0 = same shape). Both paths are resampled by arc length,
     * so a point may only match one within [band] steps of the same share of the path: a J's hook cannot be squeezed
     * into its last point to look like a straight line.
     */
    fun dtw(a: List<V3>, b: List<V3>, band: Int = BAND): Float {
        val n = a.size
        val m = b.size
        val inf = Float.MAX_VALUE / 4f
        val cost = Array(n + 1) { FloatArray(m + 1) { inf } }
        val len = Array(n + 1) { IntArray(m + 1) }
        cost[0][0] = 0f
        for (i in 1..n) for (j in 1..m) {
            if (abs(i * m / n - j) > band) continue
            var best = cost[i - 1][j - 1]
            var bl = len[i - 1][j - 1]
            if (cost[i - 1][j] < best) { best = cost[i - 1][j]; bl = len[i - 1][j] }
            if (cost[i][j - 1] < best) { best = cost[i][j - 1]; bl = len[i][j - 1] }
            val dist = d(a[i - 1], b[j - 1])
            cost[i][j] = best + dist * dist
            len[i][j] = bl + 1
        }
        return kotlin.math.sqrt(cost[n][m] / len[n][m].coerceAtLeast(1))
    }

    /** Moving average over [radius] samples each side (tracking jitter off a short path). */
    fun smooth(pts: List<V3>, radius: Int = 2): List<V3> =
        List(pts.size) { i ->
            val a = (i - radius).coerceAtLeast(0)
            val b = (i + radius).coerceAtMost(pts.size - 1)
            var s = V3.ZERO
            for (k in a..b) s += pts[k]
            s * (1f / (b - a + 1))
        }

    /** A template ready for [match]: resampled and normalized. */
    fun template(path: List<V3>): List<V3> = normalize(resample(path) ?: path)

    /** Rotation about the vertical (the user turned half to the side), degrees. */
    fun rotateYaw(pts: List<V3>, deg: Float): List<V3> {
        val r = deg.toRad()
        val c = cos(r)
        val s = sin(r)
        return pts.map { V3(it.x * c + it.z * s, it.y, -it.x * s + it.z * c) }
    }

    /** Best DTW cost of [pts] against [template] turned by each of [turns] degrees in the facing plane and [yaws] about the vertical. */
    fun match(pts: List<V3>, template: List<V3>, turns: FloatArray, yaws: FloatArray = YAWS): Float {
        val r = resample(pts) ?: return Float.MAX_VALUE
        val a = normalize(r)
        var best = Float.MAX_VALUE
        for (t in turns) best = min(best, dtw(a, if (t == 0f) template else normalize(rotateFacing(template, t))))
        for (y in yaws) best = min(best, dtw(a, normalize(rotateYaw(template, y))))
        return best
    }

    private val YAWS = floatArrayOf(-30f, 30f)
}
