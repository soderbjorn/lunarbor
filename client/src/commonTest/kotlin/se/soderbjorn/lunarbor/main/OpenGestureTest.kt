/*
 * OpenGestureTest.kt (commonTest)
 * -------------------------------
 * Pins the press rule for links, dots and result rows (LBR-8): plain
 * goes here, Shift / ⌘ (and Ctrl off the Mac) open a new window, the
 * Mac's Ctrl-press defers to its `contextmenu`, other buttons do nothing.
 */

package se.soderbjorn.lunarbor.main

import kotlin.test.Test
import kotlin.test.assertEquals

class OpenGestureTest {
    private fun press(
        button: Int = 0,
        shift: Boolean = false,
        meta: Boolean = false,
        ctrl: Boolean = false,
        isMac: Boolean = true,
    ) = OpenGesture.of(button, shift, meta, ctrl, isMac)

    @Test
    fun plainPrimaryPressGoesHere() {
        assertEquals(OpenGesture.HERE, press())
        assertEquals(OpenGesture.HERE, press(isMac = false))
    }

    @Test
    fun shiftOpensANewWindow() {
        assertEquals(OpenGesture.NEW_WINDOW, press(shift = true))
        assertEquals(OpenGesture.NEW_WINDOW, press(shift = true, isMac = false))
    }

    @Test
    fun commandOpensANewWindow() {
        assertEquals(OpenGesture.NEW_WINDOW, press(meta = true))
        assertEquals(OpenGesture.NEW_WINDOW, press(meta = true, isMac = false))
    }

    @Test
    fun macCtrlPressLeavesItToTheContextMenu() {
        // macOS fires `contextmenu` after a Ctrl-press; acting on the press
        // too would navigate this pane as well as open the new window.
        assertEquals(OpenGesture.CONTEXT_MENU, press(ctrl = true))
        assertEquals(OpenGesture.CONTEXT_MENU, press(ctrl = true, shift = true))
        assertEquals(OpenGesture.CONTEXT_MENU, press(ctrl = true, meta = true))
    }

    @Test
    fun ctrlOffTheMacOpensANewWindow() {
        assertEquals(OpenGesture.NEW_WINDOW, press(ctrl = true, isMac = false))
    }

    @Test
    fun otherButtonsAreLeftAlone() {
        assertEquals(OpenGesture.NONE, press(button = 2))
        assertEquals(OpenGesture.NONE, press(button = 1))
        assertEquals(OpenGesture.NONE, press(button = 2, shift = true))
        assertEquals(OpenGesture.NONE, press(button = 2, ctrl = true))
    }
}
