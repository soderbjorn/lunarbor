/*
 * HitTesting.kt (jsMain)
 * ----------------------
 * Pure functions for converting mouse-event coordinates into absolute
 * (row, col) document positions. Touches the DOM only to read the
 * editor's bounding rect and scroll offsets — no writes, no event wiring.
 */

package se.soderbjorn.notegrow.main

import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent

/**
 * Converts a mouse event into an absolute (row, col) pair on the
 * document. Returns `null` while the document is still loading or while
 * the editor has no visible rows (e.g. zoomed into an empty subtree).
 */
fun pointFromEvent(
    editor: HTMLElement,
    viewModel: MainViewModel,
    style: EditorStyle,
    charWidthPx: Double,
    wrapWidth: Int,
    event: MouseEvent,
): Pair<Int, Int>? = pointFromClient(
    editor, viewModel, style, charWidthPx, wrapWidth,
    event.clientX.toDouble(), event.clientY.toDouble()
)

/**
 * Converts a pair of client-space pixel coordinates into an absolute
 * (row, col) on the document, accounting for wrap width and — when
 * zoomed — the row offset between the visible slice and the full
 * document.
 */
fun pointFromClient(
    editor: HTMLElement,
    viewModel: MainViewModel,
    style: EditorStyle,
    charWidthPx: Double,
    wrapWidth: Int,
    clientX: Double,
    clientY: Double,
): Pair<Int, Int>? {
    val backing = viewModel.stateFlow.value.backingState ?: return null
    if (!backing.isLoaded) return null
    val docState = backing.documentState ?: return null
    val zoom = viewModel.zoomInfo(backing)
    val startRow = zoom?.startRow ?: 0
    val endRowInclusive = zoom?.endRowInclusive ?: backing.lines.lastIndex
    if (endRowInclusive < startRow) return null
    // Mirror the paint loop's relative-indent transformation: when zoomed,
    // each visible line is rendered with its first `viewOriginCol` characters
    // dropped (the zoom target's indent plus one TAB_SIZE level so closest
    // descendants render flush-left), so the click coordinates we feed into
    // `locateLogicalPosition` must be against the same trimmed view. Add
    // `viewOriginCol` back when returning the column so the caller stores
    // absolute model columns.
    val viewOriginCol = zoom?.let { it.zoomIndent + DocumentViewBackingViewModel.TAB_SIZE } ?: 0
    val visibleRows = DocumentLayout.visibleRowsOf(
        docState.lines, docState.lineIds, backing.collapsedIds, startRow, endRowInclusive
    )
    if (visibleRows.isEmpty()) return null
    val visibleLines = visibleRows.map { row ->
        val raw = backing.lines[row]
        if (viewOriginCol > 0 && raw.length >= viewOriginCol) raw.substring(viewOriginCol) else raw
    }
    val rect = editor.getBoundingClientRect()
    val localX = clientX - rect.left - style.editorPaddingLeftPx + editor.scrollLeft
    val localY = clientY - rect.top - style.editorPaddingTopPx + editor.scrollTop
    val charWidth = if (charWidthPx > 0.0) charWidthPx else 1.0
    val visualRow = (localY / style.lineHeightPx).toInt().coerceAtLeast(0)
    // Find which logical (visible) row the click lands on first; the painter
    // shifts each row right by `lineLeftOffsetPxFor(bulletCol)` so we must
    // subtract the same offset before mapping localX to a column.
    val w = wrapWidth.coerceAtLeast(1)
    var remaining = visualRow
    var localRow = visibleLines.lastIndex
    for ((idx, line) in visibleLines.withIndex()) {
        val chunkCount = DocumentLayout.chunkCountOf(line, w)
        if (remaining < chunkCount) {
            localRow = idx
            break
        }
        remaining -= chunkCount
    }
    val rowBulletCol = DocumentLayout.bulletAsteriskColumn(visibleLines[localRow])
    val rowOffsetPx = lineLeftOffsetPxFor(rowBulletCol)
    val adjustedX = (localX - rowOffsetPx).coerceAtLeast(0.0)
    val visualCol = ((adjustedX / charWidth) + 0.5).toInt().coerceAtLeast(0)
    val (localRow2, col) = DocumentLayout.locateLogicalPosition(
        visibleLines, visualRow, visualCol, wrapWidth
    )
    // Clamp the resolved column so that on a bullet row the cursor cannot land in the
    // indent / `"* "` marker zone — clicks to the left of the bullet snap to the start
    // of the row's text content. No-op on non-bullet rows and on wrap continuation rows
    // (where the resolved column is already well past the marker).
    val resolvedLine = visibleLines[localRow2.coerceAtMost(visibleLines.lastIndex)]
    val clampedCol = col.coerceAtLeast(DocumentLayout.textStartCol(resolvedLine))
    val absRow = visibleRows[localRow2.coerceAtMost(visibleRows.lastIndex)]
    return absRow to (clampedCol + viewOriginCol)
}
