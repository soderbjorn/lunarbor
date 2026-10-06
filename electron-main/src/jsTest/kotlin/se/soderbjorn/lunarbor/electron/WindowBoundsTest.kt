/* WindowBoundsTest.kt (electron-main, jsTest)
 *
 * Pins the remembered-window rules in WindowBounds.kt: lenient parsing
 * of the stored `windowBounds`, and fitting them to the displays that
 * exist at startup (off-screen positions dropped, oversize shrunk). */
package se.soderbjorn.lunarbor.electron

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowBoundsTest {

    private val laptop = ScreenRect(0, 25, 1440, 875)
    private val external = ScreenRect(1440, 0, 2560, 1415)

    @Test
    fun parsesStoredBounds() {
        assertEquals(
            WindowBounds(ScreenRect(10, 20, 800, 600), true),
            windowBoundsOf(10.0, 20.0, 800.0, 600.0, true),
        )
        assertEquals(false, windowBoundsOf(10.0, 20.0, 800.0, 600.0, null)?.maximized)
    }

    @Test
    fun unusableBoundsReadAsNone() {
        assertNull(windowBoundsOf(null, 20.0, 800.0, 600.0, false))
        assertNull(windowBoundsOf(10.0, 20.0, 50.0, 600.0, false))
        assertNull(windowBoundsOf(Double.NaN, 20.0, 800.0, 600.0, false))
    }

    @Test
    fun nothingSavedUsesDefaults() {
        assertEquals(
            Placement(null, null, DEFAULT_WINDOW_WIDTH, DEFAULT_WINDOW_HEIGHT, false),
            placeWindow(null, listOf(laptop)),
        )
    }

    @Test
    fun keepsBoundsOnAnExistingDisplay() {
        val saved = WindowBounds(ScreenRect(1600, 100, 1200, 900), false)
        assertEquals(Placement(1600, 100, 1200, 900, false), placeWindow(saved, listOf(laptop, external)))
    }

    @Test
    fun dropsPositionWhenTheDisplayIsGone() {
        val saved = WindowBounds(ScreenRect(1600, 100, 1200, 900), true)
        assertEquals(Placement(null, null, 1200, 875, true), placeWindow(saved, listOf(laptop)))
    }

    @Test
    fun dropsPositionWhenOnlyASliverShows() {
        val saved = WindowBounds(ScreenRect(1400, 100, 800, 600), false)
        assertEquals(Placement(null, null, 800, 600, false), placeWindow(saved, listOf(laptop)))
    }

    @Test
    fun shrinksToTheLargestWorkArea() {
        val saved = WindowBounds(ScreenRect(0, 25, 3000, 2000), false)
        assertEquals(Placement(0, 25, 1440, 875, false), placeWindow(saved, listOf(laptop)))
    }
}
