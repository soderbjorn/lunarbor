/*
 * ZoomNavigation.kt
 * -----------------
 * Owns the "zoom into a bullet" intents — making one bullet the logical
 * root of the editor view, returning to root, and resolving the current
 * zoom id into concrete row geometry. The reconciliation step that
 * clamps a zoomed cursor inside the visible subtree lives in
 * `DocumentViewBackingViewModel.reconcile`.
 *
 * commonMain only — this slice does not touch the DOM or any platform UI.
 */

package se.soderbjorn.notegrow.main

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import se.soderbjorn.notegrow.main.DocumentViewBackingViewModel.Companion.TAB_SIZE

/**
 * Zoom slice of the per-viewer ViewModel. Composed by
 * `DocumentViewBackingViewModel`; mutates the aggregate state through
 * the supplied [patch] hook.
 *
 * @param documentBackingViewModel Shared document VM used to insert a
 *   placeholder child when zooming into a leaf bullet.
 * @param stateProvider Reads the latest aggregate state.
 * @param patch Applies a transform that touches document content; refreshes
 *   the mirrored `documentState` and reconciles.
 */
internal class ZoomNavigation(
    private val documentBackingViewModel: DocumentBackingViewModel,
    private val stateProvider: () -> DocumentViewBackingViewModel.State,
    private val patch: ((DocumentViewBackingViewModel.State) -> DocumentViewBackingViewModel.State) -> Unit,
    private val scope: CoroutineScope,
) {
    fun zoomInto(row: Int) {
        val s = stateProvider()
        if (!s.isLoaded) return
        val docState = s.documentState ?: return
        if (row !in docState.lines.indices) return
        val line = docState.lines[row]
        val indent = DocumentLayout.bulletAsteriskColumn(line)
        if (indent < 0) return
        val id = docState.lineIds[row]
        // If the zoom target is a folded ref, lazy-load its file first then
        // re-enter zoomInto with the now-loaded subtree.
        if (documentBackingViewModel.isPromotedRef(id) && id !in docState.expandedRefIds) {
            scope.launch {
                documentBackingViewModel.expandSubtree(id)
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
            val childIndent = indent + TAB_SIZE
            val childPrefix = " ".repeat(childIndent) + "* "
            documentBackingViewModel.insertText(row, docState.lines[row].length, "\n" + childPrefix)
            val newChildRow = row + 1
            patch {
                it.copy(
                    zoomedLineId = id,
                    cursorRow = newChildRow,
                    cursorCol = childPrefix.length,
                    anchorRow = null,
                    anchorCol = null,
                    collapsedIds = it.collapsedIds - id,
                )
            }
        } else {
            val firstChildRow = row + 1
            val firstChildLine = docState.lines[firstChildRow]
            val bulletCol = DocumentLayout.bulletAsteriskColumn(firstChildLine)
            val targetCol = if (bulletCol >= 0) bulletCol + 2 else 0
            patch {
                it.copy(
                    zoomedLineId = id,
                    cursorRow = firstChildRow,
                    cursorCol = targetCol,
                    anchorRow = null,
                    anchorCol = null,
                    collapsedIds = it.collapsedIds - id,
                )
            }
        }
    }

    fun zoomOut() {
        if (!stateProvider().isLoaded) return
        patch { it.copy(zoomedLineId = null, anchorRow = null, anchorCol = null) }
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
            it.copy(
                zoomedLineId = lineId,
                anchorRow = null,
                anchorCol = null,
                collapsedIds = it.collapsedIds - lineId,
            )
        }
    }

    fun zoomInfo(state: DocumentViewBackingViewModel.State): DocumentViewBackingViewModel.ZoomInfo? =
        zoomInfoOf(state)

    /**
     * Returns the ancestor breadcrumb chain for the currently zoomed line
     * (outer-to-inner, excluding the zoomed line itself). See
     * [bulletAncestorsOf] for ordering details.
     */
    fun bulletAncestors(state: DocumentViewBackingViewModel.State): List<BreadcrumbAncestor> =
        bulletAncestorsOf(state)

    /**
     * Outer-to-inner breadcrumb segments for the current zoom (ancestors +
     * current zoom target text). Empty when the document is not zoomed —
     * callers fall back to the pane's static title in that case. Delegates
     * to [zoomPathSegmentsOf].
     */
    fun zoomPathSegments(state: DocumentViewBackingViewModel.State): List<String> =
        zoomPathSegmentsOf(state)
}
