/*
 * BlockLayout.kt (commonMain)
 * ---------------------------
 * The in-memory shape of a block (TRF-5): a bordered run of free Markdown
 * that sits among the bullets of an outline.
 *
 * On disk a block is a `:::` fence (see `SubtreeCodec`). In the editor's
 * flat line list it is one row per content line, each carrying a hidden
 * marker character right after its indent:
 *
 * ```
 * * Trip to Lisbon
 *   ## Packing list      ← first row of the block
 *   - passport           ← every further row
 * * Next bullet
 * ```
 *
 * The marker makes every block row recognisable from its text alone, so
 * the context-free helpers in [DocumentLayout] (and everything built on
 * them — search, breadcrumbs, zoom, fold, the codec) never mistake a
 * `* item` line *inside* a block for a bullet. The marker is a Unicode
 * private-use character, so it can never collide with anything the user
 * types; the view never renders it (the editable text starts after it),
 * and `SubtreeCodec` strips it on save.
 *
 * Two markers are needed so two adjacent blocks at the same indent stay
 * separate: [FIRST] opens a block, [NEXT] continues the one above. A
 * [NEXT] row that does not follow a block row at the same indent (a
 * deletion can strand one) simply opens a new block — every helper here
 * applies that rule, so a block is always well-defined.
 *
 * A block row nests under the preceding bullet by its indent, exactly like
 * a bullet does, so a block indented under a leaf makes that leaf a parent
 * (and therefore folder-backed on the next save).
 *
 * commonMain only — pure functions, no state.
 */

package se.soderbjorn.treefacts.main

/**
 * Pure helpers for block rows in the editor's composed line list.
 *
 * ### Callers
 * - [DocumentLayout.textStartCol] uses [markerColumn] so the caret never
 *   lands on (or before) the hidden marker.
 * - `SubtreeCodec.composeNodeLines` / `parseComposed` build and read
 *   block rows with [firstLine], [nextLine], [markerColumn], [contentOf].
 * - `TextEditingViewModel` and `PaneBackingViewModel` use [rangeAt] and
 *   [startsBlock] for Enter, Backspace, Tab, paste, and the Insert /
 *   Delete / Leave block intents.
 * - The web paint loop uses them to draw the block's border.
 */
object BlockLayout {

    /** Marker of a block's first row. */
    const val FIRST: Char = ''

    /** Marker of every further row of a block. */
    const val NEXT: Char = ''

    /**
     * Column of the block marker on [line] — its indent — or `-1` when
     * [line] is not a block row. A block row is any number of spaces
     * followed by [FIRST] or [NEXT].
     */
    fun markerColumn(line: String): Int {
        val i = line.indexOfFirst { it != ' ' }
        if (i < 0) return -1
        val c = line[i]
        return if (c == FIRST || c == NEXT) i else -1
    }

    /** `true` when [line] is a block row. */
    fun isBlockLine(line: String): Boolean = markerColumn(line) >= 0

    /**
     * The Markdown content of block row [line]: everything after the
     * marker. Returns [line] unchanged when it is not a block row.
     */
    fun contentOf(line: String): String {
        val col = markerColumn(line)
        return if (col < 0) line else line.substring(col + 1)
    }

    /** A block's first row at [indent] holding [content]. */
    fun firstLine(indent: Int, content: String = ""): String = " ".repeat(indent) + FIRST + content

    /** A further block row at [indent] holding [content]. */
    fun nextLine(indent: Int, content: String = ""): String = " ".repeat(indent) + NEXT + content

    /**
     * `true` when the block row at [row] opens a block: it carries
     * [FIRST], or the row above is not a block row at the same indent.
     * `false` for non-block rows.
     */
    fun startsBlock(lines: List<String>, row: Int): Boolean {
        val line = lines.getOrNull(row) ?: return false
        val col = markerColumn(line)
        if (col < 0) return false
        if (line[col] == FIRST) return true
        val above = lines.getOrNull(row - 1) ?: return true
        return markerColumn(above) != col
    }

    /**
     * Rows of the block containing [row], or `null` when [row] is not a
     * block row. The block runs from its opening row (see [startsBlock])
     * down through every following [NEXT] row at the same indent.
     */
    fun rangeAt(lines: List<String>, row: Int): IntRange? {
        val line = lines.getOrNull(row) ?: return null
        val col = markerColumn(line)
        if (col < 0) return null
        var start = row
        while (!startsBlock(lines, start)) start--
        var end = row
        while (end + 1 <= lines.lastIndex && continuesBlock(lines, end + 1, col)) end++
        return start..end
    }

    /**
     * Opening rows of every block in [lines], in order, each with its
     * row range. Used by the paint loop to style a block's first and last
     * rows.
     */
    fun blocksOf(lines: List<String>): List<IntRange> {
        val out = ArrayList<IntRange>()
        var row = 0
        while (row <= lines.lastIndex) {
            val range = if (startsBlock(lines, row)) rangeAt(lines, row) else null
            if (range != null) {
                out += range
                row = range.last + 1
            } else {
                row++
            }
        }
        return out
    }

    /** `true` when a block content line holds nothing but whitespace. */
    fun isEmptyContent(line: String): Boolean = contentOf(line).isBlank()

    private fun continuesBlock(lines: List<String>, row: Int, col: Int): Boolean {
        val line = lines[row]
        return markerColumn(line) == col && line[col] == NEXT
    }
}
