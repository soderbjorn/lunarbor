/*
 * MainScreen.kt (jsMain)
 * ----------------------
 * DOM view for the web platform. Builds the header + editor layout, wires
 * the input handlers from `OutlineInputHandlers.kt`, and runs the paint
 * loop from `OutlinePaintLoop.kt`.
 *
 * The screen is composed of two stacked regions inside a flex column:
 *
 *   ┌───────────────────────────────┐
 *   │  header  (zoom breadcrumb)    │  ← fixed height, stays put when editor scrolls
 *   ├───────────────────────────────┤
 *   │  editor  (lines, cursor, …)   │  ← grows, owns scroll
 *   └───────────────────────────────┘
 *
 * This file holds only platform glue — no business rules, no document
 * logic. User input is translated to intent calls on `MainViewModel`;
 * actual paint and hit-testing math live in dedicated siblings.
 */

package se.soderbjorn.notegrow.main

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
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
    private val scope: CoroutineScope,
) {
    private val style = EditorStyle()
    private val wrapWidthFlow = MutableStateFlow(40)
    private val drag = DragState()

    private var charWidthPx: Double = 0.0
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
        installRootStyles(root)

        val header = buildHeaderElement()
        root.appendChild(header)
        headerElement = header

        val editor = buildEditorElement()
        root.appendChild(editor)
        editorElement = editor

        charWidthPx = measureCharWidth(style)
        wrapWidthFlow.value = wrapWidthInChars(editor, charWidthPx)

        editor.addEventListener("keydown", { event -> handleKey(event as KeyboardEvent, viewModel) })
        editor.addEventListener("mousedown", { event ->
            handleMouseDown(editor, event as MouseEvent, viewModel, style, charWidthPx, wrapWidthFlow.value, drag)
        })
        window.addEventListener("mousemove", { event ->
            handleMouseMove(editor, event as MouseEvent, viewModel, style, charWidthPx, wrapWidthFlow.value, drag)
        })
        window.addEventListener("mouseup", { _: Event ->
            drag.isDragging = false
            stopAutoScroll(drag)
        })
        window.addEventListener("resize", { _: Event ->
            wrapWidthFlow.value = wrapWidthInChars(editor, charWidthPx)
        })

        editor.focus()

        scope.launch {
            combine(viewModel.stateFlow, wrapWidthFlow) { state, width -> state to width }
                .collect { (state, width) ->
                    val backing = state.backingState
                    if (backing == null) {
                        paintLoading(editor)
                        paintHeader(header, null, viewModel) { editor.focus() }
                    } else {
                        paint(editor, backing, viewModel, style, charWidthPx, width)
                        paintHeader(header, backing, viewModel) { editor.focus() }
                    }
                }
        }
    }

    private fun installRootStyles(root: HTMLElement) {
        document.documentElement?.let {
            (it as HTMLElement).style.backgroundColor = "var(--t-terminal-bg, #1e1e1e)"
        }
        document.body?.let {
            val bodyStyle = it.style
            bodyStyle.margin = "0"
            bodyStyle.padding = "0"
            bodyStyle.backgroundColor = "var(--t-terminal-bg, #1e1e1e)"
        }
        root.style.margin = "0"
        root.style.height = "100vh"
        root.style.display = "flex"
        root.style.flexDirection = "column"
        root.style.backgroundColor = "var(--t-terminal-bg, #1e1e1e)"
    }

    private fun buildHeaderElement(): HTMLElement {
        val header = document.createElement("div") as HTMLElement
        header.className = "notegrow-header"
        header.style.apply {
            flex = "0 0 auto"
            padding = "${style.headerPaddingPx}px ${style.editorPaddingPx}px"
            fontFamily = style.fontFamily
            fontSize = "${style.fontSize}px"
            lineHeight = "${style.lineHeightPx}px"
            backgroundColor = "var(--t-surface-raised, #252525)"
            color = "var(--t-terminal-fg, #e6e6e6)"
            setProperty("border-bottom", "1px solid #333333")
            setProperty("user-select", "none")
            whiteSpace = "nowrap"
            setProperty("overflow", "hidden")
            setProperty("text-overflow", "ellipsis")
        }
        return header
    }

    private fun buildEditorElement(): HTMLElement {
        val editor = document.createElement("div") as HTMLElement
        editor.setAttribute("tabindex", "0")
        editor.className = "notegrow-editor"
        editor.style.apply {
            flex = "1 1 auto"
            padding = "${style.editorPaddingPx}px"
            fontFamily = style.fontFamily
            fontSize = "${style.fontSize}px"
            lineHeight = "${style.lineHeightPx}px"
            whiteSpace = "pre"
            setProperty("overflow-x", "hidden")
            setProperty("overflow-y", "auto")
            outline = "none"
            backgroundColor = "var(--t-terminal-bg, #1e1e1e)"
            color = "var(--t-terminal-fg, #e6e6e6)"
            setProperty("caret-color", "transparent")
            setProperty("user-select", "none")
        }
        return editor
    }
}
