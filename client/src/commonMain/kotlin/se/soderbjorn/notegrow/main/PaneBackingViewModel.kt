/*
 * PaneBackingViewModel.kt
 * -----------------------
 * The per-pane backing view-model. One [PaneBackingViewModel] per visible
 * editor pane (window / device / split). Owns every piece of state that
 * is logically per-viewer:
 *
 *   - cursor, selection anchor
 *   - zoom target, zoom history, file history (cross-file back / forward)
 *   - within-file fold state (`collapsedIds`)
 *   - undo / redo stack
 *   - pending inline styles (Cmd-B armed + waiting for the next keystroke)
 *
 * It also holds a reference to *one* [Document] at a time — whichever
 * file the pane is currently viewing. Two panes pointed at the same
 * `fileRel` share one [Document] instance via [DocumentRegistry], so
 * concurrent edits show up live in both. Navigating to a different file
 * (via [navigateToVaultFile] / [fileBack] / [fileForward]) acquires the
 * new document, swaps the inner collector, and releases the old one.
 *
 * Editing behavior is split across small siblings in this package:
 *
 *   - [TextEditingViewModel] — typing, deletion, indent/outdent,
 *     movement, selection.
 *   - [ZoomNavigation]       — zoom in/out and zoom-info resolution.
 *   - [MarkdownStyleViewModel] — inline / line-level markdown styling.
 *   - SelectionHelper.kt     — pure helpers (no class).
 *
 * commonMain only — no DOM, Android UI, or UIKit imports.
 */

package se.soderbjorn.notegrow.main

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.TimeSource
import se.soderbjorn.notegrow.data.InlineStyle
import se.soderbjorn.notegrow.data.LineStyle
import se.soderbjorn.notegrow.data.VaultEntry

/**
 * Per-pane backing view-model. Mirrors the active [Document]'s content
 * and adds cursor, selection, zoom, file navigation, undo/redo, and
 * per-pane fold state on top.
 *
 * ### Callers
 * - Created per pane by the platform layer (web `AppShell.ensurePaneViewModel`)
 *   and wrapped in a thin platform-specific `MainViewModel` facade.
 * - Tests exercise the intents directly without going through a
 *   platform VM.
 *
 * @param registry The shared [DocumentRegistry] used to acquire/release
 *   [Document]s as the pane navigates between files.
 * @param scope Coroutine scope owning the inner collectors that mirror
 *   the active document's state and the registry's vault listings into
 *   this pane's state flow.
 * @param initialFileRel Vault-relative path of the file this pane should
 *   open with. Typically the configured root.
 */
class PaneBackingViewModel(
    private val registry: DocumentRegistry,
    private val scope: CoroutineScope,
    initialFileRel: String,
) {
    /** Mirrors [DocumentRegistry.rootFileName] for the view layer. */
    val rootFileName: String get() = registry.rootFileName

    /**
     * Immutable snapshot of one pane's state.
     *
     * @property activeFileRel Vault-relative path of the file this pane
     *   is currently viewing. Distinct per pane — two panes at the same
     *   file share a [Document] but each pane owns its own copy of
     *   [activeFileRel].
     * @property documentState Mirror of the active [Document]'s latest
     *   state, or `null` before the first emission. Kept in sync by
     *   the inner collector and by [patch] after every edit, so every
     *   emission is a consistent snapshot of document + pane state.
     * @property vaultListings Mirror of the registry's shared vault-tree
     *   directory cache. Mirrored here so view code can read pane state
     *   alone without a separate registry handle.
     * @property cursorRow Row of the caret, in absolute document coords.
     * @property cursorCol Column of the caret on [cursorRow].
     * @property anchorRow If non-null, together with [anchorCol] defines
     *   the other end of the active selection. `null` means "no
     *   selection — the caret is just a point".
     * @property anchorCol Column companion to [anchorRow].
     * @property zoomedLineId If non-null, the [LineId] of the bullet
     *   whose subtree is being shown. `null` means "at root".
     * @property zoomHistory Browser-style back stack of previous zoom
     *   targets within the active file. Capped at [NAV_HISTORY_CAP].
     * @property zoomForward Browser-style forward stack populated by
     *   [zoomBack] and consumed by [zoomForward].
     * @property fileHistory Browser-style back stack of `(fileRel,
     *   zoomedLineId)` pairs the user has switched away from. A single
     *   Back chord walks zoom-back first, then file-back.
     * @property fileForward Mirror of [fileHistory] for forward
     *   navigation.
     * @property collapsedIds Within-file fold state.
     * @property expandedRefIdsLocal Per-pane intent: which promoted-ref
     *   ids THIS pane wants expanded. Drives chevron direction and
     *   visibility for refs without coupling to other panes' choices.
     *   Distinct from `Document.State.expandedRefIds`, which is the
     *   shared "currently spliced into lines" set; the document
     *   refcounts these per-pane intents to decide when to evict
     *   children from `lines`.
     * @property pendingLeafZoomChild When non-null, indicates this pane
     *   has zoomed into a childless leaf bullet whose first child has
     *   not been materialized yet. The `LineId` is the parent's id. The
     *   first edit intent in this state inserts the placeholder child
     *   into the document and clears this flag; zooming away or
     *   switching files clears it without inserting anything. Avoids
     *   the multi-pane bug where leaf-zoom in one pane would write a
     *   stray bullet visible to all panes (and sometimes promote into
     *   a brand new file via autosave).
     * @property seenLineIds Internal: the set of [LineId]s the
     *   default-collapse pass has already processed.
     * @property isVaultFooterExpanded Master toggle for the editor's
     *   filesystem-tree footer.
     * @property expandedVaultPaths Per-pane open folders in the
     *   filesystem-tree footer.
     * @property pendingInlineStyles Inline styles armed via Cmd-B / etc
     *   while the caret was collapsed.
     */
    data class State(
        val activeFileRel: String = "",
        val documentState: Document.State? = null,
        val vaultListings: Map<String, List<VaultEntry>> = emptyMap(),
        val cursorRow: Int = 0,
        val cursorCol: Int = 0,
        val anchorRow: Int? = null,
        val anchorCol: Int? = null,
        val zoomedLineId: LineId? = null,
        val zoomHistory: List<LineId?> = emptyList(),
        val zoomForward: List<LineId?> = emptyList(),
        val fileHistory: List<String> = emptyList(),
        val fileForward: List<String> = emptyList(),
        val collapsedIds: Set<LineId> = emptySet(),
        val expandedRefIdsLocal: Set<LineId> = emptySet(),
        val pendingLeafZoomChild: LineId? = null,
        internal val seenLineIds: Set<LineId> = emptySet(),
        val isVaultFooterExpanded: Boolean = true,
        val expandedVaultPaths: Set<String> = emptySet(),
        val pendingInlineStyles: Set<InlineStyle> = emptySet(),
    ) {
        /** `true` once the document has loaded from disk at least once. */
        val isLoaded: Boolean get() = documentState?.isLoaded == true

        /**
         * `true` while the document is mid-save on a tick that
         * promotes or demotes a subtree across the per-file boundary
         * — see [Document.State.isRestructuring].
         */
        val isRestructuring: Boolean get() = documentState?.isRestructuring == true

        /** Convenience accessor — never null, falls back to a single empty line. */
        val lines: List<String> get() = documentState?.lines ?: listOf("")

        /**
         * `true` when the editor is currently displaying the configured
         * root file (so the vault-tree footer should render).
         */
        fun isAtRootFile(rootFileName: String): Boolean =
            activeFileRel == rootFileName
    }

    /**
     * A normalized selection range (start ≤ end, by row-then-column),
     * or absent when the caret is not extending any selection. Produced
     * by `selectionOf` from the raw anchor/cursor pair.
     */
    data class Selection(val startRow: Int, val startCol: Int, val endRow: Int, val endCol: Int)

    /**
     * Resolved zoom geometry. Computed on demand by `zoomInfoOf` from
     * the current zoom id and document state — never stored, so it
     * cannot go stale.
     */
    data class ZoomInfo(
        val zoomRow: Int,
        val zoomIndent: Int,
        val startRow: Int,
        val endRowInclusive: Int,
        val titleText: String,
    ) {
        /** `true` when the zoom target has at least one descendant bullet. */
        val hasVisibleRows: Boolean get() = startRow <= endRowInclusive
    }

    private val _stateFlow = MutableStateFlow(State(activeFileRel = initialFileRel))

    /**
     * Observable stream of pane states. Platform `MainViewModel`s
     * collect this and re-emit through their own envelope.
     */
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    /**
     * The [Document] the pane is currently viewing. Swapped by
     * [switchActiveFile] on cross-file navigation. Slice classes read
     * the live document via [currentDocument]; passing them this lambda
     * keeps them oblivious to swaps.
     */
    private var document: Document? = null

    private fun currentDocument(): Document = document
        ?: error("PaneBackingViewModel: no document acquired yet")

    /**
     * Job mirroring [Document.stateFlow] into [State.documentState].
     * Cancelled and re-launched whenever [switchActiveFile] swaps the
     * active document.
     */
    private var documentCollectorJob: Job? = null

    private val textEditing = TextEditingViewModel(
        documentProvider = { currentDocument() },
        stateProvider = { _stateFlow.value },
        applyState = { _stateFlow.value = it },
        mutate = { transform -> mutate(transform) },
        patch = { transform -> patch(transform) },
        revealAncestors = { row -> revealAncestors(row) },
    )

    private val zoomNavigation = ZoomNavigation(
        documentProvider = { currentDocument() },
        stateProvider = { _stateFlow.value },
        patch = { transform -> patch(transform) },
        scope = scope,
    )

    private val markdownStyle = MarkdownStyleViewModel(
        documentProvider = { currentDocument() },
        stateProvider = { _stateFlow.value },
        patch = { transform -> patch(transform) },
        selectWord = { row, col -> textEditing.selectWord(row, col) },
    )

    init {
        // Mirror the registry's shared vault-listings cache into pane
        // state so view code can read both document content and folder
        // tree from a single state snapshot.
        scope.launch {
            registry.vaultListingsFlow.collect { listings ->
                _stateFlow.value = _stateFlow.value.copy(vaultListings = listings)
            }
        }
        // Acquire the initial document and start mirroring it.
        scope.launch {
            val doc = registry.acquire(initialFileRel)
            document = doc
            startDocumentCollector(doc)
        }
    }

    /**
     * Subscribes to [doc]'s state flow and mirrors each emission into
     * [State.documentState], applying the default-collapse pass and
     * reconciling. The returned job is stored in [documentCollectorJob]
     * so [switchActiveFile] can cancel it before swapping.
     */
    private fun startDocumentCollector(doc: Document) {
        documentCollectorJob = scope.launch {
            doc.stateFlow.collect { docState ->
                val merged = _stateFlow.value.copy(documentState = docState)
                val withDefaults = applyDefaultCollapseIfNeeded(merged)
                _stateFlow.value = reconcile(withDefaults)
            }
        }
    }

    /**
     * Acquires the [Document] for [fileRel], cancels the inner
     * collector for the outgoing document, swaps to the new document,
     * starts a new inner collector, and releases the outgoing one.
     * Idempotent on the same `fileRel`.
     *
     * Resets per-pane state that no longer applies in the new file:
     * cursor, anchor, zoom target + history, fold state. File history
     * bookkeeping is not touched here — callers ([navigateToVaultFile],
     * [fileBack], [fileForward]) push / pop those stacks themselves.
     */
    private suspend fun switchActiveFile(fileRel: String) {
        if (_stateFlow.value.activeFileRel == fileRel && document != null) return
        val outgoing = document
        val outgoingFile = _stateFlow.value.activeFileRel
        val outgoingExpansions = _stateFlow.value.expandedRefIdsLocal
        val incoming = registry.acquire(fileRel)
        documentCollectorJob?.cancelAndJoin()
        documentCollectorJob = null
        document = incoming
        // Hand back this pane's per-id expansion intents on the outgoing
        // document BEFORE releasing it, so the refcount can hit zero and
        // the shared document evicts spliced child files no pane wants
        // anymore.
        if (outgoing != null) {
            for (id in outgoingExpansions) outgoing.releaseExpansion(id)
        }
        // Reset per-pane state tied to the outgoing file.
        _stateFlow.value = _stateFlow.value.copy(
            activeFileRel = fileRel,
            documentState = null,
            cursorRow = 0,
            cursorCol = 0,
            anchorRow = null,
            anchorCol = null,
            zoomedLineId = null,
            zoomHistory = emptyList(),
            zoomForward = emptyList(),
            collapsedIds = emptySet(),
            expandedRefIdsLocal = emptySet(),
            pendingLeafZoomChild = null,
            seenLineIds = emptySet(),
            pendingInlineStyles = emptySet(),
        )
        // Captured snapshots in undo/redo refer to the outgoing
        // document; they would corrupt the new one if applied.
        undoStack.clear()
        redoStack.clear()
        startDocumentCollector(incoming)
        if (outgoing != null && outgoingFile.isNotEmpty() && outgoingFile != fileRel) {
            registry.release(outgoingFile)
        }
    }

    /**
     * Releases the active document. Called by the platform layer when
     * the pane itself is being torn down (e.g. the user closes the
     * pane). Safe to call once; subsequent calls are no-ops.
     */
    suspend fun release() {
        documentCollectorJob?.cancelAndJoin()
        documentCollectorJob = null
        val outgoing = document
        val outgoingFile = _stateFlow.value.activeFileRel
        val outgoingExpansions = _stateFlow.value.expandedRefIdsLocal
        document = null
        if (outgoing != null) {
            for (id in outgoingExpansions) outgoing.releaseExpansion(id)
        }
        if (outgoing != null && outgoingFile.isNotEmpty()) {
            registry.release(outgoingFile)
        }
    }

    /**
     * Adds newly observed parent-bullet and promoted-ref [LineId]s to
     * [State.collapsedIds] so each subtree starts folded the first time
     * we see it. The pass runs on `lineIds - seenLineIds` so it does
     * not re-collapse ids the user has explicitly expanded.
     */
    private fun applyDefaultCollapseIfNeeded(state: State): State {
        val docState = state.documentState ?: return state
        if (!docState.isLoaded) return state
        val seen = state.seenLineIds
        val currentIds = docState.lineIds
        if (seen.size == currentIds.size && seen.containsAll(currentIds)) return state

        val newCollapsed = HashSet<LineId>()
        val doc = document
        for ((idx, id) in currentIds.withIndex()) {
            if (id in seen) continue
            if (idx !in docState.lines.indices) continue
            val line = docState.lines[idx]
            val indent = DocumentLayout.bulletAsteriskColumn(line)
            if (indent < 0) continue
            val isParent = DocumentLayout.hasChildren(docState.lines, idx, indent)
            val isRef = doc?.isPromotedRef(id) == true
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
     * `true` if [lineId] is a file-boundary reference whose children
     * live in a separate `.md` file. The paint loop uses this to
     * decide whether to render a chevron on a bullet that has no
     * children currently in `lines`.
     */
    fun isPromotedRef(lineId: LineId): Boolean = document?.isPromotedRef(lineId) == true

    /**
     * Toggle the fold state of [lineId] for THIS pane. For file-boundary
     * references the call also drives `Document.acquireExpansion` /
     * `releaseExpansion` so the shared document refcounts pane-local
     * intents — children are spliced in on the first acquire across all
     * panes, and only evicted when every pane has released.
     */
    fun toggleCollapse(lineId: LineId) {
        val current = _stateFlow.value
        if (!current.isLoaded) return
        val doc = document ?: return
        val isRef = doc.isPromotedRef(lineId)
        if (isRef) {
            val isExpandedInPane = lineId in current.expandedRefIdsLocal
            if (isExpandedInPane) {
                patch {
                    it.copy(
                        expandedRefIdsLocal = it.expandedRefIdsLocal - lineId,
                        collapsedIds = it.collapsedIds + lineId,
                    )
                }
                scope.launch { doc.releaseExpansion(lineId) }
            } else {
                patch {
                    it.copy(
                        expandedRefIdsLocal = it.expandedRefIdsLocal + lineId,
                        collapsedIds = it.collapsedIds - lineId,
                    )
                }
                scope.launch { doc.acquireExpansion(lineId) }
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

    /**
     * If [State.pendingLeafZoomChild] is set, materializes the deferred
     * placeholder bullet for real now: appends `"\n" + childPrefix` to
     * the parent's row in the document, moves the caret to the start
     * of the new child's editable area, and clears the pending flag.
     * Returns `true` when materialization happened, `false` when there
     * was nothing pending.
     *
     * The materialized child uses the parent's indent + [TAB_SIZE]; if
     * the parent's row vanished or no longer parses as a bullet, the
     * pending flag is cleared without writing.
     */
    private fun materializePendingLeafZoomChild(): Boolean {
        val s = _stateFlow.value
        val pending = s.pendingLeafZoomChild ?: return false
        val docState = s.documentState
        val doc = document
        if (docState == null || doc == null) {
            patch { it.copy(pendingLeafZoomChild = null) }
            return false
        }
        val parentRow = docState.lineIds.indexOf(pending)
        if (parentRow < 0) {
            patch { it.copy(pendingLeafZoomChild = null) }
            return false
        }
        val parentLine = docState.lines[parentRow]
        val parentIndent = DocumentLayout.bulletAsteriskColumn(parentLine)
        if (parentIndent < 0) {
            patch { it.copy(pendingLeafZoomChild = null) }
            return false
        }
        val childIndent = parentIndent + TAB_SIZE
        val childPrefix = " ".repeat(childIndent) + "* "
        doc.insertText(parentRow, parentLine.length, "\n" + childPrefix)
        val newChildRow = parentRow + 1
        patch {
            it.copy(
                cursorRow = newChildRow,
                cursorCol = childPrefix.length,
                anchorRow = null,
                anchorCol = null,
                pendingLeafZoomChild = null,
            )
        }
        return true
    }

    /** See [TextEditingViewModel.insertChar]. */
    fun insertChar(char: Char) {
        recordEdit(FrameKind.TYPING) {
            materializePendingLeafZoomChild()
            textEditing.insertChar(char)
        }
    }

    /** See [TextEditingViewModel.insertNewline]. */
    fun insertNewline() {
        recordEdit(FrameKind.OTHER) {
            // Materialization itself already inserts a newline + bullet
            // prefix and parks the caret on the new child — exactly what
            // the user pressed Enter to get. Skip the standard newline
            // insert in that case to avoid producing two blank bullets.
            if (materializePendingLeafZoomChild()) {
                revealAncestors(_stateFlow.value.cursorRow)
                return@recordEdit
            }
            textEditing.insertNewline()
            revealAncestors(_stateFlow.value.cursorRow)
        }
    }

    /** See [TextEditingViewModel.insertText]. */
    fun insertText(text: String) {
        recordEdit(FrameKind.OTHER) {
            materializePendingLeafZoomChild()
            textEditing.insertText(text)
            if ('\n' in text || '\r' in text) revealAncestors(_stateFlow.value.cursorRow)
        }
    }

    /** See [TextEditingViewModel.backspace]. */
    fun backspace() {
        recordEdit(FrameKind.BACKSPACE) {
            // Backspace in the pending-leaf-zoom state has no obvious
            // intent — there's nothing to delete in the (yet-uncreated)
            // child. Clear the pending flag without materializing so the
            // backspace acts on the parent row's text instead.
            if (_stateFlow.value.pendingLeafZoomChild != null) {
                patch { it.copy(pendingLeafZoomChild = null) }
            }
            textEditing.backspace()
        }
    }

    /** See [TextEditingViewModel.indentLine]. */
    fun indentLine(amount: Int = TAB_SIZE) {
        recordEdit(FrameKind.OTHER) {
            materializePendingLeafZoomChild()
            textEditing.indentLine(amount)
            revealAncestors(_stateFlow.value.cursorRow)
        }
    }

    /** See [TextEditingViewModel.outdentLine]. */
    fun outdentLine(amount: Int = TAB_SIZE) {
        recordEdit(FrameKind.OTHER) {
            materializePendingLeafZoomChild()
            textEditing.outdentLine(amount)
        }
    }

    /** See [TextEditingViewModel.isBulletLine]. */
    fun isBulletLine(): Boolean = textEditing.isBulletLine()

    /**
     * Resolves the absolute row range owned by the bullet at [row]:
     * the bullet itself plus its descendants up to (but not including)
     * the next sibling-or-shallower row.
     */
    fun subtreeRange(row: Int): IntRange? {
        val doc = document ?: return null
        val docState = doc.stateFlow.value
        if (!docState.isLoaded) return null
        val lines = docState.lines
        if (row !in lines.indices) return null
        val indent = DocumentLayout.bulletAsteriskColumn(lines[row])
        if (indent < 0) return null
        return row..DocumentLayout.subtreeEnd(lines, row, indent)
    }

    /**
     * Move the contiguous row range `[fromStartRow..fromEndRow]` to land
     * before [insertBeforeRow] with [targetIndent] applied to the top
     * of the moved block. See the v1 indent / disallow-cases / id
     * semantics rules from the original docstring; preserved verbatim.
     */
    fun moveLineRange(
        fromStartRow: Int,
        fromEndRow: Int,
        insertBeforeRow: Int,
        targetIndent: Int,
    ) = recordEdit(FrameKind.OTHER) {
        val doc = document ?: return@recordEdit
        val docStart = doc.stateFlow.value
        if (!docStart.isLoaded) return@recordEdit
        val lines0 = docStart.lines
        if (lines0.isEmpty()) return@recordEdit

        val src0 = fromStartRow.coerceIn(0, lines0.lastIndex)
        val src1 = fromEndRow.coerceIn(src0, lines0.lastIndex)
        val drop = insertBeforeRow.coerceIn(0, lines0.size)

        if (drop in (src0 + 1)..src1) return@recordEdit
        if (drop == src0 || drop == src1 + 1) return@recordEdit
        if (src0 == 0 && src1 == lines0.lastIndex) return@recordEdit

        val deletedRowCount = src1 - src0 + 1
        val sourceTopIndent = leadingSpaceCount(lines0[src0])
        val shift = targetIndent - sourceTopIndent
        val movedText = (src0..src1).joinToString("\n") { reindentLine(lines0[it], shift) }

        if (src0 > 0) {
            val above = lines0[src0 - 1]
            val tailRow = src1
            doc.delete(src0 - 1, above.length, tailRow, lines0[tailRow].length)
        } else {
            doc.delete(0, 0, src1 + 1, 0)
        }

        val postDeleteInsertRow = if (drop <= src0) drop else drop - deletedRowCount

        val newLines = doc.stateFlow.value.lines
        val landedFirst: Int
        val landedLast: Int
        if (postDeleteInsertRow > newLines.lastIndex) {
            val lastIdx = newLines.lastIndex
            doc.insertText(lastIdx, newLines[lastIdx].length, "\n" + movedText)
            landedFirst = lastIdx + 1
            landedLast = landedFirst + deletedRowCount - 1
        } else {
            doc.insertText(postDeleteInsertRow, 0, movedText + "\n")
            landedFirst = postDeleteInsertRow
            landedLast = landedFirst + deletedRowCount - 1
        }

        val finalLines = doc.stateFlow.value.lines
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
        // Suppress an unused-value warning on landedLast: it's purely
        // for documentation that the logic computed the correct range.
        @Suppress("UNUSED_EXPRESSION") landedLast
    }

    private fun leadingSpaceCount(line: String): Int {
        val nonWs = line.indexOfFirst { !it.isWhitespace() }
        return if (nonWs < 0) line.length else nonWs
    }

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
     * to the previous document.
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
        val currentFile = _stateFlow.value.activeFileRel
        val priorHistory = _stateFlow.value.fileHistory.dropLast(1)
        val priorForward = _stateFlow.value.fileForward
        switchActiveFile(previous)
        patch {
            it.copy(
                fileHistory = priorHistory,
                fileForward = (priorForward + currentFile).takeLast(NAV_HISTORY_CAP),
            )
        }
    }

    private suspend fun fileForward() {
        val next = _stateFlow.value.fileForward.lastOrNull() ?: return
        val currentFile = _stateFlow.value.activeFileRel
        val priorHistory = _stateFlow.value.fileHistory
        val priorForward = _stateFlow.value.fileForward.dropLast(1)
        switchActiveFile(next)
        patch {
            it.copy(
                fileHistory = (priorHistory + currentFile).takeLast(NAV_HISTORY_CAP),
                fileForward = priorForward,
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
     * Pushes a logical selection into the pane. Used by platforms whose
     * native text surface owns the live caret (web `contenteditable`,
     * Compose `BasicTextField`, UIKit `UITextView`).
     */
    fun setSelection(anchorRow: Int, anchorCol: Int, cursorRow: Int, cursorCol: Int) {
        val current = _stateFlow.value
        if (!current.isLoaded) return
        val collapsed = anchorRow == cursorRow && anchorCol == cursorCol
        val moved = current.cursorRow != cursorRow || current.cursorCol != cursorCol ||
            current.anchorRow != (if (collapsed) null else anchorRow) ||
            current.anchorCol != (if (collapsed) null else anchorCol)
        val pending = if (moved) emptySet() else current.pendingInlineStyles
        // Any explicit caret reposition (mouse click, IME) means the
        // user has navigated past the "I just zoomed into a leaf" hint;
        // drop the pending placeholder so a later edit on this caret
        // doesn't accidentally insert a child under the old zoom target.
        val pendingLeaf = if (moved) null else current.pendingLeafZoomChild
        _stateFlow.value = reconcile(
            current.copy(
                cursorRow = cursorRow,
                cursorCol = cursorCol,
                anchorRow = if (collapsed) null else anchorRow,
                anchorCol = if (collapsed) null else anchorCol,
                pendingInlineStyles = pending,
                pendingLeafZoomChild = pendingLeaf,
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
        recordEdit(FrameKind.OTHER) {
            materializePendingLeafZoomChild()
            markdownStyle.applyInlineStyle(style)
        }
    }

    /** See [MarkdownStyleViewModel.applyLineStyle]. */
    fun applyLineStyle(style: LineStyle) {
        recordEdit(FrameKind.OTHER) {
            materializePendingLeafZoomChild()
            markdownStyle.applyLineStyle(style)
        }
    }

    /** See [MarkdownStyleViewModel.activeInlineStyles]. */
    fun activeInlineStyles(): Set<InlineStyle> = markdownStyle.activeInlineStyles()

    /** See [MarkdownStyleViewModel.activeLineStyle]. */
    fun activeLineStyle(): LineStyle? = markdownStyle.activeLineStyle()

    // ----------------------------------------------------------- vault footer

    /** Flips [State.isVaultFooterExpanded]. */
    fun toggleVaultFooter() {
        patch { it.copy(isVaultFooterExpanded = !it.isVaultFooterExpanded) }
    }

    /**
     * Toggles whether the folder at [dirRel] is open in the
     * filesystem-tree footer. Adding a path also kicks off
     * `DocumentRegistry.ensureVaultListing(dirRel)` so the folder's
     * direct children are fetched on first expand.
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
            scope.launch { registry.ensureVaultListing(dirRel) }
        }
    }

    /**
     * Switches this pane to the markdown file at [pathRel]. Releases
     * the current file (last pane on it triggers final flush + Document
     * shutdown), acquires the new file (first pane on it triggers a
     * fresh load), and resets per-pane state.
     *
     * Other panes are unaffected — this is genuinely pane-local now.
     * Two panes pointed at the same file share one [Document] so their
     * edits show up in each other live.
     */
    fun navigateToVaultFile(pathRel: String) {
        if (!_stateFlow.value.isLoaded) return
        val currentFile = _stateFlow.value.activeFileRel
        if (currentFile == pathRel) return
        scope.launch {
            switchActiveFile(pathRel)
            patch {
                it.copy(
                    fileHistory = (it.fileHistory + currentFile).takeLast(NAV_HISTORY_CAP),
                    fileForward = emptyList(),
                )
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private inline fun mutate(transform: (State) -> State) {
        val current = _stateFlow.value
        if (!current.isLoaded) return
        _stateFlow.value = reconcile(transform(current))
    }

    private inline fun patch(transform: (State) -> State) {
        val current = _stateFlow.value
        val patched = transform(current).copy(
            documentState = document?.stateFlow?.value ?: current.documentState
        )
        _stateFlow.value = reconcile(patched)
    }

    private fun reconcile(state: State): State {
        val docState = state.documentState
        if (docState?.isLoaded != true) return state
        val lines = state.lines
        val lastRow = lines.lastIndex
        val baseRow = state.cursorRow.coerceIn(0, lastRow)
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
        return clampToVisible(clamped)
    }

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

    private data class Snapshot(
        val lines: List<String>,
        val lineIds: List<LineId>,
        val expandedRefIds: Set<LineId>,
        val cursorRow: Int,
        val cursorCol: Int,
        val anchorRow: Int?,
        val anchorCol: Int?,
    )

    private enum class FrameKind { TYPING, BACKSPACE, OTHER }

    private data class UndoFrame(
        val before: Snapshot,
        val after: Snapshot,
        val kind: FrameKind,
        val timestampMs: Long,
    )

    private val undoStack: ArrayDeque<UndoFrame> = ArrayDeque()
    private val redoStack: ArrayDeque<UndoFrame> = ArrayDeque()

    private val timeOrigin = TimeSource.Monotonic.markNow()

    private var recordingDepth: Int = 0

    private fun snapshotNow(): Snapshot {
        val view = _stateFlow.value
        val doc = view.documentState ?: document?.stateFlow?.value ?: Document.State()
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

    private fun shouldCoalesce(prev: UndoFrame, next: UndoFrame): Boolean {
        if (prev.kind != next.kind) return false
        if (prev.kind != FrameKind.TYPING && prev.kind != FrameKind.BACKSPACE) return false
        if (prev.before.anchorRow != null || next.before.anchorRow != null) return false
        if (prev.after.anchorRow != null || next.after.anchorRow != null) return false
        if (next.timestampMs - prev.timestampMs > COALESCE_WINDOW_MS) return false
        if (prev.after.cursorRow != next.before.cursorRow) return false
        if (prev.after.cursorCol != next.before.cursorCol) return false
        if (prev.kind == FrameKind.TYPING) {
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

    fun undo() {
        if (!_stateFlow.value.isLoaded) return
        val frame = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(frame)
        while (redoStack.size > MAX_UNDO_FRAMES) redoStack.removeFirst()
        restoreSnapshot(frame.before)
    }

    fun redo() {
        if (!_stateFlow.value.isLoaded) return
        val frame = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(frame)
        while (undoStack.size > MAX_UNDO_FRAMES) undoStack.removeFirst()
        restoreSnapshot(frame.after)
    }

    fun canUndo(): Boolean = undoStack.isNotEmpty()

    fun canRedo(): Boolean = redoStack.isNotEmpty()

    private fun restoreSnapshot(snap: Snapshot) {
        val doc = document ?: return
        doc.replaceContent(snap.lines, snap.lineIds, snap.expandedRefIds)
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

        /** Maximum gap between two coalescing-eligible frames, in ms. */
        private const val COALESCE_WINDOW_MS: Long = 1_000L

        /** Cap on entries in [State.fileHistory] / [State.fileForward]. */
        const val NAV_HISTORY_CAP: Int = 50

        /** Normalizes an anchor + cursor pair into a [Selection]. */
        fun selectionOf(state: State): Selection? = se.soderbjorn.notegrow.main.selectionOf(state)
    }
}
