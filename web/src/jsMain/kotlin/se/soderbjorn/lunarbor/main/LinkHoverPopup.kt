/*
 * LinkHoverPopup.kt (jsMain)
 * --------------------------
 * The small "Edit link" button that pops up under a link the pointer
 * rests on for a moment. A click on a link follows it, so without this
 * there is no way to change a link: the button opens the Edit link dialog
 * ([openLinkEditDialog]) — text, URL, Remove link. Its tooltip names
 * where the link goes.
 *
 * View glue only: the dialog does the edits, addressed by the link's
 * document row and a model column inside it. Styled by the
 * `.lunarbor-link-popup*` rules injected by `AppShell`.
 */

package se.soderbjorn.lunarbor.main

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.events.Event
import org.w3c.dom.events.MouseEvent
import se.soderbjorn.lunarbor.data.LunarborLink

/**
 * Hover popup for the links of one editor. One instance per [MainScreen].
 *
 * @param viewModel The pane's view model; receives the edits.
 * @param scope Scope for the Edit link dialog's vault search.
 * @param focusEditor Puts focus back in the editor once the dialog closes.
 */
internal class LinkHoverPopup(
    private val viewModel: MainViewModel,
    private val scope: CoroutineScope,
    private val focusEditor: () -> Unit,
) {
    private var popupEl: HTMLElement? = null
    private var showTimer: Int? = null
    private var hideTimer: Int? = null
    /** The link the pointer rests on ([linkKeyOf]), or null. */
    private var hoveredKey: String? = null
    /** The link the open popup belongs to. */
    private var openKey: String? = null
    private var linkRect: Rect? = null
    private var removeDocListeners: (() -> Unit)? = null
    private var editorEl: HTMLElement? = null
    private var pointerX: Double = 0.0
    private var pointerY: Double = 0.0

    /**
     * Wires the popup to [editor]: resting on a `data-href` span for
     * [SHOW_DELAY_MS] opens it; leaving the link and the popup, typing,
     * scrolling or pressing anywhere else closes it. Called once by
     * [MainScreen] when it builds the editor.
     *
     * A link is identified by its row and source column ([linkKeyOf]), never
     * by its span: the editor repaints often and replaces the spans, and a
     * fresh span under the pointer must not read as another link. While the
     * popup is open, leaving is decided by geometry ([trackPointer]), so the
     * pointer may cross other rows, links included, on its way to the button.
     */
    fun attach(editor: HTMLElement) {
        editorEl = editor
        editor.addEventListener("mousemove", { e ->
            pointerX = (e as MouseEvent).clientX.toDouble()
            pointerY = e.clientY.toDouble()
        })
        editor.addEventListener("mouseover", { e ->
            pointerX = (e as MouseEvent).clientX.toDouble()
            pointerY = e.clientY.toDouble()
            val link = linkSpanOf(e.target as? Node)
            val key = link?.let(::linkKeyOf)
            if (popupEl != null) {
                // The popup's own corridor decides when it closes; another
                // link rested on long enough takes it over.
                if (key == null || key == openKey) {
                    cancelShow()
                    hoveredKey = openKey
                } else if (key != hoveredKey) {
                    hoveredKey = key
                    cancelShow()
                    if (e.buttons.toInt() == 0) showTimer = window.setTimeout({ show(key) }, SHOW_DELAY_MS)
                }
                return@addEventListener
            }
            if (key == null) {
                cancelShow()
                hoveredKey = null
                return@addEventListener
            }
            if (key == hoveredKey) return@addEventListener
            hoveredKey = key
            cancelShow()
            // Not while a button is down: that's a click or a drag.
            if (e.buttons.toInt() != 0) return@addEventListener
            showTimer = window.setTimeout({ show(key) }, SHOW_DELAY_MS)
        })
        editor.addEventListener("mouseleave", { _ ->
            if (popupEl == null) {
                cancelShow()
                hoveredKey = null
            }
        })
        editor.addEventListener("mousedown", { _ -> cancelShow(); close() })
        editor.addEventListener("keydown", { _ -> cancelShow(); close() })
    }

    /** The link's identity across repaints: `row:sourceColumn`, or null. */
    private fun linkKeyOf(link: HTMLElement): String? {
        val row = (link.closest("[data-row]") as? HTMLElement)?.getAttribute("data-row") ?: return null
        val src = link.getAttribute("data-src-start") ?: return null
        return "$row:$src"
    }

    private fun linkSpanOf(node: Node?): HTMLElement? {
        var n = node
        while (n != null) {
            if (n is Element && n.hasAttribute("data-href")) return n as? HTMLElement
            if (n is Element && n.classList.contains("lunarbor-editor")) return null
            n = n.parentNode
        }
        return null
    }

    /**
     * Opens the popup for the link [key] once the hover delay ran out, if
     * the pointer still rests on that link. The span seen on `mouseover`
     * may have been repainted away by now, so the link under the last
     * pointer position is looked up again and must have the same key.
     */
    private fun show(key: String) {
        showTimer = null
        val live = linkSpanOf(document.elementFromPoint(pointerX, pointerY)) ?: return
        if (linkKeyOf(live) != key) return
        if (editorEl?.contains(live) != true) return
        hoveredKey = key
        showFor(live, key)
    }

    private fun showFor(link: HTMLElement, key: String) {
        val href = link.getAttribute("data-href") ?: return
        val rowDiv = link.closest("[data-row]") as? HTMLElement ?: return
        val row = rowDiv.getAttribute("data-row")?.toIntOrNull() ?: return
        val prefixLen = rowDiv.getAttribute("data-prefix-len")?.toIntOrNull() ?: 0
        val srcStart = link.getAttribute("data-src-start")?.toIntOrNull() ?: return
        val col = prefixLen + srcStart

        close()
        val popup = document.createElement("div") as HTMLElement
        popup.className = "lunarbor-link-popup"
        val rect = link.getBoundingClientRect()
        popup.style.top = "${rect.bottom + 4}px"
        popup.style.left = "${rect.left}px"

        // A target the privacy mode hides is not named: it reads as missing.
        val edit = button("Edit link") {
            openLinkEditDialog(viewModel, scope, row, col, href, focusEditor)
        }
        edit.title = LunarborLink.parse(href)?.let { path ->
            if (viewModel.isPathHidden(path)) "Not found" else LunarborLink.displayPath(path)
        } ?: href
        popup.appendChild(edit)

        // Keep presses in the popup from moving the editor's selection.
        popup.addEventListener("mousedown", { e -> e.preventDefault() })
        document.body?.appendChild(popup)
        popupEl = popup
        openKey = key
        linkRect = Rect(rect.left, rect.top, rect.right, rect.bottom)

        // Keep it inside the window.
        val box = popup.getBoundingClientRect()
        if (box.right > window.innerWidth - 8) {
            popup.style.left = "${maxOf(8.0, window.innerWidth - 8 - box.width)}px"
        }
        if (box.bottom > window.innerHeight - 8) {
            popup.style.top = "${maxOf(8.0, rect.top - 4 - box.height)}px"
        }

        val onMove: (Event) -> Unit = { e -> trackPointer(e as MouseEvent) }
        val onScroll: (Event) -> Unit = { _ -> close() }
        val onLeaveWindow: (Event) -> Unit = { _ -> scheduleHide() }
        val onDocDown: (Event) -> Unit = { e ->
            if (popupEl?.contains(e.target as? Node) != true) close()
        }
        document.addEventListener("mousemove", onMove, true)
        document.documentElement?.addEventListener("mouseleave", onLeaveWindow)
        window.addEventListener("scroll", onScroll, true)
        document.addEventListener("mousedown", onDocDown, true)
        removeDocListeners = {
            document.removeEventListener("mousemove", onMove, true)
            document.documentElement?.removeEventListener("mouseleave", onLeaveWindow)
            window.removeEventListener("scroll", onScroll, true)
            document.removeEventListener("mousedown", onDocDown, true)
        }
    }

    private fun button(label: String, onClick: () -> Unit): HTMLButtonElement {
        val b = document.createElement("button") as HTMLButtonElement
        b.type = "button"
        b.className = "lunarbor-link-popup-button"
        b.textContent = label
        b.addEventListener("click", { _ ->
            close()
            onClick()
        })
        return b
    }

    /**
     * Keeps the open popup while the pointer is inside the corridor — the
     * box spanning the link and the popup, padded by [CORRIDOR_PAD_PX] — so
     * the way from the link to the button never closes it; outside it the
     * popup closes after [HIDE_DELAY_MS].
     */
    private fun trackPointer(e: MouseEvent) {
        pointerX = e.clientX.toDouble()
        pointerY = e.clientY.toDouble()
        val popup = popupEl ?: return
        val link = linkRect ?: return
        val box = popup.getBoundingClientRect()
        val inside = pointerX >= minOf(link.left, box.left) - CORRIDOR_PAD_PX &&
            pointerX <= maxOf(link.right, box.right) + CORRIDOR_PAD_PX &&
            pointerY >= minOf(link.top, box.top) - CORRIDOR_PAD_PX &&
            pointerY <= maxOf(link.bottom, box.bottom) + CORRIDOR_PAD_PX
        if (inside) cancelHide() else scheduleHide()
    }

    private fun scheduleHide() {
        if (popupEl == null || hideTimer != null) return
        hideTimer = window.setTimeout({ hideTimer = null; close() }, HIDE_DELAY_MS)
    }

    private fun cancelShow() {
        showTimer?.let(window::clearTimeout)
        showTimer = null
    }

    private fun cancelHide() {
        hideTimer?.let(window::clearTimeout)
        hideTimer = null
    }

    /** Removes the popup, if open. */
    fun close() {
        cancelHide()
        removeDocListeners?.invoke()
        removeDocListeners = null
        popupEl?.let { it.parentNode?.removeChild(it) }
        popupEl = null
        openKey = null
        linkRect = null
        hoveredKey = null
    }

    /** A link's box in client coordinates, kept from when the popup opened. */
    private data class Rect(val left: Double, val top: Double, val right: Double, val bottom: Double)

    private companion object {
        /** How long the pointer rests on a link before the popup opens. */
        const val SHOW_DELAY_MS = 1000

        /** How long the pointer may stray outside the link + popup corridor. */
        const val HIDE_DELAY_MS = 400

        /** Slack around the link + popup box before the pointer counts as gone. */
        const val CORRIDOR_PAD_PX = 12.0
    }
}
