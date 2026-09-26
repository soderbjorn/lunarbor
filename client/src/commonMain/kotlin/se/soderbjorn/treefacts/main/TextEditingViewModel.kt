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
 * commonMain only — no DOM, Android UI, or UIKit imports. The class holds
 * no state of its own; cursor and selection live in the aggregate's
 * single `MutableStateFlow`.
 */

package se.soderbjorn.treefacts.main

import se.soderbjorn.treefacts.data.InlineMarkdownTokenizer
import se.soderbjorn.treefacts.data.InlineStyle
import se.soderbjorn.treefacts.data.LineMarkdownPrefix
import se.soderbjorn.treefacts.main.PaneBackingViewModel.Companion.TAB_SIZE

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
 */
internal class TextEditingViewModel(
    private val documentProvider: () -> Document,
    private val stateProvider: () -> PaneBackingViewModel.State,
    @Suppress("unused") private val applyState: (PaneBackingViewModel.State) -> Unit,
    private val mutate: ((PaneBackingViewModel.State) -> PaneBackingViewModel.State) -> Unit,
    private val patch: ((PaneBackingViewModel.State) -> PaneBackingViewModel.State) -> Unit,
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

        // Zoom view, empty leaf bullet: drop the row entirely and land the
        // caret at the start of the next visible row's editable text. The
        // strip-in-place "exit the list" behavior used at root level reads
        // as awkward inside a zoom — an indented blank row looks like it's
        // still part of the bullet list. Users expect the empty bullet to
        // vanish so they're on the row below ready to keep going. Only
        // applies when the next visible row is the immediately-adjacent
        // row (so we never silently merge across a folded subtree) and
        // there is one (so we don't strand the caret past end-of-zoom);
        // anything else falls through to the canonical strip path below.
        if (deleteEmptyBulletInZoomIfAny()) return

        // Empty leaf bullet: Enter "exits the list" by stripping the `"* "`
        // marker in place, leaving any indent and parking the caret at the
        // indent column. Complements the backspace-join in [backspace]
        // (which merges the row upward); Enter escapes downward-in-place.
        // Refuse on non-leaf bullets (orphans children) — fall through to
        // normal continuation.
        if (exitListOnEmptyBulletIfAny()) return

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
     * If the caret sits on an empty leaf bullet (line is just indent +
     * `"* "` with no descendants), strip the marker so the row becomes a
     * plain (possibly indented) blank line and the caret lands at the
     * indent column. Returns `true` when it consumed the Enter; `false`
     * when the line isn't an empty leaf bullet and normal newline handling
     * should run.
     *
     * Symmetric with the bullet-start branch in [backspace]: the same
     * children-check guards against orphaning a subtree.
     */
    /**
     * Zoom-only counterpart to [exitListOnEmptyBulletIfAny]. When the
     * caret sits on an empty leaf bullet inside a zoom and there is a
     * directly-adjacent visible row to fall onto, deletes the empty
     * bullet's whole line (including its trailing newline) and parks the
     * caret at [DocumentLayout.caretStartCol] of what is now the cursor
     * row (previously the next row). Returns `true` when it consumed
     * the Enter; `false` otherwise so the caller can fall through to
     * the canonical [exitListOnEmptyBulletIfAny] strip path.
     *
     * The "next visible row must equal row + 1" guard avoids silently
     * pulling content out of a folded subtree the user can't see;
     * the "must be inside the zoom region" guard (implicit in
     * [nextVisibleRow]) avoids landing the caret past `endRowInclusive`
     * where reconcile's zoom clamp would then yank it back up.
     */
    private fun deleteEmptyBulletInZoomIfAny(): Boolean {
        val s = state
        if (zoomInfoOf(s) == null) return false
        val line = s.lines[s.cursorRow]
        if (!DocumentLayout.isEmptyBulletLine(line)) return false
        val bulletCol = DocumentLayout.bulletAsteriskColumn(line)
        if (DocumentLayout.hasChildren(s.lines, s.cursorRow, bulletCol)) return false
        val next = nextVisibleRow(s, s.cursorRow) ?: return false
        if (next != s.cursorRow + 1) return false
        document.delete(s.cursorRow, 0, s.cursorRow + 1, 0)
        val newLine = state.lines[s.cursorRow]
        val newCol = DocumentLayout.caretStartCol(newLine)
        patch {
            it.copy(
                cursorCol = newCol,
                anchorRow = null, anchorCol = null,
                pendingInlineStyles = emptySet(),
            )
        }
        return true
    }

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
     * Empty *leaf* bullets never reach here (the exit-list checks run
     * first); an empty bullet with children does, and gets the sibling
     * above — preferable to the old first-child split, which would
     * re-parent its subtree.
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
        if (zoom != null && s.cursorRow <= zoom.startRow) return false
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
        val childCol = DocumentLayout.bulletAsteriskColumn(s.lines[s.cursorRow + 1])
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
        if (zoom != null && row == zoom.startRow) {
            val childCol = DocumentLayout.bulletAsteriskColumn(s.lines[row + 1])
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
     * [se.soderbjorn.treefacts.data.TokenizedLine.stylesAt].
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
     */
    fun insertText(text: String) {
        if (!state.isLoaded) return
        deleteSelectionIfAny()
        val startRow = state.cursorRow
        insertWithPendingStyles(text)
        document.adoptCut(startRow, text)
    }

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
        val ordered = se.soderbjorn.treefacts.data.InlineStyle.entries.filter { it in pending }
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
                    if (prevVisibleRow(s, s.cursorRow) == s.cursorRow - 1) {
                        val previousLen = s.lines[s.cursorRow - 1].length
                        document.delete(s.cursorRow - 1, previousLen, s.cursorRow, s.cursorCol)
                        patch { it.copy(cursorRow = s.cursorRow - 1, cursorCol = previousLen) }
                        return
                    }
                    // Nothing to merge into (first row of the document or of a zoom,
                    // or a folded subtree directly above): fall through to remove just
                    // the `"* "` marker, leaving any indent and trailing content intact
                    // and the cursor at the indent column — the row stays put as a
                    // plain (possibly indented) line instead of collapsing upward.
                    // Works the same inside a zoom: the zoom region (computed via
                    // [DocumentLayout.zoomSubtreeEnd]) includes non-bullet prose rows,
                    // so the just-stripped row stays inside `endRowInclusive` and
                    // reconcile's zoom clamp leaves the cursor where it is.
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

    fun indentLine(amount: Int = TAB_SIZE) {
        val s = state
        if (!s.isLoaded) return
        val sel = selectionOf(s)
        if (sel != null && sel.startRow != sel.endRow) {
            indentRange(s, sel, amount)
            return
        }
        val row = s.cursorRow
        val line = s.lines[row]
        val currentIndent = line.takeWhile { it == ' ' }.length
        val ancestorIndent = precedingBulletIndent(s.lines, row) ?: return
        if (currentIndent >= ancestorIndent + amount) return

        // Refuse to indent under a collapsed promoted-ref: auto-expanding
        // it would silently splice in another file's content and the
        // moved subtree would appear to belong to that other file at the
        // next autosave. Force the user to expand the ref first.
        val newIndent = currentIndent + amount
        val newParentRow = precedingBulletRowAtIndentBelow(s.lines, row, newIndent)
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
        val end = if (bulletCol >= 0) DocumentLayout.subtreeEnd(s.lines, row, bulletCol) else row
        val pad = " ".repeat(amount)
        for (r in row..end) {
            document.insertText(r, 0, pad)
        }
        // The reveal must ride in the SAME patch as the cursor move: the
        // reconcile pass inside every patch clamps the cursor to a visible
        // row, and if the new parent is still marked collapsed at that
        // instant the clamp yanks the caret off the just-indented row (up
        // to the previous visible row) before a separate reveal could run.
        val reveal = ancestorIdsAt(s, row, newIndent)
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

        // Outdent the whole subtree as a unit so the relative hierarchy
        // is preserved. Children all have indent strictly greater than
        // the parent's, so removing `remove` (≤ parent's leading) from
        // each row's column 0 is always safe.
        val bulletCol = DocumentLayout.bulletAsteriskColumn(line)
        val end = if (bulletCol >= 0) DocumentLayout.subtreeEnd(s.lines, row, bulletCol) else row
        for (r in row..end) {
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
        val effEnd = if (sel.endCol == 0) sel.endRow - 1 else sel.endRow
        if (effEnd < sel.startRow) return
        val ancestorIndent = precedingBulletIndent(s0.lines, sel.startRow) ?: return
        val firstIndent = s0.lines[sel.startRow].takeWhile { it == ' ' }.length
        if (firstIndent >= ancestorIndent + amount) return
        val pad = " ".repeat(amount)
        for (row in sel.startRow..effEnd) {
            document.insertText(row, 0, pad)
        }
        val cursorShift = if (s0.cursorRow in sel.startRow..effEnd) amount else 0
        val anchorShift = if (s0.anchorRow != null && s0.anchorRow in sel.startRow..effEnd) amount else 0
        // Same-patch reveal as in [indentLine]: if the block's new parent is
        // still marked collapsed when this patch reconciles, the clamp-to-
        // visible pass would tear the cursor (and selection anchor) off the
        // indented rows.
        val reveal = ancestorIdsAt(s0, sel.startRow, firstIndent + amount)
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
        val effEnd = if (sel.endCol == 0) sel.endRow - 1 else sel.endRow
        if (effEnd < sel.startRow) return
        val zoom = zoomInfoOf(s0)
        val minAllowed = if (zoom != null) zoom.zoomIndent + TAB_SIZE else 0
        val removals = IntArray(effEnd - sel.startRow + 1) { i ->
            val line = s0.lines[sel.startRow + i]
            val leading = line.takeWhile { it == ' ' }.length
            minOf(amount, leading - minAllowed).coerceAtLeast(0)
        }
        if (removals.all { it == 0 }) return
        for (i in removals.indices) {
            val remove = removals[i]
            if (remove > 0) {
                val row = sel.startRow + i
                document.delete(row, 0, row, remove)
            }
        }
        val cursorRemoval = if (s0.cursorRow in sel.startRow..effEnd) {
            removals[s0.cursorRow - sel.startRow]
        } else 0
        val anchorRemoval = if (s0.anchorRow != null && s0.anchorRow in sel.startRow..effEnd) {
            removals[s0.anchorRow - sel.startRow]
        } else 0
        patch {
            it.copy(
                cursorCol = (it.cursorCol - cursorRemoval).coerceAtLeast(0),
                anchorCol = it.anchorCol?.let { c -> (c - anchorRemoval).coerceAtLeast(0) },
            )
        }
    }

    fun isBulletLine(): Boolean {
        val s = state
        if (!s.isLoaded) return false
        return DocumentLayout.bulletAsteriskColumn(s.lines[s.cursorRow]) >= 0
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
        val curMin = DocumentLayout.caretStartCol(curLine)
        val (r, c) = when {
            st.cursorCol > curMin -> st.cursorRow to skipMarkersLeft(st.lines[st.cursorRow], st.cursorCol - 1)
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
            st.cursorCol < line.length -> st.cursorRow to skipMarkersRight(line, st.cursorCol + 1)
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
        val startRow = zoom?.startRow ?: 0
        val endRow = zoom?.endRowInclusive ?: st.lines.lastIndex
        if (endRow < startRow) return@mutate st
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

    fun deleteSelectionIfAny(): Boolean {
        val s = state
        val sel = selectionOf(s) ?: run {
            if (s.anchorRow != null) {
                patch { it.copy(anchorRow = null, anchorCol = null) }
            }
            return false
        }
        document.delete(sel.startRow, sel.startCol, sel.endRow, sel.endCol)
        patch {
            it.copy(
                cursorRow = sel.startRow, cursorCol = sel.startCol,
                anchorRow = null, anchorCol = null
            )
        }
        return true
    }

    fun getSelectedText(): String? {
        val s = state
        if (!s.isLoaded) return null
        val sel = selectionOf(s) ?: return null
        val lines = s.lines
        return if (sel.startRow == sel.endRow) {
            lines[sel.startRow].substring(sel.startCol, sel.endCol)
        } else {
            buildString {
                append(lines[sel.startRow].substring(sel.startCol))
                append('\n')
                for (i in sel.startRow + 1 until sel.endRow) {
                    append(lines[i])
                    append('\n')
                }
                append(lines[sel.endRow].substring(0, sel.endCol))
            }
        }
    }

    /**
     * Cuts the selection: returns its text for the clipboard and deletes
     * it. Before deleting, tells the document which folder-backed bullets
     * the cut holds ([Document.rememberCut]) so pasting the same text
     * moves their folders instead of trashing them.
     */
    fun onCutRequested(): String? {
        val text = getSelectedText() ?: return null
        val sel = selectionOf(state)
        if (sel != null) document.rememberCut(sel.startRow, sel.startCol, sel.endRow, sel.endCol, text)
        deleteSelectionIfAny()
        return text
    }

    /**
     * Indent (leading-space count) of the nearest bullet line strictly
     * before [row], or `null` when no preceding bullet exists. Used by
     * [indentLine] / [indentRange] as the structural ceiling: a row may
     * never be indented more than one tab past its nearest preceding
     * bullet, and the very first bullet in the document (or zoom region)
     * has no ancestor and so cannot be indented at all.
     */
    private fun precedingBulletIndent(lines: List<String>, row: Int): Int? {
        var r = row - 1
        while (r >= 0) {
            val col = DocumentLayout.bulletAsteriskColumn(lines[r])
            if (col >= 0) return col
            r--
        }
        return null
    }

    /**
     * Walks backward from [row] and returns the row index of the nearest
     * preceding bullet whose indent is strictly less than [newIndent], or
     * `null` when no such bullet exists. Used by [indentLine] to identify
     * the bullet that would *become* the new parent after a Tab so we
     * can short-circuit the indent when that parent is a collapsed
     * promoted-ref.
     */
    private fun precedingBulletRowAtIndentBelow(lines: List<String>, row: Int, newIndent: Int): Int? {
        var r = row - 1
        while (r >= 0) {
            val col = DocumentLayout.bulletAsteriskColumn(lines[r])
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
            val col = DocumentLayout.bulletAsteriskColumn(s.lines[r])
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
