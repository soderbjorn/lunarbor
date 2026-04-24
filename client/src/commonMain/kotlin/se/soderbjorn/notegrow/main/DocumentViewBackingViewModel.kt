package se.soderbjorn.notegrow.main

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds one viewer's view of the shared document: cursor, selection anchor,
 * and a mirror of [DocumentBackingViewModel]'s state. All selection-aware
 * editor intents (typing that replaces a selection, bullet continuation,
 * indent/outdent, etc.) are implemented here by composing primitive edits on
 * the document backing VM with local cursor updates.
 */
class DocumentViewBackingViewModel(
    private val documentBackingViewModel: DocumentBackingViewModel,
    scope: CoroutineScope
) {
    data class State(
        val documentState: DocumentBackingViewModel.State? = null,
        val cursorRow: Int = 0,
        val cursorCol: Int = 0,
        val anchorRow: Int? = null,
        val anchorCol: Int? = null
    ) {
        val isLoaded: Boolean get() = documentState?.isLoaded == true
        val lines: List<String> get() = documentState?.lines ?: listOf("")
    }

    data class Selection(val startRow: Int, val startCol: Int, val endRow: Int, val endCol: Int)

    private val _stateFlow = MutableStateFlow(State())
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    init {
        scope.launch {
            documentBackingViewModel.stateFlow.collect { docState ->
                _stateFlow.value = clampCursor(_stateFlow.value.copy(documentState = docState))
            }
        }
    }

    // ------------------------------------------------------------------ edits

    fun insertChar(char: Char) {
        if (!_stateFlow.value.isLoaded) return
        deleteSelectionIfAny()
        val (row, col) = cursor()
        val result = documentBackingViewModel.insertText(row, col, char.toString())
        patch { it.copy(cursorRow = result.endRow, cursorCol = result.endCol, anchorRow = null, anchorCol = null) }
    }

    fun insertNewline() {
        if (!_stateFlow.value.isLoaded) return
        deleteSelectionIfAny()
        val state = _stateFlow.value
        val line = state.lines[state.cursorRow]
        val bulletPrefix = continuationBulletPrefix(line, state.cursorCol)
        val result = documentBackingViewModel.insertText(state.cursorRow, state.cursorCol, "\n" + bulletPrefix)
        patch { it.copy(cursorRow = result.endRow, cursorCol = result.endCol, anchorRow = null, anchorCol = null) }
    }

    fun insertText(text: String) {
        if (!_stateFlow.value.isLoaded) return
        deleteSelectionIfAny()
        val (row, col) = cursor()
        val result = documentBackingViewModel.insertText(row, col, text)
        patch { it.copy(cursorRow = result.endRow, cursorCol = result.endCol, anchorRow = null, anchorCol = null) }
    }

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
                val previousLen = state.lines[state.cursorRow - 1].length
                documentBackingViewModel.delete(state.cursorRow - 1, previousLen, state.cursorRow, 0)
                patch { it.copy(cursorRow = state.cursorRow - 1, cursorCol = previousLen) }
            }
        }
    }

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

    fun outdentLine(amount: Int = TAB_SIZE) {
        val state = _stateFlow.value
        if (!state.isLoaded) return
        val line = state.lines[state.cursorRow]
        val leading = line.takeWhile { it == ' ' }.length
        val remove = minOf(amount, leading)
        if (remove == 0) return
        documentBackingViewModel.delete(state.cursorRow, 0, state.cursorRow, remove)
        patch {
            it.copy(
                cursorCol = (state.cursorCol - remove).coerceAtLeast(0),
                anchorRow = null, anchorCol = null
            )
        }
    }

    fun isBulletLine(): Boolean {
        val state = _stateFlow.value
        if (!state.isLoaded) return false
        return DocumentLayout.bulletAsteriskColumn(state.lines[state.cursorRow]) >= 0
    }

    // ------------------------------------------------------------------ movement

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

    fun moveUp(extend: Boolean = false) = mutate { state ->
        if (state.cursorRow == 0) {
            if (extend) state else state.copy(anchorRow = null, anchorCol = null)
        } else {
            val targetRow = state.cursorRow - 1
            val targetCol = state.cursorCol.coerceAtMost(state.lines[targetRow].length)
            moved(state, targetRow, targetCol, extend)
        }
    }

    fun moveDown(extend: Boolean = false) = mutate { state ->
        if (state.cursorRow >= state.lines.lastIndex) {
            if (extend) state else state.copy(anchorRow = null, anchorCol = null)
        } else {
            val targetRow = state.cursorRow + 1
            val targetCol = state.cursorCol.coerceAtMost(state.lines[targetRow].length)
            moved(state, targetRow, targetCol, extend)
        }
    }

    fun moveTo(row: Int, col: Int, extend: Boolean = false) = mutate { state ->
        val clampedRow = row.coerceIn(0, state.lines.lastIndex)
        val clampedCol = col.coerceIn(0, state.lines[clampedRow].length)
        moved(state, clampedRow, clampedCol, extend)
    }

    fun moveLineStart(extend: Boolean = false) = mutate { moved(it, it.cursorRow, 0, extend) }
    fun moveLineEnd(extend: Boolean = false) = mutate {
        moved(it, it.cursorRow, it.lines[it.cursorRow].length, extend)
    }
    fun moveDocStart(extend: Boolean = false) = mutate { moved(it, 0, 0, extend) }
    fun moveDocEnd(extend: Boolean = false) = mutate {
        val lastRow = it.lines.lastIndex
        moved(it, lastRow, it.lines[lastRow].length, extend)
    }

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

    fun selectAll() = mutate { state ->
        val lastRow = state.lines.lastIndex
        state.copy(
            anchorRow = 0, anchorCol = 0,
            cursorRow = lastRow, cursorCol = state.lines[lastRow].length
        )
    }

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

    fun selectLine(row: Int) = mutate { state ->
        val clampedRow = row.coerceIn(0, state.lines.lastIndex)
        state.copy(
            anchorRow = clampedRow, anchorCol = 0,
            cursorRow = clampedRow, cursorCol = state.lines[clampedRow].length
        )
    }

    fun clearSelection() = mutate { it.copy(anchorRow = null, anchorCol = null) }

    /** Deletes the active selection (if any). Returns true if something was deleted. */
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

    /** Returns the selected text (or null if no selection) AND deletes it. */
    fun onCutRequested(): String? {
        val text = getSelectedText() ?: return null
        deleteSelectionIfAny()
        return text
    }

    // ------------------------------------------------------------------ helpers

    private fun cursor(): Pair<Int, Int> {
        val s = _stateFlow.value
        return s.cursorRow to s.cursorCol
    }

    private inline fun mutate(transform: (State) -> State) {
        val current = _stateFlow.value
        if (!current.isLoaded) return
        _stateFlow.value = clampCursor(transform(current))
    }

    /**
     * Patches the view state while ensuring [State.documentState] is refreshed
     * to the document VM's latest. Used right after calling a document VM
     * mutation so readers never see a view state whose cursor references stale
     * document content.
     */
    private inline fun patch(transform: (State) -> State) {
        val current = _stateFlow.value
        val patched = transform(current).copy(
            documentState = documentBackingViewModel.stateFlow.value
        )
        _stateFlow.value = clampCursor(patched)
    }

    private fun clampCursor(state: State): State {
        val docLoaded = state.documentState?.isLoaded == true
        if (!docLoaded) return state
        val lines = state.lines
        val lastRow = lines.lastIndex
        val row = state.cursorRow.coerceIn(0, lastRow)
        val col = state.cursorCol.coerceIn(0, lines[row].length)
        val ar = state.anchorRow?.coerceIn(0, lastRow)
        val ac = if (ar != null) state.anchorCol?.coerceIn(0, lines[ar].length) else null
        return state.copy(cursorRow = row, cursorCol = col, anchorRow = ar, anchorCol = ac)
    }

    private fun moved(state: State, newRow: Int, newCol: Int, extend: Boolean): State {
        return if (extend) {
            val ar = state.anchorRow ?: state.cursorRow
            val ac = state.anchorCol ?: state.cursorCol
            state.copy(cursorRow = newRow, cursorCol = newCol, anchorRow = ar, anchorCol = ac)
        } else {
            state.copy(cursorRow = newRow, cursorCol = newCol, anchorRow = null, anchorCol = null)
        }
    }

    private fun continuationBulletPrefix(line: String, cursorCol: Int): String {
        val indent = line.indexOfFirst { !it.isWhitespace() }
        if (indent < 0) return ""
        if (indent + 1 >= line.length) return ""
        if (line[indent] != '*' || line[indent + 1] != ' ') return ""
        if (cursorCol <= indent + 1) return ""
        return line.substring(0, indent) + "* "
    }

    private fun isAtBulletMarkerEnd(line: String, cursorCol: Int): Boolean {
        if (cursorCol < 2) return false
        if (line[cursorCol - 1] != ' ' || line[cursorCol - 2] != '*') return false
        for (i in 0 until cursorCol - 2) {
            if (line[i] != ' ') return false
        }
        return true
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    companion object {
        const val TAB_SIZE: Int = 2

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
