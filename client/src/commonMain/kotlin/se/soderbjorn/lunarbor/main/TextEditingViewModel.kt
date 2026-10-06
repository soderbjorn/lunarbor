/*
 * TextEditingViewModel.kt
 * -----------------------
 * Owns text-editing intents (typing, deletion, indent/outdent, paste,
 * cursor and selection movement, copy/cut text extraction). Operates on
 * the shared `PaneBackingViewModel.State` through the small set
 * of mutators ([apply], [patch], [mutate]) supplied by the aggregate
 * `PaneBackingViewModel`. Reads the canonical document via the injected
 * `documentProvider` lambda — the pane swaps the underlying [Document]
 * on cross-file navigation, so this slice never holds a direct ref.
 *
 * Bullets only (TRF-4): in an outline document ([Document.bulletsOnly])
 * no intent here produces a non-bullet line. Enter on an empty bullet
 * outdents it or opens another bullet, Backspace at a bullet's start
 * merges or deletes it, paste makes one bullet per line, and deleting a
 * selection keeps the first row's marker. Plain Markdown files keep the
 * plain-line behaviors (strip the marker, paste verbatim).
 *
 * Blocks (TRF-5, see [BlockLayout]): block rows are the one other kind of
 * outline line. Inside a block Enter adds a block row, Backspace merges
 * rows of the block (and deletes an empty block), paste keeps the pasted
 * lines verbatim as block rows, and Tab moves the whole block. The block
 * intents — [insertBlock], [deleteBlockAt], [convertBlockToNodesAt],
 * [exitBlock] — live here too.
 *
 * Privacy modes (LBR-10): rows the app's mode hides ([hiddenRowsIn]) are
 * never touched. Deleting a selection across them deletes only the visible
 * rows around them, and keeps a visible item that has hidden content under
 * it ([isProtectedRow]); Tab and Shift-Tab next to a hidden sibling move the
 * item past it rather than re-parent it; copy leaves hidden rows out. What
 * slips through is caught by the pane's check after every edit.
 *
 * commonMain only — no DOM, Android UI, or UIKit imports. The class holds
 * no state of its own; cursor and selection live in the aggregate's
 * single `MutableStateFlow`.
 */

package se.soderbjorn.lunarbor.main

import se.soderbjorn.lunarbor.data.LunarborLink
import se.soderbjorn.lunarbor.data.LunicleNode
import se.soderbjorn.lunarbor.data.InlineMarkdownTokenizer
import se.soderbjorn.lunarbor.data.InlineStyle
import se.soderbjorn.lunarbor.data.LineMarkdownPrefix
import se.soderbjorn.lunarbor.main.PaneBackingViewModel.Companion.TAB_SIZE

/**
 * Text-editing slice of the per-pane ViewModel. Composed by
 * `PaneBackingViewModel` which owns the state flow; this class
 * implements the actual editing behavior.
 *
 * @param documentProvider Returns the [Document] the pane currently
 *   has acquired. Called on every edit so a pane swap (between two
 *   files) is transparent to this slice.
 * @param stateProvider Reads the latest aggregate state.
 * @param applyState Replaces the aggregate state without re-running the
 *   reconcile pass (used by the few intents that need a tight write).
 * @param mutate Applies a transform to the current state and reconciles.
 * @param patch Applies a transform that touches document content; refreshes
 *   the mirrored `documentState` and reconciles.
 * @param isProtectedRow `true` for a visible item that must not be deleted
 *   because content the privacy mode hides lives under it
 *   (`PaneBackingViewModel.isProtectedRow`); always `false` with no mode on.
 */
internal class TextEditingViewModel(
    private val documentProvider: () -> Document,
    private val stateProvider: () -> PaneBackingViewModel.State,
    @Suppress("unused") private val applyState: (PaneBackingViewModel.State) -> Unit,
    private val mutate: ((PaneBackingViewModel.State) -> PaneBackingViewModel.State) -> Unit,
    private val patch: ((PaneBackingViewModel.State) -> PaneBackingViewModel.State) -> Unit,
    private val isProtectedRow: (PaneBackingViewModel.State, Int) -> Boolean = { _, _ -> false },
) {
    private val state: PaneBackingViewModel.State
        get() = stateProvider()

    private val document: Document get() = documentProvider()

    // ------------------------------------------------------------------ edits

    fun insertChar(char: Char) {
        if (!state.isLoaded) return
        deleteSelectionIfAny()
        insertWithPendingStyles(char.toString())
    }

    fun insertNewline() {
        if (!state.isLoaded) return
        deleteSelectionIfAny()

        // Empty leaf bullet. In an outline (bullets only, TRF-4) Enter
        // outdents it one level when it is the last of its siblings, the
        // usual outliner "step out of the list" gesture; everywhere else
        // it falls through and opens another bullet — a node never gets a
        // non-bullet line. Plain Markdown files keep the Markdown-editor
        // behavior of stripping the marker in place.
        if (endListInBlockIfAny()) return
        if (endCodeInBlockIfAny()) return
        // Caret at the very start of a block: a new bullet above it, as at
        // the start of a bullet — the way to put an item before a block.
        if (s0AtBlockStart()) {
            exitBlockAbove()
            return
        }
        if (document.bulletsOnly) {
            if (outdentEmptyLastChildIfAny()) return
        } else if (exitListOnEmptyBulletIfAny()) {
            return
        }

        // Caret at the very start of a bullet's text: open an empty
        // sibling *above* and leave the node — and its entire subtree —
        // untouched (Workflowy-style). Without this, the split paths
        // below would move the whole title onto the new row, demoting it
        // and re-parenting its children onto the leftover empty bullet.
        if (insertSiblingAboveAtTextStartIfAny()) return

        // Bullet with a *folded* subtree: a plain split would drop the new
        // row between the bullet and its hidden children — silently
        // re-parenting the subtree onto the fresh row and stranding the
        // caret on a row the paint loop won't emit. Insert the new sibling
        // after the whole subtree instead (visually directly below).
        if (insertSiblingAfterCollapsedSubtreeIfAny()) return

        // Three cases for inline-style preservation across the line break:
        //
        //   (1) Caret strictly inside an existing tokenized span — e.g.
        //       user clicked between `f|oo` of saved `**foo**bar**`. The
        //       split would orphan the original closers on row N+1; fix by
        //       sealing the span on row N (insert closers at caret) and
        //       balancing row N+1 with a fresh opener at its start.
        //   (2) Pending styles armed (Cmd-B then typing or empty caret).
        //       Carry the armed set across the break and let the user's
        //       next keystroke wrap via [insertWithPendingStyles] — no
        //       markers inserted on row N+1 (avoids stray empty `****`
        //       spans).
        //   (3) No styles. Plain newline.
        val s0 = state
        val tokenizedAtCaret = tokenizedStylesAtCaret(s0)

        if (tokenizedAtCaret.isNotEmpty()) {
            insertNewlineSplittingSpan(s0, tokenizedAtCaret)
            return
        }
        if (s0.pendingInlineStyles.isNotEmpty()) {
            insertNewlineCarryingPending(s0, s0.pendingInlineStyles)
            return
        }
        insertNewlinePlain(s0)
    }

    /**
     * Case (1): caret strictly inside a saved styled span. Close the span
     * on row N by inserting the closer markers at the caret, insert the
     * newline + bullet prefix, then insert the opener markers at the start
     * of row N+1's inline content so the original closers (now dangling on
     * row N+1) are re-balanced by a fresh opener. `pendingInlineStyles`
     * stays empty — the on-line markers handle styling, no need to arm.
     */
    private fun insertNewlineSplittingSpan(s0: PaneBackingViewModel.State, styles: Set<InlineStyle>) {
        val ordered = InlineStyle.entries.filter { it in styles }
        val openers = ordered.joinToString("") { it.openMarker }
        val closers = ordered.reversed().joinToString("") { it.closeMarker }

        document.insertText(s0.cursorRow, s0.cursorCol, closers)
        patch { it.copy(cursorCol = it.cursorCol + closers.length) }

        val s1 = state
        val bulletPrefix = newlineBulletPrefix(s1)
        val nlResult = document.insertText(
            s1.cursorRow, s1.cursorCol, "\n" + bulletPrefix
        )
        val openResult = document.insertText(
            nlResult.endRow, nlResult.endCol, openers
        )
        patch {
            it.copy(
                cursorRow = openResult.endRow, cursorCol = openResult.endCol,
                anchorRow = null, anchorCol = null,
                pendingInlineStyles = emptySet(),
            )
        }
    }

    /**
     * Case (2): pending inline styles are armed. Two sub-cases by whether
     * close markers already sit at the caret:
     *   - "armed-typing" (`**foo|**`): the user typed inside the armed pair
     *     so `**` follows the caret. Step over the closers so they stay on
     *     row N attached to `**foo`.
     *   - "armed-empty" or "armed-mid-text": no markers near the caret yet
     *     (Cmd-B with empty selection, or armed with no content typed). No
     *     closers to step over.
     * In both sub-cases, insert the newline + bullet prefix and arm
     * `pendingInlineStyles = pending`. We do NOT insert opener/closer
     * markers on row N+1 — the next keystroke is wrapped by
     * [insertWithPendingStyles], which keeps row N+1 free of stray empty
     * spans.
     */
    private fun insertNewlineCarryingPending(s0: PaneBackingViewModel.State, pending: Set<InlineStyle>) {
        val ordered = InlineStyle.entries.filter { it in pending }
        val closers = ordered.reversed().joinToString("") { it.closeMarker }
        val line0 = s0.lines[s0.cursorRow]
        val closersAlreadyAtCaret = closers.isNotEmpty() &&
            s0.cursorCol + closers.length <= line0.length &&
            line0.regionMatches(s0.cursorCol, closers, 0, closers.length)
        if (closersAlreadyAtCaret) {
            patch { it.copy(cursorCol = it.cursorCol + closers.length) }
        }

        val s1 = state
        val bulletPrefix = newlineBulletPrefix(s1)
        val nlResult = document.insertText(
            s1.cursorRow, s1.cursorCol, "\n" + bulletPrefix
        )
        patch {
            it.copy(
                cursorRow = nlResult.endRow, cursorCol = nlResult.endCol,
                anchorRow = null, anchorCol = null,
                pendingInlineStyles = pending,
            )
        }
    }

    /**
     * Enter on an empty leaf bullet in an outline: when the bullet is the
     * last of its siblings and may be outdented (it sits deeper than the
     * top level of the document or of the zoom), outdent it one level via
     * [outdentLine] and return `true`.
     *
     * Returns `false` — so Enter opens a new bullet as usual — when the
     * row is not an empty leaf bullet, is already at the top level, or has
     * a following sibling. Outdenting a bullet with siblings below it
     * would silently re-parent those siblings under the empty row.
     */
    private fun outdentEmptyLastChildIfAny(): Boolean {
        val s = state
        val row = s.cursorRow
        val line = s.lines[row]
        if (!DocumentLayout.isEmptyBulletLine(line)) return false
        val bulletCol = DocumentLayout.bulletAsteriskColumn(line)
        if (DocumentLayout.hasChildren(s.lines, row, bulletCol)) return false
        val zoom = zoomInfoOf(s)
        val minAllowed = if (zoom != null) zoom.zoomIndent + TAB_SIZE else 0
        if (bulletCol - TAB_SIZE < minAllowed) return false
        val next = s.lines.getOrNull(row + 1)
        val regionEnd = zoom?.endRowInclusive ?: s.lines.lastIndex
        if (next != null && row + 1 <= regionEnd && DocumentLayout.indentOf(next) >= bulletCol) return false
        outdentLine()
        return true
    }

    /**
     * Plain (non-outline) files only: if the caret sits on an empty leaf
     * bullet, strip the `"* "` marker so the row becomes a plain blank
     * line — the Markdown-editor "exit the list" gesture. Returns `true`
     * when it consumed the Enter. Never used in an outline, where every
     * line stays a bullet (see [Document.bulletsOnly]).
     */
    private fun exitListOnEmptyBulletIfAny(): Boolean {
        val s = state
        val line = s.lines[s.cursorRow]
        if (!DocumentLayout.isEmptyBulletLine(line)) return false
        val bulletCol = DocumentLayout.bulletAsteriskColumn(line)
        if (DocumentLayout.hasChildren(s.lines, s.cursorRow, bulletCol)) return false
        document.delete(s.cursorRow, bulletCol, s.cursorRow, line.length)
        patch {
            it.copy(
                cursorCol = bulletCol,
                anchorRow = null, anchorCol = null,
                pendingInlineStyles = emptySet(),
            )
        }
        return true
    }

    /**
     * Handles Enter with the caret at the start of a bullet's text (at
     * [DocumentLayout.caretStartCol], i.e. right after the `"* "` marker
     * and any hidden line-level markdown prefix). Inserts an empty
     * sibling bullet *above* the caret row at the same indent via
     * [Document.insertLine] and moves the caret reference down one row —
     * the node's line content, [LineId], fold state, and subtree are all
     * untouched.
     *
     * Splitting in place here would be wrong in every variant: with an
     * expanded subtree the first-child rule in [newlineBulletPrefix]
     * demotes the entire title one level and re-parents the children onto
     * the leftover empty bullet; with a folded subtree
     * [insertSiblingAfterCollapsedSubtreeIfAny] ships the title below the
     * subtree, likewise orphaning the children.
     *
     * Skipped (falls through to the paths below) when:
     *   - the row is not a bullet, or the caret sits past the text start;
     *   - the caret row is the zoom root — a sibling above the zoom
     *     target would land outside the zoom region, invisibly.
     *
     * An empty bullet reaches here when [outdentEmptyLastChildIfAny]
     * declined it (top level, or siblings below); the sibling above then
     * reads as "Enter opened another bullet". An empty bullet with
     * children gets the sibling above too — preferable to the old
     * first-child split, which would re-parent its subtree.
     *
     * Returns `true` when it consumed the Enter.
     */
    private fun insertSiblingAboveAtTextStartIfAny(): Boolean {
        val s = state
        val line = s.lines[s.cursorRow]
        val bulletCol = DocumentLayout.bulletAsteriskColumn(line)
        if (bulletCol < 0) return false
        if (s.cursorCol > DocumentLayout.caretStartCol(line)) return false
        val zoom = zoomInfoOf(s)
        // Only the zoomed item itself is off limits. `zoom.startRow` is
        // the zoom's *first child* for a bullet zoom — refusing that row
        // sent Enter at its start to the plain split, which left the
        // node's row (its folder, its children) on the emptied line and
        // moved its text to a new row below.
        if (zoom != null && s.cursorRow <= zoom.zoomRow) return false
        document.insertLine(s.cursorRow, line.substring(0, bulletCol) + "* ")
        patch {
            it.copy(
                cursorRow = it.cursorRow + 1,
                anchorRow = null, anchorCol = null,
                pendingInlineStyles = emptySet(),
            )
        }
        return true
    }

    /**
     * Enter on an empty list item inside a block (its content is nothing
     * but the `* ` / `- ` prefix): ends the list by removing the prefix,
     * leaving an empty plain row in the block — as in any Markdown
     * editor. Returns `true` when it consumed the Enter.
     */
    private fun endListInBlockIfAny(): Boolean {
        val s = state
        val line = s.lines[s.cursorRow]
        val listPrefix = BlockLayout.listPrefixLength(line)
        if (listPrefix == 0) return false
        val textStart = DocumentLayout.textStartCol(line)
        if (line.length != textStart + listPrefix) return false
        document.delete(s.cursorRow, textStart, s.cursorRow, line.length)
        patch { it.copy(cursorCol = textStart, anchorRow = null, anchorCol = null) }
        return true
    }

    /**
     * Enter on an empty last row of a code block inside a block: ends the
     * code by turning that row into a plain row of the block (drops its
     * hidden [BlockLayout.CODE] marker) — the way an empty list item ends
     * a list. Returns `true` when it consumed the Enter.
     */
    private fun endCodeInBlockIfAny(): Boolean {
        val s = state
        val row = s.cursorRow
        val line = s.lines[row]
        if (!BlockLayout.isCodeLine(line) || BlockLayout.textOf(line).isNotEmpty()) return false
        val next = s.lines.getOrNull(row + 1)
        val block = BlockLayout.rangeAt(s.lines, row) ?: return false
        if (next != null && row + 1 in block && BlockLayout.isCodeLine(next)) return false
        val col = BlockLayout.markerColumn(line) + 1
        document.delete(row, col, row, col + 1)
        patch { it.copy(cursorCol = col, anchorRow = null, anchorCol = null, pendingInlineStyles = emptySet()) }
        return true
    }

    /** Case (3): no inline styles at the caret. Plain newline + bullet continuation. */
    private fun insertNewlinePlain(s0: PaneBackingViewModel.State) {
        val bulletPrefix = newlineBulletPrefix(s0)
        val result = document.insertText(s0.cursorRow, s0.cursorCol, "\n" + bulletPrefix)
        patch {
            it.copy(
                cursorRow = result.endRow, cursorCol = result.endCol,
                anchorRow = null, anchorCol = null,
                pendingInlineStyles = emptySet(),
            )
        }
    }

    /**
     * Bullet prefix for the row Enter creates at the caret. Normally the
     * caret row's own indent + `"* "` (sibling continuation — see
     * [continuationBulletPrefix]); when the caret row is a bullet with an
     * *expanded* subtree the new row instead becomes the subtree's first
     * child, matching the existing first child's indent. A sibling
     * inserted directly below such a parent would sit between the parent
     * and its children and silently re-parent the whole subtree onto the
     * fresh (usually empty) row — Workflowy-style first-child insertion
     * keeps the children attached to the row the user split.
     *
     * The folded-subtree case never reaches here:
     * [insertSiblingAfterCollapsedSubtreeIfAny] consumes the Enter first.
     * Likewise caret-at-text-start: [insertSiblingAboveAtTextStartIfAny]
     * consumes it (except on the zoom root), so the split below always
     * leaves some of the bullet's own text on the caret row.
     */
    private fun newlineBulletPrefix(s: PaneBackingViewModel.State): String {
        val line = s.lines[s.cursorRow]
        val base = continuationBulletPrefix(line, s.cursorCol)
        if (base.isEmpty()) return base
        val bulletCol = DocumentLayout.bulletAsteriskColumn(line)
        if (!DocumentLayout.hasChildren(s.lines, s.cursorRow, bulletCol)) return base
        val childCol = DocumentLayout.indentOf(s.lines[s.cursorRow + 1])
        return " ".repeat(childCol) + "* "
    }

    /**
     * Handles Enter on a bullet whose within-file subtree is folded (its
     * [LineId] is in `collapsedIds` while the children are physically
     * present in `lines`). Splitting in place would land the new row
     * inside the hidden region — silently re-parenting the children onto
     * it and stranding the caret on a row the paint loop won't emit — so
     * instead:
     *   - Normal case: any text after the caret moves onto a new *sibling*
     *     bullet inserted after the whole subtree, visually directly
     *     below the collapsed parent, with the caret at its text start.
     *   - Caret row is the zoom root: a sibling would fall outside the
     *     zoom region (reconcile's clamp would yank the caret back), so
     *     insert a first child and reveal the subtree in the same patch.
     *
     * Folded *promoted refs* never reach the sibling path: their children
     * are unspliced from `lines` while folded, so
     * [DocumentLayout.hasChildren] is `false` and Enter behaves as on any
     * childless bullet. Caret-at-text-start is consumed earlier by
     * [insertSiblingAboveAtTextStartIfAny] except on the zoom root, so
     * outside a zoom the tail moved below the subtree is always a proper
     * suffix of the bullet's text, never the whole title. Returns `true`
     * when it consumed the Enter.
     */
    private fun insertSiblingAfterCollapsedSubtreeIfAny(): Boolean {
        val s = state
        val row = s.cursorRow
        val line = s.lines[row]
        val bulletCol = DocumentLayout.bulletAsteriskColumn(line)
        if (bulletCol < 0 || s.cursorCol <= bulletCol + 1) return false
        if (!DocumentLayout.hasChildren(s.lines, row, bulletCol)) return false
        val id = s.documentState?.lineIds?.getOrNull(row) ?: return false
        if (id !in s.collapsedIds) return false

        val zoom = zoomInfoOf(s)
        // The zoomed item itself (not `zoom.startRow`, the zoom's first
        // child, whose subtree ends inside the zoom like any sibling's).
        if (zoom != null && row == zoom.zoomRow) {
            val childCol = DocumentLayout.indentOf(s.lines[row + 1])
            val result = document.insertText(row, s.cursorCol, "\n" + " ".repeat(childCol) + "* ")
            patch {
                it.copy(
                    cursorRow = result.endRow, cursorCol = result.endCol,
                    anchorRow = null, anchorCol = null,
                    pendingInlineStyles = emptySet(),
                    collapsedIds = it.collapsedIds - id,
                )
            }
            return true
        }

        // The delete only touches columns on [row], so the subtree end and
        // the pre-edit snapshot's line at [end] stay valid for the insert.
        val end = DocumentLayout.subtreeEnd(s.lines, row, bulletCol)
        val tail = line.substring(s.cursorCol)
        if (tail.isNotEmpty()) document.delete(row, s.cursorCol, row, line.length)
        val prefix = line.substring(0, bulletCol) + "* "
        document.insertText(end, s.lines[end].length, "\n" + prefix + tail)
        patch {
            it.copy(
                cursorRow = end + 1, cursorCol = prefix.length,
                anchorRow = null, anchorCol = null,
                pendingInlineStyles = emptySet(),
            )
        }
        return true
    }

    /**
     * Inline styles of the tokenized run that strictly encloses the caret
     * on its current row. Boundary positions (caret on a marker edge or
     * between two runs) report empty — see
     * [se.soderbjorn.lunarbor.data.TokenizedLine.stylesAt].
     */
    private fun tokenizedStylesAtCaret(s: PaneBackingViewModel.State): Set<InlineStyle> {
        val line = s.lines[s.cursorRow]
        val tStart = DocumentLayout.textStartCol(line)
        val linePrefix = LineMarkdownPrefix.detect(line, tStart)
        val inlineStart = linePrefix.markerEnd
        val inlineText = if (inlineStart >= line.length) "" else line.substring(inlineStart)
        val tokenized = InlineMarkdownTokenizer.tokenize(inlineText)
        val col = (s.cursorCol - inlineStart).coerceAtLeast(0)
        return tokenized.stylesAt(col)
    }

    /**
     * Types or pastes [text] at the caret, replacing any selection. When
     * [text] is exactly the last cut, the document re-attaches the cut
     * bullets' folders to the pasted rows ([Document.adoptCut]).
     *
     * In an outline ([Document.bulletsOnly]) multi-line text is first run
     * through [bulletLinesForPaste], so every pasted line lands as its own
     * bullet: the first line joins the caret row, each further line
     * becomes a bullet at the caret row's depth (or deeper, keeping the
     * pasted text's own nesting). Blank lines are dropped. Inside a block
     * the lines instead become block rows, verbatim ([blockLinesForPaste]).
     */
    fun insertText(text: String) {
        if (!state.isLoaded) return
        deleteSelectionIfAny()
        val startRow = state.cursorRow
        val multiLine = '\n' in text || '\r' in text
        val blockCol = BlockLayout.markerColumn(state.lines[startRow])
        val toInsert = when {
            multiLine && blockCol >= 0 ->
                blockLinesForPaste(text, blockCol, code = BlockLayout.isCodeLine(state.lines[startRow]))
            multiLine && document.bulletsOnly -> {
                val s = state
                val bulletCol = DocumentLayout.bulletAsteriskColumn(s.lines[startRow]).coerceAtLeast(0)
                // A bullet whose children the privacy mode hides: the pasted
                // lines become its first children, so the hidden ones keep
                // their parent (as Enter does on any parent).
                val baseIndent = if (PrivacyLayout.hasHiddenDescendants(s.lines, hiddenRowsIn(s), startRow)) {
                    DocumentLayout.indentOf(s.lines[startRow + 1])
                } else bulletCol
                bulletLinesForPaste(text, baseIndent)
            }
            else -> text
        }
        if (toInsert.isEmpty()) return
        insertWithPendingStyles(toInsert)
        // The cut record matches on the clipboard text, row by row. The
        // normalized text has the same rows as a cut of whole bullets
        // (no blank lines to drop), so offsets still line up.
        if (lineCount(toInsert) == lineCount(text)) document.adoptCut(startRow, text)
    }

    private fun lineCount(text: String): Int =
        text.replace("\r\n", "\n").count { it == '\n' || it == '\r' } + 1

    /**
     * Inserts [text] at the caret verbatim, **without** wrapping it in
     * markers for any armed [PaneBackingViewModel.State.pendingInlineStyles].
     * Used for structural insertions that carry their own syntax — most
     * notably the markdown link emitted by Insert Link — where the standard
     * typing path's "wrap the next run in pending markers" behaviour would
     * embed the structural text inside (say) `` ` … ` `` and silently turn
     * it into an inline code span.
     *
     * The pending style set is cleared in the same patch: those styles
     * were conceptually armed for "the next thing the user inserts", and a
     * structural insert is reasonable to count as that input. Leaving them
     * armed would wrap the *next* typed character — more surprising than
     * clearing.
     *
     * @param text Literal text to insert at the caret. Must not contain
     *   any markup the caller doesn't intend to land in the document.
     */
    fun insertLiteralText(text: String) {
        if (!state.isLoaded) return
        deleteSelectionIfAny()
        val s = state
        val result = document.insertText(s.cursorRow, s.cursorCol, text)
        patch {
            it.copy(
                cursorRow = result.endRow,
                cursorCol = result.endCol,
                anchorRow = null,
                anchorCol = null,
                pendingInlineStyles = emptySet(),
            )
        }
    }

    /**
     * Inserts [text] at the caret, honoring any [PaneBackingViewModel.State.pendingInlineStyles]
     * by wrapping the inserted text with the matching markers. Keeps the
     * pending set armed across consecutive insertions so continuous typing
     * extends the styled span — the second character lands inside the
     * already-open markers (the caret sits between text and closer) so we
     * just insert plainly and the existing closers shift right.
     *
     * The pending set is cleared on cursor movement, on selection
     * changes, and on Enter — see [moved], [setSelection], and
     * [insertNewline].
     */
    private fun insertWithPendingStyles(text: String) {
        val s = state
        val pending = s.pendingInlineStyles
        if (pending.isEmpty()) {
            val result = document.insertText(s.cursorRow, s.cursorCol, text)
            patch { it.copy(cursorRow = result.endRow, cursorCol = result.endCol, anchorRow = null, anchorCol = null) }
            return
        }
        val ordered = se.soderbjorn.lunarbor.data.InlineStyle.entries.filter { it in pending }
        val closers = ordered.reversed().joinToString("") { it.closeMarker }
        val line = s.lines[s.cursorRow]
        val alreadyInsideOpenSpan = s.cursorCol + closers.length <= line.length &&
            line.regionMatches(s.cursorCol, closers, 0, closers.length)
        if (alreadyInsideOpenSpan) {
            // Continuing to type inside the markers we just opened: the
            // closers already sit at the caret, so a plain insert grows
            // the styled span and the close markers shift right naturally.
            val result = document.insertText(s.cursorRow, s.cursorCol, text)
            patch {
                it.copy(
                    cursorRow = result.endRow, cursorCol = result.endCol,
                    anchorRow = null, anchorCol = null,
                    // Keep the pending set armed so the *next* keystroke
                    // also extends instead of opening a fresh pair.
                )
            }
            return
        }
        // Fresh start: wrap the inserted text with the markers. Outer-most
        // marker = first entry in InlineStyle.entries.
        val openers = ordered.joinToString("") { it.openMarker }
        val wrapped = openers + text + closers
        val result = document.insertText(s.cursorRow, s.cursorCol, wrapped)
        patch {
            it.copy(
                cursorRow = result.endRow,
                cursorCol = result.endCol - closers.length,
                anchorRow = null, anchorCol = null,
            )
        }
    }

    fun backspace() {
        if (!state.isLoaded) return
        if (deleteSelectionIfAny()) return
        val s = state
        when {
            s.cursorCol > 0 -> {
                val line = s.lines[s.cursorRow]
                val leadingSpaces = line.takeWhile { it == ' ' }.length
                val textStart = DocumentLayout.textStartCol(line)
                val caretStart = DocumentLayout.caretStartCol(line)
                if (s.cursorCol == caretStart && caretStart > textStart) {
                    // Caret sits just after a hidden line-level markdown prefix
                    // (`# `, `## `, `### `, `> `). The prefix is invisible to
                    // the user, so deleting one character would silently strip
                    // the trailing space and surface the marker — surprising the
                    // user. Remove the entire prefix in a single keystroke so
                    // the line "demotes" cleanly back to plain text.
                    document.delete(s.cursorRow, textStart, s.cursorRow, caretStart)
                    patch { it.copy(cursorCol = textStart) }
                    return
                }
                if (BlockLayout.isBlockLine(line) && s.cursorCol == textStart) {
                    backspaceAtBlockRowStart(s)
                    return
                }
                if (isAtBulletMarkerEnd(line, s.cursorCol)) {
                    // Caret at the first text position of a bullet. Deleting here would
                    // orphan any subtree this bullet anchors, so refuse on non-leaf
                    // bullets — the user must remove (or merge away) the children first
                    // (deliberate action, no accidental detachment).
                    val bulletCol = DocumentLayout.bulletAsteriskColumn(line)
                    if (DocumentLayout.hasChildren(s.lines, s.cursorRow, bulletCol)) return
                    // Leaf bullet: merge the row into the previous line — delete the
                    // newline + indent + `"* "` marker so the bullet's remaining text
                    // joins the end of the previous line and the caret lands there
                    // (the standard outliner backspace-join; an empty bullet simply
                    // disappears). Only when the previous *visible* row is the
                    // array-adjacent row: merging across a folded subtree would pull
                    // content into rows the user can't see (same hazard as the
                    // cursorRow > 0 branch below). [prevVisibleRow] is zoom-clamped,
                    // so at the top of a zoom this is never taken.
                    if (prevVisibleRow(s, s.cursorRow) == s.cursorRow - 1 &&
                        BlockLayout.isBlockLine(s.lines[s.cursorRow - 1])
                    ) {
                        // The row above is the last row of a block. A bullet never
                        // merges into a block: an empty bullet just goes, the caret
                        // landing at the end of the block; a non-empty one stays.
                        if (DocumentLayout.isEmptyBulletLine(line)) {
                            document.deleteLine(s.cursorRow)
                            val prevLen = s.lines[s.cursorRow - 1].length
                            patch { it.copy(cursorRow = s.cursorRow - 1, cursorCol = prevLen) }
                        }
                        return
                    }
                    if (prevVisibleRow(s, s.cursorRow) == s.cursorRow - 1) {
                        val previousLen = s.lines[s.cursorRow - 1].length
                        document.delete(s.cursorRow - 1, previousLen, s.cursorRow, s.cursorCol)
                        patch { it.copy(cursorRow = s.cursorRow - 1, cursorCol = previousLen) }
                        return
                    }
                    // Nothing adjacent to merge into. An outline never unbullets the
                    // row (TRF-4): an empty bullet is deleted, a non-empty one stays.
                    if (document.bulletsOnly) {
                        deleteEmptyBulletWithoutMerge(s)
                        return
                    }
                    // Plain Markdown file: fall through and remove just the `"* "`
                    // marker, leaving any indent and trailing content intact and the
                    // cursor at the indent column.
                }
                val removed = when {
                    isAtBulletMarkerEnd(line, s.cursorCol) -> 2
                    s.cursorCol <= leadingSpaces && s.cursorCol % TAB_SIZE == 0 -> TAB_SIZE
                    else -> 1
                }
                val newCol = s.cursorCol - removed
                document.delete(s.cursorRow, newCol, s.cursorRow, s.cursorCol)
                patch { it.copy(cursorCol = newCol) }
            }
            s.cursorRow > 0 -> {
                val zoom = zoomInfoOf(s)
                if (zoom != null && s.cursorRow <= zoom.startRow) {
                    return
                }
                // Refuse to merge across a hidden row: the array-adjacent row may
                // sit inside a collapsed subtree or folded promoted-ref, in which
                // case the merge would silently pull content into a row the user
                // can't see *and* strand the caret on a row the paint loop won't
                // emit — `applyDomSelection` then can't anchor, the browser is
                // left with a dangling caret, and the next Backspace falls back
                // to its default history-back behavior (which navigates the SPA
                // to the parent file). Make the user expand the parent first.
                val prevVisible = prevVisibleRow(s, s.cursorRow) ?: return
                if (prevVisible != s.cursorRow - 1) return
                val previousLen = s.lines[prevVisible].length
                document.delete(prevVisible, previousLen, s.cursorRow, 0)
                patch { it.copy(cursorRow = prevVisible, cursorCol = previousLen) }
            }
        }
    }

    /**
     * Backspace with the caret at the text start of block row
     * `s.cursorRow` (right after the hidden marker):
     *
     *  - The first row of a code block leaves the code: it becomes a
     *    plain row of the block.
     *  - A further row of the block merges into the row above, like
     *    joining two lines of a paragraph.
     *  - The first row of an empty block (every row blank) deletes the
     *    whole block ([deleteBlockAt]).
     *  - The first row of a non-empty block does nothing: the block never
     *    merges into the bullet above.
     *
     * Called only from [backspace].
     */
    private fun backspaceAtBlockRowStart(s: PaneBackingViewModel.State) {
        val row = s.cursorRow
        val range = BlockLayout.rangeAt(s.lines, row) ?: return
        // The first row of a code block: Backspace takes it out of the
        // code (drops the hidden marker) instead of merging it upward.
        val line = s.lines[row]
        if (BlockLayout.isCodeLine(line) && !(row > range.first && BlockLayout.isCodeLine(s.lines[row - 1]))) {
            val col = BlockLayout.markerColumn(line) + 1
            document.delete(row, col, row, col + 1)
            patch { it.copy(cursorCol = col, anchorRow = null, anchorCol = null) }
            return
        }
        if (row > range.first) {
            val previousLen = s.lines[row - 1].length
            document.delete(row - 1, previousLen, row, DocumentLayout.textStartCol(s.lines[row]))
            patch { it.copy(cursorRow = row - 1, cursorCol = previousLen, anchorRow = null, anchorCol = null) }
            return
        }
        if (range.all { BlockLayout.isEmptyContent(s.lines[it]) }) deleteBlockAt(row)
    }

    /**
     * Backspace at the text start of an empty leaf bullet that has no
     * array-adjacent visible row above it to merge into — the first row
     * of the document or of the zoom, or a row right below a folded
     * subtree. Deletes the row outright instead of unbulleting it:
     *
     *  - Folded subtree above: remove the row (and the newline before it)
     *    and park the caret at the end of the folded bullet, the previous
     *    visible row.
     *  - First row with rows below it: remove the row and put the caret at
     *    the text start of the row that moves up into its place.
     *  - The only row: nothing to do; the empty bullet stays.
     *
     * A non-empty bullet is left alone — Backspace at its start has
     * nothing sensible to merge with. Called only from [backspace] in an
     * outline ([Document.bulletsOnly]).
     */
    private fun deleteEmptyBulletWithoutMerge(s: PaneBackingViewModel.State) {
        val row = s.cursorRow
        val line = s.lines[row]
        if (!DocumentLayout.isEmptyBulletLine(line)) return
        val prev = prevVisibleRow(s, row)
        if (prev != null) {
            document.deleteLine(row)
            patch { it.copy(cursorRow = prev, cursorCol = s.lines[prev].length, anchorRow = null, anchorCol = null) }
            return
        }
        if (nextVisibleRow(s, row) != row + 1) return
        // [Document.deleteLine], not a delete across the newline: the row
        // moving up must keep its own id (fold state, backing folder).
        document.deleteLine(row)
        val newCol = DocumentLayout.caretStartCol(state.lines[row])
        patch { it.copy(cursorRow = row, cursorCol = newCol, anchorRow = null, anchorCol = null) }
    }

    /**
     * The folder-backed bullet a Tab of [amount] would nest the caret's
     * item (or the selected rows) under, when that bullet is folded in
     * this pane — its children may be on disk only, so the rows must not
     * be indented until they are loaded. `null` when the indent is not
     * allowed anyway, or its new parent is not a folded folder-backed
     * bullet.
     *
     * Called by `PaneBackingViewModel.indentLine`, which unfolds the
     * returned bullet first and then runs [indentLine].
     */
    fun foldedRefIndentParent(amount: Int = TAB_SIZE): LineId? {
        val s = state
        if (!s.isLoaded) return null
        if (blockUnderEdit(s) != null) return null
        val sel = selectionOf(s)
        val first: Int
        val currentIndent: Int
        if (sel != null && sel.startRow != sel.endRow) {
            first = widenToBlocks(s.lines, sel)?.first ?: return null
            currentIndent = s.lines[first].takeWhile { it == ' ' }.length
        } else {
            first = BlockLayout.rangeAt(s.lines, s.cursorRow)?.first ?: s.cursorRow
            currentIndent = s.lines[s.cursorRow].takeWhile { it == ' ' }.length
        }
        val ancestorIndent = precedingBulletIndent(s.lines, first) ?: return null
        if (currentIndent >= ancestorIndent + amount) return null
        val parentRow = precedingBulletRowAtIndentBelow(s.lines, first, currentIndent + amount) ?: return null
        val parentId = s.documentState?.lineIds?.getOrNull(parentRow) ?: return null
        return parentId.takeIf { it in s.collapsedIds && document.isPromotedRef(it) }
    }

    /**
     * The block that a Tab / Shift-Tab edits line by line: the one holding
     * the caret, or holding every row of a multi-row selection. `null`
     * when the caret is outside a block or the selection reaches past
     * one — then Tab moves whole items, as on bullets.
     */
    private fun blockUnderEdit(s: PaneBackingViewModel.State): IntRange? {
        val block = BlockLayout.rangeAt(s.lines, s.cursorRow) ?: return null
        val sel = selectionOf(s) ?: return block
        return block.takeIf { sel.startRow in block && sel.endRow in block }
    }

    /**
     * Rows of [block] a Tab / Shift-Tab touches: the caret row, or every
     * selected row (a selection ending at column 0 excludes its last row).
     */
    private fun blockRowsUnderEdit(s: PaneBackingViewModel.State, block: IntRange): IntRange {
        val sel = selectionOf(s) ?: return s.cursorRow..s.cursorRow
        if (sel.startRow == sel.endRow) return sel.startRow..sel.startRow
        val end = if (sel.endCol == 0 && sel.endRow > sel.startRow) sel.endRow - 1 else sel.endRow
        return sel.startRow..end.coerceIn(block)
    }

    /**
     * Tab inside a block: indents each row's *content* by [amount] spaces
     * (after the hidden marker), nesting a list item one level deeper.
     * The block itself never moves, and nothing inside it is ever an
     * outline bullet, so no folder is created. A row may sit at most one
     * level deeper than the row above it in the block; the block's first
     * row never indents. Code rows indent freely (after their hidden
     * [BlockLayout.CODE] marker). Selection columns shift with their rows.
     */
    private fun indentBlockContent(s: PaneBackingViewModel.State, block: IntRange, amount: Int) {
        val rows = blockRowsUnderEdit(s, block)
        val shifted = HashSet<Int>()
        for (r in rows) {
            val line = s.lines[r]
            // Code is indented freely: no nesting ceiling, first row too.
            val code = BlockLayout.isCodeLine(line)
            if (!code) {
                if (r == block.first) continue
                val allowed = BlockLayout.contentIndentOf(s.lines[r - 1]) + amount
                if (BlockLayout.contentIndentOf(line) + amount > allowed) continue
            }
            document.insertText(r, DocumentLayout.textStartCol(line), " ".repeat(amount))
            shifted += r
        }
        if (shifted.isEmpty()) return
        patch {
            it.copy(
                cursorCol = if (s.cursorRow in shifted) s.cursorCol + amount else s.cursorCol,
                anchorCol = s.anchorCol?.let { c -> if (s.anchorRow in shifted) c + amount else c },
            )
        }
    }

    /**
     * Shift-Tab inside a block: removes up to [amount] leading spaces of
     * each row's content. See [indentBlockContent].
     */
    private fun outdentBlockContent(s: PaneBackingViewModel.State, block: IntRange, amount: Int) {
        val rows = blockRowsUnderEdit(s, block)
        val removed = HashMap<Int, Int>()
        for (r in rows) {
            val n = minOf(amount, BlockLayout.contentIndentOf(s.lines[r]))
            if (n == 0) continue
            val start = DocumentLayout.textStartCol(s.lines[r])
            document.delete(r, start, r, start + n)
            removed[r] = n
        }
        if (removed.isEmpty()) return
        patch {
            it.copy(
                cursorCol = (s.cursorCol - (removed[s.cursorRow] ?: 0))
                    .coerceAtLeast(DocumentLayout.textStartCol(it.lines[s.cursorRow])),
                anchorCol = s.anchorCol?.let { c -> (c - (removed[s.anchorRow] ?: 0)).coerceAtLeast(0) },
            )
        }
    }

    fun indentLine(amount: Int = TAB_SIZE) {
        val s = state
        if (!s.isLoaded) return
        blockUnderEdit(s)?.let { indentBlockContent(s, it, amount); return }
        val sel = selectionOf(s)
        if (sel != null && sel.startRow != sel.endRow) {
            indentRange(s, sel, amount)
            return
        }
        val row = s.cursorRow
        val line = s.lines[row]
        // A block moves as a unit, measured from its first row: indenting
        // one row alone would split it into two blocks.
        val block = BlockLayout.rangeAt(s.lines, row)
        val first = block?.first ?: row
        val currentIndent = line.takeWhile { it == ' ' }.length
        if (indentPastHiddenIfAny(s, first, block, currentIndent, amount)) return
        val ancestorIndent = precedingBulletIndent(s.lines, first) ?: return
        if (currentIndent >= ancestorIndent + amount) return

        // Never indent under a folded folder-backed bullet: its children
        // may be on disk only, and the row would land before them.
        // `PaneBackingViewModel.indentLine` unfolds it first
        // ([foldedRefIndentParent]); this is the safety net.
        val newIndent = currentIndent + amount
        val newParentRow = precedingBulletRowAtIndentBelow(s.lines, first, newIndent)
        if (newParentRow != null) {
            val ids = s.documentState?.lineIds
            val parentId = ids?.getOrNull(newParentRow)
            if (parentId != null && parentId in s.collapsedIds && document.isPromotedRef(parentId)) {
                return
            }
        }

        // Indent the whole subtree as a unit so children stay nested
        // under their parent (Workflowy-style Tab semantics).
        val bulletCol = DocumentLayout.bulletAsteriskColumn(line)
        val end = when {
            block != null -> block.last
            bulletCol >= 0 -> DocumentLayout.subtreeEnd(s.lines, row, bulletCol)
            else -> row
        }
        val pad = " ".repeat(amount)
        for (r in first..end) {
            document.insertText(r, 0, pad)
        }
        // The reveal must ride in the SAME patch as the cursor move: the
        // reconcile pass inside every patch clamps the cursor to a visible
        // row, and if the new parent is still marked collapsed at that
        // instant the clamp yanks the caret off the just-indented row (up
        // to the previous visible row) before a separate reveal could run.
        val reveal = ancestorIdsAt(s, first, newIndent)
        patch {
            it.copy(
                cursorCol = it.cursorCol + amount, anchorRow = null, anchorCol = null,
                collapsedIds = it.collapsedIds - reveal,
            )
        }
    }

    fun outdentLine(amount: Int = TAB_SIZE) {
        val s = state
        if (!s.isLoaded) return
        blockUnderEdit(s)?.let { outdentBlockContent(s, it, amount); return }
        val sel = selectionOf(s)
        if (sel != null && sel.startRow != sel.endRow) {
            outdentRange(s, sel, amount)
            return
        }
        val row = s.cursorRow
        val line = s.lines[row]
        val leading = line.takeWhile { it == ' ' }.length
        val zoom = zoomInfoOf(s)
        val minAllowed = if (zoom != null) zoom.zoomIndent + TAB_SIZE else 0
        val remove = minOf(amount, leading - minAllowed).coerceAtLeast(0)
        if (remove == 0) return
        if (outdentPastHiddenIfAny(s, row, remove)) return

        // Outdent the whole subtree as a unit so the relative hierarchy
        // is preserved. Children all have indent strictly greater than
        // the parent's, so removing `remove` (≤ parent's leading) from
        // each row's column 0 is always safe.
        val block = BlockLayout.rangeAt(s.lines, row)
        val bulletCol = DocumentLayout.bulletAsteriskColumn(line)
        val first = block?.first ?: row
        val end = when {
            block != null -> block.last
            bulletCol >= 0 -> DocumentLayout.subtreeEnd(s.lines, row, bulletCol)
            else -> row
        }
        for (r in first..end) {
            document.delete(r, 0, r, remove)
        }
        patch {
            it.copy(
                cursorCol = (s.cursorCol - remove).coerceAtLeast(0),
                anchorRow = null, anchorCol = null
            )
        }
    }

    /**
     * Multi-row indent. Inserts [amount] leading spaces at column 0 of every
     * row in the selection range, preserving the selection (anchor + cursor
     * columns shift by [amount] on rows that were indented). The constraint
     * "first row may not jump more than one level past the bullet above the
     * selection" mirrors the single-row rule. A selection that ends at
     * column 0 of its last row excludes that row, matching standard editor
     * behavior.
     */
    private fun indentRange(
        s0: PaneBackingViewModel.State,
        sel: PaneBackingViewModel.Selection,
        amount: Int,
    ) {
        val (startRow, effEnd) = widenToBlocks(s0.lines, sel) ?: return
        val ancestorIndent = precedingBulletIndent(s0.lines, startRow) ?: return
        val firstIndent = s0.lines[startRow].takeWhile { it == ' ' }.length
        if (firstIndent >= ancestorIndent + amount) return
        val pad = " ".repeat(amount)
        for (row in startRow..effEnd) {
            document.insertText(row, 0, pad)
        }
        val cursorShift = if (s0.cursorRow in startRow..effEnd) amount else 0
        val anchorShift = if (s0.anchorRow != null && s0.anchorRow in startRow..effEnd) amount else 0
        // Same-patch reveal as in [indentLine]: if the block's new parent is
        // still marked collapsed when this patch reconciles, the clamp-to-
        // visible pass would tear the cursor (and selection anchor) off the
        // indented rows.
        val reveal = ancestorIdsAt(s0, startRow, firstIndent + amount)
        patch {
            it.copy(
                cursorCol = it.cursorCol + cursorShift,
                anchorCol = it.anchorCol?.let { c -> c + anchorShift },
                collapsedIds = it.collapsedIds - reveal,
            )
        }
    }

    /**
     * Multi-row outdent. Removes up to [amount] leading spaces from each row
     * in the selection range, clamped per-row so no line drops below the
     * minimum indent allowed by the current zoom. Selection is preserved;
     * anchor and cursor columns shift by the actual removal on their
     * respective rows. No-op when no row can be outdented.
     */
    private fun outdentRange(
        s0: PaneBackingViewModel.State,
        sel: PaneBackingViewModel.Selection,
        amount: Int,
    ) {
        val (startRow, effEnd) = widenToBlocks(s0.lines, sel) ?: return
        val zoom = zoomInfoOf(s0)
        val minAllowed = if (zoom != null) zoom.zoomIndent + TAB_SIZE else 0
        val removals = IntArray(effEnd - startRow + 1) { i ->
            val line = s0.lines[startRow + i]
            val leading = line.takeWhile { it == ' ' }.length
            minOf(amount, leading - minAllowed).coerceAtLeast(0)
        }
        if (removals.all { it == 0 }) return
        for (i in removals.indices) {
            val remove = removals[i]
            if (remove > 0) {
                val row = startRow + i
                document.delete(row, 0, row, remove)
            }
        }
        val cursorRemoval = if (s0.cursorRow in startRow..effEnd) {
            removals[s0.cursorRow - startRow]
        } else 0
        val anchorRemoval = if (s0.anchorRow != null && s0.anchorRow in startRow..effEnd) {
            removals[s0.anchorRow - startRow]
        } else 0
        patch {
            it.copy(
                cursorCol = (it.cursorCol - cursorRemoval).coerceAtLeast(0),
                anchorCol = it.anchorCol?.let { c -> (c - anchorRemoval).coerceAtLeast(0) },
            )
        }
    }

    /**
     * The rows a multi-row indent/outdent of [sel] touches: the selected
     * rows (a selection ending at column 0 excludes its last row), widened
     * so a block the selection only partly covers moves whole. `null` when
     * nothing is left.
     */
    private fun widenToBlocks(lines: List<String>, sel: PaneBackingViewModel.Selection): Pair<Int, Int>? {
        val effEnd = if (sel.endCol == 0) sel.endRow - 1 else sel.endRow
        if (effEnd < sel.startRow) return null
        val start = BlockLayout.rangeAt(lines, sel.startRow)?.first ?: sel.startRow
        val end = BlockLayout.rangeAt(lines, effEnd)?.last ?: effEnd
        return start to end
    }

    /**
     * Tab on the item at [first] while the privacy mode hides rows above
     * it. The item's new parent is the nearest *visible* item above it
     * (never a hidden one, under which it would vanish), and the ceiling is
     * one level under the nearest visible item. When hidden rows sit
     * between that parent's subtree and the item, the item (with its
     * subtree) moves up to become the parent's last child, before them —
     * they keep their own parent. Returns `true` when it handled the Tab
     * (done, or refused); `false` when the mode hides nothing above and the
     * usual Tab applies.
     */
    private fun indentPastHiddenIfAny(
        s: PaneBackingViewModel.State,
        first: Int,
        block: IntRange?,
        currentIndent: Int,
        amount: Int,
    ): Boolean {
        val hidden = hiddenRowsIn(s) ?: return false
        if ((0 until first).none { hidden[it] }) return false
        val lines = s.lines
        val visibleItemAbove = (first - 1 downTo 0).firstOrNull { !hidden[it] && DocumentLayout.itemColumn(lines, it) >= 0 }
            ?: return true
        if (currentIndent >= DocumentLayout.itemColumn(lines, visibleItemAbove) + amount) return true
        val newIndent = currentIndent + amount
        val parent = (first - 1 downTo 0).firstOrNull { !hidden[it] && DocumentLayout.itemColumn(lines, it) in 0 until newIndent }
            ?: return true
        val parentCol = DocumentLayout.itemColumn(lines, parent)
        val parentEnd = DocumentLayout.subtreeEnd(lines, parent, parentCol)
        if (parentEnd + 1 >= first || ((parentEnd + 1) until first).none { hidden[it] }) return false
        // A folded folder-backed parent has its children on disk only.
        val parentId = s.documentState?.lineIds?.getOrNull(parent)
        if (parentId != null && parentId in s.collapsedIds && document.isPromotedRef(parentId)) return true
        val bulletCol = DocumentLayout.bulletAsteriskColumn(lines[first])
        val end = when {
            block != null -> DocumentLayout.subtreeEnd(lines, first, BlockLayout.markerColumn(lines[first]))
            bulletCol >= 0 -> DocumentLayout.subtreeEnd(lines, first, bulletCol)
            else -> first
        }
        val pad = " ".repeat(amount)
        val landed = document.moveRows(first, end, parentEnd + 1, (first..end).map { pad + lines[it] })
        val reveal = ancestorIdsAt(s.copy(documentState = document.stateFlow.value), landed, newIndent)
        patch {
            it.copy(
                cursorRow = landed + (s.cursorRow - first), cursorCol = s.cursorCol + amount,
                anchorRow = null, anchorCol = null,
                collapsedIds = it.collapsedIds - reveal,
            )
        }
        return true
    }

    /**
     * Shift-Tab on the item at [row] while the privacy mode hides rows
     * after it under the same parent: outdenting in place would make those
     * hidden rows its children. Instead the item (with its subtree) moves
     * below its parent's whole subtree, [remove] spaces shallower — the
     * hidden rows keep their parent. Returns `true` when it handled the
     * Shift-Tab; `false` when the usual outdent applies.
     */
    private fun outdentPastHiddenIfAny(s: PaneBackingViewModel.State, row: Int, remove: Int): Boolean {
        val hidden = hiddenRowsIn(s) ?: return false
        val lines = s.lines
        val first = BlockLayout.rangeAt(lines, row)?.first ?: row
        val col = DocumentLayout.itemColumn(lines, first)
        if (col < 0) return false
        val end = DocumentLayout.subtreeEnd(lines, first, col)
        val parent = (first - 1 downTo 0).firstOrNull { DocumentLayout.itemColumn(lines, it) in 0 until col } ?: return false
        val parentEnd = DocumentLayout.subtreeEnd(lines, parent, DocumentLayout.itemColumn(lines, parent))
        if (parentEnd <= end || ((end + 1)..parentEnd).none { hidden[it] }) return false
        val texts = (first..end).map { r -> lines[r].substring(minOf(remove, lines[r].takeWhile { c -> c == ' ' }.length)) }
        val landed = document.moveRows(first, end, parentEnd + 1, texts)
        patch {
            it.copy(
                cursorRow = landed + (s.cursorRow - first),
                cursorCol = (s.cursorCol - remove).coerceAtLeast(0),
                anchorRow = null, anchorCol = null,
            )
        }
        return true
    }

    fun isBulletLine(): Boolean {
        val s = state
        if (!s.isLoaded) return false
        return DocumentLayout.bulletAsteriskColumn(s.lines[s.cursorRow]) >= 0
    }

    // ------------------------------------------------------------------ blocks

    /** `true` when the caret row is a block row (TRF-5). */
    fun isBlockLine(): Boolean {
        val s = state
        if (!s.isLoaded) return false
        return BlockLayout.isBlockLine(s.lines[s.cursorRow])
    }

    /**
     * Puts an empty block at the caret's item and the caret in it.
     *
     * - On an empty leaf bullet: the bullet *becomes* the block — the
     *   block is that item's body, drawn right of its dot.
     * - Otherwise: a new block item after the caret's item, at the same
     *   level. The "item" is the caret's bullet together with its whole
     *   subtree — so the block lands after the bullet's children and
     *   never re-parents them — or, when the caret is in a block, that
     *   block.
     *
     * Any selection is dropped, not deleted.
     *
     * With [contents] the block is not empty but holds those rows, and
     * the caret lands at the end of its last row instead.
     *
     * Outlines only ([Document.bulletsOnly]); a no-op in a plain Markdown
     * file. Called by `PaneBackingViewModel.insertBlock` (the "Insert
     * block" palette command) and `PaneBackingViewModel.insertMarkdownAsBlock`
     * (the "Insert Markdown file as block" palette command).
     *
     * @param contents The block's row contents without indent or block
     *   marker, as [BlockLayout.rowContentsOf] makes them (code rows keep
     *   their [BlockLayout.CODE] marker); empty for an empty block.
     */
    fun insertBlock(contents: List<String> = emptyList()) {
        val s = state
        if (!s.isLoaded || !document.bulletsOnly) return
        val row = s.cursorRow
        val line = s.lines[row]
        val block = BlockLayout.rangeAt(s.lines, row)
        val bulletCol = DocumentLayout.bulletAsteriskColumn(line)
        val rowId = s.documentState?.lineIds?.getOrNull(row)
        val emptyLeaf = bulletCol >= 0 && DocumentLayout.isEmptyBulletLine(line) &&
            !DocumentLayout.hasChildren(s.lines, row, bulletCol) &&
            (rowId == null || !document.isPromotedRef(rowId))
        val rows = contents.ifEmpty { listOf("") }
        if (emptyLeaf) {
            document.delete(row, bulletCol, row, line.length)
            document.insertText(row, bulletCol, BlockLayout.FIRST.toString() + rows.first())
            rows.drop(1).forEachIndexed { i, c -> document.insertLine(row + 1 + i, BlockLayout.nextLine(bulletCol, c)) }
            patch {
                it.copy(
                    cursorRow = row + rows.lastIndex,
                    cursorCol = bulletCol + 1 + rows.last().length,
                    anchorRow = null, anchorCol = null,
                    pendingInlineStyles = emptySet(),
                )
            }
            return
        }
        val (after, indent) = when {
            block != null -> BlockLayout.markerColumn(s.lines[block.first]).let { col ->
                DocumentLayout.subtreeEnd(s.lines, block.first, col) to col
            }
            bulletCol >= 0 -> DocumentLayout.subtreeEnd(s.lines, row, bulletCol) to bulletCol
            else -> row to DocumentLayout.indentOf(line)
        }
        rows.forEachIndexed { i, c ->
            document.insertLine(after + 1 + i, if (i == 0) BlockLayout.firstLine(indent, c) else BlockLayout.nextLine(indent, c))
        }
        patch {
            it.copy(
                cursorRow = after + 1 + rows.lastIndex, cursorCol = indent + 1 + rows.last().length,
                anchorRow = null, anchorCol = null,
                pendingInlineStyles = emptySet(),
            )
        }
    }

    /**
     * Puts an example search node ([SEARCH_NODE_EXAMPLE]) where
     * [insertBlock] would put a block: on the caret's empty leaf bullet,
     * or as a new item after the caret's item (with its subtree), at its
     * level. The expression is left selected, so typing replaces it.
     * Outlines only. Called by `PaneBackingViewModel.insertSearchNode`
     * (the "Insert search node" palette command).
     */
    fun insertSearchNode() {
        insertNodeItem(SEARCH_NODE_EXAMPLE, SEARCH_NODE_EXAMPLE_EXPR)
    }

    /**
     * Puts a board node (LBR-27), `Lunicle board {{lunicle: <connection>/<KEY>}}`
     * (always the full form, [LunicleNode.format]), where [insertSearchNode]
     * would put a search node, with its title ("Lunicle board") selected so
     * typing renames it. Outlines only. Called by
     * `PaneBackingViewModel.insertLunicleBoard` (the "Insert Lunicle board…"
     * palette command).
     *
     * @param connection The connection's name (App settings → Lunicle).
     * @param key The project's key prefix.
     */
    fun insertLunicleBoard(connection: String, key: String) {
        insertNodeItem("$LUNICLE_BOARD_TITLE ${LunicleNode.format(connection, key)}", LUNICLE_BOARD_TITLE)
    }

    /**
     * Inserts the bullet text [itemText] on the caret's empty leaf bullet,
     * or as a new item after the caret's item (with its subtree), at its
     * level — where [insertBlock] would put a block — and selects the first
     * [selected] in it. Shared by [insertSearchNode] and [insertLunicleBoard].
     */
    private fun insertNodeItem(itemText: String, selected: String) {
        val s = state
        if (!s.isLoaded || !document.bulletsOnly) return
        val row = s.cursorRow
        val line = s.lines[row]
        val block = BlockLayout.rangeAt(s.lines, row)
        val bulletCol = DocumentLayout.bulletAsteriskColumn(line)
        val rowId = s.documentState?.lineIds?.getOrNull(row)
        val emptyLeaf = bulletCol >= 0 && DocumentLayout.isEmptyBulletLine(line) &&
            !DocumentLayout.hasChildren(s.lines, row, bulletCol) &&
            (rowId == null || !document.isPromotedRef(rowId))
        val (at, indent) = when {
            emptyLeaf -> row to bulletCol
            block != null -> BlockLayout.markerColumn(s.lines[block.first]).let { col ->
                DocumentLayout.subtreeEnd(s.lines, block.first, col) + 1 to col
            }
            bulletCol >= 0 -> DocumentLayout.subtreeEnd(s.lines, row, bulletCol) + 1 to bulletCol
            else -> row + 1 to DocumentLayout.indentOf(line)
        }
        val text = " ".repeat(indent) + "* " + itemText
        if (emptyLeaf) {
            document.insertText(row, line.length, itemText)
        } else {
            document.insertLine(at, text)
        }
        val selStart = text.indexOf(selected, startIndex = indent + 2)
        patch {
            it.copy(
                cursorRow = at, cursorCol = selStart + selected.length,
                anchorRow = at, anchorCol = selStart,
                pendingInlineStyles = emptySet(),
            )
        }
    }

    /**
     * Deletes the whole block containing [row] — with any children it has
     * — and moves the caret to the
     * end of the previous visible row (or, at the top of the document or
     * zoom, to the text start of the row that moves up). A no-op when
     * [row] is not a block row.
     *
     * Called by `PaneBackingViewModel.deleteBlock` (the hover delete
     * control and the "Delete block" palette command) and by [backspace]
     * in an empty block. Undo restores the block through the pane's
     * snapshot history.
     */
    fun deleteBlockAt(row: Int) {
        val s = state
        if (!s.isLoaded) return
        val range = BlockLayout.rangeAt(s.lines, row) ?: return
        val prev = prevVisibleRow(s, range.first)
        // The block item goes with its children, like any deleted subtree.
        val end = DocumentLayout.subtreeEnd(s.lines, range.first, BlockLayout.markerColumn(s.lines[range.first]))
        document.deleteRows(range.first, end)
        val lines = document.stateFlow.value.lines
        val (r, c) = if (prev != null) {
            prev to lines[prev].length
        } else {
            val r0 = range.first.coerceAtMost(lines.lastIndex)
            r0 to DocumentLayout.caretStartCol(lines[r0])
        }
        patch {
            it.copy(
                cursorRow = r, cursorCol = c,
                anchorRow = null, anchorCol = null,
                pendingInlineStyles = emptySet(),
            )
        }
    }

    /**
     * Replaces the block containing [row] with bullets, one per paragraph
     * ([NoteConversion.nodeGroupsOfBlock]), in the block's parent.
     *
     * The first node takes over the block's first row — and with it the
     * row's [LineId], so a block with children keeps its folder, its
     * children and its fold state, now under that bullet (the next save
     * renames the folder after the bullet's title). Its own nested nodes
     * (list items under it) go right below it; every further node goes
     * after the block's whole subtree, at the block's level. The caret
     * lands at the end of the first node. A no-op outside a block or in
     * a plain Markdown file.
     *
     * Called by `PaneBackingViewModel.convertBlockToNodes` (the "Convert
     * block to nodes" palette command); undo restores the block through
     * the pane's snapshot history.
     */
    fun convertBlockToNodesAt(row: Int) {
        val s = state
        if (!s.isLoaded || !document.bulletsOnly) return
        val range = BlockLayout.rangeAt(s.lines, row) ?: return
        val indent = BlockLayout.markerColumn(s.lines[range.first])
        val groups = NoteConversion.nodeGroupsOfBlock(range.map { BlockLayout.contentOf(s.lines[it]) }, indent)
        // Bottom-up, so the row indices above stay valid.
        val subtreeEnd = DocumentLayout.subtreeEnd(s.lines, range.first, indent)
        groups.drop(1).flatten().forEachIndexed { i, l -> document.insertLine(subtreeEnd + 1 + i, l) }
        val first = groups.first()
        if (range.last > range.first) document.deleteRows(range.first + 1, range.last)
        val old = document.stateFlow.value.lines[range.first]
        document.delete(range.first, indent, range.first, old.length)
        document.insertText(range.first, indent, first.first().substring(indent))
        first.drop(1).forEachIndexed { i, l -> document.insertLine(range.first + 1 + i, l) }
        patch {
            it.copy(
                cursorRow = range.first, cursorCol = first.first().length,
                anchorRow = null, anchorCol = null,
                pendingInlineStyles = emptySet(),
            )
        }
    }

    /**
     * Leaves the block the caret is in onto a new empty bullet, and puts
     * the caret on it. The bullet is the block's first child when the
     * pane is zoomed into the block or its children are showing — so it
     * never lands between the block and its children — and otherwise
     * the block item's next sibling, after its whole subtree. The bullet
     * is recorded as a throwaway placeholder, so it disappears again if
     * the caret leaves it (or the pane navigates) before anything is
     * typed in it. A no-op outside a block. Called by `PaneBackingViewModel.exitBlock`
     * (Cmd-Enter and Escape in a block) and [moveDownOutOfBlock].
     */
    fun exitBlock() {
        val s = state
        if (!s.isLoaded) return
        val range = BlockLayout.rangeAt(s.lines, s.cursorRow) ?: return
        val indent = BlockLayout.markerColumn(s.lines[range.first])
        val id = s.documentState?.lineIds?.getOrNull(range.first)
        val subtreeEnd = DocumentLayout.subtreeEnd(s.lines, range.first, indent)
        val childrenShowing = subtreeEnd > range.last && id !in s.collapsedIds
        val (at, col) = when {
            zoomInfoOf(s)?.zoomRow == range.first -> range.last + 1 to indent + TAB_SIZE
            childrenShowing -> range.last + 1 to DocumentLayout.indentOf(s.lines[range.last + 1])
            else -> subtreeEnd + 1 to indent
        }
        document.insertLine(at, " ".repeat(col) + "* ")
        // The new bullet is a throwaway until typed in: navigating away or
        // putting the caret elsewhere removes it again (see
        // `PaneBackingViewModel.State.pendingLeafZoomChild`).
        val newId = document.stateFlow.value.lineIds.getOrNull(at)
        patch {
            it.copy(
                cursorRow = at, cursorCol = col + 2,
                anchorRow = null, anchorCol = null,
                pendingInlineStyles = emptySet(),
                pendingLeafZoomChild = newId,
            )
        }
    }

    /**
     * `true` when the caret, with no selection, sits at the text start of
     * a block's first row (after any hidden marker or list prefix) and the
     * block is not the zoomed item. Used by [insertNewline].
     */
    private fun s0AtBlockStart(): Boolean {
        val s = state
        if (selectionOf(s) != null) return false
        val range = BlockLayout.rangeAt(s.lines, s.cursorRow) ?: return false
        if (s.cursorRow != range.first) return false
        if (zoomInfoOf(s)?.zoomRow == range.first) return false
        return s.cursorCol <= DocumentLayout.caretStartCol(s.lines[s.cursorRow])
    }

    /**
     * Leaves the block the caret is in *upwards*: inserts an empty bullet
     * right above the block item, at its level (a sibling before it — the
     * block keeps its rows, children and identity), and puts the caret on
     * it. Like [exitBlock]'s bullet it is a throwaway placeholder, removed
     * again if the caret leaves it before anything is typed. A no-op
     * outside a block, or when the block is the zoomed item (above it is
     * outside the page).
     *
     * Called by `PaneBackingViewModel.exitBlockAbove` (Shift-Cmd-Enter),
     * by [insertNewline] at the start of a block and by [moveUpOutOfBlock].
     *
     * @return `true` when it inserted the bullet.
     */
    fun exitBlockAbove(): Boolean {
        val s = state
        if (!s.isLoaded) return false
        val range = BlockLayout.rangeAt(s.lines, s.cursorRow) ?: return false
        if (zoomInfoOf(s)?.zoomRow == range.first) return false
        val indent = BlockLayout.markerColumn(s.lines[range.first])
        val at = range.first
        document.insertLine(at, " ".repeat(indent) + "* ")
        val newId = document.stateFlow.value.lineIds.getOrNull(at)
        patch {
            it.copy(
                cursorRow = at, cursorCol = indent + 2,
                anchorRow = null, anchorCol = null,
                pendingInlineStyles = emptySet(),
                pendingLeafZoomChild = newId,
            )
        }
        return true
    }

    /**
     * Arrow Up on the first row of a block that nothing visible precedes
     * (the top of the document or zoom): leaves the block onto a new
     * bullet above it ([exitBlockAbove]) — the mirror of
     * [moveDownOutOfBlock]. Returns `false` — and does nothing — anywhere
     * else; the caller then moves the caret as usual.
     *
     * Called by `PaneBackingViewModel.moveUpOutOfBlock`.
     */
    fun moveUpOutOfBlock(): Boolean {
        val s = state
        if (!s.isLoaded || selectionOf(s) != null) return false
        val range = BlockLayout.rangeAt(s.lines, s.cursorRow) ?: return false
        if (s.cursorRow != range.first || prevVisibleRow(s, s.cursorRow) != null) return false
        return exitBlockAbove()
    }

    /**
     * Arrow Down on the last row of a block that nothing visible follows
     * (the end of the document or zoom): leaves the block onto a new
     * bullet below it ([exitBlock]), so there is always a way out.
     * Returns `false` — and does nothing — anywhere else; the caller then
     * moves the caret as usual.
     *
     * Called by `PaneBackingViewModel.moveDownOutOfBlock`.
     */
    fun moveDownOutOfBlock(): Boolean {
        val s = state
        if (!s.isLoaded || selectionOf(s) != null) return false
        val range = BlockLayout.rangeAt(s.lines, s.cursorRow) ?: return false
        if (s.cursorRow != range.last || nextVisibleRow(s, s.cursorRow) != null) return false
        exitBlock()
        return true
    }

    // ------------------------------------------------------------------ movement

    fun moveLeft(extend: Boolean = false) = mutate { st ->
        val sel = selectionOf(st)
        if (!extend && sel != null) {
            return@mutate st.copy(
                cursorRow = sel.startRow, cursorCol = sel.startCol,
                anchorRow = null, anchorCol = null
            )
        }
        val curLine = st.lines[st.cursorRow]
        val (r, c) = when {
            // Only hidden markers (e.g. inline code's opening backtick)
            // before the caret still counts as the start: wrap.
            !DocumentLayout.isAtVisibleTextStart(curLine, st.cursorCol) -> st.cursorRow to skipMarkersLeft(st.lines[st.cursorRow], st.cursorCol - 1)
            else -> {
                val prev = prevVisibleRow(st, st.cursorRow)
                if (prev != null) prev to st.lines[prev].length
                else st.cursorRow to st.cursorCol
            }
        }
        moved(st, r, c, extend)
    }

    fun moveRight(extend: Boolean = false) = mutate { st ->
        val sel = selectionOf(st)
        if (!extend && sel != null) {
            return@mutate st.copy(
                cursorRow = sel.endRow, cursorCol = sel.endCol,
                anchorRow = null, anchorCol = null
            )
        }
        val line = st.lines[st.cursorRow]
        val (r, c) = when {
            // Only hidden markers (e.g. bold's closing `**`) after the
            // caret still counts as the end: wrap.
            !DocumentLayout.isAtVisibleTextEnd(line, st.cursorCol) -> st.cursorRow to skipMarkersRight(line, st.cursorCol + 1)
            else -> {
                val next = nextVisibleRow(st, st.cursorRow)
                if (next != null) next to DocumentLayout.caretStartCol(st.lines[next])
                else st.cursorRow to st.cursorCol
            }
        }
        moved(st, r, c, extend)
    }

    fun moveUp(extend: Boolean = false) = mutate { st ->
        val targetRow = prevVisibleRow(st, st.cursorRow)
        if (targetRow == null) {
            if (extend) st else st.copy(anchorRow = null, anchorCol = null)
        } else {
            val targetLine = st.lines[targetRow]
            val targetCol = st.cursorCol.coerceIn(
                DocumentLayout.caretStartCol(targetLine), targetLine.length
            )
            moved(st, targetRow, targetCol, extend)
        }
    }

    fun moveDown(extend: Boolean = false) = mutate { st ->
        val targetRow = nextVisibleRow(st, st.cursorRow)
        if (targetRow == null) {
            if (extend) st else st.copy(anchorRow = null, anchorCol = null)
        } else {
            val targetLine = st.lines[targetRow]
            val targetCol = st.cursorCol.coerceIn(
                DocumentLayout.caretStartCol(targetLine), targetLine.length
            )
            moved(st, targetRow, targetCol, extend)
        }
    }

    fun moveTo(row: Int, col: Int, extend: Boolean = false) = mutate { st ->
        val clampedRow = row.coerceIn(0, st.lines.lastIndex)
        val line = st.lines[clampedRow]
        val clampedCol = col.coerceIn(DocumentLayout.caretStartCol(line), line.length)
        moved(st, clampedRow, clampedCol, extend)
    }

    fun moveLineStart(extend: Boolean = false) = mutate {
        moved(it, it.cursorRow, DocumentLayout.caretStartCol(it.lines[it.cursorRow]), extend)
    }

    fun moveLineEnd(extend: Boolean = false) = mutate {
        moved(it, it.cursorRow, it.lines[it.cursorRow].length, extend)
    }

    fun moveDocStart(extend: Boolean = false) = mutate {
        moved(it, 0, DocumentLayout.caretStartCol(it.lines[0]), extend)
    }

    fun moveDocEnd(extend: Boolean = false) = mutate {
        val lastRow = it.lines.lastIndex
        moved(it, lastRow, it.lines[lastRow].length, extend)
    }

    fun moveWordLeft(extend: Boolean = false) = mutate { st ->
        var row = st.cursorRow
        var col = st.cursorCol
        var minCol = DocumentLayout.caretStartCol(st.lines[row])
        if (col <= minCol && row > 0) {
            row--
            col = st.lines[row].length
            minCol = DocumentLayout.caretStartCol(st.lines[row])
        } else {
            val line = st.lines[row]
            while (col > minCol && !isWordChar(line[col - 1])) col--
            while (col > minCol && isWordChar(line[col - 1])) col--
        }
        moved(st, row, col, extend)
    }

    fun moveWordRight(extend: Boolean = false) = mutate { st ->
        var row = st.cursorRow
        var col = st.cursorCol
        val line = st.lines[row]
        if (col == line.length && row < st.lines.lastIndex) {
            row++
            col = DocumentLayout.caretStartCol(st.lines[row])
        } else {
            while (col < line.length && !isWordChar(line[col])) col++
            while (col < line.length && isWordChar(line[col])) col++
        }
        moved(st, row, col, extend)
    }

    // ------------------------------------------------------------------ selection

    fun selectAll() = mutate { st ->
        val zoom = zoomInfoOf(st)
        var startRow = zoom?.startRow ?: st.firstEditableRow
        var endRow = zoom?.endRowInclusive ?: st.lines.lastIndex
        if (endRow < startRow) return@mutate st
        // Rows the privacy mode hides at either end are not selected.
        hiddenRowsIn(st)?.let { hidden ->
            while (startRow <= endRow && hidden[startRow]) startRow++
            while (endRow >= startRow && hidden[endRow]) endRow--
            if (endRow < startRow) return@mutate st
        }
        st.copy(
            anchorRow = startRow, anchorCol = 0,
            cursorRow = endRow, cursorCol = st.lines[endRow].length
        )
    }

    fun selectWord(row: Int, col: Int) = mutate { st ->
        val clampedRow = row.coerceIn(0, st.lines.lastIndex)
        val line = st.lines[clampedRow]
        val minCol = DocumentLayout.caretStartCol(line)
        if (line.isEmpty() || minCol >= line.length) {
            st.copy(cursorRow = clampedRow, cursorCol = minCol, anchorRow = clampedRow, anchorCol = minCol)
        } else {
            val clampedCol = col.coerceIn(minCol, line.length - 1)
            val isWord = isWordChar(line[clampedCol])
            var start = clampedCol
            var end = clampedCol
            while (start > minCol && isWordChar(line[start - 1]) == isWord) start--
            while (end < line.length && isWordChar(line[end]) == isWord) end++
            st.copy(anchorRow = clampedRow, anchorCol = start, cursorRow = clampedRow, cursorCol = end)
        }
    }

    fun selectLine(row: Int) = mutate { st ->
        val clampedRow = row.coerceIn(0, st.lines.lastIndex)
        st.copy(
            anchorRow = clampedRow, anchorCol = 0,
            cursorRow = clampedRow, cursorCol = st.lines[clampedRow].length
        )
    }

    fun clearSelection() = mutate { it.copy(anchorRow = null, anchorCol = null) }

    /**
     * Deletes the active selection, if any, and collapses the caret to its
     * start. Returns `true` when something was deleted.
     *
     * In an outline ([Document.bulletsOnly]) both ends are first clamped
     * to the bullet's text start, so a selection that begins at column 0
     * (Select All, a triple-click) keeps the first row's `"* "` marker and
     * the merged row stays a bullet — deleting everything leaves one empty
     * bullet, never a bare line.
     *
     * A multi-row selection that takes the first row's whole text deletes
     * that row as an item: it does not live on in the merged row. The
     * merged row is the last row when some of that row's text is left,
     * otherwise a brand-new row. Keeping the first row's identity instead
     * would hand its folder — and every child it has on disk only — to
     * whatever is typed next (Select All over a folded node, then typing,
     * brought the old node's hidden children back under the new text).
     * The dropped row's folder goes to the trash on the next save; undo
     * restores it.
     */
    fun deleteSelectionIfAny(): Boolean {
        val s = state
        val sel = selectionOf(s) ?: run {
            if (s.anchorRow != null) {
                patch { it.copy(anchorRow = null, anchorCol = null) }
            }
            return false
        }
        var startCol = sel.startCol
        var endCol = sel.endCol
        if (document.bulletsOnly) {
            startCol = maxOf(startCol, DocumentLayout.textStartCol(s.lines[sel.startRow]))
            endCol = maxOf(endCol, DocumentLayout.textStartCol(s.lines[sel.endRow]))
            if (sel.startRow == sel.endRow && endCol <= startCol) {
                patch { it.copy(cursorRow = sel.startRow, cursorCol = startCol, anchorRow = null, anchorCol = null) }
                return true
            }
        }
        val startLine = s.lines[sel.startRow]
        val endLine = s.lines[sel.endRow]
        val startRowGone = document.bulletsOnly && sel.startRow != sel.endRow &&
            startCol <= DocumentLayout.textStartCol(startLine)
        if (document.bulletsOnly && sel.startRow != sel.endRow &&
            deleteAroundHiddenIfAny(s, sel, startCol, endCol, startRowGone)
        ) return true
        when {
            !startRowGone -> document.delete(sel.startRow, startCol, sel.endRow, endCol)
            endCol < endLine.length -> {
                // The last row survives, under the first row's prefix.
                document.delete(sel.endRow, 0, sel.endRow, endCol)
                document.insertText(sel.endRow, 0, startLine.substring(0, startCol))
                document.deleteRows(sel.startRow, sel.endRow - 1)
            }
            else -> {
                // Nothing survives: a new row takes the place of them all.
                document.insertLine(sel.startRow, startLine.substring(0, startCol))
                document.deleteRows(sel.startRow + 1, sel.endRow + 1)
            }
        }
        patch {
            it.copy(
                cursorRow = sel.startRow, cursorCol = startCol,
                anchorRow = null, anchorCol = null
            )
        }
        return true
    }

    /**
     * Deletes a multi-row selection that spans rows the privacy mode hides,
     * or visible items with hidden content under them ([isProtectedRow]):
     * those rows are kept whole, and only the visible rows around them are
     * deleted — the first row from [startCol] (all of it, as an item, when
     * [startRowGone]), the last up to [endCol] (as an item when the
     * selection takes its whole text), every row in between as an item.
     * Rows are not merged across the kept ones. The caret lands at the
     * selection start, or where the first row was.
     *
     * Returns `false` — nothing done — when the selection holds no such
     * row; [deleteSelectionIfAny] then deletes as usual.
     */
    private fun deleteAroundHiddenIfAny(
        s: PaneBackingViewModel.State,
        sel: PaneBackingViewModel.Selection,
        startCol: Int,
        endCol: Int,
        startRowGone: Boolean,
    ): Boolean {
        if (!s.privacy.isActive) return false
        val hidden = hiddenRowsIn(s)
        val lines = s.lines
        val keep = (sel.startRow..sel.endRow).filter { r ->
            PrivacyLayout.isHidden(hidden, r) || (DocumentLayout.itemColumn(lines, r) >= 0 && isProtectedRow(s, r))
        }.toHashSet()
        if (keep.isEmpty()) return false
        val removed = HashSet<Int>()
        // Bottom-up: text edits and removals below never shift rows above.
        val last = sel.endRow
        val lastText = DocumentLayout.textStartCol(lines[last])
        when {
            // A kept item keeps its title whole; only part of it can go.
            last in keep -> if (endCol in (lastText + 1) until lines[last].length) document.delete(last, lastText, last, endCol)
            endCol >= lines[last].length -> removed += last
            endCol > lastText -> document.delete(last, lastText, last, endCol)
        }
        for (r in sel.startRow + 1 until last) if (r !in keep) removed += r
        val first = sel.startRow
        val firstKept = first in keep || !startRowGone
        if (firstKept) {
            val partOfTitle = startCol > DocumentLayout.textStartCol(lines[first])
            if (startCol < lines[first].length && (first !in keep || partOfTitle)) {
                document.delete(first, startCol, first, lines[first].length)
            }
        } else {
            removed += first
        }
        // Remove whole rows, bottom-up, a contiguous run at a time.
        val runs = removed.sortedDescending()
        var i = 0
        while (i < runs.size) {
            var lo = runs[i]
            val hi = lo
            while (i + 1 < runs.size && runs[i + 1] == lo - 1) {
                i++
                lo = runs[i]
            }
            document.deleteRows(lo, hi)
            i++
        }
        val now = document.stateFlow.value.lines
        val (r, c) = if (firstKept) {
            first to startCol.coerceAtMost(now[first].length)
        } else {
            val r0 = first.coerceAtMost(now.lastIndex)
            r0 to DocumentLayout.caretStartCol(now[r0])
        }
        patch { it.copy(cursorRow = r, cursorCol = c, anchorRow = null, anchorCol = null) }
        return true
    }

    /**
     * Text of the active selection for the clipboard, or `null` when
     * nothing is selected.
     *
     * A multi-row selection that starts at or before the first row's text
     * start copies that row *with* its indent and `"* "` marker, like
     * every following row. The clipboard then holds a well-formed
     * Markdown list, and [bulletLinesForPaste] can tell the first row's
     * depth from the rest when the text is pasted back. Block rows copy as
     * their indent plus content: the hidden [BlockLayout] markers never
     * reach the clipboard. Links into the vault are copied vault-rooted
     * (`/…`, [LunarborLink.rootedText]), since each row's relative links
     * only hold in its own folder; the save after a paste writes them
     * relative to where they landed.
     */
    fun getSelectedText(): String? {
        val s = state
        if (!s.isLoaded) return null
        val sel = selectionOf(s) ?: return null
        val lines = s.lines
        val hidden = hiddenRowsIn(s)
        fun rooted(row: Int, text: String) = LunarborLink.rootedText(text, document.linkBaseOf(row))
        val raw = if (sel.startRow == sel.endRow) {
            rooted(sel.startRow, lines[sel.startRow].substring(sel.startCol, sel.endCol))
        } else {
            val firstLine = lines[sel.startRow]
            val firstFrom = if (sel.startCol <= DocumentLayout.textStartCol(firstLine)) 0 else sel.startCol
            buildString {
                append(rooted(sel.startRow, firstLine.substring(firstFrom)))
                append('\n')
                for (i in sel.startRow + 1 until sel.endRow) {
                    // What the privacy mode hides never reaches the clipboard.
                    if (PrivacyLayout.isHidden(hidden, i)) continue
                    append(rooted(i, lines[i]))
                    append('\n')
                }
                append(rooted(sel.endRow, lines[sel.endRow].substring(0, sel.endCol)))
            }
        }
        // Block and code markers are an in-memory device; the clipboard
        // gets the block's Markdown content.
        return raw.filter { it != BlockLayout.FIRST && it != BlockLayout.NEXT && it != BlockLayout.CODE }
    }

    /**
     * Cuts the selection: returns its text for the clipboard and deletes
     * it. Before deleting, tells the document which folder-backed bullets
     * the cut holds ([Document.rememberCut]) so pasting the same text
     * moves their folders instead of trashing them.
     */
    fun onCutRequested(): String? {
        val text = getSelectedText() ?: return null
        val s = state
        val sel = selectionOf(s)
        if (sel != null) {
            val hidden = hiddenRowsIn(s)
            val rows = (sel.startRow..sel.endRow).filterNot { PrivacyLayout.isHidden(hidden, it) }
            document.rememberCut(sel.startRow, sel.startCol, sel.endRow, sel.endCol, text, rows)
        }
        deleteSelectionIfAny()
        return text
    }

    /**
     * Indent (leading-space count) of the nearest item — bullet or block
     * ([DocumentLayout.itemColumn]) — strictly before [row] that [row]
     * could become a child of, or `null` when there is none. Used by
     * [indentLine] / [indentRange] as the structural ceiling: a row may
     * never be indented more than one tab past that item, and the very
     * first item in the document (or zoom region) has no ancestor and so
     * cannot be indented at all. A block item can be a parent, so Tab on
     * the row after a block nests it under the block.
     */
    private fun precedingBulletIndent(lines: List<String>, row: Int): Int? {
        var r = row - 1
        while (r >= 0) {
            val col = DocumentLayout.itemColumn(lines, r)
            if (col >= 0) return col
            r--
        }
        return null
    }


    /**
     * Walks backward from [row] and returns the row index of the nearest
     * preceding bullet whose indent is strictly less than [newIndent], or
     * `null` when no such bullet exists. Used by [indentLine] to identify
     * the bullet that would *become* the new parent after a Tab, which
     * must be unfolded first when it is a folded folder-backed bullet.
     */
    private fun precedingBulletRowAtIndentBelow(lines: List<String>, row: Int, newIndent: Int): Int? {
        var r = row - 1
        while (r >= 0) {
            val col = DocumentLayout.itemColumn(lines, r)
            if (col in 0 until newIndent) return r
            r--
        }
        return null
    }

    /**
     * [LineId]s of the bullets that become [row]'s ancestor chain once the
     * row sits at [indent]: the nearest preceding bullet at each
     * successively shallower indent. Computed from the pre-edit snapshot
     * [s] — an indent only inserts pad at column 0 of rows at/below [row],
     * so the rows above (the only ones inspected here) are unaffected.
     *
     * Used by [indentLine] / [indentRange] to drop the new ancestors from
     * `collapsedIds` in the same patch that moves the cursor. Mirrors the
     * walk in `PaneBackingViewModel.revealAncestors`, which cannot be used
     * there: it patches separately, and by then the reconcile clamp has
     * already torn the cursor off the (momentarily hidden) indented row.
     */
    private fun ancestorIdsAt(s: PaneBackingViewModel.State, row: Int, indent: Int): Set<LineId> {
        val ids = s.documentState?.lineIds ?: return emptySet()
        val toReveal = mutableSetOf<LineId>()
        var lookingFor = indent
        var r = row - 1
        while (r >= 0 && lookingFor > 0) {
            val col = DocumentLayout.itemColumn(s.lines, r)
            if (col in 0 until lookingFor) {
                if (r in ids.indices) toReveal += ids[r]
                lookingFor = col
            }
            r--
        }
        return toReveal
    }

    /**
     * Returns the set of editable-relative model columns occupied by
     * markdown marker characters (line-level prefix + inline markers) on
     * [line]. Empty when the line has no markers.
     */
    private fun markerSet(line: String): Set<Int> {
        val tStart = DocumentLayout.textStartCol(line)
        val editable = if (tStart >= line.length) "" else line.substring(tStart)
        val linePrefix = LineMarkdownPrefix.detect(editable, 0)
        val lineMarkerLen = if (linePrefix.style != null) linePrefix.markerEnd else 0
        val inlineText = if (lineMarkerLen >= editable.length) "" else editable.substring(lineMarkerLen)
        val tokenized = InlineMarkdownTokenizer.tokenize(inlineText)
        if (lineMarkerLen == 0 && tokenized.markerCols.isEmpty()) return emptySet()
        val out = HashSet<Int>(tokenized.markerCols.size + lineMarkerLen)
        for (i in 0 until lineMarkerLen) out += (tStart + i)
        for (m in tokenized.markerCols) out += (tStart + lineMarkerLen + m)
        return out
    }

    /**
     * `true` when [col] is strictly inside a marker run on [line] — i.e.
     * both the char at [col]-1 and the char at [col] are markers, so the
     * cursor would sit invisibly between two collapsed marker chars. The
     * boundaries (col immediately before / after a marker run) are not
     * "bad" — those are valid caret positions.
     */
    private fun isInsideMarker(line: String, col: Int, markers: Set<Int>): Boolean {
        if (col <= 0 || col >= line.length) return false
        if (markers.isEmpty()) return false
        return (col - 1) in markers && col in markers
    }

    /**
     * From candidate column [from], advance leftward (decreasing col) until
     * the cursor sits outside any marker run. Used by [moveLeft] so a
     * single arrow press jumps past hidden markers in one visual step.
     */
    private fun skipMarkersLeft(line: String, from: Int): Int {
        val markers = markerSet(line)
        if (markers.isEmpty()) return from
        var c = from
        val floor = DocumentLayout.caretStartCol(line)
        while (c > floor && isInsideMarker(line, c, markers)) c--
        return c
    }

    /**
     * Mirror of [skipMarkersLeft]: advance rightward (increasing col)
     * until the cursor sits outside any marker run.
     */
    private fun skipMarkersRight(line: String, from: Int): Int {
        val markers = markerSet(line)
        if (markers.isEmpty()) return from
        var c = from
        while (c < line.length && isInsideMarker(line, c, markers)) c++
        return c
    }
}

/** The expression of [SEARCH_NODE_EXAMPLE], selected after an insert. */
internal const val SEARCH_NODE_EXAMPLE_EXPR: String = "#todo -#done"

/** The bullet text "Insert search node" puts in: a title and an example query. */
internal const val SEARCH_NODE_EXAMPLE: String = "Open tasks {{search: $SEARCH_NODE_EXAMPLE_EXPR}}"

/** The title "Insert Lunicle board…" gives a new board node, selected after the insert. */
internal const val LUNICLE_BOARD_TITLE: String = "Lunicle board"
