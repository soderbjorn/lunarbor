/*
 * DocumentViewBackingViewModel.kt
 * -------------------------------
 * Per-viewer state on top of the shared `DocumentBackingViewModel`. This file
 * owns everything that belongs to *one* viewer of the note — cursor position,
 * selection anchor, and the current "zoom target" (the bullet whose subtree
 * is currently displayed as the editor's root).
 *
 * All selection-aware editor intents live here. Typing that replaces a
 * selection, bullet continuation on Enter, indent/outdent, and zoom
 * navigation are composed from primitive edits on the document VM plus local
 * cursor updates, using the private `patch { }` helper to keep the mirrored
 * document snapshot consistent on every emission.
 *
 * The "zoom" feature models Notegrow as an infinite outliner: clicking a
 * bullet marker makes that bullet the logical root of the current view, so
 * only its descendants are shown and edited. Zoom state is view-local — the
 * underlying document is never reshaped. A sticky header above the editor
 * displays either the current zoom target's text or "Root".
 *
 * This file is commonMain — no DOM, Android UI, or UIKit imports.
 */

package se.soderbjorn.notegrow.main

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The view-layer ViewModel for a single viewer (one window / pane / device)
 * of the shared note. It mirrors `DocumentBackingViewModel`'s content and
 * adds cursor, selection anchor, and zoom target on top.
 *
 * ### Callers
 * - Created per viewer by the platform DI graph (`JsAppGraph`) and injected
 *   into the platform `MainViewModel`.
 * - `MainViewModel` delegates every intent to this class one-for-one.
 * - Tests exercise the intents directly without going through a platform
 *   VM.
 *
 * @param documentBackingViewModel The shared document VM this viewer reads
 *   from and writes to.
 * @param scope Coroutine scope owning the collector that mirrors the
 *   document VM's state flow.
 */
class DocumentViewBackingViewModel(
    private val documentBackingViewModel: DocumentBackingViewModel,
    scope: CoroutineScope
) {
    /**
     * Immutable snapshot of one viewer's state.
     *
     * @property documentState A mirror of the latest `DocumentBackingViewModel.State`,
     *   or `null` before the first emission. Kept in sync by the collector
     *   in [init] and by [patch] after any edit, so every emission is a
     *   consistent snapshot of both document + viewer state.
     * @property cursorRow Row of the caret, in absolute document coordinates.
     * @property cursorCol Column of the caret on [cursorRow].
     * @property anchorRow If non-null, together with [anchorCol] defines the
     *   other end of the active selection. `null` means "no selection — the
     *   caret is just a point".
     * @property anchorCol Column companion to [anchorRow].
     * @property zoomedLineId If non-null, the [LineId] of the bullet whose
     *   subtree is being shown. `null` means "at root — show everything".
     *   Stored as an id (not a row) so the reference survives edits that
     *   shift rows above the zoom target.
     */
    data class State(
        val documentState: DocumentBackingViewModel.State? = null,
        val cursorRow: Int = 0,
        val cursorCol: Int = 0,
        val anchorRow: Int? = null,
        val anchorCol: Int? = null,
        val zoomedLineId: LineId? = null
    ) {
        /** `true` once the document has loaded from disk at least once. */
        val isLoaded: Boolean get() = documentState?.isLoaded == true

        /** Convenience accessor — never null, falls back to a single empty line. */
        val lines: List<String> get() = documentState?.lines ?: listOf("")
    }

    /**
     * A normalized selection range (start ≤ end, by row-then-column), or
     * absent when the caret is not extending any selection. Produced by
     * [selectionOf] from the raw anchor/cursor pair.
     */
    data class Selection(val startRow: Int, val startCol: Int, val endRow: Int, val endCol: Int)

    /**
     * Resolved zoom geometry. Computed on demand by [zoomInfoOf] from the
     * current zoom id and document state — never stored, so it cannot go
     * stale.
     *
     * @property zoomRow The absolute row of the zoom target bullet itself.
     * @property zoomIndent The indent (leading-space count) of the zoom
     *   target line. Descendant lines are those with indent strictly greater
     *   than this.
     * @property startRow First visible row (`zoomRow + 1`).
     * @property endRowInclusive Last visible row. If `endRowInclusive < startRow`
     *   the subtree is currently empty.
     * @property titleText The display text of the zoom target with the
     *   leading indent and `"* "` marker stripped. Used by the view to
     *   render the sticky header.
     */
    data class ZoomInfo(
        val zoomRow: Int,
        val zoomIndent: Int,
        val startRow: Int,
        val endRowInclusive: Int,
        val titleText: String
    ) {
        /** `true` when the zoom target has at least one descendant bullet. */
        val hasVisibleRows: Boolean get() = startRow <= endRowInclusive
    }

    private val _stateFlow = MutableStateFlow(State())

    /**
     * Observable stream of view states. Platform `MainViewModel`s collect
     * this and re-emit through their own envelope.
     */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    init {
        scope.launch {
            documentBackingViewModel.stateFlow.collect { docState ->
                _stateFlow.value = reconcile(_stateFlow.value.copy(documentState = docState))
            }
        }
    }

    // ------------------------------------------------------------------ edits

    /**
     * Types a single character at the caret, replacing any active selection.
     *
     * Called by platform views on every keypress that produces a printable
     * character (the `event.key.length == 1` branch in `MainScreen`).
     *
     * @param char The character to insert.
     */
    fun insertChar(char: Char) {
        if (!_stateFlow.value.isLoaded) return
        deleteSelectionIfAny()
        val (row, col) = cursor()
        val result = documentBackingViewModel.insertText(row, col, char.toString())
        patch { it.copy(cursorRow = result.endRow, cursorCol = result.endCol, anchorRow = null, anchorCol = null) }
    }

    /**
     * Handles the Enter key. Replaces any active selection, then splits the
     * current line. If the current line is a bullet (`"* "` prefix) and the
     * caret is past the marker, the new line inherits the same indent and
     * marker, matching outliner expectations.
     *
     * Called by platform views from the Enter key handler.
     */
    fun insertNewline() {
        if (!_stateFlow.value.isLoaded) return
        deleteSelectionIfAny()
        val state = _stateFlow.value
        val line = state.lines[state.cursorRow]
        val bulletPrefix = continuationBulletPrefix(line, state.cursorCol)
        val result = documentBackingViewModel.insertText(state.cursorRow, state.cursorCol, "\n" + bulletPrefix)
        patch { it.copy(cursorRow = result.endRow, cursorCol = result.endCol, anchorRow = null, anchorCol = null) }
    }

    /**
     * Inserts arbitrary text at the caret, replacing any active selection.
     * Used by the clipboard paste path and by any future import flow.
     *
     * @param text Text to insert. May contain newlines.
     */
    fun insertText(text: String) {
        if (!_stateFlow.value.isLoaded) return
        deleteSelectionIfAny()
        val (row, col) = cursor()
        val result = documentBackingViewModel.insertText(row, col, text)
        patch { it.copy(cursorRow = result.endRow, cursorCol = result.endCol, anchorRow = null, anchorCol = null) }
    }

    /**
     * Handles the Backspace key with three behaviors, in priority order:
     *
     * 1. If there is an active selection, delete it.
     * 2. If the caret is mid-line, delete either one character, a bullet
     *    marker (`"* "` ⇒ 2 chars), or one indent step of leading spaces,
     *    whichever applies.
     * 3. If the caret is at column 0, merge the current line onto the end
     *    of the previous line — *unless* the viewer is zoomed and the caret
     *    is at the first visible row, in which case the operation is a
     *    no-op to avoid merging into content outside the zoom subtree.
     *
     * Called by platform views from the Backspace key handler.
     */
    fun backspace() {
        if (!_stateFlow.value.isLoaded) return
        if (deleteSelectionIfAny()) return
        val state = _stateFlow.value
        when {
            state.cursorCol > 0 -> {
                val line = state.lines[state.cursorRow]
                val leadingSpaces = line.takeWhile { it == ' ' }.length
                val removed = when {
                    isAtBulletMarkerEnd(line, state.cursorCol) -> 2
                    state.cursorCol <= leadingSpaces && state.cursorCol % TAB_SIZE == 0 -> TAB_SIZE
                    else -> 1
                }
                val newCol = state.cursorCol - removed
                documentBackingViewModel.delete(state.cursorRow, newCol, state.cursorRow, state.cursorCol)
                patch { it.copy(cursorCol = newCol) }
            }
            state.cursorRow > 0 -> {
                val zoom = zoomInfoOf(state)
                if (zoom != null && state.cursorRow <= zoom.startRow) {
                    // Don't merge the first child up into the hidden zoom target.
                    return
                }
                val previousLen = state.lines[state.cursorRow - 1].length
                documentBackingViewModel.delete(state.cursorRow - 1, previousLen, state.cursorRow, 0)
                patch { it.copy(cursorRow = state.cursorRow - 1, cursorCol = previousLen) }
            }
        }
    }

    /**
     * Indents the current line by [amount] spaces, unless doing so would
     * make it nest deeper than one step beyond its predecessor (the
     * outliner rule — you can't skip levels).
     *
     * Called by platform views from the Tab key handler when the current
     * line is a bullet.
     *
     * @param amount Number of spaces to insert at the start of the line.
     *   Defaults to [TAB_SIZE].
     */
    fun indentLine(amount: Int = TAB_SIZE) {
        val state = _stateFlow.value
        if (!state.isLoaded) return
        val line = state.lines[state.cursorRow]
        val currentIndent = line.takeWhile { it == ' ' }.length
        if (state.cursorRow > 0) {
            val prev = state.lines[state.cursorRow - 1]
            if (DocumentLayout.bulletAsteriskColumn(prev) >= 0) {
                val prevIndent = prev.takeWhile { it == ' ' }.length
                if (currentIndent >= prevIndent + amount) return
            }
        }
        documentBackingViewModel.insertText(state.cursorRow, 0, " ".repeat(amount))
        patch { it.copy(cursorCol = state.cursorCol + amount, anchorRow = null, anchorCol = null) }
    }

    /**
     * Outdents the current line by up to [amount] spaces. When the viewer
     * is zoomed, the minimum allowed indent is clamped to `zoomIndent + 2`
     * so an outdent cannot push the line out of the zoom subtree.
     *
     * Called by platform views from Shift-Tab.
     *
     * @param amount Maximum number of leading spaces to remove. Defaults
     *   to [TAB_SIZE].
     */
    fun outdentLine(amount: Int = TAB_SIZE) {
        val state = _stateFlow.value
        if (!state.isLoaded) return
        val line = state.lines[state.cursorRow]
        val leading = line.takeWhile { it == ' ' }.length
        val zoom = zoomInfoOf(state)
        val minAllowed = if (zoom != null) zoom.zoomIndent + TAB_SIZE else 0
        val remove = minOf(amount, leading - minAllowed).coerceAtLeast(0)
        if (remove == 0) return
        documentBackingViewModel.delete(state.cursorRow, 0, state.cursorRow, remove)
        patch {
            it.copy(
                cursorCol = (state.cursorCol - remove).coerceAtLeast(0),
                anchorRow = null, anchorCol = null
            )
        }
    }

    /**
     * Reports whether the cursor is currently on a bullet line. Used by
     * platform views to decide whether Tab should indent the line or insert
     * whitespace.
     *
     * @return `true` iff the current line matches the bullet pattern.
     */
    fun isBulletLine(): Boolean {
        val state = _stateFlow.value
        if (!state.isLoaded) return false
        return DocumentLayout.bulletAsteriskColumn(state.lines[state.cursorRow]) >= 0
    }

    // ------------------------------------------------------------------ zoom

    /**
     * Zooms the current viewer into the bullet at [row], making it the new
     * logical root. The sticky header will start showing its text, and the
     * editor will display only its descendant bullets.
     *
     * If the target bullet currently has no descendants, an empty child
     * bullet is inserted immediately after it so the zoomed view has
     * something to edit, and the cursor is placed inside that new child.
     *
     * Called by the platform view when the user clicks a bullet marker.
     *
     * @param row Absolute row of the bullet to zoom into. Must be a bullet
     *   line; non-bullet rows are ignored.
     */
    fun zoomInto(row: Int) {
        val state = _stateFlow.value
        if (!state.isLoaded) return
        val docState = state.documentState ?: return
        if (row !in docState.lines.indices) return
        val line = docState.lines[row]
        val indent = DocumentLayout.bulletAsteriskColumn(line)
        if (indent < 0) return
        val id = docState.lineIds[row]
        val endInclusive = DocumentLayout.subtreeEnd(docState.lines, row, indent)
        if (endInclusive < row + 1) {
            val childIndent = indent + TAB_SIZE
            val childPrefix = " ".repeat(childIndent) + "* "
            documentBackingViewModel.insertText(row, docState.lines[row].length, "\n" + childPrefix)
            val newChildRow = row + 1
            patch {
                it.copy(
                    zoomedLineId = id,
                    cursorRow = newChildRow,
                    cursorCol = childPrefix.length,
                    anchorRow = null,
                    anchorCol = null
                )
            }
        } else {
            val firstChildRow = row + 1
            val firstChildLine = docState.lines[firstChildRow]
            val bulletCol = DocumentLayout.bulletAsteriskColumn(firstChildLine)
            val targetCol = if (bulletCol >= 0) bulletCol + 2 else 0
            patch {
                it.copy(
                    zoomedLineId = id,
                    cursorRow = firstChildRow,
                    cursorCol = targetCol,
                    anchorRow = null,
                    anchorCol = null
                )
            }
        }
    }

    /**
     * Returns the viewer to the document root. The header will show "Root"
     * and the editor will show all lines again.
     *
     * Called by the platform view when the user clicks the header while
     * zoomed.
     */
    fun zoomOut() {
        if (!_stateFlow.value.isLoaded) return
        patch { it.copy(zoomedLineId = null, anchorRow = null, anchorCol = null) }
    }

    /**
     * Resolves the current zoom state into concrete row geometry.
     *
     * @param state The view state to inspect; defaults to the current value.
     * @return A [ZoomInfo] describing the visible range and header text, or
     *   `null` when the viewer is at root (or the previous zoom target has
     *   since disappeared).
     */
    fun zoomInfo(state: State = _stateFlow.value): ZoomInfo? = zoomInfoOf(state)

    // ------------------------------------------------------------------ movement

    /**
     * Moves the caret one character left, wrapping to the previous line's
     * end when at column 0. When [extend] is true the selection anchor is
     * kept (or established) so the caret move grows a selection instead of
     * clearing it. When [extend] is false and a selection is active, the
     * caret collapses to the selection's start without moving further.
     *
     * @param extend `true` when Shift is held; grow the selection.
     */
    fun moveLeft(extend: Boolean = false) = mutate { state ->
        val sel = selectionOf(state)
        if (!extend && sel != null) {
            return@mutate state.copy(
                cursorRow = sel.startRow, cursorCol = sel.startCol,
                anchorRow = null, anchorCol = null
            )
        }
        val (r, c) = when {
            state.cursorCol > 0 -> state.cursorRow to (state.cursorCol - 1)
            state.cursorRow > 0 -> (state.cursorRow - 1) to state.lines[state.cursorRow - 1].length
            else -> state.cursorRow to state.cursorCol
        }
        moved(state, r, c, extend)
    }

    /**
     * Moves the caret one character right, wrapping to the next line's
     * start at line end. Selection semantics mirror [moveLeft] (the
     * collapse direction flips to the selection end).
     *
     * @param extend `true` when Shift is held; grow the selection.
     */
    fun moveRight(extend: Boolean = false) = mutate { state ->
        val sel = selectionOf(state)
        if (!extend && sel != null) {
            return@mutate state.copy(
                cursorRow = sel.endRow, cursorCol = sel.endCol,
                anchorRow = null, anchorCol = null
            )
        }
        val line = state.lines[state.cursorRow]
        val (r, c) = when {
            state.cursorCol < line.length -> state.cursorRow to (state.cursorCol + 1)
            state.cursorRow < state.lines.lastIndex -> (state.cursorRow + 1) to 0
            else -> state.cursorRow to state.cursorCol
        }
        moved(state, r, c, extend)
    }

    /**
     * Moves the caret one row up, clamping the column to the target line's
     * length. At the top row, clears the selection (if any) without moving.
     *
     * @param extend `true` when Shift is held; grow the selection.
     */
    fun moveUp(extend: Boolean = false) = mutate { state ->
        if (state.cursorRow == 0) {
            if (extend) state else state.copy(anchorRow = null, anchorCol = null)
        } else {
            val targetRow = state.cursorRow - 1
            val targetCol = state.cursorCol.coerceAtMost(state.lines[targetRow].length)
            moved(state, targetRow, targetCol, extend)
        }
    }

    /**
     * Moves the caret one row down, clamping the column. At the bottom row,
     * clears the selection (if any) without moving.
     *
     * @param extend `true` when Shift is held; grow the selection.
     */
    fun moveDown(extend: Boolean = false) = mutate { state ->
        if (state.cursorRow >= state.lines.lastIndex) {
            if (extend) state else state.copy(anchorRow = null, anchorCol = null)
        } else {
            val targetRow = state.cursorRow + 1
            val targetCol = state.cursorCol.coerceAtMost(state.lines[targetRow].length)
            moved(state, targetRow, targetCol, extend)
        }
    }

    /**
     * Places the caret at ([row], [col]), clamped into the document. When
     * zoomed, [reconcile] further clamps the result into the visible
     * subtree. Called by the platform view for mouse clicks and drags.
     *
     * @param row Absolute row to move to (platform clicks convert visible
     *   rows to absolute rows before calling this).
     * @param col Column to move to on [row].
     * @param extend `true` when the click is shift-extended.
     */
    fun moveTo(row: Int, col: Int, extend: Boolean = false) = mutate { state ->
        val clampedRow = row.coerceIn(0, state.lines.lastIndex)
        val clampedCol = col.coerceIn(0, state.lines[clampedRow].length)
        moved(state, clampedRow, clampedCol, extend)
    }

    /** Moves the caret to column 0 of the current row. */
    fun moveLineStart(extend: Boolean = false) = mutate { moved(it, it.cursorRow, 0, extend) }

    /** Moves the caret past the last character of the current row. */
    fun moveLineEnd(extend: Boolean = false) = mutate {
        moved(it, it.cursorRow, it.lines[it.cursorRow].length, extend)
    }

    /** Moves the caret to the document start; [reconcile] clamps to the zoom range when zoomed. */
    fun moveDocStart(extend: Boolean = false) = mutate { moved(it, 0, 0, extend) }

    /** Moves the caret to the document end; [reconcile] clamps to the zoom range when zoomed. */
    fun moveDocEnd(extend: Boolean = false) = mutate {
        val lastRow = it.lines.lastIndex
        moved(it, lastRow, it.lines[lastRow].length, extend)
    }

    /**
     * Moves the caret to the start of the previous word. Skips over
     * non-word characters first, then walks through the run of word
     * characters. Wraps to the previous line when already at column 0.
     */
    fun moveWordLeft(extend: Boolean = false) = mutate { state ->
        var row = state.cursorRow
        var col = state.cursorCol
        if (col == 0 && row > 0) {
            row--; col = state.lines[row].length
        } else {
            val line = state.lines[row]
            while (col > 0 && !isWordChar(line[col - 1])) col--
            while (col > 0 && isWordChar(line[col - 1])) col--
        }
        moved(state, row, col, extend)
    }

    /**
     * Moves the caret to the end of the next word. Mirrors [moveWordLeft]
     * in the forward direction.
     */
    fun moveWordRight(extend: Boolean = false) = mutate { state ->
        var row = state.cursorRow
        var col = state.cursorCol
        val line = state.lines[row]
        if (col == line.length && row < state.lines.lastIndex) {
            row++; col = 0
        } else {
            while (col < line.length && !isWordChar(line[col])) col++
            while (col < line.length && isWordChar(line[col])) col++
        }
        moved(state, row, col, extend)
    }

    // ------------------------------------------------------------------ selection

    /**
     * Selects everything in the currently-visible range — the whole
     * document at root, or the zoom subtree when zoomed.
     *
     * Called from Cmd/Ctrl-A in the platform view.
     */
    fun selectAll() = mutate { state ->
        val zoom = zoomInfoOf(state)
        val startRow = zoom?.startRow ?: 0
        val endRow = zoom?.endRowInclusive ?: state.lines.lastIndex
        if (endRow < startRow) return@mutate state
        state.copy(
            anchorRow = startRow, anchorCol = 0,
            cursorRow = endRow, cursorCol = state.lines[endRow].length
        )
    }

    /**
     * Selects the word (or run of non-word characters) at ([row], [col]).
     * Called on double-click.
     */
    fun selectWord(row: Int, col: Int) = mutate { state ->
        val clampedRow = row.coerceIn(0, state.lines.lastIndex)
        val line = state.lines[clampedRow]
        if (line.isEmpty()) {
            state.copy(cursorRow = clampedRow, cursorCol = 0, anchorRow = clampedRow, anchorCol = 0)
        } else {
            val clampedCol = col.coerceIn(0, line.length - 1)
            val isWord = isWordChar(line[clampedCol])
            var start = clampedCol
            var end = clampedCol
            while (start > 0 && isWordChar(line[start - 1]) == isWord) start--
            while (end < line.length && isWordChar(line[end]) == isWord) end++
            state.copy(anchorRow = clampedRow, anchorCol = start, cursorRow = clampedRow, cursorCol = end)
        }
    }

    /**
     * Selects the entire [row]. Called on triple-click.
     */
    fun selectLine(row: Int) = mutate { state ->
        val clampedRow = row.coerceIn(0, state.lines.lastIndex)
        state.copy(
            anchorRow = clampedRow, anchorCol = 0,
            cursorRow = clampedRow, cursorCol = state.lines[clampedRow].length
        )
    }

    /** Clears the selection without moving the caret. */
    fun clearSelection() = mutate { it.copy(anchorRow = null, anchorCol = null) }

    /**
     * Deletes the active selection, if any.
     *
     * @return `true` when something was deleted, `false` when there was no
     *   selection (in which case the caller typically proceeds with the
     *   non-selection branch of their handler — e.g. [backspace] falls
     *   through to single-character deletion).
     */
    fun deleteSelectionIfAny(): Boolean {
        val state = _stateFlow.value
        val sel = selectionOf(state) ?: run {
            if (state.anchorRow != null) {
                patch { it.copy(anchorRow = null, anchorCol = null) }
            }
            return false
        }
        documentBackingViewModel.delete(sel.startRow, sel.startCol, sel.endRow, sel.endCol)
        patch {
            it.copy(
                cursorRow = sel.startRow, cursorCol = sel.startCol,
                anchorRow = null, anchorCol = null
            )
        }
        return true
    }

    /**
     * Returns the currently selected text, or `null` if nothing is selected.
     * Called by copy/cut handlers in the platform view.
     */
    fun getSelectedText(): String? {
        val state = _stateFlow.value
        if (!state.isLoaded) return null
        val sel = selectionOf(state) ?: return null
        val lines = state.lines
        return if (sel.startRow == sel.endRow) {
            lines[sel.startRow].substring(sel.startCol, sel.endCol)
        } else {
            buildString {
                append(lines[sel.startRow].substring(sel.startCol))
                append('\n')
                for (i in sel.startRow + 1 until sel.endRow) {
                    append(lines[i])
                    append('\n')
                }
                append(lines[sel.endRow].substring(0, sel.endCol))
            }
        }
    }

    /**
     * Cut semantics: returns the selected text and deletes it in the same
     * step. Called by the Cmd/Ctrl-X handler.
     *
     * @return The text that was cut, or `null` when there was no selection.
     */
    fun onCutRequested(): String? {
        val text = getSelectedText() ?: return null
        deleteSelectionIfAny()
        return text
    }

    // ------------------------------------------------------------------ helpers

    /** Pair helper for current caret position. */
    private fun cursor(): Pair<Int, Int> {
        val s = _stateFlow.value
        return s.cursorRow to s.cursorCol
    }

    /**
     * Applies [transform] to the current state, then re-runs [reconcile]
     * (cursor clamping, zoom validity) and publishes. Used by every movement
     * and selection intent that does not touch document content.
     */
    private inline fun mutate(transform: (State) -> State) {
        val current = _stateFlow.value
        if (!current.isLoaded) return
        _stateFlow.value = reconcile(transform(current))
    }

    /**
     * Patches the view state while ensuring [State.documentState] is
     * refreshed to the document VM's latest. Used right after calling a
     * document VM mutation so readers never see a view state whose cursor
     * references stale document content.
     */
    private inline fun patch(transform: (State) -> State) {
        val current = _stateFlow.value
        val patched = transform(current).copy(
            documentState = documentBackingViewModel.stateFlow.value
        )
        _stateFlow.value = reconcile(patched)
    }

    /**
     * Post-mutation normalization: clamps caret/anchor into the current
     * document, self-heals zoom state when the target disappears or its
     * subtree becomes empty, and clamps caret/anchor into the zoom range
     * when zoomed.
     *
     * Called from every publish path ([init]'s collector, [mutate],
     * [patch]).
     *
     * @param state The candidate state produced by an intent.
     * @return A normalized state safe to publish.
     */
    private fun reconcile(state: State): State {
        val docState = state.documentState
        if (docState?.isLoaded != true) return state
        val lines = state.lines
        val lastRow = lines.lastIndex
        val baseRow = state.cursorRow.coerceIn(0, lastRow)
        val baseCol = state.cursorCol.coerceIn(0, lines[baseRow].length)
        val baseAr = state.anchorRow?.coerceIn(0, lastRow)
        val baseAc = if (baseAr != null) state.anchorCol?.coerceIn(0, lines[baseAr].length) else null
        val clamped = state.copy(
            cursorRow = baseRow, cursorCol = baseCol,
            anchorRow = baseAr, anchorCol = baseAc
        )
        if (clamped.zoomedLineId == null) return clamped

        val zoom = zoomInfoOf(clamped)
        if (zoom == null || !zoom.hasVisibleRows) {
            // Target gone (deleted / no longer a bullet) or subtree empty —
            // drop the zoom rather than leave the viewer stuck.
            return clamped.copy(zoomedLineId = null)
        }
        val zRow = clamped.cursorRow.coerceIn(zoom.startRow, zoom.endRowInclusive)
        val zCol = clamped.cursorCol.coerceIn(0, lines[zRow].length)
        val zAr = clamped.anchorRow?.coerceIn(zoom.startRow, zoom.endRowInclusive)
        val zAc = if (zAr != null) clamped.anchorCol?.coerceIn(0, lines[zAr].length) else null
        return clamped.copy(cursorRow = zRow, cursorCol = zCol, anchorRow = zAr, anchorCol = zAc)
    }

    /**
     * Produces a new state with the caret at ([newRow], [newCol]) and
     * either grows the selection (when [extend] is `true`) or clears it.
     */
    private fun moved(state: State, newRow: Int, newCol: Int, extend: Boolean): State {
        return if (extend) {
            val ar = state.anchorRow ?: state.cursorRow
            val ac = state.anchorCol ?: state.cursorCol
            state.copy(cursorRow = newRow, cursorCol = newCol, anchorRow = ar, anchorCol = ac)
        } else {
            state.copy(cursorRow = newRow, cursorCol = newCol, anchorRow = null, anchorCol = null)
        }
    }

    /**
     * Computes the prefix (indent + `"* "`) that a newline should inherit
     * from [line] when Enter is pressed at [cursorCol]. Returns an empty
     * string for non-bullet lines or when the caret is still at/before the
     * bullet marker (so pressing Enter at the very start of a bullet
     * produces a blank line, matching most editors).
     */
    private fun continuationBulletPrefix(line: String, cursorCol: Int): String {
        val indent = line.indexOfFirst { !it.isWhitespace() }
        if (indent < 0) return ""
        if (indent + 1 >= line.length) return ""
        if (line[indent] != '*' || line[indent + 1] != ' ') return ""
        if (cursorCol <= indent + 1) return ""
        return line.substring(0, indent) + "* "
    }

    /**
     * Detects the special case where the caret sits immediately after the
     * `"* "` of a bullet marker and there's nothing else on the line's
     * leading whitespace. Used by [backspace] so hitting Backspace on an
     * empty bullet removes the marker as one "character" rather than two.
     */
    private fun isAtBulletMarkerEnd(line: String, cursorCol: Int): Boolean {
        if (cursorCol < 2) return false
        if (line[cursorCol - 1] != ' ' || line[cursorCol - 2] != '*') return false
        for (i in 0 until cursorCol - 2) {
            if (line[i] != ' ') return false
        }
        return true
    }

    /** Word-character predicate used by word movement and [selectWord]. */
    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /**
     * Resolves [State.zoomedLineId] to concrete row geometry for [state].
     * Returns `null` if there is no zoom, the id is no longer in the
     * document, or the line it points to is no longer a bullet.
     */
    private fun zoomInfoOf(state: State): ZoomInfo? {
        val id = state.zoomedLineId ?: return null
        val docState = state.documentState ?: return null
        val row = docState.lineIds.indexOf(id)
        if (row < 0) return null
        val lines = docState.lines
        if (row !in lines.indices) return null
        val indent = DocumentLayout.bulletAsteriskColumn(lines[row])
        if (indent < 0) return null
        val end = DocumentLayout.subtreeEnd(lines, row, indent)
        val titleText = lines[row].substring(minOf(indent + 2, lines[row].length))
        return ZoomInfo(
            zoomRow = row,
            zoomIndent = indent,
            startRow = row + 1,
            endRowInclusive = end,
            titleText = titleText
        )
    }

    companion object {
        /** Standard indent step across the app (two spaces). */
        const val TAB_SIZE: Int = 2

        /**
         * Normalizes an anchor + cursor pair into a [Selection]. Returns
         * `null` when there is no anchor (caret only) or when the anchor
         * and caret coincide (empty selection). Used by the view layer so
         * it doesn't re-implement the "which end comes first" logic.
         */
        fun selectionOf(state: State): Selection? {
            val ar = state.anchorRow ?: return null
            val ac = state.anchorCol ?: return null
            if (ar == state.cursorRow && ac == state.cursorCol) return null
            return if (ar < state.cursorRow || (ar == state.cursorRow && ac <= state.cursorCol))
                Selection(ar, ac, state.cursorRow, state.cursorCol)
            else
                Selection(state.cursorRow, state.cursorCol, ar, ac)
        }
    }
}
