/*
 * MarkdownStyleViewModel.kt
 * -------------------------
 * Inline / line-level markdown styling slice of the per-pane ViewModel.
 * Composed by `PaneBackingViewModel`, this class implements:
 *
 *   - [applyInlineStyle]  — wrap or unwrap a selection / word with one of
 *     the inline markers (bold, italic, strike, inline code).
 *   - [applyLineStyle]    — set / replace / remove a line-level prefix
 *     (heading 1–3, quote) on the cursor row or every row in a multi-row
 *     selection.
 *   - [clearFormatting] / [clearFormattingIn] — strip both kinds from
 *     the caret's rows, or from every bullet in a range of rows.
 *   - [activeInlineStyles] — which inline styles enclose the current
 *     cursor (or are common across the selection); used by the style
 *     dropdown to render check-marks.
 *   - [activeLineStyle]   — the line-level style of the cursor row, or
 *     `null` if mixed across a multi-row selection.
 *
 * commonMain — no DOM, no platform UI. The class holds no state of its
 * own; it operates on the aggregate's state flow through the same small
 * set of mutators used by [TextEditingViewModel].
 */

package se.soderbjorn.lunarbor.main

import se.soderbjorn.lunarbor.data.DoneState
import se.soderbjorn.lunarbor.data.InlineMarkdownTokenizer
import se.soderbjorn.lunarbor.data.InlineStyle
import se.soderbjorn.lunarbor.data.LineMarkdownPrefix
import se.soderbjorn.lunarbor.data.LineStyle

/**
 * Markdown-styling slice of the per-pane ViewModel.
 *
 * @param documentProvider Returns the [Document] the pane currently has
 *   acquired. Called on every edit so a pane swap is transparent.
 * @param stateProvider Reads the latest aggregate state.
 * @param patch Applies a transform that touches document content; refreshes
 *   the mirrored `documentState` and reconciles.
 * @param selectWord Delegates to `TextEditingViewModel.selectWord` when an
 *   inline style is invoked with no active selection.
 */
internal class MarkdownStyleViewModel(
    private val documentProvider: () -> Document,
    private val stateProvider: () -> PaneBackingViewModel.State,
    private val patch: ((PaneBackingViewModel.State) -> PaneBackingViewModel.State) -> Unit,
    private val selectWord: (row: Int, col: Int) -> Unit,
) {
    private val state: PaneBackingViewModel.State get() = stateProvider()
    private val document: Document get() = documentProvider()

    // ---------------------------------------------------------------- inline

    /**
     * Toggle [style] on the current selection, or on the word at the
     * cursor when the selection is empty. Multi-row selections are a
     * no-op (wrapping markers across line boundaries is rarely what users
     * want) — except [InlineStyle.INLINE_CODE] across rows of one block,
     * which toggles a code block ([codeBlockRows]). On a code row, whose
     * text is shown verbatim, inline styles do not apply; Inline code
     * there takes the rows out of the code block.
     *
     * Toggle semantics, decided from the tokenized line so hidden marker
     * columns at either end of the selection do not count:
     * - When every visible character selected already has this style and
     *   its markers sit right around them, the markers are stripped.
     * - When it has the style but only as part of a longer run, nothing
     *   changes.
     * - Otherwise the markers are inserted around the visible text.
     */
    fun applyInlineStyle(style: InlineStyle) {
        val s = state
        if (!s.isLoaded) return

        val sel = selectionOf(s)
        val codeRows = codeBlockRows(s)
        if (codeRows != null) {
            if (style == InlineStyle.INLINE_CODE) setCode(codeRows, on = !codeRows.all { BlockLayout.isCodeLine(s.lines[it]) })
            return
        }
        if (sel == null) {
            // Collapsed caret. Three cases:
            //   1. [style] is currently armed (in pendingInlineStyles).
            //      Disarm it. If the caret sits just before the matching
            //      closer (we just typed inside the armed pair), also hop
            //      past the closer so the next keystroke isn't reabsorbed
            //      by the still-styled run.
            //   2. [style] is not armed but the caret is inside an
            //      existing tokenized span of this style (the user
            //      clicked into `**foo|bar**`). Hop past that span's
            //      closer — "stop bolding from here onward".
            //   3. Neither — arm [style] so the next inserted text is
            //      wrapped with the markers.
            // This matches every other editor's Cmd-B / Cmd-I behavior:
            // pressing the shortcut toggles styled typing from the caret
            // onward, regardless of whether the caret was already inside
            // a styled run or not.
            if (style in s.pendingInlineStyles) {
                val line = s.lines[s.cursorRow]
                val closer = style.closeMarker
                val advance = if (
                    s.cursorCol + closer.length <= line.length &&
                    line.regionMatches(s.cursorCol, closer, 0, closer.length)
                ) closer.length else 0
                patch {
                    it.copy(
                        pendingInlineStyles = it.pendingInlineStyles - style,
                        cursorCol = it.cursorCol + advance,
                    )
                }
                return
            }
            val exitCol = exitColumnForSurroundingStyle(s, style)
            if (exitCol != null) {
                patch { it.copy(cursorCol = exitCol) }
                return
            }
            patch {
                it.copy(pendingInlineStyles = it.pendingInlineStyles + style)
            }
            return
        }

        if (sel.startRow != sel.endRow) return  // multi-row no-op in v1

        val row = sel.startRow
        val line = state.lines[row]
        val open = style.openMarker
        val close = style.closeMarker
        val inlineStart = LineMarkdownPrefix.detect(line, DocumentLayout.textStartCol(line)).markerEnd
        val tokens = InlineMarkdownTokenizer.tokenize(line.substring(inlineStart))

        // Snap the selection onto the visible text it covers. Markers are
        // hidden, so a selection synced from the DOM can start or end on
        // either side of them; deciding from the raw characters next to it
        // would then wrap `*word*` into `**word**` (bold) or strip one `*`
        // of a bold pair.
        var from = (sel.startCol - inlineStart).coerceAtLeast(0)
        var to = (sel.endCol - inlineStart).coerceAtLeast(from)
        while (from < to && from in tokens.markerCols) from++
        while (to > from && (to - 1) in tokens.markerCols) to--
        if (from == to) return
        val start = inlineStart + from
        val end = inlineStart + to

        if (style in tokens.stylesAcross(from, to)) {
            // Toggle off: strip the markers right around the text. The
            // same characters serve every nesting (`***word***` minus
            // italic is `**word**`). A selection inside a longer run of
            // [style] has no markers of its own there and is left alone.
            val wrapped = start - open.length >= inlineStart &&
                end + close.length <= line.length &&
                line.regionMatches(start - open.length, open, 0, open.length) &&
                line.regionMatches(end, close, 0, close.length)
            if (!wrapped) return
            // Delete the close marker first (later in the line) so start positions are stable.
            document.delete(row, end, row, end + close.length)
            document.delete(row, start - open.length, row, start)
            patch {
                it.copy(
                    anchorRow = row, anchorCol = start - open.length,
                    cursorRow = row, cursorCol = end - open.length,
                )
            }
            return
        }

        // Toggle on: wrap the visible text.
        document.insertText(row, end, close)
        document.insertText(row, start, open)
        patch {
            it.copy(
                anchorRow = row, anchorCol = start + open.length,
                cursorRow = row, cursorCol = end + open.length,
            )
        }
    }

    // ----------------------------------------------------------- line-level

    /**
     * Set the line-level [style] on the cursor row, or on every row in a
     * multi-row selection. When every affected row already has [style],
     * the prefix is removed instead (toggle-off). Otherwise [style]
     * replaces any conflicting prefix (`# `→`## `, etc).
     *
     * Edits run bottom-up so column shifts on earlier rows do not
     * invalidate later row indices.
     */
    fun applyLineStyle(style: LineStyle) {
        val s = state
        if (!s.isLoaded) return
        val (firstRow, lastRow) = rowSpan(s)
        val lines = s.lines
        // Code rows are verbatim: a `# ` there is code, not a heading.
        val rows = (firstRow..lastRow).filter { !BlockLayout.isCodeLine(lines[it]) }

        // Determine toggle-off vs apply.
        val allHaveTarget = rows.all { row ->
            val tStart = DocumentLayout.textStartCol(lines[row])
            LineMarkdownPrefix.detect(lines[row], tStart).style == style
        }

        for (row in rows.asReversed()) {
            val original = state.lines[row]
            val tStart = DocumentLayout.textStartCol(original)
            val updated = if (allHaveTarget) {
                LineMarkdownPrefix.remove(original, tStart)
            } else {
                LineMarkdownPrefix.apply(original, style, tStart)
            }
            if (updated == original) continue
            document.delete(row, 0, row, original.length)
            document.insertText(row, 0, updated)
        }
        patch { it }
    }

    // ------------------------------------------------------ clear formatting

    /**
     * "Clear formatting" (palette): strips the heading / quote prefix and
     * every inline style marker (bold, italic, strikethrough, inline code)
     * from the caret's row, or from every row a selection touches — bullets,
     * block rows and Markdown-mode lines alike ([clearedLine]). Links, tags,
     * images and list items stay; a done item stays done; code rows are
     * left alone. The caret and anchor keep their place in the text.
     *
     * Called by `PaneBackingViewModel.clearFormatting` inside `recordEdit`.
     */
    fun clearFormatting() {
        val s = state
        if (!s.isLoaded) return
        val (first, last) = rowSpan(s)
        clearRows((first..last).toList())
    }

    /**
     * The recursive "Clear formatting in subtree": [clearedLine] on every
     * bullet row in [startRow]..[endRow] that [skipRow] does not name.
     * Block rows are left alone — a block is free Markdown the user wrote
     * as such — and so are other files (only this outline's rows are in
     * the range). The caret keeps its place.
     *
     * Called by `PaneBackingViewModel.clearFormattingInSubtree` once every
     * folder under the item is loaded.
     */
    fun clearFormattingIn(startRow: Int, endRow: Int, skipRow: (Int) -> Boolean) {
        val s = state
        if (!s.isLoaded) return
        val rows = (startRow.coerceAtLeast(0)..endRow.coerceAtMost(s.lines.lastIndex)).filter { row ->
            !skipRow(row) && DocumentLayout.bulletAsteriskColumn(s.lines[row]) >= 0
        }
        clearRows(rows)
    }

    /** Rewrites each of [rows] to its [clearedLine], moving the caret and anchor with their text. */
    private fun clearRows(rows: List<Int>) {
        val s = state
        var cursorCol = s.cursorCol
        var anchorCol = s.anchorCol
        for (row in rows.asReversed()) {
            val original = state.lines[row]
            val (updated, mapCol) = clearedLine(original) ?: continue
            if (updated == original) continue
            document.delete(row, 0, row, original.length)
            document.insertText(row, 0, updated)
            if (row == s.cursorRow) cursorCol = mapCol(cursorCol)
            if (row == s.anchorRow && anchorCol != null) anchorCol = mapCol(anchorCol)
        }
        patch {
            val lines = it.documentState?.lines ?: return@patch it
            fun clamp(row: Int, col: Int): Int =
                lines.getOrNull(row)?.let { l -> col.coerceIn(DocumentLayout.caretStartCol(l), l.length) } ?: col
            it.copy(
                cursorCol = clamp(it.cursorRow, cursorCol),
                anchorCol = anchorCol?.let { c -> it.anchorRow?.let { r -> clamp(r, c) } },
                pendingInlineStyles = emptySet(),
            )
        }
    }

    // --------------------------------------------------------- query helpers

    /**
     * Returns the inline styles common to every model column in the
     * current selection (or enclosing the caret when there is none).
     * Marker columns are skipped.
     */
    fun activeInlineStyles(): Set<InlineStyle> {
        val s = state
        if (!s.isLoaded) return emptySet()
        val sel = selectionOf(s)
        val row = sel?.startRow ?: s.cursorRow
        if (sel != null && sel.startRow != sel.endRow) return emptySet()
        val line = s.lines[row]
        // Code is verbatim: markers in it are not styles.
        if (BlockLayout.isCodeLine(line)) return emptySet()
        val tStart = DocumentLayout.textStartCol(line)
        val linePrefix = LineMarkdownPrefix.detect(line, tStart)
        val inlineStart = linePrefix.markerEnd
        val tokenized = InlineMarkdownTokenizer.tokenize(line.substring(inlineStart))
        val fromTokens = if (sel != null) {
            val from = (sel.startCol - inlineStart).coerceAtLeast(0)
            val to = (sel.endCol - inlineStart).coerceAtLeast(from)
            tokenized.stylesAcross(from, to)
        } else {
            val col = (s.cursorCol - inlineStart).coerceAtLeast(0)
            tokenized.stylesAt(col)
        }
        // A collapsed caret with armed styles should report them as active
        // too, so the dropdown's check-marks match what the next keystroke
        // will produce.
        return if (sel == null) fromTokens + s.pendingInlineStyles else fromTokens
    }

    /**
     * Returns the line-level style on the cursor row, or `null` when the
     * row has no recognised prefix or when a multi-row selection spans
     * rows with mixed prefixes.
     */
    fun activeLineStyle(): LineStyle? {
        val s = state
        if (!s.isLoaded) return null
        val (first, last) = rowSpan(s)
        var common: LineStyle? = null
        for (row in first..last) {
            val line = s.lines[row]
            val tStart = DocumentLayout.textStartCol(line)
            val style = LineMarkdownPrefix.detect(line, tStart).style
            if (row == first) common = style
            else if (style != common) return null
        }
        return common
    }

    /**
     * Whether the Style menu's code item acts on a code block here, and
     * in which state: `true` when the rows it would act on are already
     * code (the item then takes them out), `false` when it would make
     * them a code block, `null` when it is plain inline code (see
     * [codeBlockRows]).
     */
    fun codeBlockState(): Boolean? {
        val s = state
        if (!s.isLoaded) return null
        val rows = codeBlockRows(s) ?: return null
        return rows.all { BlockLayout.isCodeLine(s.lines[it]) }
    }

    // --------------------------------------------------------------- helpers

    /**
     * The rows Inline code acts on as a code block, or `null` when it
     * acts as inline code:
     *  - a selection across two or more rows of one block: those rows (a
     *    selection ending at the very start of a row leaves that row out);
     *  - otherwise, the caret or selection on a code row: that row's whole
     *    run of code rows.
     */
    private fun codeBlockRows(s: PaneBackingViewModel.State): IntRange? {
        val sel = selectionOf(s)
        if (sel != null && sel.startRow != sel.endRow) {
            val block = BlockLayout.rangeAt(s.lines, sel.startRow) ?: return null
            val atRowStart = sel.endCol <= DocumentLayout.textStartCol(s.lines[sel.endRow])
            val last = if (atRowStart) sel.endRow - 1 else sel.endRow
            if (last !in block || last < sel.startRow) return null
            return sel.startRow..last
        }
        val row = sel?.startRow ?: s.cursorRow
        if (!BlockLayout.isCodeLine(s.lines[row])) return null
        val block = BlockLayout.rangeAt(s.lines, row) ?: return null
        var first = row
        while (first - 1 in block && BlockLayout.isCodeLine(s.lines[first - 1])) first--
        var last = row
        while (last + 1 in block && BlockLayout.isCodeLine(s.lines[last + 1])) last++
        return first..last
    }

    /**
     * Makes [rows] (block rows) code rows, [on], or plain rows, by adding
     * or removing the hidden [BlockLayout.CODE] marker after each row's
     * block marker. The caret and anchor move with their row's text.
     */
    private fun setCode(rows: IntRange, on: Boolean) {
        val s = state
        val shift = HashMap<Int, Int>()
        for (r in rows) {
            val line = s.lines[r]
            if (BlockLayout.isCodeLine(line) == on) continue
            val col = BlockLayout.markerColumn(line) + 1
            if (on) document.insertText(r, col, BlockLayout.CODE.toString())
            else document.delete(r, col, r, col + 1)
            shift[r] = if (on) 1 else -1
        }
        if (shift.isEmpty()) return
        fun moved(row: Int, col: Int): Int {
            val d = shift[row] ?: return col
            val markerEnd = BlockLayout.markerColumn(s.lines[row]) + 1
            return if (col >= markerEnd) (col + d).coerceAtLeast(markerEnd) else col
        }
        patch {
            it.copy(
                cursorCol = moved(s.cursorRow, s.cursorCol),
                anchorCol = s.anchorCol?.let { c -> moved(s.anchorRow ?: s.cursorRow, c) },
                pendingInlineStyles = emptySet(),
            )
        }
    }

    companion object {
        /**
         * The row [line] (as `Document.lines` holds it) without formatting:
         * its heading / quote prefix ([LineMarkdownPrefix]) and the
         * [InlineStyle] markers the tokenizer finds
         * ([se.soderbjorn.lunarbor.data.TokenizedLine.styleMarkerCols]) are
         * removed; links, tags, images, entities, a block row's list prefix
         * and everything before the text are kept. A done row stays done
         * (its whole-title strike is put back, [DoneState.withDoneRow]).
         *
         * @return the new line and a map from an old column on it to the new
         *   one, or `null` for a code row (verbatim, never formatted).
         */
        internal fun clearedLine(line: String): Pair<String, (Int) -> Int>? {
            if (BlockLayout.isCodeLine(line)) return null
            val tStart = DocumentLayout.textStartCol(line)
            val listLen = BlockLayout.listPrefixLength(line)
            val removed = HashSet<Int>()
            val inlineStart = if (listLen > 0) {
                tStart + listLen
            } else {
                val prefix = LineMarkdownPrefix.detect(line, tStart)
                if (prefix.style != null) for (c in tStart until prefix.markerEnd) removed += c
                prefix.markerEnd
            }
            val tokens = InlineMarkdownTokenizer.tokenize(line.substring(inlineStart))
            for (c in tokens.styleMarkerCols) removed += inlineStart + c
            if (removed.isEmpty()) return line to { c: Int -> c }
            val cleared = buildString { line.forEachIndexed { i, ch -> if (i !in removed) append(ch) } }
            val done = DoneState.isDoneRow(line)
            val result = if (done) DoneState.withDoneRow(cleared, true) else cleared
            val titleStart = DoneState.titleStartOf(cleared) ?: 0
            val shift = if (done && result != cleared) 2 else 0
            val map = { col: Int ->
                val c = col - removed.count { it < col }
                if (shift > 0 && c > titleStart) c + shift else c
            }
            return result to map
        }
    }

    private fun rowSpan(s: PaneBackingViewModel.State): Pair<Int, Int> {
        val sel = selectionOf(s)
        return if (sel == null) s.cursorRow to s.cursorRow else sel.startRow to sel.endRow
    }

    /**
     * If the collapsed caret in [s] sits inside a tokenized run that has
     * [style] active, returns the absolute model column just past that
     * run's closing marker for [style] — i.e. the column the caret should
     * jump to in order to "exit" the span. Returns `null` when the caret
     * is not inside such a run.
     *
     * Only a single layer of style is unwrapped; if [style] is nested
     * inside another active style on the same run, the caret jumps just
     * past [style]'s closer regardless. The simpler one-layer behavior is
     * what users expect when toggling a single shortcut.
     */
    private fun exitColumnForSurroundingStyle(
        s: PaneBackingViewModel.State,
        style: InlineStyle,
    ): Int? {
        val line = s.lines[s.cursorRow]
        val tStart = DocumentLayout.textStartCol(line)
        val linePrefix = se.soderbjorn.lunarbor.data.LineMarkdownPrefix.detect(line, tStart)
        val inlineStart = linePrefix.markerEnd
        if (s.cursorCol < inlineStart) return null
        val tokens = InlineMarkdownTokenizer.tokenize(line.substring(inlineStart))
        val rel = s.cursorCol - inlineStart
        val run = tokens.runs.firstOrNull { rel >= it.modelStart && rel <= it.modelEnd } ?: return null
        if (style !in run.styles) return null
        return inlineStart + run.modelEnd + style.closeMarker.length
    }
}
