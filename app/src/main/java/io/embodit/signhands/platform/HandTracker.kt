package io.embodit.signhands.platform

import android.os.Build
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.hand.HandJoint
import com.pico.spatial.tracking.hand.HandPose
import com.pico.spatial.tracking.hand.HandTrackingProvider
import io.embodit.signhands.domain.Handed
import io.embodit.signhands.domain.J
import io.embodit.signhands.domain.Support
import io.embodit.signhands.domain.V3

/**
 * Hand joints from `HandTrackingProvider` (Full Space only), read once per frame by the scene's ECS system.
 * Started when the Stage scene attaches and stopped when it detaches (also on pause, the Stage tears down).
 * On the emulator the provider is never started (it reports SUPPORTED without data and would break the
 * emulator's gaze-and-pinch, Spatial Ruler finding); debug `fakeHand` stands in.
 */
class HandTracker {
    private var provider: HandTrackingProvider? = null
    private var lastSupport: DataProvider.SupportState? = null

    /** Debug `support=<name>`: forces the reported support state. */
    @Volatile var forced: Support? = null

    val isEmulator: Boolean by lazy {
        Build.FINGERPRINT.contains("emulator", ignoreCase = true) || Build.SUPPORTED_ABIS.any { it.startsWith("x86") } || Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true)
    }

    fun start() {
        if (provider != null || isEmulator) {
            if (isEmulator) SignLog.i("TRACK hands not started on the emulator (debug fakeHand)")
            return
        }
        val p = HandTrackingProvider()
        val result = runCatching { p.start() }
        lastSupport = runCatching { p.supportState }.getOrNull()
        SignLog.i("TRACK hands start=${result.getOrNull() ?: result.exceptionOrNull()} support=$lastSupport")
        provider = p
    }

    fun stop() {
        provider?.let { runCatching { it.stop() } }
        provider = null
        SignLog.i("TRACK hands stopped")
    }

    fun support(): Support {
        forced?.let { return it }
        if (isEmulator) return Support.EMULATOR
        val p = provider ?: return Support.WAIT
        val s = runCatching { p.supportState }.getOrNull()
        if (s != lastSupport) {
            lastSupport = s
            SignLog.i("TRACK hands support=$s")
        }
        return when (s) {
            DataProvider.SupportState.SUPPORTED -> Support.OK
            DataProvider.SupportState.NOT_IN_FULL_SPACE -> Support.WAIT
            DataProvider.SupportState.WITHOUT_PERMISSION -> Support.NO_PERMISSION
            null -> Support.WAIT
            else -> Support.OFF
        }
    }

    /** The dominant hand's 25 joints (app order, Stage space), or null when it is not tracked. */
    fun joints(handed: Handed): List<V3>? {
        val p = provider ?: return null
        if (support() != Support.OK) return null
        val data = runCatching { p.latestData }.getOrNull() ?: return null
        val pose = (if (handed == Handed.RIGHT) data.right else data.left) ?: return null
        return convert(pose)
    }

    private fun convert(pose: HandPose): List<V3>? {
        val byIndex = HashMap<HandJoint.Index, V3>()
        for (j in pose.handJoints) {
            val p = j.position
            byIndex[j.index] = V3(p.x, p.y, p.z)
        }
        val out = ArrayList<V3>(J.COUNT)
        for (index in ORDER) out += byIndex[index]?.takeIf { it.isFinite() } ?: return null
        // a lost hand comes back as joints all at the origin
        if (out.all { it.length < 1e-4f }) return null
        return out
    }

    private companion object {
        val ORDER =
            listOf(
                HandJoint.Index.WRIST,
                HandJoint.Index.THUMB_METACARPAL, HandJoint.Index.THUMB_PROXIMAL, HandJoint.Index.THUMB_DISTAL, HandJoint.Index.THUMB_TIP,
                HandJoint.Index.INDEX_METACARPAL, HandJoint.Index.INDEX_PROXIMAL, HandJoint.Index.INDEX_INTERMEDIATE, HandJoint.Index.INDEX_DISTAL, HandJoint.Index.INDEX_TIP,
                HandJoint.Index.MIDDLE_METACARPAL, HandJoint.Index.MIDDLE_PROXIMAL, HandJoint.Index.MIDDLE_INTERMEDIATE, HandJoint.Index.MIDDLE_DISTAL, HandJoint.Index.MIDDLE_TIP,
                HandJoint.Index.RING_METACARPAL, HandJoint.Index.RING_PROXIMAL, HandJoint.Index.RING_INTERMEDIATE, HandJoint.Index.RING_DISTAL, HandJoint.Index.RING_TIP,
                HandJoint.Index.LITTLE_METACARPAL, HandJoint.Index.LITTLE_PROXIMAL, HandJoint.Index.LITTLE_INTERMEDIATE, HandJoint.Index.LITTLE_DISTAL, HandJoint.Index.LITTLE_TIP,
            )
    }
}
