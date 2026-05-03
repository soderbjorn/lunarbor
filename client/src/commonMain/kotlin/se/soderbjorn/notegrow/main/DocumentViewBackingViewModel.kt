/*
 * DocumentViewBackingViewModel.kt
 * -------------------------------
 * Per-viewer state on top of the shared `DocumentBackingViewModel`. This file
 * owns everything that belongs to *one* viewer of the note — cursor position,
 * selection anchor, and the current "zoom target" (the bullet whose subtree
 * is currently displayed as the editor's root).
 *
 * The actual editing behavior is split across small siblings in this package:
 *
 *   - [TextEditingViewModel]  — typing, deletion, indent/outdent, movement,
 *     selection.
 *   - [ZoomNavigation]        — zoom in/out and zoom-info resolution.
 *   - SelectionHelper.kt      — pure helpers (no class).
 *
 * This class composes those slices and remains the consumer-facing aggregate
 * so platform `MainViewModel`s can keep their existing call sites.
 *
 * The "zoom" feature models Notegrow as an infinite outliner: clicking a
 * bullet marker makes that bullet the logical root of the current view, so
 * only its descendants are shown and edited. Zoom state is view-local — the
 * underlying document is never reshaped.
 *
 * This file is commonMain — no DOM, Android UI, or UIKit imports.
 */

package se.soderbjorn.notegrow.main

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.TimeSource
import se.soderbjorn.notegrow.data.InlineStyle
import se.soderbjorn.notegrow.data.LineStyle

/**
 * The view-layer ViewModel for a single viewer (one window / pane / device)
 * of the shared note. It mirrors `DocumentBackingViewModel`'s content and
 * adds cursor, selection anchor, and zoom target on top.
 *
 * ### Callers
 * - Created per viewer by the platform DI graph (`JsAppGraph`) and injected
 *   into the platform `MainViewModel`.
 * - `MainViewModel` delegates every intent to this class one-for-one.
 * - Tests exercise the intents directly without going through a platform
 *   VM.
 *
 * @param documentBackingViewModel The shared document VM this viewer reads
 *   from and writes to.
 * @param scope Coroutine scope owning the collector that mirrors the
 *   document VM's state flow.
 */
class DocumentViewBackingViewModel(
    private val documentBackingViewModel: DocumentBackingViewModel,
    private val scope: CoroutineScope,
) {
    /** Mirrors [DocumentBackingViewModel.rootFileName] for the view layer. */
    val rootFileName: String get() = documentBackingViewModel.rootFileName

    /**
     * Immutable snapshot of one viewer's state.
     *
     * @property documentState A mirror of the latest `DocumentBackingViewModel.State`,
     *   or `null` before the first emission. Kept in sync by the collector
     *   in [init] and by [patch] after any edit, so every emission is a
     *   consistent snapshot of both document + viewer state.
     * @property cursorRow Row of the caret, in absolute document coordinates.
     * @property cursorCol Column of the caret on [cursorRow].
     * @property anchorRow If non-null, together with [anchorCol] defines the
     *   other end of the active selection. `null` means "no selection — the
     *   caret is just a point".
     * @property anchorCol Column companion to [anchorRow].
     * @property zoomedLineId If non-null, the [LineId] of the bullet whose
     *   subtree is being shown. `null` means "at root — show everything".
     *   Stored as an id (not a row) so the reference survives edits that
     *   shift rows above the zoom target.
     * @property zoomHistory Browser-style back stack of previous zoom
     *   targets. The most recent prior zoom is at the *end* of the list.
     *   `null` entries represent the root (un-zoomed) view. Pushed by
     *   every zoom-changing intent ([zoomInto], [zoomTo], [zoomOut],
     *   [zoomToParent]); popped by [zoomBack].
     * @property zoomForward Browser-style forward stack populated by
     *   [zoomBack] and consumed by [zoomForward]. Cleared whenever the
     *   user navigates to a new (non-history) target so forward only ever
     *   points along the path you arrived at via back.
     */
    data class State(
        val documentState: DocumentBackingViewModel.State? = null,
        val cursorRow: Int = 0,
        val cursorCol: Int = 0,
        val anchorRow: Int? = null,
        val anchorCol: Int? = null,
        val zoomedLineId: LineId? = null,
        val zoomHistory: List<LineId?> = emptyList(),
        val zoomForward: List<LineId?> = emptyList(),
        /**
         * Browser-style back stack of *files* the user has switched away
         * from (vault-relative paths). Pushed by [navigateToVaultFile]
         * when it swaps the active document; popped by [zoomBack] after
         * the per-file zoom history is exhausted, so a single Back chord
         * walks zoom-back first, then file-back.
         */
        val fileHistory: List<String> = emptyList(),
        /**
         * Mirror of [fileHistory] populated by [zoomBack] when it pops a
         * file entry, and consumed by [zoomForward]. Cleared whenever
         * the user navigates to a new file directly (so forward never
         * leads somewhere they didn't arrive at via back).
         */
        val fileForward: List<String> = emptyList(),
        /**
         * Within-file fold state. A bullet whose [LineId] is in this set is
         * rendered with its chevron rotated and its descendants hidden by
         * the paint loop and skipped by hit-testing. File-boundary refs
         * also live here while collapsed; expanding them additionally calls
         * `DocumentBackingViewModel.expandSubtree` to lazy-load the file.
         *
         * Within-file collapse is intentionally *not* an unload: the rows
         * already live in the parent file the user just loaded, so hiding
         * them visually has no further memory benefit and avoids
         * complications with autosave seeing a truncated parent file.
         */
        val collapsedIds: Set<LineId> = emptySet(),
        /**
         * Set of [LineId]s the default-collapse pass has already processed,
         * so the same id is not auto-collapsed again after the user has
         * explicitly expanded it. Each emission of `documentState` runs the
         * pass on `documentState.lineIds - seenLineIds`, adding parent
         * bullets and refs found in that diff to [collapsedIds]. Internal
         * — not meaningful to consumers.
         */
        internal val seenLineIds: Set<LineId> = emptySet(),
        /**
         * Master toggle for the editor's filesystem-tree footer. When `true`,
         * the footer renders below the document at zoom-root with whatever
         * folders the user has opened in [expandedVaultPaths]. When `false`,
         * the footer collapses to its single header row. Per-viewer (each
         * window/pane can show or hide the footer independently).
         */
        val isVaultFooterExpanded: Boolean = true,
        /**
         * Paths (relative to the vault root) of folders the user has opened
         * in the filesystem-tree footer. Folders not in this set render
         * collapsed; folders in it render their children one indent deeper.
         * Same shape and spirit as [collapsedIds] but indexed by path string
         * because vault entries don't have stable [LineId]s — they aren't
         * part of the document model. Lazy: adding a path here triggers
         * `DocumentBackingViewModel.ensureVaultListing(path)` so the listing
         * is fetched on first expand.
         */
        val expandedVaultPaths: Set<String> = emptySet(),
        /**
         * Inline styles the user has armed via Cmd-B / Cmd-I / the dropdown
         * while the caret was collapsed (no selection). The very next
         * character or text inserted at the caret is wrapped with the
         * markers for these styles, then the set clears. Cleared on any
         * cursor movement (so an arrow key cancels the pending style) and
         * on any non-typing edit.
         *
         * Empty when no style is pending — the common case.
         */
        val pendingInlineStyles: Set<InlineStyle> = emptySet(),
    ) {
        /** `true` once the document has loaded from disk at least once. */
        val isLoaded: Boolean get() = documentState?.isLoaded == true

        /**
         * `true` while the document VM is mid-save on a tick that promotes or
         * demotes a subtree across the per-file boundary — see
         * [DocumentBackingViewModel.State.isRestructuring]. Surfaced here so
         * platform views can render a small status indicator without poking
         * into the document-level state directly.
         */
        val isRestructuring: Boolean get() = documentState?.isRestructuring == true

        /** Convenience accessor — never null, falls back to a single empty line. */
        val lines: List<String> get() = documentState?.lines ?: listOf("")

        /**
         * `true` when the editor is currently displaying the configured
         * root file (so the vault-tree footer should render).
         */
        fun isAtRootFile(rootFileName: String): Boolean =
            documentState?.activeFileRel == rootFileName
    }

    /**
     * A normalized selection range (start ≤ end, by row-then-column), or
     * absent when the caret is not extending any selection. Produced by
     * `selectionOf` from the raw anchor/cursor pair.
     */
    data class Selection(val startRow: Int, val startCol: Int, val endRow: Int, val endCol: Int)

    /**
     * Resolved zoom geometry. Computed on demand by `zoomInfoOf` from the
     * current zoom id and document state — never stored, so it cannot go
     * stale.
     *
     * @property zoomRow The absolute row of the zoom target bullet itself.
     * @property zoomIndent The indent (leading-space count) of the zoom
     *   target line. Descendant lines are those with indent strictly greater
     *   than this.
     * @property startRow First visible row (`zoomRow + 1`).
     * @property endRowInclusive Last visible row. If `endRowInclusive < startRow`
     *   the subtree is currently empty.
     * @property titleText The display text of the zoom target with the
     *   leading indent and `"* "` marker stripped. Used by the view to
     *   render the sticky header.
     */
    data class ZoomInfo(
        val zoomRow: Int,
        val zoomIndent: Int,
        val startRow: Int,
        val endRowInclusive: Int,
        val titleText: String
    ) {
        /** `true` when the zoom target has at least one descendant bullet. */
        val hasVisibleRows: Boolean get() = startRow <= endRowInclusive
    }

    private val _stateFlow = MutableStateFlow(State())

    /**
     * Observable stream of view states. Platform `MainViewModel`s collect
     * this and re-emit through their own envelope.
     */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    private val textEditing = TextEditingViewModel(
        documentBackingViewModel = documentBackingViewModel,
        stateProvider = { _stateFlow.value },
        applyState = { _stateFlow.value = it },
        mutate = { transform -> mutate(transform) },
        patch = { transform -> patch(transform) },
    )

    private val zoomNavigation = ZoomNavigation(
        documentBackingViewModel = documentBackingViewModel,
        stateProvider = { _stateFlow.value },
        patch = { transform -> patch(transform) },
        scope = scope,
    )

    private val markdownStyle = MarkdownStyleViewModel(
        documentBackingViewModel = documentBackingViewModel,
        stateProvider = { _stateFlow.value },
        patch = { transform -> patch(transform) },
        selectWord = { row, col -> textEditing.selectWord(row, col) },
    )

    init {
        scope.launch {
            documentBackingViewModel.stateFlow.collect { docState ->
                // Clear undo/redo when the editor swaps to a different file:
                // the captured snapshots reference content from the
                // previous document and would silently corrupt the new one
                // if applied. Detected on the first emission whose
                // activeFileRel differs from what we last saw loaded.
                if (docState.isLoaded && docState.activeFileRel != lastObservedActiveFile) {
                    undoStack.clear()
                    redoStack.clear()
                    lastObservedActiveFile = docState.activeFileRel
                }
                val merged = _stateFlow.value.copy(documentState = docState)
                val withDefaults = applyDefaultCollapseIfNeeded(merged)
                _stateFlow.value = reconcile(withDefaults)
            }
        }
    }

    /**
     * Adds newly observed parent-bullet and promoted-ref [LineId]s to
     * [State.collapsedIds] so each subtree starts folded the first time
     * we see it. The pass runs on `lineIds - seenLineIds` so it does not
     * re-collapse ids the user has explicitly expanded — once an id flows
     * through this once it joins [State.seenLineIds] and is never
     * re-considered.
     *
     * Triggered initially on `isLoaded: false → true`, and again on every
     * subsequent emission that introduces fresh ids (e.g. the document VM
     * spliced in children on `expandSubtree`, or the user typed a newline
     * which allocated a new id).
     */
    private fun applyDefaultCollapseIfNeeded(state: State): State {
        val docState = state.documentState ?: return state
        if (!docState.isLoaded) return state
        val seen = state.seenLineIds
        val currentIds = docState.lineIds
        // Fast path: no new ids to consider.
        if (seen.size == currentIds.size && seen.containsAll(currentIds)) return state

        val newCollapsed = HashSet<LineId>()
        for ((idx, id) in currentIds.withIndex()) {
            if (id in seen) continue
            if (idx !in docState.lines.indices) continue
            val line = docState.lines[idx]
            val indent = DocumentLayout.bulletAsteriskColumn(line)
            if (indent < 0) continue
            val isParent = DocumentLayout.hasChildren(docState.lines, idx, indent)
            val isRef = documentBackingViewModel.isPromotedRef(id)
            if (isParent || isRef) newCollapsed += id
        }
        val nextSeen = seen + currentIds
        if (newCollapsed.isEmpty() && nextSeen.size == seen.size) return state
        return state.copy(
            collapsedIds = state.collapsedIds + newCollapsed,
            seenLineIds = nextSeen,
        )
    }

    /**
     * `true` if [lineId] is a file-boundary reference whose children live
     * in a separate `.nogr` file. The paint loop uses this to decide
     * whether to render a chevron on a bullet that has no children
     * currently in `lines` (because the file is folded).
     */
    fun isPromotedRef(lineId: LineId): Boolean = documentBackingViewModel.isPromotedRef(lineId)

    /**
     * Toggle the fold state of [lineId]. For file-boundary references the
     * call also drives `DocumentBackingViewModel.expandSubtree` /
     * `collapseSubtree` so children are lazy-loaded on expand and unloaded
     * on collapse. For within-file parents, only [State.collapsedIds] is
     * mutated — the children are already in `lines` and stay there.
     */
    fun toggleCollapse(lineId: LineId) {
        val current = _stateFlow.value
        if (!current.isLoaded) return
        val isRef = documentBackingViewModel.isPromotedRef(lineId)
        if (isRef) {
            val docState = current.documentState
            val isExpanded = docState != null && lineId in docState.expandedRefIds
            if (isExpanded) {
                documentBackingViewModel.collapseSubtree(lineId)
                patch { it.copy(collapsedIds = it.collapsedIds + lineId) }
            } else {
                // Optimistically mark as expanded in view state so the chevron
                // animates open immediately. The expand intent re-publishes
                // documentState; our collector then runs reconcile.
                patch { it.copy(collapsedIds = it.collapsedIds - lineId) }
                scope.launch { documentBackingViewModel.expandSubtree(lineId) }
            }
        } else {
            patch {
                val next = if (lineId in it.collapsedIds) {
                    it.collapsedIds - lineId
                } else {
                    it.collapsedIds + lineId
                }
                it.copy(collapsedIds = next)
            }
        }
    }

    /**
     * Removes every ancestor of [row] from [State.collapsedIds] so the row
     * is visible. Walks upward through bullet rows of strictly decreasing
     * indent. Used by editing intents (Tab indent, newline that creates a
     * child) to auto-expand the parent of newly inserted content.
     */
    internal fun revealAncestors(row: Int) {
        val current = _stateFlow.value
        val docState = current.documentState ?: return
        if (row !in docState.lines.indices) return
        val rowIndent = DocumentLayout.bulletAsteriskColumn(docState.lines[row])
        var lookingFor = if (rowIndent >= 0) rowIndent else Int.MAX_VALUE
        if (lookingFor <= 0) return
        val toReveal = mutableSetOf<LineId>()
        var r = row - 1
        while (r >= 0 && lookingFor > 0) {
            val col = DocumentLayout.bulletAsteriskColumn(docState.lines[r])
            if (col in 0 until lookingFor) {
                toReveal += docState.lineIds[r]
                lookingFor = col
            }
            r--
        }
        if (toReveal.isNotEmpty() && (toReveal intersect current.collapsedIds).isNotEmpty()) {
            patch { it.copy(collapsedIds = it.collapsedIds - toReveal) }
        }
    }

    // ------------------------------------------------------------------ edits

    /** See [TextEditingViewModel.insertChar]. */
    fun insertChar(char: Char) {
        // Classify as TYPING so consecutive single-char inserts of word
        // characters fold into one undo step. The coalescing predicate in
        // [shouldCoalesce] still rejects whitespace and selection-replace
        // boundaries, so this kind is safe even when the inner intent
        // happens to delete a selection first.
        recordEdit(FrameKind.TYPING) { textEditing.insertChar(char) }
    }

    /** See [TextEditingViewModel.insertNewline]. */
    fun insertNewline() {
        recordEdit(FrameKind.OTHER) {
            textEditing.insertNewline()
            revealAncestors(_stateFlow.value.cursorRow)
        }
    }

    /** See [TextEditingViewModel.insertText]. */
    fun insertText(text: String) {
        recordEdit(FrameKind.OTHER) {
            textEditing.insertText(text)
            if ('\n' in text || '\r' in text) revealAncestors(_stateFlow.value.cursorRow)
        }
    }

    /** See [TextEditingViewModel.backspace]. */
    fun backspace() {
        recordEdit(FrameKind.BACKSPACE) { textEditing.backspace() }
    }

    /** See [TextEditingViewModel.indentLine]. */
    fun indentLine(amount: Int = TAB_SIZE) {
        recordEdit(FrameKind.OTHER) {
            textEditing.indentLine(amount)
            // Tab on a bullet line slots it under the previous bullet as a child;
            // if that parent was folded, reveal it so the new child stays visible.
            revealAncestors(_stateFlow.value.cursorRow)
        }
    }

    /** See [TextEditingViewModel.outdentLine]. */
    fun outdentLine(amount: Int = TAB_SIZE) {
        recordEdit(FrameKind.OTHER) { textEditing.outdentLine(amount) }
    }

    /** See [TextEditingViewModel.isBulletLine]. */
    fun isBulletLine(): Boolean = textEditing.isBulletLine()

    /**
     * Resolves the absolute row range owned by the bullet at [row]: the bullet
     * itself plus its descendants up to (but not including) the next sibling-
     * or-shallower row. Returns `null` if [row] is out of range or its line is
     * not a bullet.
     *
     * ### Callers
     * - The web `MainScreen` drag-handler when the user mousedowns on a bullet
     *   glyph, to know which rows form the moveable block.
     */
    fun subtreeRange(row: Int): IntRange? {
        val docState = documentBackingViewModel.stateFlow.value
        if (!docState.isLoaded) return null
        val lines = docState.lines
        if (row !in lines.indices) return null
        val indent = DocumentLayout.bulletAsteriskColumn(lines[row])
        if (indent < 0) return null
        return row..DocumentLayout.subtreeEnd(lines, row, indent)
    }

    /**
     * Move the contiguous row range `[fromStartRow..fromEndRow]` (both
     * inclusive, absolute document coordinates) so that, after the move, the
     * first moved row sits immediately above the row that was originally at
     * [insertBeforeRow]. Pass `lines.size` as [insertBeforeRow] to drop at
     * the very end of the document.
     *
     * Pure view-layer composition: the document VM's `delete(...)` and
     * `insertText(...)` primitives are called in sequence, then a single
     * trailing `patch { }` restores cursor + selection so observers see one
     * consistent post-move emission.
     *
     * **Indent rewriting (v1).** Every moved line is reindented by
     * `targetIndent - sourceTopIndent`, clamped per-line so no line goes
     * below indent 0. The top of the moved block ends up with indent
     * [targetIndent]; deeper descendants keep their relative offset.
     *
     * **Disallowed cases.** Drop *inside* the moved range, drop immediately
     * above or below the unchanged source, and "move the whole document" all
     * silently no-op.
     *
     * **Id semantics.** The moved block lands with freshly-allocated line
     * ids (since the rows are re-inserted via `insertText`). The
     * default-collapse pass in [applyDefaultCollapseIfNeeded] will re-fold
     * any parent bullets among the new ids on the next emission, so a
     * folded parent stays visually folded after the move. Per-id zoom or
     * collapse state targeting the moved rows is discarded — these are
     * transient view affordances and the cost of preserving them across a
     * move would require a new `DocumentBackingViewModel` primitive.
     *
     * **Cursor.** After the move, the caret is collapsed at the start of
     * the moved block's first line (after any bullet prefix). No selection
     * is restored — typing immediately after a drop must not destroy the
     * just-moved content.
     *
     * ### Callers
     * - The web `MainScreen` drag-and-drop handler, on `mouseup` after a
     *   successful drag (both bullet-dot drags and gutter selection drags).
     *
     * @param fromStartRow First row of the source block (inclusive).
     * @param fromEndRow Last row of the source block (inclusive). Must be
     *   ≥ [fromStartRow]; values are coerced into the legal range.
     * @param insertBeforeRow Pre-move row index that the moved block should
     *   land above. Coerced to `[0..lines.size]`. Treated as "drop at end of
     *   document" when it equals `lines.size`.
     * @param targetIndent New indent (leading-space count) for the top of
     *   the moved block. Pass the indent of the row immediately above the
     *   drop point (the simple v1 rule).
     */
    fun moveLineRange(
        fromStartRow: Int,
        fromEndRow: Int,
        insertBeforeRow: Int,
        targetIndent: Int,
    ) = recordEdit(FrameKind.OTHER) {
        val docStart = documentBackingViewModel.stateFlow.value
        if (!docStart.isLoaded) return@recordEdit
        val lines0 = docStart.lines
        if (lines0.isEmpty()) return@recordEdit

        val src0 = fromStartRow.coerceIn(0, lines0.lastIndex)
        val src1 = fromEndRow.coerceIn(src0, lines0.lastIndex)
        val drop = insertBeforeRow.coerceIn(0, lines0.size)

        // Disallowed cases: drop strictly inside the source, or drop adjacent
        // to source on either side (no-op moves), or moving the entire doc.
        if (drop in (src0 + 1)..src1) return@recordEdit
        if (drop == src0 || drop == src1 + 1) return@recordEdit
        if (src0 == 0 && src1 == lines0.lastIndex) return@recordEdit

        val deletedRowCount = src1 - src0 + 1
        val sourceTopIndent = leadingSpaceCount(lines0[src0])
        val shift = targetIndent - sourceTopIndent
        val movedText = (src0..src1).joinToString("\n") { reindentLine(lines0[it], shift) }

        // 1. Delete the source rows as whole rows. Two cases avoid clobbering
        //    surviving ids by keeping `startRow` outside the moved block where
        //    possible. When the source includes row 0, we have no row above
        //    to anchor on; row 0's id is preserved but takes the content of
        //    the row that previously followed the source. The default-
        //    collapse pass will re-fold parent bullets among the new ids on
        //    the next emission.
        if (src0 > 0) {
            val above = lines0[src0 - 1]
            val tailRow = src1
            documentBackingViewModel.delete(src0 - 1, above.length, tailRow, lines0[tailRow].length)
        } else {
            // src0 == 0 and src1 < lines0.lastIndex (the all-doc case is
            // already filtered above). Remove rows [0..src1] by collapsing
            // them with row src1+1 as the survivor's content source.
            documentBackingViewModel.delete(0, 0, src1 + 1, 0)
        }

        // 2. Translate the pre-delete drop index into post-delete coordinates.
        val postDeleteInsertRow = if (drop <= src0) drop else drop - deletedRowCount

        // 3. Insert. Either prepend at row[postDeleteInsertRow] with a trailing
        //    newline, or append at end-of-doc when the drop went past the new
        //    last row.
        val newLines = documentBackingViewModel.stateFlow.value.lines
        val landedFirst: Int
        val landedLast: Int
        if (postDeleteInsertRow > newLines.lastIndex) {
            val lastIdx = newLines.lastIndex
            documentBackingViewModel.insertText(lastIdx, newLines[lastIdx].length, "\n" + movedText)
            landedFirst = lastIdx + 1
            landedLast = landedFirst + deletedRowCount - 1
        } else {
            documentBackingViewModel.insertText(postDeleteInsertRow, 0, movedText + "\n")
            landedFirst = postDeleteInsertRow
            landedLast = landedFirst + deletedRowCount - 1
        }

        // 4. Place a collapsed caret at the start of the moved block's
        //    first line (after any bullet prefix). Restoring a selection
        //    would let an accidental keystroke delete the just-moved
        //    content. A single trailing `patch { }` so observers see one
        //    emission with the new doc + cursor.
        val finalLines = documentBackingViewModel.stateFlow.value.lines
        val safeFirst = landedFirst.coerceIn(0, finalLines.lastIndex)
        val caretCol = DocumentLayout.caretStartCol(finalLines[safeFirst])
        patch {
            it.copy(
                anchorRow = null,
                anchorCol = null,
                cursorRow = safeFirst,
                cursorCol = caretCol,
            )
        }
    }

    /**
     * Returns the count of leading whitespace characters on [line]; equal to
     * [line]'s length when the line is entirely whitespace.
     */
    private fun leadingSpaceCount(line: String): Int {
        val nonWs = line.indexOfFirst { !it.isWhitespace() }
        return if (nonWs < 0) line.length else nonWs
    }

    /**
     * Shifts [line]'s leading whitespace by [delta], clamped so the resulting
     * indent is never negative. Non-whitespace content (including any `"* "`
     * bullet marker) is preserved verbatim.
     */
    private fun reindentLine(line: String, delta: Int): String {
        val nonWs = leadingSpaceCount(line)
        val newIndent = (nonWs + delta).coerceAtLeast(0)
        return " ".repeat(newIndent) + line.substring(nonWs)
    }

    // ------------------------------------------------------------------ zoom

    /** See [ZoomNavigation.zoomInto]. */
    fun zoomInto(row: Int) = zoomNavigation.zoomInto(row)

    /** See [ZoomNavigation.zoomOut]. */
    fun zoomOut() = zoomNavigation.zoomOut()

    /** See [ZoomNavigation.zoomTo]. */
    fun zoomTo(lineId: LineId?) = zoomNavigation.zoomTo(lineId)

    /**
     * Walk one step back in the unified navigation history. Pops the
     * per-file zoom-history stack first; when that's empty, falls
     * through to the cross-file [State.fileHistory] stack and switches
     * to the previous document. The pane back button and the
     * Cmd-Opt-Left chord both call this.
     */
    fun zoomBack() {
        val s = _stateFlow.value
        if (s.zoomHistory.isNotEmpty()) {
            zoomNavigation.zoomBack()
            return
        }
        if (s.fileHistory.isNotEmpty()) {
            scope.launch { fileBack() }
        }
    }

    /** Mirror of [zoomBack] for the forward direction. */
    fun zoomForward() {
        val s = _stateFlow.value
        if (s.zoomForward.isNotEmpty()) {
            zoomNavigation.zoomForward()
            return
        }
        if (s.fileForward.isNotEmpty()) {
            scope.launch { fileForward() }
        }
    }

    /** `true` when there's somewhere to go back to (zoom or file). */
    fun canZoomBack(state: State = _stateFlow.value): Boolean =
        state.zoomHistory.isNotEmpty() || state.fileHistory.isNotEmpty()

    /** `true` when there's somewhere to go forward to (zoom or file). */
    fun canZoomForward(state: State = _stateFlow.value): Boolean =
        state.zoomForward.isNotEmpty() || state.fileForward.isNotEmpty()

    private suspend fun fileBack() {
        val previous = _stateFlow.value.fileHistory.lastOrNull() ?: return
        val currentFile = documentBackingViewModel.stateFlow.value.activeFileRel
        documentBackingViewModel.switchTo(previous)
        patch {
            it.copy(
                cursorRow = 0, cursorCol = 0,
                anchorRow = null, anchorCol = null,
                zoomedLineId = null,
                zoomHistory = emptyList(),
                zoomForward = emptyList(),
                collapsedIds = emptySet(),
                seenLineIds = emptySet(),
                fileHistory = it.fileHistory.dropLast(1),
                fileForward = (it.fileForward + currentFile).takeLast(NAV_HISTORY_CAP),
            )
        }
    }

    private suspend fun fileForward() {
        val next = _stateFlow.value.fileForward.lastOrNull() ?: return
        val currentFile = documentBackingViewModel.stateFlow.value.activeFileRel
        documentBackingViewModel.switchTo(next)
        patch {
            it.copy(
                cursorRow = 0, cursorCol = 0,
                anchorRow = null, anchorCol = null,
                zoomedLineId = null,
                zoomHistory = emptyList(),
                zoomForward = emptyList(),
                collapsedIds = emptySet(),
                seenLineIds = emptySet(),
                fileHistory = (it.fileHistory + currentFile).takeLast(NAV_HISTORY_CAP),
                fileForward = it.fileForward.dropLast(1),
            )
        }
    }

    /** See [ZoomNavigation.zoomInfo]. */
    fun zoomInfo(state: State = _stateFlow.value): ZoomInfo? = zoomNavigation.zoomInfo(state)

    /** See [ZoomNavigation.bulletAncestors]. */
    fun bulletAncestors(state: State = _stateFlow.value): List<BreadcrumbAncestor> =
        zoomNavigation.bulletAncestors(state)

    /** See [ZoomNavigation.zoomPathSegments]. */
    fun zoomPathSegments(state: State = _stateFlow.value): List<String> =
        zoomNavigation.zoomPathSegments(state)

    // ------------------------------------------------------------------ movement

    /** See [TextEditingViewModel.moveLeft]. */
    fun moveLeft(extend: Boolean = false) = textEditing.moveLeft(extend)

    /** See [TextEditingViewModel.moveRight]. */
    fun moveRight(extend: Boolean = false) = textEditing.moveRight(extend)

    /** See [TextEditingViewModel.moveUp]. */
    fun moveUp(extend: Boolean = false) = textEditing.moveUp(extend)

    /** See [TextEditingViewModel.moveDown]. */
    fun moveDown(extend: Boolean = false) = textEditing.moveDown(extend)

    /** See [TextEditingViewModel.moveTo]. */
    fun moveTo(row: Int, col: Int, extend: Boolean = false) = textEditing.moveTo(row, col, extend)

    /** See [TextEditingViewModel.moveLineStart]. */
    fun moveLineStart(extend: Boolean = false) = textEditing.moveLineStart(extend)

    /** See [TextEditingViewModel.moveLineEnd]. */
    fun moveLineEnd(extend: Boolean = false) = textEditing.moveLineEnd(extend)

    /** See [TextEditingViewModel.moveDocStart]. */
    fun moveDocStart(extend: Boolean = false) = textEditing.moveDocStart(extend)

    /** See [TextEditingViewModel.moveDocEnd]. */
    fun moveDocEnd(extend: Boolean = false) = textEditing.moveDocEnd(extend)

    /** See [TextEditingViewModel.moveWordLeft]. */
    fun moveWordLeft(extend: Boolean = false) = textEditing.moveWordLeft(extend)

    /** See [TextEditingViewModel.moveWordRight]. */
    fun moveWordRight(extend: Boolean = false) = textEditing.moveWordRight(extend)

    // ------------------------------------------------------------------ selection

    /** See [TextEditingViewModel.selectAll]. */
    fun selectAll() = textEditing.selectAll()

    /** See [TextEditingViewModel.selectWord]. */
    fun selectWord(row: Int, col: Int) = textEditing.selectWord(row, col)

    /** See [TextEditingViewModel.selectLine]. */
    fun selectLine(row: Int) = textEditing.selectLine(row)

    /** See [TextEditingViewModel.clearSelection]. */
    fun clearSelection() = textEditing.clearSelection()

    /**
     * Pushes a logical selection into the viewer, used by platforms whose
     * native text surface owns the live caret (web `contenteditable`,
     * Compose `BasicTextField`, UIKit `UITextView`). The platform reads its
     * surface's anchor/cursor on demand and forwards them here so the
     * commonMain layer's selection-aware intents (`backspace`,
     * `deleteSelectionIfAny`, `onCutRequested`, …) can compose primitive
     * edits against a freshly-synced range without the platform having to
     * call `moveTo` twice.
     *
     * @param anchorRow Row of the selection anchor (the end the user pinned
     *   first). Pass the same value as [cursorRow] for a collapsed caret.
     * @param anchorCol Column of the anchor on [anchorRow].
     * @param cursorRow Row of the active end of the selection.
     * @param cursorCol Column of the active end on [cursorRow].
     */
    fun setSelection(anchorRow: Int, anchorCol: Int, cursorRow: Int, cursorCol: Int) {
        val current = _stateFlow.value
        if (!current.isLoaded) return
        val collapsed = anchorRow == cursorRow && anchorCol == cursorCol
        // A new caret position from the platform (mouse click, native arrow
        // sync) cancels any armed inline styles for the same reason as
        // [moved]. Skip the clear when nothing actually moved (the
        // platform re-pushes selection on every keystroke and we don't
        // want a no-op sync to wipe a freshly-armed style).
        val moved = current.cursorRow != cursorRow || current.cursorCol != cursorCol ||
            current.anchorRow != (if (collapsed) null else anchorRow) ||
            current.anchorCol != (if (collapsed) null else anchorCol)
        val pending = if (moved) emptySet() else current.pendingInlineStyles
        _stateFlow.value = reconcile(
            current.copy(
                cursorRow = cursorRow,
                cursorCol = cursorCol,
                anchorRow = if (collapsed) null else anchorRow,
                anchorCol = if (collapsed) null else anchorCol,
                pendingInlineStyles = pending,
            )
        )
    }

    /** See [TextEditingViewModel.deleteSelectionIfAny]. */
    fun deleteSelectionIfAny(): Boolean {
        var result = false
        recordEdit(FrameKind.OTHER) { result = textEditing.deleteSelectionIfAny() }
        return result
    }

    /** See [TextEditingViewModel.getSelectedText]. */
    fun getSelectedText(): String? = textEditing.getSelectedText()

    /** See [TextEditingViewModel.onCutRequested]. */
    fun onCutRequested(): String? {
        var text: String? = null
        recordEdit(FrameKind.OTHER) { text = textEditing.onCutRequested() }
        return text
    }

    // ------------------------------------------------------- markdown styles

    /** See [MarkdownStyleViewModel.applyInlineStyle]. */
    fun applyInlineStyle(style: InlineStyle) {
        recordEdit(FrameKind.OTHER) { markdownStyle.applyInlineStyle(style) }
    }

    /** See [MarkdownStyleViewModel.applyLineStyle]. */
    fun applyLineStyle(style: LineStyle) {
        recordEdit(FrameKind.OTHER) { markdownStyle.applyLineStyle(style) }
    }

    /** See [MarkdownStyleViewModel.activeInlineStyles]. */
    fun activeInlineStyles(): Set<InlineStyle> = markdownStyle.activeInlineStyles()

    /** See [MarkdownStyleViewModel.activeLineStyle]. */
    fun activeLineStyle(): LineStyle? = markdownStyle.activeLineStyle()

    // ----------------------------------------------------------- vault footer

    /**
     * Flips [State.isVaultFooterExpanded]. Invoked by the master chevron
     * next to the footer's "Files" header.
     */
    fun toggleVaultFooter() {
        patch { it.copy(isVaultFooterExpanded = !it.isVaultFooterExpanded) }
    }

    /**
     * Toggles whether the folder at [dirRel] is open in the filesystem-tree
     * footer. Adding a path also kicks off
     * `DocumentBackingViewModel.ensureVaultListing(dirRel)` so the folder's
     * direct children are fetched on first expand. Removing a path keeps the
     * cached listing — same cheap behaviour as collapsing a within-file
     * parent (no memory benefit to clearing it, and instant re-open).
     */
    fun toggleVaultFolder(dirRel: String) {
        val current = _stateFlow.value
        val isOpening = dirRel !in current.expandedVaultPaths
        patch {
            val next = if (dirRel in it.expandedVaultPaths) {
                it.expandedVaultPaths - dirRel
            } else {
                it.expandedVaultPaths + dirRel
            }
            it.copy(expandedVaultPaths = next)
        }
        if (isOpening) {
            scope.launch { documentBackingViewModel.ensureVaultListing(dirRel) }
        }
    }

    /**
     * Switches the editor to the markdown file at [pathRel]. The clicked
     * file becomes the active document — its content replaces the
     * editor's lines, its `#notegrow` link bullets become the new outline,
     * and autosave writes back to it. The previously-active file's
     * pending changes (if any) are flushed first.
     *
     * Resets per-viewer state (cursor, anchor, selection, zoom history,
     * collapse state) since none of it is meaningful in the new file.
     *
     * Runs on [scope]; callers fire and forget.
     *
     * @param pathRel Vault-relative path to the file, including the `.md`
     *   extension (e.g. `Recipes/Recipes.md`, `links.md`,
     *   `Recipes/Quick Granola.md`).
     */
    fun navigateToVaultFile(pathRel: String) {
        if (!_stateFlow.value.isLoaded) return
        val currentFile = documentBackingViewModel.stateFlow.value.activeFileRel
        if (currentFile == pathRel) return
        scope.launch {
            documentBackingViewModel.switchTo(pathRel)
            patch {
                it.copy(
                    cursorRow = 0,
                    cursorCol = 0,
                    anchorRow = null,
                    anchorCol = null,
                    zoomedLineId = null,
                    zoomHistory = emptyList(),
                    zoomForward = emptyList(),
                    collapsedIds = emptySet(),
                    seenLineIds = emptySet(),
                    // Browser-style: pushing to history kills forward.
                    fileHistory = (it.fileHistory + currentFile).takeLast(NAV_HISTORY_CAP),
                    fileForward = emptyList(),
                )
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Applies [transform] to the current state, then re-runs [reconcile]
     * (cursor clamping, zoom validity) and publishes. Used by every movement
     * and selection intent that does not touch document content.
     */
    private inline fun mutate(transform: (State) -> State) {
        val current = _stateFlow.value
        if (!current.isLoaded) return
        _stateFlow.value = reconcile(transform(current))
    }

    /**
     * Patches the view state while ensuring [State.documentState] is
     * refreshed to the document VM's latest. Used right after calling a
     * document VM mutation so readers never see a view state whose cursor
     * references stale document content.
     */
    private inline fun patch(transform: (State) -> State) {
        val current = _stateFlow.value
        val patched = transform(current).copy(
            documentState = documentBackingViewModel.stateFlow.value
        )
        _stateFlow.value = reconcile(patched)
    }

    /**
     * Post-mutation normalization: clamps caret/anchor into the current
     * document, self-heals zoom state when the target disappears or its
     * subtree becomes empty, and clamps caret/anchor into the zoom range
     * when zoomed.
     *
     * Called from every publish path ([init]'s collector, [mutate],
     * [patch]).
     *
     * @param state The candidate state produced by an intent.
     * @return A normalized state safe to publish.
     */
    private fun reconcile(state: State): State {
        val docState = state.documentState
        if (docState?.isLoaded != true) return state
        val lines = state.lines
        val lastRow = lines.lastIndex
        val baseRow = state.cursorRow.coerceIn(0, lastRow)
        // Defensive lower bound: on bullet rows the cursor must sit at or after the
        // `"* "` marker. Anchors are *not* clamped — explicit `selectLine` / `selectAll`
        // intentionally anchor at column 0, and we don't want this safety net to
        // alter their semantics.
        val baseLineMin = DocumentLayout.caretStartCol(lines[baseRow])
        val baseCol = state.cursorCol.coerceIn(baseLineMin, lines[baseRow].length)
        val baseAr = state.anchorRow?.coerceIn(0, lastRow)
        val baseAc = if (baseAr != null) state.anchorCol?.coerceIn(0, lines[baseAr].length) else null
        var clamped = state.copy(
            cursorRow = baseRow, cursorCol = baseCol,
            anchorRow = baseAr, anchorCol = baseAc
        )
        if (clamped.zoomedLineId != null) {
            val zoom = zoomInfoOf(clamped)
            if (zoom == null || !zoom.hasVisibleRows) {
                clamped = clamped.copy(zoomedLineId = null)
            } else {
                val zRow = clamped.cursorRow.coerceIn(zoom.startRow, zoom.endRowInclusive)
                val zLineMin = DocumentLayout.caretStartCol(lines[zRow])
                val zCol = clamped.cursorCol.coerceIn(zLineMin, lines[zRow].length)
                val zAr = clamped.anchorRow?.coerceIn(zoom.startRow, zoom.endRowInclusive)
                val zAc = if (zAr != null) clamped.anchorCol?.coerceIn(0, lines[zAr].length) else null
                clamped = clamped.copy(cursorRow = zRow, cursorCol = zCol, anchorRow = zAr, anchorCol = zAc)
            }
        }
        // Clamp into visible rows: if the cursor sits inside a folded subtree
        // (or on a now-removed row from a ref collapse), walk up to the
        // nearest visible ancestor and clear selection.
        return clampToVisible(clamped)
    }

    /**
     * Walks the cursor (and anchor) up to the nearest visible row when the
     * current row sits inside a folded subtree. Selections are cleared on
     * any such adjustment — preserving them through a fold would require
     * mapping ranges through hidden rows, which is rarely useful and easy
     * to get wrong.
     */
    private fun clampToVisible(state: State): State {
        val docState = state.documentState ?: return state
        val zoom = zoomInfoOf(state)
        val startRow = zoom?.startRow ?: 0
        val endRowInclusive = zoom?.endRowInclusive ?: docState.lines.lastIndex
        if (endRowInclusive < startRow) return state
        val visible = DocumentLayout.visibleRowsOf(
            docState.lines, docState.lineIds, state.collapsedIds, startRow, endRowInclusive
        )
        if (visible.isEmpty()) return state
        val visibleSet = visible.toHashSet()
        if (state.cursorRow in visibleSet) return state
        // Walk upward to find the nearest visible ancestor.
        var target = state.cursorRow
        while (target > 0 && target !in visibleSet) target--
        if (target !in visibleSet) target = visible.first()
        val targetLine = docState.lines[target]
        val safeCol = state.cursorCol.coerceIn(
            DocumentLayout.caretStartCol(targetLine), targetLine.length
        )
        return state.copy(
            cursorRow = target,
            cursorCol = safeCol,
            anchorRow = null,
            anchorCol = null,
        )
    }

    // ------------------------------------------------------------------ undo / redo

    /**
     * Frozen view of the document content + caret + selection at one point
     * in time. Each [UndoFrame] holds two of these — the state right before
     * a recorded edit ran (used by undo) and the state right after (used by
     * redo). Stored by reference: [lines] / [lineIds] are immutable list
     * snapshots from `DocumentBackingViewModel.State`, which is replaced
     * wholesale on every edit, so capturing the reference is a true
     * snapshot — no defensive copy needed.
     */
    private data class Snapshot(
        val lines: List<String>,
        val lineIds: List<LineId>,
        val expandedRefIds: Set<LineId>,
        val cursorRow: Int,
        val cursorCol: Int,
        val anchorRow: Int?,
        val anchorCol: Int?,
    )

    /**
     * Classification of a recorded edit, used to decide whether two
     * consecutive frames may merge into one undo step.
     *
     * Only [TYPING] (single-character non-whitespace insertion at a
     * collapsed caret) and [BACKSPACE] (single-character deletion at a
     * collapsed caret) are considered for coalescing — see
     * [shouldCoalesce]. Everything else uses [OTHER] and never merges.
     */
    private enum class FrameKind { TYPING, BACKSPACE, OTHER }

    /**
     * One undo step. [before] is what undo restores, [after] is what redo
     * restores. [kind] and [timestampMs] feed the coalescing decision; if a
     * new frame coalesces into this one, [after] and [timestampMs] are
     * updated to the new edit's tail while [before] is preserved.
     */
    private data class UndoFrame(
        val before: Snapshot,
        val after: Snapshot,
        val kind: FrameKind,
        val timestampMs: Long,
    )

    private val undoStack: ArrayDeque<UndoFrame> = ArrayDeque()
    private val redoStack: ArrayDeque<UndoFrame> = ArrayDeque()

    /**
     * Tracks the most recent [DocumentBackingViewModel.State.activeFileRel]
     * the collector has seen loaded. When it changes (e.g. the user clicked
     * a different file in the vault footer), [undoStack] / [redoStack] are
     * cleared because their snapshots refer to the previous document.
     */
    private var lastObservedActiveFile: String? = null

    /** Monotonic time origin for [nowMs]. Set once at construction. */
    private val timeOrigin = TimeSource.Monotonic.markNow()

    /** Reentrancy depth for [recordEdit]; non-zero means a recording is in progress. */
    private var recordingDepth: Int = 0

    /**
     * Snapshot the current document + caret. Cheap — captures references to
     * the immutable list inside `DocumentBackingViewModel.State`, plus a few
     * scalars from the view state.
     */
    private fun snapshotNow(): Snapshot {
        val view = _stateFlow.value
        val doc = view.documentState ?: documentBackingViewModel.stateFlow.value
        return Snapshot(
            lines = doc.lines,
            lineIds = doc.lineIds,
            expandedRefIds = doc.expandedRefIds,
            cursorRow = view.cursorRow,
            cursorCol = view.cursorCol,
            anchorRow = view.anchorRow,
            anchorCol = view.anchorCol,
        )
    }

    private fun nowMs(): Long = timeOrigin.elapsedNow().inWholeMilliseconds

    /**
     * Wraps a document-mutating intent so its before / after snapshots are
     * captured and pushed onto [undoStack]. No-op edits (where the captured
     * snapshots are equal) are not recorded — this lets call sites stay
     * simple without having to decide whether their work actually touched
     * content.
     *
     * Reentrant calls (one wrapped intent invoking another) are flattened:
     * only the outermost call records, so a composite intent produces one
     * undo frame regardless of how many primitives it composes internally.
     *
     * @param kind Classification used by [shouldCoalesce]; pass
     *   [FrameKind.OTHER] for anything that isn't strict character-by-
     *   character typing or backspace.
     */
    private inline fun recordEdit(kind: FrameKind, block: () -> Unit) {
        if (recordingDepth > 0) {
            block()
            return
        }
        val before = snapshotNow()
        recordingDepth++
        try {
            block()
        } finally {
            recordingDepth--
        }
        val after = snapshotNow()
        if (before == after) return
        pushUndoFrame(UndoFrame(before, after, kind, nowMs()))
        redoStack.clear()
    }

    /**
     * Add [frame] to [undoStack], coalescing into the previous frame when
     * [shouldCoalesce] permits. Caps the stack at [MAX_UNDO_FRAMES] by
     * dropping the oldest entries.
     */
    private fun pushUndoFrame(frame: UndoFrame) {
        val top = undoStack.lastOrNull()
        if (top != null && shouldCoalesce(top, frame)) {
            undoStack.removeLast()
            undoStack.addLast(top.copy(after = frame.after, timestampMs = frame.timestampMs))
        } else {
            undoStack.addLast(frame)
        }
        while (undoStack.size > MAX_UNDO_FRAMES) undoStack.removeFirst()
    }

    /**
     * Coalescing rule. Returns `true` when [next] should merge into [prev]
     * so the user observes them as one undo step.
     *
     * Coalesces only when both frames are the same coalescing-eligible
     * kind ([FrameKind.TYPING] or [FrameKind.BACKSPACE]), no selection was
     * involved at either end, the time gap is under [COALESCE_WINDOW_MS],
     * and the caret was contiguous (the previous frame ended where the
     * next one begins). For [TYPING], also requires the inserted character
     * to be non-whitespace — typing a space is the conventional word
     * boundary that breaks a typing run.
     */
    private fun shouldCoalesce(prev: UndoFrame, next: UndoFrame): Boolean {
        if (prev.kind != next.kind) return false
        if (prev.kind != FrameKind.TYPING && prev.kind != FrameKind.BACKSPACE) return false
        if (prev.before.anchorRow != null || next.before.anchorRow != null) return false
        if (prev.after.anchorRow != null || next.after.anchorRow != null) return false
        if (next.timestampMs - prev.timestampMs > COALESCE_WINDOW_MS) return false
        if (prev.after.cursorRow != next.before.cursorRow) return false
        if (prev.after.cursorCol != next.before.cursorCol) return false
        if (prev.kind == FrameKind.TYPING) {
            // Inspect the line that was edited to identify the inserted
            // character. Only coalesce when it's a single non-whitespace
            // char appended at the caret (the common typing case); pasted
            // text or selection-replace produces different shapes that
            // fall through to a fresh frame.
            val r = next.before.cursorRow
            if (r !in next.before.lines.indices || r !in next.after.lines.indices) return false
            val beforeLine = next.before.lines[r]
            val afterLine = next.after.lines[r]
            if (afterLine.length != beforeLine.length + 1) return false
            val col = next.before.cursorCol
            if (col !in afterLine.indices) return false
            val inserted = afterLine[col]
            if (inserted.isWhitespace()) return false
        }
        return true
    }

    /**
     * Pop one frame from [undoStack], move it to [redoStack], and restore
     * its [UndoFrame.before] snapshot. No-op when there's nothing to undo
     * or the document has not finished loading.
     */
    fun undo() {
        if (!_stateFlow.value.isLoaded) return
        val frame = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(frame)
        while (redoStack.size > MAX_UNDO_FRAMES) redoStack.removeFirst()
        restoreSnapshot(frame.before)
    }

    /**
     * Mirror of [undo]: pop one frame from [redoStack], move it back to
     * [undoStack], and restore its [UndoFrame.after] snapshot. No-op when
     * there's nothing to redo.
     */
    fun redo() {
        if (!_stateFlow.value.isLoaded) return
        val frame = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(frame)
        while (undoStack.size > MAX_UNDO_FRAMES) undoStack.removeFirst()
        restoreSnapshot(frame.after)
    }

    /** `true` when there is at least one frame on the undo stack. */
    fun canUndo(): Boolean = undoStack.isNotEmpty()

    /** `true` when there is at least one frame on the redo stack. */
    fun canRedo(): Boolean = redoStack.isNotEmpty()

    /**
     * Apply [snap] to the document VM and view state in a single emission
     * pair. The document VM's [DocumentBackingViewModel.replaceContent]
     * publishes the new content; [patch] then restores cursor + selection
     * over the freshly-published documentState. Pending inline styles are
     * cleared — they're transient arming state that would be confusing to
     * resurrect mid-undo.
     */
    private fun restoreSnapshot(snap: Snapshot) {
        documentBackingViewModel.replaceContent(snap.lines, snap.lineIds, snap.expandedRefIds)
        patch {
            it.copy(
                cursorRow = snap.cursorRow,
                cursorCol = snap.cursorCol,
                anchorRow = snap.anchorRow,
                anchorCol = snap.anchorCol,
                pendingInlineStyles = emptySet(),
            )
        }
    }

    companion object {
        /** Standard indent step across the app (two spaces). */
        const val TAB_SIZE: Int = 2

        /** Maximum number of frames retained on either undo stack. */
        private const val MAX_UNDO_FRAMES: Int = 500

        /** Maximum gap between two coalescing-eligible frames, in milliseconds. */
        private const val COALESCE_WINDOW_MS: Long = 1_000L

        /** Cap on entries in [State.fileHistory] / [State.fileForward]. */
        const val NAV_HISTORY_CAP: Int = 50

        /**
         * Normalizes an anchor + cursor pair into a [Selection]. Returns
         * `null` when there is no anchor (caret only) or when the anchor
         * and caret coincide (empty selection). Used by the view layer so
         * it doesn't re-implement the "which end comes first" logic.
         */
        fun selectionOf(state: State): Selection? = se.soderbjorn.notegrow.main.selectionOf(state)
    }
}
