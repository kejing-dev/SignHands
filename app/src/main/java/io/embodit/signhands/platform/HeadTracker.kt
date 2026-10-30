package io.embodit.signhands.platform

import android.os.SystemClock
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import io.embodit.signhands.domain.Q4
import io.embodit.signhands.domain.V3
import io.embodit.signhands.domain.headPitchOf
import io.embodit.signhands.domain.headYawOf

/** Head pose in Stage space. */
data class HeadPose(val position: V3, val rotation: Q4) {
    val yaw: Float
        get() = headYawOf(rotation)

    val pitch: Float
        get() = headPitchOf(rotation)
}

/**
 * HMD pose source (`HMDTrackingProvider`), read once per frame from the ECS system; the last valid
 * pose is kept so that a missing sample never makes content jump. Same pattern as Spatial Ruler.
 *
 * Lost tracking (M4): no valid pose for [LOST_AFTER_MS]. The emulator never loses tracking; the debug
 * command `mod M4=1` forces it.
 */
class HeadTracker {
    private var provider: HMDTrackingProvider? = null
    private var last: HeadPose? = null
    private var lastValidMs = 0L

    @Volatile var forceLost = false

    fun start() {
        if (provider != null) return
        val p = HMDTrackingProvider()
        val result = runCatching { p.start() }
        SignLog.i("TRACK hmd start=${result.getOrNull() ?: result.exceptionOrNull()} support=${runCatching { p.supportState }.getOrNull()}")
        provider = p
        lastValidMs = SystemClock.elapsedRealtime()
    }

    fun stop() {
        runCatching { provider?.stop() }
        provider = null
        last = null
    }

    fun lost(nowMs: Long): Boolean = forceLost || (provider != null && last != null && nowMs - lastValidMs > LOST_AFTER_MS)

    /** Current head pose, or the last valid one, or null before the first sample. */
    fun pose(): HeadPose? {
        val p = provider ?: return last
        val pose = runCatching { p.latestData.hmdPose }.getOrNull() ?: return last
        val position = pose.position
        val rotation = pose.rotation
        val sample = HeadPose(V3(position.x, position.y, position.z), Q4(rotation.x, rotation.y, rotation.z, rotation.w))
        if (!sample.position.isFinite()) return last
        // v0.1.2: before tracking settles the HMD reports an all-zero pose (or a zero quaternion); content placed from it
        // landed at the Stage origin (floor level) and stayed there once the panel stopped following the head
        val q = sample.rotation
        val qLen = q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w
        if (qLen < 0.5f || !qLen.isFinite() || (sample.position.length < 1e-4f && q.w > 0.99999f)) return last
        last = sample
        lastValidMs = SystemClock.elapsedRealtime()
        return sample
    }

    private companion object {
        const val LOST_AFTER_MS = 500L
    }
}
