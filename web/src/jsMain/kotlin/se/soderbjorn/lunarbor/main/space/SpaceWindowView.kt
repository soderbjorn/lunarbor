/*
 * SpaceWindowView.kt (jsMain)
 * ---------------------------
 * What `SpaceMode` needs of one window's page view in 3D mode, whichever
 * page shape it shows: Pages ([PageSpaceView]) or Grove ([GroveView]).
 * `SpaceMode` builds one per shown window, drives them from its render
 * loop, draws the starfield behind each and lays them out.
 *
 * View glue only.
 */

package se.soderbjorn.lunarbor.main.space

import org.w3c.dom.HTMLElement
import se.soderbjorn.lunarbor.main.SpacePage
import se.soderbjorn.lunarbor.main.space.three.Camera3
import se.soderbjorn.lunarbor.main.space.three.Object3

/**
 * One window's view into a page space.
 *
 * ### Callers
 * - [SpaceMode]: builds, [start]s, [place]s and [dispose]s views as
 *   windows are shown and hidden, [tick]s them every frame, toggles free
 *   flight on the focused one ([toggleFlight], ⌥⌘F), and reads [page] for
 *   the dock's window chips.
 * - `Backdrop` draws the starfield into each view's rectangle with its
 *   [camera], after [applyBackdropOffset].
 */
internal interface SpaceWindowView {
    /** The toolkit pane (window) this view shows. */
    val paneId: String

    /** The view's box in the space overlay. */
    val element: HTMLElement

    /** The three.js camera the starfield is drawn with. */
    val camera: Camera3

    /** The view's rectangle inside the overlay, in CSS pixels. */
    val x: Int
    val y: Int
    val w: Int
    val h: Int

    /** The page the window is on, or `null` until its first state has loaded. */
    val page: SpacePage?

    /**
     * `true` when the starfield behind this view should surround it (a
     * camera that turns), `false` for the flat field behind Pages' fixed
     * camera.
     */
    val roundBackdrop: Boolean

    /**
     * Something the starfield's pass draws too, in this view's world
     * coordinates (Pages' far pages in free flight), or `null`.
     */
    val backdropExtra: Object3?
        get() = null

    /** Mounts the window's editor and starts following the pane. */
    fun start()

    /** Gives the editor back to its pane; with [focus], the editor takes the keyboard. */
    fun dispose(focus: Boolean)

    /** Puts the keyboard in the live page's editor, caret where the pane has it. */
    fun focusEditor()

    /** Moves and sizes the view inside the overlay. */
    fun place(nx: Int, ny: Int, nw: Int, nh: Int)

    /**
     * Advances one frame of [dt] seconds.
     *
     * @return `true` while anything is still moving.
     */
    fun tick(dt: Double): Boolean

    /** Outlines the live page while its window is the focused one. */
    fun renderFocus()

    /** Moves (and turns) the shared starfield with this view's camera. */
    fun applyBackdropOffset(backdropWorld: Object3)

    /** Takes off into free flight, or lands back on the window's page ([PageFlight]). */
    fun toggleFlight()
}
