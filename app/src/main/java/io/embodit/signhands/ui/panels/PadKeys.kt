package io.embodit.signhands.ui.panels

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import io.embodit.signhands.platform.BackKeys
import io.embodit.signhands.platform.SignLog

/**
 * v0.1.6 (profile D60, gates button-focus / back-key): controller keys never press a window button. Buttons cannot
 * take focus (so ENTER / A have nothing to click) and the window eats A / X / Y / ENTER / DPAD_CENTER (logged,
 * unassigned); BACK and controller B go to the one back handler ([BackKeys]), never to a focused control.
 */
internal val PAD_KEYS = setOf(Key.ButtonA, Key.ButtonB, Key.ButtonX, Key.ButtonY, Key.Enter, Key.NumPadEnter, Key.DirectionCenter)

internal fun Modifier.eatPadKeys(where: String): Modifier =
    onPreviewKeyEvent { e ->
        if (BackKeys.route(e.nativeKeyEvent, "compose:$where")) {
            true
        } else if (e.key in PAD_KEYS) {
            SignLog.i("KEY eaten $where ${e.key}")
            true
        } else {
            false
        }
    }

internal val NoFocus: Modifier = Modifier.focusProperties { canFocus = false }
