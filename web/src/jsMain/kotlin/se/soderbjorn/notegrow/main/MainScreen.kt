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

class MainScreen(
    private val viewModel: MainViewModel,
    private val scope: CoroutineScope
) {
    private val fontFamily = "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace"
    private val fontSize = 14
    private val lineHeightPx = 20
    private val editorPaddingPx = 12

    private val wrapWidthFlow = MutableStateFlow(40)

    private var charWidthPx = 0.0
    private var isDragging = false
    private var lastDragClientX: Double = 0.0
    private var lastDragClientY: Double = 0.0
    private var autoScrollHandle: Int? = null

    fun render(root: HTMLElement) {
        root.innerHTML = ""
        ensureCursorStyles()
        document.documentElement?.let { (it as HTMLElement).style.backgroundColor = "#1e1e1e" }
        document.body?.let {
            val bodyStyle = (it as HTMLElement).style
            bodyStyle.margin = "0"
            bodyStyle.padding = "0"
            bodyStyle.backgroundColor = "#1e1e1e"
        }
        root.style.margin = "0"
        root.style.height = "100vh"
        root.style.display = "flex"
        root.style.flexDirection = "column"
        root.style.backgroundColor = "#1e1e1e"

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
                    if (backing == null) paintLoading(editor) else paint(editor, backing, width)
                }
        }
    }

    private fun paintLoading(editor: HTMLElement) {
        editor.innerHTML = ""
        val loading = document.createElement("div") as HTMLElement
        loading.textContent = "Loading…"
        loading.style.opacity = "0.6"
        editor.appendChild(loading)
    }

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

    private fun writeClipboard(text: String) {
        val clipboard = window.asDynamic().navigator?.clipboard
        if (clipboard != null && clipboard != undefined) {
            clipboard.writeText(text)
        }
    }

    private fun readClipboard() {
        val clipboard = window.asDynamic().navigator?.clipboard
        if (clipboard == null || clipboard == undefined) return
        val promise = clipboard.readText()
        promise.then({ text: Any? ->
            if (text != null) viewModel.insertText(text.toString())
            null
        })
    }

    private fun paint(editor: HTMLElement, state: DocumentViewBackingViewModel.State, wrapWidth: Int) {
        editor.innerHTML = ""
        if (!state.isLoaded) {
            paintLoading(editor)
            return
        }

        val width = wrapWidth.coerceAtLeast(1)
        val selection = DocumentViewBackingViewModel.selectionOf(state)
        state.lines.forEachIndexed { row, line ->
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

                if (cursorInfo != null && cursorInfo.chunkIndex == chunkIndex) {
                    appendCursor(chunkDiv, cursorInfo.colInChunk)
                }
                editor.appendChild(chunkDiv)
            }
        }
        scrollCursorIntoView(editor, state, width)
    }

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

    private fun scrollCursorIntoView(
        editor: HTMLElement,
        state: DocumentViewBackingViewModel.State,
        width: Int
    ) {
        val visualRow = DocumentLayout.visualRowOfCursor(
            state.lines, state.cursorRow, state.cursorCol, width
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

    private fun handleMouseMove(editor: HTMLElement, event: MouseEvent) {
        if (!isDragging) return
        lastDragClientX = event.clientX.toDouble()
        lastDragClientY = event.clientY.toDouble()
        extendDragSelection(editor)
        event.preventDefault()
    }

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

    private fun stepForDistance(distance: Double): Double {
        val base = lineHeightPx.toDouble()
        val ramp = (distance / 20.0).coerceIn(1.0, 4.0)
        return base * ramp
    }

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

    private fun stopAutoScroll() {
        val handle = autoScrollHandle ?: return
        window.clearInterval(handle)
        autoScrollHandle = null
    }

    private fun pointFromEvent(editor: HTMLElement, event: MouseEvent): Pair<Int, Int>? =
        pointFromClient(editor, event.clientX.toDouble(), event.clientY.toDouble())

    private fun pointFromClient(editor: HTMLElement, clientX: Double, clientY: Double): Pair<Int, Int>? {
        val backing = viewModel.stateFlow.value.backingState ?: return null
        if (!backing.isLoaded) return null
        val rect = editor.getBoundingClientRect()
        val localX = clientX - rect.left - editorPaddingPx + editor.scrollLeft
        val localY = clientY - rect.top - editorPaddingPx + editor.scrollTop
        val charWidth = if (charWidthPx > 0.0) charWidthPx else 1.0
        val visualRow = (localY / lineHeightPx).toInt().coerceAtLeast(0)
        val visualCol = ((localX / charWidth) + 0.5).toInt().coerceAtLeast(0)
        return DocumentLayout.locateLogicalPosition(backing.lines, visualRow, visualCol, wrapWidthFlow.value)
    }

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

    private fun ensureCursorStyles() {
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
        """.trimIndent()
        document.head?.appendChild(style)
    }

    private fun measureCharWidth(): Double {
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        val ctx = canvas.getContext("2d").asDynamic()
        ctx.font = "${fontSize}px $fontFamily"
        return (ctx.measureText("M").width as Number).toDouble()
    }

    private fun updateWrapWidth(editor: HTMLElement, charWidth: Double) {
        val usableWidth = (editor.clientWidth - 24).coerceAtLeast(charWidth.toInt())
        val chars = (usableWidth / charWidth).toInt().coerceAtLeast(1)
        wrapWidthFlow.value = chars
    }
}
