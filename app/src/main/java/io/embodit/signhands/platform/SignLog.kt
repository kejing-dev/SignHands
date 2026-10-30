package io.embodit.signhands.platform

import android.util.Log

/** One log tag for the whole app: `adb logcat -s SignHands`. */
object SignLog {
    private const val TAG = "SignHands"

    fun i(msg: String) {
        Log.i(TAG, msg)
    }

    fun w(msg: String) {
        Log.w(TAG, msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
    }
}
