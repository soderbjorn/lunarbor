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
    val zoom = viewModel.zoomInfo(backing)
    val startRow = zoom?.startRow ?: 0
    val endRowInclusive = zoom?.endRowInclusive ?: backing.lines.lastIndex
    if (endRowInclusive < startRow) return null
    val visibleLines = backing.lines.subList(startRow, endRowInclusive + 1)
    val rect = editor.getBoundingClientRect()
    val localX = clientX - rect.left - style.editorPaddingPx + editor.scrollLeft
    val localY = clientY - rect.top - style.editorPaddingPx + editor.scrollTop
    val charWidth = if (charWidthPx > 0.0) charWidthPx else 1.0
    val visualRow = (localY / style.lineHeightPx).toInt().coerceAtLeast(0)
    val visualCol = ((localX / charWidth) + 0.5).toInt().coerceAtLeast(0)
    val (localRow, col) = DocumentLayout.locateLogicalPosition(
        visibleLines, visualRow, visualCol, wrapWidth
    )
    return (startRow + localRow) to col
}
