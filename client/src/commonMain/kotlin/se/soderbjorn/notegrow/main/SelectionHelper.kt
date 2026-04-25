/*
 * SelectionHelper.kt
 * ------------------
 * Pure helpers used by the editing and navigation ViewModels: selection
 * normalization, caret-with-anchor moves, word/whitespace classification,
 * and small text predicates that depend only on the line text plus a
 * column index.
 *
 * commonMain — no DOM, no platform UI, no flows or coroutines. Everything
 * here is referentially transparent so the splitter could call it from
 * anywhere without worrying about state ownership.
 */

package se.soderbjorn.notegrow.main

/**
 * Word-character predicate used by word movement and word selection.
 */
internal fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

/**
 * Computes the prefix (indent + `"* "`) that a newline should inherit
 * from [line] when Enter is pressed at [cursorCol]. Returns an empty
 * string for non-bullet lines or when the caret is still at/before the
 * bullet marker (so pressing Enter at the very start of a bullet
 * produces a blank line, matching most editors).
 */
internal fun continuationBulletPrefix(line: String, cursorCol: Int): String {
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
 * leading whitespace. Used by backspace so hitting Backspace on an
 * empty bullet removes the marker as one "character" rather than two.
 */
internal fun isAtBulletMarkerEnd(line: String, cursorCol: Int): Boolean {
    if (cursorCol < 2) return false
    if (line[cursorCol - 1] != ' ' || line[cursorCol - 2] != '*') return false
    for (i in 0 until cursorCol - 2) {
        if (line[i] != ' ') return false
    }
    return true
}

/**
 * Produces a new state with the caret at ([newRow], [newCol]) and either
 * grows the selection (when [extend] is `true`) or clears it.
 */
internal fun moved(
    state: DocumentViewBackingViewModel.State,
    newRow: Int,
    newCol: Int,
    extend: Boolean
): DocumentViewBackingViewModel.State {
    return if (extend) {
        val ar = state.anchorRow ?: state.cursorRow
        val ac = state.anchorCol ?: state.cursorCol
        state.copy(cursorRow = newRow, cursorCol = newCol, anchorRow = ar, anchorCol = ac)
    } else {
        state.copy(cursorRow = newRow, cursorCol = newCol, anchorRow = null, anchorCol = null)
    }
}

/**
 * Resolves [DocumentViewBackingViewModel.State.zoomedLineId] to concrete
 * row geometry. Returns `null` if there is no zoom, the id is no longer
 * in the document, or the line it points to is no longer a bullet.
 */
internal fun zoomInfoOf(
    state: DocumentViewBackingViewModel.State
): DocumentViewBackingViewModel.ZoomInfo? {
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
    return DocumentViewBackingViewModel.ZoomInfo(
        zoomRow = row,
        zoomIndent = indent,
        startRow = row + 1,
        endRowInclusive = end,
        titleText = titleText
    )
}

/**
 * Normalizes an anchor + cursor pair into a [DocumentViewBackingViewModel.Selection].
 * Returns `null` when there is no anchor (caret only) or when the anchor
 * and caret coincide (empty selection).
 */
internal fun selectionOf(
    state: DocumentViewBackingViewModel.State
): DocumentViewBackingViewModel.Selection? {
    val ar = state.anchorRow ?: return null
    val ac = state.anchorCol ?: return null
    if (ar == state.cursorRow && ac == state.cursorCol) return null
    return if (ar < state.cursorRow || (ar == state.cursorRow && ac <= state.cursorCol))
        DocumentViewBackingViewModel.Selection(ar, ac, state.cursorRow, state.cursorCol)
    else
        DocumentViewBackingViewModel.Selection(state.cursorRow, state.cursorCol, ar, ac)
}
