/* LunicleBoardCursor.kt (jsMain)
 *
 * The arrow keys through a board node's rows (LBR-28). A board is one
 * `contenteditable="false"` box inside its node's row
 * (`LunicleBoardView.buildLunicleBoard`), so the browser's caret cannot
 * enter it: this class gives the editor a keyboard cursor over its rows,
 * beside `SearchNodeHitCursor` (same shape, same hand-off at the ends):
 *
 *  - Arrow Down on the node's last text line goes onto the board's first
 *    row; Arrow Up on the first line of the row right below the board onto
 *    its last row. On a board node's own page Arrow Down / Up start at the
 *    top / bottom.
 *  - Inside, Down / Up go row by row in `LunicleBoardRows.of`'s order
 *    (column name, issue, an unfolded issue's description, comments and
 *    "Comment…"); past the last row the caret goes on to the row below,
 *    before the first it is back on the node's line (on the page it stays).
 *  - ⌘↑ / ⌘↓ fold and unfold the column or issue under the cursor
 *    (`MainViewModel.foldLunicleRow`, the chords of `setCaretItemFolded`);
 *    on a row under an issue ⌘↑ folds the issue and lands on its title.
 *  - Escape, or a click in the editor, leaves the board onto the node's
 *    line.
 *  - Every other key does nothing and is swallowed — typing, Enter, Tab,
 *    Backspace, editing chords — so it never edits the node's line, where
 *    the editor's caret is hidden. Other chords (the palette, navigation,
 *    app shortcuts) go on as usual. Editing board rows comes with LBR-29
 *    and LBR-31.
 *
 * Editable rows (an issue's title, its description, "Comment…") show a
 * caret; the others (column names, comments) a focus tint, as in the
 * design's prototype. The cursor is keyed by the node's `LineId` and the
 * row's `LunicleRowRef` (issue id plus row kind, never an index), and found
 * again after every repaint (`LunicleBoardRows.relocate`), so a poll that
 * adds issues above never moves it off its row.
 *
 * View only — ephemeral UI state, no business rules: the rows come from
 * commonMain, every action is a `MainViewModel` intent. Owned by
 * `MainScreen`. */
package se.soderbjorn.lunarbor.main

import kotlinx.browser.window
import org.w3c.dom.DOMRect
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent

/**
 * The keyboard's cursor over a board node's rows (see the file header).
 * One per pane editor.
 *
 * ### Callers
 * - `MainScreen.handleKey` offers every keydown to [handleKey], right after
 *   `SearchNodeHitCursor` and before the read-only page check.
 * - `MainScreen.reconcile` calls [applyHighlight] after every repaint.
 * - `MainScreen`'s editor mousedown calls [clear].
 *
 * @param viewModel The pane's facade; every action is one of its intents.
 */
internal class LunicleBoardCursor(private val viewModel: MainViewModel) {

    /**
     * The row the cursor is on.
     *
     * @property nodeId The board node's row id.
     * @property ref The board row, by issue id plus kind.
     */
    private data class Spot(val nodeId: LineId, val ref: LunicleRowRef)

    /**
     * A board's navigable rows as painted.
     *
     * @property nodeRow The node's document row.
     * @property rows Its rows, top to bottom.
     * @property isPage Whether the pane is zoomed into the node.
     */
    private data class Rows(val nodeRow: Int, val rows: List<LunicleBoardRow>, val isPage: Boolean)

    private var spot: Spot? = null

    /** `true` while the cursor is on a board row. */
    val isActive: Boolean get() = spot != null

    /** Set by [move], so the next [applyHighlight] scrolls the row into view. */
    private var scrollPending = false

    /** Leaves the board; the editor's caret shows again on the node's line. */
    fun clear(editor: HTMLElement) {
        if (spot == null) return
        spot = null
        applyHighlight(editor)
    }

    /**
     * Offers a keydown to the cursor (see the file header for the keys).
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
        val board = rowsOf(viewModel.currentBackingState, current.nodeId)
        val at = board?.let { LunicleBoardRows.relocate(it.rows, current.ref) }
        if (board == null || at == null) {
            clear(editor)
            return false
        }
        val foldChord = event.metaKey && !event.ctrlKey && !event.altKey && !event.shiftKey
        when {
            (event.key == "ArrowDown" || event.key == "ArrowUp") && plain -> {
                when (val step = LunicleBoardRows.step(board.rows, at, down = event.key == "ArrowDown")) {
                    is LunicleBoardRows.Step.To -> move(editor, current.nodeId, step.ref)
                    LunicleBoardRows.Step.LeaveUp -> if (!board.isPage) clear(editor)
                    LunicleBoardRows.Step.LeaveDown -> if (!board.isPage) {
                        // Past the last row: on to the row below the board.
                        clear(editor)
                        viewModel.moveDown()
                    }
                }
            }
            (event.key == "ArrowDown" || event.key == "ArrowUp") && foldChord -> {
                val next = viewModel.foldLunicleRow(board.nodeRow, at, folded = event.key == "ArrowUp") ?: at
                move(editor, current.nodeId, next)
            }
            event.key == "Escape" && plain -> clear(editor)
            event.key in MODIFIER_KEYS -> return false
            // Chords that would edit the node's line unseen are swallowed;
            // the rest (palette, navigation, app shortcuts) go on.
            (event.metaKey || event.ctrlKey || event.altKey) && !isEditingChord(event) -> return false
            // Anything else does nothing on a board row.
            else -> {}
        }
        event.preventDefault()
        return true
    }

    /**
     * Marks the cursor's row in the DOM after a repaint (the paint loop
     * rebuilds every row) and hides the editor's caret meanwhile. Finds
     * the row again by its key; lets go when the board is gone (folded,
     * the pane navigated), nothing of the row is left, or the caret has
     * left the node's line.
     *
     * Called by `MainScreen.reconcile` after every paint, and by this
     * class after every change.
     */
    fun applyHighlight(editor: HTMLElement) {
        val old = editor.querySelectorAll(".$SELECTED_CLASS")
        for (i in 0 until old.length) (old.item(i) as? Element)?.classList?.remove(SELECTED_CLASS, CARET_CLASS)
        val s = spot
        val state = viewModel.currentBackingState
        val board = s?.let { rowsOf(state, it.nodeId) }
        val ref = if (s != null && board != null) LunicleBoardRows.relocate(board.rows, s.ref) else null
        val rowEl = if (board != null && ref != null) rowElementOf(editor, board.nodeRow, ref) else null
        if (s == null || board == null || ref == null || rowEl == null ||
            (!board.isPage && state.cursorRow != board.nodeRow)
        ) {
            spot = null
            editor.classList.remove(ACTIVE_CLASS)
            return
        }
        spot = Spot(s.nodeId, ref)
        rowEl.classList.add(SELECTED_CLASS)
        if (board.rows.firstOrNull { it.ref.key == ref.key }?.editable == true) rowEl.classList.add(CARET_CLASS)
        editor.classList.add(ACTIVE_CLASS)
        if (scrollPending) {
            scrollPending = false
            rowEl.asDynamic().scrollIntoView(js("({ block: 'nearest' })"))
        }
    }

    /** Puts the cursor on [ref] of the node [nodeId] and scrolls it into view. */
    private fun move(editor: HTMLElement, nodeId: LineId, ref: LunicleRowRef) {
        spot = Spot(nodeId, ref)
        scrollPending = true
        applyHighlight(editor)
    }

    /**
     * Arrow Down / Up from the editor's caret onto a board, when the caret
     * is right next to one: Down on a board node's last text line, Up on
     * the first line of the row below a board, either on a board node's
     * own page.
     *
     * @return `true` when the cursor took the key.
     */
    private fun enter(editor: HTMLElement, down: Boolean, syncSelection: () -> Boolean): Boolean {
        val state = viewModel.currentBackingState
        val docState = state.documentState ?: return false
        if (state.isReadOnlyPage) {
            val zoomRow = viewModel.zoomInfo(state)?.zoomRow ?: return false
            val nodeId = docState.lineIds.getOrNull(zoomRow) ?: return false
            val board = rowsOf(state, nodeId) ?: return false
            val ref = LunicleBoardRows.entry(board.rows, down) ?: return false
            move(editor, nodeId, ref)
            return true
        }
        if (!syncSelection()) return false
        val synced = viewModel.currentBackingState
        if (PaneBackingViewModel.selectionOf(synced) != null) return false
        val caret = caretRect()
        if (down) {
            val row = synced.cursorRow
            val nodeId = docState.lineIds.getOrNull(row) ?: return false
            val board = rowsOf(synced, nodeId) ?: return false
            val ref = LunicleBoardRows.entry(board.rows, down = true) ?: return false
            val box = boxOf(editor, row) ?: return false
            // Only from the line right above the board, not from an
            // earlier line of a wrapped title.
            if (caret != null && box.getBoundingClientRect().top - caret.bottom > caret.height / 2) return false
            move(editor, nodeId, ref)
            return true
        }
        val rowEl = editor.querySelector("[data-row=\"${synced.cursorRow}\"]") as? HTMLElement ?: return false
        if (caret != null && caret.top - rowEl.getBoundingClientRect().top > caret.height / 2) return false
        var prev = rowEl.previousElementSibling
        while (prev != null && !prev.hasAttribute("data-row")) prev = prev.previousElementSibling
        val prevRow = prev?.getAttribute("data-row")?.toIntOrNull() ?: return false
        val nodeId = docState.lineIds.getOrNull(prevRow) ?: return false
        val board = rowsOf(synced, nodeId) ?: return false
        val ref = LunicleBoardRows.entry(board.rows, down = false) ?: return false
        if (prev.querySelector("[$LUNICLE_BOARD_ROW_ATTR=\"$prevRow\"]") == null) return false
        // The model caret goes to the end of the node's line, where Up
        // past the first row leaves it.
        val endCol = synced.lines.getOrNull(prevRow)?.length ?: 0
        viewModel.setSelection(prevRow, endCol, prevRow, endCol)
        move(editor, nodeId, ref)
        return true
    }

    /**
     * The board of the node [nodeId] as painted, or `null` when none is
     * shown (no board node, folded, not read yet, malformed).
     */
    private fun rowsOf(state: PaneBackingViewModel.State, nodeId: LineId): Rows? {
        val row = state.documentState?.lineIds?.indexOf(nodeId)?.takeIf { it >= 0 } ?: return null
        val view = viewModel.lunicleBoardOf(state, row) ?: return null
        val isPage = viewModel.zoomInfo(state)?.zoomRow == row
        if (!isPage && view.folded) return null
        val rows = LunicleBoardRows.of(view).takeIf { it.isNotEmpty() } ?: return null
        return Rows(row, rows, isPage)
    }

    /** The painted board box of the node at [nodeRow]. */
    private fun boxOf(editor: HTMLElement, nodeRow: Int): HTMLElement? =
        editor.querySelector("[$LUNICLE_BOARD_ROW_ATTR=\"$nodeRow\"]") as? HTMLElement

    /** The painted row [ref] of the board at [nodeRow] (compared by attribute, so no selector escaping). */
    private fun rowElementOf(editor: HTMLElement, nodeRow: Int, ref: LunicleRowRef): HTMLElement? {
        val box = boxOf(editor, nodeRow) ?: return null
        val children = box.children
        for (i in 0 until children.length) {
            val el = children.item(i) as? HTMLElement ?: continue
            if (el.getAttribute(LUNICLE_ROW_KEY_ATTR) == ref.key) return el
        }
        return null
    }

    /** The collapsed caret's box, or `null` when the browser reports none (an empty line). */
    private fun caretRect(): DOMRect? {
        val sel = window.asDynamic().getSelection() ?: return null
        if ((sel.rangeCount as Number).toInt() == 0) return null
        val rect = sel.getRangeAt(0).getBoundingClientRect().unsafeCast<DOMRect>()
        return if (rect.height > 0.0) rect else null
    }

    /**
     * A chord that would edit the node's line (where the hidden caret is):
     * any modified Backspace / Delete / Enter / Tab, and ⌘ / Ctrl with
     * cut, paste, undo, redo or an inline style.
     */
    private fun isEditingChord(event: KeyboardEvent): Boolean {
        if (event.key in setOf("Backspace", "Delete", "Enter", "Tab")) return true
        return (event.metaKey || event.ctrlKey) && event.key.lowercase() in setOf("x", "v", "z", "y", "b", "i", "k")
    }

    private companion object {
        val MODIFIER_KEYS = setOf("Shift", "Meta", "Control", "Alt", "CapsLock")

        /** On the cursor's row element. */
        const val SELECTED_CLASS = "is-board-cursor"

        /** Also on it when the row is editable: a caret, not a tint. */
        const val CARET_CLASS = "is-key-caret"

        /** On the editor while the cursor is on a board: its own caret is hidden. */
        const val ACTIVE_CLASS = "lunarbor-board-cursor-active"
    }
}
