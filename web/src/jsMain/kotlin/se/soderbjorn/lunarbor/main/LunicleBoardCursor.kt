/* LunicleBoardCursor.kt (jsMain)
 *
 * The keyboard on a board node's rows (LBR-28) and editing them in place
 * (LBR-29). A board is one `contenteditable="false"` box inside its node's
 * row (`LunicleBoardView.buildLunicleBoard`), so the editor's caret cannot
 * enter it: this class gives the editor a cursor over its rows, beside
 * `SearchNodeHitCursor` (same shape, same hand-off at the ends):
 *
 *  - Arrow Down on the node's last text line goes onto the board's first
 *    row; Arrow Up on the first line of the row right below the board onto
 *    its last row. On a board node's own page Arrow Down / Up start at the
 *    top / bottom.
 *  - Inside, Down / Up go row by row in `LunicleBoardRows.of`'s order
 *    (column name, issue, an unfolded issue's description, comments and
 *    "Comment…", drafts, "New issue"); past the last row the caret goes on
 *    to the row below, before the first it is back on the node's line (on
 *    the page it stays).
 *  - ⌘↑ / ⌘↓ fold and unfold the column or issue under the cursor
 *    (`MainViewModel.foldLunicleRow`); on a row under an issue ⌘↑ folds
 *    the issue and lands on its title.
 *  - Escape, or a click in the editor, leaves the board onto the node's
 *    line.
 *
 * **Editing (LBR-29).** On an editable issue's title, a draft and a
 * column's "New issue" line the cursor puts a real `<input>` in place of
 * the text ([Field]): typing, ←/→, selection, copy / paste and ⌘Z within
 * the field are the browser's own. The text stays in the field until the
 * caret leaves the row (an arrow key, Escape, a click elsewhere, the pane
 * navigating): then `MainViewModel.leaveLunicleRow` sends a changed title,
 * files a draft that has one, or removes an empty draft. Enter is
 * `MainViewModel.lunicleEnter` (commit, then a draft below — also on a
 * column's name); ⌫ on an empty draft removes it
 * (`MainViewModel.removeLunicleDraft`). The field element lives across
 * repaints — the paint loop rebuilds every row, and the cursor puts the
 * same element, with its text and selection, back into the new row — so
 * neither a poll nor a remote change to the issue ever overwrites what is
 * being typed. A row that cannot be edited (a read-only token, Lunicle's
 * `canEdit: false`) gets a tint; typing on it makes the indicator say why
 * once (`MainViewModel.explainLunicleRow`).
 *
 * Board edits are remote writes, never in the pane's undo stack: ⌘Z on a
 * board row outside a field does nothing. Every other key on a row that is
 * not a field is swallowed, so it never edits the node's line, where the
 * editor's caret is hidden. Other chords (the palette, navigation, app
 * shortcuts) go on as usual.
 *
 * Rows that LBR-31 will edit (the description, "Comment…") still show a
 * drawn caret. The cursor is keyed by the node's `LineId` and the row's
 * `LunicleRowRef` (issue id plus row kind, never an index), and found
 * again after every repaint (`LunicleBoardRows.relocate`).
 *
 * View only — ephemeral UI state, no business rules: the rows and rules
 * come from commonMain, every action is a `MainViewModel` intent. Owned by
 * `MainScreen`. */
package se.soderbjorn.lunarbor.main

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.DOMRect
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.lunarbor.lunicle.LunicleBoardKey

/**
 * The keyboard's cursor over a board node's rows, and its text field (see
 * the file header). One per pane editor.
 *
 * ### Callers
 * - `MainScreen.handleKey` offers every keydown to [handleKey], right after
 *   `SearchNodeHitCursor` and before the read-only page check — keys typed
 *   in the field bubble up to it too.
 * - `MainScreen.reconcile` calls [saveField] before every repaint and
 *   [applyHighlight] after it.
 * - `MainScreen`'s editor mousedown calls [clear]; its
 *   [LUNICLE_PRESS_EVENT] listener calls [press].
 *
 * @param viewModel The pane's facade; every action is one of its intents.
 * @param focusEditor Gives the keyboard back to the editor (its caret on
 *   the model's) after the field goes away.
 */
internal class LunicleBoardCursor(
    private val viewModel: MainViewModel,
    private val focusEditor: () -> Unit,
) {

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
     * @property view The board as the pane draws it.
     */
    private data class Rows(
        val nodeRow: Int,
        val rows: List<LunicleBoardRow>,
        val isPage: Boolean,
        val view: PaneBackingViewModel.LunicleBoardView,
    )

    /**
     * The text field on the cursor's row.
     *
     * @property board The board it edits.
     * @property ref The row it edits.
     * @property input The element, kept across repaints.
     * @property host The painted text it stands in for, shown again when it goes.
     */
    private class Field(val board: LunicleBoardKey, val ref: LunicleRowRef, val input: HTMLInputElement, var host: HTMLElement?) {
        /** The selection saved before a repaint ([saveField]). */
        var selStart = 0

        /** See [selStart]. */
        var selEnd = 0
    }

    private var spot: Spot? = null
    private var field: Field? = null

    /** `true` while the cursor is on a board row. */
    val isActive: Boolean get() = spot != null

    /** Set by [move], so the next [applyHighlight] scrolls the row into view. */
    private var scrollPending = false

    /**
     * Leaves the board (a click in the editor): the field's text is
     * committed ([leaveField]) and the editor's caret shows again.
     */
    fun clear(editor: HTMLElement) {
        if (spot == null) return
        leaveField(refocus = false)
        spot = null
        applyHighlight(editor)
    }

    /**
     * A press on a field's text (a title, a draft, "New issue";
     * [LUNICLE_PRESS_EVENT]): the cursor goes there and edits it. The
     * model caret goes to the end of the node's line, where leaving the
     * board up puts it.
     *
     * @param nodeRow The board node's document row.
     * @param key The row's [LunicleRowRef.key].
     */
    fun press(editor: HTMLElement, nodeRow: Int, key: String) {
        val state = viewModel.currentBackingState
        val nodeId = state.documentState?.lineIds?.getOrNull(nodeRow) ?: return
        val board = rowsOf(state, nodeId) ?: return
        val ref = board.rows.firstOrNull { it.ref.key == key }?.ref ?: return
        if (field?.ref?.key == key) return
        leaveField(refocus = false)
        if (!board.isPage && state.cursorRow != nodeRow) {
            val endCol = state.lines.getOrNull(nodeRow)?.length ?: 0
            viewModel.setSelection(nodeRow, endCol, nodeRow, endCol)
        }
        move(editor, nodeId, ref)
    }

    /** Remembers the field's selection before a repaint detaches it. Called by `MainScreen.reconcile`. */
    fun saveField() {
        val f = field ?: return
        f.selStart = f.input.selectionStart ?: f.input.value.length
        f.selEnd = f.input.selectionEnd ?: f.selStart
    }

    /**
     * Offers a keydown to the cursor (see the file header for the keys).
     *
     * @param syncSelection Pulls the DOM caret into the model; `false`
     *   when the editor holds none.
     * @return `true` when the key was handled — its default prevented,
     *   except for keys the field handles itself (typing, ←/→, ⌘Z, …).
     */
    fun handleKey(editor: HTMLElement, event: KeyboardEvent, syncSelection: () -> Boolean): Boolean {
        val plain = !event.altKey && !event.metaKey && !event.ctrlKey && !event.shiftKey
        val current = spot
        if (current == null) {
            if (!plain || (event.key != "ArrowDown" && event.key != "ArrowUp")) return false
            return enter(editor, down = event.key == "ArrowDown", syncSelection).also { if (it) event.preventDefault() }
        }
        val board = rowsOf(viewModel.currentBackingState, current.nodeId)
        val at = board?.let { LunicleBoardRows.relocate(it.rows, current.ref, it.view.createdIds) }
        if (board == null || at == null) {
            clear(editor)
            return false
        }
        val f = field?.takeIf { it.ref.key == at.key }
        // An input method composing in the field owns every key.
        if (f != null && (event.asDynamic().isComposing == true || event.keyCode == 229)) return true
        val foldChord = event.metaKey && !event.ctrlKey && !event.altKey && !event.shiftKey
        when {
            (event.key == "ArrowDown" || event.key == "ArrowUp") && plain -> {
                val step = LunicleBoardRows.step(board.rows, at, down = event.key == "ArrowDown")
                leaveField(refocus = true)
                when (step) {
                    is LunicleBoardRows.Step.To -> move(editor, current.nodeId, step.ref)
                    LunicleBoardRows.Step.LeaveUp -> if (!board.isPage) clear(editor) else move(editor, current.nodeId, at)
                    LunicleBoardRows.Step.LeaveDown -> if (!board.isPage) {
                        // Past the last row: on to the row below the board.
                        clear(editor)
                        viewModel.moveDown()
                    } else {
                        move(editor, current.nodeId, at)
                    }
                }
            }
            (event.key == "ArrowDown" || event.key == "ArrowUp") && foldChord -> {
                leaveField(refocus = true)
                val next = viewModel.foldLunicleRow(board.nodeRow, at, folded = event.key == "ArrowUp") ?: at
                move(editor, current.nodeId, next)
            }
            event.key == "Enter" && plain -> onEnter(editor, current.nodeId, board, at, f)
            event.key == "Backspace" && plain && f != null && at.kind == LunicleRowKind.DRAFT && f.input.value.isEmpty() -> {
                val step = viewModel.removeLunicleDraft(board.nodeRow, at)
                dropField(refocus = true)
                when (step) {
                    is LunicleBoardRows.Step.To -> move(editor, current.nodeId, step.ref)
                    else -> if (!board.isPage) clear(editor) else applyHighlight(editor)
                }
            }
            event.key == "Escape" && plain -> {
                leaveField(refocus = true)
                clear(editor)
            }
            event.key in MODIFIER_KEYS -> return false
            f != null -> return fieldKey(event)
            // Chords that would edit the node's line unseen are swallowed;
            // the rest (palette, navigation, app shortcuts) go on.
            (event.metaKey || event.ctrlKey || event.altKey) && !isEditingChord(event) -> return false
            // Typing on a title that cannot be edited: say why, once.
            at.kind == LunicleRowKind.ISSUE && event.key.length == 1 && !event.metaKey && !event.ctrlKey ->
                viewModel.explainLunicleRow(board.nodeRow, at)
            // Anything else does nothing on a board row.
            else -> {}
        }
        event.preventDefault()
        return true
    }

    /**
     * Enter on the board row [at] (LBR-29): `MainViewModel.lunicleEnter`
     * commits the field and says where the caret goes — a new draft, or
     * the "New issue" line again (emptied).
     */
    private fun onEnter(editor: HTMLElement, nodeId: LineId, board: Rows, at: LunicleRowRef, f: Field?) {
        val text = f?.input?.value.orEmpty()
        val next = viewModel.lunicleEnter(board.nodeRow, at, text)
        when {
            next == null -> {
                // A title committed with nowhere to go (no draft allowed):
                // the field starts again from the title it now has.
                if (f != null && at.kind == LunicleRowKind.ISSUE) {
                    dropField(refocus = false)
                    applyHighlight(editor)
                }
            }
            next.key == at.key -> f?.input?.value = ""
            else -> {
                dropField(refocus = false)
                move(editor, nodeId, next)
            }
        }
    }

    /**
     * A key typed in the field: the field's own (typing, ←/→, Home / End,
     * selection, ⌫ / Delete, ⌘A / C / V / X / Z / Y) goes to the browser;
     * Tab, modified Enter and inline-style chords are swallowed; other
     * chords go on to the app.
     *
     * @return `true` when the key is taken (default prevented only when swallowed).
     */
    private fun fieldKey(event: KeyboardEvent): Boolean {
        val chord = event.metaKey || event.ctrlKey
        if (event.key == "Tab" || event.key == "Enter") {
            event.preventDefault()
            return true
        }
        if (!chord) return true
        if (event.key.lowercase() in FIELD_CHORDS || event.key in FIELD_CHORD_KEYS) return true
        if (isEditingChord(event)) {
            event.preventDefault()
            return true
        }
        return false
    }

    /**
     * Marks the cursor's row in the DOM after a repaint (the paint loop
     * rebuilds every row), puts the field back on it (or starts one on a
     * row edited in a field), and hides the editor's caret meanwhile. Finds
     * the row again by its key; lets go — committing the field — when the
     * board is gone (folded, the pane navigated), nothing of the row is
     * left, or the caret has left the node's line.
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
        val ref = if (s != null && board != null) LunicleBoardRows.relocate(board.rows, s.ref, board.view.createdIds) else null
        if (s == null || board == null || ref == null || (!board.isPage && state.cursorRow != board.nodeRow)) {
            spot = null
            leaveField(refocus = true)
            editor.classList.remove(ACTIVE_CLASS)
            return
        }
        spot = Spot(s.nodeId, ref)
        // A row the model has but the DOM not yet (a new draft): the
        // repaint that draws it calls this again.
        val rowEl = rowElementOf(editor, board.nodeRow, ref) ?: return
        rowEl.classList.add(SELECTED_CLASS)
        val row = board.rows.firstOrNull { it.ref.key == ref.key }
        val f = field
        if (f != null && (f.ref.key != ref.key || row?.editable != true)) {
            // The edited row is gone from under the cursor, or no longer
            // editable (a write found the token read-only).
            dropField(refocus = true)
        }
        if (row?.editable == true && ref.kind in FIELD_KINDS) {
            mountField(board, ref, rowEl)
        } else if (row?.editable == true) {
            rowEl.classList.add(CARET_CLASS)
        }
        editor.classList.add(ACTIVE_CLASS)
        if (scrollPending) {
            scrollPending = false
            rowEl.asDynamic().scrollIntoView(js("({ block: 'nearest' })"))
        }
    }

    /**
     * Puts the field on the painted row [rowEl] — the same element as
     * before a repaint, with its text and selection, or a new one starting
     * from `MainViewModel.beginLunicleEdit`'s text. A row that cannot be
     * edited after all keeps its tint.
     */
    private fun mountField(board: Rows, ref: LunicleRowRef, rowEl: HTMLElement) {
        val host = rowEl.querySelector("[$LUNICLE_FIELD_ATTR]") as? HTMLElement ?: return
        var f = field
        if (f == null) {
            val text = viewModel.beginLunicleEdit(board.nodeRow, ref) ?: return
            val input = document.createElement("input") as HTMLInputElement
            input.type = "text"
            input.className = "lunarbor-lunicle-field"
            input.value = text
            input.spellcheck = true
            input.setAttribute("autocomplete", "off")
            input.setAttribute("aria-label", if (ref.kind == LunicleRowKind.ISSUE) "Issue title" else "New issue title")
            if (ref.kind == LunicleRowKind.NEW_ISSUE) input.placeholder = NEW_ISSUE_TEXT
            // Presses inside place the field's own caret; the board's
            // handler (which prevents that) must not see them.
            input.addEventListener("mousedown", { ev -> ev.stopPropagation() })
            input.addEventListener("blur", { _ -> window.setTimeout({ onFieldBlur(input) }, 0) })
            f = Field(board.view.key, ref, input, host)
            f.selStart = text.length
            f.selEnd = text.length
            field = f
        }
        f.host = host
        if (f.input.nextSibling !== host) host.parentNode?.insertBefore(f.input, host)
        host.style.display = "none"
        if (document.activeElement !== f.input) {
            f.input.focus()
            f.input.setSelectionRange(f.selStart, f.selEnd)
        }
    }

    /**
     * The field lost the focus: when it went elsewhere in the app (another
     * pane, a click outside the board) the caret has left the row — commit
     * and let go. Not when the window lost the focus (another app), nor
     * when a repaint briefly took the field out and put it back.
     */
    private fun onFieldBlur(input: HTMLInputElement) {
        val f = field ?: return
        if (f.input !== input || document.activeElement === input || !document.hasFocus()) return
        leaveField(refocus = false)
        spot = null
    }

    /**
     * The caret leaves the field's row: its text goes to
     * `MainViewModel.leaveLunicleRow` (sent, filed, or the empty draft
     * removed), and the field goes.
     *
     * @param refocus Give the keyboard back to the editor when the field had it.
     */
    private fun leaveField(refocus: Boolean) {
        val f = field ?: return
        val text = f.input.value
        dropField(refocus)
        viewModel.leaveLunicleRow(f.board, f.ref, text)
    }

    /** Takes the field out (its text dropped), showing the painted text again. */
    private fun dropField(refocus: Boolean) {
        val f = field ?: return
        field = null
        val hadFocus = document.activeElement === f.input
        f.host?.style?.removeProperty("display")
        f.input.parentNode?.removeChild(f.input)
        if (refocus && hadFocus) focusEditor()
    }

    /** Puts the cursor on [ref] of the node [nodeId] and scrolls it into view. */
    private fun move(editor: HTMLElement, nodeId: LineId, ref: LunicleRowRef) {
        if (field != null && field?.ref?.key != ref.key) leaveField(refocus = true)
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
        return Rows(row, rows, isPage, view)
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

        /** Rows edited in a real text field (LBR-29). */
        val FIELD_KINDS = setOf(LunicleRowKind.ISSUE, LunicleRowKind.DRAFT, LunicleRowKind.NEW_ISSUE)

        /** ⌘ / Ctrl letters the field handles itself: select all, copy, paste, cut, undo, redo. */
        val FIELD_CHORDS = setOf("a", "c", "v", "x", "z", "y")

        /** ⌘ / Ctrl keys the field handles itself: line and word moves and deletes. */
        val FIELD_CHORD_KEYS = setOf("ArrowLeft", "ArrowRight", "Backspace", "Delete", "Home", "End")

        /** On the cursor's row element. */
        const val SELECTED_CLASS = "is-board-cursor"

        /** Also on it when the row takes a drawn caret (the description, "Comment…"). */
        const val CARET_CLASS = "is-key-caret"

        /** On the editor while the cursor is on a board: its own caret is hidden. */
        const val ACTIVE_CLASS = "lunarbor-board-cursor-active"
    }
}
