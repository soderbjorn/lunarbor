package se.soderbjorn.notegrow.main

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.soderbjorn.notegrow.data.NoteRepository

/**
 * Owns the canonical, persisted document: an ordered list of text lines.
 * Knows nothing about cursors, selections, or view state — those live in
 * [DocumentViewBackingViewModel]. Operations are primitive edits
 * ([insertText], [insertNewline], [delete]); higher-level editor behaviors
 * (typing that replaces a selection, bullet continuation, indent/outdent)
 * compose these in the view backing VM.
 */
class DocumentBackingViewModel(
    private val repository: NoteRepository,
    private val scope: CoroutineScope,
    private val autoSaveIntervalMillis: Long = 5_000L
) {
    data class State(
        val lines: List<String> = listOf(""),
        val isLoaded: Boolean = false
    )

    data class InsertResult(val endRow: Int, val endCol: Int)

    private val _stateFlow = MutableStateFlow(State())
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    private var lastSavedText: String = ""

    init {
        scope.launch { loadFromDisk() }
        scope.launch { runAutoSaveLoop() }
    }

    /** Inserts [text] (may contain `\n`) at ([row], [col]). Returns the end position. */
    fun insertText(row: Int, col: Int, text: String): InsertResult {
        val state = _stateFlow.value
        if (!state.isLoaded) return InsertResult(row, col)
        if (text.isEmpty()) return InsertResult(row, col)
        val incoming = text.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        val currentLine = state.lines[row]
        val before = currentLine.substring(0, col)
        val after = currentLine.substring(col)
        val newLines = state.lines.toMutableList()
        val result: InsertResult
        if (incoming.size == 1) {
            newLines[row] = before + incoming[0] + after
            result = InsertResult(row, col + incoming[0].length)
        } else {
            newLines[row] = before + incoming.first()
            for (i in 1 until incoming.size - 1) {
                newLines.add(row + i, incoming[i])
            }
            val lastIdx = row + incoming.size - 1
            newLines.add(lastIdx, incoming.last() + after)
            result = InsertResult(lastIdx, incoming.last().length)
        }
        _stateFlow.value = state.copy(lines = newLines)
        return result
    }

    /** Splits the line at ([row], [col]). Returns the position at the start of the new line. */
    fun insertNewline(row: Int, col: Int): InsertResult = insertText(row, col, "\n")

    /**
     * Deletes everything from ([startRow], [startCol]) to ([endRow], [endCol]).
     * Safe to call with start == end (no-op).
     */
    fun delete(startRow: Int, startCol: Int, endRow: Int, endCol: Int) {
        val state = _stateFlow.value
        if (!state.isLoaded) return
        if (startRow == endRow && startCol == endCol) return
        val lines = state.lines
        val startLine = lines[startRow]
        val endLine = lines[endRow]
        val merged = startLine.substring(0, startCol) + endLine.substring(endCol)
        val newLines = lines.toMutableList()
        newLines[startRow] = merged
        repeat(endRow - startRow) {
            newLines.removeAt(startRow + 1)
        }
        _stateFlow.value = state.copy(lines = newLines)
    }

    private suspend fun loadFromDisk() {
        val text = repository.load()
        lastSavedText = text
        val lines = if (text.isEmpty()) listOf("") else text.split("\n")
        _stateFlow.value = _stateFlow.value.copy(lines = lines, isLoaded = true)
    }

    private suspend fun runAutoSaveLoop() {
        while (true) {
            delay(autoSaveIntervalMillis)
            val state = _stateFlow.value
            if (!state.isLoaded) continue
            val currentText = state.lines.joinToString("\n")
            if (currentText == lastSavedText) continue
            repository.save(currentText)
            lastSavedText = currentText
        }
    }
}
