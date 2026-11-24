package io.embodit.signhands.ui.window

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import io.embodit.signhands.platform.SignLog

/** Id and tag of the main window (tag != null: "reuse" mode, one instance). */
const val PANEL_ID = "LessonWindow"
const val PANEL_TAG = "main"

/**
 * v0.1.4: the main window's state shared by the Stage and the window (one process, factory native-window-move §3.4;
 * same as drone-sight v0.1.3): the window reports its lifecycle, the Stage opens / recalls it with its navigator.
 * Main thread only.
 */
object WindowCtl {
    /** Last lifecycle event of the window ("ON_CREATE" … "ON_DESTROY"), "never" before the first open. */
    var panelState by mutableStateOf("never")
        private set

    /** Bumped on every ON_CREATE: the scene re-estimates where the system put the window. */
    var createdEpoch by mutableIntStateOf(0)
        private set

    /** The Stage's navigator while the Stage is composed. */
    var navigator: SpatialNavigator? = null

    private val main = Handler(Looper.getMainLooper())
    private var recallPending = false
    private var askedAtMs = 0L

    val open: Boolean
        get() = panelState != "never" && panelState != "ON_DESTROY" && panelState != "ON_STOP"

    /** An open was sent and the window has not reported ON_CREATE yet (the system takes ~1-6 s). */
    private var opening = false

    /** Set once the window logged its dp size (the converter is handed out again on every recomposition). */
    var dpLogged = false

    /**
     * The Stage shows "打开面板" while the window is closed / minimised, or never came up within 8 s of asking; not in
     * the second between an open and the window's ON_CREATE (the chip flashed there).
     */
    fun chipWanted(now: Long): Boolean =
        when (panelState) {
            "ON_DESTROY", "ON_STOP" -> !recallPending && !(opening && now - askedAtMs < 8000)
            "never" -> askedAtMs > 0 && now - askedAtMs > 8000
            else -> false
        }

    /** v0.1.6 (profile D10): ask for the window and open it at once (no head-pitch gate). */
    fun openWindow(why: String) = openNow(why)

    private fun openNow(why: String) {
        val nav = navigator ?: return SignLog.w("WINDOW open why=$why: no navigator")
        askedAtMs = SystemClock.elapsedRealtime()
        opening = true
        SignLog.i("WINDOW open why=$why state=$panelState")
        runCatching { nav.openWindowContainer(PANEL_ID, PANEL_TAG) }.onFailure { SignLog.w("WINDOW open failed: $it") }
    }

    /** The chip: a minimised window is restored where it was, a closed one opens in front of you. */
    fun reopen(why: String) {
        if (panelState == "ON_STOP" && navigator?.let { runCatching { it.restoreWindowContainer(PANEL_ID, PANEL_TAG) }.getOrDefault(false) } == true) {
            SignLog.i("WINDOW restore why=$why")
            return
        }
        openWindow(why)
    }

    /**
     * "叫回面前": the system has no call to move a window, and a second open of an open window only focuses it, so a
     * recall closes it and opens it again once it reported ON_DESTROY (an open right after close is swallowed, §3.5).
     */
    fun recall(why: String) {
        if (panelState == "never" || panelState == "ON_DESTROY") return openWindow(why)
        val nav = navigator ?: return
        recallPending = true
        SignLog.i("WINDOW recall why=$why: close, then open after ON_DESTROY")
        runCatching { nav.closeWindowContainer(PANEL_ID, PANEL_TAG) }.onFailure { SignLog.w("WINDOW close failed: $it"); recallPending = false }
    }

    fun onLifecycle(event: String) {
        panelState = event
        SignLog.i("WINDOW lifecycle $event")
        if (event == "ON_CREATE") {
            createdEpoch++
            opening = false
        }
        if (event == "ON_DESTROY" && recallPending) {
            recallPending = false
            main.postDelayed({ openWindow("recall") }, 300)
        }
    }

    /** Quit: close the window first (it would otherwise outlive the Stage in the Shared Space, §7.7). */
    fun closeForQuit() {
        recallPending = false
        runCatching { navigator?.closeWindowContainer(PANEL_ID, PANEL_TAG) }
    }
}
