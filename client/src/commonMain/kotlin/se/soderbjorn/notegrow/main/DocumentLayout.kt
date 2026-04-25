package se.soderbjorn.notegrow.main

/**
 * Pure, platform-agnostic helpers that translate between the logical document
 * ([lines] + cursor column) and its visual layout under a given character wrap
 * [width]. Shared by every platform's view so that wrapping, caret placement,
 * and pointer hit-testing behave identically.
 */
object DocumentLayout {

    fun wrapLine(line: String, width: Int): List<String> {
        val w = width.coerceAtLeast(1)
        return if (line.isEmpty()) listOf("") else line.chunked(w)
    }

    fun chunkCountOf(line: String, width: Int): Int {
        val w = width.coerceAtLeast(1)
        return if (line.isEmpty()) 1 else (line.length + w - 1) / w
    }

    data class CursorVisual(
        val chunkIndex: Int,
        val colInChunk: Int,
        val needsTrailingEmptyChunk: Boolean
    )

    fun cursorVisualPosition(line: String, cursorCol: Int, width: Int): CursorVisual {
        val w = width.coerceAtLeast(1)
        if (line.isEmpty()) return CursorVisual(0, 0, false)
        return if (cursorCol < line.length) {
            CursorVisual(cursorCol / w, cursorCol % w, false)
        } else if (line.length % w == 0) {
            CursorVisual(line.length / w, 0, true)
        } else {
            CursorVisual(line.length / w, line.length % w, false)
        }
    }

    fun locateLogicalPosition(
        lines: List<String>,
        visualRow: Int,
        visualCol: Int,
        width: Int
    ): Pair<Int, Int> {
        if (lines.isEmpty()) return 0 to 0
        val w = width.coerceAtLeast(1)
        var remaining = visualRow
        for ((rowIndex, line) in lines.withIndex()) {
            val chunkCount = chunkCountOf(line, w)
            if (remaining < chunkCount) {
                val chunkStart = remaining * w
                val chunkEnd = (chunkStart + w).coerceAtMost(line.length)
                val col = (chunkStart + visualCol).coerceIn(chunkStart, chunkEnd)
                return rowIndex to col
            }
            remaining -= chunkCount
        }
        val lastRow = lines.lastIndex
        return lastRow to lines[lastRow].length
    }

    fun visualRowOfCursor(
        lines: List<String>,
        cursorRow: Int,
        cursorCol: Int,
        width: Int
    ): Int {
        val w = width.coerceAtLeast(1)
        var visualRow = 0
        for (i in 0 until cursorRow) {
            visualRow += chunkCountOf(lines[i], w)
        }
        visualRow += cursorCol / w
        return visualRow
    }

    /** Column of the leading bullet `*` on [line], or -1 if [line] is not a bullet line. */
    fun bulletAsteriskColumn(line: String): Int {
        val indent = line.indexOfFirst { !it.isWhitespace() }
        if (indent < 0) return -1
        if (indent >= line.length - 1) return -1
        return if (line[indent] == '*' && line[indent + 1] == ' ') indent else -1
    }

    /**
     * Walks downward from [row] and returns the last absolute row index that
     * belongs to its subtree. A line belongs to the subtree if it is a bullet
     * with indent strictly greater than [parentIndent]; anything else
     * (including non-bullet prose) terminates.
     *
     * @return [row] itself when the subtree is empty.
     */
    fun subtreeEnd(lines: List<String>, row: Int, parentIndent: Int): Int {
        var end = row
        while (end + 1 <= lines.lastIndex) {
            val col = bulletAsteriskColumn(lines[end + 1])
            if (col < 0 || col <= parentIndent) break
            end++
        }
        return end
    }

    data class ChunkHighlight(val leftChars: Int, val widthChars: Double)

    /**
     * For a single rendered chunk, returns the selection overlap expressed in
     * character units from the chunk's left edge — or null if the chunk has no
     * selection overlap. [trailingIndicatorChars] is the small extra width
     * appended on non-terminal rows of a multi-row selection to indicate the
     * newline is part of the selection.
     */
    fun chunkSelectionHighlight(
        selection: DocumentViewBackingViewModel.Selection,
        row: Int,
        line: String,
        chunkIndex: Int,
        totalChunks: Int,
        chunkLen: Int,
        wrapWidth: Int,
        trailingIndicatorChars: Double = 0.6
    ): ChunkHighlight? {
        if (row < selection.startRow || row > selection.endRow) return null
        val w = wrapWidth.coerceAtLeast(1)
        val chunkStart = chunkIndex * w
        val chunkEndContent = chunkStart + chunkLen
        val rowStartCol = if (row == selection.startRow) selection.startCol else 0
        val rowEndCol = if (row == selection.endRow) selection.endCol else line.length
        val overlapStart = maxOf(rowStartCol, chunkStart)
        val overlapEnd = minOf(rowEndCol, chunkEndContent)
        val isLastChunkOfLine = chunkIndex == totalChunks - 1
        val extendsPastRow = row < selection.endRow && isLastChunkOfLine

        return if (overlapStart < overlapEnd) {
            val base = (overlapEnd - overlapStart).toDouble()
            ChunkHighlight(
                leftChars = overlapStart - chunkStart,
                widthChars = if (extendsPastRow) base + trailingIndicatorChars else base
            )
        } else if (extendsPastRow && rowStartCol <= chunkEndContent) {
            ChunkHighlight(
                leftChars = (maxOf(rowStartCol, chunkStart) - chunkStart).coerceAtLeast(0),
                widthChars = trailingIndicatorChars
            )
        } else {
            null
        }
    }
}
