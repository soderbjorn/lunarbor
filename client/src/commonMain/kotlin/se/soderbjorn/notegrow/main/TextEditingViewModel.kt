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
        val s = state
        val result = documentBackingViewModel.insertText(s.cursorRow, s.cursorCol, char.toString())
        patch { it.copy(cursorRow = result.endRow, cursorCol = result.endCol, anchorRow = null, anchorCol = null) }
    }

    fun insertNewline() {
        if (!state.isLoaded) return
        deleteSelectionIfAny()
        val s = state
        val line = s.lines[s.cursorRow]
        val bulletPrefix = continuationBulletPrefix(line, s.cursorCol)
        val result = documentBackingViewModel.insertText(s.cursorRow, s.cursorCol, "\n" + bulletPrefix)
        patch { it.copy(cursorRow = result.endRow, cursorCol = result.endCol, anchorRow = null, anchorCol = null) }
    }

    fun insertText(text: String) {
        if (!state.isLoaded) return
        deleteSelectionIfAny()
        val s = state
        val result = documentBackingViewModel.insertText(s.cursorRow, s.cursorCol, text)
        patch { it.copy(cursorRow = result.endRow, cursorCol = result.endCol, anchorRow = null, anchorCol = null) }
    }

    fun backspace() {
        if (!state.isLoaded) return
        if (deleteSelectionIfAny()) return
        val s = state
        when {
            s.cursorCol > 0 -> {
                val line = s.lines[s.cursorRow]
                val leadingSpaces = line.takeWhile { it == ' ' }.length
                if (isAtBulletMarkerEnd(line, s.cursorCol)) {
                    // Removing the `"* "` marker would orphan any subtree this bullet anchors.
                    // Only allow it when the bullet is a leaf — otherwise the user must remove
                    // the children first (deliberate action, no accidental detachment).
                    val bulletCol = DocumentLayout.bulletAsteriskColumn(line)
                    if (DocumentLayout.hasChildren(s.lines, s.cursorRow, bulletCol)) return
                    // Empty leaf bullet (line is exactly the indent + `"* "`):
                    // collapse the whole row into the end of the previous one
                    // so a single backspace deletes the bullet, its indent,
                    // and the now-empty line in one step instead of three.
                    if (line.length == bulletCol + 2 && s.cursorRow > 0) {
                        val zoom = zoomInfoOf(s)
                        if (zoom == null || s.cursorRow > zoom.startRow) {
                            val previousLen = s.lines[s.cursorRow - 1].length
                            documentBackingViewModel.delete(
                                s.cursorRow - 1, previousLen, s.cursorRow, line.length
                            )
                            patch { it.copy(cursorRow = s.cursorRow - 1, cursorCol = previousLen) }
                            return
                        }
                    }
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
        val curMin = DocumentLayout.textStartCol(curLine)
        val (r, c) = when {
            st.cursorCol > curMin -> st.cursorRow to (st.cursorCol - 1)
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
            st.cursorCol < line.length -> st.cursorRow to (st.cursorCol + 1)
            else -> {
                val next = nextVisibleRow(st, st.cursorRow)
                if (next != null) next to DocumentLayout.textStartCol(st.lines[next])
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
                DocumentLayout.textStartCol(targetLine), targetLine.length
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
                DocumentLayout.textStartCol(targetLine), targetLine.length
            )
            moved(st, targetRow, targetCol, extend)
        }
    }

    fun moveTo(row: Int, col: Int, extend: Boolean = false) = mutate { st ->
        val clampedRow = row.coerceIn(0, st.lines.lastIndex)
        val line = st.lines[clampedRow]
        val clampedCol = col.coerceIn(DocumentLayout.textStartCol(line), line.length)
        moved(st, clampedRow, clampedCol, extend)
    }

    fun moveLineStart(extend: Boolean = false) = mutate {
        moved(it, it.cursorRow, DocumentLayout.textStartCol(it.lines[it.cursorRow]), extend)
    }

    fun moveLineEnd(extend: Boolean = false) = mutate {
        moved(it, it.cursorRow, it.lines[it.cursorRow].length, extend)
    }

    fun moveDocStart(extend: Boolean = false) = mutate {
        moved(it, 0, DocumentLayout.textStartCol(it.lines[0]), extend)
    }

    fun moveDocEnd(extend: Boolean = false) = mutate {
        val lastRow = it.lines.lastIndex
        moved(it, lastRow, it.lines[lastRow].length, extend)
    }

    fun moveWordLeft(extend: Boolean = false) = mutate { st ->
        var row = st.cursorRow
        var col = st.cursorCol
        var minCol = DocumentLayout.textStartCol(st.lines[row])
        if (col <= minCol && row > 0) {
            row--
            col = st.lines[row].length
            minCol = DocumentLayout.textStartCol(st.lines[row])
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
            col = DocumentLayout.textStartCol(st.lines[row])
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
        val minCol = DocumentLayout.textStartCol(line)
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
}
