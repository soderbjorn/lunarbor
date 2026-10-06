/* SearchNodeHitCursor.kt (jsMain)
 *
 * Walking a search node's result rows with the arrow keys. A search
 * node's results are a read-only box inside the node's own row
 * (`OutlinePaintLoop.buildSearchNodeResults`), so the browser's caret
 * cannot enter them: Arrow Down on the node's line would jump over the
 * list, or stick. This class gives the editor a second, keyboard-only
 * cursor over that list:
 *
 *  - Arrow Down on the node's last text line highlights the first entry;
 *    Arrow Up on the first line of the row right below the list highlights
 *    the last one. On a search node's own page Arrow Down / Up start at
 *    the top / bottom.
 *  - Arrow Down / Up move between the entries; past the last one the
 *    caret goes on to the next row, before the first one it is back on
 *    the node's line.
 *  - Enter goes there in this pane, ⌘↩ (Ctrl-Enter off the Mac) opens a
 *    new window, ⌃↩ (Alt-Enter off the Mac) and the palette's "Toggle done"
 *    toggle done on the highlighted hit (LBR-22). "…and N more" is an entry
 *    too: Enter zooms into the node.
 *  - Escape, a click in the editor, or any unmodified key lets go; typing
 *    keys are swallowed then, so they never edit the node's line unseen.
 *
 * View only — ephemeral UI state (which entry is highlighted, by the
 * node's `LineId` so it survives repaints), no business rules: every
 * action is a `MainViewModel` intent. Owned by `MainScreen`. */
package se.soderbjorn.lunarbor.main

import kotlinx.browser.window
import org.w3c.dom.DOMRect
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.lunarbor.data.TextHit

/**
 * The keyboard's cursor over a search node's result rows (see the file
 * header). One per pane editor.
 *
 * ### Callers
 * - `MainScreen.handleKey` offers every keydown to [handleKey] first.
 * - `MainScreen.reconcile` calls [applyHighlight] after every repaint.
 * - `MainScreen`'s editor mousedown calls [clear].
 * - `AppShell`'s palette reads [selectedHit] through
 *   `MainScreen.selectedSearchHit` to offer "Toggle done" on it.
 *
 * @param viewModel The pane's facade; every action is one of its intents.
 */
internal class SearchNodeHitCursor(private val viewModel: MainViewModel) {

    /**
     * The highlighted entry.
     *
     * @property nodeId The search node's row id.
     * @property index Index into the node's [Entries]: a hit, or
     *   `hits.size` for "…and N more".
     */
    private data class Spot(val nodeId: LineId, val index: Int)

    /**
     * What a search node lists, as the paint loop draws it.
     *
     * @property nodeRow The node's document row.
     * @property hits The hit rows shown, in order.
     * @property hasMore Whether a clickable "…and N more" follows them.
     * @property isPage Whether the pane is zoomed into the node.
     */
    private data class Entries(val nodeRow: Int, val hits: List<TextHit>, val hasMore: Boolean, val isPage: Boolean) {
        val size: Int get() = hits.size + if (hasMore) 1 else 0
    }

    private var spot: Spot? = null

    /** `true` while an entry is highlighted. */
    val isActive: Boolean get() = spot != null

    /** Set by [move], so the next [applyHighlight] scrolls the entry into view. */
    private var scrollPending = false

    /**
     * The highlighted hit, or `null` (none, or "…and N more"). Read by the
     * palette to offer Toggle done on it.
     */
    fun selectedHit(): TextHit? {
        val s = spot ?: return null
        val entries = entriesOf(viewModel.currentBackingState, s.nodeId) ?: return null
        return entries.hits.getOrNull(s.index)
    }

    /** Toggle done on the highlighted hit, when it can be toggled. The palette's command and ⌃↩. */
    fun toggleSelectedDone() {
        val hit = selectedHit()?.takeIf { it.canToggleDone } ?: return
        viewModel.toggleDoneOnHit(hit)
    }

    /** Lets go of the highlight; the editor's caret shows again on the node's line. */
    fun clear(editor: HTMLElement) {
        if (spot == null) return
        spot = null
        applyHighlight(editor)
    }

    /**
     * Offers a keydown to the hit cursor (see the file header for the
     * keys). Called by `MainScreen.handleKey` before anything else, the
     * read-only page check included.
     *
     * @param syncSelection Pulls the DOM caret into the model; `false`
     *   when the editor holds none.
     * @return `true` when the key was handled (and its default prevented).
     */
    fun handleKey(editor: HTMLElement, event: KeyboardEvent, syncSelection: () -> Boolean): Boolean {
        val plain = !event.altKey && !event.metaKey && !event.ctrlKey && !event.shiftKey
        val current = spot
        if (current == null) {
            if (!plain || (event.key != "ArrowDown" && event.key != "ArrowUp")) return false
            return enter(editor, down = event.key == "ArrowDown", syncSelection).also { if (it) event.preventDefault() }
        }
        val state = viewModel.currentBackingState
        val entries = entriesOf(state, current.nodeId)
        if (entries == null || entries.size == 0) {
            clear(editor)
            return false
        }
        val index = current.index.coerceAtMost(entries.size - 1)
        val toggleChord = if (isMacPlatform) {
            event.ctrlKey && !event.metaKey && !event.altKey && !event.shiftKey
        } else {
            event.altKey && !event.ctrlKey && !event.metaKey && !event.shiftKey
        }
        val newWindowChord = !event.altKey && !event.shiftKey &&
            (if (isMacPlatform) event.metaKey && !event.ctrlKey else event.ctrlKey && !event.metaKey)
        when {
            event.key == "ArrowDown" && plain -> {
                if (index + 1 < entries.size) {
                    move(editor, current.nodeId, index + 1)
                } else if (!entries.isPage) {
                    // Past the last entry: on to the row below the list.
                    clear(editor)
                    viewModel.moveDown()
                }
            }
            event.key == "ArrowUp" && plain -> {
                if (index > 0) {
                    move(editor, current.nodeId, index - 1)
                } else if (!entries.isPage) {
                    // Before the first entry: back on the node's line.
                    clear(editor)
                }
            }
            event.key == "Enter" && toggleChord -> toggleSelectedDone()
            event.key == "Enter" && plain -> {
                clear(editor)
                val hit = entries.hits.getOrNull(index)
                if (hit != null) viewModel.navigateToSearchHit(hit) else viewModel.zoomInto(entries.nodeRow)
            }
            event.key == "Enter" && newWindowChord -> {
                entries.hits.getOrNull(index)?.let { hit -> viewModel.openSearchHitInNewWindow?.invoke(hit) }
            }
            event.key == "Escape" && plain -> clear(editor)
            // Modifiers alone, and chords (the palette, app shortcuts),
            // keep the highlight: the palette's Toggle done acts on it.
            event.key in MODIFIER_KEYS || event.metaKey || event.ctrlKey || event.altKey -> return false
            else -> {
                clear(editor)
                // A typing key would edit the node's line, where the caret
                // was hidden: swallow it. Anything else (Tab, Page Down, …)
                // goes on as usual from the node's line.
                val edits = event.key.length == 1 || event.key == "Backspace" || event.key == "Delete" ||
                    event.key == "Enter"
                if (!edits) return false
            }
        }
        event.preventDefault()
        return true
    }

    /**
     * Marks the highlighted entry in the DOM after a repaint (the paint
     * loop rebuilds every row), and hides the editor's caret while there
     * is one. Lets go when the list is gone — folded, emptied, the pane
     * navigated — or the caret has left the node's line.
     *
     * Called by `MainScreen.reconcile` after every paint, and by this
     * class after every change.
     */
    fun applyHighlight(editor: HTMLElement) {
        val old = editor.querySelectorAll(".is-key-selected")
        for (i in 0 until old.length) (old.item(i) as? Element)?.classList?.remove("is-key-selected")
        val s = spot
        val state = viewModel.currentBackingState
        val entries = s?.let { entriesOf(state, it.nodeId) }?.takeIf { it.size > 0 }
        val entry = if (s != null && entries != null) {
            boxOf(editor, entries.nodeRow)?.children?.item(s.index.coerceAtMost(entries.size - 1)) as? HTMLElement
        } else {
            null
        }
        if (entries == null || entry == null || (!entries.isPage && state.cursorRow != entries.nodeRow)) {
            spot = null
            editor.classList.remove("lunarbor-hit-cursor-active")
            return
        }
        entry.classList.add("is-key-selected")
        editor.classList.add("lunarbor-hit-cursor-active")
        if (scrollPending) {
            scrollPending = false
            entry.asDynamic().scrollIntoView(js("({ block: 'nearest' })"))
        }
    }

    /** Highlights entry [index] of the node [nodeId] and scrolls it into view. */
    private fun move(editor: HTMLElement, nodeId: LineId, index: Int) {
        spot = Spot(nodeId, index)
        scrollPending = true
        applyHighlight(editor)
    }

    /**
     * Arrow Down / Up from the editor's caret into a list, when the caret
     * is right next to one: Down on a search node's last text line, Up on
     * the first line of the row below a search node's list, either on a
     * search node's own page.
     *
     * @return `true` when the cursor took the key.
     */
    private fun enter(editor: HTMLElement, down: Boolean, syncSelection: () -> Boolean): Boolean {
        val state = viewModel.currentBackingState
        val docState = state.documentState ?: return false
        if (state.isReadOnlyPage) {
            val zoomRow = viewModel.zoomInfo(state)?.zoomRow ?: return false
            val nodeId = docState.lineIds.getOrNull(zoomRow) ?: return false
            val entries = entriesOf(state, nodeId)?.takeIf { it.size > 0 } ?: return false
            move(editor, nodeId, if (down) 0 else entries.size - 1)
            return true
        }
        if (!syncSelection()) return false
        val synced = viewModel.currentBackingState
        if (PaneBackingViewModel.selectionOf(synced) != null) return false
        val caret = caretRect()
        if (down) {
            val row = synced.cursorRow
            val nodeId = docState.lineIds.getOrNull(row) ?: return false
            val entries = entriesOf(synced, nodeId)?.takeIf { it.size > 0 } ?: return false
            val box = boxOf(editor, row) ?: return false
            // Only from the line right above the list, not from an
            // earlier line of a wrapped title.
            if (caret != null && box.getBoundingClientRect().top - caret.bottom > caret.height / 2) return false
            move(editor, nodeId, 0)
            return true
        }
        val rowEl = editor.querySelector("[data-row=\"${synced.cursorRow}\"]") as? HTMLElement ?: return false
        if (caret != null && caret.top - rowEl.getBoundingClientRect().top > caret.height / 2) return false
        val prev = previousRowElement(rowEl)
        val prevRow = prev?.getAttribute("data-row")?.toIntOrNull() ?: return false
        val nodeId = docState.lineIds.getOrNull(prevRow) ?: return false
        val entries = entriesOf(synced, nodeId)?.takeIf { it.size > 0 } ?: return false
        if (prev.querySelector("[$SEARCH_NODE_ROW_ATTR=\"$prevRow\"]") == null) return false
        // The model caret goes to the end of the node's line, where Up
        // past the first entry leaves it.
        val endCol = synced.lines.getOrNull(prevRow)?.length ?: 0
        viewModel.setSelection(prevRow, endCol, prevRow, endCol)
        move(editor, nodeId, entries.size - 1)
        return true
    }

    /** The node [nodeId]'s entries as painted, or `null` when it lists nothing (folded, not a search node). */
    private fun entriesOf(state: PaneBackingViewModel.State, nodeId: LineId): Entries? {
        val row = state.documentState?.lineIds?.indexOf(nodeId)?.takeIf { it >= 0 } ?: return null
        val view = viewModel.searchNodeOf(state, row) ?: return null
        val result = view.result ?: return null
        val isPage = viewModel.zoomInfo(state)?.zoomRow == row
        if (!isPage && view.folded) return null
        val hits = if (isPage) result.hits else result.hits.take(PaneBackingViewModel.SEARCH_NODE_INLINE)
        return Entries(row, hits, hasMore = !isPage && result.total > hits.size, isPage = isPage)
    }

    /** The painted result box of the search node at [nodeRow]. */
    private fun boxOf(editor: HTMLElement, nodeRow: Int): HTMLElement? =
        editor.querySelector("[$SEARCH_NODE_ROW_ATTR=\"$nodeRow\"]") as? HTMLElement

    /** The collapsed caret's box, or `null` when the browser reports none (an empty line). */
    private fun caretRect(): DOMRect? {
        val sel = window.asDynamic().getSelection() ?: return null
        if ((sel.rangeCount as Number).toInt() == 0) return null
        val rect = sel.getRangeAt(0).getBoundingClientRect().unsafeCast<DOMRect>()
        return if (rect.height > 0.0) rect else null
    }

    private companion object {
        val MODIFIER_KEYS = setOf("Shift", "Meta", "Control", "Alt", "CapsLock")
    }
}
