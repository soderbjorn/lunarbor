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
    private var editorElement: HTMLElement? = null

    /**
     * Pending `setTimeout` handle for the restructuring banner's show-debounce.
     * Non-null only between the moment `isRestructuring` flipped true and
     * either the timer firing (banner becomes visible) or the flag flipping
     * back to false (timer cancelled, banner stays hidden). Used to keep the
     * UI calm: restructures that complete inside ~150 ms never surface.
     */
    private var bannerShowTimeoutHandle: Int? = null

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

        // The zoom breadcrumb that used to live here moved into the
        // toolkit's pane chrome title (RTL-clipped) — the editor now
        // mounts directly into the pane content so bullets start at the
        // very top of the pane.
        val editor = buildEditorElement()
        root.appendChild(editor)
        editorElement = editor

        val restructureBanner = buildRestructureBanner()
        root.appendChild(restructureBanner)

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
                    } else {
                        paint(editor, backing, viewModel, style, charWidthPx, width)
                    }
                    updateRestructureBanner(restructureBanner, backing?.isRestructuring == true)
                }
        }
    }

    /**
     * Drives the visibility of the "Restructuring…" indicator with a small
     * show-debounce. The banner only appears once `isRestructuring` has been
     * true for ~150 ms continuously, so the common case (small notes whose
     * restructure finishes inside one frame) produces no UI flicker. Hiding
     * is immediate and cancels any pending show-timer.
     */
    private fun updateRestructureBanner(banner: HTMLElement, isRestructuring: Boolean) {
        if (isRestructuring) {
            // Already visible — nothing to schedule.
            if (banner.style.display != "none") return
            // Already armed — let the existing timer fire.
            if (bannerShowTimeoutHandle != null) return
            bannerShowTimeoutHandle = window.setTimeout({
                banner.style.display = "flex"
                bannerShowTimeoutHandle = null
            }, 150)
        } else {
            bannerShowTimeoutHandle?.let { window.clearTimeout(it) }
            bannerShowTimeoutHandle = null
            banner.style.display = "none"
        }
    }

    /**
     * Builds the small floating pill rendered in the bottom-right corner
     * while the autosave loop is mid-restructure. Pinned to the viewport
     * via `position: fixed` so it stays put regardless of editor scroll
     * and so it works inside the toolkit pane chrome without needing
     * `position: relative` on the host element.
     */
    private fun buildRestructureBanner(): HTMLElement {
        val banner = document.createElement("div") as HTMLElement
        banner.className = "notegrow-restructuring"
        val spinner = document.createElement("div") as HTMLElement
        spinner.className = "notegrow-restructuring-spinner"
        banner.appendChild(spinner)
        val label = document.createElement("span") as HTMLElement
        label.textContent = "Restructuring…"
        banner.appendChild(label)
        return banner
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
        // Fill the host container, NOT the viewport. The editor is mounted
        // inside a toolkit pane whose own height is less than 100vh; using
        // `100vh` here pushed the header above the visible area, hiding the
        // zoom breadcrumb.
        root.style.height = "100%"
        root.style.minHeight = "0"
        root.style.display = "flex"
        root.style.flexDirection = "column"
        root.style.backgroundColor = "var(--t-terminal-bg, #1e1e1e)"
    }

    private fun buildEditorElement(): HTMLElement {
        val editor = document.createElement("div") as HTMLElement
        editor.setAttribute("tabindex", "0")
        editor.className = "notegrow-editor"
        editor.style.apply {
            flex = "1 1 auto"
            paddingTop = "${style.editorPaddingTopPx}px"
            paddingRight = "${style.editorPaddingRightPx}px"
            paddingBottom = "${style.editorPaddingBottomPx}px"
            paddingLeft = "${style.editorPaddingLeftPx}px"
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
