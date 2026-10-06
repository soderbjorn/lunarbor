/* WindowBounds.kt (electron-main)
 *
 * The main window's size, position and maximized state, remembered
 * across restarts as `windowBounds` in the app's settings file
 * (`lunarbor.json`, beside `vaultPath`). Like `vaultPath` the key is
 * owned by the main process: renderer ui-settings writes keep whatever
 * is on disk (see `withMainOwnedKeys` in ElectronMain.kt).
 *
 * Pure rules only — no Electron or Node calls, so they are unit-tested
 * in `WindowBoundsTest`. ElectronMain.kt reads / writes the JSON and
 * hands in the displays' work areas. */
package se.soderbjorn.lunarbor.electron

/** Key in the app's settings file (`lunarbor.json`) holding [WindowBounds]. */
const val SETTINGS_KEY_WINDOW_BOUNDS: String = "windowBounds"

/** Default window width, used when nothing usable is remembered. */
const val DEFAULT_WINDOW_WIDTH: Int = 1024

/** Default window height, used when nothing usable is remembered. */
const val DEFAULT_WINDOW_HEIGHT: Int = 720

/** Smallest remembered width / height that is still restored. */
const val MIN_WINDOW_SIZE: Int = 200

/** How much of the window's title strip must lie on a display for its position to be kept. */
private const val MIN_VISIBLE_PX: Int = 80

/**
 * An axis-aligned rectangle in screen coordinates (DIPs), as Electron's
 * `getBounds()` / `Display.workArea` report it.
 *
 * @property x Left edge.
 * @property y Top edge.
 * @property width Width, positive.
 * @property height Height, positive.
 */
data class ScreenRect(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * The remembered window placement.
 *
 * @property rect The window's normal (un-maximized) bounds.
 * @property maximized Whether the window was maximized when last saved.
 */
data class WindowBounds(val rect: ScreenRect, val maximized: Boolean)

/**
 * Parses the stored `windowBounds` value (`{ x, y, width, height,
 * maximized }`), leniently: anything missing, non-numeric or smaller
 * than [MIN_WINDOW_SIZE] reads as `null` (the defaults apply).
 *
 * Called by `readPersistedWindowBounds` in ElectronMain.kt.
 *
 * @param x The stored `x`, or `null`.
 * @param y The stored `y`, or `null`.
 * @param width The stored `width`, or `null`.
 * @param height The stored `height`, or `null`.
 * @param maximized The stored `maximized` flag, or `null` (= false).
 * @return The bounds, or `null` when they are unusable.
 */
fun windowBoundsOf(x: Double?, y: Double?, width: Double?, height: Double?, maximized: Boolean?): WindowBounds? {
    if (x == null || y == null || width == null || height == null) return null
    if (!x.isFinite() || !y.isFinite() || !width.isFinite() || !height.isFinite()) return null
    if (width < MIN_WINDOW_SIZE || height < MIN_WINDOW_SIZE) return null
    return WindowBounds(ScreenRect(x.toInt(), y.toInt(), width.toInt(), height.toInt()), maximized == true)
}

/**
 * Where to open the window: the remembered [saved] bounds, fitted to
 * the displays that exist now.
 *
 * - The size is kept, shrunk to the largest work area if it no longer
 *   fits there.
 * - The position is kept when a strip of the window's top edge (where
 *   the title bar is) of at least [MIN_VISIBLE_PX] lies on some
 *   display's work area; otherwise (a display was unplugged) it is
 *   dropped and the window opens centred — `x` / `y` `null`.
 *
 * Called by `createWindow` in ElectronMain.kt.
 *
 * @param saved The remembered bounds, or `null` for none.
 * @param workAreas Every display's work area; empty means unknown (the position is dropped).
 * @return The rectangle to open with; `x` / `y` are `null` to let the OS centre it.
 */
fun placeWindow(saved: WindowBounds?, workAreas: List<ScreenRect>): Placement {
    if (saved == null) return Placement(null, null, DEFAULT_WINDOW_WIDTH, DEFAULT_WINDOW_HEIGHT, false)
    val r = saved.rect
    val maxW = workAreas.maxOfOrNull { it.width } ?: r.width
    val maxH = workAreas.maxOfOrNull { it.height } ?: r.height
    val w = r.width.coerceAtMost(maxW).coerceAtLeast(MIN_WINDOW_SIZE)
    val h = r.height.coerceAtMost(maxH).coerceAtLeast(MIN_WINDOW_SIZE)
    val titleStrip = ScreenRect(r.x, r.y, w, MIN_VISIBLE_PX.coerceAtMost(h))
    val onScreen = workAreas.any { overlap(it, titleStrip, MIN_VISIBLE_PX) }
    return if (onScreen) Placement(r.x, r.y, w, h, saved.maximized)
    else Placement(null, null, w, h, saved.maximized)
}

/**
 * The window options [placeWindow] chose.
 *
 * @property x Left edge, or `null` to let the OS centre the window.
 * @property y Top edge, or `null` with [x].
 * @property width Width to open with.
 * @property height Height to open with.
 * @property maximized Whether to maximize the window once created.
 */
data class Placement(val x: Int?, val y: Int?, val width: Int, val height: Int, val maximized: Boolean)

/**
 * Whether [a] and [b] overlap by at least [minPx] horizontally and at
 * least a pixel vertically.
 */
private fun overlap(a: ScreenRect, b: ScreenRect, minPx: Int): Boolean {
    val dx = minOf(a.x + a.width, b.x + b.width) - maxOf(a.x, b.x)
    val dy = minOf(a.y + a.height, b.y + b.height) - maxOf(a.y, b.y)
    return dx >= minPx.coerceAtMost(b.width) && dy > 0
}
