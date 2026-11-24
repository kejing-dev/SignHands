package io.embodit.signhands.platform

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.signhands.mainApp
import io.embodit.signhands.ui.SignViewModel
import io.embodit.signhands.ui.window.WindowCtl

class SpatialApplication : Application() {
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        // v0.1.6 (profile D60, gate back-key): BACK / controller B are caught at the earliest layer of every window
        // (BackKeys); the OnBackPressedCallback stays as the last fallback. The app never goes to the background on B.
        BackKeys.handler = { where -> main.post { onBack(where) } }
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityCreated(a: Activity, b: Bundle?) {
                    BackKeys.wrap(a)
                    val ca = a as? ComponentActivity ?: return SignLog.w("BACK guard: ${a.javaClass.simpleName} is not a ComponentActivity")
                    ca.onBackPressedDispatcher.addCallback(
                        ca,
                        object : OnBackPressedCallback(true) {
                            override fun handleOnBackPressed() = BackKeys.handler("dispatcher:${a.javaClass.simpleName}")
                        },
                    )
                    SignLog.i("BACK guard on ${a.javaClass.simpleName}")
                }

                override fun onActivityPostCreated(a: Activity, b: Bundle?) {
                    BackKeys.wrap(a)
                    BackKeys.sweep("created")
                }

                override fun onActivityStarted(a: Activity) = BackKeys.sweep("started")

                override fun onActivityResumed(a: Activity) {
                    BackKeys.wrap(a)
                    BackKeys.sweep("resumed")
                }

                override fun onActivityPostResumed(a: Activity) = BackKeys.sweep("post-resumed")

                override fun onActivityPaused(a: Activity) {}

                override fun onActivityStopped(a: Activity) {}

                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}

                override fun onActivityDestroyed(a: Activity) {}
            },
        )
        launch(::mainApp)
        main.post(resweep)
    }

    /**
     * The system adds a root of its own for the Stage ("Sys…:LaunchActivity", key focus while the window is closed)
     * after the activity events: re-sweep every second so its BACK / B arrive (bandage-wrap v0.1.x finding).
     */
    private val resweep =
        object : Runnable {
            override fun run() {
                BackKeys.sweep("tick")
                main.postDelayed(this, 1000L)
            }
        }

    /**
     * BACK / controller B from any window: a closed or minimised window comes back (restored where it was, or opened in
     * front of you); with the window open it is the page's "‹" (a lesson / word / letter goes back to the page it was
     * started from, the confirm pages cancel, other pages go home). Never leaves the app.
     */
    private fun onBack(where: String) {
        if (!WindowCtl.open) {
            SignLog.i("BACK $where -> reopen window (state=${WindowCtl.panelState})")
            WindowCtl.reopen("back")
            return
        }
        val vm = SignViewModel.shared(this)
        val screen = vm.state.value.screen
        // the first-run pages (splash, choose your hand) and the goodbye page have no "back"
        if (screen == io.embodit.signhands.domain.Screen.S00 || screen == io.embodit.signhands.domain.Screen.S01 || screen == io.embodit.signhands.domain.Screen.S15) {
            return SignLog.i("BACK $where ignored on $screen")
        }
        SignLog.i("BACK $where -> page back from ${vm.state.value.screen}")
        vm.back()
    }
}
