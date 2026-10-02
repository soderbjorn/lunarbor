/*
 * LinkHoverPopup.kt (jsMain)
 * --------------------------
 * The small popup that appears when the pointer rests on a link in the
 * editor for a moment. A click on a link follows it, so without this
 * there is no way to change a link: the popup names where the link goes
 * and offers "Change link…" (the link search, picking a new target),
 * "Edit text" (the caret at the end of the link's text) and "Remove link"
 * (the text stays; not offered on a bare URL, which is a link to itself).
 *
 * View glue only: every edit is a `MainViewModel` intent
 * (`retargetLinkAt`, `editLinkTextAt`, `removeLinkAt`), addressed by the
 * link's document row and a model column inside it. Styled by the
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
 * @param scope Scope for the link search modal "Change link…" opens.
 * @param focusEditor Puts focus back in the editor, so "Edit text" leaves
 *   a live caret.
 */
internal class LinkHoverPopup(
    private val viewModel: MainViewModel,
    private val scope: CoroutineScope,
    private val focusEditor: () -> Unit,
) {
    private var popupEl: HTMLElement? = null
    private var showTimer: Int? = null
    private var hideTimer: Int? = null
    private var hoveredLink: HTMLElement? = null
    private var removeDocListeners: (() -> Unit)? = null
    private var editorEl: HTMLElement? = null
    private var pointerX: Double = 0.0
    private var pointerY: Double = 0.0

    /**
     * Wires the popup to [editor]: resting on a `data-href` span for
     * [SHOW_DELAY_MS] opens it; leaving the link and the popup, typing,
     * scrolling or pressing anywhere else closes it. Called once by
     * [MainScreen] when it builds the editor.
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
            if (link == null) {
                scheduleHide()
                return@addEventListener
            }
            cancelHide()
            if (link === hoveredLink) return@addEventListener
            hoveredLink = link
            cancelShow()
            if (popupEl != null) close()
            // Not while a button is down: that's a click or a drag.
            if (e.buttons.toInt() != 0) return@addEventListener
            showTimer = window.setTimeout({ show(link) }, SHOW_DELAY_MS)
        })
        editor.addEventListener("mouseleave", { _ -> scheduleHide() })
        editor.addEventListener("mousedown", { _ -> cancelShow(); close() })
        editor.addEventListener("keydown", { _ -> cancelShow(); close() })
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
     * Opens the popup for [link] once the hover delay ran out, if the
     * pointer still rests on that link. The editor repaints often (link
     * checks, autosave), so the span captured on `mouseover` may have been
     * replaced by now: the link under the last pointer position is looked
     * up again and must be the same one (same row and source column).
     */
    private fun show(link: HTMLElement) {
        showTimer = null
        val row = (link.closest("[data-row]") as? HTMLElement)?.getAttribute("data-row") ?: return
        val src = link.getAttribute("data-src-start") ?: return
        val live = linkSpanOf(document.elementFromPoint(pointerX, pointerY)) ?: return
        if ((live.closest("[data-row]") as? HTMLElement)?.getAttribute("data-row") != row) return
        if (live.getAttribute("data-src-start") != src) return
        if (editorEl?.contains(live) != true) return
        hoveredLink = live
        showFor(live)
    }

    private fun showFor(link: HTMLElement) {
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

        val target = document.createElement("div") as HTMLElement
        target.className = "lunarbor-link-popup-target"
        target.textContent = LunarborLink.parse(href)?.let { "/$it" } ?: href
        popup.appendChild(target)

        val actions = document.createElement("div") as HTMLElement
        actions.className = "lunarbor-link-popup-actions"
        actions.appendChild(button("Change link…") { changeLink(row, col, link.textContent.orEmpty()) })
        actions.appendChild(button("Edit text") {
            viewModel.editLinkTextAt(row, col)
            focusEditor()
        })
        // A link showing its own URL (a bare `https://…`) would just link
        // again once unlinked.
        if (link.textContent != href) {
            actions.appendChild(button("Remove link") { viewModel.removeLinkAt(row, col) })
        }
        popup.appendChild(actions)

        popup.addEventListener("mouseenter", { _ -> cancelHide() })
        popup.addEventListener("mouseleave", { _ -> scheduleHide() })
        // Keep presses in the popup from moving the editor's selection.
        popup.addEventListener("mousedown", { e -> e.preventDefault() })
        document.body?.appendChild(popup)
        popupEl = popup

        // Keep it inside the window.
        val box = popup.getBoundingClientRect()
        if (box.right > window.innerWidth - 8) {
            popup.style.left = "${maxOf(8.0, window.innerWidth - 8 - box.width)}px"
        }
        if (box.bottom > window.innerHeight - 8) {
            popup.style.top = "${maxOf(8.0, rect.top - 4 - box.height)}px"
        }

        val onScroll: (Event) -> Unit = { _ -> close() }
        val onDocDown: (Event) -> Unit = { e ->
            if (popupEl?.contains(e.target as? Node) != true) close()
        }
        window.addEventListener("scroll", onScroll, true)
        document.addEventListener("mousedown", onDocDown, true)
        removeDocListeners = {
            window.removeEventListener("scroll", onScroll, true)
            document.removeEventListener("mousedown", onDocDown, true)
        }
    }

    /**
     * Opens the link search pre-filled with the link's text; the picked
     * target replaces the link ([MainViewModel.retargetLinkAt]).
     */
    private fun changeLink(row: Int, col: Int, shownText: String) {
        val query = shownText.removePrefix("[[").removeSuffix("]]").substringBefore('|').trim()
        LinkSearchModal.forChangeLink(
            parentScope = scope,
            activePaneVmProvider = { viewModel },
            row = row,
            col = col,
        ).open(initialQuery = query)
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

    private fun scheduleHide() {
        cancelShow()
        hoveredLink = null
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
    }

    private companion object {
        /** How long the pointer rests on a link before the popup opens. */
        const val SHOW_DELAY_MS = 1000

        /** Grace period for moving the pointer from the link into the popup. */
        const val HIDE_DELAY_MS = 300
    }
}
