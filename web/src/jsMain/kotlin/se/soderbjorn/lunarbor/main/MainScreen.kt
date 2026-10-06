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

package se.soderbjorn.lunarbor.main

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.Node
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import se.soderbjorn.lunarbor.data.ImagePaths
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.data.InlineMarkdownTokenizer
import se.soderbjorn.lunarbor.data.InlineStyle
import se.soderbjorn.lunarbor.data.LineStyle
import se.soderbjorn.lunarbor.data.LunarborLink
import kotlin.math.sqrt

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
    /**
     * Opens a vault link (or resolved wiki link) in a new window
     * instead of navigating this pane: a Shift- or ⌘-press on it (Ctrl
     * off the Mac) or a right-click / the Mac's Ctrl-click
     * ([OpenGesture]). Receives the link's `href` (a `/…` path);
     * the host adds the pane. `null` falls back to in-pane navigation.
     */
    private val onOpenLinkInNewPane: ((href: String) -> Unit)? = null,
    /**
     * Opens a search result in a new window (the host adds a pane at the
     * result's line). `null` opens nothing.
     */
    private val onOpenSearchHit: ((se.soderbjorn.lunarbor.data.TextHit) -> Unit)? = null,
    /**
     * Told the page's scroll offset once scrolling settles, so the host
     * can persist it with the pane's location. `null` persists nothing.
     */
    private val onScrollSettled: ((Double) -> Unit)? = null,
    /**
     * Opens a location in a new window — the item of a bullet dot that
     * was right-clicked or Shift- / ⌘-pressed ([MainViewModel.locationOfRow],
     * [OpenGesture]). `null` opens nothing.
     */
    private val onOpenLocationInNewPane: ((PaneBackingViewModel.FileHistoryEntry) -> Unit)? = null,
) {
    /** [PaneBackingViewModel.ScrollRestore.seq] of the last scroll restore applied. */
    private var lastScrollRestoreSeq = -1

    /** Pending `setTimeout` handle of the scroll-settled report. */
    private var scrollSettleHandle: Int? = null

    private val style = EditorStyle()

    private var editorElement: HTMLElement? = null

    /**
     * Root container the screen mounts into (the per-pane slot the shell
     * passes to [render]). Captured so [captureOutgoing] can snapshot the
     * entire pane content (headline, editor, folder contents list) as a
     * single block on navigation transitions.
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
     * shared scroll container. Hosts the folder contents list (see
     * `FolderContentsList.paintFolderContents`). Distinct from the editor so the
     * list's DOM is never wiped by `OutlinePaintLoop.paint`'s
     * `editor.innerHTML = ""` and never participates in DOM-to-model
     * selection mapping.
     */
    private var folderContentsElement: HTMLElement? = null

    /**
     * Sibling div positioned next to [editorElement] inside the shared
     * scroll container, shown only when the pane is in image view
     * (`state.isImageView`). Hosts the read-only image viewer (see
     * `ImageViewer.paintImageViewer`). Distinct from the editor so
     * neither host wipes the other's DOM, and so the contenteditable
     * surface stays detached while the user is viewing an image.
     */
    private var imageViewerElement: HTMLElement? = null

    /**
     * Sibling of the scroll wrapper, filling the pane below the title,
     * shown only while the pane shows a drawing (`state.isDrawingView`):
     * the drawing editor (see [DrawingEditor]) renders the Excalidraw UI
     * into it. Outside the scroll wrapper because Excalidraw fills its
     * container and scrolls its own canvas.
     */
    private var drawingHostElement: HTMLElement? = null

    /** The Excalidraw editor living in [drawingHostElement]; built at first mount. */
    private var drawingEditor: DrawingEditor? = null

    /**
     * Web page view ([HtmlViewer]) shown in place of the scroll wrapper
     * while the pane shows an HTML page (`state.isHtmlView`); its host is
     * built like [drawingHostElement]. Built at first mount.
     */
    private var htmlViewer: HtmlViewer? = null

    /**
     * Wrapper element that owns the page's vertical scroll. Holds the
     * editor and the folder contents list as siblings so they scroll together.
     * The editor itself no longer scrolls — its content height grows as
     * needed and the wrapper's `overflow-y: auto` carries the scrollbar.
     */
    private var scrollWrapperElement: HTMLElement? = null

    /**
     * Headline element rendered above the editor that shows the leaf title
     * of the current zoom target — i.e. the deepest segment of the
     * breadcrumb path the pane chrome displays. Falls back to "Home" when
     * the document is not zoomed. Updated on every state emission.
     */
    private var titleElement: HTMLElement? = null

    /**
     * The pane's search field and result list ([PaneSearchBar]): the
     * field above the title, the list in the scroll area in the page's
     * place while a query is active.
     */
    private val searchBar = PaneSearchBar(
        viewModel,
        onLeave = {
            // The editor is still hidden behind the result list until the
            // next repaint; show it now, or it cannot take focus.
            editorElement?.let { ed ->
                ed.style.display = ""
                folderContentsElement?.style?.display = ""
                ed.focus()
            }
        },
        onOpenHit = { hit -> onOpenSearchHit?.invoke(hit) },
    )

    /**
     * A title edit committed but not yet applied: the file it renames and
     * the typed title. [updateTitle] shows the typed title until the
     * pane's `activeFileRel` moves off that file (or [RENAME_PENDING_MS]
     * passes), so the old name never flashes back while the rename runs.
     */
    private var pendingRename: Pair<String, String>? = null

    /** Set by Escape in the title so the blur that follows discards the edit. */
    private var titleEditCancelled = false

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
     * collector. When the next emission's pair differs, the pane plays
     * its navigation animation ([navigationTransition]). Initialised on
     * the first emission so the very first paint after mount does not
     * animate (mounting already implies a fresh render).
     */
    private var lastNavSignature: Pair<String?, LineId?>? = null

    /**
     * The last state the collector painted — the view on screen. Read by
     * [captureOutgoing] to tell a zoom in from a zoom out and to find the
     * clicked bullet's row in the outgoing view.
     */
    private var lastPaintedState: PaneBackingViewModel.State? = null

    /** Plays the zoom morph / fade-through on navigation. */
    private val navigationTransition = NavigationTransition()

    /** Plays the unfold / fold animation. */
    private val foldTransition = FoldTransition()

    /**
     * Until when (`Date.now()` ms) a fold is in progress: set when a fold
     * control is clicked ([FOLD_EVENT]) or Cmd-Up / Cmd-Down folds the
     * caret's item, so the repaints that follow —
     * including an unfold's children arriving after they load from disk —
     * animate; cleared once one has shown rows appearing, disappearing or
     * changing size. Fold state that changes by itself (the default fold
     * when a document loads) and ordinary typing never animate.
     */
    private var foldArmedUntil: Double = 0.0

    /**
     * The outgoing view, captured the moment the navigation signature
     * changed and before the new view was painted. Held across one or
     * more `isLoaded=false` "Loading…" frames of a cross-file switch and
     * animated away once the new content lands underneath.
     *
     * For same-file zoom transitions the new content is already loaded
     * on the same emission, so the animation starts on that emission.
     */
    private var pendingOutgoing: OutgoingView? = null

    /**
     * `true` while 3D mode has this screen's elements on a page in space
     * ([mountInSpace]). The flight there replaces the navigation and fold
     * animations, Escape leaves 3D mode instead of clearing the zoom, and a
     * [render] from the pane chrome only records [homeRoot].
     */
    var inSpace: Boolean = false
        private set

    /**
     * The pane's own container — where [leaveSpace] puts the elements back.
     * Set by every [render] made outside 3D mode and by renders the toolkit
     * makes while the elements are in space.
     */
    private var homeRoot: HTMLElement? = null

    /**
     * The page's last scroll offset, from the scroll wrapper's own scroll
     * events. Moving the elements between the pane and a page in space
     * resets the browser's offset; [mountInSpace] / [leaveSpace] put this
     * back.
     */
    private var lastScrollTop: Double = 0.0

    /**
     * Mounts the editor UI into [root]. Clears any prior content, builds
     * the contenteditable host, wires input listeners, and starts the
     * state collector. Safe to call once per pane lifetime.
     */
    fun render(root: HTMLElement) {
        viewModel.openSearchHitInNewWindow = onOpenSearchHit
        viewModel.openLinkInNewWindow = onOpenLinkInNewPane
        ensureStyles()
        installRootStyles(root)
        if (inSpace && titleElement != null) {
            // The toolkit rebuilt the pane while 3D mode holds the
            // elements: remember the new container for [leaveSpace].
            homeRoot = root
            return
        }
        homeRoot = root
        rootElement = root

        val existingTitle = titleElement
        val existingScroll = scrollWrapperElement
        val existingEditor = editorElement
        val existingBanner = restructureBannerElement
        val existingImageViewer = imageViewerElement
        val existingDrawingHost = drawingHostElement
        if (existingTitle != null && existingScroll != null &&
            existingEditor != null && existingBanner != null &&
            existingImageViewer != null && existingDrawingHost != null
        ) {
            // Idempotent re-mount: AppShell rebuilds the pane chrome on
            // every navigation transition (back/forward stack changes
            // trigger `rerenderActivePane`), passing a fresh `container`
            // div each time. Wiping our state and rebuilding from
            // scratch would (1) wipe the title/editor content the live
            // collector just painted — breaking the navigation animation's
            // snapshot — and (2) launch a duplicate collector that
            // writes to detached elements. Instead, move the existing
            // elements into the new container and bail out before the
            // collector launch below.
            root.innerHTML = ""
            root.appendChild(searchBar.element)
            root.appendChild(existingTitle)
            root.appendChild(existingScroll)
            root.appendChild(existingDrawingHost)
            root.appendChild(existingBanner)
            return
        }
        // First-time mount: wipe (defensive — caller passes an empty
        // container) and build the element tree from scratch.
        root.innerHTML = ""

        root.appendChild(searchBar.element)

        val title = buildTitleElement()
        root.appendChild(title)
        titleElement = title
        wireTitleEditing(title)

        val scrollWrapper = buildScrollWrapper()
        root.appendChild(scrollWrapper)
        scrollWrapperElement = scrollWrapper
        // The pane remembers each page's scroll (PaneBackingViewModel
        // page memory); the host persists the settled one.
        scrollWrapper.addEventListener("scroll", { _ ->
            val top = scrollWrapper.scrollTop
            lastScrollTop = top
            viewModel.noteScroll(top)
            scrollSettleHandle?.let { window.clearTimeout(it) }
            scrollSettleHandle = window.setTimeout({ onScrollSettled?.invoke(top) }, SCROLL_SETTLE_MS)
        })

        val editor = buildEditorElement()
        scrollWrapper.appendChild(editor)
        editorElement = editor

        scrollWrapper.appendChild(searchBar.resultsElement)

        val folderContents = buildFolderContentsElement()
        scrollWrapper.appendChild(folderContents)
        folderContentsElement = folderContents

        val imageViewer = buildImageViewerElement()
        scrollWrapper.appendChild(imageViewer)
        imageViewerElement = imageViewer

        val drawingHost = buildDrawingHostElement()
        root.appendChild(drawingHost)
        drawingHostElement = drawingHost
        val drawing = DrawingEditor(drawingHost, viewModel, scope)
        drawingEditor = drawing

        val htmlHost = buildDrawingHostElement().apply { className = "lunarbor-html-host" }
        root.appendChild(htmlHost)
        val html = HtmlViewer(htmlHost)
        htmlViewer = html

        val restructureBanner = buildRestructureBanner()
        root.appendChild(restructureBanner)
        restructureBannerElement = restructureBanner

        wireInputListeners(editor)
        editor.addEventListener(FOLD_EVENT, { _ -> foldArmedUntil = kotlin.js.Date.now() + FOLD_WINDOW_MS })

        // Click-anywhere-to-type. The editor is only as tall as its
        // content, so the scroll wrapper's background below the rows is
        // dead space to the browser's caret placement. Route a click that
        // lands on the wrapper (or the editor host's own empty area — not
        // a row, the folder contents list, a link, or a bullet) to "caret at end of document"
        // so a fresh/empty vault isn't an un-clickable black void.
        scrollWrapper.addEventListener("mousedown", { event ->
            val me = event as MouseEvent
            val target = me.target
            // `editor.style.display == "none"` in image view — the pane is
            // showing the image viewer, not an editable document, so leave
            // the click alone.
            if ((target === scrollWrapper || target === editor) && editor.style.display != "none") {
                me.preventDefault()
                focusEditorAtLastRow()
            }
        })

        editor.focus()

        scope.launch {
            viewModel.stateFlow.collect { state ->
                val backing = state.backingState
                // Detect navigation transitions BEFORE running reconcile so
                // we can snapshot the outgoing content as an overlay; the
                // navigation animation (zoom morph or fade-through, see
                // NavigationTransition) then plays from it onto the freshly
                // rendered new content, avoiding a blank middle frame.
                //
                // Cross-file navigation (contents-list click) emits an interim
                // `isLoaded=false` "Loading…" state before the new file's
                // content lands. We snapshot on that first emission (so
                // we capture the *outgoing* file's content), then hold
                // the overlay until a subsequent `isLoaded=true` emission
                // arrives — only then is there real new content beneath
                // the overlay to dissolve into.
                val navigated = isNavigationTransition(backing)
                if (pendingOutgoing == null && navigated && backing != null && !inSpace) {
                    pendingOutgoing = captureOutgoing(backing)
                }
                // Pull DOM focus back into the editor whenever the pane
                // changes file or zoom target. The trigger may have come
                // from a click on a header button (Back / Forward) or a
                // breadcrumb segment, the folder contents list, the starred modal, or the
                // command palette — none of which leave focus on the
                // editor. Mirrors the on-pane-focus behaviour so the
                // caret is ready for keystrokes the moment the new view
                // lands. Cursor position itself is restored from the
                // pane VM during reconcile, so this only re-arms input.
                if (navigated) editor.focus()
                // Landing on a new node re-reads its folder, so the
                // contents list picks up files added outside the app
                // (Finder) since the folder was last read.
                if (navigated) viewModel.refreshCurrentFolderListing()
                // Skip the paint while an animation is pending and the new
                // file is still loading: reconcile would wipe the editor
                // and stamp "Loading…" underneath the overlay, which —
                // despite the overlay being opaque — was perceptible as a
                // brief flash on the file-nav path. Keeping the live DOM
                // unchanged means the overlay sits over an identical copy
                // of itself; the next emission (isLoaded=true) repaints
                // to the new content and the animation plays onto it.
                // Image view is "ready to paint" as soon as the new
                // activeFileRel lands — there is no Document load step to
                // wait on — so it bypasses the loading-skip and the
                // animation also plays on the first emission that points
                // at the image.
                val skipPaint = pendingOutgoing != null &&
                    backing != null && !backing.isLoaded && !backing.isFileView
                // A fold or unfold in the same view (armed by the fold
                // control): animate the repaint.
                val prev = lastPaintedState
                val foldBefore = if (!inSpace && !navigated && !skipPaint && prev != null && backing != null &&
                    backing.isLoaded && !backing.isImageView && kotlin.js.Date.now() < foldArmedUntil
                ) {
                    prev.documentState?.lineIds?.let { foldTransition.capture(editor, it) }
                } else null
                if (backing != null) showHitDoneToast(backing)
                if (!skipPaint) {
                    if (backing != null) searchBar.update(backing)
                    // The drawing editor takes the whole pane below the
                    // title in place of the scroll wrapper.
                    // So does an HTML page's web view.
                    if (backing != null && backing.isDrawingView) {
                        scrollWrapper.style.display = "none"
                        html.hide()
                        drawing.show(backing.activeFileRel, backing.drawingRevision)
                    } else if (backing != null && backing.isHtmlView) {
                        scrollWrapper.style.display = "none"
                        drawing.hide()
                        html.show(backing.activeFileRel)
                    } else {
                        drawing.hide()
                        html.hide()
                        scrollWrapper.style.display = ""
                    }
                    // No editor on screen: no board node shown either (LBR-27);
                    // the editor's paint reports its own.
                    if (backing == null || backing.isFileView || backing.isSearchActive) {
                        viewModel.reportShownBoards(emptyMap())
                    }
                    if (backing != null && (backing.isDrawingView || backing.isHtmlView)) {
                        // Nothing else to paint: the editor and the folder
                        // contents list are hidden with the scroll wrapper.
                    } else if (backing != null && backing.isSearchActive && !backing.isImageView) {
                        // The result list stands in for the page: the
                        // editor is neither shown nor repainted, so typing
                        // in the search field stays cheap.
                        editor.style.display = "none"
                        imageViewer.style.display = "none"
                        folderContents.style.display = "none"
                    } else if (backing == null) {
                        paintLoading(editor)
                    } else if (backing.isImageView) {
                        folderContents.style.display = ""
                        editor.style.display = "none"
                        imageViewer.style.display = "flex"
                        paintImageViewer(imageViewer, backing.activeFileRel)
                        paintFolderContents(folderContents, backing, viewModel, style, scope)
                    } else {
                        imageViewer.style.display = "none"
                        editor.style.display = ""
                        folderContents.style.display = ""
                        reconcile(editor, backing)
                        val ids = backing.documentState?.lineIds.orEmpty()
                        if (foldBefore != null && foldTransition.play(foldBefore, editor, ids)) {
                            foldArmedUntil = 0.0
                        } else {
                            // A repaint during a fold keeps it running.
                            foldTransition.resume(editor, ids)
                        }
                        paintFolderContents(folderContents, backing, viewModel, style, scope)
                    }
                    updateTitle(title, backing)
                    // A page the pane came back to scrolls where it was —
                    // once its content is painted: on the "Loading…" frame
                    // there is nothing to scroll and the offset would be lost.
                    backing?.takeIf { it.isLoaded || it.isFileView }?.scrollRestore?.let { r ->
                        if (r.seq != lastScrollRestoreSeq) {
                            lastScrollRestoreSeq = r.seq
                            scrollWrapper.scrollTop = r.top
                            // The caret counts as placed: a later repaint
                            // with the caret where it is must not scroll it
                            // into view and undo this.
                            lastAppliedCursor = backing.cursorRow to backing.cursorCol
                        }
                    }
                    updateRestructureBanner(restructureBanner, backing?.isRestructuring == true)
                    if (backing != null) lastPaintedState = backing
                }
                val pending = pendingOutgoing
                if (pending != null && backing != null &&
                    (backing.isLoaded || backing.isFileView)
                ) {
                    navigationTransition.play(pending, title, scrollWrapper, editor)
                    pendingOutgoing = null
                }
                rememberNavSignature(backing)
            }
        }
    }

    /**
     * Give the editor DOM focus without moving the caret. Called by
     * [AppShell] from the toolkit's `onPaneFocused` callback (which
     * fires on every pane focus event — hotkey cycles, mouse clicks on
     * a pane, host-driven focus on tab switch) so the editor is ready
     * for keystrokes the moment a pane becomes active.
     *
     * The caret position is per-pane state owned by
     * [se.soderbjorn.lunarbor.main.PaneBackingViewModel] and survives
     * re-renders and tab switches. Re-focusing a pane (including the
     * already-active one) therefore leaves the caret exactly where the
     * user last placed it — opening and closing the command palette,
     * clicking inside the active pane, or switching tabs and back all
     * preserve the caret. The per-pane VM is what makes "switch to
     * pane X" land back at pane X's last cursor instead of row 0.
     *
     * No-op until the editor has been mounted (i.e. before the first
     * [render]) so a stray pre-mount focus event cannot crash.
     */
    fun focusEditor() {
        val editor = editorElement ?: return
        editor.focus()
    }

    /**
     * Opens the pane's search field (or re-focuses it when open) and puts
     * the keyboard in it. Called by the pane header's search button and
     * the "Search this tree" palette command and Cmd-F ([AppShell]).
     */
    fun openSearch() {
        viewModel.openSearch()
        // Show the field now — the state collector repaints a tick later,
        // and a hidden input cannot take focus.
        searchBar.update(viewModel.currentBackingState)
        searchBar.focus()
    }

    /**
     * Moves the screen's elements onto a page in 3D mode's space: into
     * [host], at the page's 1:1 size, with the page's scroll offset kept.
     * Until [leaveSpace] the pane chrome cannot take them back, and the
     * navigation and fold animations are off (the flight replaces them).
     *
     * Called by the web `PageSpaceView` when a view mounts its pane's live
     * page. Mounts the screen for the first time when it never was.
     *
     * @param host The live page's body; filled by the screen like a pane.
     */
    fun mountInSpace(host: HTMLElement) {
        val keepFocus = editorElement?.let { ed -> document.activeElement?.let { ed.contains(it) } } == true
        val top = lastScrollTop
        val home = homeRoot
        inSpace = false
        render(host)
        // Never mounted in the pane yet: the chrome's first render will
        // set the home (and, with the screen back out of space, take it).
        homeRoot = home
        inSpace = true
        // A navigation snapshot caught on the way in would hang over the
        // page; the flight stands in for it.
        pendingOutgoing?.let { it.overlay.remove(); it.titleFlyer?.remove() }
        pendingOutgoing = null
        restoreScrollAndCaret(top, keepFocus)
    }

    /**
     * Puts the screen's elements back in the pane's own container (the
     * last one the chrome rendered it into) with the page's scroll offset,
     * and turns the animations and Escape's zoom-out back on. A no-op when
     * the screen is not in space.
     *
     * Called by the web `SpaceMode` when 3D mode closes, or a view stops
     * showing this pane.
     *
     * @param focus Whether to put the keyboard back in the editor, with
     *   the caret where the pane has it (the focused pane only — there is
     *   one document selection).
     */
    fun leaveSpace(focus: Boolean) {
        if (!inSpace) return
        val top = lastScrollTop
        inSpace = false
        val home = homeRoot ?: return
        render(home)
        restoreScrollAndCaret(top, focus)
    }

    /**
     * Sets the scroll wrapper back to [top] and, with [focus], focuses the
     * editor with the pane's caret and selection re-applied — moving the
     * elements dropped both.
     */
    private fun restoreScrollAndCaret(top: Double, focus: Boolean) {
        val scroller = scrollWrapperElement ?: return
        scroller.scrollTop = top
        lastScrollTop = top
        if (!focus) return
        val editor = editorElement ?: return
        if (editor.style.display == "none") return
        editor.asDynamic().focus(js("({preventScroll: true})"))
        val state = viewModel.currentBackingState
        if (!state.isLoaded) return
        applyDomSelection(
            editor,
            state.anchorRow ?: state.cursorRow,
            state.anchorCol ?: state.cursorCol,
            state.cursorRow,
            state.cursorCol,
            scrollCursorIntoView = false,
        )
        scroller.scrollTop = top
    }

    /**
     * The bullet dot of the item at [row] in the painted outline, else the
     * row itself; `null` when the row is not painted (folded away,
     * scrolled-off rows are still painted). Read by the web `PageSpaceView`
     * to start a thread at a child page's bullet.
     */
    fun bulletElementOfRow(row: Int): HTMLElement? {
        val editor = editorElement ?: return null
        if (editor.style.display == "none") return null
        val rowDiv = editor.querySelector("[data-row='$row']") as? HTMLElement ?: return null
        return rowDiv.querySelector(".lunarbor-bullet") as? HTMLElement ?: rowDiv
    }

    /** The element that scrolls the page; the web `PageSpaceView` redraws its threads on its scroll. */
    val scrollElement: HTMLElement? get() = scrollWrapperElement

    /** `true` while the pane's search field is open. Read by [AppShell]'s Escape handling. */
    val isSearchOpen: Boolean get() = viewModel.currentBackingState.searchQuery != null

    /** Closes the pane's search and puts the keyboard back in the editor (Escape, via [AppShell]). */
    fun closeSearch() = searchBar.close()

    /**
     * The pane search's highlighted result while its list shows, or `null`
     * ([PaneSearchBar.selectedHit]). Read by [AppShell]'s palette to offer
     * "Toggle done" on it (LBR-22).
     */
    val selectedSearchHit: se.soderbjorn.lunarbor.data.TextHit? get() = searchBar.selectedHit()

    /** Toggle done on the highlighted search result ([PaneSearchBar.toggleSelectedDone]); the palette's command. */
    fun toggleSelectedSearchHitDone() = searchBar.toggleSelectedDone()

    /** The arrow keys' cursor over a search node's result rows ([SearchNodeHitCursor]). */
    private val searchNodeHitCursor = SearchNodeHitCursor(viewModel)

    /**
     * The arrow keys' cursor over a board node's rows ([LunicleBoardCursor],
     * LBR-28), and its text field on titles, drafts and "New issue" (LBR-29).
     * When the field goes, the keyboard comes back to the editor.
     */
    private val lunicleBoardCursor = LunicleBoardCursor(viewModel) { focusEditorAfterBoardField() }

    /**
     * Gives the keyboard back to the editor after a board field went away
     * (LBR-29): focus, and the DOM caret on the model's (the board node's
     * line), so the next key's DOM sync keeps the cursor where it is. On a
     * read-only page the editor takes no focus; the body has it.
     */
    private fun focusEditorAfterBoardField() {
        val editor = editorElement ?: return
        val s = viewModel.currentBackingState
        if (s.isReadOnlyPage) return
        editor.focus()
        applyDomSelection(editor, s.anchorRow ?: s.cursorRow, s.anchorCol ?: s.cursorCol, s.cursorRow, s.cursorCol, scrollCursorIntoView = false)
    }

    /**
     * `true` when [event] comes from a board's text field (LBR-29,
     * [LunicleBoardCursor]): the field edits itself, so the editor's input,
     * clipboard and selection handlers leave it alone.
     */
    private fun fromBoardField(event: Event): Boolean =
        (event.target as? HTMLElement)?.classList?.contains("lunarbor-lunicle-field") == true

    /** `true` while the arrow keys are on a board node's row ([LunicleBoardCursor.isActive]). */
    val isOnLunicleBoardRow: Boolean get() = lunicleBoardCursor.isActive

    /**
     * The search-node result the arrow keys highlight, or `null`
     * ([SearchNodeHitCursor.selectedHit]). Read by [AppShell]'s palette to
     * offer "Toggle done" on it (LBR-22).
     */
    val selectedSearchNodeHit: se.soderbjorn.lunarbor.data.TextHit? get() = searchNodeHitCursor.selectedHit()

    /** `true` while the arrow keys highlight a search node's result ([SearchNodeHitCursor.isActive]). */
    val isOnSearchNodeHit: Boolean get() = searchNodeHitCursor.isActive

    /** Toggle done on the highlighted search-node result ([SearchNodeHitCursor.toggleSelectedDone]); the palette's command. */
    fun toggleSelectedSearchNodeHitDone() = searchNodeHitCursor.toggleSelectedDone()

    /** Serial of the last Toggle done toast shown ([showHitDoneToast]). */
    private var shownToastSerial = 0

    /**
     * Shows the "Marked done · Undo" toast (LBR-22) when [state] carries a
     * Toggle done on a search result this view has not shown a toast for
     * yet. Called on every state emission.
     */
    private fun showHitDoneToast(state: PaneBackingViewModel.State) {
        val toast = state.hitDoneToast ?: return
        if (toast.serial == shownToastSerial) return
        shownToastSerial = toast.serial
        showUndoToast(
            if (toast.toggle.done) "Marked done" else "Marked not done",
            onUndo = { viewModel.undoHitDoneToggle() },
            onTimeout = { viewModel.dismissHitDoneToast() },
        )
    }

    /**
     * Whether [target] is contained inside this pane's editor element.
     * Used by the AppShell-level keydown delegate to know if it should
     * dispatch a non-editor-focused keypress to this pane's editor (it
     * shouldn't if focus is already inside the editor — the editor's
     * own handler is about to fire).
     */
    fun editorContains(target: org.w3c.dom.Node?): Boolean {
        if (target == null) return false
        val editor = editorElement ?: return false
        return editor.contains(target)
    }

    /**
     * Synthesise a keydown event into this pane's editor handling
     * pipeline. Used by AppShell's document-level key delegate so
     * shortcuts like Cmd-Shift-Left work even when DOM focus is
     * outside any editable (e.g., on `<body>` after the user closed a
     * non-editor popover).
     *
     * Focuses the editor before dispatching so the regular flow
     * (selection sync, model updates, paint) can settle in the right
     * place once the dispatched key has done its work.
     */
    fun dispatchEditorKey(event: KeyboardEvent) {
        val editor = editorElement ?: return
        editor.focus()
        handleKey(editor, event)
    }

    // ----------------------------------------------------------------- input

    private fun wireInputListeners(editor: HTMLElement) {
        editor.addEventListener("beforeinput", { event ->
            if (fromBoardField(event)) return@addEventListener
            handleBeforeInput(editor, event.unsafeCast<dynamic>())
        })
        // A press on a board's title, draft or "New issue" line starts
        // editing it there (LBR-29); on a description line or "Comment…"
        // too (LBR-31), a description at the pressed line.
        editor.addEventListener(LUNICLE_PRESS_EVENT, { event ->
            val detail = event.asDynamic().detail ?: return@addEventListener
            searchNodeHitCursor.clear(editor)
            val line = (detail.line as? Number)?.toInt() ?: -1
            lunicleBoardCursor.press(editor, (detail.row as Number).toInt(), detail.key as String, line)
        })
        // A press on an issue's pill opens that field's menu (LBR-30).
        editor.addEventListener(LUNICLE_PILL_EVENT, { event ->
            val detail = event.asDynamic().detail ?: return@addEventListener
            searchNodeHitCursor.clear(editor)
            lunicleBoardCursor.pressPill(editor, (detail.row as Number).toInt(), detail.key as String, detail.field as String)
        })
        editor.addEventListener("keydown", { event ->
            handleKey(editor, event as KeyboardEvent)
        })
        // Drag-from-gutter init: a mousedown in a row's left padding zone,
        // when that row sits inside the active selection, begins a row-range
        // drag. Bullet-dot drags are wired separately by `OutlinePaintLoop`
        // via the `onBulletMouseDown` callback we pass to `paint`.
        //
        // External-link follow is wired into the same `mousedown` slot so
        // the browser opens before the default contenteditable caret-place
        // runs — handling this on `click` was unreliable because the
        // intervening `mouseup` triggers `syncSelectionFromDom`, which can
        // emit state and rebuild the DOM; by the time `click` fires the
        // original target span is detached and the event is lost.
        linkHoverPopup.attach(editor)
        editor.addEventListener("mousedown", { event ->
            val me = event as MouseEvent
            searchNodeHitCursor.clear(editor)
            lunicleBoardCursor.clear(editor)
            if (handleExternalLinkMouseDown(me)) return@addEventListener
            if (handleLunarborLinkMouseDown(me)) return@addEventListener
            // Resize-handle drag has to win against the click-popover
            // handler since the handle sits inside the image span; the
            // popover only opens when the press lands on the image body.
            if (handleImageResizeMouseDown(me)) return@addEventListener
            if (handleImageMouseDown(me)) return@addEventListener
            maybeBeginGutterDrag(editor, me)
        })
        // Right-click opens in a new window: a vault link (or a resolved
        // wiki link) its target, a bullet's dot its item. Anywhere else the
        // usual context menu shows.
        editor.addEventListener("contextmenu", { event ->
            if (handleOpenInNewPaneContextMenu(event as MouseEvent)) {
                event.preventDefault()
                event.stopPropagation()
            }
        })
        editor.addEventListener("copy", { event ->
            if (fromBoardField(event)) return@addEventListener
            handleCopy(editor, event.unsafeCast<dynamic>())
        })
        editor.addEventListener("cut", { event ->
            if (fromBoardField(event)) return@addEventListener
            handleCut(editor, event.unsafeCast<dynamic>())
        })
        editor.addEventListener("paste", { event ->
            if (fromBoardField(event)) return@addEventListener
            handlePaste(editor, event.unsafeCast<dynamic>())
        })
        // Drag-and-drop image files from the OS into the editor. Same
        // pipeline as paste — the only difference is the data source
        // (`dataTransfer` vs `clipboardData`). We need both `dragover`
        // (to allow the drop at all — without preventing the default
        // there, the browser refuses the drop) and `drop`.
        editor.addEventListener("dragover", { event ->
            val dt = (event.asDynamic().dataTransfer)
            if (dt != null && carriesDroppableImage(dt)) {
                event.preventDefault()
            }
        })
        editor.addEventListener("drop", { event ->
            handleDrop(editor, event.unsafeCast<dynamic>())
        })
        // Sync model selection from DOM on mouse interactions so any
        // selection-aware intent (cut, indent) sees the user's intent.
        editor.addEventListener("mouseup", { event: Event ->
            if (fromBoardField(event)) return@addEventListener
            syncSelectionFromDom(editor)
        })
        editor.addEventListener("keyup", { event ->
            if (fromBoardField(event)) return@addEventListener
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
        if (viewModel.currentBackingState.isReadOnlyPage) return
        // Skip the DOM→model selection sync when an inline style is armed
        // and the caret is collapsed: hidden marker runs (`**`, `~~`, …)
        // create multiple model positions that map to the same display
        // position, and the DOM round-trip lands on the position past
        // the closers — which would shift the cursor out of the styled
        // span we just opened, clear `pendingInlineStyles`, and break
        // continuous bold/italic typing. The model's cursor is already
        // correct (we set it via `applyDomSelection` after the prior
        // edit); trust it.
        val backing = viewModel.currentBackingState
        val skipSync = backing.pendingInlineStyles.isNotEmpty() &&
            backing.anchorRow == null && backing.anchorCol == null
        if (!skipSync) {
            if (!syncSelectionFromDom(editor)) return
        }
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
            "historyUndo" -> viewModel.undo()
            "historyRedo" -> viewModel.redo()
            else -> {
                // Unknown input type — fall back to data insertion if any.
                val data = event.data?.unsafeCast<String?>()
                if (!data.isNullOrEmpty()) viewModel.insertText(data)
            }
        }
    }

    /**
     * Handles keyboard shortcuts that don't fit the `beforeinput` model:
     * Tab (indent/outdent; a block moves whole), Escape (leave a block,
     * else zoom out), Cmd-Enter (leave a block), Cmd/Ctrl+A (select all
     * routed through the model so subsequent edits see the right range),
     * and the Option-Cmd navigation set —
     * Left = back through zoom history, Right = forward,
     * Up = zoom one level out (parent ancestor),
     * Enter = zoom into the bullet on the cursor row (a promoted-ref
     * bullet lazy-loads its linked file, giving "open this page" UX).
     */
    private fun handleKey(editor: HTMLElement, event: KeyboardEvent) {
        val cmd = event.ctrlKey || event.metaKey
        // The arrow keys walk a search node's result rows (also on its
        // read-only page): they take the key first while on them.
        if (searchNodeHitCursor.handleKey(editor, event) { syncSelectionFromDom(editor) }) return
        // And a board node's rows (LBR-28), where ⌘↑ / ⌘↓ fold columns and issues.
        if (lunicleBoardCursor.handleKey(editor, event) { syncSelectionFromDom(editor) }) return
        if (viewModel.currentBackingState.isReadOnlyPage) {
            // Read-only: only the way up leaves it from here (Back and
            // Forward are app-wide shortcuts).
            if (event.key == "ArrowUp" && event.ctrlKey && event.metaKey && !event.altKey) {
                event.preventDefault()
                if (event.shiftKey) viewModel.navigateHome() else viewModel.navigateUp()
            } else if (!(cmd && event.key.lowercase() == "c")) {
                event.preventDefault()
            }
            return
        }
        if (cmd && event.key.lowercase() == "a") {
            // Let the model own selection bounds — when zoomed, selectAll
            // should clamp to the visible subtree, not the whole document.
            event.preventDefault()
            syncSelectionFromDom(editor)
            viewModel.selectAll()
            return
        }
        if (cmd && !event.altKey && event.key.lowercase() == "z") {
            // Cmd-Z / Shift-Cmd-Z. Most browsers also fire `beforeinput`
            // with `historyUndo` / `historyRedo` for these chords inside
            // contenteditable, but routing here too ensures the shortcut
            // works even if the focused element doesn't dispatch
            // beforeinput (e.g. while a non-editable bullet glyph holds
            // focus during a drag handoff).
            event.preventDefault()
            if (event.shiftKey) viewModel.redo() else viewModel.undo()
            return
        }
        if (cmd && !event.altKey && !event.shiftKey && event.key.lowercase() == "y") {
            // Windows/Linux convention: Cmd/Ctrl-Y as redo. Mac users
            // typically use Shift-Cmd-Z (handled above).
            event.preventDefault()
            viewModel.redo()
            return
        }
        if (cmd && !event.altKey && !event.shiftKey) {
            // Inline-style toggles (Cmd-B / Cmd-I). Sync the DOM selection
            // first so the toggle wraps whatever the user has highlighted
            // — but skip the sync when an inline style is already armed
            // and the caret is collapsed. The DOM round-trip lands at the
            // "past the close markers" canonical column (the same hazard
            // that `handleBeforeInput` works around), which would yank
            // the model cursor out of the armed pair and clear
            // [pendingInlineStyles] before [applyInlineStyle] runs — so
            // a second Cmd-B to "stop bolding" would silently re-arm
            // bold and wrap the next typed char in a nested pair.
            val backing = viewModel.currentBackingState
            val skipSync = backing.pendingInlineStyles.isNotEmpty() &&
                backing.anchorRow == null && backing.anchorCol == null
            when (event.key.lowercase()) {
                "b" -> {
                    event.preventDefault()
                    if (!skipSync) syncSelectionFromDom(editor)
                    viewModel.applyInlineStyle(InlineStyle.BOLD)
                    return
                }
                "i" -> {
                    event.preventDefault()
                    if (!skipSync) syncSelectionFromDom(editor)
                    viewModel.applyInlineStyle(InlineStyle.ITALIC)
                    return
                }
            }
        }
        if (event.key == "ArrowUp" && event.ctrlKey && event.metaKey && !event.altKey) {
            // Ctrl-Cmd-Up: one level up; with Shift, all the way to the
            // root outline. Not Option-Cmd-Up: the toolkit's "Expand
            // pane" owns that chord and takes it before the editor sees it.
            event.preventDefault()
            if (event.shiftKey) viewModel.navigateHome() else viewModel.navigateUp()
            return
        }
        if ((event.key == "ArrowUp" || event.key == "ArrowDown") &&
            event.metaKey && !event.ctrlKey && !event.altKey && !event.shiftKey
        ) {
            // Cmd-Up folds / Cmd-Down unfolds the caret's item (Dynalist /
            // Workflowy chords). Replaces the browser's jump to the top /
            // bottom of the editor; Shift keeps its select-to-edge meaning.
            // Armed like a click on the fold control, so it animates too —
            // disarmed again when nothing folded (a leaf, already folded),
            // so the next edit doesn't animate.
            event.preventDefault()
            syncSelectionFromDom(editor)
            val armedBefore = foldArmedUntil
            foldArmedUntil = kotlin.js.Date.now() + FOLD_WINDOW_MS
            val before = viewModel.currentBackingState
            viewModel.setCaretItemFolded(folded = event.key == "ArrowUp")
            val after = viewModel.currentBackingState
            if (before.collapsedIds == after.collapsedIds &&
                before.expandedRefIdsLocal == after.expandedRefIdsLocal
            ) {
                foldArmedUntil = armedBefore
            }
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
                "Enter" -> {
                    event.preventDefault()
                    viewModel.zoomInto(viewModel.currentBackingState.cursorRow)
                    return
                }
            }
        }
        if ((event.key == "ArrowLeft" || event.key == "ArrowRight") &&
            !event.altKey && !event.metaKey && !event.ctrlKey && !event.shiftKey
        ) {
            // Plain Arrow with an active model selection: collapse through
            // the view model rather than letting the browser collapse the
            // DOM selection. Hidden marker runs (`**`, `~~`, …) make the
            // DOM-level collapse ambiguous — multiple model columns map to
            // the same display column and the round-trip lands "past the
            // closers". After Cmd-B'ing a word at the end of a row, that
            // round-trip leaves the caret at end-of-editable, and the
            // browser's next caret slot is the start of the following row,
            // so the press visibly jumps to the next line. Routing through
            // the model makes collapse selection-aware and unambiguous.
            if (syncSelectionFromDom(editor)) {
                val backing = viewModel.stateFlow.value.backingState
                val sel = backing?.let { PaneBackingViewModel.selectionOf(it) }
                if (sel != null) {
                    event.preventDefault()
                    if (event.key == "ArrowLeft") viewModel.moveLeft() else viewModel.moveRight()
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
                    if (line != null && DocumentLayout.isAtVisibleTextStart(line, backing.cursorCol)) {
                        event.preventDefault()
                        viewModel.moveLeft(extend = event.shiftKey)
                        return
                    }
                }
            }
        }
        if (event.key == "ArrowRight" && !event.altKey && !event.metaKey && !event.ctrlKey) {
            // The mirror: plain ArrowRight at the end of a row's text. The
            // browser would step into the next row's contenteditable=false
            // bullet prefix first and the model would then snap the caret
            // to the start of its text — a visible two-step hop. Route
            // through the view model so the caret lands there directly.
            if (syncSelectionFromDom(editor)) {
                val backing = viewModel.stateFlow.value.backingState
                if (backing != null) {
                    val line = backing.lines.getOrNull(backing.cursorRow)
                    if (line != null && DocumentLayout.isAtVisibleTextEnd(line, backing.cursorCol)) {
                        event.preventDefault()
                        viewModel.moveRight(extend = event.shiftKey)
                        return
                    }
                }
            }
        }
        if (event.key == "ArrowDown" && !event.altKey && !event.metaKey && !event.ctrlKey && !event.shiftKey) {
            // Plain ArrowDown on the last row of a block that nothing
            // follows: the browser has nowhere to go, so leave the block
            // onto a new bullet below it. Everywhere else the browser
            // moves the caret as usual.
            if (syncSelectionFromDom(editor) && viewModel.moveDownOutOfBlock()) {
                event.preventDefault()
                return
            }
        }
        if (event.key == "ArrowUp" && !event.altKey && !event.metaKey && !event.ctrlKey && !event.shiftKey) {
            // The mirror: plain ArrowUp on the first row of a block that
            // nothing precedes leaves it onto a new bullet above it.
            if (syncSelectionFromDom(editor) && viewModel.moveUpOutOfBlock()) {
                event.preventDefault()
                return
            }
        }
        if (event.key == "Enter" && !event.shiftKey && !event.metaKey &&
            (if (isMacPlatform) event.ctrlKey && !event.altKey else event.altKey && !event.ctrlKey)
        ) {
            // Toggle done (LBR-24): ⌃↩ on the Mac (Cmd-Enter and
            // Shift-Cmd-Enter leave a block, Option-Cmd-Enter zooms),
            // Alt-Enter elsewhere, where Ctrl stands in for Cmd. Strikes or
            // unstrikes the whole title of the caret's item, or of every
            // item the selection touches. Outlines only.
            event.preventDefault()
            syncSelectionFromDom(editor)
            viewModel.toggleDone()
            return
        }
        if (event.key == "Enter" && cmd && !event.altKey && event.shiftKey) {
            // Shift-Cmd-Enter in a block: leave it onto a new bullet right
            // above it (Cmd-Enter leaves below).
            event.preventDefault()
            syncSelectionFromDom(editor)
            if (viewModel.isBlockLine()) viewModel.exitBlockAbove()
            return
        }
        if (event.key == "Enter" && cmd && !event.altKey && !event.shiftKey) {
            // Cmd-Enter in a block (TRF-5): leave it onto a new bullet
            // right after it. Outside a block Cmd-Enter does nothing.
            event.preventDefault()
            syncSelectionFromDom(editor)
            if (viewModel.isBlockLine()) viewModel.exitBlock()
            return
        }
        if (event.key == "Tab") {
            event.preventDefault()
            syncSelectionFromDom(editor)
            val backing = viewModel.stateFlow.value.backingState
            val sel = backing?.let { PaneBackingViewModel.selectionOf(it) }
            val multiRow = sel != null && sel.startRow != sel.endRow
            if (event.shiftKey) {
                viewModel.outdentLine()
            } else if (multiRow || viewModel.isBulletLine() || viewModel.isBlockLine()) {
                viewModel.indentLine()
            } else {
                viewModel.insertText("  ")
            }
            return
        }
        if (event.key == "Escape" && viewModel.currentBackingState.searchQuery != null) {
            // An open (empty) search field closes first.
            event.preventDefault()
            viewModel.closeSearch()
            return
        }
        if (event.key == "Escape") {
            // In a block (TRF-5) Escape leaves it onto a new bullet, like
            // Cmd-Enter; elsewhere it zooms out.
            syncSelectionFromDom(editor)
            if (viewModel.isBlockLine()) {
                event.preventDefault()
                viewModel.exitBlock()
                return
            }
            // In 3D mode Escape leaves the mode (the space's own key
            // handler); flying out is Ctrl-Cmd-Up, Back or the breadcrumb.
            if (inSpace) return
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
        if (viewModel.currentBackingState.isReadOnlyPage) return
        if (!syncSelectionFromDom(editor)) return
        val text = viewModel.onCutRequested() ?: return
        event.clipboardData?.setData("text/plain", text)
        event.preventDefault()
    }

    /**
     * Cmd-V: route paste data through `viewModel.insertText`, or — when
     * an image is on the clipboard — through `viewModel.onImagePasted`.
     * A nested list in the clipboard's HTML ([htmlListItems]) is pasted
     * as indented list text, so an outliner's nesting survives.
     *
     * Image MIME inspection happens *before* the plain-text fallback so
     * an OS screenshot tool that also offers a `text/plain` filename
     * doesn't accidentally insert that filename as text. When the
     * clipboard carries both an image and selected text, the image wins
     * — that's the case the user usually means by Cmd-C → Cmd-V from a
     * screenshot tool.
     */
    private fun handlePaste(editor: HTMLElement, event: dynamic) {
        event.preventDefault()
        if (viewModel.currentBackingState.isReadOnlyPage) return
        if (!syncSelectionFromDom(editor)) return
        if (consumeClipboardImage(event)) return
        // A nested list from an outliner: its HTML carries the nesting,
        // its plain text does not (every line flush left).
        val html = event.clipboardData?.getData("text/html")?.unsafeCast<String?>()
        val fromList = html?.let { htmlListItems(it) }?.let { pastedListText(it) }
        val text = fromList ?: event.clipboardData?.getData("text/plain")?.unsafeCast<String?>()
        if (!text.isNullOrEmpty()) viewModel.insertText(text)
    }

    /**
     * Look through `clipboardData.items` for an image; if one is found,
     * read its bytes asynchronously and route through
     * `viewModel.onImagePasted`. Returns `true` when an image was
     * consumed (caller should not fall through to text paste).
     *
     * The filename is generated from the current wall clock so two
     * pastes in the same second still produce distinct filenames after
     * the repository's collision-suffix logic. Extension is chosen from
     * the MIME — falling back to `.png` since that's what every common
     * screenshot tool emits.
     */
    private fun consumeClipboardImage(event: dynamic): Boolean {
        val items = event.clipboardData?.items ?: return false
        val length = (items.length as? Int) ?: return false
        for (i in 0 until length) {
            val item = items[i]
            val kind = item.kind as? String
            val type = item.type as? String
            if (kind != "file" || type == null || !type.startsWith("image/")) continue
            val file = item.getAsFile() ?: continue
            val suggested = buildPastedImageName(type)
            scope.launch {
                val buffer = (file.arrayBuffer() as kotlin.js.Promise<dynamic>).await()
                val bytes = uint8ArrayToByteArray(js("new Uint8Array(buffer)"))
                viewModel.onImagePasted(suggested, bytes)
            }
            return true
        }
        return false
    }

    /**
     * Build a stable filename of the form `Pasted-YYYY-MM-DD-HH-MM-SS.<ext>`
     * for a clipboard image MIME like `image/png`. The repository adds a
     * `-2`/`-3`/… suffix on collision.
     */
    private fun buildPastedImageName(mime: String): String {
        val ext = when (mime.lowercase()) {
            "image/png" -> ".png"
            "image/jpeg", "image/jpg" -> ".jpg"
            "image/gif" -> ".gif"
            "image/webp" -> ".webp"
            "image/svg+xml" -> ".svg"
            else -> ".png"
        }
        val now: dynamic = js("new Date()")
        fun two(v: Int): String = if (v < 10) "0$v" else v.toString()
        val stamp = "" +
            (now.getFullYear() as Int) + "-" +
            two((now.getMonth() as Int) + 1) + "-" +
            two(now.getDate() as Int) + "-" +
            two(now.getHours() as Int) + "-" +
            two(now.getMinutes() as Int) + "-" +
            two(now.getSeconds() as Int)
        return "Pasted-$stamp$ext"
    }

    /**
     * Drop handler for image files dragged from the OS. Shares the
     * filename generation + bytes-to-disk pipeline with paste. Drops
     * are routed through the cursor position the browser placed before
     * the drop event fired, so the markdown lands where the user aimed.
     */
    private fun handleDrop(editor: HTMLElement, event: dynamic) {
        if (viewModel.currentBackingState.isReadOnlyPage) return
        val dt = event.dataTransfer ?: return
        val files = dt.files ?: return
        val length = (files.length as? Int) ?: return
        if (length == 0) return
        var consumedAny = false
        for (i in 0 until length) {
            val file = files[i]
            val type = file.type as? String ?: continue
            if (!type.startsWith("image/")) continue
            consumedAny = true
            val suggested = buildPastedImageName(type)
            scope.launch {
                val buffer = (file.arrayBuffer() as kotlin.js.Promise<dynamic>).await()
                val bytes = uint8ArrayToByteArray(js("new Uint8Array(buffer)"))
                viewModel.onImagePasted(suggested, bytes)
            }
        }
        if (consumedAny) {
            event.preventDefault()
            syncSelectionFromDom(editor)
        }
    }

    /** Returns `true` when [dataTransfer] carries at least one file-typed
     *  entry advertised as an image. Inspected during `dragover` so we
     *  can call `preventDefault` only for image drops (other drops keep
     *  their default behavior). */
    private fun carriesDroppableImage(dataTransfer: dynamic): Boolean {
        val items = dataTransfer.items ?: return false
        val length = (items.length as? Int) ?: return false
        for (i in 0 until length) {
            val item = items[i]
            val kind = item.kind as? String
            val type = item.type as? String
            if (kind == "file" && type != null && type.startsWith("image/")) return true
        }
        return false
    }

    /** Copy a JS Uint8Array's bytes into a Kotlin ByteArray. */
    private fun uint8ArrayToByteArray(u8: dynamic): ByteArray {
        val len = (u8.length as Int)
        val out = ByteArray(len)
        for (i in 0 until len) {
            // JS uint range 0..255; Kotlin Byte is signed -128..127.
            val b = (u8[i] as Int) and 0xFF
            out[i] = b.toByte()
        }
        return out
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
        // Multiple model cols can map to the same display position when
        // they sit on either side of a hidden marker run (open `**`,
        // close `**`, line-prefix `# `, …). The DOM round-trip lands on
        // a canonical representative — usually past the close markers —
        // so a no-op DOM sync would silently jump the caret out of any
        // styled span we just opened. Keep the current model position
        // when it's visually identical to the DOM-derived one.
        viewModel.setSelection(anchor.first, anchor.second, focus.first, focus.second)
        return true
    }

    /**
     * Focuses the editor and drops the caret at the end of the last
     * visible row, then syncs that position back into the model.
     *
     * ### Callers
     * The scroll-wrapper `mousedown` handler wired in [render], fired when
     * the user clicks the empty background *below* the rows. The editor is
     * only as tall as its content, so on a short (or empty, single-blank-
     * line) document the pane is mostly non-editable scroll region:
     * native `contenteditable` caret placement only fires for clicks that
     * land on a row, so those clicks would otherwise do nothing and leave
     * the user with no caret and no obvious way to start typing. This
     * routes "click the dead space" to "put the caret at the end of the
     * document", matching the behaviour of every other text editor.
     */
    private fun focusEditorAtLastRow() {
        val editor = editorElement ?: return
        editor.focus()
        val rows = editor.querySelectorAll("[data-row]")
        val lastRow = if (rows.length > 0) rows.item(rows.length - 1) as? HTMLElement else null
        // Prefer the editable text span so the caret lands in a real caret
        // slot (empty rows carry a `<br>`/ZWSP placeholder run inside it).
        val target: Node = (lastRow?.querySelector(".lunarbor-text") as? HTMLElement) ?: lastRow ?: editor
        // `getSelection`/`Selection` aren't in the Kotlin/JS window binding
        // used here, so go dynamic — same approach as syncSelectionFromDom.
        val sel = window.asDynamic().getSelection() ?: return
        val range = document.createRange()
        range.selectNodeContents(target)
        range.collapse(false)
        sel.removeAllRanges()
        sel.addRange(range)
        syncSelectionFromDom(editor)
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
        // The editable region is the `.lunarbor-text` span. Caret offsets
        // anywhere outside it (e.g. on the bullet prefix or the row div
        // itself) snap to the start of the text region.
        val textSpan = rowDiv.querySelector(".lunarbor-text") as? HTMLElement
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
        // Inline images render as zero-display-char atoms, so a click
        // *past* an image collapses to the same display column as a
        // click *before* it — model col would land at the leading `!`
        // either way. Add up the source-side length of every image
        // child the click skipped over so the caret lands after the
        // closing `)` instead.
        val imagePastCols = imageSourceColsPastOffset(textSpan, node, offset)
        return row to (prefixLen + editableCol + imagePastCols)
    }

    /**
     * Sum the `data-img-source-len` of every image child the click
     * landed past. Returns 0 when the click was inside a non-image
     * run (the existing display→model machinery is fully correct in
     * that case).
     *
     * "Past" means: the [node] is the `.lunarbor-text` wrapper itself
     * and [offset] is a child index that includes one or more image
     * spans, OR the [node] is an inline-style run that follows an
     * image in the wrapper.
     */
    private fun imageSourceColsPastOffset(textSpan: HTMLElement, node: Node, offset: Int): Int {
        val children = textSpan.children
        // Determine the upper bound (exclusive) of children we walked
        // past. When the click target is the wrapper, that's the
        // offset itself. When the click target is a child run, it's
        // the index of that child.
        val cap: Int = if (node === textSpan) {
            offset.coerceAtMost(children.length)
        } else {
            var found = -1
            for (i in 0 until children.length) {
                val child = children.item(i) ?: continue
                if (child === node || child.contains(node)) { found = i; break }
                // Anchor sometimes lands on a deeper descendant; the
                // outer iteration's `contains` check catches it.
            }
            if (found < 0) return 0 else found
        }
        var sum = 0
        for (i in 0 until cap) {
            val child = children.item(i) as? Element ?: continue
            val len = child.getAttribute("data-img-source-len")?.toIntOrNull() ?: continue
            sum += len
        }
        return sum
    }

    /**
     * Walks the children of [textSpan] (each a `.lunarbor-text-run`
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

    /**
     * If [ev] hit a span carrying a `data-href` attribute (set by
     * [OutlinePaintLoop] for inline markdown links) whose href has an
     * external URL scheme, open it in the OS default browser, suppress
     * the default caret placement, and return `true` so the caller skips
     * its remaining mousedown handling.
     *
     * Returns `false` (and does nothing) when the press did not land on
     * an external link, so internal `.md` refs and plain text still get
     * their default contenteditable caret behavior.
     */
    private fun handleExternalLinkMouseDown(ev: MouseEvent): Boolean {
        // Only act on the primary button; let middle/right clicks fall
        // through to the browser so context menus and "open in tab"
        // gestures still work.
        val gesture = openGestureOf(ev)
        if (gesture == OpenGesture.NONE) return false
        val target = ev.target as? Node ?: return false
        val href = ancestorHref(target) ?: return false
        if (!isExternalUrl(href)) return false
        // preventDefault stops contenteditable from placing the caret in
        // the link span; without it the first press would just move the
        // cursor and the URL would not open until the second press.
        ev.preventDefault()
        ev.stopPropagation()
        // The Mac's Ctrl-click is a right-click: its `contextmenu` follows,
        // and the press itself opens nothing.
        if (gesture == OpenGesture.CONTEXT_MENU) return true
        // `noopener,noreferrer` makes the new context independent of this
        // window — required by Electron's `setWindowOpenHandler` contract
        // (so main.js can route to `shell.openExternal`) and best practice
        // on the plain web build too.
        window.open(href, "_blank", "noopener,noreferrer")
        return true
    }

    private fun ancestorHref(node: Node): String? {
        var n: Node? = node
        while (n != null) {
            if (n is Element && n.hasAttribute("data-href")) {
                return n.getAttribute("data-href")
            }
            n = n.parentNode
        }
        return null
    }

    /**
     * If [ev] hit a span carrying a vault link (`data-href`,
     * also set on a resolved wiki link), acts on it by [OpenGesture] and
     * suppresses the default contenteditable caret placement:
     * - plain press → the pane's [MainViewModel.navigateToLink];
     * - Shift- / ⌘-press (Ctrl off the Mac) → [onOpenLinkInNewPane], this
     *   pane stays put, and the trailing click is swallowed
     *   ([swallowTrailingClick]) so the toolkit does not raise this pane
     *   back over the new one;
     * - the Mac's Ctrl-press → nothing: the `contextmenu` that follows
     *   opens the new window ([handleOpenInNewPaneContextMenu]).
     *
     * Returns `true` when the event was handled. A broken link (drawn
     * struck through) is still routed: navigation does nothing, and the
     * click re-checks the target. Plain `#section` anchors and bare
     * relative paths fall through, so the press lands as a normal caret
     * place. Called by the editor's and the page title's `mousedown`.
     */
    private fun handleLunarborLinkMouseDown(ev: MouseEvent): Boolean {
        val gesture = openGestureOf(ev)
        if (gesture == OpenGesture.NONE) return false
        val target = ev.target as? Node ?: return false
        val href = ancestorHref(target) ?: return false
        if (!LunarborLink.isRooted(href)) return false
        ev.preventDefault()
        ev.stopPropagation()
        val inNewPane = onOpenLinkInNewPane
        when {
            gesture == OpenGesture.CONTEXT_MENU -> {}
            gesture == OpenGesture.NEW_WINDOW && inNewPane != null -> {
                inNewPane(href)
                swallowTrailingClick()
            }
            else -> viewModel.navigateToLink(href)
        }
        return true
    }

    /**
     * Right-click (or the Mac's Ctrl-click, which fires `contextmenu`) on
     * a vault link or a bullet's dot: opens the link's target, or
     * the dot's item, in a new window (through the host's
     * [onOpenLinkInNewPane] / [onOpenLocationInNewPane]). Returns `true`
     * when it did, so the context menu is suppressed.
     */
    private fun handleOpenInNewPaneContextMenu(ev: MouseEvent): Boolean {
        val target = ev.target as? Element ?: return false
        val href = ancestorHref(target)
        if (href != null && LunarborLink.isRooted(href)) {
            val open = onOpenLinkInNewPane ?: return false
            open(href)
            return true
        }
        if (target.closest(".lunarbor-bullet-prefix") == null) return false
        val row = target.closest("[data-row]")?.getAttribute("data-row")?.toIntOrNull() ?: return false
        val location = viewModel.locationOfRow(row) ?: return false
        val open = onOpenLocationInNewPane ?: return false
        open(location)
        return true
    }

    /** The popup that opens on a link the pointer rests on ([LinkHoverPopup]). */
    private val linkHoverPopup: LinkHoverPopup by lazy {
        LinkHoverPopup(viewModel, scope, focusEditor = ::focusEditor)
    }

    /**
     * Single image-resize popover owned by this editor instance. Reused
     * across image clicks — opening on a new image swaps the anchor.
     */
    private val imageResizePopover: ImageResizePopover by lazy {
        ImageResizePopover(onApply = { newWidth ->
            val anchor = pendingResizeAnchor ?: return@ImageResizePopover
            val src = anchor.getAttribute("data-img-src") ?: return@ImageResizePopover
            val row = ancestorRowDiv(anchor)?.getAttribute("data-row")?.toIntOrNull()
                ?: return@ImageResizePopover
            pendingResizeAnchor = null
            viewModel.setImageWidth(row, src, newWidth)
        })
    }

    /** Anchor element captured when opening the popover so the apply
     *  callback can look up its row/src without another DOM walk. */
    private var pendingResizeAnchor: HTMLElement? = null

    /**
     * If [ev] hit an inline image span (carrying `data-img-src`),
     * navigate the pane to the image's read-only viewer — or, for an
     * embedded drawing, the drawing editor — instead of placing the caret. Returns `true` when the event was handled.
     *
     * The earlier `handleImageResizeMouseDown` dispatch (line ~358) wins
     * for events that land on the bottom-right resize handle, so
     * dragging to resize still works. Pixel-perfect resize via the
     * popover is no longer reachable from a plain click; the popover
     * machinery is left parked in case a future affordance wants to
     * reopen it.
     */
    private fun handleImageMouseDown(ev: MouseEvent): Boolean {
        if (ev.button.toInt() != 0) return false
        val target = ev.target as? Node ?: return false
        val imgSpan = ancestorImageSpan(target) ?: return false
        val src = imgSpan.getAttribute("data-img-src") ?: return false
        ev.preventDefault()
        ev.stopPropagation()
        // The src is relative to the row's folder (or vault-rooted); an
        // external URL has no vault file to open.
        val row = ancestorRowDiv(imgSpan)?.getAttribute("data-row")?.toIntOrNull()
        val file = if (row != null) viewModel.resolveImageSrc(row, src) else ImagePaths.resolve("", src)
        if (file != null) viewModel.navigateToVaultFile(file)
        return true
    }

    /**
     * If [ev] hit an image's resize-handle, begin a document-level
     * drag that previews the new width on the `<img>` style and
     * commits the final width to the markdown source on mouseup.
     * Returns `true` when the event was consumed.
     */
    private fun handleImageResizeMouseDown(ev: MouseEvent): Boolean {
        if (ev.button.toInt() != 0) return false
        val target = ev.target as? Element ?: return false
        if (!target.classList.contains("lunarbor-image-resize-handle")) return false
        val span = ancestorImageSpan(target) ?: return false
        // The `<img>`, or an embedded drawing's picture span.
        val img = (span.firstChild as? HTMLElement)
            ?: return false
        val src = span.getAttribute("data-img-src") ?: return false
        val rowDiv = ancestorRowDiv(span) ?: return false
        val row = rowDiv.getAttribute("data-row")?.toIntOrNull() ?: return false
        ev.preventDefault()
        ev.stopPropagation()

        val startX = ev.clientX.toDouble()
        val startWidth = img.getBoundingClientRect().width
        span.classList.add("is-resizing")

        var moveHandler: ((Event) -> Unit)? = null
        var upHandler: ((Event) -> Unit)? = null

        val onMove: (Event) -> Unit = { e ->
            val me = e as MouseEvent
            val dx = me.clientX.toDouble() - startX
            // Clamp so the user can't drag below a sensible minimum
            // or past anything the screen could plausibly show.
            val newWidth = (startWidth + dx).coerceIn(20.0, 4000.0)
            img.style.width = "${newWidth.toInt()}px"
        }
        val onUp: (Event) -> Unit = onUp@{ _ ->
            document.removeEventListener("mousemove", moveHandler!!, /* capture = */ true)
            document.removeEventListener("mouseup", upHandler!!, /* capture = */ true)
            span.classList.remove("is-resizing")
            val finalWidth = img.getBoundingClientRect().width.toInt()
            // No-op if the user didn't actually drag (single click on
            // the handle); the popover-click branch handles intentional
            // pixel-perfect input.
            if (kotlin.math.abs(finalWidth - startWidth.toInt()) < 2) return@onUp
            viewModel.setImageWidth(row, src, finalWidth)
        }
        moveHandler = onMove
        upHandler = onUp
        // Capture-phase listeners on the document so the drag survives
        // mouse motion outside the editor (e.g. dragging across the
        // app chrome to make the image very wide).
        document.addEventListener("mousemove", onMove, /* capture = */ true)
        document.addEventListener("mouseup", onUp, /* capture = */ true)
        return true
    }

    /** Walk up from [node] to the nearest `lunarbor-md-image` span. */
    private fun ancestorImageSpan(node: Node): HTMLElement? {
        var n: Node? = node
        while (n != null) {
            if (n is Element && n.hasAttribute("data-img-src")) return n as HTMLElement
            n = n.parentNode
        }
        return null
    }

    private fun isExternalUrl(href: String): Boolean {
        // Match the small set of URL schemes that mean "leave the app" —
        // anything else (including bare relative paths to other notes) is
        // treated as an internal reference and left for native handling.
        val lower = href.lowercase()
        return lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("mailto:") ||
            lower.startsWith("tel:") ||
            lower.startsWith("ftp://") ||
            lower.startsWith("ftps://")
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
    private fun reconcile(editor: HTMLElement, state: PaneBackingViewModel.State) {
        val scroller = scrollWrapperElement ?: editor
        val savedScrollTop = scroller.scrollTop
        // A search or link page is read-only (State.isReadOnlyPage): no caret to type at.
        editor.setAttribute("contenteditable", if (state.isReadOnlyPage) "false" else "true")
        // A board's text field (LBR-29) is put back after the rebuild, with its selection.
        lunicleBoardCursor.saveField()
        paint(editor, state, viewModel, style, onBulletMouseDown = { row, ev ->
            beginDragFromBullet(row, ev)
        })
        scroller.scrollTop = savedScrollTop
        // The hovered row's −/+ and dot stay put instead of blinking (LBR-17).
        carryHoverAcrossRepaint(editor)
        // The arrow keys' highlight on a search node's results survives the rebuild.
        searchNodeHitCursor.applyHighlight(editor)
        // And the board cursor's row (LBR-28), found again by its key.
        lunicleBoardCursor.applyHighlight(editor)

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
        // The browser has a single `window.getSelection()` shared across the
        // whole document. If multiple panes view the same backing document,
        // every pane's reconcile fires on every edit — letting an unfocused
        // pane call `setBaseAndExtent` here would yank the live caret out
        // of the pane the user is typing in and drop it into this one.
        // Restrict the DOM-side caret update to either (a) this pane owns
        // focus, or (b) the existing window selection is already inside
        // this editor (initial mount / not-yet-focused case where activeElement
        // is still <body>).
        val active = document.activeElement
        // Typing in a text field (the search field, a modal's input): the
        // document selection must stay out of the editor.
        if (active is org.w3c.dom.HTMLInputElement) return
        val ownsFocus = active != null && editor.contains(active)
        val selectionInsideUs = run {
            val sel = window.asDynamic().getSelection() ?: return@run false
            if ((sel.rangeCount as Number).toInt() == 0) return@run false
            val anchorNode = sel.anchorNode ?: return@run false
            editor.contains(anchorNode)
        }
        if (!ownsFocus && !selectionInsideUs) return
        // If the requested row isn't currently rendered (e.g. it sits inside
        // a collapsed subtree the caller didn't reveal), fall back to (0, 0)
        // rather than early-returning. Leaving the caret wherever the browser
        // parked it when the previous row's element was removed lets the next
        // Backspace fall through to the browser default (history.back), which
        // navigates the SPA out of the current file. Anchoring to a visible
        // slot keeps the caret inside the contenteditable host.
        val anchor = locateDomPosition(editor, anchorRow, anchorCol)
            ?: locateDomPosition(editor, 0, 0)
            ?: return
        val focus = locateDomPosition(editor, cursorRow, cursorCol)
            ?: locateDomPosition(editor, 0, 0)
            ?: return
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
        val textSpan = rowDiv.querySelector(".lunarbor-text") as? HTMLElement ?: return null
        val editableCol = (col - prefixLen).coerceAtLeast(0)

        // Walk run spans by their `data-src-start`/`data-src-end` range
        // (editable-relative SOURCE columns, stamped by the paint loop).
        // Hidden marker chars (`**`, a link's `](href)` tail, a line
        // prefix like `# `) belong to no run, so they live in the gaps
        // between consecutive ranges — a model column inside a gap
        // anchors at the start of the next run, mirroring how
        // `RowColumnMap.modelToDom` collapses marker columns. Walking by
        // `textContent.length` instead would treat model columns as
        // display columns and paint the caret shifted right by every
        // hidden marker char to its left.
        //
        // Inline-image runs carry zero display chars but a wide source
        // range. A column inside (or in the gap before) an image anchors
        // on the wrapper at the image's child index — image atoms can't
        // host a caret. A column at/past the image's source end falls
        // through to the next sibling, which matters because Chromium
        // refuses to paint a caret at "wrapper offset i+1" when child
        // `i+1` lacks a text node; falling through lets us anchor INSIDE
        // the zero-width-space placeholder when present.
        val children = textSpan.children
        for (i in 0 until children.length) {
            val child = children.item(i) as? HTMLElement ?: continue
            val srcStart = child.getAttribute("data-src-start")?.toIntOrNull() ?: continue
            val srcEnd = child.getAttribute("data-src-end")?.toIntOrNull() ?: continue
            if (editableCol >= srcEnd) continue
            if (child.hasAttribute("data-img-source-len")) return textSpan to i
            val textNode = child.firstChild?.takeIf { it.nodeType.toInt() == 3 }
                // Empty run span (blank-row `<br>` placeholder).
                // Anchor on the span itself.
                ?: return child to 0
            val maxOffset = textNode.nodeValue?.length ?: 0
            return textNode to (editableCol - srcStart).coerceIn(0, maxOffset)
        }
        // Past the end — drop to the last text node, or the wrapper.
        val lastChild = textSpan.lastElementChild
        val lastTextNode = lastChild?.firstChild?.takeIf { it.nodeType.toInt() == 3 }
        return if (lastTextNode != null) {
            lastTextNode to (lastTextNode.nodeValue?.length ?: 0)
        } else {
            // Anchor on the wrapper at the very end. With our trailing
            // `<br>` placeholder this gives the browser a caret slot
            // past every image / atomic glyph on the line.
            textSpan to children.length.toInt()
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
        title.className = "lunarbor-title"
        // Box / typography geometry lives inline because it depends on
        // the [style] payload (per-app padding + font family). Font
        // size, weight, and line-height live in CSS (injected by
        // `ensureStyles` in OutlinePaintLoop) so the line-level style
        // classes — `lunarbor-title-h1` … `lunarbor-title-quote` — can
        // override them per-heading without specificity tricks.
        title.style.apply {
            flex = "0 0 auto"
            paddingTop = "6px"
            paddingBottom = "2px"
            paddingLeft = "${style.editorPaddingLeftPx}px"
            paddingRight = "${style.editorPaddingRightPx}px"
            // The Display font (App settings → Appearance), else the editor's.
            fontFamily = "var(--dt-font-display, ${style.fontFamily})"
            setProperty("white-space", "nowrap")
            setProperty("overflow", "hidden")
            setProperty("text-overflow", "ellipsis")
            color = "var(--t-text, #e6e6e6)"
        }
        return title
    }

    /**
     * Makes a note's, image's or drawing's title an inline rename field (the view turns
     * `contenteditable` on in [updateTitle] only where
     * `State.canRenameFromTitle`): Enter commits and returns to the
     * editor, Escape reverts, and leaving the field commits too. A commit
     * goes to `MainViewModel.renameActiveFile`, which renames the file
     * (and the hidden `# H1` that repeated the old name).
     */
    private fun wireTitleEditing(title: HTMLElement) {
        // A zoomed bullet's links work in the title as in its row: a press
        // follows it (Shift: a new window), a right-click opens a new window.
        title.addEventListener("mousedown", { event ->
            val me = event as MouseEvent
            if (handleExternalLinkMouseDown(me)) return@addEventListener
            handleLunarborLinkMouseDown(me)
        })
        title.addEventListener("contextmenu", { event ->
            if (handleOpenInNewPaneContextMenu(event as MouseEvent)) {
                event.preventDefault()
                event.stopPropagation()
            }
        })
        // An image or drawing has no editor to hand the focus back to.
        fun leaveTitle() {
            val editor = editorElement
            if (editor != null && !viewModel.currentBackingState.isFileView) editor.focus() else title.blur()
        }
        title.addEventListener("keydown", { event ->
            val ke = event as KeyboardEvent
            when (ke.key) {
                "Enter" -> {
                    ke.preventDefault()
                    ke.stopPropagation()
                    leaveTitle()
                }
                "Escape" -> {
                    ke.preventDefault()
                    ke.stopPropagation()
                    titleEditCancelled = true
                    leaveTitle()
                }
            }
        })
        title.addEventListener("blur", { _ ->
            val cancelled = titleEditCancelled
            titleEditCancelled = false
            val backing = viewModel.currentBackingState
            val typed = (title.textContent ?: "").replace('\n', ' ').trim()
            val current = NoteRepository.displayNameOf(backing.activeFileRel)
            if (!cancelled && backing.canRenameFromTitle && typed.isNotEmpty() && typed != current) {
                val from = backing.activeFileRel
                pendingRename = from to typed
                window.setTimeout({
                    if (pendingRename?.first == from) {
                        pendingRename = null
                        updateTitle(title, viewModel.stateFlow.value.backingState)
                    }
                }, RENAME_PENDING_MS)
                viewModel.renameActiveFile(typed)
            }
            updateTitle(title, viewModel.stateFlow.value.backingState)
        })
    }

    /**
     * Full set of line-level style classes the headline can wear. Used
     * by [updateTitle] to clear stale classes before applying the one
     * matching the current zoom target's style — kept here so additions
     * to [LineStyle] only need to be reflected in two places (this
     * companion constant + the `when` in `updateTitle`).
     */
    private val titleStyleClasses = listOf(
        "lunarbor-title-h1",
        "lunarbor-title-h2",
        "lunarbor-title-h3",
        "lunarbor-title-h4",
        "lunarbor-title-h5",
        "lunarbor-title-h6",
        "lunarbor-title-quote",
    )

    /**
     * Refreshes the headline text from [backing]. Shows the leaf segment of
     * the zoom path when zoomed (matching the trailing breadcrumb segment
     * in the pane chrome), or the active file name when at document root.
     * Renders empty while the document hasn't loaded yet so the headline
     * doesn't flash incorrect copy during boot.
     *
     * When zoomed into a bullet that has a line-level style (heading
     * level or quote), the wrapper gets a corresponding `lunarbor-title-*`
     * class so the headline visually reflects that style. Inline styles
     * inside the title (`**bold**`, `*italic*`, etc.) are rendered as
     * styled child spans using the same `lunarbor-md-*` classes the
     * editor's paint loop uses.
     */
    private fun updateTitle(title: HTMLElement, backing: PaneBackingViewModel.State?) {
        // Never repaint over a title the user is typing in.
        if (document.activeElement === title) return
        val editable = backing?.canRenameFromTitle == true
        title.setAttribute("contenteditable", if (editable) "plaintext-only" else "false")
        if (editable) {
            title.classList.add(TITLE_EDITABLE_CLASS)
            title.setAttribute("spellcheck", "false")
            title.title = "Click to rename"
        } else {
            title.classList.remove(TITLE_EDITABLE_CLASS)
            title.removeAttribute("title")
        }
        val pending = pendingRename
        if (pending != null && backing != null && backing.activeFileRel == pending.first) {
            applyTitleStyleClass(title, null)
            title.textContent = pending.second
            return
        }
        pendingRename = null
        // Resolve text + line-level style. The non-zoomed case shows the
        // active file's display name (NoteRepository.displayNameOf);
        // the zoomed case shows the leaf bullet's prefix-stripped text
        // plus its line-level style.
        val (text, style) = when {
            backing == null -> "" to null
            // Image and drawing views have no document to draw a title
            // from — show the file name verbatim (extension included so the
            // user can tell `photo.png` from `photo.jpg`); editing it renames.
            backing.isFileView -> {
                applyTitleStyleClass(title, null)
                title.textContent = NoteRepository.displayNameOf(backing.activeFileRel).ifBlank { "Untitled" }
                return
            }
            !backing.isLoaded -> "" to null
            else -> viewModel.zoomInfo(backing)?.let { zoom ->
                zoom.titleText.ifBlank { "(untitled)" } to zoom.style
            } ?: run {
                // A file name is not Markdown: shown (and edited) verbatim.
                val fileRel = backing.activeFileRel
                val fileName = NoteRepository.displayNameOf(fileRel).ifBlank { "Untitled" }
                applyTitleStyleClass(title, null)
                title.textContent = fileName
                appendDailyTemplateLabel(title, backing)
                return
            }
        }
        applyTitleStyleClass(title, style)
        // Images in a zoomed headline resolve against the zoom row's folder.
        val zoomRow = backing?.takeIf { it.isLoaded }?.let { viewModel.zoomInfo(it)?.zoomRow }
        renderInlineRuns(
            title, text, baseRunClass = null,
            linkResolver = { url -> if (zoomRow != null) viewModel.linkHrefOf(zoomRow, url) else url },
        ) { src ->
            if (zoomRow != null) viewModel.resolveImageSrc(zoomRow, src) else ImagePaths.resolve("", src)
        }
        // A search node's page: its match count after the magnifier, as on
        // its line in the parent.
        if (backing != null && zoomRow != null) {
            viewModel.searchNodeOf(backing, zoomRow)?.let { view ->
                val count = document.createElement("span") as HTMLElement
                count.className = "lunarbor-title-search-count"
                count.setAttribute("contenteditable", "false")
                count.textContent = searchCountText(view.result)
                title.appendChild(count)
            }
            // A board node's page (LBR-27): its sync indicator, as on its line.
            viewModel.lunicleBoardOf(backing, zoomRow)?.let { view ->
                val holder = document.createElement("span") as HTMLElement
                holder.className = "lunarbor-title-lunicle-sync"
                holder.setAttribute("contenteditable", "false")
                holder.appendChild(buildLunicleSyncIndicator(view))
                title.appendChild(holder)
            }
        }
        if (backing != null) appendDailyTemplateLabel(title, backing)
    }

    /**
     * Appends the "Daily template" pill to the page [title] when the page
     * is the daily template ([MainViewModel.isDailyTemplatePage], LBR-21):
     * chrome, not content — not editable, not selectable, never saved.
     * Called by [updateTitle] after it has filled the title.
     */
    private fun appendDailyTemplateLabel(title: HTMLElement, backing: PaneBackingViewModel.State) {
        if (!viewModel.isDailyTemplatePage(backing)) return
        val label = document.createElement("span") as HTMLElement
        label.className = "lunarbor-title-daily-template"
        label.setAttribute("contenteditable", "false")
        label.title = "New journal days start as a copy of this page's items"
        label.textContent = "Daily template"
        title.appendChild(label)
    }

    /**
     * Tokenizes [text] and rebuilds [parent]'s children so inline markers
     * (`**bold**`, `*italic*`, `` `code` ``, `~~strike~~`, `[label](href)`,
     * `#tag`) render as styled spans instead of literal punctuation.
     * Reuses the global `lunarbor-md-*` classes via [inlineRunCssClasses]
     * so the same stylesheet that drives the editor's paint loop also
     * drives this rendering — single source of truth for inline styling.
     *
     * @param parent     element to clear + repopulate.
     * @param text       source text (line-level prefix already stripped).
     * @param baseRunClass optional base class added to every run span. The
     *   editor uses `lunarbor-text-run` so its caret-mapping code can
     *   walk the spans; the headline passes `null` and just gets the
     *   style classes.
     * @param linkResolver maps a link's destination as written to the
     *   href the app uses (`MainViewModel.linkHrefOf` for the zoom row).
     * @param imageResolver maps an inline image `src` to its vault file
     *   (see `createImageRunElement`).
     */
    private fun renderInlineRuns(
        parent: HTMLElement,
        text: String,
        baseRunClass: String?,
        linkResolver: (String) -> String = { it },
        imageResolver: (String) -> String?,
    ) {
        val tokenized = InlineMarkdownTokenizer.tokenize(text)
        parent.innerHTML = ""
        if (tokenized.runs.isEmpty()) {
            parent.appendChild(document.createTextNode(tokenized.displayText))
            return
        }
        for (run in tokenized.runs) {
            if (run.imageSrc != null) {
                parent.appendChild(createImageRunElement(run, baseRunClass = baseRunClass, imageResolver = imageResolver))
                continue
            }
            val span = document.createElement("span") as HTMLElement
            val classes = inlineRunCssClasses(
                run.styles,
                isLink = run.linkHref != null,
                isTag = run.isTag,
                isSearch = run.isSearchQuery,
                // A board node's reference (LBR-27): its icon, only where there is Lunicle.
                isLunicle = run.isLunicleQuery && viewModel.currentBackingState.lunicleEnabled,
            )
            val full = if (baseRunClass == null) classes
                else if (classes.isEmpty()) listOf(baseRunClass)
                else listOf(baseRunClass) + classes
            if (full.isNotEmpty()) span.className = full.joinToString(" ")
            if (run.isTag) span.style.setProperty("--tag-h", tagHue(run.text).toString())
            // Followed on press by the title's link handlers ([wireTitleEditing]).
            run.linkHref?.let { span.setAttribute("data-href", linkResolver(it)) }
            span.textContent = run.text
            parent.appendChild(span)
        }
    }

    /**
     * Replaces [title]'s line-level style class with the one matching
     * [style] (or none for plain text / non-zoomed). The full set of
     * candidate classes lives in [titleStyleClasses] so additions to
     * [LineStyle] only need to be reflected here and in the `when`.
     */
    private fun applyTitleStyleClass(title: HTMLElement, style: LineStyle?) {
        val styleClass = when (style) {
            LineStyle.HEADING_1 -> "lunarbor-title-h1"
            LineStyle.HEADING_2 -> "lunarbor-title-h2"
            LineStyle.HEADING_3 -> "lunarbor-title-h3"
            LineStyle.HEADING_4 -> "lunarbor-title-h4"
            LineStyle.HEADING_5 -> "lunarbor-title-h5"
            LineStyle.HEADING_6 -> "lunarbor-title-h6"
            LineStyle.QUOTE -> "lunarbor-title-quote"
            null -> null
        }
        for (cls in titleStyleClasses) {
            if (cls != styleClass) title.classList.remove(cls)
        }
        if (styleClass != null && !title.classList.contains(styleClass)) {
            title.classList.add(styleClass)
        }
    }

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
    private fun isNavigationTransition(backing: PaneBackingViewModel.State?): Boolean {
        if (backing == null) return false
        val previous = lastNavSignature ?: return false
        return !isSameLocation(previous, navSignatureOf(backing))
    }

    /**
     * `true` when [before] and [after] are the same pane location — equal,
     * or the same note under the name it was just renamed to
     * (`MainViewModel.renamedTo`), which is not a navigation and plays
     * no animation.
     */
    private fun isSameLocation(before: Pair<String?, LineId?>, after: Pair<String?, LineId?>): Boolean {
        if (before == after) return true
        val from = before.first ?: return false
        return before.second == after.second && viewModel.renamedTo(from) == after.first
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
    private fun rememberNavSignature(backing: PaneBackingViewModel.State?) {
        lastNavSignature = backing?.let { navSignatureOf(it) }
    }

    /** The `(activeFileRel, zoomedLineId)` pair that identifies a pane location. */
    private fun navSignatureOf(backing: PaneBackingViewModel.State): Pair<String?, LineId?> =
        Pair(backing.activeFileRel, backing.zoomedLineId)

    /**
     * Captures the pane's outgoing view **before** the toolkit's
     * `LayoutRenderer.render()` wipes the floating-pane container. Called
     * by `AppShell` from its per-pane navigation observer right before
     * it rebuilds the pane chrome.
     *
     * The snapshot overlay lives on `document.body`, which the rebuild
     * does not touch, so it covers the pane in the brief window between
     * the container wipe and `render` re-attaching the MainScreen
     * elements. Without it a single-frame paint can leak the page
     * background as a visible blink before the animation starts.
     *
     * A no-op when a capture is already pending (back-to-back navigation)
     * and when this screen's collector has already handled the current
     * navigation — capturing then would snapshot the *new* view.
     */
    fun prepareNavigationCrossfade() {
        if (pendingOutgoing != null || inSpace) return
        val backing = viewModel.stateFlow.value.backingState ?: return
        val last = lastNavSignature ?: return
        if (isSameLocation(last, navSignatureOf(backing))) return
        pendingOutgoing = captureOutgoing(backing)
    }

    /**
     * Snapshots the view on screen ([lastPaintedState]) as it leaves for
     * [next], and picks the animation:
     *
     *  - another file (or image view) → [NavigationKind.OTHER_FILE];
     *  - deeper in the same outline → [NavigationKind.ZOOM_IN], morphing
     *    from the new target's row in the outgoing view (the capture falls
     *    back to [NavigationKind.DEEPER] when that row isn't painted);
     *  - shallower → [NavigationKind.ZOOM_OUT], morphing into the old
     *    target's row in the new view ([NavigationKind.SHALLOWER] when it
     *    has no row there, and `play` falls back when it isn't painted);
     *  - same depth → [NavigationKind.SHALLOWER] (a sideways jump).
     *
     * @return `null` when the pane is not mounted yet.
     */
    private fun captureOutgoing(next: PaneBackingViewModel.State): OutgoingView? {
        val root = rootElement ?: return null
        val title = titleElement ?: return null
        val editor = editorElement ?: return null
        val prev = lastPaintedState
        val sameOutline = prev != null && prev.activeFileRel == next.activeFileRel &&
            !prev.isFileView && !next.isFileView && prev.isLoaded && next.isLoaded
        var morphRow: Int? = null
        val kind = if (prev == null || !sameOutline) {
            NavigationKind.OTHER_FILE
        } else {
            val prevDepth = viewModel.zoomPathSegments(prev).size
            val nextDepth = viewModel.zoomPathSegments(next).size
            when {
                nextDepth > prevDepth -> {
                    morphRow = next.zoomedLineId
                        ?.let { prev.documentState?.lineIds?.indexOf(it) }
                        ?.takeIf { it >= 0 }
                    if (morphRow != null) NavigationKind.ZOOM_IN else NavigationKind.DEEPER
                }
                nextDepth < prevDepth -> {
                    morphRow = prev.zoomedLineId
                        ?.let { next.documentState?.lineIds?.indexOf(it) }
                        ?.takeIf { it >= 0 }
                    if (morphRow != null) NavigationKind.ZOOM_OUT else NavigationKind.SHALLOWER
                }
                else -> NavigationKind.SHALLOWER
            }
        }
        return navigationTransition.capture(root, title, editor, kind, morphRow)
    }

    private fun buildRestructureBanner(): HTMLElement {
        val banner = document.createElement("div") as HTMLElement
        banner.className = "lunarbor-restructuring"
        val spinner = document.createElement("div") as HTMLElement
        spinner.className = "lunarbor-restructuring-spinner"
        banner.appendChild(spinner)
        val label = document.createElement("span") as HTMLElement
        label.textContent = "Restructuring…"
        banner.appendChild(label)
        return banner
    }

    private fun installRootStyles(root: HTMLElement) {
        document.documentElement?.let {
            (it as HTMLElement).style.backgroundColor = "var(--t-bg, #1e1e1e)"
        }
        document.body?.let {
            val bodyStyle = it.style
            bodyStyle.margin = "0"
            bodyStyle.padding = "0"
            bodyStyle.backgroundColor = "var(--t-bg, #1e1e1e)"
        }
        root.style.margin = "0"
        root.style.height = "100%"
        root.style.minHeight = "0"
        root.style.display = "flex"
        root.style.flexDirection = "column"
        root.style.backgroundColor = "var(--t-bg, #1e1e1e)"
    }

    /**
     * Builds the scroll wrapper that hosts the editor and the folder
     * contents list stacked vertically. Owns the page's vertical scrollbar
     * so the bullets and the list scroll together.
     */
    private fun buildScrollWrapper(): HTMLElement {
        val wrapper = document.createElement("div") as HTMLElement
        wrapper.className = "lunarbor-scroll"
        wrapper.style.apply {
            flex = "1 1 auto"
            setProperty("min-height", "0")
            setProperty("overflow-x", "hidden")
            setProperty("overflow-y", "auto")
            backgroundColor = "var(--t-bg, #1e1e1e)"
        }
        return wrapper
    }

    private fun buildEditorElement(): HTMLElement {
        val editor = document.createElement("div") as HTMLElement
        editor.className = "lunarbor-editor"
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
            fontSize = style.fontSize
            setProperty("line-height", "${style.lineHeightPx}px")
            // Browser-native wrap: long lines break on word boundaries,
            // explicit newlines split rows (each row is its own div anyway).
            setProperty("white-space", "pre-wrap")
            setProperty("word-break", "break-word")
            outline = "none"
            color = "var(--t-text, #e6e6e6)"
        }
        return editor
    }

    /**
     * Builds the sibling div that hosts the folder contents list. The
     * `contenteditable="false"` attribute keeps caret placement out of
     * this subtree even if a drag-selection sweeps into it, so its rows
     * are never editable text. Padding mirrors the editor's so the list
     * lines up horizontally with the bullets.
     */
    private fun buildFolderContentsElement(): HTMLElement {
        val list = document.createElement("div") as HTMLElement
        list.className = "lunarbor-folder-contents"
        list.setAttribute("contenteditable", "false")
        list.style.apply {
            paddingTop = "${style.editorPaddingTopPx}px"
            paddingRight = "${style.editorPaddingRightPx}px"
            paddingBottom = "${style.editorPaddingBottomPx + 32}px"
            paddingLeft = "${style.editorPaddingLeftPx}px"
            fontFamily = style.fontFamily
            fontSize = style.fontSize
            setProperty("line-height", "${style.lineHeightPx}px")
            setProperty("white-space", "pre-wrap")
            setProperty("word-break", "break-word")
            color = "var(--t-text, #e6e6e6)"
        }
        return list
    }

    /**
     * Builds the sibling div that hosts the read-only image viewer (see
     * `ImageViewer.paintImageViewer`). Hidden at mount; the state
     * collector toggles `display` based on `state.isImageView`. Padding
     * mirrors the editor's so the image sits in the same content gutter
     * the user is used to.
     */
    /**
     * Builds the drawing editor's host ([drawingHostElement]): hidden at
     * mount, a flex child that takes the pane's remaining height when the
     * collector shows it.
     */
    private fun buildDrawingHostElement(): HTMLElement {
        val host = document.createElement("div") as HTMLElement
        host.className = "lunarbor-drawing-host"
        host.setAttribute("contenteditable", "false")
        host.style.apply {
            display = "none"
            flex = "1 1 auto"
            setProperty("min-height", "0")
            position = "relative"
        }
        return host
    }

    private fun buildImageViewerElement(): HTMLElement {
        val viewer = document.createElement("div") as HTMLElement
        viewer.className = "lunarbor-image-viewer"
        viewer.setAttribute("contenteditable", "false")
        viewer.style.apply {
            display = "none"
            paddingTop = "${style.editorPaddingTopPx}px"
            paddingRight = "${style.editorPaddingRightPx}px"
            paddingBottom = "${style.editorPaddingBottomPx}px"
            paddingLeft = "${style.editorPaddingLeftPx}px"
        }
        return viewer
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

    // ----------------------------------------------------------------- drag

    /**
     * In-flight drag state. A press on a bullet glyph (`originMode = Bullet`)
     * or in a selected row's left gutter (`originMode = Selection`) creates
     * a session; window-level mousemove/mouseup listeners drive the rest of
     * the gesture and the session is cleared on release. `null` when no
     * drag is in progress.
     *
     * @property sourceStart First absolute row of the moveable block
     *   (resolved at session start, so subsequent edits cannot shift it
     *   under us).
     * @property sourceEnd Last absolute row of the moveable block
     *   (inclusive).
     * @property originX Initial mousedown clientX, used to detect the
     *   movement threshold that promotes a press into an armed drag, and
     *   as the zero point of the sideways movement that picks the drop
     *   level.
     * @property originY Initial mousedown clientY.
     * @property originMode Whether the gesture began on a bullet glyph or
     *   in a selected row's gutter.
     * @property armed `true` once the pointer has moved more than
     *   [DRAG_THRESHOLD_PX] from the origin. Until armed, the gesture is
     *   indistinguishable from a click and we leave the click handler to
     *   run on release.
     * @property target Where the rows would land now
     *   (`PaneBackingViewModel.dropTarget`): the row they go before and
     *   the level they get. `null` when the pointer is over editor chrome
     *   or no drop is valid there (the release is then a no-op).
     * @property lastX Latest pointer clientX, for the edge auto-scroll
     *   ([dragAutoScrollTick]), which re-aims the drop as rows scroll by.
     * @property lastY Latest pointer clientY.
     */
    private data class DragSession(
        val sourceStart: Int,
        val sourceEnd: Int,
        val originX: Double,
        val originY: Double,
        val originMode: Origin,
        var armed: Boolean = false,
        var target: PaneBackingViewModel.DropTarget? = null,
        var lastX: Double = originX,
        var lastY: Double = originY,
    ) {
        enum class Origin { Bullet, Selection }
    }

    private var dragSession: DragSession? = null
    private var dropIndicator: HTMLElement? = null
    private var dragMoveListener: ((Event) -> Unit)? = null
    private var dragUpListener: ((Event) -> Unit)? = null

    /** `setInterval` handle of the edge auto-scroll while a drag is armed. */
    private var dragScrollTimer: Int? = null

    /**
     * Begins a drag session anchored on the bullet glyph at [absoluteRow]
     * — for a plain primary press ([OpenGesture.HERE]); a Shift- / ⌘-press
     * opens the dot's item in a new window instead
     * ([onOpenLocationInNewPane]), and a right press or the Mac's
     * Ctrl-press is left to the `contextmenu` that follows.
     *
     * Two drag cases:
     * - If there is an active multi-row selection and [absoluteRow] is
     *   inside it, the drag moves the whole selected row range. Grabbing
     *   any bullet within a multi-row selection feels like "drag the thing
     *   I selected", and matches the gutter-drag affordance.
     * - Otherwise resolves the bullet's subtree via
     *   [MainViewModel.subtreeRange] and drags that — the standard
     *   bullet-and-children move.
     *
     * The chosen range is resolved now so a concurrent edit cannot shift
     * it mid-drag. A no-op if [absoluteRow] is not a bullet line and the
     * selection branch does not apply.
     *
     * Called by [OutlinePaintLoop.buildBulletPrefix] through the
     * `onBulletMouseDown` callback we pass to `paint(...)`.
     */
    private fun beginDragFromBullet(absoluteRow: Int, ev: MouseEvent) {
        val backing = viewModel.currentBackingState
        // Markdown mode (TRF-7): rows are text, not movable bullets.
        if (backing.isMarkdownMode) return
        when (openGestureOf(ev)) {
            // A right-click, or the Mac's Ctrl-click: the `contextmenu`
            // that follows opens the item in a new window.
            OpenGesture.NONE, OpenGesture.CONTEXT_MENU -> return
            // Shift- / ⌘-press: the item in a new window, no drag, no zoom.
            OpenGesture.NEW_WINDOW -> {
                val open = onOpenLocationInNewPane
                val location = viewModel.locationOfRow(absoluteRow)
                if (open != null && location != null) {
                    open(location)
                    swallowTrailingClick()
                }
                return
            }
            OpenGesture.HERE -> {}
        }
        val sel = PaneBackingViewModel.selectionOf(backing)
        if (sel != null && sel.startRow != sel.endRow && absoluteRow in sel.startRow..sel.endRow) {
            startDragSession(sel.startRow, sel.endRow, ev, DragSession.Origin.Selection)
            return
        }
        val range = viewModel.subtreeRange(absoluteRow) ?: return
        startDragSession(range.first, range.last, ev, DragSession.Origin.Bullet)
    }

    /**
     * If [ev] is a mousedown inside a selected row's left gutter, begins a
     * row-range drag covering the whole active selection. Otherwise leaves
     * the event alone so the browser handles it as caret/selection input.
     *
     * "Gutter" is operationalized as `clientX < rowRect.left + paddingLeft
     * - 4`, i.e. left of the row's content area with a small tolerance.
     * This is the only zone where a press should be unambiguously
     * interpreted as "grab this whole block" rather than "place caret".
     */
    private fun maybeBeginGutterDrag(editor: HTMLElement, ev: MouseEvent) {
        val backing = viewModel.currentBackingState
        if (backing.isMarkdownMode) return
        val sel = PaneBackingViewModel.selectionOf(backing) ?: return
        val target = ev.target as? Node ?: return
        val rowDiv = ancestorRowDiv(target) ?: return
        val rowIdx = rowDiv.getAttribute("data-row")?.toIntOrNull() ?: return
        if (rowIdx !in sel.startRow..sel.endRow) return
        val rect = rowDiv.getBoundingClientRect()
        val paddingLeft = window.asDynamic().getComputedStyle(rowDiv)
            .getPropertyValue("padding-left").unsafeCast<String?>()
            ?.removeSuffix("px")?.toDoubleOrNull() ?: 0.0
        if (ev.clientX.toDouble() > rect.left + paddingLeft - 4.0) return
        ev.preventDefault()
        startDragSession(sel.startRow, sel.endRow, ev, DragSession.Origin.Selection)
    }

    private fun startDragSession(
        startRow: Int,
        endRow: Int,
        ev: MouseEvent,
        origin: DragSession.Origin,
    ) {
        // Tear down any leftover session — paranoia guard. Real life:
        // mouseup always fires and clears the previous one.
        if (dragSession != null) endDragSession()
        dragSession = DragSession(
            sourceStart = startRow,
            sourceEnd = endRow,
            originX = ev.clientX.toDouble(),
            originY = ev.clientY.toDouble(),
            originMode = origin,
        )
        val moveListener: (Event) -> Unit = { e -> handleDragMove(e as MouseEvent) }
        val upListener: (Event) -> Unit = { e -> handleDragUp(e as MouseEvent) }
        dragMoveListener = moveListener
        dragUpListener = upListener
        window.addEventListener("mousemove", moveListener)
        window.addEventListener("mouseup", upListener)
    }

    private fun endDragSession() {
        dragMoveListener?.let { window.removeEventListener("mousemove", it) }
        dragUpListener?.let { window.removeEventListener("mouseup", it) }
        dragMoveListener = null
        dragUpListener = null
        dragScrollTimer?.let { window.clearInterval(it) }
        dragScrollTimer = null
        document.body?.classList?.remove(DRAGGING_CLASS)
        dragSession = null
        dropIndicator?.let { it.parentNode?.removeChild(it) }
        dropIndicator = null
    }

    private fun handleDragMove(ev: MouseEvent) {
        val s = dragSession ?: return
        if (!s.armed) {
            val dx = ev.clientX.toDouble() - s.originX
            val dy = ev.clientY.toDouble() - s.originY
            if (sqrt(dx * dx + dy * dy) < DRAG_THRESHOLD_PX) return
            s.armed = true
            ensureDropIndicator()
            // Suppress the browser's native text selection that would
            // otherwise grow as the pointer moves; we own the gesture now.
            window.asDynamic().getSelection()?.removeAllRanges()
            // A closed hand everywhere until release (CSS in ensureStyles).
            document.body?.classList?.add(DRAGGING_CLASS)
            dragScrollTimer = window.setInterval({ dragAutoScrollTick() }, DRAG_SCROLL_INTERVAL_MS)
        }
        s.lastX = ev.clientX.toDouble()
        s.lastY = ev.clientY.toDouble()
        aimDrop(s)
    }

    /**
     * One step of the edge auto-scroll: while the pointer is within
     * [DRAG_SCROLL_EDGE_PX] of the page's top or bottom edge — or past it,
     * outside the pane or the window — scrolls the page that way, faster
     * the further out it is, and re-aims the drop at the rows now under
     * the pointer. Runs every [DRAG_SCROLL_INTERVAL_MS] while a drag is
     * armed.
     */
    private fun dragAutoScrollTick() {
        val s = dragSession ?: return
        val scroller = scrollWrapperElement ?: return
        val rect = scroller.getBoundingClientRect()
        val y = s.lastY
        val depth = when {
            y < rect.top + DRAG_SCROLL_EDGE_PX -> y - (rect.top + DRAG_SCROLL_EDGE_PX)
            y > rect.bottom - DRAG_SCROLL_EDGE_PX -> y - (rect.bottom - DRAG_SCROLL_EDGE_PX)
            else -> return
        }
        val step = (depth / DRAG_SCROLL_EDGE_PX * DRAG_SCROLL_MAX_STEP_PX)
            .coerceIn(-DRAG_SCROLL_MAX_STEP_PX, DRAG_SCROLL_MAX_STEP_PX)
        val before = scroller.scrollTop
        scroller.scrollTop = before + step
        if (scroller.scrollTop != before) aimDrop(s)
    }

    /**
     * Works out where the dragged rows would land for the pointer at
     * ([DragSession.lastX], [DragSession.lastY]) and moves the drop line
     * there. The hit test is held inside the page, so a pointer above or
     * below it (auto-scrolling) aims at the edge row.
     */
    private fun aimDrop(s: DragSession) {
        val editor = editorElement ?: return
        val scroller = scrollWrapperElement ?: return
        val bounds = scroller.getBoundingClientRect()
        val x = s.lastX
        val y = s.lastY.coerceIn(bounds.top + 2.0, bounds.bottom - 2.0)
        val hit = document.elementFromPoint(x, y)
        val directRow = (if (hit != null) ancestorRowDiv(hit as Node) else null)?.takeIf { editor.contains(it) }
        // Over the page but not on a row — the space under the last row,
        // the folder contents list, or past the edge while auto-scrolling
        // at the end: aim at the nearest row instead. Off to the side of
        // the page (another pane, the sidebar) there is no drop.
        val rowDiv = directRow ?: if (x >= bounds.left && x <= bounds.right) nearestRowDiv(editor, y) else null
        val hoverRow = rowDiv?.getAttribute("data-row")?.toIntOrNull()
        if (rowDiv == null || hoverRow == null) {
            hideDropIndicator()
            s.target = null
            return
        }
        // Above or below by the half of the item under the pointer. A
        // block is one row div per line but one item — its drop is before
        // or after the whole block — so measure the whole block, or the
        // line would flip between the two on every row it crosses.
        val (top, bottom) = itemBoundsOf(editor, rowDiv)
        val insertAbove = y < (top + bottom) / 2.0
        // Sideways movement since the press picks the level: one level
        // per DRAG_LEVEL_STEP_PX, right to nest deeper, left to move out.
        val levelDelta = kotlin.math.round((x - s.originX) / DRAG_LEVEL_STEP_PX).toInt()
        val target = viewModel.dropTarget(s.sourceStart, s.sourceEnd, hoverRow, insertAbove, levelDelta)
        s.target = target
        if (target == null) hideDropIndicator() else showDropIndicator(editor, s, target)
    }

    /**
     * Top and bottom (client coordinates) of the item [rowDiv] belongs to:
     * the row itself, or — for a block row (`data-block-start`) — the span
     * from the block's first to its last painted row.
     */
    private fun itemBoundsOf(editor: HTMLElement, rowDiv: HTMLElement): Pair<Double, Double> {
        val rect = rowDiv.getBoundingClientRect()
        val start = rowDiv.getAttribute("data-block-start") ?: return rect.top to rect.bottom
        val rows = editor.querySelectorAll("[data-block-start='$start']")
        if (rows.length == 0) return rect.top to rect.bottom
        val first = (rows.item(0) as HTMLElement).getBoundingClientRect()
        val last = (rows.item(rows.length - 1) as HTMLElement).getBoundingClientRect()
        return first.top to last.bottom
    }

    /** The editor row whose box is vertically nearest to clientY [y], or `null` when there are no rows. */
    private fun nearestRowDiv(editor: HTMLElement, y: Double): HTMLElement? {
        val rows = editor.querySelectorAll("[data-row]")
        var best: HTMLElement? = null
        var bestDist = Double.MAX_VALUE
        for (i in 0 until rows.length) {
            val el = rows.item(i) as? HTMLElement ?: continue
            val r = el.getBoundingClientRect()
            val dist = if (y < r.top) r.top - y else if (y > r.bottom) y - r.bottom else 0.0
            if (dist < bestDist) {
                bestDist = dist
                best = el
            }
        }
        return best
    }

    private fun handleDragUp(@Suppress("UNUSED_PARAMETER") ev: MouseEvent) {
        val s = dragSession ?: return
        // A press without movement is a stationary click. Bullet origin →
        // zoom into the bullet's row. Selection origin → no-op (gutter
        // press without drag does nothing by design).
        //
        // Zooming is fired here rather than via a `click` listener on the
        // bullet because the editor's `mouseup` listener runs
        // `syncSelectionFromDom`, which can emit state and rebuild the
        // DOM — by the time `click` would fire, the original bullet span
        // is detached and the event is lost. See the matching note above
        // `handleExternalLinkMouseDown` for the same issue with link
        // following.
        if (!s.armed) {
            val origin = s.originMode
            val sourceStart = s.sourceStart
            endDragSession()
            if (origin == DragSession.Origin.Bullet) {
                viewModel.zoomInto(sourceStart)
            }
            return
        }
        val target = s.target
        endDragSession()
        if (target == null) return
        viewModel.moveLineRange(s.sourceStart, s.sourceEnd, target.insertBeforeRow, target.indent)
    }

    private fun ensureDropIndicator() {
        if (dropIndicator != null) return
        val ind = document.createElement("div") as HTMLElement
        ind.className = "lunarbor-drop-indicator"
        ind.style.display = "none"
        document.body?.appendChild(ind)
        dropIndicator = ind
    }

    /**
     * Draws the drop line for [target]: at the bottom edge of the last
     * painted row before the drop point (the dragged rows aside), or the
     * top edge of the first row when the drop is at the very top, and
     * indented to the level the moved rows will get, so the user sees
     * which parent they will land under.
     */
    private fun showDropIndicator(editor: HTMLElement, s: DragSession, target: PaneBackingViewModel.DropTarget) {
        val ind = dropIndicator ?: return
        val rows = editor.querySelectorAll("[data-row]")
        var before: HTMLElement? = null
        var beforeRow = -1
        var after: HTMLElement? = null
        var afterRow = Int.MAX_VALUE
        for (i in 0 until rows.length) {
            val el = rows.item(i) as? HTMLElement ?: continue
            val r = el.getAttribute("data-row")?.toIntOrNull() ?: continue
            if (r in s.sourceStart..s.sourceEnd && target.insertBeforeRow != s.sourceStart) continue
            if (r < target.insertBeforeRow && r > beforeRow) { before = el; beforeRow = r }
            if (r >= target.insertBeforeRow && r < afterRow) { after = el; afterRow = r }
        }
        val anchor = before ?: after ?: return
        val rect = anchor.getBoundingClientRect()
        val editorRect = editor.getBoundingClientRect()
        val padLeft = window.asDynamic().getComputedStyle(editor).getPropertyValue("padding-left")
            .unsafeCast<String?>()?.removeSuffix("px")?.toDoubleOrNull() ?: 0.0
        val backing = viewModel.currentBackingState
        val origin = viewModel.zoomInfo(backing)?.let { it.zoomIndent + PaneBackingViewModel.TAB_SIZE } ?: 0
        val level = (target.indent - origin) / PaneBackingViewModel.TAB_SIZE
        val left = editorRect.left + padLeft + level * style.indentStepPx
        ind.style.display = "block"
        ind.style.left = "${left}px"
        ind.style.width = "${(editorRect.right - left - 12.0).coerceAtLeast(40.0)}px"
        val y = if (before != null) rect.bottom - 1.0 else rect.top - 1.0
        ind.style.top = "${y}px"
    }

    private fun hideDropIndicator() {
        dropIndicator?.style?.display = "none"
    }
}

private const val DRAG_THRESHOLD_PX: Double = 4.0

/** Sideways drag distance that changes the drop level by one. */
private const val DRAG_LEVEL_STEP_PX: Double = 24.0

/** Class on `<body>` while a row drag is armed: a grabbing cursor everywhere. */
private const val DRAGGING_CLASS: String = "lunarbor-dragging"

/** Distance from the page's top / bottom edge at which a drag starts scrolling it. */
private const val DRAG_SCROLL_EDGE_PX: Double = 40.0

/** Largest scroll step per tick, reached one edge-width past the edge. */
private const val DRAG_SCROLL_MAX_STEP_PX: Double = 24.0

/** Interval of the drag auto-scroll ticks. */
private const val DRAG_SCROLL_INTERVAL_MS: Int = 16

/**
 * How long (ms) after a fold-state change the pane still animates the
 * rows it repaints: long enough for an unfolded folder-backed bullet's
 * children to load from disk.
 */
private const val FOLD_WINDOW_MS: Double = 1000.0

/**
 * Longest (ms) the title keeps showing a committed rename that hasn't
 * landed yet (see `MainScreen.pendingRename`); after that it shows
 * whatever the pane state says, so a failed rename can't stick.
 */
private const val RENAME_PENDING_MS: Int = 3000

/** Class on the title while it is an inline rename field (a `.md` note). */
private const val TITLE_EDITABLE_CLASS: String = "lunarbor-title-editable"

/** Quiet time after the last scroll event before the offset is reported for persisting. */
private const val SCROLL_SETTLE_MS: Int = 400
