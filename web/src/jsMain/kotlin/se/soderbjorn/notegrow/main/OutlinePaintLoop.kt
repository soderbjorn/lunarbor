/*
 * OutlinePaintLoop.kt (jsMain)
 * ----------------------------
 * The repaint pipeline for the editor body and the sticky header. Top-level
 * functions accept the DOM nodes, the current backing state, and a few
 * style/measurement values; they own no state themselves. The collector
 * that subscribes to the view-model's flow lives in `MainScreen` and just
 * forwards each emission to [paint] / [paintHeader].
 *
 * Style installation, character-width measurement, and wrap-width math
 * also live here because they're concerns of the painter rather than the
 * input handlers.
 */

package se.soderbjorn.notegrow.main

import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent

/**
 * Renders the "Loading…" placeholder into [editor]. Used on cold start
 * until the document is read from disk.
 */
fun paintLoading(editor: HTMLElement) {
    editor.innerHTML = ""
    val loading = document.createElement("div") as HTMLElement
    loading.textContent = "Loading…"
    loading.style.opacity = "0.6"
    editor.appendChild(loading)
}

/**
 * Repaints the sticky header to reflect the current zoom state. Shows
 * "Root" when not zoomed, or the zoom target's text when zoomed.
 * Clicking the header while zoomed returns to root.
 *
 * @param header The sticky header element.
 * @param backing Latest backing state, or `null` during the initial
 *   loading phase (in which case the header shows a muted placeholder).
 * @param viewModel View-model used to resolve zoom info and to invoke
 *   `zoomOut` from the click handler.
 * @param onZoomOutFocus Called after `zoomOut` so the editor can
 *   re-acquire focus.
 */
fun paintHeader(
    header: HTMLElement,
    backing: DocumentViewBackingViewModel.State?,
    viewModel: MainViewModel,
    onZoomOutFocus: () -> Unit,
) {
    header.innerHTML = ""
    val zoom = backing?.let { viewModel.zoomInfo(it) }
    val label = document.createElement("span") as HTMLElement
    if (zoom == null) {
        label.textContent = "Root"
        label.style.apply {
            color = "var(--t-text-secondary, #9aa0a6)"
            cursor = "default"
        }
        header.onclick = null
        header.style.cursor = "default"
    } else {
        val title = zoom.titleText.ifEmpty { "Untitled" }
        label.textContent = title
        label.style.apply {
            color = "var(--t-terminal-fg, #e6e6e6)"
            fontWeight = "600"
        }
        val hint = document.createElement("span") as HTMLElement
        hint.textContent = "Root  /  "
        hint.style.apply {
            color = "var(--t-text-secondary, #9aa0a6)"
            marginRight = "0"
        }
        header.appendChild(hint)
        header.style.cursor = "pointer"
        header.title = "Back to Root"
        header.onclick = { _ ->
            viewModel.zoomOut()
            onZoomOutFocus()
        }
    }
    header.appendChild(label)
}

/**
 * Repaints the editor for the current [state]. Renders only the rows
 * inside the current zoom range (or the whole document when at root).
 * Also scrolls the caret into view at the end of the paint.
 */
fun paint(
    editor: HTMLElement,
    state: DocumentViewBackingViewModel.State,
    viewModel: MainViewModel,
    style: EditorStyle,
    charWidthPx: Double,
    wrapWidth: Int,
) {
    editor.innerHTML = ""
    if (!state.isLoaded) {
        paintLoading(editor)
        return
    }

    val width = wrapWidth.coerceAtLeast(1)
    val selection = DocumentViewBackingViewModel.selectionOf(state)
    val zoom = viewModel.zoomInfo(state)
    val startRow = zoom?.startRow ?: 0
    val endRowInclusive = zoom?.endRowInclusive ?: state.lines.lastIndex
    if (endRowInclusive < startRow) return

    for (row in startRow..endRowInclusive) {
        val line = state.lines[row]
        val chunks = DocumentLayout.wrapLine(line, width).toMutableList()
        val cursorInfo = if (row == state.cursorRow) {
            DocumentLayout.cursorVisualPosition(line, state.cursorCol, width).also {
                if (it.needsTrailingEmptyChunk) chunks.add("")
            }
        } else null

        val bulletCol = DocumentLayout.bulletAsteriskColumn(line)
        chunks.forEachIndexed { chunkIndex, chunk ->
            val chunkDiv = document.createElement("div") as HTMLElement
            chunkDiv.style.apply {
                height = "${style.lineHeightPx}px"
                setProperty("position", "relative")
            }

            if (selection != null) {
                val highlight = DocumentLayout.chunkSelectionHighlight(
                    selection, row, line, chunkIndex, chunks.size, chunk.length, width
                )
                if (highlight != null) {
                    appendSelectionHighlight(chunkDiv, highlight, charWidthPx, style.lineHeightPx)
                }
            }

            val displayChunk = if (chunkIndex == 0 && bulletCol >= 0) {
                chunk.substring(0, bulletCol) + "•" + chunk.substring(bulletCol + 1)
            } else {
                chunk
            }
            val textSpan = document.createElement("span") as HTMLElement
            textSpan.style.apply {
                setProperty("position", "relative")
                setProperty("z-index", "1")
            }
            textSpan.textContent = displayChunk
            chunkDiv.appendChild(textSpan)

            if (chunkIndex == 0 && bulletCol >= 0) {
                appendBulletClickTarget(chunkDiv, bulletCol, row, charWidthPx, style.lineHeightPx, viewModel) {
                    editor.focus()
                }
            }

            if (cursorInfo != null && cursorInfo.chunkIndex == chunkIndex) {
                appendCursor(chunkDiv, cursorInfo.colInChunk, charWidthPx, style.lineHeightPx)
            }
            editor.appendChild(chunkDiv)
        }
    }
    scrollCursorIntoView(editor, state, style, width, startRow, endRowInclusive)
}

/**
 * Overlays a transparent clickable element on top of a bullet marker so
 * clicking `•` zooms into that bullet without also moving the caret.
 */
private fun appendBulletClickTarget(
    chunkDiv: HTMLElement,
    bulletCol: Int,
    absoluteRow: Int,
    charWidthPx: Double,
    lineHeightPx: Int,
    viewModel: MainViewModel,
    onAfterZoom: () -> Unit,
) {
    val target = document.createElement("div") as HTMLElement
    target.className = "notegrow-bullet"
    target.title = "Zoom into bullet"
    target.style.apply {
        setProperty("position", "absolute")
        left = "${bulletCol * charWidthPx}px"
        top = "0"
        width = "${charWidthPx}px"
        height = "${lineHeightPx}px"
        setProperty("z-index", "3")
        cursor = "pointer"
    }
    target.addEventListener("mousedown", { event ->
        val me = event as MouseEvent
        me.stopPropagation()
        me.preventDefault()
    })
    target.addEventListener("click", { event ->
        val me = event as MouseEvent
        me.stopPropagation()
        me.preventDefault()
        viewModel.zoomInto(absoluteRow)
        onAfterZoom()
    })
    chunkDiv.appendChild(target)
}

/**
 * Paints a translucent rectangle representing the portion of the user's
 * selection that falls inside one rendered chunk.
 */
private fun appendSelectionHighlight(
    chunkDiv: HTMLElement,
    highlight: DocumentLayout.ChunkHighlight,
    charWidthPx: Double,
    lineHeightPx: Int,
) {
    val div = document.createElement("div") as HTMLElement
    div.style.apply {
        setProperty("position", "absolute")
        left = "${highlight.leftChars * charWidthPx}px"
        top = "0"
        width = "${highlight.widthChars * charWidthPx}px"
        height = "${lineHeightPx}px"
        backgroundColor = "var(--t-terminal-selection, rgba(90, 176, 255, 0.3))"
        setProperty("pointer-events", "none")
        setProperty("z-index", "0")
    }
    chunkDiv.appendChild(div)
}

/**
 * Appends the blinking caret span at column [col] inside a chunk.
 */
private fun appendCursor(container: HTMLElement, col: Int, charWidthPx: Double, lineHeightPx: Int) {
    val cursor = document.createElement("span") as HTMLElement
    cursor.className = "notegrow-cursor"
    cursor.style.apply {
        setProperty("position", "absolute")
        left = "${col * charWidthPx}px"
        top = "0"
        width = "2px"
        height = "${lineHeightPx}px"
        backgroundColor = "var(--t-terminal-cursor, #5ab0ff)"
        setProperty("pointer-events", "none")
        setProperty("z-index", "2")
    }
    container.appendChild(cursor)
}

/**
 * Injects the stylesheet that drives caret blinking, scrollbars, and
 * bullet-hover styling. Runs once — the guard on the `id` makes repeat
 * calls cheap.
 */
fun ensureStyles() {
    val existing = document.getElementById("notegrow-cursor-style")
    if (existing != null) return
    val style = document.createElement("style") as HTMLElement
    style.id = "notegrow-cursor-style"
    style.textContent = """
        @keyframes notegrow-cursor-blink {
            0%, 49% { opacity: 1; }
            50%, 100% { opacity: 0; }
        }
        .notegrow-cursor {
            animation: notegrow-cursor-blink 1s steps(1, end) infinite;
        }
        .notegrow-editor::-webkit-scrollbar {
            width: 12px;
        }
        .notegrow-editor::-webkit-scrollbar-track {
            background: var(--t-terminal-bg, #1e1e1e);
        }
        .notegrow-editor::-webkit-scrollbar-thumb {
            background: var(--t-border-strong, #4a4a4a);
            border-radius: 6px;
            border: 2px solid var(--t-terminal-bg, #1e1e1e);
        }
        .notegrow-editor::-webkit-scrollbar-thumb:hover {
            background: var(--t-text-tertiary, #5e5e5e);
        }
        .notegrow-bullet:hover {
            background: var(--t-terminal-selection, rgba(90, 176, 255, 0.25));
            border-radius: 3px;
        }
        .notegrow-header:hover {
            background: var(--t-surface-overlay, #2a2a2a);
        }
    """.trimIndent()
    document.head?.appendChild(style)
}

/**
 * Measures the width of a single `M` at the editor's font, so cursor
 * and selection overlays can position themselves in character-unit
 * multiples of that width.
 */
fun measureCharWidth(style: EditorStyle): Double {
    val canvas = document.createElement("canvas") as HTMLCanvasElement
    val ctx = canvas.getContext("2d").asDynamic()
    ctx.font = "${style.fontSize}px ${style.fontFamily}"
    return (ctx.measureText("M").width as Number).toDouble()
}

/**
 * Computes the wrap width (in characters) from the editor's pixel width
 * and the measured character width.
 */
fun wrapWidthInChars(editor: HTMLElement, charWidth: Double): Int {
    val usableWidth = (editor.clientWidth - 24).coerceAtLeast(charWidth.toInt())
    return (usableWidth / charWidth).toInt().coerceAtLeast(1)
}
