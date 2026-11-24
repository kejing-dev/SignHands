package io.embodit.signhands.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** D60 补 (gate back-key): BACK / controller B reach the one back handler from any layer, once per press. */
class BackKeysTest {
    private val fired = mutableListOf<String>()

    @Before fun setUp() { BackKeys.reset(); fired.clear(); BackKeys.handler = { fired += it } }

    private fun press(code: Int, t: Long, vararg layers: String) {
        for (l in layers) assertTrue(BackKeys.route(code, BackKeys.ACTION_DOWN, t, false, l))
        for (l in layers) assertTrue(BackKeys.route(code, BackKeys.ACTION_UP, t, false, l))
    }

    @Test fun backFiresOnUpOnce() {
        press(BackKeys.KEYCODE_BACK, 100, "key:Stub")
        assertEquals(1, fired.size)
    }

    @Test fun twoLayersSeeingOnePressFireOnce() {
        // e.g. the Compose root and the Window.Callback both see the same event
        press(BackKeys.KEYCODE_BACK, 200, "compose:window", "key:Stub")
        assertEquals(1, fired.size)
    }

    @Test fun threePressesFireThree() {
        press(BackKeys.KEYCODE_BACK, 300, "unhandled:tabbar")
        press(BackKeys.KEYCODE_BACK, 400, "unhandled:tabbar")
        press(BackKeys.KEYCODE_BUTTON_B, 500, "compose:tab")
        assertEquals(3, fired.size)
        assertTrue(fired[2].endsWith("BUTTON_B"))
    }

    @Test fun downAloneAndCanceledDoNotFire() {
        assertTrue(BackKeys.route(BackKeys.KEYCODE_BACK, BackKeys.ACTION_DOWN, 600, false, "x"))
        assertTrue(BackKeys.route(BackKeys.KEYCODE_BACK, BackKeys.ACTION_UP, 600, true, "x"))
        assertEquals(0, fired.size)
    }

    @Test fun otherKeysPassThrough() {
        assertFalse(BackKeys.route(96 /* BUTTON_A */, BackKeys.ACTION_UP, 700, false, "x"))
        assertFalse(BackKeys.route(23 /* DPAD_CENTER */, BackKeys.ACTION_UP, 701, false, "x"))
        assertEquals(0, fired.size)
    }

    /** v0.1.6 (D60): A / X / Y / ENTER / NUMPAD_ENTER / DPAD_CENTER are eaten (no job in the app); BACK / B are not "unassigned". */
    @Test fun padConfirmKeysAreUnassigned() {
        for (k in listOf(96, 99, 100, 66, 160, 23)) assertTrue(BackKeys.isUnassigned(k))
        for (k in listOf(BackKeys.KEYCODE_BACK, BackKeys.KEYCODE_BUTTON_B, 19, 20, 21, 22)) assertFalse(BackKeys.isUnassigned(k))
    }
}
