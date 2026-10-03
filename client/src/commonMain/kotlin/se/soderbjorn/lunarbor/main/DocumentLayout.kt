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
 *
 * Every row of an outline is a bullet (TRF-4) or a block row (TRF-5, see
 * [BlockLayout]): the editing intents never produce any other line in a
 * `_node.md` node. Block rows carry a hidden marker, so none of the
 * context-free helpers here mistakes a `* item` inside a block for a
 * bullet. The helpers still accept plain lines — plain Markdown files use
 * them — and treat them by indentation, the same rule the storage codec
 * uses.
 */

package se.soderbjorn.lunarbor.main

import se.soderbjorn.lunarbor.data.InlineMarkdownTokenizer
import se.soderbjorn.lunarbor.data.LineMarkdownPrefix

object DocumentLayout {

    /**
     * `true` when [line] is a bullet line whose content (everything after
     * the `"* "` marker) is empty or only whitespace. Includes both the
     * fully-blank string and bullets like `"  * "` or `"  *  "`.
     *
     * Used both by the per-pane VM (to decide whether a placeholder
     * inserted by [se.soderbjorn.lunarbor.main.PaneBackingViewModel.zoomInto]
     * is still "throwaway") and by [se.soderbjorn.lunarbor.data.NoteRepository.save]
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
     * for block rows it is right after the hidden block marker
     * ([BlockLayout.markerColumn] + 1), and on a code row after its hidden
     * [BlockLayout.CODE] marker too; for any other line it is `0`.
     *
     * Used by the platform view layer (when mapping DOM/native selection back into the
     * model) and every cursor-movement intent to keep the caret out of the bullet/indent
     * zone, so the user cannot place the cursor before the bullet and cannot accidentally
     * backspace through the marker that anchors a row to its subtree.
     */
    fun textStartCol(line: String): Int {
        val bulletCol = bulletAsteriskColumn(line)
        if (bulletCol >= 0) return bulletCol + 2
        val blockCol = BlockLayout.markerColumn(line)
        return if (blockCol >= 0) blockCol + 1 + BlockLayout.codePrefixLength(line) else 0
    }

    /**
     * Smallest column the caret is allowed to occupy on [line] for the
     * WYSIWYG editor. Equals [textStartCol] plus the length of any
     * recognised line-level markdown prefix (`# `, `## `, `### `, `> `),
     * or, on a block row, of a list-item prefix
     * ([BlockLayout.listPrefixLength]), which the view draws as a dot.
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
        // Code is shown verbatim: no list dot, no heading or quote prefix.
        if (BlockLayout.isCodeLine(line)) return textStart
        val listPrefix = BlockLayout.listPrefixLength(line)
        if (listPrefix > 0) return textStart + listPrefix
        val prefix = LineMarkdownPrefix.detect(line, textStart)
        return if (prefix.style != null) prefix.markerEnd else textStart
    }

    /**
     * `true` when [col] sits at the visible start of [line]'s text: at or
     * before [caretStartCol], or separated from it only by hidden inline
     * markers (the opening `` ` `` of inline code, `**`, `~~`, …). The
     * renderer draws every such column at display 0, so the caret is
     * visually at the start even when the model column is past a marker.
     *
     * Used by `TextEditingViewModel.moveLeft` and the web view's Arrow
     * Left handling to decide when a press wraps to the previous row.
     */
    fun isAtVisibleTextStart(line: String, col: Int): Boolean {
        val start = caretStartCol(line)
        if (col <= start) return true
        if (BlockLayout.isCodeLine(line) || col > line.length) return false
        val markers = InlineMarkdownTokenizer.tokenize(line.substring(start)).markerCols
        return (start until col).all { (it - start) in markers }
    }

    /**
     * Nesting column of [line]: the bullet's `*` column for a bullet line,
     * otherwise its leading-space count (the whole length for an
     * all-whitespace line, `0` for the empty string).
     *
     * In an outline every row is a bullet or a block row. A block row's
     * nesting column is its marker column (its indent), so a block nests
     * under the preceding bullet by indentation — the same ownership rule
     * `SubtreeCodec.parseComposed` applies on save. Plain lines (plain
     * Markdown files) nest the same way.
     */
    fun indentOf(line: String): Int {
        val bulletCol = bulletAsteriskColumn(line)
        if (bulletCol >= 0) return bulletCol
        val firstNonSpace = line.indexOfFirst { it != ' ' }
        return if (firstNonSpace >= 0) firstNonSpace else line.length
    }

    /**
     * Walks downward from [row] and returns the last absolute row index that
     * belongs to its subtree: every following row whose [indentOf] is
     * strictly greater than [parentIndent]. The first row at or above
     * [parentIndent] (a sibling or a shallower bullet) ends the walk.
     *
     * Single source of truth for "the rows under a bullet" — folding
     * ([visibleRowsOf]), the zoom region (`zoomInfoOf`), subtree
     * indent/outdent and drag all use it.
     *
     * A block item's own rows ([itemLastRow]) always belong to it, so the
     * walk starts after them.
     *
     * @param parentIndent The item column of [row] ([itemColumn]).
     * @return [itemLastRow] when the subtree is empty.
     */
    fun subtreeEnd(lines: List<String>, row: Int, parentIndent: Int): Int {
        var end = itemLastRow(lines, row)
        while (end + 1 <= lines.lastIndex && indentOf(lines[end + 1]) > parentIndent) end++
        return end
    }

    /**
     * `true` when [row] in [lines] has at least one row nested under it,
     * i.e. the line after the item's own rows ([itemLastRow]) has an
     * [indentOf] strictly greater than [indent]. [indent] should be the
     * item column of [row] ([itemColumn]); pass `-1` for rows that start
     * no item and the result is always `false`.
     *
     * Used by the chevron painter, by [PaneBackingViewModel]'s
     * default-collapse pass, and by the editing intents that must not
     * orphan a subtree (Backspace-merge, Enter on an empty bullet).
     */
    fun hasChildren(lines: List<String>, row: Int, indent: Int): Boolean {
        if (indent < 0) return false
        val last = itemLastRow(lines, row)
        if (last + 1 > lines.lastIndex) return false
        return indentOf(lines[last + 1]) > indent
    }

    /**
     * Nesting column of the outline item that starts at [row]: the `*`
     * column of a bullet, or the marker column when [row] opens a block
     * ([BlockLayout.startsBlock]) — a block is an item of its own and can
     * have children. `-1` for every other row, including a block's
     * further rows.
     */
    fun itemColumn(lines: List<String>, row: Int): Int {
        val line = lines.getOrNull(row) ?: return -1
        val bulletCol = bulletAsteriskColumn(line)
        if (bulletCol >= 0) return bulletCol
        return if (BlockLayout.startsBlock(lines, row)) BlockLayout.markerColumn(line) else -1
    }

    /**
     * Last row of the item's own text: the block's last row when [row]
     * is a block row, otherwise [row]. Children start after it.
     */
    fun itemLastRow(lines: List<String>, row: Int): Int =
        BlockLayout.rangeAt(lines, row)?.last ?: row

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
     *
     * With [expandedBlockIds] given, a large block ([BlockLayout.isLarge])
     * whose first row's id is not in it shows only its first
     * [BlockLayout.PREVIEW_ROWS] rows. `null` shows every block whole.
     *
     * Rows marked in [hidden] (a privacy mode's, [PrivacyLayout.hiddenRows])
     * are never emitted; they always cover whole subtrees.
     */
    fun visibleRowsOf(
        lines: List<String>,
        lineIds: List<LineId>,
        collapsedIds: Set<LineId>,
        startRow: Int,
        endRowInclusive: Int,
        expandedBlockIds: Set<LineId>? = null,
        hidden: BooleanArray? = null,
    ): List<Int> {
        if (endRowInclusive < startRow) return emptyList()
        val out = ArrayList<Int>(endRowInclusive - startRow + 1)
        var row = startRow
        while (row <= endRowInclusive) {
            if (hidden != null && row in hidden.indices && hidden[row]) {
                row++
                continue
            }
            val id = if (row in lineIds.indices) lineIds[row] else null
            val clipped = expandedBlockIds != null && id !in expandedBlockIds &&
                BlockLayout.startsBlock(lines, row) &&
                BlockLayout.rangeAt(lines, row)?.let(BlockLayout::isLarge) == true
            if (clipped) {
                val last = minOf(row + BlockLayout.PREVIEW_ROWS - 1, endRowInclusive)
                for (r in row..last) out += r
            } else {
                out += row
            }
            if (id != null && id in collapsedIds) {
                val indent = itemColumn(lines, row)
                if (indent >= 0) {
                    // A folded block still shows its own rows.
                    if (!clipped) {
                        val own = minOf(itemLastRow(lines, row), endRowInclusive)
                        for (r in row + 1..own) out += r
                    }
                    row = subtreeEnd(lines, row, indent) + 1
                    continue
                }
            }
            if (clipped) {
                row = itemLastRow(lines, row) + 1
                continue
            }
            row++
        }
        return out
    }
}
