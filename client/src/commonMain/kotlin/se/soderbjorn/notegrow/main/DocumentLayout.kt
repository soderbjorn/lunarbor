/*
 * DocumentLayout.kt
 * -----------------
 * Pure, platform-agnostic helpers that describe the document's *logical*
 * shape: which line is a bullet, where its text starts, where its
 * subtree ends, which rows are visible under the current fold state.
 *
 * Visual layout (wrap, chunking, caret pixels) used to live here too,
 * back when the web view painted text manually. Since the move to
 * `contenteditable` (and the platform-native text surfaces planned for
 * Android/iOS), those concerns belong to each platform — every native
 * editor surface already knows how to wrap and place a caret. What
 * stays in commonMain is the bullet/indent/zoom semantics, shared by
 * every platform.
 */

package se.soderbjorn.notegrow.main

import se.soderbjorn.notegrow.data.LineMarkdownPrefix

object DocumentLayout {

    /**
     * `true` when [line] is a bullet line whose content (everything after
     * the `"* "` marker) is empty or only whitespace. Includes both the
     * fully-blank string and bullets like `"  * "` or `"  *  "`.
     *
     * Used both by the per-pane VM (to decide whether a placeholder
     * inserted by [se.soderbjorn.notegrow.main.PaneBackingViewModel.zoomInto]
     * is still "throwaway") and by [se.soderbjorn.notegrow.data.NoteRepository.save]
     * (to strip trailing empty bullets at file end).
     */
    fun isEmptyBulletLine(line: String): Boolean {
        val bulletCol = bulletAsteriskColumn(line)
        if (bulletCol < 0) return false
        val textStart = bulletCol + 2
        if (textStart >= line.length) return true
        for (i in textStart until line.length) {
            if (!line[i].isWhitespace()) return false
        }
        return true
    }

    /** Column of the leading bullet `*` on [line], or -1 if [line] is not a bullet line. */
    fun bulletAsteriskColumn(line: String): Int {
        val indent = line.indexOfFirst { !it.isWhitespace() }
        if (indent < 0) return -1
        if (indent >= line.length - 1) return -1
        return if (line[indent] == '*' && line[indent + 1] == ' ') indent else -1
    }

    /**
     * Smallest column the cursor is allowed to occupy on [line]. For bullet lines this is
     * the position immediately after the `"* "` marker (`bulletAsteriskColumn(line) + 2`);
     * for non-bullet lines it is `0`.
     *
     * Used by the platform view layer (when mapping DOM/native selection back into the
     * model) and every cursor-movement intent to keep the caret out of the bullet/indent
     * zone, so the user cannot place the cursor before the bullet and cannot accidentally
     * backspace through the marker that anchors a row to its subtree.
     */
    fun textStartCol(line: String): Int {
        val bulletCol = bulletAsteriskColumn(line)
        return if (bulletCol >= 0) bulletCol + 2 else 0
    }

    /**
     * Smallest column the caret is allowed to occupy on [line] for the
     * WYSIWYG editor. Equals [textStartCol] plus the length of any
     * recognised line-level markdown prefix (`# `, `## `, `### `, `> `).
     *
     * The renderer hides those prefix characters entirely, so allowing the
     * caret to land before them would desync the model from the visible
     * caret: the user would press a key and either nothing appears to
     * happen (movement collapses back to display 0 on the next paint) or
     * subsequent typing would land *inside* the hidden prefix, breaking
     * the prefix's recognition pattern and revealing the markers.
     *
     * Used by every cursor-movement intent and by reconcile-time clamping
     * so the caret can never sit inside a hidden line-level prefix.
     */
    fun caretStartCol(line: String): Int {
        val textStart = textStartCol(line)
        val prefix = LineMarkdownPrefix.detect(line, textStart)
        return if (prefix.style != null) prefix.markerEnd else textStart
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

    /**
     * Zoom-tolerant variant of [subtreeEnd]. Walks downward from [row]
     * and returns the last row that should be considered "inside" the
     * zoom region rooted at [row].
     *
     * Unlike [subtreeEnd], a non-bullet prose line does not automatically
     * terminate the walk — it's included when its leading indent (or, for
     * an all-whitespace line, its length) is strictly greater than
     * [parentIndent]. This keeps Notegrow's always-supported mixed bullet
     * / non-bullet blocks visible inside a zoom view, and in particular
     * lets the user strip the `"* "` from an empty leaf bullet without
     * the resulting all-whitespace row falling outside the region (which
     * would trigger `reconcile`'s zoom clamp and yank the caret).
     *
     * Termination conditions, in order:
     *   - sibling or shallower bullet (`bulletAsteriskColumn ≤ parentIndent`)
     *   - non-bullet prose row whose leading indent ≤ [parentIndent]
     *     (catches root-level prose that doesn't belong to this zoom)
     *   - end of document
     *
     * A truly empty row (length 0) is treated as a neutral spacer: it
     * neither extends the meaningful end of the zoom nor terminates the
     * walk, but it IS included so the caret can sit there after pressing
     * Enter on a previous in-region row. Without this carve-out, the
     * fresh blank row from `insertNewlinePlain` would fall outside the
     * region and reconcile's zoom clamp would yank the caret back up.
     *
     * @return [row] itself when no rows belong to the zoom region.
     */
    fun zoomSubtreeEnd(lines: List<String>, row: Int, parentIndent: Int): Int {
        var end = row
        var i = row + 1
        while (i <= lines.lastIndex) {
            val line = lines[i]
            val col = bulletAsteriskColumn(line)
            if (col >= 0) {
                if (col <= parentIndent) break
                end = i
                i++
                continue
            }
            if (line.isEmpty()) {
                end = i
                i++
                continue
            }
            val firstNonSpace = line.indexOfFirst { it != ' ' }
            val effectiveIndent = if (firstNonSpace >= 0) firstNonSpace else line.length
            if (effectiveIndent <= parentIndent) break
            end = i
            i++
        }
        return end
    }

    /**
     * `true` when [row] in [lines] is a bullet whose immediate next line is
     * also a bullet at strictly greater indent than [indent]. [indent] should
     * be the bullet column of [row] (i.e. [bulletAsteriskColumn] of that line);
     * pass `-1` for non-bullet rows and the result is always `false`.
     *
     * Used by the chevron painter and by [PaneBackingViewModel]'s
     * default-collapse pass to decide whether a bullet is foldable.
     */
    fun hasChildren(lines: List<String>, row: Int, indent: Int): Boolean {
        if (indent < 0) return false
        if (row + 1 > lines.lastIndex) return false
        val nextCol = bulletAsteriskColumn(lines[row + 1])
        return nextCol > indent
    }

    /**
     * Returns the absolute row indices in `[startRow, endRowInclusive]` that
     * should be rendered, given a set of [collapsedIds] whose subtrees should
     * be hidden. A row whose [LineId] is in [collapsedIds] is itself emitted
     * (the parent is visible) but its descendants are skipped via
     * [subtreeEnd].
     *
     * This helper is the single source of truth shared by the paint loop and
     * cursor-movement helpers so they always agree on what is visible.
     * File-boundary "collapse" (an unexpanded `[[ref]]`) does not need
     * handling here: when a ref is collapsed its children are physically
     * absent from [lines] (the document VM unsplices them), so
     * `visibleRowsOf` only handles within-file folds.
     */
    fun visibleRowsOf(
        lines: List<String>,
        lineIds: List<LineId>,
        collapsedIds: Set<LineId>,
        startRow: Int,
        endRowInclusive: Int,
    ): List<Int> {
        if (endRowInclusive < startRow) return emptyList()
        val out = ArrayList<Int>(endRowInclusive - startRow + 1)
        var row = startRow
        while (row <= endRowInclusive) {
            out += row
            val id = if (row in lineIds.indices) lineIds[row] else null
            if (id != null && id in collapsedIds) {
                val indent = bulletAsteriskColumn(lines[row])
                if (indent >= 0) {
                    row = subtreeEnd(lines, row, indent) + 1
                    continue
                }
            }
            row++
        }
        return out
    }
}
