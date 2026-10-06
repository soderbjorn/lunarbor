/*
 * PageFlight.kt (jsMain)
 * ----------------------
 * Free flight in 3D mode's page views (Pages and Grove): ⌥⌘F or ⌃⌘4 (or the
 * strip's Fly button) turns a view's camera into the maps' spaceship
 * ([FreeFlight], Lunamux's ship model and keys), and landing flies the
 * camera back to face the window's page — the page it was zoomed to when
 * it took off, or wherever it has navigated since.
 *
 * What this owns, so both views fly alike:
 *  - the ship and its legend ([MapLegend] with the free-flight panel
 *    only — the page views have no landed legend, their keys are the
 *    editor's);
 *  - the keyboard while flying: a capture-phase listener on `window` takes
 *    every key before the editor sees it (held flight keys steer; F / C
 *    land, K hides the legend, Enter opens the page ahead), single-modifier
 *    chords too, so nothing edits the document; Escape and two-modifier
 *    chords (navigation, 3D mode's own) pass, so Esc leaves 3D and ⌥⌘F
 *    lands. The views make a click on the live page land, never edit;
 *  - which page is ahead ([aim], [target]); the view highlights that
 *    page's border (`is-aimed`), no ring.
 *
 * The view keeps its camera: it reads [pose] every frame while [isOn],
 * and does the landing flight itself ([onLand]).
 *
 * View glue only — no vault logic.
 */

package se.soderbjorn.lunarbor.main.space

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.lunarbor.main.SpacePose
import se.soderbjorn.lunarbor.main.SpaceVec
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One page view's free flight.
 *
 * ### Callers
 * - `PageSpaceView` and `GroveView` build one each, call [takeOff] /
 *   [stop] (on ⌥⌘F through `SpaceMode.toggleFlight`), [step] and [pose]
 *   every frame while [isOn], [aim] to find the page ahead, and
 *   [dispose] when the view goes.
 *
 * @param host The view's element: the legend is placed in it.
 * @param onLand F / C: the view stops the ship ([stop]) and flies back to
 *   its window's page.
 * @param onEngage Enter: the view opens [target] (or lands when there is none).
 * @param requestFrame Asks the render loop for frames.
 */
internal class PageFlight(
    private val host: HTMLElement,
    private val onLand: () -> Unit,
    private val onEngage: () -> Unit,
    private val requestFrame: () -> Unit,
) {
    private val legend = MapLegend(mapSections = null, flightSections = MapLegend.PAGE_FLIGHT_SECTIONS)
    private val ship = FreeFlight(SCALE, legend)

    /** The page ahead while flying ([aim]), by page key. */
    var target: String? = null
        private set

    /** `true` while flying. */
    val isOn: Boolean get() = ship.isOn

    private val keyDown: (Event) -> Unit = { e -> onKeyDown(e as KeyboardEvent) }
    private val keyUp: (Event) -> Unit = { e ->
        if (ship.isOn && ship.release((e as KeyboardEvent).code)) e.preventDefault()
    }
    private val windowBlur: (Event) -> Unit = { _ -> ship.releaseAll() }

    init {
        host.appendChild(legend.element)
    }

    /**
     * Takes off from the camera's pose: [eye], looking along [forward],
     * [up] its roof. The editor lets go of the keyboard.
     */
    fun takeOff(eye: SpaceVec, forward: SpaceVec, up: SpaceVec) {
        if (ship.isOn) return
        ship.start(eye, eye + forward, up)
        (document.activeElement as? HTMLElement)?.blur()
        window.addEventListener("keydown", keyDown, true)
        window.addEventListener("keyup", keyUp, true)
        window.addEventListener("blur", windowBlur)
        requestFrame()
    }

    /** Stops the ship where it is (the view then flies the camera wherever it wants). */
    fun stop() {
        if (!ship.isOn) return
        ship.stop()
        target = null
        window.removeEventListener("keydown", keyDown, true)
        window.removeEventListener("keyup", keyUp, true)
        window.removeEventListener("blur", windowBlur)
    }

    /** Stops flying and removes the legend. */
    fun dispose() {
        stop()
        legend.element.remove()
    }

    /**
     * Advances the ship [dt] seconds.
     *
     * @return `true` while it moves (a key held, or drifting).
     */
    fun step(dt: Double): Boolean {
        if (!ship.isOn) return false
        ship.step(dt * 60)
        return ship.isMoving
    }

    /** The ship as a camera pose (looking along its nose). */
    fun pose(): SpacePose {
        val normal = -ship.forward
        val up = ship.up
        return SpacePose(ship.pos, up.cross(normal), up, normal)
    }

    /**
     * Finds the page ahead among [pages] (key → slot centre) — of those in
     * front of the camera [cam], the one whose centre on screen is nearest
     * the view's centre, within a third of the view — into [target].
     *
     * @param focal The camera's focal length in CSS pixels (its distance
     *   for 1:1, `PageSpaceGeometry.cameraDistance`).
     * @param width The view's width; [height] its height.
     * @param pageWidth A page's width (a big page near the centre counts as there).
     * @return The new [target].
     */
    fun aim(pages: Map<String, SpaceVec>, cam: SpacePose, focal: Double, width: Int, height: Int, pageWidth: Double): String? {
        if (!ship.isOn) return null
        val fwd = cam.forward
        val cx = width / 2.0
        val cy = height / 2.0
        val reach = min(width, height) / 3.0
        var best: String? = null
        var bestD = Double.MAX_VALUE
        for ((key, at) in pages) {
            val v = at - cam.position
            val depth = v.dot(fwd)
            if (depth < MIN_DEPTH) continue
            val sx = cx + v.dot(cam.right) / depth * focal
            val sy = cy - v.dot(cam.up) / depth * focal
            val size = pageWidth * focal / depth
            val d = sqrt((sx - cx) * (sx - cx) + (sy - cy) * (sy - cy)) - size / 2
            if (d < reach && d < bestD) {
                best = key
                bestD = d
            }
        }
        target = best
        return best
    }

    /**
     * Keys while flying: held flight keys steer; F / C land, K hides the
     * legend, Enter opens the page ahead. Nothing edits the document
     * meanwhile: every other key is swallowed — chords with one modifier
     * too (paste, undo, the palette's commands) — except Escape (leaves
     * 3D) and chords of two modifiers, which are navigation and 3D mode's
     * own (⌥⌘F lands, ⌃⌘3, ⌥⌘← / →, ⌃⌘↑).
     */
    private fun onKeyDown(e: KeyboardEvent) {
        if (!ship.isOn) return
        val mods = listOf(e.metaKey, e.ctrlKey, e.altKey).count { it }
        if (e.key == "Escape" || mods >= 2) return
        e.preventDefault()
        e.stopPropagation()
        when {
            e.code == "KeyF" || e.code == "KeyC" -> {
                legend.flash("fly-land")
                onLand()
            }
            e.code == "KeyK" -> legend.toggle()
            e.key == "Enter" -> {
                legend.flash("engage")
                onEngage()
            }
            ship.press(e.code) -> requestFrame()
        }
    }

    private companion object {
        /** Thrust per Lunamux unit: pages are CSS pixels, a far bigger world than the maps'. */
        const val SCALE = 2.5

        /** Pages nearer than this in front of the camera are not "ahead". */
        const val MIN_DEPTH = 50.0
    }
}
