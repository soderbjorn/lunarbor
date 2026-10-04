/*
 * MapLegend.kt (jsMain)
 * ---------------------
 * The keyboard legend in the bottom left of 3D mode's maps (Crown, Cone,
 * Galaxy), modelled on Lunamux's: a "MAP" panel with the map's mouse and
 * keys, swapped for a "FREE FLIGHT" panel while flying ([FreeFlight]).
 * K shows or hides it (remembered for the session, in both panels); a key
 * in use flashes its row. Pages has no legend — its keys are the editor's.
 *
 * Owned by `MapView`; view glue only.
 */

package se.soderbjorn.lunarbor.main.space

import kotlinx.browser.document
import org.w3c.dom.HTMLElement

/**
 * The map's key legend.
 *
 * ### Callers
 * - `MapView` appends [element], calls [show] when free flight starts or
 *   stops, [toggle] on K and [flash] for one-shot keys.
 * - [FreeFlight] flashes the rows of held flight keys.
 */
internal class MapLegend {
    /** The panel host; absolutely placed in the bottom left of the map. */
    val element: HTMLElement = div("lunarbor-map-legend")

    private val mapPanel = buildPanel("MAP", MAP_SECTIONS)
    private val flightPanel = buildPanel("FREE FLIGHT", FLIGHT_SECTIONS)
    private var flying = false

    init {
        element.appendChild(mapPanel)
        element.appendChild(flightPanel)
        render()
    }

    /** Shows the free-flight panel ([flying]) or the map's. */
    fun show(flying: Boolean) {
        this.flying = flying
        render()
    }

    /** Hides or shows the legend (K), in both panels. */
    fun toggle() {
        hidden = !hidden
        render()
    }

    /** Flashes the row [id] of the panel on screen (a key just used). */
    fun flash(id: String) {
        val panel = if (flying) flightPanel else mapPanel
        val row = panel.querySelector("[data-key-row=\"$id\"]") as? HTMLElement ?: return
        row.classList.remove("is-flash")
        // Restart the fade: a reflow between removing and adding the class.
        row.offsetWidth
        row.classList.add("is-flash")
    }

    private fun render() {
        element.style.display = if (hidden) "none" else ""
        mapPanel.style.display = if (flying) "none" else ""
        flightPanel.style.display = if (flying) "" else "none"
    }

    private fun buildPanel(title: String, sections: List<Pair<String, List<Triple<String, String, String>>>>): HTMLElement {
        val el = div("lunarbor-legend-panel")
        el.appendChild(div("lunarbor-legend-title").also { it.textContent = title })
        for ((caption, rows) in sections) {
            val sec = div("lunarbor-legend-section")
            sec.appendChild(div("lunarbor-legend-caption").also { it.textContent = caption })
            for ((id, keys, text) in rows) {
                val row = div("lunarbor-legend-row")
                row.setAttribute("data-key-row", id)
                val kk = span("lunarbor-legend-keys", "")
                for (k in keys.split(' ')) {
                    // A word in the keys column ("click", "drag") is a gesture, not a key cap.
                    if (k.length > 2 && k.all { it.isLowerCase() }) kk.appendChild(span("lunarbor-legend-gesture", k))
                    else kk.appendChild(document.createElement("kbd").also { it.textContent = k })
                }
                row.appendChild(kk)
                row.appendChild(span("lunarbor-legend-text", text))
                sec.appendChild(row)
            }
            el.appendChild(sec)
        }
        return el
    }

    companion object {
        /** Hidden with K; shared by every map for the rest of the session. */
        private var hidden = false

        /** The map's legend: sections of (row id, keys separated by spaces, text). */
        private val MAP_SECTIONS: List<Pair<String, List<Triple<String, String, String>>>> = listOf(
            "MOUSE" to listOf(
                Triple("click", "click", "Select a node and fly to it"),
                Triple("edit", "click", "…the selected one again: edit it"),
                Triple("orbit", "drag", "Orbit"),
                Triple("pan", "⇧ drag", "Pan (or right-drag)"),
                Triple("zoom", "scroll", "Zoom (or pinch)"),
            ),
            "KEYS" to listOf(
                Triple("walk", "← →", "Previous / next sibling"),
                Triple("walk-v", "↑ ↓", "Parent / first child"),
                Triple("edit-key", "P", "Edit the selection"),
                Triple("open", "⏎", "Open it in the window"),
                Triple("fold", "␣", "Fold / unfold"),
                Triple("spread", "− +", "Less / more space between"),
                Triple("fly", "F", "Free flight"),
                Triple("shape", "L", "Next view"),
                Triple("pages", "E", "Back to Pages"),
                Triple("home", "C", "Whole map"),
            ),
            "SYSTEM" to listOf(
                Triple("help", "?", "Help"),
                Triple("legend", "K", "Hide shortcuts"),
                Triple("close", "⎋", "Leave 3D"),
            ),
        )

        /** Free flight's legend (Lunamux's keys). */
        private val FLIGHT_SECTIONS: List<Pair<String, List<Triple<String, String, String>>>> = listOf(
            "FLY" to listOf(
                Triple("fly-throttle", "W S", "Throttle forward / reverse"),
                Triple("fly-strafe", "A D", "Strafe left / right"),
                Triple("fly-down", "⇧", "Descend"),
                Triple("fly-pitch", "↑ ↓", "Pitch"),
                Triple("fly-yaw", "← →", "Yaw"),
                Triple("fly-roll", "Q E", "Roll"),
            ),
            "CAMERA" to listOf(
                Triple("cam-home", "C", "Fly camera home"),
                Triple("fly-land", "F", "Land (back to the map)"),
            ),
            "NODE" to listOf(
                Triple("engage", "⏎", "Edit the node ahead"),
            ),
            "SYSTEM" to listOf(
                Triple("legend", "K", "Hide shortcuts"),
                Triple("close", "⎋", "Leave 3D"),
            ),
        )

        /** The legend's styles; installed with `MapView`'s. */
        const val CSS = """
.lunarbor-map-legend { position: absolute; left: 16px; bottom: 54px; z-index: 3; pointer-events: none; }
.lunarbor-legend-panel {
    display: flex; flex-direction: column; gap: 6px; padding: 8px 10px; border-radius: 10px;
    border: 1px solid var(--t-border, rgba(255,255,255,.14));
    background: color-mix(in srgb, var(--t-bg, #0b0f16) 95%, transparent);
    box-shadow: 0 6px 24px rgba(0,0,0,.45);
    font: 10.5px ui-monospace, Menlo, monospace; color: var(--t-text-dim, #a9bad4);
}
.lunarbor-legend-title { font-weight: 700; font-size: 9.5px; letter-spacing: 1.5px; }
.lunarbor-legend-section { display: flex; flex-direction: column; gap: 1px; }
.lunarbor-legend-section + .lunarbor-legend-section { border-top: 1px solid var(--t-border, #232c3c); padding-top: 5px; }
.lunarbor-legend-caption { font-size: 8.5px; letter-spacing: 1px; opacity: .7; margin-bottom: 2px; }
.lunarbor-legend-row { display: flex; align-items: center; gap: 8px; padding: 1px 4px; border-radius: 4px; }
.lunarbor-legend-row.is-flash { animation: lunarbor-legend-flash 610ms ease-out; }
@keyframes lunarbor-legend-flash {
    0%, 26% { background: color-mix(in srgb, var(--t-accent, #2e4a75) 45%, transparent); }
    100% { background: transparent; }
}
@media (prefers-reduced-motion: reduce) { .lunarbor-legend-row.is-flash { animation: none; } }
.lunarbor-legend-keys { display: inline-flex; align-items: center; gap: 3px; min-width: 64px; }
.lunarbor-legend-keys kbd {
    font: inherit; font-weight: 600; padding: 1px 4px; border-radius: 4px;
    border: 1px solid var(--t-border, #38445c); background: var(--t-surface, #171e2b); color: var(--t-text, #d3ddec);
}
.lunarbor-legend-gesture { font-style: italic; color: var(--t-text, #d3ddec); }
"""
    }
}
