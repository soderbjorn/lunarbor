/*
 * TextEditingViewModel.kt
 * -----------------------
 * Owns text-editing intents (typing, deletion, indent/outdent, paste,
 * cursor and selection movement, copy/cut text extraction). Operates on
 * the shared `DocumentViewBackingViewModel.State` through the small set
 * of mutators ([apply], [patch], [mutate]) supplied by the aggregate
 * `DocumentViewBackingViewModel`. Reads the canonical document via the
 * injected `DocumentBackingViewModel`.
 *
 * commonMain only — no DOM, Android UI, or UIKit imports. The class holds
 * no state of its own; cursor and selection live in the aggregate's
 * single `MutableStateFlow`.
 */

package se.soderbjorn.notegrow.main

import se.soderbjorn.notegrow.data.InlineMarkdownTokenizer
import se.soderbjorn.notegrow.data.InlineStyle
import se.soderbjorn.notegrow.data.LineMarkdownPrefix
import se.soderbjorn.notegrow.main.DocumentViewBackingViewModel.Companion.TAB_SIZE

/**
 * Text-editing slice of the per-viewer ViewModel. Composed by
 * `DocumentViewBackingViewModel` which owns the state flow; this class
 * implements the actual editing behavior.
 *
 * @param documentBackingViewModel Shared document VM this slice writes to.
 * @param stateProvider Reads the latest aggregate state.
 * @param applyState Replaces the aggregate state without re-running the
 *   reconcile pass (used by the few intents that need a tight write).
 * @param mutate Applies a transform to the current state and reconciles.
 * @param patch Applies a transform that touches document content; refreshes
 *   the mirrored `documentState` and reconciles.
 */
internal class TextEditingViewModel(
    private val documentBackingViewModel: DocumentBackingViewModel,
    private val stateProvider: () -> DocumentViewBackingViewModel.State,
    @Suppress("unused") private val applyState: (DocumentViewBackingViewModel.State) -> Unit,
    private val mutate: ((DocumentViewBackingViewModel.State) -> DocumentViewBackingViewModel.State) -> Unit,
    private val patch: ((DocumentViewBackingViewModel.State) -> DocumentViewBackingViewModel.State) -> Unit,
) {
    private val state: DocumentViewBackingViewModel.State
        get() = stateProvider()

    // ------------------------------------------------------------------ edits

    fun insertChar(char: Char) {
        if (!state.isLoaded) return
        deleteSelectionIfAny()
        insertWithPendingStyles(char.toString())
    }

    fun insertNewline() {
        if (!state.isLoaded) return
        deleteSelectionIfAny()

        // Three cases for inline-style preservation across the line break:
        //
        //   (1) Caret strictly inside an existing tokenized span — e.g.
        //       user clicked between `f|oo` of saved `**foo**bar**`. The
        //       split would orphan the original closers on row N+1; fix by
        //       sealing the span on row N (insert closers at caret) and
        //       balancing row N+1 with a fresh opener at its start.
        //   (2) Pending styles armed (Cmd-B then typing or empty caret).
        //       Carry the armed set across the break and let the user's
        //       next keystroke wrap via [insertWithPendingStyles] — no
        //       markers inserted on row N+1 (avoids stray empty `****`
        //       spans).
        //   (3) No styles. Plain newline.
        val s0 = state
        val tokenizedAtCaret = tokenizedStylesAtCaret(s0)

        if (tokenizedAtCaret.isNotEmpty()) {
            insertNewlineSplittingSpan(s0, tokenizedAtCaret)
            return
        }
        if (s0.pendingInlineStyles.isNotEmpty()) {
            insertNewlineCarryingPending(s0, s0.pendingInlineStyles)
            return
        }
        insertNewlinePlain(s0)
    }

    /**
     * Case (1): caret strictly inside a saved styled span. Close the span
     * on row N by inserting the closer markers at the caret, insert the
     * newline + bullet prefix, then insert the opener markers at the start
     * of row N+1's inline content so the original closers (now dangling on
     * row N+1) are re-balanced by a fresh opener. `pendingInlineStyles`
     * stays empty — the on-line markers handle styling, no need to arm.
     */
    private fun insertNewlineSplittingSpan(s0: DocumentViewBackingViewModel.State, styles: Set<InlineStyle>) {
        val ordered = InlineStyle.entries.filter { it in styles }
        val openers = ordered.joinToString("") { it.openMarker }
        val closers = ordered.reversed().joinToString("") { it.closeMarker }

        documentBackingViewModel.insertText(s0.cursorRow, s0.cursorCol, closers)
        patch { it.copy(cursorCol = it.cursorCol + closers.length) }

        val s1 = state
        val line1 = s1.lines[s1.cursorRow]
        val bulletPrefix = continuationBulletPrefix(line1, s1.cursorCol)
        val nlResult = documentBackingViewModel.insertText(
            s1.cursorRow, s1.cursorCol, "\n" + bulletPrefix
        )
        val openResult = documentBackingViewModel.insertText(
            nlResult.endRow, nlResult.endCol, openers
        )
        patch {
            it.copy(
                cursorRow = openResult.endRow, cursorCol = openResult.endCol,
                anchorRow = null, anchorCol = null,
                pendingInlineStyles = emptySet(),
            )
        }
    }

    /**
     * Case (2): pending inline styles are armed. Two sub-cases by whether
     * close markers already sit at the caret:
     *   - "armed-typing" (`**foo|**`): the user typed inside the armed pair
     *     so `**` follows the caret. Step over the closers so they stay on
     *     row N attached to `**foo`.
     *   - "armed-empty" or "armed-mid-text": no markers near the caret yet
     *     (Cmd-B with empty selection, or armed with no content typed). No
     *     closers to step over.
     * In both sub-cases, insert the newline + bullet prefix and arm
     * `pendingInlineStyles = pending`. We do NOT insert opener/closer
     * markers on row N+1 — the next keystroke is wrapped by
     * [insertWithPendingStyles], which keeps row N+1 free of stray empty
     * spans.
     */
    private fun insertNewlineCarryingPending(s0: DocumentViewBackingViewModel.State, pending: Set<InlineStyle>) {
        val ordered = InlineStyle.entries.filter { it in pending }
        val closers = ordered.reversed().joinToString("") { it.closeMarker }
        val line0 = s0.lines[s0.cursorRow]
        val closersAlreadyAtCaret = closers.isNotEmpty() &&
            s0.cursorCol + closers.length <= line0.length &&
            line0.regionMatches(s0.cursorCol, closers, 0, closers.length)
        if (closersAlreadyAtCaret) {
            patch { it.copy(cursorCol = it.cursorCol + closers.length) }
        }

        val s1 = state
        val line1 = s1.lines[s1.cursorRow]
        val bulletPrefix = continuationBulletPrefix(line1, s1.cursorCol)
        val nlResult = documentBackingViewModel.insertText(
            s1.cursorRow, s1.cursorCol, "\n" + bulletPrefix
        )
        patch {
            it.copy(
                cursorRow = nlResult.endRow, cursorCol = nlResult.endCol,
                anchorRow = null, anchorCol = null,
                pendingInlineStyles = pending,
            )
        }
    }

    /** Case (3): no inline styles at the caret. Plain newline + bullet continuation. */
    private fun insertNewlinePlain(s0: DocumentViewBackingViewModel.State) {
        val line = s0.lines[s0.cursorRow]
        val bulletPrefix = continuationBulletPrefix(line, s0.cursorCol)
        val result = documentBackingViewModel.insertText(s0.cursorRow, s0.cursorCol, "\n" + bulletPrefix)
        patch {
            it.copy(
                cursorRow = result.endRow, cursorCol = result.endCol,
                anchorRow = null, anchorCol = null,
                pendingInlineStyles = emptySet(),
            )
        }
    }

    /**
     * Inline styles of the tokenized run that strictly encloses the caret
     * on its current row. Boundary positions (caret on a marker edge or
     * between two runs) report empty — see
     * [se.soderbjorn.notegrow.data.TokenizedLine.stylesAt].
     */
    private fun tokenizedStylesAtCaret(s: DocumentViewBackingViewModel.State): Set<InlineStyle> {
        val line = s.lines[s.cursorRow]
        val tStart = DocumentLayout.textStartCol(line)
        val linePrefix = LineMarkdownPrefix.detect(line, tStart)
        val inlineStart = linePrefix.markerEnd
        val inlineText = if (inlineStart >= line.length) "" else line.substring(inlineStart)
        val tokenized = InlineMarkdownTokenizer.tokenize(inlineText)
        val col = (s.cursorCol - inlineStart).coerceAtLeast(0)
        return tokenized.stylesAt(col)
    }

    fun insertText(text: String) {
        if (!state.isLoaded) return
        deleteSelectionIfAny()
        insertWithPendingStyles(text)
    }

    /**
     * Inserts [text] at the caret, honoring any [DocumentViewBackingViewModel.State.pendingInlineStyles]
     * by wrapping the inserted text with the matching markers. Keeps the
     * pending set armed across consecutive insertions so continuous typing
     * extends the styled span — the second character lands inside the
     * already-open markers (the caret sits between text and closer) so we
     * just insert plainly and the existing closers shift right.
     *
     * The pending set is cleared on cursor movement, on selection
     * changes, and on Enter — see [moved], [setSelection], and
     * [insertNewline].
     */
    private fun insertWithPendingStyles(text: String) {
        val s = state
        val pending = s.pendingInlineStyles
        if (pending.isEmpty()) {
            val result = documentBackingViewModel.insertText(s.cursorRow, s.cursorCol, text)
            patch { it.copy(cursorRow = result.endRow, cursorCol = result.endCol, anchorRow = null, anchorCol = null) }
            return
        }
        val ordered = se.soderbjorn.notegrow.data.InlineStyle.entries.filter { it in pending }
        val closers = ordered.reversed().joinToString("") { it.closeMarker }
        val line = s.lines[s.cursorRow]
        val alreadyInsideOpenSpan = s.cursorCol + closers.length <= line.length &&
            line.regionMatches(s.cursorCol, closers, 0, closers.length)
        if (alreadyInsideOpenSpan) {
            // Continuing to type inside the markers we just opened: the
            // closers already sit at the caret, so a plain insert grows
            // the styled span and the close markers shift right naturally.
            val result = documentBackingViewModel.insertText(s.cursorRow, s.cursorCol, text)
            patch {
                it.copy(
                    cursorRow = result.endRow, cursorCol = result.endCol,
                    anchorRow = null, anchorCol = null,
                    // Keep the pending set armed so the *next* keystroke
                    // also extends instead of opening a fresh pair.
                )
            }
            return
        }
        // Fresh start: wrap the inserted text with the markers. Outer-most
        // marker = first entry in InlineStyle.entries.
        val openers = ordered.joinToString("") { it.openMarker }
        val wrapped = openers + text + closers
        val result = documentBackingViewModel.insertText(s.cursorRow, s.cursorCol, wrapped)
        patch {
            it.copy(
                cursorRow = result.endRow,
                cursorCol = result.endCol - closers.length,
                anchorRow = null, anchorCol = null,
            )
        }
    }

    fun backspace() {
        if (!state.isLoaded) return
        if (deleteSelectionIfAny()) return
        val s = state
        when {
            s.cursorCol > 0 -> {
                val line = s.lines[s.cursorRow]
                val leadingSpaces = line.takeWhile { it == ' ' }.length
                val textStart = DocumentLayout.textStartCol(line)
                val caretStart = DocumentLayout.caretStartCol(line)
                if (s.cursorCol == caretStart && caretStart > textStart) {
                    // Caret sits just after a hidden line-level markdown prefix
                    // (`# `, `## `, `### `, `> `). The prefix is invisible to
                    // the user, so deleting one character would silently strip
                    // the trailing space and surface the marker — surprising the
                    // user. Remove the entire prefix in a single keystroke so
                    // the line "demotes" cleanly back to plain text.
                    documentBackingViewModel.delete(s.cursorRow, textStart, s.cursorRow, caretStart)
                    patch { it.copy(cursorCol = textStart) }
                    return
                }
                if (isAtBulletMarkerEnd(line, s.cursorCol)) {
                    // Removing the `"* "` marker would orphan any subtree this bullet anchors.
                    // Only allow it when the bullet is a leaf — otherwise the user must remove
                    // the children first (deliberate action, no accidental detachment).
                    val bulletCol = DocumentLayout.bulletAsteriskColumn(line)
                    if (DocumentLayout.hasChildren(s.lines, s.cursorRow, bulletCol)) return
                    // Leaf bullet (empty or not): fall through to remove just the `"* "`
                    // marker, leaving any indent and trailing content intact and the cursor
                    // at the indent column. This gives the user a way to "exit" a bullet
                    // list by pressing backspace on an empty bullet — the row stays put as
                    // a plain (possibly indented) line instead of collapsing upward.
                }
                val removed = when {
                    isAtBulletMarkerEnd(line, s.cursorCol) -> 2
                    s.cursorCol <= leadingSpaces && s.cursorCol % TAB_SIZE == 0 -> TAB_SIZE
                    else -> 1
                }
                val newCol = s.cursorCol - removed
                documentBackingViewModel.delete(s.cursorRow, newCol, s.cursorRow, s.cursorCol)
                patch { it.copy(cursorCol = newCol) }
            }
            s.cursorRow > 0 -> {
                val zoom = zoomInfoOf(s)
                if (zoom != null && s.cursorRow <= zoom.startRow) {
                    return
                }
                val previousLen = s.lines[s.cursorRow - 1].length
                documentBackingViewModel.delete(s.cursorRow - 1, previousLen, s.cursorRow, 0)
                patch { it.copy(cursorRow = s.cursorRow - 1, cursorCol = previousLen) }
            }
        }
    }

    fun indentLine(amount: Int = TAB_SIZE) {
        val s = state
        if (!s.isLoaded) return
        val line = s.lines[s.cursorRow]
        val currentIndent = line.takeWhile { it == ' ' }.length
        if (s.cursorRow > 0) {
            val prev = s.lines[s.cursorRow - 1]
            if (DocumentLayout.bulletAsteriskColumn(prev) >= 0) {
                val prevIndent = prev.takeWhile { it == ' ' }.length
                if (currentIndent >= prevIndent + amount) return
            }
        }
        documentBackingViewModel.insertText(s.cursorRow, 0, " ".repeat(amount))
        patch { it.copy(cursorCol = s.cursorCol + amount, anchorRow = null, anchorCol = null) }
    }

    fun outdentLine(amount: Int = TAB_SIZE) {
        val s = state
        if (!s.isLoaded) return
        val line = s.lines[s.cursorRow]
        val leading = line.takeWhile { it == ' ' }.length
        val zoom = zoomInfoOf(s)
        val minAllowed = if (zoom != null) zoom.zoomIndent + TAB_SIZE else 0
        val remove = minOf(amount, leading - minAllowed).coerceAtLeast(0)
        if (remove == 0) return
        documentBackingViewModel.delete(s.cursorRow, 0, s.cursorRow, remove)
        patch {
            it.copy(
                cursorCol = (s.cursorCol - remove).coerceAtLeast(0),
                anchorRow = null, anchorCol = null
            )
        }
    }

    fun isBulletLine(): Boolean {
        val s = state
        if (!s.isLoaded) return false
        return DocumentLayout.bulletAsteriskColumn(s.lines[s.cursorRow]) >= 0
    }

    // ------------------------------------------------------------------ movement

    fun moveLeft(extend: Boolean = false) = mutate { st ->
        val sel = selectionOf(st)
        if (!extend && sel != null) {
            return@mutate st.copy(
                cursorRow = sel.startRow, cursorCol = sel.startCol,
                anchorRow = null, anchorCol = null
            )
        }
        val curLine = st.lines[st.cursorRow]
        val curMin = DocumentLayout.caretStartCol(curLine)
        val (r, c) = when {
            st.cursorCol > curMin -> st.cursorRow to skipMarkersLeft(st.lines[st.cursorRow], st.cursorCol - 1)
            else -> {
                val prev = prevVisibleRow(st, st.cursorRow)
                if (prev != null) prev to st.lines[prev].length
                else st.cursorRow to st.cursorCol
            }
        }
        moved(st, r, c, extend)
    }

    fun moveRight(extend: Boolean = false) = mutate { st ->
        val sel = selectionOf(st)
        if (!extend && sel != null) {
            return@mutate st.copy(
                cursorRow = sel.endRow, cursorCol = sel.endCol,
                anchorRow = null, anchorCol = null
            )
        }
        val line = st.lines[st.cursorRow]
        val (r, c) = when {
            st.cursorCol < line.length -> st.cursorRow to skipMarkersRight(line, st.cursorCol + 1)
            else -> {
                val next = nextVisibleRow(st, st.cursorRow)
                if (next != null) next to DocumentLayout.caretStartCol(st.lines[next])
                else st.cursorRow to st.cursorCol
            }
        }
        moved(st, r, c, extend)
    }

    fun moveUp(extend: Boolean = false) = mutate { st ->
        val targetRow = prevVisibleRow(st, st.cursorRow)
        if (targetRow == null) {
            if (extend) st else st.copy(anchorRow = null, anchorCol = null)
        } else {
            val targetLine = st.lines[targetRow]
            val targetCol = st.cursorCol.coerceIn(
                DocumentLayout.caretStartCol(targetLine), targetLine.length
            )
            moved(st, targetRow, targetCol, extend)
        }
    }

    fun moveDown(extend: Boolean = false) = mutate { st ->
        val targetRow = nextVisibleRow(st, st.cursorRow)
        if (targetRow == null) {
            if (extend) st else st.copy(anchorRow = null, anchorCol = null)
        } else {
            val targetLine = st.lines[targetRow]
            val targetCol = st.cursorCol.coerceIn(
                DocumentLayout.caretStartCol(targetLine), targetLine.length
            )
            moved(st, targetRow, targetCol, extend)
        }
    }

    fun moveTo(row: Int, col: Int, extend: Boolean = false) = mutate { st ->
        val clampedRow = row.coerceIn(0, st.lines.lastIndex)
        val line = st.lines[clampedRow]
        val clampedCol = col.coerceIn(DocumentLayout.caretStartCol(line), line.length)
        moved(st, clampedRow, clampedCol, extend)
    }

    fun moveLineStart(extend: Boolean = false) = mutate {
        moved(it, it.cursorRow, DocumentLayout.caretStartCol(it.lines[it.cursorRow]), extend)
    }

    fun moveLineEnd(extend: Boolean = false) = mutate {
        moved(it, it.cursorRow, it.lines[it.cursorRow].length, extend)
    }

    fun moveDocStart(extend: Boolean = false) = mutate {
        moved(it, 0, DocumentLayout.caretStartCol(it.lines[0]), extend)
    }

    fun moveDocEnd(extend: Boolean = false) = mutate {
        val lastRow = it.lines.lastIndex
        moved(it, lastRow, it.lines[lastRow].length, extend)
    }

    fun moveWordLeft(extend: Boolean = false) = mutate { st ->
        var row = st.cursorRow
        var col = st.cursorCol
        var minCol = DocumentLayout.caretStartCol(st.lines[row])
        if (col <= minCol && row > 0) {
            row--
            col = st.lines[row].length
            minCol = DocumentLayout.caretStartCol(st.lines[row])
        } else {
            val line = st.lines[row]
            while (col > minCol && !isWordChar(line[col - 1])) col--
            while (col > minCol && isWordChar(line[col - 1])) col--
        }
        moved(st, row, col, extend)
    }

    fun moveWordRight(extend: Boolean = false) = mutate { st ->
        var row = st.cursorRow
        var col = st.cursorCol
        val line = st.lines[row]
        if (col == line.length && row < st.lines.lastIndex) {
            row++
            col = DocumentLayout.caretStartCol(st.lines[row])
        } else {
            while (col < line.length && !isWordChar(line[col])) col++
            while (col < line.length && isWordChar(line[col])) col++
        }
        moved(st, row, col, extend)
    }

    // ------------------------------------------------------------------ selection

    fun selectAll() = mutate { st ->
        val zoom = zoomInfoOf(st)
        val startRow = zoom?.startRow ?: 0
        val endRow = zoom?.endRowInclusive ?: st.lines.lastIndex
        if (endRow < startRow) return@mutate st
        st.copy(
            anchorRow = startRow, anchorCol = 0,
            cursorRow = endRow, cursorCol = st.lines[endRow].length
        )
    }

    fun selectWord(row: Int, col: Int) = mutate { st ->
        val clampedRow = row.coerceIn(0, st.lines.lastIndex)
        val line = st.lines[clampedRow]
        val minCol = DocumentLayout.caretStartCol(line)
        if (line.isEmpty() || minCol >= line.length) {
            st.copy(cursorRow = clampedRow, cursorCol = minCol, anchorRow = clampedRow, anchorCol = minCol)
        } else {
            val clampedCol = col.coerceIn(minCol, line.length - 1)
            val isWord = isWordChar(line[clampedCol])
            var start = clampedCol
            var end = clampedCol
            while (start > minCol && isWordChar(line[start - 1]) == isWord) start--
            while (end < line.length && isWordChar(line[end]) == isWord) end++
            st.copy(anchorRow = clampedRow, anchorCol = start, cursorRow = clampedRow, cursorCol = end)
        }
    }

    fun selectLine(row: Int) = mutate { st ->
        val clampedRow = row.coerceIn(0, st.lines.lastIndex)
        st.copy(
            anchorRow = clampedRow, anchorCol = 0,
            cursorRow = clampedRow, cursorCol = st.lines[clampedRow].length
        )
    }

    fun clearSelection() = mutate { it.copy(anchorRow = null, anchorCol = null) }

    fun deleteSelectionIfAny(): Boolean {
        val s = state
        val sel = selectionOf(s) ?: run {
            if (s.anchorRow != null) {
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

    fun getSelectedText(): String? {
        val s = state
        if (!s.isLoaded) return null
        val sel = selectionOf(s) ?: return null
        val lines = s.lines
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

    fun onCutRequested(): String? {
        val text = getSelectedText() ?: return null
        deleteSelectionIfAny()
        return text
    }

    /**
     * Returns the set of editable-relative model columns occupied by
     * markdown marker characters (line-level prefix + inline markers) on
     * [line]. Empty when the line has no markers.
     */
    private fun markerSet(line: String): Set<Int> {
        val tStart = DocumentLayout.textStartCol(line)
        val editable = if (tStart >= line.length) "" else line.substring(tStart)
        val linePrefix = LineMarkdownPrefix.detect(editable, 0)
        val lineMarkerLen = if (linePrefix.style != null) linePrefix.markerEnd else 0
        val inlineText = if (lineMarkerLen >= editable.length) "" else editable.substring(lineMarkerLen)
        val tokenized = InlineMarkdownTokenizer.tokenize(inlineText)
        if (lineMarkerLen == 0 && tokenized.markerCols.isEmpty()) return emptySet()
        val out = HashSet<Int>(tokenized.markerCols.size + lineMarkerLen)
        for (i in 0 until lineMarkerLen) out += (tStart + i)
        for (m in tokenized.markerCols) out += (tStart + lineMarkerLen + m)
        return out
    }

    /**
     * `true` when [col] is strictly inside a marker run on [line] — i.e.
     * both the char at [col]-1 and the char at [col] are markers, so the
     * cursor would sit invisibly between two collapsed marker chars. The
     * boundaries (col immediately before / after a marker run) are not
     * "bad" — those are valid caret positions.
     */
    private fun isInsideMarker(line: String, col: Int, markers: Set<Int>): Boolean {
        if (col <= 0 || col >= line.length) return false
        if (markers.isEmpty()) return false
        return (col - 1) in markers && col in markers
    }

    /**
     * From candidate column [from], advance leftward (decreasing col) until
     * the cursor sits outside any marker run. Used by [moveLeft] so a
     * single arrow press jumps past hidden markers in one visual step.
     */
    private fun skipMarkersLeft(line: String, from: Int): Int {
        val markers = markerSet(line)
        if (markers.isEmpty()) return from
        var c = from
        val floor = DocumentLayout.caretStartCol(line)
        while (c > floor && isInsideMarker(line, c, markers)) c--
        return c
    }

    /**
     * Mirror of [skipMarkersLeft]: advance rightward (increasing col)
     * until the cursor sits outside any marker run.
     */
    private fun skipMarkersRight(line: String, from: Int): Int {
        val markers = markerSet(line)
        if (markers.isEmpty()) return from
        var c = from
        while (c < line.length && isInsideMarker(line, c, markers)) c++
        return c
    }
}
