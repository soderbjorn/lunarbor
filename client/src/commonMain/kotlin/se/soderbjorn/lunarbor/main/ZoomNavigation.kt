/*
 * ZoomNavigation.kt
 * -----------------
 * Owns the "zoom into a bullet" intents — making one bullet the logical
 * root of the editor view, returning to root, navigating back/forward
 * through past zoom targets, and resolving the current zoom id into
 * concrete row geometry. The reconciliation step that clamps a zoomed
 * cursor inside the visible subtree lives in
 * `PaneBackingViewModel.reconcile`.
 *
 * Browser-style back/forward semantics:
 * - Every zoom-changing intent ([zoomInto], [zoomTo], [zoomOut]) pushes
 *   the current `zoomedLineId` onto `zoomHistory` and clears
 *   `zoomForward` — moving to a new target severs the redo path, just
 *   like a web browser.
 * - Zooming never leaves a fold changed behind it: a folded item the zoom
 *   opens is recorded in `zoomUnfoldedIds` and folds again once the zoom
 *   target leaves its subtree ([ZoomNavigation.refoldLeftBehind], run by
 *   `PaneBackingViewModel.patch` on every zoom change).
 * - [zoomBack] pops `zoomHistory` into the current target and pushes the
 *   previous target onto `zoomForward`.
 * - [zoomForward] is the mirror — pops `zoomForward`, pushes onto
 *   `zoomHistory`. Calling either when its stack is empty is a no-op.
 *
 * commonMain only — this slice does not touch the DOM or any platform UI.
 */

package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import se.soderbjorn.lunarbor.data.SearchNode
import se.soderbjorn.lunarbor.main.PaneBackingViewModel.Companion.TAB_SIZE

/**
 * Zoom slice of the per-pane ViewModel. Composed by
 * `PaneBackingViewModel`; mutates the aggregate state through
 * the supplied [patch] hook.
 *
 * @param documentProvider Returns the [Document] the pane currently has
 *   acquired. Called when zooming into a leaf bullet (which inserts a
 *   placeholder child) so a pane swap is transparent.
 * @param stateProvider Reads the latest aggregate state.
 * @param patch Applies a transform that touches document content; refreshes
 *   the mirrored `documentState` and reconciles.
 * @param cleanupEmptyPlaceholder Called at the top of every zoom-changing
 *   intent. If [PaneBackingViewModel.State.pendingLeafZoomChild] points
 *   at a still-empty placeholder bullet, the row is removed from the
 *   document, and the pane lets go of the pending rows it holds
 *   ([PaneBackingViewModel.State.pendingRowsGroup]). Implements the "go
 *   back without writing anything → don't leave the placeholder behind"
 *   rule.
 */
internal class ZoomNavigation(
    private val documentProvider: () -> Document,
    private val stateProvider: () -> PaneBackingViewModel.State,
    private val patch: ((PaneBackingViewModel.State) -> PaneBackingViewModel.State) -> Unit,
    private val scope: CoroutineScope,
    private val cleanupEmptyPlaceholder: () -> Unit,
) {
    private val document: Document get() = documentProvider()

    /** Cap on how many zoom transitions we remember per direction. */
    private val historyCap: Int = 50

    fun zoomInto(row: Int) {
        val s = stateProvider()
        if (!s.isLoaded) return
        // Read the freshest doc state directly. The pane mirror in
        // [State.documentState] may lag by one collector tick when this
        // runs in the re-entry continuation after `acquireExpansion`.
        val docState0 = document.stateFlow.value
        if (row !in docState0.lines.indices) return
        // Capture the target's stable id before calling cleanup — the
        // cleanup may delete a placeholder row above the target, shifting
        // the row index. Re-resolve via the id afterwards.
        val targetId = docState0.lineIds[row]
        // Capture the pre-cleanup zoom target so we push the original
        // value (not the post-cleanup `null`) onto history.
        val previousZoom = s.zoomedLineId

        cleanupEmptyPlaceholder()

        val docState = document.stateFlow.value
        val resolvedRow = docState.lineIds.indexOf(targetId)
        if (resolvedRow < 0) return
        if (resolvedRow !in docState.lines.indices) return
        // A bullet or a block item: both can be zoomed into.
        val indent = DocumentLayout.itemColumn(docState.lines, resolvedRow)
        if (indent < 0) return
        val ownLast = DocumentLayout.itemLastRow(docState.lines, resolvedRow)
        val id = targetId
        // If the zoom target is a folded ref FOR THIS PANE, lazy-load its
        // file first then re-enter zoomInto with the now-loaded subtree.
        // We consult per-pane intent (not the shared `unloadedRefIds`)
        // because another pane may already have the ref open — but this
        // pane still needs to record its own intent and bump the
        // refcount so a later collapse here actually evicts.
        if (document.isPromotedRef(id) && id !in s.expandedRefIdsLocal) {
            patch {
                it.copy(
                    expandedRefIdsLocal = it.expandedRefIdsLocal + id,
                    collapsedIds = it.collapsedIds - id,
                    zoomUnfoldedIds = it.zoomUnfoldedIds + id,
                )
            }
            scope.launch {
                document.acquireExpansion(id)
                // Re-resolve the row by id; expansion may have shifted nothing
                // above this row, but a sibling cleanup or async edit could.
                val after = document.stateFlow.value
                val newRow = after.lineIds.indexOf(id)
                if (newRow >= 0) zoomInto(newRow)
            }
            return
        }
        val endInclusive = DocumentLayout.subtreeEnd(docState.lines, resolvedRow, indent)
        if (endInclusive < ownLast + 1 && BlockLayout.isBlockLine(docState.lines[resolvedRow])) {
            // A childless block: its own rows head the page and are
            // editable, so no placeholder child. Arrow Down (or Cmd-Enter /
            // Escape) out of its last row makes a child bullet, a throwaway
            // until typed in ([TextEditingViewModel.exitBlock]).
            patch {
                pushHistory(markUnfolded(it, id), previousZoom).copy(
                    zoomedLineId = id,
                    cursorRow = resolvedRow,
                    cursorCol = DocumentLayout.caretStartCol(docState.lines[resolvedRow]),
                    anchorRow = null,
                    anchorCol = null,
                    collapsedIds = it.collapsedIds - id,
                    pendingLeafZoomChild = null,
                )
            }
        } else if (endInclusive < ownLast + 1 &&
            (isReadOnlyLeaf(docState.lines[resolvedRow]) ||
                (document.isPromotedRef(id) && id in docState.unloadedRefIds))
        ) {
            // A childless search node or link bullet: its results or the
            // linked node's preview are the page, which is read-only, so no
            // placeholder child to type in. Nor for a folder-backed row whose
            // items could not be loaded here: it is not a leaf, and a
            // placeholder would be saved as its only child.
            patch {
                pushHistory(markUnfolded(it, id), previousZoom).copy(
                    zoomedLineId = id,
                    cursorRow = resolvedRow,
                    cursorCol = docState.lines[resolvedRow].length,
                    anchorRow = null,
                    anchorCol = null,
                    collapsedIds = it.collapsedIds - id,
                    pendingLeafZoomChild = null,
                )
            }
        } else if (endInclusive < ownLast + 1) {
            // Childless bullet (with or without its own text). Insert an
            // empty placeholder child so the user has somewhere to type
            // inside the zoom view, and remember the placeholder's id in
            // [State.pendingLeafZoomChild]. If the user navigates away
            // without modifying it, the cleanup hook removes the row
            // again. If they type, [PaneBackingViewModel] clears the
            // pending flag (committing the placeholder for real).
            // [NoteRepository.save] also strips trailing empty bullets
            // globally so any placeholder that survives at the end of a
            // file never makes it to disk.
            val childIndent = indent + TAB_SIZE
            val childPrefix = " ".repeat(childIndent) + "* "
            val childRow = ownLast + 1
            document.insertLine(childRow, childPrefix)
            val newDocState = document.stateFlow.value
            val childId = if (childRow in newDocState.lineIds.indices) {
                newDocState.lineIds[childRow]
            } else null
            patch {
                pushHistory(markUnfolded(it, id), previousZoom).copy(
                    zoomedLineId = id,
                    cursorRow = childRow,
                    cursorCol = childPrefix.length,
                    anchorRow = null,
                    anchorCol = null,
                    collapsedIds = it.collapsedIds - id,
                    pendingLeafZoomChild = childId,
                )
            }
        } else {
            val firstChildRow = ownLast + 1
            val firstChildLine = docState.lines[firstChildRow]
            val bulletCol = DocumentLayout.bulletAsteriskColumn(firstChildLine)
            val targetCol = if (bulletCol >= 0) bulletCol + 2 else 0
            patch {
                pushHistory(markUnfolded(it, id), previousZoom).copy(
                    zoomedLineId = id,
                    cursorRow = firstChildRow,
                    cursorCol = targetCol,
                    anchorRow = null,
                    anchorCol = null,
                    collapsedIds = it.collapsedIds - id,
                    pendingLeafZoomChild = null,
                )
            }
        }
    }

    fun zoomOut() {
        val s = stateProvider()
        if (!s.isLoaded) return
        val previousZoom = s.zoomedLineId
        cleanupEmptyPlaceholder()
        zoomOutAfterCleanup(previousZoom)
    }

    /** The root view, with [previousZoom] pushed onto the zoom history. */
    private fun zoomOutAfterCleanup(previousZoom: LineId?) {
        patch {
            pushHistory(it, previousZoom).copy(
                zoomedLineId = null,
                anchorRow = null,
                anchorCol = null,
                pendingLeafZoomChild = null,
            )
        }
    }

    /**
     * Zooms directly to a specific bullet by [LineId], or clears the zoom
     * when [lineId] is `null`. Used by the breadcrumb trail in the editor
     * header — clicking an ancestor segment navigates exactly one level up
     * (or any specific ancestor) without funneling through `zoomOut` first.
     *
     * Unlike [zoomInto], this does not insert a placeholder child or move
     * the caret — the caller already has a valid zoom target chosen from
     * the existing tree (an ancestor of the current zoom), so the visible
     * subtree under it is non-empty by construction. When the cleanup hook
     * removes the target (an untouched item the Today command prepared,
     * dropped with the pane's pending rows), the nearest surviving item
     * above it is the target instead, or the root view.
     *
     * @param lineId target bullet id, or null to clear the zoom.
     */
    fun zoomTo(lineId: LineId?) {
        val s = stateProvider()
        if (!s.isLoaded) return
        if (lineId == null) {
            zoomOut()
            return
        }
        val previousZoom = s.zoomedLineId
        // The cleanup may remove the target itself — a breadcrumb up to a
        // week the Today command prepared and nobody typed in: land on the
        // nearest item above it that survives instead.
        val candidates = listOf(lineId) + ancestorIdsOf(lineId)
        cleanupEmptyPlaceholder()
        val ids = document.stateFlow.value.lineIds
        val target = candidates.firstOrNull { it in ids }
        if (target == null) {
            zoomOutAfterCleanup(previousZoom)
            return
        }
        patch {
            pushHistory(markUnfolded(it, target), previousZoom).copy(
                zoomedLineId = target,
                anchorRow = null,
                anchorCol = null,
                collapsedIds = it.collapsedIds - target,
                pendingLeafZoomChild = null,
            )
        }
    }

    /**
     * Zooms to [lineId] with zoom history, like [zoomTo], but without the
     * cleanup hook: the caller has just prepared the rows it lands on
     * (pending rows, [Document.insertPendingRows]) and released the
     * pane's earlier ones itself. Called by
     * `PaneBackingViewModel.navigateToToday`.
     *
     * @param previousZoom The zoom the pane had before the caller's own
     *   cleanup, pushed onto the history so Back returns there.
     */
    fun zoomToPrepared(lineId: LineId, previousZoom: LineId?) {
        if (!stateProvider().isLoaded) return
        patch {
            pushHistory(markUnfolded(it, lineId), previousZoom).copy(
                zoomedLineId = lineId,
                anchorRow = null,
                anchorCol = null,
                collapsedIds = it.collapsedIds - lineId,
                pendingLeafZoomChild = null,
            )
        }
    }

    /**
     * Pops the most recent entry off [PaneBackingViewModel.State.zoomHistory]
     * and applies it as the new zoom target, pushing the *current* target
     * onto [PaneBackingViewModel.State.zoomForward] so [zoomForward]
     * can replay the move. No-op when the history stack is empty.
     *
     * Skips entries whose `LineId` is no longer present in the document
     * (the target row was deleted by an edit since the entry was pushed)
     * — those are silently dropped and the next entry is tried.
     */
    fun zoomBack() {
        val s = stateProvider()
        if (!s.isLoaded) return
        if (s.zoomHistory.isEmpty()) return
        val previous = popValid(s.zoomHistory, s) ?: return
        val (target, remaining) = previous
        val current = s.zoomedLineId
        cleanupEmptyPlaceholder()
        applyZoomTransition(
            target = target,
            newHistory = remaining,
            newForward = (s.zoomForward + current).takeLast(historyCap),
        )
    }

    /**
     * Mirror of [zoomBack]: pops [PaneBackingViewModel.State.zoomForward]
     * and pushes the current target onto
     * [PaneBackingViewModel.State.zoomHistory]. No-op when the
     * forward stack is empty.
     */
    fun zoomForward() {
        val s = stateProvider()
        if (!s.isLoaded) return
        if (s.zoomForward.isEmpty()) return
        val next = popValid(s.zoomForward, s) ?: return
        val (target, remaining) = next
        val current = s.zoomedLineId
        cleanupEmptyPlaceholder()
        applyZoomTransition(
            target = target,
            newHistory = (s.zoomHistory + current).takeLast(historyCap),
            newForward = remaining,
        )
    }

    /**
     * Shared core for [zoomBack] / [zoomForward]: lands the pane on
     * [target], re-creating the leaf placeholder when [target] points at
     * a now-childless bullet so `reconcile` doesn't clear the zoom on
     * `hasVisibleRows = false`. Without this, a forward into a former
     * leaf zoom (whose placeholder was removed by the corresponding back)
     * silently snaps back to the root view.
     */
    private fun applyZoomTransition(
        target: LineId?,
        newHistory: List<LineId?>,
        newForward: List<LineId?>,
    ) {
        val placeholderChildId = target?.let(::insertLeafZoomPlaceholderIfNeeded)
        patch {
            (if (target != null) markUnfolded(it, target) else it).copy(
                zoomedLineId = target,
                zoomHistory = newHistory,
                zoomForward = newForward,
                anchorRow = null,
                anchorCol = null,
                collapsedIds = if (target != null) it.collapsedIds - target else it.collapsedIds,
                pendingLeafZoomChild = placeholderChildId,
            )
        }
    }

    /**
     * Records [id] in [PaneBackingViewModel.State.zoomUnfoldedIds] when it
     * is folded in [state] — about to be unfolded by a zoom into it — so
     * [refoldLeftBehind] folds it again once the pane zooms away.
     */
    private fun markUnfolded(state: PaneBackingViewModel.State, id: LineId): PaneBackingViewModel.State =
        if (id in state.collapsedIds) state.copy(zoomUnfoldedIds = state.zoomUnfoldedIds + id) else state

    /**
     * If [targetId] points at a childless bullet (a former leaf zoom),
     * splices in an empty placeholder child the same way [zoomInto] does
     * and returns the placeholder's stable id so the caller can record it
     * in `pendingLeafZoomChild`. Returns `null` for non-leaf targets,
     * targets that no longer exist, blocks (their own rows show), and ref
     * bullets (handled by the lazy-load branch in [zoomInto]).
     */
    private fun insertLeafZoomPlaceholderIfNeeded(targetId: LineId): LineId? {
        val docState = document.stateFlow.value
        val row = docState.lineIds.indexOf(targetId)
        if (row < 0) return null
        val indent = DocumentLayout.itemColumn(docState.lines, row)
        if (indent < 0) return null
        if (document.isPromotedRef(targetId)) return null
        // A block zoom shows the block's own rows: never empty.
        if (BlockLayout.isBlockLine(docState.lines[row])) return null
        // A search node's or link bullet's page is read-only.
        if (isReadOnlyLeaf(docState.lines[row])) return null
        val ownLast = DocumentLayout.itemLastRow(docState.lines, row)
        val endInclusive = DocumentLayout.subtreeEnd(docState.lines, row, indent)
        if (endInclusive >= ownLast + 1) return null
        val childIndent = indent + TAB_SIZE
        val childPrefix = " ".repeat(childIndent) + "* "
        val childRow = ownLast + 1
        document.insertLine(childRow, childPrefix)
        val newDocState = document.stateFlow.value
        return if (childRow in newDocState.lineIds.indices) newDocState.lineIds[childRow] else null
    }

    /**
     * Ids of the items above [lineId], nearest first; empty when it is
     * gone or top-level.
     */
    private fun ancestorIdsOf(lineId: LineId): List<LineId> {
        val docState = document.stateFlow.value
        val lines = docState.lines
        val row = docState.lineIds.indexOf(lineId)
        if (row < 0) return emptyList()
        var lookingFor = DocumentLayout.itemColumn(lines, row)
        val out = ArrayList<LineId>()
        var r = row - 1
        while (r >= 0 && lookingFor > 0) {
            val col = DocumentLayout.itemColumn(lines, r)
            if (col in 0 until lookingFor) {
                out += docState.lineIds[r]
                lookingFor = col
            }
            r--
        }
        return out
    }

    fun zoomInfo(state: PaneBackingViewModel.State): PaneBackingViewModel.ZoomInfo? =
        zoomInfoOf(state)

    /**
     * Returns the ancestor breadcrumb chain for the currently zoomed line
     * (outer-to-inner, excluding the zoomed line itself). See
     * [bulletAncestorsOf] for ordering details.
     */
    fun bulletAncestors(state: PaneBackingViewModel.State): List<BreadcrumbAncestor> =
        bulletAncestorsOf(state)

    /**
     * Outer-to-inner breadcrumb segments for the current zoom (ancestors +
     * current zoom target text). Empty when the document is not zoomed —
     * callers fall back to the pane's static title in that case. Delegates
     * to [zoomPathSegmentsOf].
     */
    fun zoomPathSegments(state: PaneBackingViewModel.State): List<String> =
        zoomPathSegmentsOf(state)

    // ---------------------------------------------------------------- private

    /**
     * Returns [state] with [previousZoom] appended to
     * [PaneBackingViewModel.State.zoomHistory] and the forward
     * stack cleared. Used by every "go somewhere new" intent so back can
     * find it later. Capped at [historyCap] to avoid unbounded growth on
     * navigation-heavy sessions.
     *
     * [previousZoom] is passed explicitly rather than read from `state`
     * because [cleanupEmptyPlaceholder] runs first and may have caused
     * [reconcile][PaneBackingViewModel] to clear `state.zoomedLineId`
     * already (a leaf zoom whose placeholder we just deleted has
     * `hasVisibleRows = false`, which clears the zoom). Callers capture
     * the pre-cleanup id and pass it through here.
     */
    private fun pushHistory(
        state: PaneBackingViewModel.State,
        previousZoom: LineId?,
    ): PaneBackingViewModel.State =
        state.copy(
            zoomHistory = (state.zoomHistory + previousZoom).takeLast(historyCap),
            zoomForward = emptyList(),
        )

    /**
     * Pops the most recent entry off [stack] that points at a still-valid
     * zoom target. Returns the popped value plus the trimmed stack, or
     * `null` if no valid entry exists. A `null` entry (root) is always
     * valid; a non-null id is valid only when its row is still in the
     * document.
     */
    internal companion object {
        /**
         * Folds again every item in [PaneBackingViewModel.State.zoomUnfoldedIds]
         * whose subtree no longer holds the zoom target (the item itself
         * counts), and drops ids no longer in the document. The pane keeps
         * any folder expansion it holds, so zooming back in is instant.
         *
         * Called by `PaneBackingViewModel.patch` whenever a patch changes
         * `zoomedLineId`.
         *
         * @param state The patched state, with a fresh `documentState`.
         * @return [state] with those items added to `collapsedIds`.
         */
        fun refoldLeftBehind(state: PaneBackingViewModel.State): PaneBackingViewModel.State {
            if (state.zoomUnfoldedIds.isEmpty()) return state
            val docState = state.documentState ?: return state
            val lines = docState.lines
            val targetRow = state.zoomedLineId?.let { docState.lineIds.indexOf(it) } ?: -1
            val keep = mutableSetOf<LineId>()
            val fold = mutableSetOf<LineId>()
            for (id in state.zoomUnfoldedIds) {
                val row = docState.lineIds.indexOf(id)
                if (row < 0) continue
                val col = DocumentLayout.itemColumn(lines, row)
                val end = if (col >= 0) DocumentLayout.subtreeEnd(lines, row, col) else row
                if (targetRow in row..end) keep += id else fold += id
            }
            return state.copy(collapsedIds = state.collapsedIds + fold, zoomUnfoldedIds = keep)
        }
    }

    /**
     * `true` when a childless bullet [line] zooms into a read-only page
     * ([PaneBackingViewModel.ZoomInfo.isReadOnly]): a search node.
     */
    private fun isReadOnlyLeaf(line: String): Boolean = SearchNode.queryOf(line) != null

    private fun popValid(
        stack: List<LineId?>,
        state: PaneBackingViewModel.State,
    ): Pair<LineId?, List<LineId?>>? {
        val docState = state.documentState ?: return null
        var idx = stack.lastIndex
        while (idx >= 0) {
            val candidate = stack[idx]
            val ok = candidate == null || docState.lineIds.contains(candidate)
            if (ok) return Pair(candidate, stack.subList(0, idx))
            idx--
        }
        return null
    }
}
