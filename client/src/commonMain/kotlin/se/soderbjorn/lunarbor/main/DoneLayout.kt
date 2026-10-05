/*
 * DoneLayout.kt (commonMain)
 * --------------------------
 * Which rows of an open outline are done (LBR-24): an item whose own title
 * is struck through as a whole ([DoneState.isDoneRow]; a block's first row
 * decides) is done, and so is every row of its subtree. The web view dims
 * done rows (the item itself is also drawn struck through by its Markdown;
 * the rows done only by inheritance are dimmed, not struck), and a pane's
 * "Hide done items" ([PaneBackingViewModel.State.hideDone]) leaves done
 * items and their subtrees off the screen through [hiddenRows], which
 * `visibleRowsIn` merges with the privacy mode's hidden rows.
 *
 * Unlike a privacy mode, hiding done items is only a view filter: edits
 * next to or across them are never refused.
 *
 * Only within the open document: an outline whose node is done by an item
 * in a file above it is not dimmed for that (the search index knows,
 * `TextIndex.isFolderDone`; the outline view does not ask).
 *
 * Pure helpers with a small identity cache (asked on every paint and caret
 * move; a document's rows change only on edits).
 *
 * commonMain only — no DOM, Android UI, or UIKit imports.
 */

package se.soderbjorn.lunarbor.main

import se.soderbjorn.lunarbor.data.DoneState

object DoneLayout {

    /** One cached answer, by identity of [lines] and the start row. */
    private class Cached(val lines: List<String>, val from: Int, val rows: BooleanArray?)

    private val cache = ArrayDeque<Cached>()

    private const val CACHE_SIZE = 8

    /**
     * Done rows of [lines] — `result[row]` is `true` for a row of a done
     * item or of anything under one — or `null` when no row is done (the
     * usual case: no `~~` at all).
     *
     * Called by `PaneBackingViewModel.isRowDone` (the paint loop's dimming).
     */
    fun doneRows(lines: List<String>): BooleanArray? = cached(lines, 0)

    /**
     * The rows "Hide done items" hides when the page starts at [from]: every
     * done item at or after [from] with its subtree. Done-ness inherited
     * from rows before [from] (a done zoom target, or one of its ancestors)
     * does not count — the page itself is never hidden away. `null` when
     * nothing is hidden.
     *
     * Called by `hiddenOnScreenIn` (SelectionHelper) for `visibleRowsIn`.
     *
     * @param from The page's first row: the zoom's start row, or `0`.
     */
    fun hiddenRows(lines: List<String>, from: Int): BooleanArray? = cached(lines, from)

    private fun cached(lines: List<String>, from: Int): BooleanArray? {
        cache.firstOrNull { it.lines === lines && it.from == from }?.let { return it.rows }
        val rows = compute(lines, from)
        cache.addFirst(Cached(lines, from, rows))
        while (cache.size > CACHE_SIZE) cache.removeLast()
        return rows
    }

    private fun compute(lines: List<String>, from: Int): BooleanArray? {
        if (lines.none { "~~" in it }) return null
        var out: BooleanArray? = null
        var row = from.coerceAtLeast(0)
        while (row < lines.size) {
            val col = DocumentLayout.itemColumn(lines, row)
            if (col < 0) {
                row++
                continue
            }
            if (DoneState.isDoneRow(lines[row])) {
                val end = DocumentLayout.subtreeEnd(lines, row, col)
                val done = out ?: BooleanArray(lines.size).also { out = it }
                for (r in row..end) done[r] = true
                row = end + 1
            } else {
                row = DocumentLayout.itemLastRow(lines, row) + 1
            }
        }
        return out
    }

    /** `true` when [row] is marked in [rows] (a [doneRows] / [hiddenRows] answer). */
    fun isMarked(rows: BooleanArray?, row: Int): Boolean = rows != null && row in rows.indices && rows[row]

    /**
     * [a] and [b] merged (a row marked in either), reusing one of them when
     * the other is `null`.
     */
    fun union(a: BooleanArray?, b: BooleanArray?): BooleanArray? {
        if (a == null) return b
        if (b == null) return a
        return BooleanArray(maxOf(a.size, b.size)) { (it < a.size && a[it]) || (it < b.size && b[it]) }
    }
}
