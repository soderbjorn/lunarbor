/*
 * MainScreen.kt (jsMain)
 * ----------------------
 * DOM view for the web platform. Renders the note into a `<div>`-based custom
 * editor and wires keyboard / mouse / clipboard events into
 * `MainViewModel` intents.
 *
 * The screen is composed of two stacked regions inside a flex column:
 *
 *   ┌───────────────────────────────┐
 *   │  header  (zoom breadcrumb)    │  ← fixed height, stays put when editor scrolls
 *   ├───────────────────────────────┤
 *   │  editor  (lines, cursor, …)   │  ← grows, owns scroll
 *   └───────────────────────────────┘
 *
 * The header shows "Root" when the viewer is at the document root, or the
 * text of the current zoom target otherwise. Clicking the header while
 * zoomed returns to root. Clicking a bullet marker (•) in the editor zooms
 * into that bullet's subtree.
 *
 * When zoomed, only the zoom target's descendant rows are painted, and
 * visual-row↔absolute-row translation in hit-testing and scroll-into-view
 * is offset by the visible slice's start row.
 *
 * This file holds only platform glue — no business rules, no document
 * logic. User input is translated to intent calls on `MainViewModel`;
 * nothing else.
 */

package se.soderbjorn.notegrow.main

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent

/**
 * The note editor's web view.
 *
 * ### Callers
 * - Instantiated in `Main.kt` (web entry point) after the DI graph is
 *   built; exactly one instance per document.
 * - `render` is the only public entry point — called once to mount the UI
 *   into a host element.
 *
 * @param viewModel Platform VM that exposes state and accepts intents.
 * @param scope Coroutine scope owning the paint-loop collector.
 */
class MainScreen(
    private val viewModel: MainViewModel,
    private val scope: CoroutineScope
) {
    private val fontFamily = "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace"
    private val fontSize = 14
    private val lineHeightPx = 20
    private val editorPaddingPx = 12
    private val headerPaddingPx = 10

    private val wrapWidthFlow = MutableStateFlow(40)

    private var charWidthPx = 0.0
    private var isDragging = false
    private var lastDragClientX: Double = 0.0
    private var lastDragClientY: Double = 0.0
    private var autoScrollHandle: Int? = null

    private var headerElement: HTMLElement? = null
    private var editorElement: HTMLElement? = null

    /**
     * Mounts the editor UI into [root]. Clears any prior content, builds
     * the header + editor DOM, wires up input listeners, and starts the
     * paint-loop collector. Safe to call once per app startup.
     *
     * @param root Host element that fills the browser viewport.
     */
    fun render(root: HTMLElement) {
        root.innerHTML = ""
        ensureStyles()
        document.documentElement?.let { (it as HTMLElement).style.backgroundColor = "#1e1e1e" }
        document.body?.let {
            val bodyStyle = it.style
            bodyStyle.margin = "0"
            bodyStyle.padding = "0"
            bodyStyle.backgroundColor = "#1e1e1e"
        }
        root.style.margin = "0"
        root.style.height = "100vh"
        root.style.display = "flex"
        root.style.flexDirection = "column"
        root.style.backgroundColor = "#1e1e1e"

        val header = document.createElement("div") as HTMLElement
        header.className = "notegrow-header"
        header.style.apply {
            flex = "0 0 auto"
            padding = "${headerPaddingPx}px ${editorPaddingPx}px"
            fontFamily = this@MainScreen.fontFamily
            fontSize = "${this@MainScreen.fontSize}px"
            lineHeight = "${this@MainScreen.lineHeightPx}px"
            backgroundColor = "#252525"
            color = "#e6e6e6"
            setProperty("border-bottom", "1px solid #333333")
            setProperty("user-select", "none")
            whiteSpace = "nowrap"
            setProperty("overflow", "hidden")
            setProperty("text-overflow", "ellipsis")
        }
        root.appendChild(header)
        headerElement = header

        val editor = document.createElement("div") as HTMLElement
        editor.setAttribute("tabindex", "0")
        editor.className = "notegrow-editor"
        editor.style.apply {
            flex = "1 1 auto"
            padding = "${editorPaddingPx}px"
            fontFamily = this@MainScreen.fontFamily
            fontSize = "${this@MainScreen.fontSize}px"
            lineHeight = "${this@MainScreen.lineHeightPx}px"
            whiteSpace = "pre"
            setProperty("overflow-x", "hidden")
            setProperty("overflow-y", "auto")
            outline = "none"
            backgroundColor = "#1e1e1e"
            color = "#e6e6e6"
            setProperty("caret-color", "transparent")
            setProperty("user-select", "none")
        }
        root.appendChild(editor)
        editorElement = editor

        charWidthPx = measureCharWidth()
        updateWrapWidth(editor, charWidthPx)

        editor.addEventListener("keydown", { event -> handleKey(event as KeyboardEvent) })
        editor.addEventListener("mousedown", { event -> handleMouseDown(editor, event as MouseEvent) })
        window.addEventListener("mousemove", { event -> handleMouseMove(editor, event as MouseEvent) })
        window.addEventListener("mouseup", { _: Event ->
            isDragging = false
            stopAutoScroll()
        })
        window.addEventListener("resize", { _: Event -> updateWrapWidth(editor, charWidthPx) })

        editor.focus()

        scope.launch {
            combine(viewModel.stateFlow, wrapWidthFlow) { state, width -> state to width }
                .collect { (state, width) ->
                    val backing = state.backingState
                    if (backing == null) {
                        paintLoading(editor)
                        paintHeader(null)
                    } else {
                        paint(editor, backing, width)
                        paintHeader(backing)
                    }
                }
        }
    }

    /**
     * Renders the "Loading…" placeholder into [editor]. Used on cold start
     * until the document is read from disk.
     */
    private fun paintLoading(editor: HTMLElement) {
        editor.innerHTML = ""
        val loading = document.createElement("div") as HTMLElement
        loading.textContent = "Loading…"
        loading.style.opacity = "0.6"
        editor.appendChild(loading)
    }

    /**
     * Repaints the sticky header to reflect the current zoom state.
     * Shows "Root" when not zoomed, or the zoom target's text when zoomed.
     * Clicking the header while zoomed returns to root.
     *
     * @param backing Latest backing state, or `null` during the initial
     *   loading phase (in which case the header shows a muted placeholder).
     */
    private fun paintHeader(backing: DocumentViewBackingViewModel.State?) {
        val header = headerElement ?: return
        header.innerHTML = ""
        val zoom = backing?.let { viewModel.zoomInfo(it) }
        val label = document.createElement("span") as HTMLElement
        if (zoom == null) {
            label.textContent = "Root"
            label.style.apply {
                color = "#9aa0a6"
                cursor = "default"
            }
            header.onclick = null
            header.style.cursor = "default"
        } else {
            val title = zoom.titleText.ifEmpty { "Untitled" }
            label.textContent = title
            label.style.apply {
                color = "#e6e6e6"
                fontWeight = "600"
            }
            val hint = document.createElement("span") as HTMLElement
            hint.textContent = "Root  /  "
            hint.style.apply {
                color = "#9aa0a6"
                marginRight = "0"
            }
            header.appendChild(hint)
            header.style.cursor = "pointer"
            header.title = "Back to Root"
            header.onclick = { _ ->
                viewModel.zoomOut()
                editorElement?.focus()
            }
        }
        header.appendChild(label)
    }

    /**
     * Keyboard event router. Maps DOM key events to `MainViewModel` intents,
     * handling Cmd/Ctrl shortcuts, Alt-word movement, basic navigation,
     * editing, and printable characters.
     *
     * Called from the `keydown` listener registered on the editor div.
     */
    private fun handleKey(event: KeyboardEvent) {
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
                    readClipboard()
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
    private fun writeClipboard(text: String) {
        val clipboard = window.asDynamic().navigator?.clipboard
        if (clipboard != null && clipboard != undefined) {
            clipboard.writeText(text)
        }
    }

    /**
     * Reads the browser clipboard asynchronously and, when available,
     * inserts the text at the caret via `viewModel.insertText`.
     */
    private fun readClipboard() {
        val clipboard = window.asDynamic().navigator?.clipboard
        if (clipboard == null || clipboard == undefined) return
        val promise = clipboard.readText()
        promise.then({ text: Any? ->
            if (text != null) viewModel.insertText(text.toString())
            null
        })
    }

    /**
     * Repaints the editor for the current [state]. Renders only the rows
     * inside the current zoom range (or the whole document when at root).
     *
     * Called from the paint-loop collector after every state or wrap-width
     * change.
     *
     * @param editor The editor DOM node.
     * @param state Latest backing state.
     * @param wrapWidth Current character wrap width.
     */
    private fun paint(editor: HTMLElement, state: DocumentViewBackingViewModel.State, wrapWidth: Int) {
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
        if (endRowInclusive < startRow) return  // zoomed into an empty subtree (transient)

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
                    height = "${lineHeightPx}px"
                    setProperty("position", "relative")
                }

                if (selection != null) {
                    val highlight = DocumentLayout.chunkSelectionHighlight(
                        selection, row, line, chunkIndex, chunks.size, chunk.length, width
                    )
                    if (highlight != null) appendSelectionHighlight(chunkDiv, highlight)
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
                    appendBulletClickTarget(chunkDiv, bulletCol, row)
                }

                if (cursorInfo != null && cursorInfo.chunkIndex == chunkIndex) {
                    appendCursor(chunkDiv, cursorInfo.colInChunk)
                }
                editor.appendChild(chunkDiv)
            }
        }
        scrollCursorIntoView(editor, state, width, startRow, endRowInclusive)
    }

    /**
     * Overlays a transparent clickable element on top of a bullet marker so
     * clicking `•` zooms into that bullet without also moving the caret.
     *
     * @param chunkDiv The rendered chunk that contains the bullet character.
     * @param bulletCol Column of the bullet within that chunk.
     * @param absoluteRow Absolute row of the bullet, passed to `zoomInto`.
     */
    private fun appendBulletClickTarget(chunkDiv: HTMLElement, bulletCol: Int, absoluteRow: Int) {
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
            editorElement?.focus()
        })
        chunkDiv.appendChild(target)
    }

    /**
     * Paints a translucent rectangle representing the portion of the user's
     * selection that falls inside one rendered chunk.
     */
    private fun appendSelectionHighlight(
        chunkDiv: HTMLElement,
        highlight: DocumentLayout.ChunkHighlight
    ) {
        val div = document.createElement("div") as HTMLElement
        div.style.apply {
            setProperty("position", "absolute")
            left = "${highlight.leftChars * charWidthPx}px"
            top = "0"
            width = "${highlight.widthChars * charWidthPx}px"
            height = "${lineHeightPx}px"
            backgroundColor = "rgba(90, 176, 255, 0.3)"
            setProperty("pointer-events", "none")
            setProperty("z-index", "0")
        }
        chunkDiv.appendChild(div)
    }

    /**
     * Scrolls [editor] so that the caret row is in view. Works correctly
     * both at document root and when zoomed: visual row is computed over
     * the currently-rendered slice, not the whole document.
     */
    private fun scrollCursorIntoView(
        editor: HTMLElement,
        state: DocumentViewBackingViewModel.State,
        width: Int,
        startRow: Int,
        endRowInclusive: Int
    ) {
        val cursorRow = state.cursorRow
        if (cursorRow < startRow || cursorRow > endRowInclusive) return
        val visibleLines = state.lines.subList(startRow, endRowInclusive + 1)
        val visualRow = DocumentLayout.visualRowOfCursor(
            visibleLines, cursorRow - startRow, state.cursorCol, width
        )
        val top = editorPaddingPx + visualRow * lineHeightPx
        val bottom = top + lineHeightPx
        val scrollTop = editor.scrollTop
        val clientHeight = editor.clientHeight
        when {
            top < scrollTop -> editor.scrollTop = top.toDouble()
            bottom > scrollTop + clientHeight ->
                editor.scrollTop = (bottom - clientHeight).toDouble()
        }
    }

    /**
     * Mouse-down handler: sets caret, starts word/line selection on
     * double/triple click, and begins drag-to-select for single click.
     */
    private fun handleMouseDown(editor: HTMLElement, event: MouseEvent) {
        if (event.button.toInt() != 0) return
        lastDragClientX = event.clientX.toDouble()
        lastDragClientY = event.clientY.toDouble()
        val (row, col) = pointFromEvent(editor, event) ?: return
        val clickCount = event.asDynamic().detail.unsafeCast<Int>()
        when {
            clickCount >= 3 -> {
                viewModel.selectLine(row)
                isDragging = false
            }
            clickCount == 2 -> {
                viewModel.selectWord(row, col)
                isDragging = false
            }
            else -> {
                viewModel.moveTo(row, col, extend = event.shiftKey)
                isDragging = true
            }
        }
        editor.focus()
        event.preventDefault()
    }

    /**
     * Mouse-move handler used during drag-to-select. Delegates to
     * [extendDragSelection] when a drag is in progress.
     */
    private fun handleMouseMove(editor: HTMLElement, event: MouseEvent) {
        if (!isDragging) return
        lastDragClientX = event.clientX.toDouble()
        lastDragClientY = event.clientY.toDouble()
        extendDragSelection(editor)
        event.preventDefault()
    }

    /**
     * Grows the active selection toward the current mouse position,
     * auto-scrolling when the pointer is above or below the editor's
     * visible rectangle.
     */
    private fun extendDragSelection(editor: HTMLElement) {
        val rect = editor.getBoundingClientRect()
        val cy = lastDragClientY
        val outsideAbove = cy < rect.top
        val outsideBelow = cy > rect.bottom
        val scrollDelta = when {
            outsideAbove -> -stepForDistance(rect.top - cy)
            outsideBelow -> stepForDistance(cy - rect.bottom)
            else -> 0.0
        }
        if (scrollDelta != 0.0) {
            editor.scrollTop = editor.scrollTop + scrollDelta
            startAutoScroll(editor)
        } else {
            stopAutoScroll()
        }
        val clampedY = cy.coerceIn(rect.top + 1.0, rect.bottom - 1.0)
        val point = pointFromClient(editor, lastDragClientX, clampedY) ?: return
        viewModel.moveTo(point.first, point.second, extend = true)
    }

    /**
     * Scroll step for auto-scroll while drag-selecting past the visible
     * rectangle. Ramps up as the pointer moves farther outside.
     */
    private fun stepForDistance(distance: Double): Double {
        val base = lineHeightPx.toDouble()
        val ramp = (distance / 20.0).coerceIn(1.0, 4.0)
        return base * ramp
    }

    /**
     * Starts the interval timer that drives auto-scroll while drag-selecting.
     * Idempotent — a second call while a timer is already running is a no-op.
     */
    private fun startAutoScroll(editor: HTMLElement) {
        if (autoScrollHandle != null) return
        autoScrollHandle = window.setInterval({
            if (!isDragging) {
                stopAutoScroll()
            } else {
                extendDragSelection(editor)
            }
        }, 30)
    }

    /** Cancels the auto-scroll interval if one is active. */
    private fun stopAutoScroll() {
        val handle = autoScrollHandle ?: return
        window.clearInterval(handle)
        autoScrollHandle = null
    }

    /**
     * Converts a mouse event into an absolute (row, col) pair on the
     * document. Returns `null` while the document is still loading.
     */
    private fun pointFromEvent(editor: HTMLElement, event: MouseEvent): Pair<Int, Int>? =
        pointFromClient(editor, event.clientX.toDouble(), event.clientY.toDouble())

    /**
     * Converts a pair of client-space pixel coordinates into an absolute
     * (row, col) on the document, accounting for wrap width and — when
     * zoomed — the row offset between the visible slice and the full
     * document.
     */
    private fun pointFromClient(editor: HTMLElement, clientX: Double, clientY: Double): Pair<Int, Int>? {
        val backing = viewModel.stateFlow.value.backingState ?: return null
        if (!backing.isLoaded) return null
        val zoom = viewModel.zoomInfo(backing)
        val startRow = zoom?.startRow ?: 0
        val endRowInclusive = zoom?.endRowInclusive ?: backing.lines.lastIndex
        if (endRowInclusive < startRow) return null
        val visibleLines = backing.lines.subList(startRow, endRowInclusive + 1)
        val rect = editor.getBoundingClientRect()
        val localX = clientX - rect.left - editorPaddingPx + editor.scrollLeft
        val localY = clientY - rect.top - editorPaddingPx + editor.scrollTop
        val charWidth = if (charWidthPx > 0.0) charWidthPx else 1.0
        val visualRow = (localY / lineHeightPx).toInt().coerceAtLeast(0)
        val visualCol = ((localX / charWidth) + 0.5).toInt().coerceAtLeast(0)
        val (localRow, col) = DocumentLayout.locateLogicalPosition(
            visibleLines, visualRow, visualCol, wrapWidthFlow.value
        )
        return (startRow + localRow) to col
    }

    /**
     * Appends the blinking caret span at column [col] inside a chunk.
     */
    private fun appendCursor(container: HTMLElement, col: Int) {
        val cursor = document.createElement("span") as HTMLElement
        cursor.className = "notegrow-cursor"
        cursor.style.apply {
            setProperty("position", "absolute")
            left = "${col * charWidthPx}px"
            top = "0"
            width = "2px"
            height = "${lineHeightPx}px"
            backgroundColor = "#5ab0ff"
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
    private fun ensureStyles() {
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
                background: #1e1e1e;
            }
            .notegrow-editor::-webkit-scrollbar-thumb {
                background: #4a4a4a;
                border-radius: 6px;
                border: 2px solid #1e1e1e;
            }
            .notegrow-editor::-webkit-scrollbar-thumb:hover {
                background: #5e5e5e;
            }
            .notegrow-bullet:hover {
                background: rgba(90, 176, 255, 0.25);
                border-radius: 3px;
            }
            .notegrow-header:hover {
                background: #2a2a2a;
            }
        """.trimIndent()
        document.head?.appendChild(style)
    }

    /**
     * Measures the width of a single `M` at the editor's font, so cursor
     * and selection overlays can position themselves in character-unit
     * multiples of that width.
     */
    private fun measureCharWidth(): Double {
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        val ctx = canvas.getContext("2d").asDynamic()
        ctx.font = "${fontSize}px $fontFamily"
        return (ctx.measureText("M").width as Number).toDouble()
    }

    /**
     * Recomputes the wrap width (in characters) from the editor's pixel
     * width and pushes it onto [wrapWidthFlow]. Called on startup and on
     * window resize.
     */
    private fun updateWrapWidth(editor: HTMLElement, charWidth: Double) {
        val usableWidth = (editor.clientWidth - 24).coerceAtLeast(charWidth.toInt())
        val chars = (usableWidth / charWidth).toInt().coerceAtLeast(1)
        wrapWidthFlow.value = chars
    }
}
