/*
 * OutlineInputHandlers.kt (jsMain)
 * --------------------------------
 * Keyboard, mouse, and clipboard event wiring for the editor view. Each
 * handler is a top-level function that takes the bits it needs (the
 * editor element, the view-model, the current style/measurement values,
 * and a small mutable [DragState] holder for the auto-scroll loop).
 *
 * Wrap-width updates are passed in as a callback because the live wrap
 * width belongs to the screen's `MutableStateFlow` — we don't want to
 * leak that flow into every handler.
 */

package se.soderbjorn.notegrow.main

import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent

/**
 * Mutable scratch state shared by mousedown, mousemove, mouseup, and
 * the auto-scroll interval. All fields are written and read from the
 * single browser thread, so plain `var`s are fine.
 *
 * @property isDragging `true` while the user holds primary mouse and is
 *   extending a selection.
 * @property lastDragClientX Last known clientX during a drag — used by
 *   the auto-scroll interval, which has no event of its own.
 * @property lastDragClientY Last known clientY during a drag.
 * @property autoScrollHandle `setInterval` handle for the active
 *   auto-scroll loop, or `null` when no loop is running.
 */
class DragState {
    var isDragging: Boolean = false
    var lastDragClientX: Double = 0.0
    var lastDragClientY: Double = 0.0
    var autoScrollHandle: Int? = null
}

/**
 * Keyboard event router. Maps DOM key events to `MainViewModel` intents,
 * handling Cmd/Ctrl shortcuts, Alt-word movement, basic navigation,
 * editing, and printable characters.
 */
fun handleKey(event: KeyboardEvent, viewModel: MainViewModel) {
    val extend = event.shiftKey
    val cmd = event.ctrlKey || event.metaKey

    if (event.altKey && !cmd) {
        when (event.key) {
            "ArrowLeft" -> { viewModel.moveWordLeft(extend); event.preventDefault() }
            "ArrowRight" -> { viewModel.moveWordRight(extend); event.preventDefault() }
        }
        return
    }

    if (cmd) {
        when (event.key.lowercase()) {
            "c" -> {
                viewModel.getSelectedText()?.let { writeClipboard(it) }
                event.preventDefault()
            }
            "x" -> {
                viewModel.onCutRequested()?.let { writeClipboard(it) }
                event.preventDefault()
            }
            "v" -> {
                readClipboard(viewModel)
                event.preventDefault()
            }
            "a" -> {
                viewModel.selectAll()
                event.preventDefault()
            }
            "arrowleft" -> { viewModel.moveLineStart(extend); event.preventDefault() }
            "arrowright" -> { viewModel.moveLineEnd(extend); event.preventDefault() }
            "arrowup" -> { viewModel.moveDocStart(extend); event.preventDefault() }
            "arrowdown" -> { viewModel.moveDocEnd(extend); event.preventDefault() }
        }
        return
    }

    when (event.key) {
        "ArrowLeft" -> { viewModel.moveLeft(extend); event.preventDefault() }
        "ArrowRight" -> { viewModel.moveRight(extend); event.preventDefault() }
        "ArrowUp" -> { viewModel.moveUp(extend); event.preventDefault() }
        "ArrowDown" -> { viewModel.moveDown(extend); event.preventDefault() }
        "Home" -> { viewModel.moveLineStart(extend); event.preventDefault() }
        "End" -> { viewModel.moveLineEnd(extend); event.preventDefault() }
        "Backspace" -> { viewModel.backspace(); event.preventDefault() }
        "Delete" -> {
            if (viewModel.getSelectedText() != null) {
                viewModel.deleteSelectionIfAny()
            } else {
                viewModel.moveRight(true)
                viewModel.backspace()
            }
            event.preventDefault()
        }
        "Enter" -> { viewModel.insertNewline(); event.preventDefault() }
        "Escape" -> {
            val backing = viewModel.stateFlow.value.backingState
            if (backing != null && viewModel.zoomInfo(backing) != null) {
                viewModel.zoomOut()
                event.preventDefault()
            }
        }
        "Tab" -> {
            if (event.shiftKey) {
                viewModel.outdentLine()
            } else if (viewModel.isBulletLine()) {
                viewModel.indentLine()
            } else {
                viewModel.insertChar(' '); viewModel.insertChar(' ')
            }
            event.preventDefault()
        }
        else -> if (event.key.length == 1) {
            viewModel.insertChar(event.key[0])
            event.preventDefault()
        }
    }
}

/**
 * Writes [text] to the browser clipboard. A no-op when the Clipboard
 * API is unavailable (some older browsers, insecure contexts).
 */
fun writeClipboard(text: String) {
    val clipboard = window.asDynamic().navigator?.clipboard
    if (clipboard != null && clipboard != undefined) {
        clipboard.writeText(text)
    }
}

/**
 * Reads the browser clipboard asynchronously and, when available,
 * inserts the text at the caret via `viewModel.insertText`.
 */
fun readClipboard(viewModel: MainViewModel) {
    val clipboard = window.asDynamic().navigator?.clipboard
    if (clipboard == null || clipboard == undefined) return
    val promise = clipboard.readText()
    promise.then({ text: Any? ->
        if (text != null) viewModel.insertText(text.toString())
        null
    })
}

/**
 * Mouse-down handler: sets caret, starts word/line selection on
 * double/triple click, and begins drag-to-select for single click.
 */
fun handleMouseDown(
    editor: HTMLElement,
    event: MouseEvent,
    viewModel: MainViewModel,
    style: EditorStyle,
    charWidthPx: Double,
    wrapWidth: Int,
    drag: DragState,
) {
    if (event.button.toInt() != 0) return
    drag.lastDragClientX = event.clientX.toDouble()
    drag.lastDragClientY = event.clientY.toDouble()
    val (row, col) = pointFromEvent(editor, viewModel, style, charWidthPx, wrapWidth, event) ?: return
    val clickCount = event.asDynamic().detail.unsafeCast<Int>()
    when {
        clickCount >= 3 -> {
            viewModel.selectLine(row)
            drag.isDragging = false
        }
        clickCount == 2 -> {
            viewModel.selectWord(row, col)
            drag.isDragging = false
        }
        else -> {
            viewModel.moveTo(row, col, extend = event.shiftKey)
            drag.isDragging = true
        }
    }
    editor.focus()
    event.preventDefault()
}

/**
 * Mouse-move handler used during drag-to-select. Delegates to
 * [extendDragSelection] when a drag is in progress.
 */
fun handleMouseMove(
    editor: HTMLElement,
    event: MouseEvent,
    viewModel: MainViewModel,
    style: EditorStyle,
    charWidthPx: Double,
    wrapWidth: Int,
    drag: DragState,
) {
    if (!drag.isDragging) return
    drag.lastDragClientX = event.clientX.toDouble()
    drag.lastDragClientY = event.clientY.toDouble()
    extendDragSelection(editor, viewModel, style, charWidthPx, wrapWidth, drag)
    event.preventDefault()
}

/**
 * Grows the active selection toward the current mouse position,
 * auto-scrolling when the pointer is above or below the editor's
 * visible rectangle.
 */
fun extendDragSelection(
    editor: HTMLElement,
    viewModel: MainViewModel,
    style: EditorStyle,
    charWidthPx: Double,
    wrapWidth: Int,
    drag: DragState,
) {
    val rect = editor.getBoundingClientRect()
    val cy = drag.lastDragClientY
    val outsideAbove = cy < rect.top
    val outsideBelow = cy > rect.bottom
    val scrollDelta = when {
        outsideAbove -> -stepForDistance(rect.top - cy, style.lineHeightPx)
        outsideBelow -> stepForDistance(cy - rect.bottom, style.lineHeightPx)
        else -> 0.0
    }
    if (scrollDelta != 0.0) {
        editor.scrollTop = editor.scrollTop + scrollDelta
        startAutoScroll(editor, viewModel, style, charWidthPx, wrapWidth, drag)
    } else {
        stopAutoScroll(drag)
    }
    val clampedY = cy.coerceIn(rect.top + 1.0, rect.bottom - 1.0)
    val point = pointFromClient(
        editor, viewModel, style, charWidthPx, wrapWidth, drag.lastDragClientX, clampedY
    ) ?: return
    viewModel.moveTo(point.first, point.second, extend = true)
}

/**
 * Starts the interval timer that drives auto-scroll while drag-selecting.
 * Idempotent — a second call while a timer is already running is a no-op.
 */
fun startAutoScroll(
    editor: HTMLElement,
    viewModel: MainViewModel,
    style: EditorStyle,
    charWidthPx: Double,
    wrapWidth: Int,
    drag: DragState,
) {
    if (drag.autoScrollHandle != null) return
    drag.autoScrollHandle = window.setInterval({
        if (!drag.isDragging) {
            stopAutoScroll(drag)
        } else {
            extendDragSelection(editor, viewModel, style, charWidthPx, wrapWidth, drag)
        }
    }, 30)
}

/** Cancels the auto-scroll interval if one is active. */
fun stopAutoScroll(drag: DragState) {
    val handle = drag.autoScrollHandle ?: return
    window.clearInterval(handle)
    drag.autoScrollHandle = null
}
