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
     * Root container the screen mounts into (the per-pane slot the shell
     * passes to [render]). Captured so [maybePlayNavFade] can fade the
     * entire pane content (headline, editor, footer) as a single block on
     * navigation transitions, rather than each child fading independently.
     */
    private var rootElement: HTMLElement? = null

    /**
     * Reference to the floating restructure-status banner so subsequent
     * idempotent [render] calls can move it into the new pane container
     * along with the title/scrollWrapper trio. Tracked here (rather than
     * built fresh each render) because the pane chrome rerenders on
     * every navigation transition — recreating it would wipe live state.
     */
    private var restructureBannerElement: HTMLElement? = null

    /**
     * Sibling div positioned immediately after [editorElement] inside the
     * shared scroll container. Hosts the filesystem-tree footer (see
     * `VaultFooter.paintVaultFooter`). Distinct from the editor so the
     * footer's DOM is never wiped by `OutlinePaintLoop.paint`'s
     * `editor.innerHTML = ""` and never participates in DOM-to-model
     * selection mapping.
     */
    private var vaultFooterElement: HTMLElement? = null

    /**
     * Wrapper element that owns the page's vertical scroll. Holds the
     * editor and the vault footer as siblings so they scroll together.
     * The editor itself no longer scrolls — its content height grows as
     * needed and the wrapper's `overflow-y: auto` carries the scrollbar.
     */
    private var scrollWrapperElement: HTMLElement? = null

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
     * Last `(activeFileRel, zoomedLineId)` pair observed by the state
     * collector. When the next emission's pair differs, the editor and
     * title fade in via the one-shot `notegrow-nav-fade` CSS animation
     * declared in `AppShell.ensureNotegrowChromeStyles`. Initialised on
     * the first emission so the very first paint after mount does not
     * animate (mounting already implies a fresh render).
     */
    private var lastNavSignature: Pair<String?, LineId?>? = null

    /**
     * Snapshot of the outgoing pane content captured the moment the
     * navigation signature changed but before the new file's content has
     * loaded. Held across one or more `isLoaded=false` "Loading…" frames
     * and dissolved out once the new file's content lands underneath.
     *
     * For same-file zoom transitions the new content is already loaded
     * on the same emission, so this stays `null` end-to-end and the
     * crossfade plays immediately as before. The deferral matters only
     * for cross-file navigation (footer click → `navigateToVaultFile`),
     * where `switchTo` flips `isLoaded` to false before the new content
     * arrives.
     */
    private var pendingCrossfadeOverlay: HTMLElement? = null

    /**
     * Mounts the editor UI into [root]. Clears any prior content, builds
     * the contenteditable host, wires input listeners, and starts the
     * state collector. Safe to call once per pane lifetime.
     */
    fun render(root: HTMLElement) {
        ensureStyles()
        installRootStyles(root)
        rootElement = root

        val existingTitle = titleElement
        val existingScroll = scrollWrapperElement
        val existingEditor = editorElement
        val existingBanner = restructureBannerElement
        if (existingTitle != null && existingScroll != null &&
            existingEditor != null && existingBanner != null
        ) {
            // Idempotent re-mount: AppShell rebuilds the pane chrome on
            // every navigation transition (back/forward stack changes
            // trigger `rerenderActivePane`), passing a fresh `container`
            // div each time. Wiping our state and rebuilding from
            // scratch would (1) wipe the title/editor content the live
            // collector just painted — breaking the navigation crossfade
            // snapshot — and (2) launch a duplicate collector that
            // writes to detached elements. Instead, move the existing
            // elements into the new container and bail out before the
            // collector launch below.
            root.innerHTML = ""
            root.appendChild(existingTitle)
            root.appendChild(existingScroll)
            root.appendChild(existingBanner)
            return
        }
        // First-time mount: wipe (defensive — caller passes an empty
        // container) and build the element tree from scratch.
        root.innerHTML = ""

        val title = buildTitleElement()
        root.appendChild(title)
        titleElement = title

        val scrollWrapper = buildScrollWrapper()
        root.appendChild(scrollWrapper)
        scrollWrapperElement = scrollWrapper

        val editor = buildEditorElement()
        scrollWrapper.appendChild(editor)
        editorElement = editor

        val vaultFooter = buildVaultFooterElement()
        scrollWrapper.appendChild(vaultFooter)
        vaultFooterElement = vaultFooter

        val restructureBanner = buildRestructureBanner()
        root.appendChild(restructureBanner)
        restructureBannerElement = restructureBanner

        wireInputListeners(editor)

        editor.focus()

        scope.launch {
            viewModel.stateFlow.collect { state ->
                val backing = state.backingState
                // Detect navigation transitions BEFORE running reconcile so
                // we can snapshot the outgoing content as an overlay; the
                // overlay then crossfades over the freshly-rendered new
                // content underneath, avoiding a blank middle frame.
                //
                // Cross-file navigation (footer click) emits an interim
                // `isLoaded=false` "Loading…" state before the new file's
                // content lands. We snapshot on that first emission (so
                // we capture the *outgoing* file's content), then hold
                // the overlay until a subsequent `isLoaded=true` emission
                // arrives — only then is there real new content beneath
                // the overlay to dissolve into.
                val newOverlay = if (pendingCrossfadeOverlay == null && isNavigationTransition(backing)) {
                    snapshotForCrossfade()
                } else null
                if (newOverlay != null) pendingCrossfadeOverlay = newOverlay
                // Skip the paint while a crossfade is pending and the new
                // file is still loading: reconcile would wipe the editor
                // and stamp "Loading…" underneath the overlay, which —
                // despite the overlay being opaque — was perceptible as a
                // brief flash on the file-nav path. Keeping the live DOM
                // unchanged means the overlay sits over an identical copy
                // of itself; the next emission (isLoaded=true) repaints
                // to the new content and the fade dissolves into it.
                val skipPaint = pendingCrossfadeOverlay != null &&
                    backing != null && !backing.isLoaded
                if (!skipPaint) {
                    if (backing == null) {
                        paintLoading(editor)
                    } else {
                        reconcile(editor, backing)
                        paintVaultFooter(vaultFooter, backing, viewModel, style)
                    }
                    updateTitle(title, backing)
                    updateRestructureBanner(restructureBanner, backing?.isRestructuring == true)
                }
                val pending = pendingCrossfadeOverlay
                if (pending != null && backing != null && backing.isLoaded) {
                    playCrossfade(pending)
                    pendingCrossfadeOverlay = null
                }
                rememberNavSignature(backing)
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
     * Tab (indent/outdent), Escape (zoom out), Cmd/Ctrl+A (select all
     * routed through the model so subsequent edits see the right range),
     * and the Option-Cmd navigation triplet —
     * Left = back through zoom history, Right = forward,
     * Up = zoom one level out (parent ancestor).
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
        if (event.altKey && (event.metaKey || event.ctrlKey)) {
            when (event.key) {
                "ArrowLeft" -> {
                    event.preventDefault()
                    viewModel.zoomBack()
                    return
                }
                "ArrowRight" -> {
                    event.preventDefault()
                    viewModel.zoomForward()
                    return
                }
                "ArrowUp" -> {
                    event.preventDefault()
                    onZoomUpRequested()
                    return
                }
            }
        }
        if (event.key == "ArrowLeft" && !event.altKey && !event.metaKey && !event.ctrlKey) {
            // Plain ArrowLeft at the start of a row's editable text: the
            // browser would either dump the caret into the contenteditable=false
            // bullet prefix (which the model snaps back to start-of-text, so
            // visually nothing happens) or skip across the prefix to a
            // position that round-trips to end-of-line. Route through the
            // view model instead so the caret cleanly wraps to the end of
            // the previous visible row.
            if (syncSelectionFromDom(editor)) {
                val backing = viewModel.stateFlow.value.backingState
                if (backing != null) {
                    val line = backing.lines.getOrNull(backing.cursorRow)
                    if (line != null && backing.cursorCol <= DocumentLayout.textStartCol(line)) {
                        event.preventDefault()
                        viewModel.moveLeft(extend = event.shiftKey)
                        return
                    }
                }
            }
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

    /**
     * Zoom one level out — to the immediate parent ancestor of the
     * current zoom target. Falls back to clearing the zoom (back to root)
     * when the zoom is already at the top level. Mirrors the behavior
     * wired to the toolbar `up` icon in `AppShell.zoomPaneUpOneLevel`,
     * but lives here because the keyboard handler doesn't have a
     * `paneId` — it operates on whichever pane currently owns the editor.
     */
    private fun onZoomUpRequested() {
        val backing = viewModel.stateFlow.value.backingState ?: return
        if (viewModel.zoomInfo(backing) == null) return
        val ancestors = viewModel.bulletAncestors(backing)
        viewModel.zoomTo(ancestors.lastOrNull()?.lineId)
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
        // Caret landed outside the editable text region (e.g. on the
        // contenteditable=false bullet prefix or chevron — what happens when
        // ArrowLeft is pressed at the very start of the editable text on a
        // bullet line, since the browser parks the caret in the previous
        // sibling rather than wrapping to the previous row). Snap to the
        // start of the editable text on this row; without this the
        // displayCol fallthrough below treats "node not found in any run"
        // as "after the last run" and dumps the cursor at end-of-line.
        if (node !== textSpan && !textSpan.contains(node)) return row to prefixLen
        val rowMap = rowColumnMapOf(rowDiv)
        val displayCol = displayColForDomPosition(textSpan, node, offset)
        val editableCol = if (rowMap != null) {
            val clamped = displayCol.coerceIn(0, rowMap.domToModel.size - 1)
            rowMap.domToModel[clamped]
        } else displayCol
        return row to (prefixLen + editableCol)
    }

    /**
     * Walks the children of [textSpan] (each a `.notegrow-text-run`
     * containing a single text node) and computes the display column
     * (markers-stripped offset) for the requested DOM position.
     */
    private fun displayColForDomPosition(textSpan: HTMLElement, node: Node, offset: Int): Int {
        if (node === textSpan) {
            // `offset` counts child elements consumed within the wrapper.
            var col = 0
            val children = textSpan.children
            val cap = offset.coerceAtMost(children.length)
            for (i in 0 until cap) {
                val child = children.item(i) ?: continue
                col += child.textContent?.length ?: 0
            }
            return col
        }
        var col = 0
        val children = textSpan.children
        for (i in 0 until children.length) {
            val child = children.item(i) ?: continue
            if (child === node) {
                // Caret on a run span itself; treat offset as before/after.
                return col + (if (offset >= 1) (child.textContent?.length ?: 0) else 0)
            }
            val childTextNode = child.firstChild
            if (childTextNode != null && (childTextNode === node || child.contains(node))) {
                return col + offset
            }
            col += child.textContent?.length ?: 0
        }
        return col
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
        val scroller = scrollWrapperElement ?: editor
        val savedScrollTop = scroller.scrollTop
        paint(editor, state, viewModel, style)
        scroller.scrollTop = savedScrollTop

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
        val editableCol = (col - prefixLen).coerceAtLeast(0)
        val rowMap = rowColumnMapOf(rowDiv)
        val displayCol = if (rowMap != null) {
            val clamped = editableCol.coerceIn(0, rowMap.modelToDom.size - 1)
            rowMap.modelToDom[clamped]
        } else editableCol

        // Walk run-span children until we find the one that contains displayCol.
        val children = textSpan.children
        var consumed = 0
        for (i in 0 until children.length) {
            val child = children.item(i) as? HTMLElement ?: continue
            val len = child.textContent?.length ?: 0
            if (displayCol <= consumed + len) {
                val textNode = child.firstChild
                return if (textNode != null) {
                    textNode to (displayCol - consumed).coerceAtLeast(0)
                } else {
                    child to 0
                }
            }
            consumed += len
        }
        // Past the end — drop to the last text node, or the wrapper if there are no children.
        val lastChild = textSpan.lastElementChild
        val lastTextNode = lastChild?.firstChild
        return if (lastTextNode != null) {
            lastTextNode to (lastTextNode.nodeValue?.length ?: 0)
        } else if (lastChild != null) {
            lastChild to 0
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
                if (zoom == null) {
                    // Show the active file's display name. Strip the
                    // `.md` extension and the directory path so the
                    // headline is just `Recipes` for `Recipes/Recipes.md`,
                    // `links` for `links.md`, etc.
                    val fileRel = backing.documentState?.activeFileRel ?: ""
                    fileRel.substringAfterLast('/').removeSuffix(".md").ifBlank { "Untitled" }
                } else zoom.titleText.ifBlank { "(untitled)" }
            }
        }
        if (title.textContent != text) title.textContent = text
    }

    /**
     * Compares the current navigation signature `(activeFileRel,
     * zoomedLineId)` against [lastNavSignature] and, when they differ
     * (and this isn't the very first emission), restarts the
     * `notegrow-nav-fade` animation on the editor and title so the new
     * content fades in. The first emission seeds [lastNavSignature]
     * without playing the animation — mounting already paints the
     * initial view from blank.
     *
     * Restarting a CSS animation requires removing the trigger class,
     * forcing a reflow, then re-adding it (browsers de-duplicate
     * identical class additions otherwise). We read `offsetWidth` to
     * synchronously flush the layout.
     */
    /**
     * `true` when the current emission represents a navigation (file
     * change or zoom change) relative to the last seen state. Returns
     * `false` for the very first emission (initial mount) and for
     * emissions where only cursor / selection / fold state changed.
     *
     * Intentionally tolerates `isLoaded=false`: cross-file navigation
     * goes through an interim "Loading…" state, and we want to detect
     * the transition on that very first emission so we can snapshot the
     * outgoing content before reconcile paints the loading placeholder.
     */
    private fun isNavigationTransition(backing: DocumentViewBackingViewModel.State?): Boolean {
        if (backing == null) return false
        val previous = lastNavSignature ?: return false
        val current: Pair<String?, LineId?> = Pair(
            backing.documentState?.activeFileRel,
            backing.zoomedLineId,
        )
        return previous != current
    }

    /**
     * Records the current `(activeFileRel, zoomedLineId)` pair as the
     * baseline for the next [isNavigationTransition] call. We track the
     * pair on every non-null emission (including `isLoaded=false`)
     * because the file path itself flips on the first interim emission
     * of a cross-file switch — keeping the signature in sync there means
     * the trailing `isLoaded=true` emission for the same file is *not*
     * misclassified as a second navigation.
     */
    private fun rememberNavSignature(backing: DocumentViewBackingViewModel.State?) {
        lastNavSignature = if (backing == null) null else Pair(
            backing.documentState?.activeFileRel,
            backing.zoomedLineId,
        )
    }

    /**
     * Captures the pane's currently-rendered content as an absolutely-
     * positioned overlay clone of [rootElement]'s children. Returns the
     * overlay (already attached to the DOM, layered over the new content
     * the upcoming reconcile is about to paint) so the caller can
     * crossfade it via [playCrossfade]. Returns `null` if the root is
     * not yet mounted.
     *
     * Side effect: forces `position: relative` on the root so the
     * `position: absolute; inset: 0` overlay anchors to the root's box.
     * Idempotent — once set, the inline style stays.
     */
    private fun snapshotForCrossfade(): HTMLElement? {
        val root = rootElement ?: return null
        val rect = root.getBoundingClientRect()
        val overlay = document.createElement("div") as HTMLElement
        overlay.className = "notegrow-nav-overlay"
        // Pin the overlay to the viewport via `position: fixed` using the
        // root's measured rect. This deliberately escapes the local
        // stacking/clipping context the toolkit pane chrome imposes
        // around the live root — an `absolute; inset:0` overlay was
        // being collapsed to a thin sliver despite a fully-computed
        // box. We must use `display: block`; `display: flex` here was
        // also collapsing the box height down to the cloned children's
        // intrinsic size.
        val rootStyle = window.getComputedStyle(root)
        overlay.style.apply {
            setProperty("position", "fixed")
            setProperty("top", "${rect.top}px")
            setProperty("left", "${rect.left}px")
            setProperty("width", "${rect.width}px")
            setProperty("height", "${rect.height}px")
            setProperty("pointer-events", "none")
            setProperty("z-index", "2147483600")
            setProperty("overflow", "hidden")
            setProperty("background", rootStyle.backgroundColor)
            setProperty("color", rootStyle.color)
            setProperty("font-family", rootStyle.fontFamily)
            setProperty("display", "block")
            setProperty("opacity", "1")
            setProperty("box-sizing", "border-box")
        }
        // Clone every direct child of the root so the snapshot mirrors the
        // user's current view (headline, editor, footer, restructure
        // banner). Deep clone preserves text content and inline styles.
        // Children are walked by index because a live HTMLCollection
        // shifts as we append into the overlay.
        val count = root.children.length
        for (i in 0 until count) {
            val child = root.children.item(i) ?: continue
            val clone = (child as Node).cloneNode(true) as HTMLElement
            // The cloned editor still carries `contenteditable=true`;
            // strip it so the snapshot can never steal the caret from
            // the live editor that's about to mount underneath.
            if (clone.getAttribute("contenteditable") == "true") {
                clone.setAttribute("contenteditable", "false")
            }
            clone.querySelectorAll("[contenteditable=\"true\"]").let { list ->
                for (j in 0 until list.length) {
                    (list.item(j) as? HTMLElement)?.setAttribute("contenteditable", "false")
                }
            }
            overlay.appendChild(clone)
        }
        document.body?.appendChild(overlay)
        return overlay
    }

    /**
     * Fades [overlay] from full opacity to 0 over the configured duration
     * and removes it from the DOM when the transition completes. The
     * overlay sits over the new content the collector just rendered, so
     * the dissolve reveals the new view smoothly with no blank-middle
     * flash.
     */
    private fun playCrossfade(overlay: HTMLElement) {
        overlay.style.setProperty(
            "transition",
            "opacity ${NAV_FADE_DURATION_MS}ms ease-out",
        )
        // Schedule the opacity change on the next animation frame so the
        // browser has committed the opacity:1 starting state before the
        // transition is asked to run.
        window.requestAnimationFrame {
            overlay.style.opacity = "0"
        }
        window.setTimeout({
            overlay.parentElement?.removeChild(overlay)
        }, NAV_FADE_DURATION_MS + 60)
    }

    companion object {
        /** Crossfade duration on file / zoom navigation transitions. */
        private const val NAV_FADE_DURATION_MS: Int = 450
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

    /**
     * Builds the scroll wrapper that hosts the editor and the vault-tree
     * footer side by side (vertically). Owns the page's vertical scrollbar
     * so the document and the footer scroll together — the user sees the
     * filesystem index roll up under the document as they scroll down.
     */
    private fun buildScrollWrapper(): HTMLElement {
        val wrapper = document.createElement("div") as HTMLElement
        wrapper.className = "notegrow-scroll"
        wrapper.style.apply {
            flex = "1 1 auto"
            setProperty("min-height", "0")
            setProperty("overflow-x", "hidden")
            setProperty("overflow-y", "auto")
            backgroundColor = "var(--t-terminal-bg, #1e1e1e)"
        }
        return wrapper
    }

    private fun buildEditorElement(): HTMLElement {
        val editor = document.createElement("div") as HTMLElement
        editor.className = "notegrow-editor"
        editor.setAttribute("contenteditable", "true")
        editor.setAttribute("spellcheck", "false")
        editor.setAttribute("autocorrect", "off")
        editor.setAttribute("autocapitalize", "off")
        editor.style.apply {
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
            outline = "none"
            color = "var(--t-terminal-fg, #e6e6e6)"
        }
        return editor
    }

    /**
     * Builds the sibling div that hosts the filesystem-tree footer. The
     * `contenteditable="false"` attribute keeps caret placement out of
     * this subtree even if a drag-selection sweeps into it. Padding mirrors
     * the editor's so footer rows align horizontally with document rows.
     */
    private fun buildVaultFooterElement(): HTMLElement {
        val footer = document.createElement("div") as HTMLElement
        footer.className = "notegrow-vault-footer"
        footer.setAttribute("contenteditable", "false")
        footer.style.apply {
            paddingTop = "${style.editorPaddingTopPx}px"
            paddingRight = "${style.editorPaddingRightPx}px"
            paddingBottom = "${style.editorPaddingBottomPx + 32}px"
            paddingLeft = "${style.editorPaddingLeftPx}px"
            fontFamily = style.fontFamily
            fontSize = "${style.fontSize}px"
            setProperty("line-height", "${style.lineHeightPx}px")
            setProperty("white-space", "pre-wrap")
            setProperty("word-break", "break-word")
            color = "var(--t-terminal-fg, #e6e6e6)"
        }
        return footer
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
