/*
 * ScrollIntoView.kt (jsMain)
 * --------------------------
 * Scroll math for keeping the caret visible and for ramping the
 * auto-scroll speed during drag-to-select. Touches the editor element's
 * scroll properties only; no event wiring or business logic.
 */

package se.soderbjorn.notegrow.main

import org.w3c.dom.HTMLElement

/**
 * Scrolls [editor] so that the caret row in [state] is in view. Works
 * correctly both at document root and when zoomed: visual row is
 * computed over the currently-rendered slice, not the whole document.
 */
fun scrollCursorIntoView(
    editor: HTMLElement,
    state: DocumentViewBackingViewModel.State,
    style: EditorStyle,
    wrapWidth: Int,
    startRow: Int,
    endRowInclusive: Int,
) {
    val cursorRow = state.cursorRow
    if (cursorRow < startRow || cursorRow > endRowInclusive) return
    val visibleLines = state.lines.subList(startRow, endRowInclusive + 1)
    val visualRow = DocumentLayout.visualRowOfCursor(
        visibleLines, cursorRow - startRow, state.cursorCol, wrapWidth
    )
    val top = style.editorPaddingPx + visualRow * style.lineHeightPx
    val bottom = top + style.lineHeightPx
    val scrollTop = editor.scrollTop
    val clientHeight = editor.clientHeight
    when {
        top < scrollTop -> editor.scrollTop = top.toDouble()
        bottom > scrollTop + clientHeight ->
            editor.scrollTop = (bottom - clientHeight).toDouble()
    }
}

/**
 * Auto-scroll step size while drag-selecting past the editor's visible
 * rectangle. Ramps up as the pointer moves farther outside.
 */
fun stepForDistance(distance: Double, lineHeightPx: Int): Double {
    val base = lineHeightPx.toDouble()
    val ramp = (distance / 20.0).coerceIn(1.0, 4.0)
    return base * ramp
}
