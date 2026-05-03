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
    // Any cursor movement cancels armed inline styles — Cmd-B followed
    // by an arrow key should not silently style the next typed character.
    val noPending = if (state.pendingInlineStyles.isEmpty()) state
        else state.copy(pendingInlineStyles = emptySet())
    return if (extend) {
        val ar = noPending.anchorRow ?: noPending.cursorRow
        val ac = noPending.anchorCol ?: noPending.cursorCol
        noPending.copy(cursorRow = newRow, cursorCol = newCol, anchorRow = ar, anchorCol = ac)
    } else {
        noPending.copy(cursorRow = newRow, cursorCol = newCol, anchorRow = null, anchorCol = null)
    }
}

/**
 * Returns the smallest visible row index strictly greater than [fromRow], or `null`
 * if none exists. "Visible" means the row would be rendered by the paint loop given
 * the current zoom range and within-file fold state — so callers (cursor movement,
 * etc.) skip past collapsed subtrees instead of landing on hidden rows that
 * `clampToVisible` would then bounce back into the parent.
 */
internal fun nextVisibleRow(state: DocumentViewBackingViewModel.State, fromRow: Int): Int? {
    val docState = state.documentState ?: return null
    val zoom = zoomInfoOf(state)
    val startRow = zoom?.startRow ?: 0
    val endRowInclusive = zoom?.endRowInclusive ?: docState.lines.lastIndex
    if (endRowInclusive < startRow) return null
    val visible = DocumentLayout.visibleRowsOf(
        docState.lines, docState.lineIds, state.collapsedIds, startRow, endRowInclusive
    )
    return visible.firstOrNull { it > fromRow }
}

/**
 * Returns the largest visible row index strictly less than [fromRow], or `null` if
 * none exists. Symmetric to [nextVisibleRow] — see that doc for rationale.
 */
internal fun prevVisibleRow(state: DocumentViewBackingViewModel.State, fromRow: Int): Int? {
    val docState = state.documentState ?: return null
    val zoom = zoomInfoOf(state)
    val startRow = zoom?.startRow ?: 0
    val endRowInclusive = zoom?.endRowInclusive ?: docState.lines.lastIndex
    if (endRowInclusive < startRow) return null
    val visible = DocumentLayout.visibleRowsOf(
        docState.lines, docState.lineIds, state.collapsedIds, startRow, endRowInclusive
    )
    return visible.lastOrNull { it < fromRow }
}

/**
 * One ancestor entry in the zoom breadcrumb chain. Returned by
 * [bulletAncestorsOf] from outermost-to-innermost order so the view can
 * render a `Root / outer / … / inner / current` style trail.
 *
 * @property lineId    stable id of the ancestor bullet — pass to
 *   [DocumentViewBackingViewModel.zoomTo] to navigate there.
 * @property titleText display text of the bullet with leading indent
 *   and the `"* "` marker stripped. Empty when the bullet has no text.
 */
data class BreadcrumbAncestor(
    val lineId: LineId,
    val titleText: String,
)

/**
 * Walks the bullet hierarchy upward from the currently zoomed line and
 * returns each ancestor (parent, grandparent, …) in outer-to-inner
 * order. The zoomed line itself is *not* included — the view already
 * renders it as the trailing breadcrumb segment via
 * [DocumentViewBackingViewModel.ZoomInfo.titleText].
 *
 * "Ancestor" means: a bullet line above the zoom target whose indent is
 * strictly less than the running indent, walking up the document. The
 * first such line is the immediate parent; the next-smaller-indent
 * bullet above that is the grandparent, and so on, until we reach
 * indent 0 (a root-level bullet) or the top of the document.
 *
 * Used by the editor header to render a clickable breadcrumb trail —
 * each segment can call back into `zoomTo(ancestor.lineId)` so the user
 * navigates one level up at a time without losing the current zoom
 * scope when only a partial ascent is wanted.
 *
 * @param state current view-model state
 * @return ancestors outer-to-inner, or an empty list when not zoomed.
 */
internal fun bulletAncestorsOf(
    state: DocumentViewBackingViewModel.State
): List<BreadcrumbAncestor> {
    val zoom = zoomInfoOf(state) ?: return emptyList()
    val docState = state.documentState ?: return emptyList()
    val lines = docState.lines
    val ids = docState.lineIds
    if (zoom.zoomIndent <= 0) return emptyList()

    val collected = mutableListOf<BreadcrumbAncestor>()
    var lookingFor = zoom.zoomIndent
    var row = zoom.zoomRow - 1
    while (row >= 0 && lookingFor > 0) {
        val line = lines[row]
        val indent = DocumentLayout.bulletAsteriskColumn(line)
        if (indent in 0 until lookingFor) {
            val title = line.substring(minOf(indent + 2, line.length))
            collected += BreadcrumbAncestor(lineId = ids[row], titleText = title)
            lookingFor = indent
        }
        row--
    }
    return collected.asReversed()
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
 * Builds the human-readable breadcrumb segments for the current zoom path.
 *
 * Returned segments are outer-to-inner: ancestors first (root-most ancestor
 * at index 0), then the zoom target itself last. Empty when the document is
 * not currently zoomed — callers should fall back to the document/pane title
 * in that case.
 *
 * Used by AppShell to drive both the toolkit pane header title and the
 * sidebar pane row label so they stay in sync as the user zooms in/out.
 *
 * @param state current view-model state
 * @return outer-to-inner segments, or an empty list when not zoomed.
 */
internal fun zoomPathSegmentsOf(
    state: DocumentViewBackingViewModel.State
): List<String> {
    val zoom = zoomInfoOf(state) ?: return emptyList()
    val ancestors = bulletAncestorsOf(state)
    return ancestors.map { it.titleText } + zoom.titleText
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
