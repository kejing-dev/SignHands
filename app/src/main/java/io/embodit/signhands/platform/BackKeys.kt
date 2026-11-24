package io.embodit.signhands.platform

import android.app.Activity
import android.view.KeyEvent
import android.view.View
import android.view.Window

/**
 * D60 补 (gate back-key; sign-hands v0.1.6, copied from four-drop / GlowBench v0.1.3): BACK and controller B are caught at the earliest
 * layer of every window of the app and all go to the one handler (SpatialApplication.onBack). An OnBackPressedCallback alone misses
 * them: after a tap on the native TabBar the key goes into the tab's window and never reaches the dispatcher. So:
 *  1. every Activity window: its Window.Callback is wrapped, dispatchKeyEvent sees BACK / BUTTON_B before any view;
 *  2. every other window of the process (TabBar, system-window pieces the SDK adds through WindowManager): its root
 *     view gets an unhandled-key listener, swept on each Activity event and on every tab tap;
 *  3. the main window's Compose root routes BACK / BUTTON_B in onPreviewKeyEvent (SignWindow, [eatPadKeys]).
 * DOWN is consumed silently, UP fires the handler once; [BackKeys.fire] drops a second fire for the same
 * press when two layers see it (same event time).
 */
object BackKeys {
    var handler: (String) -> Unit = {}

    private var lastFiredDownTime = -1L

    fun isBack(code: Int) = code == KEYCODE_BACK || code == KEYCODE_BUTTON_B || code == KEYCODE_ESCAPE

    /** Returns true if the event was BACK / B (consumed). The handler runs on the UP of a press, once per press. */
    fun route(e: KeyEvent, where: String): Boolean =
        route(e.keyCode, e.action, e.downTime, e.isCanceled, where)

    /** Same as [route] with the raw fields (unit-tested without android.view.KeyEvent). */
    fun route(code: Int, action: Int, downTime: Long, canceled: Boolean, where: String): Boolean {
        if (!isBack(code)) return false
        if (action == ACTION_UP && !canceled) fire(downTime, "$where ${codeName(code)}")
        return true
    }

    private fun codeName(code: Int) = when (code) { KEYCODE_BACK -> "BACK"; KEYCODE_BUTTON_B -> "BUTTON_B"; else -> "ESCAPE" }

    // KeyEvent constants (stable since API 1 / 9), kept here so the JVM unit test needs no android stubs
    const val KEYCODE_BACK = 4
    const val KEYCODE_BUTTON_B = 97
    const val KEYCODE_ESCAPE = 111
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1

    @Synchronized
    internal fun reset() { lastFiredDownTime = -1L }

    @Synchronized
    private fun fire(downTime: Long, where: String) {
        if (downTime == lastFiredDownTime) return
        lastFiredDownTime = downTime
        handler(where)
    }

    /** Layer 1: wrap the Activity window's callback (idempotent). */
    fun wrap(a: Activity) = wrap(a.window, a.javaClass.simpleName)

    /**
     * v0.1.6: any window's callback, also the native TabBar's own Dialog window (found by [io.embodit.signhands.ui.window.TabKeyGuard]):
     * its focused tab takes A / X / ENTER / DPAD_CENTER as a click (factory feedback "SpatialUI TabBar 在自己的 Dialog
     * 窗口里", terrarium v0.1.3 workaround), so the unassigned pad keys are eaten there before any view sees them.
     */
    fun wrap(w: Window?, name: String) {
        val cb = w?.callback ?: return
        if (cb is Wrapped) return
        w.callback = Wrapped(cb, name)
        SignLog.i("BACK keys wrapped on $name")
    }

    /** Pad / confirm keys the app gives no job (D60): A, X, Y, ENTER, NUMPAD_ENTER, DPAD_CENTER. */
    fun isUnassigned(code: Int) = code in UNASSIGNED

    private val UNASSIGNED = setOf(96, 99, 100, 66, 160, 23)

    private class Wrapped(private val d: Window.Callback, private val name: String) : Window.Callback by d {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (route(event, "key:$name")) return true
            if (isUnassigned(event.keyCode)) {
                if (event.action == ACTION_UP) SignLog.i("KEY eaten $name ${event.keyCode}")
                return true
            }
            return d.dispatchKeyEvent(event)
        }
    }

    private val hooked = java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    /** Layer 2: every root view of this process (WindowManagerGlobal), so a focused TabBar window cannot swallow BACK. */
    private var sweepFailLogged = false

    fun sweep(reason: String) {
        val roots = runCatching {
            val c = Class.forName("android.view.WindowManagerGlobal")
            val g = c.getMethod("getInstance").invoke(null)
            @Suppress("UNCHECKED_CAST")
            val names = c.getMethod("getViewRootNames").invoke(g) as Array<String>
            names.mapNotNull { n -> runCatching { c.getMethod("getRootView", String::class.java).invoke(g, n) as? View }.getOrNull()?.let { n to it } }
        }.getOrElse {
            if (!sweepFailLogged) SignLog.w("BACK sweep failed: $it")
            sweepFailLogged = true
            emptyList()
        }
        for ((n, v) in roots) hook(v, n, reason)
    }

    private fun hook(v: View, name: String, reason: String) {
        if (!hooked.add(v)) return
        val short = name.substringAfterLast('/').take(48)
        // runs when no view in that window handled the key (a window without an Activity never reaches the dispatcher)
        v.addOnUnhandledKeyEventListener { _, e -> route(e, "unhandled:$short") }
        SignLog.i("BACK keys hooked root $short ($reason)")
    }
}
