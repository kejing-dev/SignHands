package io.embodit.signhands.platform

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * The one app sound (design `sound`): a short soft "ding" when a letter is right, synthesised by the system
 * tone generator (no asset, no licence) at a restrained volume. Muting is the flow's setting; the caller checks it.
 */
class Chime {
    private var generator: ToneGenerator? = null

    fun ding() {
        try {
            val g = generator ?: ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME).also { generator = it }
            g.startTone(ToneGenerator.TONE_PROP_ACK, 150)
        } catch (t: Throwable) {
            SignLog.w("CHIME failed: $t")
        }
    }

    fun release() {
        runCatching { generator?.release() }
        generator = null
    }

    private companion object {
        const val VOLUME = 60
    }
}
