/*
 * BlockLayout.kt (commonMain)
 * ---------------------------
 * The in-memory shape of a block (TRF-5): a bordered run of free Markdown
 * that sits among the bullets of an outline.
 *
 * On disk a block is a run of `>` lines, a blockquote (see `SubtreeCodec`). In the editor's
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
 * A block is an outline item: it nests under the preceding item by its
 * indent, exactly like a bullet does, so a block indented under a leaf
 * makes that leaf a parent (and therefore folder-backed on the next save).
 * It can also be a parent itself: rows indented under it are its children
 * (`DocumentLayout.itemColumn` treats its first row as the item row).
 *
 * A block can hold code blocks. Their rows carry a second hidden marker,
 * [CODE], right after the block marker (`<indent><FIRST|NEXT><CODE>code`):
 * a code row is drawn in monospace with no Markdown, its caret starts
 * after the marker, and Enter keeps its indentation instead of continuing
 * a list. Like the `> ` prefixes, the Markdown code fences (```` ``` ````)
 * exist only on disk: [rowContentsOf] turns a fenced run into code rows
 * on load and [diskContentOf] wraps each run of code rows in fences on
 * save.
 *
 * commonMain only — pure functions, no state.
 */

package se.soderbjorn.lunarbor.main

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
 * - The web paint loop uses them to draw the block's border, the block's
 *   own bullet dot, and the dots of list items inside it
 *   ([listPrefixLength]).
 */
object BlockLayout {

    /** Marker of a block's first row. */
    const val FIRST: Char = ''

    /** Marker of every further row of a block. */
    const val NEXT: Char = ''

    /**
     * Marker of a code row inside a block, right after the block marker.
     * A private-use character like [FIRST] and [NEXT].
     */
    const val CODE: Char = '\uE002'

    /**
     * A block with at least this many rows is *large*: it shows only its
     * first [PREVIEW_ROWS] rows until the pane expands it
     * (`PaneBackingViewModel.State.expandedBlockIds`).
     */
    const val FOLD_MIN_ROWS: Int = 16

    /** Rows a collapsed large block shows. */
    const val PREVIEW_ROWS: Int = 8

    /** `true` when the block spanning [range] is large ([FOLD_MIN_ROWS]). */
    fun isLarge(range: IntRange): Boolean = range.last - range.first + 1 >= FOLD_MIN_ROWS

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

    /**
     * `true` when [line] is a code row: a block row whose content starts
     * with [CODE].
     */
    fun isCodeLine(line: String): Boolean {
        val col = markerColumn(line)
        return col >= 0 && col + 1 < line.length && line[col + 1] == CODE
    }

    /**
     * Number of hidden characters between the block marker and a block
     * row's visible text: `1` on a code row ([CODE]), `0` otherwise.
     */
    fun codePrefixLength(line: String): Int = if (isCodeLine(line)) 1 else 0

    /**
     * The visible text of block row [line]: its content without the
     * [CODE] marker. Returns [line] unchanged when it is not a block row.
     */
    fun textOf(line: String): String {
        val content = contentOf(line)
        return if (content.startsWith(CODE)) content.substring(1) else content
    }

    /**
     * A block's content as stored on disk → the contents of its rows:
     * every fenced code block (a line of three or more backticks and
     * nothing else, up to the same fence) becomes code rows ([CODE] +
     * the line), fences dropped. A fence with an info string
     * (```` ```kotlin ````) or without a closing fence stays as plain text
     * lines, so nothing is ever lost.
     *
     * Called by `SubtreeCodec.composeNodeLines` when a block is loaded.
     */
    fun rowContentsOf(diskContent: List<String>): List<String> {
        val out = ArrayList<String>(diskContent.size)
        var i = 0
        while (i < diskContent.size) {
            val fence = codeFenceOf(diskContent[i])
            val close = fence?.let { f -> (i + 1 until diskContent.size).firstOrNull { diskContent[it].trim() == f } }
            if (close == null) {
                out += diskContent[i]
                i++
                continue
            }
            for (r in i + 1 until close) out += CODE + diskContent[r]
            // An empty code block keeps one (empty) code row.
            if (close == i + 1) out += CODE.toString()
            i = close + 1
        }
        return out
    }

    /**
     * The contents of a block's rows → its content on disk: every run of
     * consecutive code rows is wrapped in a code fence, the [CODE]
     * markers dropped. The fence is ```` ``` ````, lengthened when a code
     * line is itself a backtick fence, so the code never closes it early.
     * The inverse of [rowContentsOf].
     *
     * Called by `SubtreeCodec.parseComposed` when a block is saved.
     */
    fun diskContentOf(rowContents: List<String>): List<String> {
        val out = ArrayList<String>(rowContents.size + 2)
        var i = 0
        while (i < rowContents.size) {
            if (!rowContents[i].startsWith(CODE)) {
                out += rowContents[i]
                i++
                continue
            }
            var end = i
            while (end + 1 < rowContents.size && rowContents[end + 1].startsWith(CODE)) end++
            val code = (i..end).map { rowContents[it].substring(1) }
            val longest = code.mapNotNull { codeFenceOf(it)?.length }.maxOrNull() ?: 0
            val fence = "`".repeat(maxOf(3, longest + 1))
            out += fence
            out += code
            out += fence
            i = end + 1
        }
        return out
    }

    /** The backtick fence [line] consists of (three or more, trimmed), or `null`. */
    fun codeFenceOf(line: String): String? {
        val t = line.trim()
        return if (t.length >= 3 && t.all { it == '`' }) t else null
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

    /**
     * Length of the list-item prefix at the start of block row [line]'s
     * content — its leading spaces plus a `* ` or `- ` marker — or `0`
     * when the content is not a list item (or [line] is not a block row).
     * Block content is free Markdown, so these are Markdown list items,
     * never outline bullets: the view draws a dot for them, indented by
     * the leading spaces, and the caret starts after the prefix
     * (`DocumentLayout.caretStartCol`). Always `0` on a code row.
     */
    fun listPrefixLength(line: String): Int {
        val col = markerColumn(line)
        if (col < 0 || isCodeLine(line)) return 0
        var i = col + 1
        while (i < line.length && line[i] == ' ') i++
        if (i + 1 >= line.length) return 0
        val c = line[i]
        if ((c != '*' && c != '-') || line[i + 1] != ' ') return 0
        return i + 2 - (col + 1)
    }

    /**
     * Number of leading spaces of block row [line]'s content — the depth
     * of a list item inside the block, or a code row's indentation (after
     * its [CODE] marker). `0` for a non-block row.
     */
    fun contentIndentOf(line: String): Int {
        val col = markerColumn(line)
        if (col < 0) return 0
        val start = col + 1 + codePrefixLength(line)
        var i = start
        while (i < line.length && line[i] == ' ') i++
        return i - start
    }

    /** `true` when a block row's visible text holds nothing but whitespace. */
    fun isEmptyContent(line: String): Boolean = textOf(line).isBlank()

    private fun continuesBlock(lines: List<String>, row: Int, col: Int): Boolean {
        val line = lines[row]
        return markerColumn(line) == col && line[col] == NEXT
    }
}
