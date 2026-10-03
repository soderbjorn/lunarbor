/*
 * PrivacyLayout.kt (commonMain)
 * -----------------------------
 * Which rows of an open outline a privacy mode hides (LBR-10): an item
 * whose own text carries one of the mode's tags — a bullet's title, or any
 * row of a block — is hidden with its whole subtree. Hidden rows stay in
 * `Document.lines` (they are saved as they are); panes treat them like a
 * folded subtree that never shows: `visibleRowsIn` (SelectionHelper) skips
 * them, so painting, caret movement, clamping and drag and drop never see
 * them, and `PaneBackingViewModel` refuses any edit that would change,
 * delete or re-parent one.
 *
 * Rows of descendants on disk only (folded folder-backed items) need no
 * test: their item's row is hidden or not, and a pane never loads a hidden
 * item's children.
 *
 * Pure helpers with a small cache: the hidden set is asked for on every
 * state emission, and a document's rows only change on edits.
 *
 * commonMain only — no DOM, Android UI, or UIKit imports.
 */

package se.soderbjorn.lunarbor.main

import se.soderbjorn.lunarbor.data.PrivacyFilter
import se.soderbjorn.lunarbor.data.TextIndex

object PrivacyLayout {

    /** One cached answer of [hiddenRows], by identity of its inputs. */
    private class Cached(val lines: List<String>, val filter: PrivacyFilter, val hidden: BooleanArray?)

    /** The last few answers (several panes may ask in turn). */
    private val cache = ArrayDeque<Cached>()

    private const val CACHE_SIZE = 8

    /**
     * Rows of [lines] that [filter] hides — `result[row]` is `true` for a
     * hidden row — or `null` when it hides none (the usual case, and
     * always with [PrivacyFilter.NONE]).
     *
     * @param lines An outline document's rows (`Document.State.lines`). Plain
     *   `.md` notes are hidden as whole files instead, never by row.
     */
    fun hiddenRows(lines: List<String>, filter: PrivacyFilter): BooleanArray? {
        if (!filter.isActive) return null
        cache.firstOrNull { it.lines === lines && it.filter == filter }?.let { return it.hidden }
        val hidden = compute(lines, filter)
        cache.addFirst(Cached(lines, filter, hidden))
        while (cache.size > CACHE_SIZE) cache.removeLast()
        return hidden
    }

    private fun compute(lines: List<String>, filter: PrivacyFilter): BooleanArray? {
        // Most documents carry no tag at all: nothing to tokenize.
        if (lines.none { '#' in it }) return null
        var out: BooleanArray? = null
        var row = 0
        while (row < lines.size) {
            val col = DocumentLayout.itemColumn(lines, row)
            if (col < 0) {
                row++
                continue
            }
            val own = DocumentLayout.itemLastRow(lines, row)
            val hides = (row..own).any { r -> filter.hides(TextIndex.tagKeysOfRow(lines[r])) }
            if (hides) {
                val end = DocumentLayout.subtreeEnd(lines, row, col)
                val hidden = out ?: BooleanArray(lines.size).also { out = it }
                for (r in row..end) hidden[r] = true
                row = end + 1
            } else {
                row = own + 1
            }
        }
        return out
    }

    /** `true` when [row] is hidden in [hidden] (a [hiddenRows] answer). */
    fun isHidden(hidden: BooleanArray?, row: Int): Boolean =
        hidden != null && row in hidden.indices && hidden[row]

    /**
     * `true` when any row strictly inside the subtree of the item at [row]
     * — its children, at any depth — is hidden.
     */
    fun hasHiddenDescendants(lines: List<String>, hidden: BooleanArray?, row: Int): Boolean {
        if (hidden == null) return false
        val col = DocumentLayout.itemColumn(lines, row)
        if (col < 0) return false
        val end = DocumentLayout.subtreeEnd(lines, row, col)
        for (r in DocumentLayout.itemLastRow(lines, row) + 1..end) if (hidden[r]) return true
        return false
    }

    /**
     * The id of the item [row] is nested under in [lines] — the nearest
     * item above it with a smaller item column — or `null` at the top
     * level. Used to check that an edit left every hidden item under the
     * parent it had.
     */
    fun parentIdOf(lines: List<String>, ids: List<LineId>, row: Int): LineId? {
        val col = DocumentLayout.itemColumn(lines, row)
        if (col <= 0) return null
        var r = row - 1
        while (r >= 0) {
            val c = DocumentLayout.itemColumn(lines, r)
            if (c in 0 until col) return ids.getOrNull(r)
            r--
        }
        return null
    }

    /**
     * `true` when going from ([beforeLines], [beforeIds]) to
     * ([afterLines], [afterIds]) keeps every row [filter] hid before: each
     * is still there (by id), with the same text apart from its indent, and
     * every hidden item still sits under the same parent — and no row that
     * was visible ended up inside an item that was already hidden (dropped,
     * indented or pasted under it, it would vanish). Rows that became
     * hidden because their own item now carries a hiding tag (just typed)
     * are fine.
     *
     * Called by `PaneBackingViewModel` after every recorded edit, to undo
     * one that would have touched content the user cannot see.
     */
    fun keepsHiddenRows(
        beforeLines: List<String>,
        beforeIds: List<LineId>,
        afterLines: List<String>,
        afterIds: List<LineId>,
        filter: PrivacyFilter,
    ): Boolean {
        val hidden = hiddenRows(beforeLines, filter)
        val afterHidden = hiddenRows(afterLines, filter)
        if (hidden == null && afterHidden == null) return true
        val beforeRow = HashMap<LineId, Int>(beforeIds.size * 2)
        beforeIds.forEachIndexed { i, id -> beforeRow[id] = i }
        if (hidden != null) {
            val afterRow = HashMap<LineId, Int>(afterIds.size * 2)
            afterIds.forEachIndexed { i, id -> afterRow[id] = i }
            for (r in beforeLines.indices) {
                if (!hidden[r]) continue
                val id = beforeIds.getOrNull(r) ?: return false
                val a = afterRow[id] ?: return false
                if (beforeLines[r].trimStart() != afterLines[a].trimStart()) return false
                if (DocumentLayout.itemColumn(beforeLines, r) >= 0 &&
                    parentIdOf(beforeLines, beforeIds, r) != parentIdOf(afterLines, afterIds, a)
                ) return false
            }
        }
        if (afterHidden != null) {
            // Each hidden range after the edit starts at the item carrying
            // the tag (its root); a row visible before may only be in a
            // range whose root was not hidden before.
            var rootId: LineId? = null
            var rootEnd = -1
            for (a in afterLines.indices) {
                if (!afterHidden[a]) continue
                if (a > rootEnd) {
                    rootId = afterIds.getOrNull(a)
                    val col = DocumentLayout.itemColumn(afterLines, a)
                    rootEnd = if (col >= 0) DocumentLayout.subtreeEnd(afterLines, a, col) else a
                }
                val id = afterIds.getOrNull(a) ?: continue
                val b = beforeRow[id] ?: continue
                if (isHidden(hidden, b)) continue
                val rootBefore = rootId?.let { beforeRow[it] } ?: continue
                if (isHidden(hidden, rootBefore)) return false
            }
        }
        return true
    }
}
