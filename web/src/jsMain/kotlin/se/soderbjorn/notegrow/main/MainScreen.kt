/*
 * MainScreen.kt (jsMain)
 * ----------------------
 * DOM view for the web platform. The editor is a single
 * `contenteditable` host. The browser owns rendering, caret, selection,
 * wrap, IME, accessibility, and clipboard affordances; this file:
 *
 *   - Mounts one `<div>` per visible row (see `OutlinePaintLoop.paint`).
 *   - Listens to `beforeinput`, `keydown`, `copy`, `cut`, `paste`, and
 *     `mouseup` events, translates DOM ranges into model `(row, col)`
 *     pairs, and routes the edit through `MainViewModel` intents.
 *   - On every state emission, reconciles the DOM (full re-render — the
 *     row count is small and the diff overhead is dwarfed by paint cost
 *     for non-trivial documents) and restores the caret/selection.
 *
 * Cursor pixels and wrap-in-chars math no longer live anywhere in the
 * web layer — `getSelection()` and `Range` replace them.
 */

package se.soderbjorn.notegrow.main

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

/**
 * The note editor's web view.
 *
 * ### Callers
 * - Instantiated in `Main.kt` (web entry point) after the DI graph is
 *   built; one instance per pane managed by `AppShell`.
 * - `render` is the only public entry point — called once to mount the
 *   UI into a host element.
 *
 * @param viewModel Platform VM that exposes state and accepts intents.
 * @param scope Coroutine scope owning the paint-loop collector.
 */
class MainScreen(
    private val viewModel: MainViewModel,
    private val scope: CoroutineScope,
) {
    private val style = EditorStyle()

    private var editorElement: HTMLElement? = null

    /**
     * Headline element rendered above the editor that shows the leaf title
     * of the current zoom target — i.e. the deepest segment of the
     * breadcrumb path the pane chrome displays. Falls back to "Root" when
     * the document is not zoomed. Updated on every state emission.
     */
    private var titleElement: HTMLElement? = null

    /**
     * Pending `setTimeout` handle for the restructuring banner's show-debounce.
     * Non-null only between the moment `isRestructuring` flipped true and
     * either the timer firing (banner becomes visible) or the flag flipping
     * back to false (timer cancelled, banner stays hidden). Used to keep the
     * UI calm: restructures that complete inside ~150 ms never surface.
     */
    private var bannerShowTimeoutHandle: Int? = null

    /**
     * Last cursor `(row, col)` applied to the DOM by [reconcile]. Used to
     * decide whether to call `scrollIntoView` on the cursor row after a
     * repaint: only when the caret actually moved should the viewport
     * follow it. Repaints driven by non-caret state changes (collapse /
     * expand a chevron, autosave round-trip, ref-load) leave this
     * unchanged and therefore do not yank the user away from wherever
     * they had scrolled. `null` means no caret has been applied yet —
     * the first reconcile after mount scrolls the caret into view.
     */
    private var lastAppliedCursor: Pair<Int, Int>? = null

    /**
     * Mounts the editor UI into [root]. Clears any prior content, builds
     * the contenteditable host, wires input listeners, and starts the
     * state collector. Safe to call once per pane lifetime.
     */
    fun render(root: HTMLElement) {
        root.innerHTML = ""
        ensureStyles()
        installRootStyles(root)

        val title = buildTitleElement()
        root.appendChild(title)
        titleElement = title

        val editor = buildEditorElement()
        root.appendChild(editor)
        editorElement = editor

        val restructureBanner = buildRestructureBanner()
        root.appendChild(restructureBanner)

        wireInputListeners(editor)

        editor.focus()

        scope.launch {
            viewModel.stateFlow.collect { state ->
                val backing = state.backingState
                if (backing == null) {
                    paintLoading(editor)
                } else {
                    reconcile(editor, backing)
                }
                updateTitle(title, backing)
                updateRestructureBanner(restructureBanner, backing?.isRestructuring == true)
            }
        }
    }

    // ----------------------------------------------------------------- input

    private fun wireInputListeners(editor: HTMLElement) {
        editor.addEventListener("beforeinput", { event ->
            handleBeforeInput(editor, event.unsafeCast<dynamic>())
        })
        editor.addEventListener("keydown", { event ->
            handleKey(editor, event as KeyboardEvent)
        })
        editor.addEventListener("copy", { event ->
            handleCopy(editor, event.unsafeCast<dynamic>())
        })
        editor.addEventListener("cut", { event ->
            handleCut(editor, event.unsafeCast<dynamic>())
        })
        editor.addEventListener("paste", { event ->
            handlePaste(editor, event.unsafeCast<dynamic>())
        })
        // Sync model selection from DOM on mouse interactions so any
        // selection-aware intent (cut, indent) sees the user's intent.
        editor.addEventListener("mouseup", { _: Event ->
            syncSelectionFromDom(editor)
        })
        editor.addEventListener("keyup", { event ->
            // Only sync after navigation keys — typing keys go through
            // beforeinput which already syncs as part of the edit path.
            val ke = event as KeyboardEvent
            if (ke.key.startsWith("Arrow") || ke.key == "Home" || ke.key == "End" ||
                ke.key == "PageUp" || ke.key == "PageDown"
            ) {
                syncSelectionFromDom(editor)
            }
        })
    }

    /**
     * Routes browser `beforeinput` events to model intents and prevents
     * the default contenteditable mutation. We always own the document
     * model — the DOM is rebuilt from state on the next emission.
     */
    private fun handleBeforeInput(editor: HTMLElement, event: dynamic) {
        // preventDefault first so a typo in our routing never lets the
        // browser silently mutate our DOM behind the model's back.
        event.preventDefault()
        if (!syncSelectionFromDom(editor)) return
        when (event.inputType.unsafeCast<String>()) {
            "insertText", "insertReplacementText", "insertCompositionText" -> {
                val data = event.data?.unsafeCast<String?>()
                if (!data.isNullOrEmpty()) viewModel.insertText(data)
            }
            "insertParagraph", "insertLineBreak" -> viewModel.insertNewline()
            "deleteContentBackward", "deleteWordBackward",
            "deleteSoftLineBackward", "deleteHardLineBackward" -> viewModel.backspace()
            "deleteContentForward", "deleteWordForward",
            "deleteSoftLineForward", "deleteHardLineForward" -> {
                if (viewModel.getSelectedText() != null) {
                    viewModel.deleteSelectionIfAny()
                } else {
                    viewModel.moveRight(extend = true)
                    viewModel.backspace()
                }
            }
            "deleteByCut" -> {
                viewModel.onCutRequested()?.let { writeClipboard(it) }
            }
            "insertFromPaste", "insertFromPasteAsQuotation",
            "insertFromDrop", "insertTranspose" -> {
                val data = event.data?.unsafeCast<String?>()
                if (!data.isNullOrEmpty()) viewModel.insertText(data)
            }
            "historyUndo", "historyRedo" -> {
                // Native contenteditable history is unreliable on a model
                // we rebuild from state; ignore for now. A future custom
                // undo stack plugs in here.
            }
            else -> {
                // Unknown input type — fall back to data insertion if any.
                val data = event.data?.unsafeCast<String?>()
                if (!data.isNullOrEmpty()) viewModel.insertText(data)
            }
        }
    }

    /**
     * Handles keyboard shortcuts that don't fit the `beforeinput` model:
     * Tab (indent/outdent), Escape (zoom out), and Cmd/Ctrl+A (select
     * all routed through the model so subsequent edits see the right
     * range).
     */
    private fun handleKey(editor: HTMLElement, event: KeyboardEvent) {
        val cmd = event.ctrlKey || event.metaKey
        if (cmd && event.key.lowercase() == "a") {
            // Let the model own selection bounds — when zoomed, selectAll
            // should clamp to the visible subtree, not the whole document.
            event.preventDefault()
            syncSelectionFromDom(editor)
            viewModel.selectAll()
            return
        }
        if (event.key == "Tab") {
            event.preventDefault()
            syncSelectionFromDom(editor)
            if (event.shiftKey) {
                viewModel.outdentLine()
            } else if (viewModel.isBulletLine()) {
                viewModel.indentLine()
            } else {
                viewModel.insertText("  ")
            }
            return
        }
        if (event.key == "Escape") {
            val backing = viewModel.stateFlow.value.backingState
            if (backing != null && viewModel.zoomInfo(backing) != null) {
                event.preventDefault()
                viewModel.zoomOut()
            }
            return
        }
    }

    /** Cmd-C: write the model's selected text to the clipboard. */
    private fun handleCopy(editor: HTMLElement, event: dynamic) {
        if (!syncSelectionFromDom(editor)) return
        val selected = viewModel.getSelectedText() ?: return
        event.clipboardData?.setData("text/plain", selected)
        event.preventDefault()
    }

    /** Cmd-X: copy + delete the selection through the model. */
    private fun handleCut(editor: HTMLElement, event: dynamic) {
        if (!syncSelectionFromDom(editor)) return
        val text = viewModel.onCutRequested() ?: return
        event.clipboardData?.setData("text/plain", text)
        event.preventDefault()
    }

    /** Cmd-V: route paste data through `viewModel.insertText`. */
    private fun handlePaste(editor: HTMLElement, event: dynamic) {
        event.preventDefault()
        if (!syncSelectionFromDom(editor)) return
        val text = event.clipboardData?.getData("text/plain")?.unsafeCast<String?>()
        if (!text.isNullOrEmpty()) viewModel.insertText(text)
    }

    // ------------------------------------------------------- selection sync

    /**
     * Reads the current DOM selection inside [editor] and pushes the
     * mapped `(anchorRow, anchorCol, cursorRow, cursorCol)` into the
     * model. Returns `false` if the selection isn't inside any row of
     * this editor (e.g. mouse went into the chrome around the pane).
     */
    private fun syncSelectionFromDom(editor: HTMLElement): Boolean {
        val sel = window.asDynamic().getSelection() ?: return false
        // `sel` is dynamic, so .rangeCount/.anchorOffset/.focusOffset are
        // raw JS Numbers (Doubles in Kotlin/JS). Calling `.toInt()` on a
        // primitive number throws "toInt is not a function" at runtime;
        // coerce via Number.toInt() (the boxed extension) instead.
        if ((sel.rangeCount as Number).toInt() == 0) return false
        val anchorNode = sel.anchorNode ?: return false
        val focusNode = sel.focusNode ?: return false
        if (!editor.contains(anchorNode) || !editor.contains(focusNode)) return false
        val anchor = domNodeToRowCol(anchorNode, (sel.anchorOffset as Number).toInt()) ?: return false
        val focus = domNodeToRowCol(focusNode, (sel.focusOffset as Number).toInt()) ?: return false
        viewModel.setSelection(anchor.first, anchor.second, focus.first, focus.second)
        return true
    }

    /**
     * Walks up from [node] to its enclosing `data-row` div and translates
     * the in-row DOM offset into a model column. Snaps any caret position
     * inside the non-editable bullet prefix to the start of the editable
     * text region (`prefix-len`).
     */
    private fun domNodeToRowCol(node: Node, offset: Int): Pair<Int, Int>? {
        val rowDiv = ancestorRowDiv(node) ?: return null
        val row = rowDiv.getAttribute("data-row")?.toIntOrNull() ?: return null
        val prefixLen = rowDiv.getAttribute("data-prefix-len")?.toIntOrNull() ?: 0
        // The editable region is the `.notegrow-text` span. Caret offsets
        // anywhere outside it (e.g. on the bullet prefix or the row div
        // itself) snap to the start of the text region.
        val textSpan = rowDiv.querySelector(".notegrow-text") as? HTMLElement
        if (textSpan == null) return row to prefixLen
        if (node === textSpan) {
            // offset counts child nodes consumed; with our single-text-node
            // structure that maps to either 0 (before text) or 1 (after).
            val childText = textSpan.firstChild
            val resolved = if (childText != null && offset >= 1) {
                (childText.nodeValue?.length ?: 0)
            } else 0
            return row to (prefixLen + resolved)
        }
        if (textSpan.contains(node)) {
            // node is the single text node child of the text span; offset
            // is a character offset directly.
            return row to (prefixLen + offset)
        }
        // Caret landed on the bullet prefix or another non-editable child;
        // snap to the editable boundary.
        return row to prefixLen
    }

    private fun ancestorRowDiv(node: Node): HTMLElement? {
        var n: Node? = node
        while (n != null) {
            if (n is Element && n.hasAttribute("data-row")) return n as HTMLElement
            n = n.parentNode
        }
        return null
    }

    // -------------------------------------------------------- reconcile/paint

    /**
     * Repaints [editor] for [state], then restores caret and selection
     * from `(anchorRow, anchorCol, cursorRow, cursorCol)` so the user
     * sees no flicker during an edit.
     *
     * `scrollTop` is captured before the rebuild and restored afterward
     * so non-user-initiated repaints (autosave, ref-load) don't yank the
     * viewport back to the start. The caret is `scrollIntoView`'d only
     * when it actually moved since the previous reconcile — otherwise a
     * collapse/expand toggle while the user has scrolled away from the
     * caret would jump the viewport back to the caret row.
     */
    private fun reconcile(editor: HTMLElement, state: DocumentViewBackingViewModel.State) {
        val savedScrollTop = editor.scrollTop
        paint(editor, state, viewModel, style)
        editor.scrollTop = savedScrollTop

        if (!state.isLoaded) return
        // Map model selection back to DOM. Selection-aware: if anchor is
        // null we collapse to the cursor.
        val anchorRow = state.anchorRow ?: state.cursorRow
        val anchorCol = state.anchorCol ?: state.cursorCol
        val cursor = state.cursorRow to state.cursorCol
        val cursorMoved = lastAppliedCursor != cursor
        applyDomSelection(editor, anchorRow, anchorCol, state.cursorRow, state.cursorCol, cursorMoved)
        lastAppliedCursor = cursor
    }

    /**
     * Sets the browser's selection to the requested model range,
     * accounting for each row's `data-prefix-len` so columns inside the
     * non-editable bullet zone clamp to the editable boundary.
     *
     * @param scrollCursorIntoView When true, the cursor row is pulled into
     *   the viewport via `scrollIntoView({block:'nearest'})`. Pass false
     *   for repaints where the caret position is unchanged (e.g. the user
     *   toggled a chevron after scrolling away) so the viewport is not
     *   yanked back to the caret.
     */
    private fun applyDomSelection(
        editor: HTMLElement,
        anchorRow: Int,
        anchorCol: Int,
        cursorRow: Int,
        cursorCol: Int,
        scrollCursorIntoView: Boolean,
    ) {
        val anchor = locateDomPosition(editor, anchorRow, anchorCol) ?: return
        val focus = locateDomPosition(editor, cursorRow, cursorCol) ?: return
        val sel = window.asDynamic().getSelection() ?: return
        try {
            sel.setBaseAndExtent(anchor.first, anchor.second, focus.first, focus.second)
        } catch (e: Throwable) {
            // Browsers throw if the requested anchor/focus aren't selectable;
            // recover by collapsing to the focus.
            val range = document.createRange()
            range.setStart(focus.first, focus.second)
            range.collapse(true)
            sel.removeAllRanges()
            sel.addRange(range)
        }
        if (scrollCursorIntoView) {
            // `block: 'nearest'` is a no-op when the row is already visible,
            // so this only scrolls when the caret moved out of view.
            val cursorRowDiv = editor.querySelector("[data-row='$cursorRow']") as? HTMLElement
            cursorRowDiv?.asDynamic()?.scrollIntoView(js("{block: 'nearest'}"))
        }
    }

    /**
     * Resolves a model `(row, col)` to a DOM (node, offset) pair suitable
     * for `Selection.setBaseAndExtent`. Returns null if the row isn't
     * currently rendered (e.g. it sits inside a folded subtree).
     *
     * Within-row mapping prefers the text span's text node when present;
     * for empty rows it falls back to the text span itself with offset 0.
     */
    private fun locateDomPosition(
        editor: HTMLElement,
        row: Int,
        col: Int,
    ): Pair<Node, Int>? {
        val rowDiv = editor.querySelector("[data-row='$row']") as? HTMLElement ?: return null
        val prefixLen = rowDiv.getAttribute("data-prefix-len")?.toIntOrNull() ?: 0
        val textSpan = rowDiv.querySelector(".notegrow-text") as? HTMLElement ?: return null
        val inTextOffset = (col - prefixLen).coerceAtLeast(0)
        val textNode = textSpan.firstChild
        return if (textNode != null) {
            val nodeLen = textNode.nodeValue?.length ?: 0
            textNode to inTextOffset.coerceAtMost(nodeLen)
        } else {
            textSpan to 0
        }
    }

    // ----------------------------------------------------- chrome / banners

    private fun updateRestructureBanner(banner: HTMLElement, isRestructuring: Boolean) {
        if (isRestructuring) {
            if (banner.style.display != "none") return
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
     * Builds the static headline element that sits above the editor and
     * shows the current zoom target's leaf title. Styled as a pronounced
     * heading so the user always knows which bullet they're focused on
     * without scanning the breadcrumb in the pane chrome.
     */
    private fun buildTitleElement(): HTMLElement {
        val title = document.createElement("div") as HTMLElement
        title.className = "notegrow-title"
        title.style.apply {
            flex = "0 0 auto"
            paddingTop = "6px"
            paddingBottom = "2px"
            paddingLeft = "${style.editorPaddingLeftPx}px"
            paddingRight = "${style.editorPaddingRightPx}px"
            fontFamily = style.fontFamily
            setProperty("font-size", "32px")
            setProperty("font-weight", "600")
            setProperty("line-height", "1.2")
            setProperty("white-space", "nowrap")
            setProperty("overflow", "hidden")
            setProperty("text-overflow", "ellipsis")
            color = "var(--t-terminal-fg, #e6e6e6)"
        }
        return title
    }

    /**
     * Refreshes the headline text from [backing]. Shows the leaf segment of
     * the zoom path when zoomed (matching the trailing breadcrumb segment
     * in the pane chrome), or "Root" when at document root. Renders empty
     * while the document hasn't loaded yet so the headline doesn't flash
     * incorrect copy during boot.
     */
    private fun updateTitle(title: HTMLElement, backing: DocumentViewBackingViewModel.State?) {
        val text = when {
            backing == null || !backing.isLoaded -> ""
            else -> {
                val zoom = viewModel.zoomInfo(backing)
                if (zoom == null) "Root" else zoom.titleText.ifBlank { "(untitled)" }
            }
        }
        if (title.textContent != text) title.textContent = text
    }

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
        root.style.height = "100%"
        root.style.minHeight = "0"
        root.style.display = "flex"
        root.style.flexDirection = "column"
        root.style.backgroundColor = "var(--t-terminal-bg, #1e1e1e)"
    }

    private fun buildEditorElement(): HTMLElement {
        val editor = document.createElement("div") as HTMLElement
        editor.className = "notegrow-editor"
        editor.setAttribute("contenteditable", "true")
        editor.setAttribute("spellcheck", "false")
        editor.setAttribute("autocorrect", "off")
        editor.setAttribute("autocapitalize", "off")
        editor.style.apply {
            flex = "1 1 auto"
            paddingTop = "${style.editorPaddingTopPx}px"
            paddingRight = "${style.editorPaddingRightPx}px"
            paddingBottom = "${style.editorPaddingBottomPx}px"
            paddingLeft = "${style.editorPaddingLeftPx}px"
            fontFamily = style.fontFamily
            fontSize = "${style.fontSize}px"
            setProperty("line-height", "${style.lineHeightPx}px")
            // Browser-native wrap: long lines break on word boundaries,
            // explicit newlines split rows (each row is its own div anyway).
            setProperty("white-space", "pre-wrap")
            setProperty("word-break", "break-word")
            setProperty("overflow-x", "hidden")
            setProperty("overflow-y", "auto")
            outline = "none"
            backgroundColor = "var(--t-terminal-bg, #1e1e1e)"
            color = "var(--t-terminal-fg, #e6e6e6)"
        }
        return editor
    }

    /**
     * Writes [text] to the browser clipboard. A no-op when the Clipboard
     * API is unavailable (older browsers, insecure contexts).
     */
    private fun writeClipboard(text: String) {
        val clipboard = window.asDynamic().navigator?.clipboard
        if (clipboard != null && clipboard != undefined) {
            clipboard.writeText(text)
        }
    }
}
