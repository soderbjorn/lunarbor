/*
 * MarkdownStyleViewModel.kt
 * -------------------------
 * Inline / line-level markdown styling slice of the per-viewer ViewModel.
 * Composed by `DocumentViewBackingViewModel`, this class implements:
 *
 *   - [applyInlineStyle]  — wrap or unwrap a selection / word with one of
 *     the inline markers (bold, italic, strike, inline code).
 *   - [applyLineStyle]    — set / replace / remove a line-level prefix
 *     (heading 1–3, quote) on the cursor row or every row in a multi-row
 *     selection.
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

package se.soderbjorn.notegrow.main

import se.soderbjorn.notegrow.data.InlineMarkdownTokenizer
import se.soderbjorn.notegrow.data.InlineStyle
import se.soderbjorn.notegrow.data.LineMarkdownPrefix
import se.soderbjorn.notegrow.data.LineStyle

/**
 * Markdown-styling slice of the per-viewer ViewModel.
 *
 * @param documentBackingViewModel Shared document VM this slice writes to.
 * @param stateProvider Reads the latest aggregate state.
 * @param patch Applies a transform that touches document content; refreshes
 *   the mirrored `documentState` and reconciles.
 * @param selectWord Delegates to `TextEditingViewModel.selectWord` when an
 *   inline style is invoked with no active selection.
 */
internal class MarkdownStyleViewModel(
    private val documentBackingViewModel: DocumentBackingViewModel,
    private val stateProvider: () -> DocumentViewBackingViewModel.State,
    private val patch: ((DocumentViewBackingViewModel.State) -> DocumentViewBackingViewModel.State) -> Unit,
    private val selectWord: (row: Int, col: Int) -> Unit,
) {
    private val state: DocumentViewBackingViewModel.State get() = stateProvider()

    // ---------------------------------------------------------------- inline

    /**
     * Toggle [style] on the current selection, or on the word at the
     * cursor when the selection is empty. No-op for multi-row selections
     * (v1 limitation — wrapping markers across line boundaries is rarely
     * what users want).
     *
     * Toggle semantics:
     * - When the selection is exactly the inside of an existing run with
     *   this style, the markers are stripped.
     * - When the selection is wrapped by this style's open / close markers
     *   immediately on either side, those markers are stripped.
     * - Otherwise the markers are inserted around the selection.
     */
    fun applyInlineStyle(style: InlineStyle) {
        val s = state
        if (!s.isLoaded) return

        val sel = selectionOf(s)
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

        // Detect "selection is wrapped by markers" → strip them.
        val wrappedByMarkers = sel.startCol >= open.length &&
            sel.endCol + close.length <= line.length &&
            line.regionMatches(sel.startCol - open.length, open, 0, open.length) &&
            line.regionMatches(sel.endCol, close, 0, close.length)

        if (wrappedByMarkers) {
            // Delete close marker first (later in the line) so start positions are stable.
            documentBackingViewModel.delete(row, sel.endCol, row, sel.endCol + close.length)
            documentBackingViewModel.delete(row, sel.startCol - open.length, row, sel.startCol)
            patch {
                it.copy(
                    anchorRow = row, anchorCol = sel.startCol - open.length,
                    cursorRow = row, cursorCol = sel.endCol - open.length,
                )
            }
            return
        }

        // Detect "selection equals the *interior* of a run with this style on the line"
        // — means user has the inside of `**bold**` selected exactly. Same handling as
        // "wrappedByMarkers" above; we already covered it.

        // Otherwise wrap.
        val selectedText = line.substring(sel.startCol, sel.endCol)
        documentBackingViewModel.delete(row, sel.startCol, row, sel.endCol)
        documentBackingViewModel.insertText(row, sel.startCol, open + selectedText + close)
        patch {
            it.copy(
                anchorRow = row, anchorCol = sel.startCol + open.length,
                cursorRow = row, cursorCol = sel.startCol + open.length + selectedText.length,
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

        // Determine toggle-off vs apply.
        val allHaveTarget = (firstRow..lastRow).all { row ->
            val tStart = DocumentLayout.textStartCol(lines[row])
            LineMarkdownPrefix.detect(lines[row], tStart).style == style
        }

        for (row in lastRow downTo firstRow) {
            val original = state.lines[row]
            val tStart = DocumentLayout.textStartCol(original)
            val updated = if (allHaveTarget) {
                LineMarkdownPrefix.remove(original, tStart)
            } else {
                LineMarkdownPrefix.apply(original, style, tStart)
            }
            if (updated == original) continue
            documentBackingViewModel.delete(row, 0, row, original.length)
            documentBackingViewModel.insertText(row, 0, updated)
        }
        patch { it }
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

    // --------------------------------------------------------------- helpers

    private fun rowSpan(s: DocumentViewBackingViewModel.State): Pair<Int, Int> {
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
        s: DocumentViewBackingViewModel.State,
        style: InlineStyle,
    ): Int? {
        val line = s.lines[s.cursorRow]
        val tStart = DocumentLayout.textStartCol(line)
        val linePrefix = se.soderbjorn.notegrow.data.LineMarkdownPrefix.detect(line, tStart)
        val inlineStart = linePrefix.markerEnd
        if (s.cursorCol < inlineStart) return null
        val tokens = InlineMarkdownTokenizer.tokenize(line.substring(inlineStart))
        val rel = s.cursorCol - inlineStart
        val run = tokens.runs.firstOrNull { rel >= it.modelStart && rel <= it.modelEnd } ?: return null
        if (style !in run.styles) return null
        return inlineStart + run.modelEnd + style.closeMarker.length
    }
}
