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
 */
internal class ZoomNavigation(
    private val documentProvider: () -> Document,
    private val stateProvider: () -> PaneBackingViewModel.State,
    private val patch: ((PaneBackingViewModel.State) -> PaneBackingViewModel.State) -> Unit,
    private val scope: CoroutineScope,
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
        val docState = document.stateFlow.value
        if (row !in docState.lines.indices) return
        val line = docState.lines[row]
        val indent = DocumentLayout.bulletAsteriskColumn(line)
        if (indent < 0) return
        val id = docState.lineIds[row]
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
                zoomInto(row)
            }
            return
        }
        val endInclusive = DocumentLayout.subtreeEnd(docState.lines, row, indent)
        // Refuse to zoom into a bullet that has neither text of its own nor any
        // children. Without this guard, the leaf-placeholder branch below would
        // auto-create an empty child and zoom in, and clicking that child's
        // handle would do the same thing again — producing an infinite chain
        // of "(untitled)" breadcrumbs from a single empty bullet.
        val hasChildren = endInclusive >= row + 1
        val hasText = line.substring(minOf(indent + 2, line.length)).isNotBlank()
        if (!hasChildren && !hasText) return
        if (endInclusive < row + 1) {
            // Childless leaf with text: don't write a placeholder bullet
            // into the shared document yet — that would surface to other
            // panes viewing the same file (and the autosave loop would
            // potentially promote it into a brand new file). Instead,
            // mark the pane as "pending leaf zoom"; the first edit
            // intent (insertChar / insertNewline / insertText) will
            // materialize the placeholder child for real.
            patch {
                pushHistory(it).copy(
                    zoomedLineId = id,
                    cursorRow = row,
                    cursorCol = docState.lines[row].length,
                    anchorRow = null,
                    anchorCol = null,
                    collapsedIds = it.collapsedIds - id,
                    pendingLeafZoomChild = id,
                )
            }
        } else {
            val firstChildRow = row + 1
            val firstChildLine = docState.lines[firstChildRow]
            val bulletCol = DocumentLayout.bulletAsteriskColumn(firstChildLine)
            val targetCol = if (bulletCol >= 0) bulletCol + 2 else 0
            patch {
                pushHistory(it).copy(
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
        if (!stateProvider().isLoaded) return
        patch {
            pushHistory(it).copy(
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
        if (!stateProvider().isLoaded) return
        if (lineId == null) {
            zoomOut()
            return
        }
        patch {
            pushHistory(it).copy(
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
        patch {
            it.copy(
                zoomedLineId = target,
                zoomHistory = remaining,
                zoomForward = (it.zoomForward + current).takeLast(historyCap),
                anchorRow = null,
                anchorCol = null,
                collapsedIds = if (target != null) it.collapsedIds - target else it.collapsedIds,
                pendingLeafZoomChild = null,
            )
        }
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
        patch {
            it.copy(
                zoomedLineId = target,
                zoomForward = remaining,
                zoomHistory = (it.zoomHistory + current).takeLast(historyCap),
                anchorRow = null,
                anchorCol = null,
                collapsedIds = if (target != null) it.collapsedIds - target else it.collapsedIds,
                pendingLeafZoomChild = null,
            )
        }
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
     * Returns [state] with the current `zoomedLineId` appended to
     * [PaneBackingViewModel.State.zoomHistory] and the forward
     * stack cleared. Used by every "go somewhere new" intent so back can
     * find it later. Capped at [historyCap] to avoid unbounded growth on
     * navigation-heavy sessions.
     */
    private fun pushHistory(state: PaneBackingViewModel.State): PaneBackingViewModel.State =
        state.copy(
            zoomHistory = (state.zoomHistory + state.zoomedLineId).takeLast(historyCap),
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
