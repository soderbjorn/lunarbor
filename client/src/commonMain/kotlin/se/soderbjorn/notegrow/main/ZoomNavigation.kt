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
        val endInclusive = DocumentLayout.subtreeEnd(docState.lines, row, indent)
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
                    anchorCol = null
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
                    anchorCol = null
                )
            }
        }
    }

    fun zoomOut() {
        if (!stateProvider().isLoaded) return
        patch { it.copy(zoomedLineId = null, anchorRow = null, anchorCol = null) }
    }

    fun zoomInfo(state: DocumentViewBackingViewModel.State): DocumentViewBackingViewModel.ZoomInfo? =
        zoomInfoOf(state)
}
