package io.embodit.signhands.data

import android.content.Context
import io.embodit.signhands.domain.GhostMode
import io.embodit.signhands.domain.Handed
import io.embodit.signhands.domain.Lang
import io.embodit.signhands.domain.Progress
import io.embodit.signhands.domain.Settings
import io.embodit.signhands.platform.SignLog
import org.json.JSONObject
import java.io.File

/**
 * Private JSON files (design `dataModel`): progress.json and settings.json. Clearing removes progress only,
 * so the dominant hand need not be chosen again. Fully offline.
 */
class Store(context: Context) {
    private val dir = context.filesDir
    private val progressFile = File(dir, "progress.json")
    private val settingsFile = File(dir, "settings.json")

    fun loadSettings(): Settings? =
        runCatching {
            if (!settingsFile.exists()) return null
            val o = JSONObject(settingsFile.readText())
            Settings(
                lang = runCatching { Lang.valueOf(o.optString("lang", "SYSTEM")) }.getOrDefault(Lang.SYSTEM),
                hand = runCatching { Handed.valueOf(o.optString("hand", "RIGHT")) }.getOrDefault(Handed.RIGHT),
                ghost = runCatching { GhostMode.valueOf(o.optString("ghostMode", "OVERLAY")) }.getOrDefault(GhostMode.OVERLAY),
                muted = o.optBoolean("muted", false),
            )
        }.onFailure { SignLog.w("STORE settings unreadable: $it") }.getOrNull()

    fun saveSettings(s: Settings) {
        val o = JSONObject().put("lang", s.lang.name).put("hand", s.hand.name).put("ghostMode", s.ghost.name).put("muted", s.muted)
        write(settingsFile, o.toString())
    }

    fun loadProgress(): Progress =
        runCatching { if (progressFile.exists()) Progress.fromJson(progressFile.readText()) else Progress() }
            .onFailure { SignLog.w("STORE progress unreadable: $it") }
            .getOrDefault(Progress())

    fun saveProgress(p: Progress) = write(progressFile, p.toJson())

    fun clearProgress() {
        runCatching { progressFile.delete() }
    }

    fun clearAll() {
        clearProgress()
        runCatching { settingsFile.delete() }
    }

    private fun write(file: File, text: String) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }.onFailure { SignLog.e("STORE write ${file.name} failed: $it", it) }
    }
}
