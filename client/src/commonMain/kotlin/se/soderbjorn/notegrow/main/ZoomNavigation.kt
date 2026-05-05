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
 * - [zoomBack] pops `zoomHistory` into the current target and pushes the
 *   previous target onto `zoomForward`.
 * - [zoomForward] is the mirror — pops `zoomForward`, pushes onto
 *   `zoomHistory`. Calling either when its stack is empty is a no-op.
 *
 * commonMain only — this slice does not touch the DOM or any platform UI.
 */

package se.soderbjorn.notegrow.main

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import se.soderbjorn.notegrow.main.PaneBackingViewModel.Companion.TAB_SIZE

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
 *   document. Implements the "go back without writing anything → don't
 *   leave the placeholder behind" rule.
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
        val line = docState.lines[resolvedRow]
        val indent = DocumentLayout.bulletAsteriskColumn(line)
        if (indent < 0) return
        val id = targetId
        // If the zoom target is a folded ref FOR THIS PANE, lazy-load its
        // file first then re-enter zoomInto with the now-loaded subtree.
        // We consult per-pane intent (not the shared `expandedRefIds`)
        // because another pane may already have the ref open — but this
        // pane still needs to record its own intent and bump the
        // refcount so a later collapse here actually evicts.
        if (document.isPromotedRef(id) && id !in s.expandedRefIdsLocal) {
            patch {
                it.copy(
                    expandedRefIdsLocal = it.expandedRefIdsLocal + id,
                    collapsedIds = it.collapsedIds - id,
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
        if (endInclusive < resolvedRow + 1) {
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
            document.insertText(resolvedRow, line.length, "\n" + childPrefix)
            val newDocState = document.stateFlow.value
            val childRow = resolvedRow + 1
            val childId = if (childRow in newDocState.lineIds.indices) {
                newDocState.lineIds[childRow]
            } else null
            patch {
                pushHistory(it, previousZoom).copy(
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
            val firstChildRow = resolvedRow + 1
            val firstChildLine = docState.lines[firstChildRow]
            val bulletCol = DocumentLayout.bulletAsteriskColumn(firstChildLine)
            val targetCol = if (bulletCol >= 0) bulletCol + 2 else 0
            patch {
                pushHistory(it, previousZoom).copy(
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
     * subtree under it is non-empty by construction.
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
        cleanupEmptyPlaceholder()
        patch {
            pushHistory(it, previousZoom).copy(
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
            it.copy(
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
     * If [targetId] points at a childless bullet (a former leaf zoom),
     * splices in an empty placeholder child the same way [zoomInto] does
     * and returns the placeholder's stable id so the caller can record it
     * in `pendingLeafZoomChild`. Returns `null` for non-leaf targets,
     * targets that no longer exist, and ref bullets (handled by the
     * lazy-load branch in [zoomInto]).
     */
    private fun insertLeafZoomPlaceholderIfNeeded(targetId: LineId): LineId? {
        val docState = document.stateFlow.value
        val row = docState.lineIds.indexOf(targetId)
        if (row < 0) return null
        val line = docState.lines[row]
        val indent = DocumentLayout.bulletAsteriskColumn(line)
        if (indent < 0) return null
        if (document.isPromotedRef(targetId)) return null
        val endInclusive = DocumentLayout.subtreeEnd(docState.lines, row, indent)
        if (endInclusive >= row + 1) return null
        val childIndent = indent + TAB_SIZE
        val childPrefix = " ".repeat(childIndent) + "* "
        document.insertText(row, line.length, "\n" + childPrefix)
        val newDocState = document.stateFlow.value
        val childRow = row + 1
        return if (childRow in newDocState.lineIds.indices) newDocState.lineIds[childRow] else null
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
