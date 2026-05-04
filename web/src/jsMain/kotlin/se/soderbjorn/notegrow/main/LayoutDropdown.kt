/*
 * LayoutDropdown.kt (jsMain)
 * --------------------------
 * Notegrow-local replacement for the toolkit's mouse-only layout-preset
 * dropdown (`buildLayoutPresetButton` in toolkit-web's `TopBarActions.kt`).
 *
 * Why a local copy: the toolkit's `openLayoutPresetGrid` is private, so we
 * cannot extend it; and it doesn't react to the keyboard at all, so a
 * command-palette command "Layout" cannot drive it. This class re-uses the
 * toolkit's public bits — the `LayoutPreset` enum (with `label` +
 * `computeBoxes(n)`) and the toolkit's `.dt-layout-preset-*` CSS classes —
 * so the resulting popover looks identical to the stock one. We just add
 * arrow-key navigation, Enter/Space invoke, and Escape close.
 */

package se.soderbjorn.notegrow.main

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import se.soderbjorn.darkness.web.layout.LayoutBox
import se.soderbjorn.darkness.web.layout.LayoutPreset

/**
 * One layout dropdown for the topbar. The dropdown owns its own trigger
 * button so callers don't have to wire up open/close — they just append
 * [triggerButton] to the topbar.
 *
 * @param paneCount Re-evaluated each time the popover opens so the
 *   miniature tiles render with the current pane count of the active tab.
 * @param onSelect Fired with the picked preset; the host applies it.
 */
internal class LayoutDropdown(
    private val paneCount: () -> Int,
    private val onSelect: (LayoutPreset) -> Unit,
) {

    /** The toolbar trigger element. Kept stable so callers can re-anchor
     *  popovers to the same element across opens (the command palette uses
     *  this when the user picks the "Layout" command). */
    val triggerButton: HTMLElement by lazy { buildTrigger() }

    private var gridEl: HTMLElement? = null
    private var tiles: List<HTMLElement> = emptyList()
    private var focusedIndex: Int = 0

    private var documentClickHandler: ((Event) -> Unit)? = null
    private var documentKeyHandler: ((Event) -> Unit)? = null

    /** `true` while the popover is mounted. Used by the trigger's click
     *  handler to toggle. */
    fun isOpen(): Boolean = gridEl != null

    /** Open the popover anchored under [anchor], with the first tile
     *  focused so arrow keys are immediately useful. Idempotent — calling
     *  while already open re-anchors and re-focuses. */
    fun openAnchoredTo(anchor: HTMLElement) {
        if (gridEl != null) {
            close()
        }
        val n = paneCount()
        val grid = document.createElement("div") as HTMLElement
        grid.className = "dt-layout-preset-grid"
        // Outline the outline-focus ring on the focused tile so keyboard
        // users can see where they are without us having to manage focus
        // outlines manually.
        grid.tabIndex = -1

        val tileList = mutableListOf<HTMLElement>()
        if (n <= 0) {
            val empty = document.createElement("div") as HTMLElement
            empty.className = "dt-layout-preset-empty"
            empty.textContent = "No panes in this tab"
            grid.appendChild(empty)
        } else {
            for (preset in LayoutPreset.values()) {
                val tile = document.createElement("button") as HTMLElement
                tile.setAttribute("type", "button")
                tile.className = "dt-layout-preset-tile"
                tile.title = preset.label
                tile.setAttribute("aria-label", preset.label)
                tile.innerHTML = renderPresetMiniatureSvg(preset.computeBoxes(n))
                tile.addEventListener("click", { e: Event ->
                    e.stopPropagation()
                    selectAndClose(preset)
                })
                tile.addEventListener("mousemove", { _: Event ->
                    val idx = tileList.indexOf(tile)
                    if (idx >= 0 && idx != focusedIndex) {
                        focusedIndex = idx
                        repaintFocusRing()
                    }
                })
                grid.appendChild(tile)
                tileList += tile
            }
        }

        document.body?.appendChild(grid)
        gridEl = grid
        tiles = tileList

        positionGrid(grid, anchor)
        focusedIndex = 0
        repaintFocusRing()

        attachDocumentDismiss(anchor)
    }

    /** Close the popover. Idempotent. */
    fun close() {
        gridEl?.let { it.parentNode?.removeChild(it) }
        gridEl = null
        tiles = emptyList()
        focusedIndex = 0
        detachDocumentDismiss()
    }

    private fun buildTrigger(): HTMLElement {
        val btn = document.createElement("button") as HTMLElement
        btn.setAttribute("type", "button")
        btn.title = "Layout"
        btn.className = "dt-topbar-icon-button"
        btn.innerHTML = ICON_LAYOUT
        btn.addEventListener("click", { _: Event ->
            if (isOpen()) close() else openAnchoredTo(btn)
        })
        return btn
    }

    private fun positionGrid(grid: HTMLElement, anchor: HTMLElement) {
        val rect = anchor.getBoundingClientRect()
        val gridRect = grid.getBoundingClientRect()
        val left = (rect.right - gridRect.width).coerceAtLeast(4.0)
        grid.style.left = "${left}px"
        grid.style.top = "${rect.bottom + 4}px"
    }

    private fun repaintFocusRing() {
        for ((i, tile) in tiles.withIndex()) {
            val active = i == focusedIndex
            val base = "dt-layout-preset-tile"
            tile.className = if (active) "$base is-focused" else base
            if (active) {
                tile.focus()
                tile.scrollIntoView(js("({block:'nearest'})"))
            }
        }
    }

    private fun selectAndClose(preset: LayoutPreset) {
        close()
        onSelect(preset)
    }

    private fun attachDocumentDismiss(anchor: HTMLElement) {
        val clickHandler: (Event) -> Unit = handler@{ e ->
            val target = e.target as? HTMLElement ?: return@handler
            val grid = gridEl ?: return@handler
            if (grid.contains(target)) return@handler
            if (anchor.contains(target)) return@handler
            close()
        }
        val keyHandler: (Event) -> Unit = handler@{ e ->
            val ke = e as? KeyboardEvent ?: return@handler
            when (ke.key) {
                "Escape" -> {
                    ke.preventDefault()
                    close()
                }
                "ArrowRight" -> {
                    if (tiles.isNotEmpty()) {
                        ke.preventDefault()
                        focusedIndex = (focusedIndex + 1).coerceAtMost(tiles.lastIndex)
                        repaintFocusRing()
                    }
                }
                "ArrowLeft" -> {
                    if (tiles.isNotEmpty()) {
                        ke.preventDefault()
                        focusedIndex = (focusedIndex - 1).coerceAtLeast(0)
                        repaintFocusRing()
                    }
                }
                "ArrowDown" -> {
                    if (tiles.isNotEmpty()) {
                        ke.preventDefault()
                        focusedIndex = (focusedIndex + GRID_COLUMNS).coerceAtMost(tiles.lastIndex)
                        repaintFocusRing()
                    }
                }
                "ArrowUp" -> {
                    if (tiles.isNotEmpty()) {
                        ke.preventDefault()
                        focusedIndex = (focusedIndex - GRID_COLUMNS).coerceAtLeast(0)
                        repaintFocusRing()
                    }
                }
                "Enter", " " -> {
                    val tile = tiles.getOrNull(focusedIndex) ?: return@handler
                    ke.preventDefault()
                    val presetIndex = tiles.indexOf(tile)
                    val preset = LayoutPreset.values().getOrNull(presetIndex) ?: return@handler
                    selectAndClose(preset)
                }
            }
        }
        documentClickHandler = clickHandler
        documentKeyHandler = keyHandler
        // Use bubble phase for clicks so the tile's own click can fire
        // first without us closing prematurely. Use capture for keydown so
        // the editor's keydown handler doesn't swallow our arrows.
        document.addEventListener("click", clickHandler)
        document.addEventListener("keydown", keyHandler, /* capture = */ true)
    }

    private fun detachDocumentDismiss() {
        documentClickHandler?.let { document.removeEventListener("click", it) }
        documentKeyHandler?.let {
            document.removeEventListener("keydown", it, /* capture = */ true)
        }
        documentClickHandler = null
        documentKeyHandler = null
    }

    companion object {
        /** Matches the toolkit's `.dt-layout-preset-grid`'s 3-column grid. */
        private const val GRID_COLUMNS = 3

        /** Same 2x2 layout glyph the toolkit ships, copied verbatim so the
         *  topbar trigger looks identical to the stock one. */
        private const val ICON_LAYOUT: String =
            "<svg viewBox=\"0 0 16 16\" width=\"16\" height=\"16\" fill=\"none\" " +
                "stroke=\"currentColor\" stroke-width=\"1.4\" stroke-linejoin=\"round\">" +
                "<rect x=\"2.5\" y=\"2.5\" width=\"4.5\" height=\"4.5\" rx=\"1\"/>" +
                "<rect x=\"9\" y=\"2.5\" width=\"4.5\" height=\"4.5\" rx=\"1\"/>" +
                "<rect x=\"2.5\" y=\"9\" width=\"4.5\" height=\"4.5\" rx=\"1\"/>" +
                "<rect x=\"9\" y=\"9\" width=\"4.5\" height=\"4.5\" rx=\"1\"/></svg>"
    }
}

/**
 * Renders a tile-sized SVG miniature for [boxes]. Mirrors the toolkit's
 * private `renderPresetMiniatureSvg` (same 36×24 viewBox, same class names)
 * so the toolkit's bundled CSS — `.dt-layout-preview`,
 * `.dt-layout-preview-primary`, `.dt-layout-preview-other` — paints us
 * identically to the stock dropdown.
 */
private fun renderPresetMiniatureSvg(boxes: List<LayoutBox>): String {
    val w = 36
    val h = 24
    val pad = 1.0
    val sb = StringBuilder()
    sb.append(
        "<svg viewBox=\"0 0 $w $h\" width=\"$w\" height=\"$h\" " +
            "class=\"dt-layout-preview\" aria-hidden=\"true\">",
    )
    for ((i, b) in boxes.withIndex()) {
        val bx = b.x * w + pad
        val by = b.y * h + pad
        val bw = (b.width * w - pad * 2).coerceAtLeast(2.0)
        val bh = (b.height * h - pad * 2).coerceAtLeast(2.0)
        val cls = if (i == 0) "dt-layout-preview-primary" else "dt-layout-preview-other"
        sb.append(
            "<rect x=\"$bx\" y=\"$by\" width=\"$bw\" height=\"$bh\" rx=\"1.2\" class=\"$cls\"/>",
        )
    }
    sb.append("</svg>")
    return sb.toString()
}
