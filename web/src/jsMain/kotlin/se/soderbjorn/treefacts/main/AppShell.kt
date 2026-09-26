/*
 * AppShell.kt (jsMain)
 * --------------------
 * Top-level shell for the treefacts web app, built on lunula.
 *
 * The chrome — top bar, tab strip, kebab menu, left sidebar's
 * tabs→panes tree, layout renderer, theme manager sidebar — comes
 * from the toolkit's `mountAppShell(AppShellSpec(...))` one-call
 * assembler (the toolkit bottom bar is disabled). TreeFacts
 * contributes:
 *
 *  - The per-pane note editor (rendered through [renderPaneContent]).
 *  - A typed `LayoutState` source ([TreeFactsTabSource]) for tab +
 *    pane identity (treefacts has its own document-model-derived shape;
 *    the toolkit's local-mode tab list isn't expressive enough).
 *  - One extra trailing top-bar action — the command-palette button
 *    (Cmd-P) — passed via `extraTopbarTrailing`.
 *  - TreeFacts-specific keyboard shortcuts (Cmd-P / Cmd-/ / Cmd-O /
 *    Cmd-S) and the Electron-menu `treefacts:show-hotkeys` bridge.
 *  - Per-pane navigation actions (zoom back/forward + up/home).
 *
 * The post-revamp toolkit theme system is global (no per-pane
 * sections), so treefacts no longer forwards a pane→section map; the
 * toolkit's `mountAppShell` owns the whole theme/settings surface.
 *
 * Persistence — theme, ui settings, layout — routes through the
 * lunula `Persister` injected by [JsAppGraph]: Electron
 * IPC when `globalThis.darknessApi` is present, namespaced
 * `localStorage` otherwise.
 *
 * commonMain rules: this file is jsMain only (touches the DOM).
 */

package se.soderbjorn.treefacts.main

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.events.Event
import se.soderbjorn.lunula.core.PersistKeys
import se.soderbjorn.lunula.core.Persister
import se.soderbjorn.lunula.store.LayoutState
import se.soderbjorn.lunula.store.TabState
import se.soderbjorn.lunula.web.injectLunulaStyles
import se.soderbjorn.lunula.web.layout.FloatingPaneSpec
import se.soderbjorn.lunula.web.layout.GridSpec
import se.soderbjorn.lunula.web.layout.LayoutPreset
import se.soderbjorn.lunula.web.layout.PaneLayout
import se.soderbjorn.lunula.web.layout.PaneActions
import se.soderbjorn.lunula.web.layout.PaneAction
import se.soderbjorn.lunula.web.layout.PaneTitleSegment
import se.soderbjorn.lunula.web.layout.withNoneMaximized
import se.soderbjorn.lunula.web.shell.AppShellHandle
import se.soderbjorn.lunula.web.shell.AppShellSpec
import se.soderbjorn.lunula.web.shell.TopbarAction
import se.soderbjorn.lunula.web.shell.mountAppShell
import se.soderbjorn.treefacts.data.InlineMarkdownTokenizer
import se.soderbjorn.treefacts.data.NoteRepository

/**
 * Top-level shell that wires the lunula windowing system
 * around the treefacts editor. One instance per app startup;
 * instantiated in [se.soderbjorn.treefacts.Main].
 *
 * @param scope coroutine scope shared with the embedded [MainScreen]
 *   for its paint loop and used for [Persister] reads/writes.
 * @param documentRegistry The shared registry that hands out
 *   [se.soderbjorn.treefacts.main.Document] instances. Each pane
 *   acquires its current file from here; two panes pointed at the
 *   same file share one Document so concurrent edits stay live.
 * @param persister Toolkit-canonical KV bridge for theme / layout /
 *   ui-settings (see [PersistKeys]). Backed by Electron IPC inside
 *   the desktop wrapper, namespaced `localStorage` in a plain browser.
 *   The toolkit's `mountAppShell` reads `UI_SETTINGS` /
 *   `LAYOUT_STATE` / `THEME_SNAPSHOT` itself; treefacts uses this
 *   handle for `LAYOUT` (its typed [LayoutState] shape, owned by
 *   the app when a `TabSource` is supplied).
 */
class AppShell(
    private val scope: CoroutineScope,
    private val documentRegistry: se.soderbjorn.treefacts.main.DocumentRegistry,
    private val persister: Persister,
) {

    /** Mounted root element. Captured at boot for the toolkit assembler. */
    private var rootEl: HTMLElement? = null

    /**
     * Handle returned by [mountAppShell]. Used to push pane-state
     * changes back to the toolkit so the per-pane chrome (action
     * button enabled state, breadcrumb title) refreshes when the
     * pane's zoom history / active file changes. See
     * [se.soderbjorn.lunula.web.shell.AppShellHandle.refresh].
     */
    private var shellHandle: AppShellHandle? = null

    /**
     * Per-pane editor instances. Each entry pairs a `MainViewModel` (with
     * its own `PaneBackingViewModel` so zoom + selection don't
     * leak across panes) with a `MainScreen` that paints into that pane's
     * DOM container. Created lazily on first render of a pane and cached
     * here so re-renders / tab switches reuse the same VM (preserves
     * zoom focus, selection, scroll). Cleared when the pane is closed.
     */
    private val paneEditors: MutableMap<String, MainScreen> = mutableMapOf()

    /**
     * Per-pane Style dropdown popovers, keyed by leaf pane id. Lazily
     * created on first click of the pane's Style toolbar button and
     * reused across opens (the popover rebuilds its body each time).
     */
    private val styleDropdowns: MutableMap<String, StyleDropdown> = mutableMapOf()

    /**
     * Per-pane Starred bookmarks modals, keyed by leaf pane id. Lazily
     * created on first click of the pane's Starred toolbar button. Each
     * modal is bound to its owning pane so "Add to starred" captures
     * *that* pane's current navigation target. Cleared when a pane is
     * removed in [closePane].
     */
    private val starredModals: MutableMap<String, StarredModal> = mutableMapOf()

    /**
     * Per-pane Insert Link modals, keyed by leaf pane id. Same lifecycle
     * pattern as [starredModals]: lazily created on first open from the
     * command palette, reused thereafter, cleared in [closePane].
     */
    private val insertLinkModals: MutableMap<String, LinkSearchModal> = mutableMapOf()

    /**
     * Per-pane Navigate-to modals (Cmd-O / "Navigate to" command).
     * Same lifecycle pattern as [insertLinkModals]: lazy first-open
     * create, reuse thereafter, cleared in [closePane].
     */
    private val navigateToModals: MutableMap<String, LinkSearchModal> = mutableMapOf()

    /**
     * Per-pane Insert Image modals ("Insert Image" command). Same lifecycle
     * pattern as [insertLinkModals]: lazy first-open create, reuse
     * thereafter, cleared in [closePane].
     */
    private val insertImageModals: MutableMap<String, ImageSearchModal> = mutableMapOf()

    /**
     * Singleton Starred-bookmarks modal mounted in the *tab toolbar*
     * (left of the layout dropdown), distinct from the per-pane
     * [starredModals]. Picking a favorite navigates whichever pane is
     * currently focused on the active tab; if no tab/pane exists, one
     * is created on the fly via [resolveOrCreateFocusedPaneVm] so the
     * navigation always lands somewhere visible.
     */
    private val topbarStarredModal: StarredModal by lazy {
        StarredModal(
            parentScope = scope,
            activePaneVmProvider = { resolveOrCreateFocusedPaneVm() },
            vaultRoot = documentRegistry.rootDirectory,
        )
    }

    /**
     * Per-pane [MainViewModel] handles, keyed by leaf pane id. Maintained
     * alongside [paneEditors] so the toolkit-rendered pane chrome
     * callbacks ([buildPaneNavActions], [paneZoomTitleSegments],
     * [paneSidebarLabel]) can wire up/home action buttons and the
     * clickable breadcrumb title to the pane's own zoom state without
     * crossing through the screen.
     *
     * Populated lazily on first render of each pane in [renderPaneContent];
     * an entry is missing until the pane has rendered at least once, so
     * action handlers that look up here must tolerate `null`.
     */
    private val paneViewModels: MutableMap<String, MainViewModel> = mutableMapOf()

    /**
     * TreeFacts's typed layout state — tab list, per-tab floating panes
     * (id + title + on-disk file ref). Hydrated from
     * `persister.read(LAYOUT)` at boot; mutated by tab/pane intents
     * and re-persisted via [persistLayoutState]. The toolkit's
     * `mountAppShell` skips its own LAYOUT key when a `TabSource` is
     * supplied, so this is the authoritative tab/pane source.
     */
    private var layoutState: LayoutState = LayoutState.defaults()

    /**
     * Pushes a fresh [se.soderbjorn.lunula.web.shell.TabListSnapshot]
     * to the toolkit shell when treefacts's [LayoutState] mutates.
     * Captured by [render] when it constructs the [TreeFactsTabSource].
     */
    private var notifyToolkitTabs: (() -> Unit)? = null

    /** Per-tab pane layout (floats only). Keyed by tab id. */
    private val tabLayouts: MutableMap<String, PaneLayout> = mutableMapOf()

    /**
     * The most recently focused pane id per tab. The toolkit's
     * `mountAppShell` owns *runtime* focus through its layout
     * renderer; treefacts only tracks an *advisory* last-focus per
     * tab so palette commands ("Close current pane", "Open new
     * pane in same tab") and link-follow can target the right
     * pane when nothing has been clicked yet.
     */
    private val lastFocusedPaneIdByTab: MutableMap<String, String> = mutableMapOf()

    /**
     * The pane that was active when each newly-spawned pane was
     * created. Used by [openLinkInNewPane] / [addFloatingPane] to
     * cluster a freshly-spawned child near its originator. Cleared
     * on pane close.
     */
    private val parentByPane: MutableMap<String, String> = mutableMapOf()

    /**
     * Layout preset currently driving each tab's geometry. Maintained
     * for [persistLayoutState] so the persisted shape continues to
     * carry the user's last preset choice round-trip; the toolkit
     * owns runtime preset enforcement.
     */
    private val activePresetByTab: MutableMap<String, LayoutPreset> = mutableMapOf()

    /**
     * Pane ids whose live DOM selection currently sits on a non-editable
     * surface of that pane's chrome — the `.treefacts-title` headline
     * or one of the `.dt-pane-breadcrumb-segment` breadcrumb labels.
     *
     * The pane chrome is read-only DOM (no `contenteditable`, no edit
     * handlers), so the browser still lets the user drag-select text
     * there. When the user then clicks the Style button, the dropdown
     * would silently apply the chosen style to the editor's *model*
     * cursor — invisible from the user's vantage point because they
     * were addressing the title visually. We gate the Style button on
     * this set so it dims while such a selection is active, removing
     * the foot-gun.
     *
     * Maintained by [refreshChromeSelectionState], driven by a single
     * `selectionchange` listener installed in [render].
     */
    private val panesWithChromeSelection: MutableSet<String> = mutableSetOf()

    /** Guard so [installChromeSelectionTracker] only attaches its listener once. */
    private var chromeSelectionTrackerInstalled: Boolean = false

    /**
     * Singleton command palette (Cmd-P). Lazily constructed so the
     * `provideCommands` lambda captures `this` at first open rather than
     * during shell construction (some fields it reads, like
     * `paneViewModels`, may not have entries yet at construction time).
     */
    private val commandPalette: CommandPalette by lazy {
        CommandPalette(provideCommands = { buildPaletteCommands() })
    }

    /** Document-level Cmd/Ctrl+P listener installed in [render]. Tracked so
     *  the listener can be torn down on a hypothetical re-render of the
     *  shell host (idempotency guard). */
    private var paletteShortcutHandler: ((Event) -> Unit)? = null

    // The legacy document-level Cmd/Ctrl+/ listener is gone — the
    // toolkit's [HotkeyRegistry] (via [installCheatsheetHotkey]) owns
    // the binding now. The Electron `treefacts:show-hotkeys` menu bridge
    // still exists in [installHotkeysMenuBridge] for the macOS menu
    // accelerator path.

    /** Document-level Cmd/Ctrl+O listener installed in [render] — opens
     *  the Navigate-to modal for the focused pane. Tracked so the
     *  listener can be re-installed idempotently. */
    private var navigateToShortcutHandler: ((Event) -> Unit)? = null

    /** Document-level Cmd/Ctrl+S listener installed in [render] — opens
     *  the Starred (bookmarks) modal for the focused pane. Notes are
     *  autosaved so Cmd+S has no "save document" meaning to collide
     *  with; we override the browser's default save dialog in capture
     *  phase. */
    private var starredShortcutHandler: ((Event) -> Unit)? = null

    /** Document-level keydown delegate — dispatches editor shortcuts
     *  to the focused pane's editor when DOM focus sits on
     *  `<body>` / a non-editable element (e.g., right after a modal
     *  close), so chords like Cmd-Shift-Left, undo, or arrow-key
     *  navigation work without the user first re-clicking into the
     *  contenteditable. Pure-formatting chords (Cmd-B/I/U/E/K) are
     *  intentionally excluded — those should require explicit editor
     *  focus to avoid surprise toggles from across the page. */
    private var editorKeyDelegateHandler: ((Event) -> Unit)? = null

    /**
     * Singleton hotkeys cheatsheet modal. Opened from the macOS
     * application menu (`TreeFacts → Hotkeys…`) via the `treefacts:show-hotkeys`
     * IPC bridge installed in [render], or from the in-app `Cmd+/`
     * shortcut bound through [installCheatsheetHotkey].
     *
     * Modal shell is owned by the toolkit; treefacts only supplies the
     * curated [HotkeysModalSpec] via [treefactsHotkeysSpec]. Lazy so the
     * app doesn't pay the construction cost on boots that never open it.
     */
    private val hotkeysModal: se.soderbjorn.lunula.web.hotkey.ToolkitHotkeysModal by lazy {
        se.soderbjorn.lunula.web.hotkey.ToolkitHotkeysModal().apply {
            setContent(treefactsHotkeysSpec())
        }
    }

    /**
     * Boots the shell into [root]. Safe to call once.
     *
     * TreeFacts contributes the persistence-aware [LayoutState] (tabs +
     * floating panes), per-pane editor body, palette button, and
     * treefacts-only keyboard shortcuts, plus the sidebar brand logo
     * ([buildAppLogo]) whose dot pulses while unsaved edits pend.
     * Everything else — top bar, tab strip, kebab menu, layout
     * dropdown, new-pane button, appearance toggle, theme manager
     * sidebar, layout renderer, pane chrome — comes from
     * [se.soderbjorn.lunula.web.shell.mountAppShell] (the toolkit
     * bottom bar is disabled; the brand lives in the sidebar logo).
     */
    fun render(root: HTMLElement) {
        injectLunulaStyles()
        ensureTreeFactsChromeStyles()
        rootEl = root

        installPaletteShortcut()
        installChromeSelectionTracker()
        installHotkeysShortcut()
        installNavigateToShortcut()
        installStarredShortcut()
        installEditorKeyDelegate()
        installHotkeysMenuBridge()

        val tabSource = TreeFactsTabSource(
            onTabSelected = { id ->
                layoutState = layoutState.copy(activeTabId = id)
                persistLayoutState()
            },
            onTabAdded = { addTab() },
            onTabClosed = { id -> closeTab(id) },
            onTabRenamed = { id, label -> renameTab(id, label) },
            onTabReordered = { sourceId, targetId, before -> reorderTab(sourceId, targetId, before) },
            onPaneSelected = { tabId, paneId ->
                if (layoutState.activeTabId != tabId) {
                    layoutState = layoutState.copy(activeTabId = tabId)
                    persistLayoutState()
                }
                paneEditors[paneId]?.focusEditor()
            },
            onPaneClosed = { tabId, paneId -> closeFloatingPane(tabId, paneId) },
            onPaneAdded = { tabId ->
                // `addFloatingPane` already calls `persistLayoutState`,
                // which calls `notifyToolkitTabs`. Don't re-notify here
                // — a second rerender right behind the first wipes the
                // restore-from-maximize CSS transition the first
                // rerender just kicked off.
                addFloatingPane(tabId)?.let { newId ->
                    ensurePaneViewModel(newId)
                    lastFocusedPaneIdByTab[tabId] = newId
                }
            },
        )
        notifyToolkitTabs = {
            tabSource.notify(layoutState, activePaneByTab = lastFocusedPaneIdByTab)
        }

        shellHandle = mountAppShell(
            AppShellSpec(
                rootContainer = root,
                title = "TreeFacts",
                persister = persister,
                paneContent = { paneId ->
                    val container = document.createElement("div") as HTMLElement
                    // The toolkit's `.dt-pane-content` is a flex column;
                    // without explicit flex sizing this wrapper would
                    // collapse to content height and the inner
                    // `renderPaneContent` container's `height: 100%` would
                    // resolve against an indeterminate parent — breaking
                    // the editor's scroll wrapper (no bounded height ⇒
                    // `overflow-y: auto` never triggers).
                    container.style.apply {
                        setProperty("flex", "1 1 auto")
                        setProperty("min-height", "0")
                        setProperty("min-width", "0")
                        setProperty("display", "flex")
                        setProperty("flex-direction", "column")
                    }
                    renderPaneContent(paneId, container)
                    container
                },
                tabSource = tabSource.tabSource,
                paneLabel = { _, paneId -> paneSidebarLabel(paneId) },
                paneIcon = { _, _ -> ICON_NOTE },
                paneActions = { _, paneId -> buildPaneNavActions(paneId) },
                // Clickable breadcrumb segments for the pane title. When
                // the pane is zoomed into a bullet the toolkit renders
                // each ancestor as its own clickable span — clicking
                // jumps the pane to that bullet via `zoomTo(lineId)`.
                // Returns empty when not zoomed so the title falls back
                // to plain-string mode (and the inline-rename hover-arm
                // gesture stays armed for renamed panes that show no
                // path).
                paneTitleSegments = { _, paneId -> paneZoomTitleSegments(paneId) },
                // Sticky pane-slot index — `①..⑨`, `Ⓐ..Ⓩ` rendered as a
                // trailing badge on both pane header and sidebar row.
                // Kept in sync with the live pane set by
                // `TreeFactsTabSource.notify`, which calls
                // `treefactsPaneAssigner.syncTo(...)` on every push.
                paneIndex = { _, paneId -> treefactsPaneAssigner.indexOf(paneId) },
                extraTopbarBeforeStandard = listOf(
                    TopbarAction(
                        id = "treefacts-topbar-starred",
                        iconHtml = ICON_STAR,
                        label = "Starred",
                        onActivate = { topbarStarredModal.open() },
                    )
                ),
                // Brand logo (dot + "treefacts" wordmark, termtastic-style)
                // pinned to the top of the left sidebar. The factory returns
                // a cached element so toolkit rerenders re-parent the same
                // node and the dot's save-state pulse survives rebuilds.
                sidebarHeader = { buildAppLogo() },
                // No bottom bar: its only content in treefacts was the
                // toolkit's default app-name label (the tiny "TreeFacts"
                // in the lower right). The brand moved to the sidebar
                // logo above, so the strip earns nothing.
                showBottomBar = false,
                // The Settings sidebar's "Custom title bar" toggle only
                // makes sense in Electron — gate it on the preload-injected
                // `darknessApi`. In a plain browser this resolves to
                // `undefined` and the toggle stays hidden.
                isElectron = (js("typeof globalThis !== 'undefined' && globalThis.darknessApi != null") as Boolean),
            ),
            scope = scope,
        )

        // Hydrate treefacts's typed LayoutState from the toolkit's
        // Persister (Electron-IPC or localStorage) and push the first
        // snapshot through the TabSource. mountAppShell is launched
        // synchronously above; if the toolkit subscribes before this
        // load finishes, TreeFactsTabSource pushes its empty
        // `lastSnapshot` first and updates once we [persistLayoutState]
        // here.
        scope.launch {
            val raw = persister.read(PersistKeys.LAYOUT)
            // First run (`raw == null`) must go through hydrateLayoutState
            // too: `LayoutState.defaults()` ships one tab with ZERO panes,
            // and hydration is what seeds the initial pane (and tabLayouts)
            // for pane-less tabs. Taking defaults() directly renders a tab
            // with no panes — an empty window with no editor anywhere.
            // `fromJsonString("")` resolves to defaults(), so the empty
            // string routes the first run through the same seeding path.
            layoutState = hydrateLayoutState(raw ?: "")
            tabSource.notify(layoutState)
        }

        // Drive the sidebar logo's save-state dot: pulse while any open
        // document holds unflushed edits, steady light once everything
        // is on disk. The registry aggregates per-document dirty flags,
        // so this is one collector for the whole app regardless of how
        // many panes/files are open.
        scope.launch {
            documentRegistry.unsavedFilesFlow
                .map { it.isNotEmpty() }
                .distinctUntilChanged()
                .collect { unsaved -> setAppLogoUnsaved(unsaved) }
        }
    }

    /**
     * Installs the document-level Cmd/Ctrl+P listener that opens the
     * command palette. Capture phase so the editor's own keydown handler
     * (which intercepts most keystrokes when the contenteditable has
     * focus) doesn't swallow the shortcut. Idempotent — a second call is
     * a no-op so [render] can stay re-entrant if we ever need to remount
     * the host element.
     */
    private fun installPaletteShortcut() {
        if (paletteShortcutHandler != null) return
        val handler: (Event) -> Unit = lambda@{ e ->
            val ke = e as? org.w3c.dom.events.KeyboardEvent ?: return@lambda
            val isCmdP = (ke.metaKey || ke.ctrlKey) &&
                !ke.altKey && !ke.shiftKey &&
                ke.key.equals("p", ignoreCase = true)
            if (!isCmdP) return@lambda
            ke.preventDefault()
            ke.stopPropagation()
            commandPalette.open()
        }
        paletteShortcutHandler = handler
        document.addEventListener("keydown", handler, /* capture = */ true)
    }

    /**
     * Bind `Cmd/Ctrl+/` to open [hotkeysModal] through the toolkit's
     * shared [HotkeyRegistry]. Idempotent — the registry's
     * replace-on-register semantics mean re-installing on a subsequent
     * boot pass overwrites the previous binding.
     */
    private fun installHotkeysShortcut() {
        se.soderbjorn.lunula.web.hotkey.installCheatsheetHotkey(hotkeysModal)
    }

    /**
     * Installs a bubble-phase (NOT capture) document-level keydown
     * delegate that, when no editable element holds focus, dispatches
     * editor-relevant chords to the focused pane's editor. Idempotent.
     *
     * **Bubble phase, not capture:** when focus *is* inside the editor
     * the editor's own keydown handler runs first; that handler may
     * `preventDefault` for keys it consumes, and `defaultPrevented`
     * tells us to stand down. When focus is on `<body>` or a
     * non-editable element, no upstream handler fires and this
     * delegate routes the key to the editor.
     *
     * Skips:
     *  - any key whose target sits inside an `<input>`, `<textarea>`,
     *    or other `contenteditable` element (that element owns the key);
     *  - pure-formatting chords (Cmd-B / Cmd-I / Cmd-U / Cmd-E / Cmd-K) —
     *    the user explicitly excluded these so a stray Cmd-B doesn't
     *    bold something across the page.
     *  - bare keys without a modifier and without being a recognised
     *    navigation key — typing on body shouldn't appear in the editor
     *    out of nowhere.
     */
    private fun installEditorKeyDelegate() {
        if (editorKeyDelegateHandler != null) return
        val handler: (Event) -> Unit = lambda@{ e ->
            val ke = e as? org.w3c.dom.events.KeyboardEvent ?: return@lambda
            if (ke.defaultPrevented) return@lambda
            val target = ke.target as? org.w3c.dom.Node ?: return@lambda
            if (isInsideEditable(target)) return@lambda
            if (!shouldDelegateToEditor(ke)) return@lambda
            val paneId = focusedPaneId() ?: return@lambda
            val mainScreen = paneEditors[paneId] ?: return@lambda
            // The editor's handler may preventDefault; mirror that here
            // so the browser doesn't run its own behaviour for chords
            // we just consumed (e.g. Cmd-Z's browser undo).
            mainScreen.dispatchEditorKey(ke)
        }
        editorKeyDelegateHandler = handler
        document.addEventListener("keydown", handler, /* capture = */ false)
    }

    /** True when [node] (or an ancestor) is an editable form field or
     *  a `contenteditable` host. */
    private fun isInsideEditable(node: org.w3c.dom.Node): Boolean {
        var n: org.w3c.dom.Node? = node
        while (n != null) {
            if (n is org.w3c.dom.Element) {
                val tag = n.tagName
                if (tag.equals("INPUT", true) || tag.equals("TEXTAREA", true)) return true
                val ce = n.getAttribute("contenteditable")
                if (ce != null && !ce.equals("false", ignoreCase = true)) return true
            }
            n = n.parentNode
        }
        return false
    }

    /** Predicate for the document-level delegate. */
    private fun shouldDelegateToEditor(ke: org.w3c.dom.events.KeyboardEvent): Boolean {
        // Pure-formatting chords (Cmd/Ctrl + a single letter, no other
        // modifier) are excluded — the user wants those to require
        // explicit editor focus.
        if ((ke.metaKey || ke.ctrlKey) && !ke.altKey && !ke.shiftKey) {
            when (ke.key.lowercase()) {
                "b", "i", "u", "e", "k" -> return false
            }
        }
        // Modifier-bearing chords are editor-relevant: undo/redo,
        // selection extension (Cmd-Shift-Arrow), word-jump, etc.
        if (ke.metaKey || ke.ctrlKey || ke.altKey) return true
        // Bare navigation keys also delegate so arrow-key motion works
        // without re-clicking the editor.
        return when (ke.key) {
            "ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown",
            "Home", "End", "PageUp", "PageDown" -> true
            else -> false
        }
    }

    /**
     * Document-level Cmd/Ctrl+O listener that opens the Navigate-to
     * modal for the focused pane. Capture phase so the editor's own
     * keydown handler doesn't swallow it inside a focused
     * contenteditable. Idempotent.
     */
    private fun installNavigateToShortcut() {
        if (navigateToShortcutHandler != null) return
        val handler: (Event) -> Unit = lambda@{ e ->
            val ke = e as? org.w3c.dom.events.KeyboardEvent ?: return@lambda
            val isCmdO = (ke.metaKey || ke.ctrlKey) &&
                !ke.altKey && !ke.shiftKey &&
                ke.key.equals("o", ignoreCase = true)
            if (!isCmdO) return@lambda
            ke.preventDefault()
            ke.stopPropagation()
            val paneId = focusedPaneId() ?: return@lambda
            openNavigateToModal(paneId)
        }
        navigateToShortcutHandler = handler
        document.addEventListener("keydown", handler, /* capture = */ true)
    }

    /**
     * Document-level Cmd/Ctrl+S listener that opens the Starred
     * (bookmarks) modal for the focused pane. Capture phase so the
     * browser's default save dialog is suppressed before any other
     * handler sees the keystroke. The pane lookup uses [focusedPaneId]
     * which falls back to the first pane in the active tab on a
     * fresh launch, so Cmd+S works even before any pane has been
     * clicked into. Idempotent.
     */
    private fun installStarredShortcut() {
        if (starredShortcutHandler != null) return
        val handler: (Event) -> Unit = lambda@{ e ->
            val ke = e as? org.w3c.dom.events.KeyboardEvent ?: return@lambda
            val isCmdS = (ke.metaKey || ke.ctrlKey) &&
                !ke.altKey && !ke.shiftKey &&
                ke.key.equals("s", ignoreCase = true)
            if (!isCmdS) return@lambda
            ke.preventDefault()
            ke.stopPropagation()
            val paneId = focusedPaneId() ?: return@lambda
            openStarredModal(paneId)
        }
        starredShortcutHandler = handler
        document.addEventListener("keydown", handler, /* capture = */ true)
    }

    /**
     * Subscribes to the Electron preload's `treefacts:show-hotkeys` channel,
     * dispatched when the user picks `TreeFacts → Hotkeys…` from the macOS
     * application menu. No-op when running in a plain browser (no
     * `darknessApi.onShowHotkeys` global) — the in-app `Cmd+/` shortcut
     * still works there.
     */
    private fun installHotkeysMenuBridge() {
        val api = js("globalThis.darknessApi") ?: return
        val onShow = js("api && api.onShowHotkeys") ?: return
        if (js("typeof onShow !== 'function'") as Boolean) return
        val callback: () -> Unit = { hotkeysModal.open() }
        js("onShow.call(api, callback)")
    }

    /**
     * Builds the palette command list. Re-evaluated each open by the
     * palette's `provideCommands` lambda so commands always target the
     * currently-focused pane / active tab.
     *
     * Commands fall back gracefully when no pane is focused: the style /
     * starred / close commands no-op rather than throwing, since the
     * palette is also openable on a freshly-launched app where focus
     * hasn't yet been recorded.
     */
    private fun buildPaletteCommands(): List<CommandPalette.Command> {
        val out = mutableListOf<CommandPalette.Command>()

        fun addStyleCmd(id: String, title: String, action: (MainViewModel) -> Unit) {
            out += CommandPalette.Command(
                id = id,
                title = title,
                run = { focusedPaneViewModel()?.let(action) },
            )
        }

        // Line-level styles
        addStyleCmd("heading-1", "Heading 1") { it.applyLineStyle(se.soderbjorn.treefacts.data.LineStyle.HEADING_1) }
        addStyleCmd("heading-2", "Heading 2") { it.applyLineStyle(se.soderbjorn.treefacts.data.LineStyle.HEADING_2) }
        addStyleCmd("heading-3", "Heading 3") { it.applyLineStyle(se.soderbjorn.treefacts.data.LineStyle.HEADING_3) }
        addStyleCmd("heading-4", "Heading 4") { it.applyLineStyle(se.soderbjorn.treefacts.data.LineStyle.HEADING_4) }
        addStyleCmd("heading-5", "Heading 5") { it.applyLineStyle(se.soderbjorn.treefacts.data.LineStyle.HEADING_5) }
        addStyleCmd("heading-6", "Heading 6") { it.applyLineStyle(se.soderbjorn.treefacts.data.LineStyle.HEADING_6) }
        addStyleCmd("quote", "Quote") { it.applyLineStyle(se.soderbjorn.treefacts.data.LineStyle.QUOTE) }

        // Inline styles
        addStyleCmd("bold", "Bold") { it.applyInlineStyle(se.soderbjorn.treefacts.data.InlineStyle.BOLD) }
        addStyleCmd("italic", "Italic") { it.applyInlineStyle(se.soderbjorn.treefacts.data.InlineStyle.ITALIC) }
        addStyleCmd("strikethrough", "Strikethrough") {
            it.applyInlineStyle(se.soderbjorn.treefacts.data.InlineStyle.STRIKETHROUGH)
        }
        addStyleCmd("inline-code", "Inline code") {
            it.applyInlineStyle(se.soderbjorn.treefacts.data.InlineStyle.INLINE_CODE)
        }

        // Pane / app actions
        out += CommandPalette.Command(
            id = "starred",
            title = "Starred",
            run = {
                val paneId = focusedPaneId()
                if (paneId != null) openStarredModal(paneId)
            },
        )
        out += CommandPalette.Command(
            id = "insert-link",
            title = "Insert Link",
            run = {
                val paneId = focusedPaneId()
                if (paneId != null) openInsertLinkModal(paneId)
            },
        )
        out += CommandPalette.Command(
            id = "navigate-to",
            title = "Navigate to",
            run = {
                val paneId = focusedPaneId()
                if (paneId != null) openNavigateToModal(paneId)
            },
        )
        out += CommandPalette.Command(
            id = "insert-image",
            title = "Insert Image",
            run = {
                val paneId = focusedPaneId()
                if (paneId != null) openInsertImageModal(paneId)
            },
        )
        out += CommandPalette.Command(
            id = "open-new-pane",
            title = "Open new pane",
            run = {
                val tabId = layoutState.activeTabId
                if (tabId != null) addFloatingPane(tabId)
            },
        )
        out += CommandPalette.Command(
            id = "close-current-pane",
            title = "Close current pane",
            run = {
                val tabId = layoutState.activeTabId
                val paneId = focusedPaneId()
                if (tabId != null && paneId != null) closeFloatingPane(tabId, paneId)
            },
        )
        // Layout-preset picking, top/bottom bar visibility, and the
        // appearance toggle live in the toolkit's mountAppShell now —
        // accessible via the trailing topbar buttons it builds. The
        // command palette only ships treefacts-specific commands.

        return out
    }

    /**
     * Pane id of the pane the user most recently interacted with on the
     * active tab, or — as a fallback when no focus has been recorded yet
     * — the first non-minimised pane. Returns `null` only when the active
     * tab has no panes at all, which the rest of the shell already
     * defends against.
     */
    private fun focusedPaneId(): String? {
        val activeId = layoutState.activeTabId ?: return null
        val recorded = lastFocusedPaneIdByTab[activeId]
        if (recorded != null) {
            // Validate that the recorded id is still in the layout —
            // closing a pane doesn't always wipe the entry, so a stale
            // id can outlive its pane.
            val layout = tabLayouts[activeId]
            if (layout?.floatingPanes?.any { it.id == recorded } == true) return recorded
        }
        val layout = tabLayouts[activeId] ?: return null
        return layout.floatingPanes.firstOrNull { !it.isMinimized }?.id
            ?: layout.floatingPanes.firstOrNull()?.id
    }

    /** Convenience: the [MainViewModel] for [focusedPaneId], or `null`. */
    private fun focusedPaneViewModel(): MainViewModel? {
        val id = focusedPaneId() ?: return null
        return paneViewModels[id]
    }

    /**
     * Resolves the active pane's [MainViewModel], creating tab and/or
     * pane on the fly when none exist. Used by the tab-toolbar Starred
     * modal so picking a favorite always has a target to navigate.
     *
     * Order:
     *  1. If there is no active tab, create one (which seeds a pane).
     *  2. If the active tab has no panes, create a floating pane in it.
     *  3. Return the focused pane's VM (lazy-instantiating it if the
     *     pane was just created and hasn't rendered yet).
     */
    private fun resolveOrCreateFocusedPaneVm(): MainViewModel? {
        if (layoutState.activeTabId == null || layoutState.tabs.isEmpty()) {
            addTab()
        } else {
            val activeTabId = layoutState.activeTabId!!
            val layout = tabLayouts[activeTabId]
            if (layout == null || layout.floatingPanes.isEmpty()) {
                addFloatingPane(activeTabId)
            }
        }
        val paneId = focusedPaneId() ?: return null
        ensurePaneViewModel(paneId)
        return paneViewModels[paneId]
    }

    // ── Mount / re-mount ────────────────────────────────────────────

    /**
     * Builds the sidebar tree row label for [paneId] in the toolkit's
     * tabs→panes default sidebar. Combines the pane's own custom title
     * (if any), the active file's display name, and the current zoom
     * path so each row reads like `"My Note / Recipes / Pasta"`.
     *
     * Called by the [paneLabel] lambda passed to
     * [se.soderbjorn.lunula.web.shell.AppShellSpec]; the toolkit
     * looks it up once per pane on every sidebar re-render.
     */
    private fun paneSidebarLabel(paneId: String): String {
        val activeTab = layoutState.activeTabId
        val float = activeTab?.let {
            tabLayouts[it]?.floatingPanes?.firstOrNull { f -> f.id == paneId }
        }
        val fileLabel = activeFileDisplayName(paneId)
        val path = zoomPathStringForPane(paneId)
        val own = float?.title?.ifBlank { null }?.takeUnless { it == "Untitled" }
        val combined = if (path != null) "$fileLabel / $path" else fileLabel
        return if (own != null) "$own / $combined" else combined
    }

    /**
     * Reconstructs treefacts's [LayoutState] from the persisted JSON read
     * back through the toolkit's [Persister]. Migrates any legacy split
     * trees into the floats-only model the runtime uses today, then
     * seeds [tabLayouts] so the tab/pane mutators have a starting point.
     */
    private fun hydrateLayoutState(raw: String): LayoutState {
        val state = LayoutState.fromJsonString(raw)
        val seeded = if (state.tabs.isEmpty()) LayoutState.defaults() else state

        for (tab in seeded.tabs) {
            // Persisted floats come over verbatim; legacy split-tree
            // leaves become full-bleed maximised floats so a user
            // upgrading from the pre-floats model still sees their
            // panes. The tab's persisted tree is dropped on next save.
            val persistedFloats = tab.floatingPanes.map { f ->
                FloatingPaneSpec(
                    id = f.id,
                    title = f.title,
                    xPct = f.xPct,
                    yPct = f.yPct,
                    widthPct = f.widthPct,
                    heightPct = f.heightPct,
                    zIndex = f.zIndex,
                    isMaximized = f.isMaximized,
                    isMinimized = f.isMinimized,
                )
            }
            val migratedTreeLeaves: List<FloatingPaneSpec> = tab.tree
                ?.let { collectTreeLeafSpecs(it) }
                .orEmpty()
            val existingIds = persistedFloats.map { it.id }.toSet()
            val merged = persistedFloats + migratedTreeLeaves.filterNot { it.id in existingIds }
            val final = if (merged.isEmpty()) listOf(seedPane(tab.id)) else merged
            tabLayouts[tab.id] = PaneLayout(floatingPanes = final)
        }

        val activeId = seeded.activeTabId ?: seeded.tabs.first().id
        // Sync `seeded.tabs[i].floatingPanes` with whatever
        // `tabLayouts[tab.id]` ended up holding — including the
        // [seedPane] we added above when a tab had zero persisted
        // floats. Without this sync, [TreeFactsTabSource.notify]
        // (which reads `layoutState.tabs[i].floatingPanes`) would
        // push an empty pane list to the toolkit, and a later
        // `addFloatingPane` would discover the seed in tabLayouts
        // and report TWO panes appearing for the user's first "+"
        // click. Mirrors the sync persistLayoutState does after
        // every mutation, applied at boot.
        val syncedTabs = seeded.tabs.map { tab ->
            val layout = tabLayouts[tab.id] ?: return@map tab
            tab.copy(
                floatingPanes = layout.floatingPanes.map { f ->
                    se.soderbjorn.lunula.store.FloatingPaneJson(
                        id = f.id,
                        title = f.title,
                        xPct = f.xPct,
                        yPct = f.yPct,
                        widthPct = f.widthPct,
                        heightPct = f.heightPct,
                        zIndex = f.zIndex,
                        isMaximized = f.isMaximized,
                        isMinimized = f.isMinimized,
                    )
                },
            )
        }
        return seeded.copy(activeTabId = activeId, tabs = syncedTabs)
    }

    /**
     * Builds the sidebar tree — REMOVED. The toolkit's `mountAppShell`
     * owns the tabs→panes default tree; row labels and focus state come
     * from [paneSidebarLabel] and [paneEditors] reachability.
     */

    /**
     * Builds the seed pane every fresh tab gets: a single full-bleed
     * (maximized) float. Geometry fields are kept on the persisted
     * shape for backwards compatibility but the toolkit's
     * `LAYOUT_STATE` is the runtime authority for pane positions —
     * treefacts only owns pane *identity* (id + title + the file the
     * editor body is viewing).
     */
    private fun seedPane(tabId: String): FloatingPaneSpec =
        FloatingPaneSpec(
            id = "pane-$tabId",
            title = null,
            xPct = 0.0,
            yPct = 0.0,
            widthPct = 1.0,
            heightPct = 1.0,
            zIndex = 1,
            isMaximized = true,
        )

    // ── Tab mutators ───────────────────────────────────────────────
    //
    // Wired into [TreeFactsTabSource]'s callbacks in [render]. Each
    // mutator updates [layoutState] (and [tabLayouts] where relevant),
    // then calls [persistLayoutState] which both writes through the
    // toolkit [Persister] and notifies the toolkit-shell tab source
    // so the chrome re-renders.

    private fun addTab() {
        var n = layoutState.tabs.size + 1
        while (layoutState.tabs.any { it.id == "tab-$n" }) n++
        val newTabId = "tab-$n"
        val newTab = TabState(
            id = newTabId,
            title = "Untitled",
            tree = null,
        )
        tabLayouts[newTabId] = PaneLayout(
            floatingPanes = listOf(seedPane(newTabId)),
        )
        layoutState = layoutState.copy(
            tabs = layoutState.tabs + newTab,
            activeTabId = newTabId,
        )
        persistLayoutState()
        notifyToolkitTabs?.invoke()
    }

    private fun closeTab(id: String) {
        if (layoutState.tabs.size <= 1) return
        val idx = layoutState.tabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        val newTabs = layoutState.tabs.toMutableList().also { it.removeAt(idx) }
        val newActive = if (layoutState.activeTabId == id) {
            newTabs[(idx).coerceAtMost(newTabs.lastIndex)].id
        } else layoutState.activeTabId
        tabLayouts.remove(id)
        layoutState = layoutState.copy(tabs = newTabs, activeTabId = newActive)
        persistLayoutState()
        notifyToolkitTabs?.invoke()
    }

    private fun setTabHidden(id: String, hidden: Boolean) {
        val newTabs = layoutState.tabs.map { t ->
            if (t.id == id) t.copy(isHidden = hidden) else t
        }
        // Hiding the active tab pushes activation to the first visible tab so
        // the user isn't left looking at a pane host with no chrome reference.
        val activeStillVisible =
            newTabs.firstOrNull { it.id == layoutState.activeTabId }?.isHidden == false
        val newActive = if (activeStillVisible) {
            layoutState.activeTabId
        } else {
            newTabs.firstOrNull { !it.isHidden }?.id ?: layoutState.activeTabId
        }
        layoutState = layoutState.copy(tabs = newTabs, activeTabId = newActive)
        persistLayoutState()
        notifyToolkitTabs?.invoke()
    }

    /**
     * Toggle a tab's `isHiddenFromSidebar` flag. Unlike [setTabHidden], the
     * tab stays in the strip and remains the active tab if it was — only
     * the left sidebar tree skips it on its next render. Mirrors the
     * "Hide / Show in side bar" affordance in termtastic's tab-bar overflow
     * menu.
     *
     * Calls [rebuildShell] (not just [refreshLeftSidebarSections]) so the
     * tab-bar overflow menu is rebuilt with the new label — its row
     * captures `activeIsHiddenFromSidebar` at build time, so without a
     * tab-bar re-render the next click would re-fire the same boolean and
     * the user could never toggle back.
     */
    private fun setTabHiddenFromSidebar(id: String, hidden: Boolean) {
        val newTabs = layoutState.tabs.map { t ->
            if (t.id == id) t.copy(isHiddenFromSidebar = hidden) else t
        }
        layoutState = layoutState.copy(tabs = newTabs)
        persistLayoutState()
        notifyToolkitTabs?.invoke()
    }

    private fun renameTab(id: String, newLabel: String) {
        val trimmed = newLabel.trim().ifBlank { "Untitled" }
        val newTabs = layoutState.tabs.map { t ->
            if (t.id == id) t.copy(title = trimmed) else t
        }
        layoutState = layoutState.copy(tabs = newTabs)
        persistLayoutState()
        notifyToolkitTabs?.invoke()
    }

    private fun reorderTab(sourceId: String, targetId: String, before: Boolean) {
        if (sourceId == targetId) return
        val tabs = layoutState.tabs.toMutableList()
        val sourceIdx = tabs.indexOfFirst { it.id == sourceId }
        if (sourceIdx < 0) return
        val sourceTab = tabs.removeAt(sourceIdx)
        var targetIdx = tabs.indexOfFirst { it.id == targetId }
        if (targetIdx < 0) {
            tabs.add(sourceTab)
        } else {
            if (!before) targetIdx += 1
            tabs.add(targetIdx, sourceTab)
        }
        layoutState = layoutState.copy(tabs = tabs)
        persistLayoutState()
        notifyToolkitTabs?.invoke()
    }

    // ── Pane chrome ─────────────────────────────────────────────────

    /**
     * Builds the breadcrumb segment list for [paneId]'s pane chrome.
     *
     * Returns an empty list when the pane is not zoomed (or the document
     * hasn't loaded yet) so the caller falls back to plain-string title
     * rendering. When zoomed, returns segments in this order:
     *   1. A leading "Home" segment that clears the zoom on click.
     *   2. One segment per ancestor (outer-to-inner), each navigating
     *      via `zoomTo(ancestor.lineId)`.
     *   3. The current zoom target as a leaf segment with no click
     *      handler (the user is already at that depth).
     */
    private fun paneZoomTitleSegments(paneId: String): List<PaneTitleSegment> {
        // Ensure the pane's view-model exists before reading its zoom
        // state. On the very first chrome render for a freshly-added pane
        // (where `renderPaneContent` hasn't mounted yet) the VM is absent
        // and we'd return empty — fine for segments, but the same
        // callback fires after every state change and we want to start
        // tracking zoom transitions immediately. Pre-creation is cheap
        // (empty VM) and idempotent — `ensurePaneViewModel` is
        // `getOrPut`-style.
        ensurePaneViewModel(paneId)
        val vm = paneViewModels[paneId] ?: return emptyList()
        val state = vm.stateFlow.value
        val backing = state.backingState ?: return emptyList()
        val zoom = vm.zoomInfo(backing) ?: return emptyList()
        val ancestors = vm.bulletAncestors(backing)
        if (ancestors.isEmpty() && zoom.titleText.isBlank()) return emptyList()
        val segments = mutableListOf<PaneTitleSegment>()
        // Leading segment is the active file's display name. Click clears
        // the zoom (back to the file's top), matching the old "Home"
        // behaviour on the root outline but generalising to any file.
        segments += PaneTitleSegment(
            label = activeFileDisplayName(paneId),
            onClick = { vm.zoomTo(null) },
        )
        // Line-level markers (`# `, `> `) are already stripped by
        // `bulletAncestors` / `zoomInfo`. We additionally flatten inline
        // markers (`**bold**`, `*italic*`, `` `code` ``, `~~strike~~`,
        // `[label](href)`) via the same tokenizer the editor's paint
        // loop uses, so the breadcrumb shows clean, plain-text labels
        // regardless of the underlying bullet's formatting. Navigation
        // is keyed on `lineId`, so the text-stripping never affects
        // where a click takes you.
        fun flat(s: String) = InlineMarkdownTokenizer.tokenize(s).displayText
        for (ancestor in ancestors) {
            val label = flat(ancestor.titleText).ifBlank { "(untitled)" }
            segments += PaneTitleSegment(
                label = label,
                onClick = { vm.zoomTo(ancestor.lineId) },
            )
        }
        segments += PaneTitleSegment(
            label = flat(zoom.titleText).ifBlank { "(untitled)" },
            onClick = null,
        )
        return segments
    }

    /**
     * Lazily creates the pane's [MainViewModel] + [PaneBackingViewModel]
     * the first time it's needed. Used by [renderPaneContent] when
     * mounting the editor DOM, and by the pane-chrome callbacks
     * ([paneZoomTitleSegments], [paneSidebarLabel]) which need to read
     * the pane's zoom path before content has mounted.
     *
     * Also installs the per-pane zoom-path collector that triggers a
     * chrome + sidebar refresh on every zoom transition.
     */
    private fun ensurePaneViewModel(paneId: String) {
        if (paneId in paneViewModels) return
        val docView = se.soderbjorn.treefacts.main.PaneBackingViewModel(
            documentRegistry,
            scope,
            initialFileRel = documentRegistry.rootFileName,
        )
        val paneVm = se.soderbjorn.treefacts.main.MainViewModel(scope, docView)
        paneViewModels[paneId] = paneVm
        // Refresh chrome + sidebar whenever this pane's zoom path or
        // back/forward stack availability changes. Path changes drive the
        // breadcrumb; stack-availability changes drive whether the
        // back/forward toolbar buttons appear. Distinct-by-tuple keeps
        // every keystroke from triggering a full chrome rebuild — only
        // true navigation transitions re-render.
        scope.launch {
            paneVm.stateFlow
                .map { state ->
                    val backing = state.backingState
                    // Tuple of everything the pane chrome cares about:
                    // active file path (so file switches re-render the
                    // breadcrumb), zoom path, and back/forward stack
                    // availability for the toolbar buttons.
                    listOf<Any?>(
                        backing?.activeFileRel,
                        backing?.let { paneVm.zoomPathSegments(it) } ?: emptyList<String>(),
                        backing != null && paneVm.canZoomBack(backing),
                        backing != null && paneVm.canZoomForward(backing),
                    )
                }
                .distinctUntilChanged()
                // Drop the very first emission — it's the pane's
                // initial state at collector start, fired
                // synchronously when collect() begins. The chrome was
                // already rendered with that state when the pane
                // mounted, so refreshing again would just trigger a
                // redundant rerender that wipes any in-flight CSS
                // transition (e.g. the new-pane entry pop-in or the
                // restore-from-maximize on an existing pane).
                .drop(1)
                .collect {
                    val active = layoutState.activeTabId
                    val activeIds = tabLayouts[active]?.floatingPanes?.map { it.id }.orEmpty()
                    if (paneId in activeIds) {
                        // Snapshot the pane's outgoing DOM into a body-level
                        // overlay BEFORE the toolkit rebuild wipes the
                        // floating-pane layout container. The overlay lives on
                        // `document.body`, which the rebuild doesn't touch,
                        // so it survives the wipe and covers the otherwise-
                        // empty pane while `screen.render` re-attaches the
                        // existing MainScreen elements into the freshly-built
                        // `.dt-pane`. Without this pre-snapshot a single-
                        // frame paint can slip through that window and leak
                        // the page background as a visible blink before the
                        // crossfade starts.
                        paneEditors[paneId]?.prepareNavigationCrossfade()
                        notifyToolkitTabs?.invoke()
                    }
                    notifyToolkitTabs?.invoke()
                    // Rebuild the per-pane chrome header so its action
                    // buttons (back/forward/up/home) reflect the new
                    // zoom-stack availability. `notifyToolkitTabs` only
                    // pushes a fresh TabListSnapshot (tab + pane
                    // structure); it doesn't re-invoke the
                    // `paneActions` callback. `shellHandle.refresh()`
                    // does — see [AppShellHandle.refresh].
                    shellHandle?.refresh()
                }
        }
    }

    /**
     * Display name of the file currently loaded in [paneId] — see
     * [NoteRepository.displayNameOf]. Falls back to "Home" when the
     * pane's view model hasn't booted yet.
     */
    private fun activeFileDisplayName(paneId: String): String {
        val backing = paneViewModels[paneId]?.stateFlow?.value?.backingState ?: return "Home"
        val fileRel = backing.activeFileRel
        if (fileRel.isEmpty()) return "Home"
        return NoteRepository.displayNameOf(fileRel).ifBlank { "Home" }
    }

    /**
     * Returns the joined zoom path for [paneId], or `null` if the pane is
     * not currently zoomed (or its view-model hasn't booted yet).
     */
    private fun zoomPathStringForPane(paneId: String): String? {
        val backing = paneViewModels[paneId]?.stateFlow?.value?.backingState ?: return null
        val segments = paneViewModels[paneId]?.zoomPathSegments(backing) ?: return null
        if (segments.isEmpty()) return null
        return segments.joinToString(" / ") { it.ifBlank { "(untitled)" } }
    }

    /**
     * Installs the global `selectionchange` listener that drives
     * [panesWithChromeSelection]. Called once from [render]; subsequent
     * calls are no-ops via [chromeSelectionTrackerInstalled].
     *
     * One document-level listener is sufficient because the browser
     * only maintains a single DOM selection per window — there is no
     * pane-local selection state to track separately.
     */
    private fun installChromeSelectionTracker() {
        if (chromeSelectionTrackerInstalled) return
        chromeSelectionTrackerInstalled = true
        document.addEventListener("selectionchange", { _ -> refreshChromeSelectionState() })
    }

    /**
     * Recomputes [panesWithChromeSelection] from the current DOM
     * selection. If the set changes, refreshes the pane chrome for the
     * panes whose flag flipped — that re-invokes the toolkit's
     * `paneActions` callback so the Style button's enabled/disabled
     * class updates.
     *
     * Chrome surfaces considered "non-editable" for this purpose:
     * - `.treefacts-title` — the big zoom headline above the editor.
     * - `.dt-pane-breadcrumb-segment` — the clickable breadcrumb labels
     *   in the pane header (rendered by the toolkit).
     *
     * Walks BOTH `anchorNode` and `focusNode` so a drag-select with
     * either endpoint on the chrome counts.
     */
    private fun refreshChromeSelectionState() {
        // The Kotlin/JS stdlib's `window` doesn't surface `getSelection()`
        // as a typed property — go through `asDynamic()` (matching the
        // pattern used throughout MainScreen). Same for the selection's
        // node accessors.
        val sel = window.asDynamic().getSelection()
        val newSet = mutableSetOf<String>()
        if (sel != null && sel.isCollapsed == false) {
            val endpoints = listOfNotNull(
                sel.anchorNode as? Node,
                sel.focusNode as? Node,
            )
            for (node in endpoints) {
                val startEl: Element? = node as? Element
                    ?: (node.asDynamic().parentNode as? Element)
                val chromeEl = startEl?.closest(".treefacts-title, .dt-pane-breadcrumb-segment")
                    ?: continue
                val paneEl = chromeEl.closest("[data-pane-id]")
                val paneId = paneEl?.getAttribute("data-pane-id") ?: continue
                newSet += paneId
            }
        }
        if (newSet == panesWithChromeSelection) return
        val toRefresh = (panesWithChromeSelection + newSet) -
            (panesWithChromeSelection intersect newSet)
        panesWithChromeSelection.clear()
        panesWithChromeSelection += newSet
        if (toRefresh.isNotEmpty()) {
            // `shellHandle.refresh()` re-invokes `paneActions` for every
            // pane — cheap, and avoids needing a per-pane refresh API.
            shellHandle?.refresh()
        }
    }

    /**
     * Walks one step up the bullet hierarchy in the pane identified by
     * [paneId]. If the current zoom has at least one ancestor bullet, jumps
     * to the closest one; otherwise (already at a root-level bullet, or
     * the pane hasn't been rendered yet) clears the zoom so the user lands
     * at the document root. Backed by the same `zoomTo` intent the
     * breadcrumb trail uses.
     *
     * @param paneId leaf pane id used to look up the per-pane [MainViewModel].
     */
    private fun zoomPaneUpOneLevel(paneId: String) {
        val paneVm = paneViewModels[paneId] ?: return
        val backing = paneVm.stateFlow.value.backingState ?: return
        val ancestors = paneVm.bulletAncestors(backing)
        when {
            // Inside a bullet zoom: walk one ancestor up (or clear the
            // zoom if we were already at a root-level zoomed bullet).
            backing.zoomedLineId != null -> paneVm.zoomTo(ancestors.lastOrNull()?.lineId)
            // No bullet zoom — interpret "up" as "go to the parent file".
            else -> {
                val fileRel = backing.activeFileRel
                if (fileRel.isEmpty()) return
                val parentRel = parentFileOf(fileRel, paneVm.rootFileName) ?: return
                paneVm.navigateToVaultFile(parentRel)
            }
        }
    }

    /**
     * "Home" handler for the pane toolbar. Clears any active bullet zoom
     * and, when the pane is on a non-root file, also switches to the
     * configured root file. Both operations push onto the unified
     * back-stack so the user can return via the back button.
     */
    private fun goPaneHome(paneId: String) {
        val paneVm = paneViewModels[paneId] ?: return
        val backing = paneVm.stateFlow.value.backingState ?: return
        if (backing.zoomedLineId != null) paneVm.zoomTo(null)
        val fileRel = backing.activeFileRel
        if (fileRel.isNotEmpty() && fileRel != paneVm.rootFileName) {
            paneVm.navigateToVaultFile(paneVm.rootFileName)
        }
    }

    /**
     * Resolves the node outline one level up from [fileRel], following
     * the folder-per-bullet layout: a node's outline is
     * `<folder>/.treefacts`, and its parent is the enclosing folder's.
     *
     *  - `Recipes/Pasta/.treefacts` → `Recipes/.treefacts`
     *  - `Recipes/.treefacts`       → [rootFileName]
     *  - `Recipes/notes.md`, `Recipes/pic.png` → `Recipes/.treefacts`
     *  - `notes.md` (at the vault root) → [rootFileName]
     *
     * Path-based; does not check that the parent outline exists (a
     * folder without one is still a node, just with no bullets yet).
     *
     * @param fileRel      vault-relative path of the current file.
     * @param rootFileName vault-relative path of the root outline.
     * @return the parent outline's vault-relative path, or `null` for the
     *   root outline itself.
     */
    private fun parentFileOf(fileRel: String, rootFileName: String): String? {
        if (fileRel == rootFileName) return null
        val folder = if (NoteRepository.isOutlineFile(fileRel)) {
            NoteRepository.folderOfOutline(fileRel).substringBeforeLast('/', "")
        } else {
            fileRel.substringBeforeLast('/', "")
        }
        return if (folder.isEmpty()) rootFileName else NoteRepository.outlineFileOf(folder)
    }

    /**
     * Builds the trailing-action strip for [paneId]'s pane header:
     * back/forward navigation buttons (always shown — dimmed via the
     * [DISABLED_CLASS] modifier when the corresponding stack is empty so
     * their position stays stable for muscle memory), then the "up one
     * level" + "home (root)" buttons, then a separator before the
     * toolkit's window-control strip.
     *
     * Since the toolkit's [PaneAction] has no native `disabled` flag, we
     * wrap each handler in a no-op when the action would be inert and
     * tag the button with [DISABLED_CLASS]; the matching CSS rule
     * injected by [ensureTreeFactsChromeStyles] paints it grayed-out.
     *
     * The back/forward buttons use custom-arrow SVG glyphs constructed
     * inline because [PaneActions] only ships the `up` and `home`
     * factories — see toolkit-web's `PaneActions.kt`.
     */
    private fun buildPaneNavActions(paneId: String): List<PaneAction> {
        val paneVm = paneViewModels[paneId]
        val backing = paneVm?.stateFlow?.value?.backingState
        val canBack = backing != null && paneVm.canZoomBack(backing)
        val canForward = backing != null && paneVm.canZoomForward(backing)
        val canUp = backing != null && canNavigateUp(paneVm, backing)
        val canHome = backing != null && !isAtRootFileWithNoZoom(paneVm, backing)
        val out = mutableListOf<PaneAction>()
        out += PaneAction(
            iconHtml = ICON_BACK,
            tooltip = "Back",
            handler = if (canBack) ({ paneViewModels[paneId]?.zoomBack() }) else ({}),
            extraClass = "dt-pane-action-back" + if (!canBack) " $DISABLED_CLASS" else "",
        )
        out += PaneAction(
            iconHtml = ICON_FORWARD,
            tooltip = "Forward",
            handler = if (canForward) ({ paneViewModels[paneId]?.zoomForward() }) else ({}),
            extraClass = "dt-pane-action-forward" + if (!canForward) " $DISABLED_CLASS" else "",
        )
        out += PaneAction(
            iconHtml = PaneActions.ICON_UP,
            tooltip = "Up one level",
            handler = if (canUp) ({ zoomPaneUpOneLevel(paneId) }) else ({}),
            extraClass = "dt-pane-action-up" + if (!canUp) " $DISABLED_CLASS" else "",
        )
        out += PaneAction(
            iconHtml = PaneActions.ICON_HOME,
            tooltip = "Go to root",
            handler = if (canHome) ({ goPaneHome(paneId) }) else ({}),
            extraClass = "dt-pane-action-home" + if (!canHome) " $DISABLED_CLASS" else "",
        )
        // Style is enabled whenever the pane has a VM AND the user
        // does not currently have a live DOM selection on a read-only
        // chrome surface of this pane (the `.treefacts-title` headline
        // or one of the `.dt-pane-breadcrumb-segment` labels). In the
        // latter case the user is visually addressing text they cannot
        // edit — opening the Style dropdown would silently apply the
        // chosen style to the editor's stale model cursor, which is
        // confusing. The chrome-selection flag is maintained by
        // [refreshChromeSelectionState], wired off `selectionchange`.
        val canStyle = paneVm != null && paneId !in panesWithChromeSelection
        out += PaneAction(
            iconHtml = StyleDropdownIcons.TOOLBAR_STYLE,
            tooltip = "Style",
            handler = if (canStyle) ({ openStyleMenu(paneId) }) else ({}),
            extraClass = "treefacts-pane-action-style" + if (!canStyle) " $DISABLED_CLASS" else "",
        )
        // Starred lives in the tab toolbar (see [buildTopbarStarredAction]).
        // The toolkit auto-inserts a separator between this list and its
        // standard window-control cluster — no manual `separator()` needed.
        return out
    }

    /**
     * Toggles the style dropdown for [paneId]. Looks up the rendered
     * Style toolbar button by class within the pane's chrome and anchors
     * the popover under it. Lazily creates a [StyleDropdown] per pane.
     */
    private fun openStyleMenu(paneId: String) {
        val paneVm = paneViewModels[paneId] ?: return
        val button = document.querySelector(
            "[data-pane-id='$paneId'] .dt-pane-action.treefacts-pane-action-style"
        ) as? HTMLElement ?: return
        val dropdown = styleDropdowns.getOrPut(paneId) { StyleDropdown(paneVm) }
        dropdown.open(button)
    }

    /**
     * Opens (or replaces, on subsequent clicks) [paneId]'s Starred
     * bookmarks modal. The modal reads `Starred.md` via its own private
     * read-only document VM, paints it through the same outline renderer
     * the live editor uses, and routes "Add to starred" + click-to-open
     * back at *this* pane's [MainViewModel].
     *
     * Lazily created and reused across opens; only the modal's internal
     * document VM is rebuilt on each open so a fresh disk snapshot is
     * always shown.
     */
    private fun openStarredModal(paneId: String) {
        if (paneViewModels[paneId] == null) return
        val modal = starredModals.getOrPut(paneId) {
            StarredModal(
                parentScope = scope,
                activePaneVmProvider = { paneViewModels[paneId] },
                vaultRoot = documentRegistry.rootDirectory,
            )
        }
        modal.open()
    }

    /**
     * Opens the per-pane Insert Link modal. Same lifecycle pattern as
     * [openStarredModal] — lazy first-open create, reuse thereafter,
     * one modal per pane keyed by [paneId].
     */
    private fun openInsertLinkModal(paneId: String) {
        if (paneViewModels[paneId] == null) return
        val modal = insertLinkModals.getOrPut(paneId) {
            LinkSearchModal.forInsertLink(
                parentScope = scope,
                activePaneVmProvider = { paneViewModels[paneId] },
                onAfterPick = { paneEditors[paneId]?.focusEditor() },
            )
        }
        modal.open()
    }

    /**
     * Opens the per-pane Navigate-to modal. Same lifecycle pattern as
     * [openInsertLinkModal] — lazy first-open create, reuse thereafter.
     * Reachable from the command palette ("Navigate to") and the
     * Cmd-O shortcut.
     */
    private fun openNavigateToModal(paneId: String) {
        if (paneViewModels[paneId] == null) return
        val modal = navigateToModals.getOrPut(paneId) {
            LinkSearchModal.forNavigateTo(
                parentScope = scope,
                activePaneVmProvider = { paneViewModels[paneId] },
                onAfterPick = { paneEditors[paneId]?.focusEditor() },
            )
        }
        modal.open()
    }

    /**
     * Opens the per-pane Insert Image modal. Same lifecycle pattern as
     * [openInsertLinkModal]: lazy first-open create, reuse thereafter,
     * cleared in [closePane].
     */
    private fun openInsertImageModal(paneId: String) {
        if (paneViewModels[paneId] == null) return
        val modal = insertImageModals.getOrPut(paneId) {
            ImageSearchModal(
                parentScope = scope,
                activePaneVmProvider = { paneViewModels[paneId] },
                onAfterPick = { paneEditors[paneId]?.focusEditor() },
            )
        }
        modal.open()
    }

    /**
     * Injects treefacts-only chrome styles that aren't part of the toolkit
     * stylesheet: the disabled state for nav buttons (back/forward/up/
     * home stay in place when inert, dimmed instead of removed) and the
     * one-shot fade-in animation that plays on a navigation transition
     * (file switch or zoom change). Idempotent via the element id guard.
     */
    private fun ensureTreeFactsChromeStyles() {
        if (document.getElementById("treefacts-chrome-style") != null) return
        val style = document.createElement("style") as HTMLElement
        style.id = "treefacts-chrome-style"
        style.textContent = """
            .dt-pane-action.$DISABLED_CLASS {
                opacity: 0.32;
                pointer-events: none;
                cursor: default;
            }
            /* ── Modal action button (termtastic-style) ─────────────────
               Filled accent button for modal dialogs' primary action
               (e.g. a confirm action). Mirrors termtastic's
               .news-update-download pattern: accent fill, 6px radius,
               bold small label, hover brightens, active presses down.
               The palette rows are keyboard-highlight driven and have
               no :hover, so modals need their own button class for a
               real click affordance. */
            .treefacts-modal-btn {
                display: block;
                margin: 4px 14px 14px;
                width: calc(100% - 28px);
                padding: 7px 16px;
                border: none;
                border-radius: 6px;
                background: var(--t-accent, #7aa2f7);
                color: var(--t-bg, #1b1b1b);
                font-size: 13px;
                font-weight: 700;
                text-align: center;
                cursor: pointer;
                transition: filter 100ms ease, transform 60ms ease;
            }
            .treefacts-modal-btn:hover {
                filter: brightness(1.12);
            }
            .treefacts-modal-btn:active {
                filter: brightness(0.92);
                transform: translateY(1px);
            }
            .treefacts-modal-btn:focus-visible {
                outline: 2px solid var(--t-text, #e6e6e6);
                outline-offset: 2px;
            }
            /* ── Sidebar brand logo (termtastic-style) ──────────────────
               Status dot + lowercase "treefacts" wordmark in the left
               sidebar's header slot (built in AppLogo.kt). The dot shows
               SAVE state: steady = everything flushed to disk, breathing
               (JS rAF-driven opacity, not a CSS animation — a re-parented
               element would restart a keyframe and snap to full
               brightness) = unsaved edits pending autosave. */
            .app-logo {
                display: flex;
                align-items: center;
                user-select: none;
            }
            /* The dot and wordmark share one horizontal row. */
            .app-logo-row {
                display: flex;
                align-items: center;
                gap: 9px;
            }
            .app-logo-wordmark {
                /* Monospaced wordmark so the brand reads terminal-style
                   lowercase, matching the termtastic sibling app. */
                font-family: var(--dt-font-mono, 'JetBrains Mono', ui-monospace, monospace);
                font-size: 15px;
                font-weight: 700;
                letter-spacing: 0.3px;
                /* The toolkit's .dt-sidebar-header slot forces uppercase;
                   override back so the wordmark reads "treefacts". */
                text-transform: none;
                /* Theme's main foreground token, not the header slot's
                   dimmed color, so the brand reads as a wordmark. */
                color: var(--t-text, #F5F5F5);
                line-height: 1;
            }
            /* The save-state bead: painted in the theme foreground so it
               meshes with any theme. Base rule = steady "all saved"
               light; .state-unsaved makes it breathe via the JS pulse. */
            .app-logo-dot {
                display: inline-block;
                width: 10px;
                height: 10px;
                border-radius: 50%;
                background: var(--t-text, #f5f5f5);
                /* Steady glow in the same foreground colour; color-mix
                   keeps the halo tied to --t-text. */
                box-shadow: 0 0 8px color-mix(in srgb, var(--t-text, #f5f5f5) 55%, transparent),
                            0 0 16px color-mix(in srgb, var(--t-text, #f5f5f5) 28%, transparent);
                flex-shrink: 0;
            }
            .app-logo-dot.state-unsaved {
                /* Opacity driven by the JS pulse loop (AppLogo.kt). Own
                   compositor layer while pulsing so per-frame repaints
                   stay inside the bead's backing store (termtastic#37:
                   at fractional device-pixel-ratios the repaint otherwise
                   bleeds into the chrome seams and flickers). */
                will-change: opacity;
                transform: translateZ(0);
                backface-visibility: hidden;
            }
            @keyframes treefacts-nav-fade-in {
                from { opacity: 0; transform: translateY(2px); }
                to   { opacity: 1; transform: translateY(0); }
            }
            .treefacts-editor.treefacts-nav-fade,
            .treefacts-title.treefacts-nav-fade {
                animation: treefacts-nav-fade-in 500ms ease-out;
            }
            /* StarredModal reuses the palette backdrop + panel so it
               looks identical to the Cmd-O navigation modal. The few
               .treefacts-starred-* rules below tweak the header bar
               that replaces the palette's text input — there's no
               type-to-filter for bookmarks, so the slot is repurposed
               as a title + Add + Close row. */
            .treefacts-starred-header {
                display: flex;
                align-items: center;
                gap: 12px;
                padding: 10px 14px;
                border-bottom: 1px solid var(--t-border, rgba(255, 255, 255, 0.10));
            }
            .treefacts-starred-title {
                font-size: 14px;
                font-weight: 600;
                opacity: 0.85;
                margin-right: auto;
            }
            .treefacts-starred-add {
                display: inline-flex;
                align-items: center;
                gap: 6px;
                background: transparent;
                border: 1px solid var(--t-border, rgba(255, 255, 255, 0.18));
                border-radius: 6px;
                color: inherit;
                padding: 4px 10px;
                font-size: 12px;
                cursor: pointer;
            }
            .treefacts-starred-add:hover {
                background: rgba(255, 255, 255, 0.06);
            }
            .treefacts-starred-add.is-active {
                background: rgba(255, 200, 60, 0.18);
                border-color: rgba(255, 200, 60, 0.55);
                color: rgb(255, 210, 90);
            }
            .treefacts-starred-add-icon {
                display: inline-flex;
                width: 14px;
                height: 14px;
            }
            .treefacts-starred-close {
                background: transparent;
                border: none;
                color: inherit;
                font-size: 22px;
                line-height: 1;
                padding: 0 4px;
                cursor: pointer;
                opacity: 0.7;
            }
            .treefacts-starred-close:hover { opacity: 1; }
            /* Command palette (Cmd-P). Surface colors match the toolkit
               variables so the palette inherits the active theme. */
            .treefacts-palette-backdrop {
                position: fixed;
                inset: 0;
                background: rgba(0, 0, 0, 0.45);
                z-index: 2147483641;
                display: flex;
                align-items: flex-start;
                justify-content: center;
                padding-top: 12vh;
            }
            .treefacts-palette-panel {
                width: min(620px, 92vw);
                max-height: 64vh;
                display: flex;
                flex-direction: column;
                background: var(--t-bg, #1e1e1e);
                color: var(--t-text, #e6e6e6);
                border: 3px solid var(--t-accent, #5ab0ff);
                border-radius: 14px;
                box-shadow:
                    0 0 0 1px rgba(0, 0, 0, 0.65),
                    0 0 0 6px color-mix(in srgb, var(--t-accent, #5ab0ff) 22%, transparent),
                    0 1px 0 rgba(255, 255, 255, 0.06) inset,
                    0 28px 72px rgba(0, 0, 0, 0.65),
                    0 10px 24px rgba(0, 0, 0, 0.45);
                overflow: hidden;
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
            }
            .treefacts-palette-input {
                appearance: none;
                background: transparent;
                color: inherit;
                border: 0;
                border-bottom: 1px solid var(--t-border, rgba(255, 255, 255, 0.10));
                outline: none;
                padding: 14px 18px;
                font-size: 17px;
                font-family: inherit;
            }
            .treefacts-palette-input::placeholder {
                color: var(--t-text-dim, rgba(255, 255, 255, 0.45));
            }
            .treefacts-palette-list {
                flex: 1 1 auto;
                min-height: 0;
                overflow-y: auto;
                padding: 4px;
            }
            .treefacts-palette-item {
                display: block;
                width: 100%;
                appearance: none;
                background: transparent;
                color: inherit;
                border: 0;
                text-align: left;
                padding: 9px 14px;
                font-size: 15px;
                font-family: inherit;
                letter-spacing: 0.01em;
                border-radius: 5px;
                cursor: pointer;
            }
            .treefacts-palette-item.is-active {
                background: color-mix(in srgb, var(--t-accent, #5ab0ff) 22%, transparent);
                color: var(--t-text, inherit);
            }
            .treefacts-palette-empty {
                padding: 16px;
                font-size: 12px;
                color: var(--t-text-dim, rgba(255, 255, 255, 0.55));
                text-align: center;
            }
            /* Insert Link modal — same visuals as the palette plus a
               two-line layout: bold title + muted breadcrumb. */
            .treefacts-link-item {
                padding: 7px 14px;
                line-height: 1.25;
            }
            .treefacts-link-item-title {
                font-size: 15px;
                font-weight: 500;
            }
            .treefacts-link-item-crumb {
                font-size: 12px;
                color: var(--t-text-dim, rgba(255, 255, 255, 0.55));
                margin-top: 2px;
                white-space: nowrap;
                overflow: hidden;
                text-overflow: ellipsis;
            }
            .treefacts-link-item-path {
                font-size: 11px;
                color: var(--t-text-dim, rgba(255, 255, 255, 0.40));
                margin-top: 1px;
                font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
                white-space: nowrap;
                overflow: hidden;
                text-overflow: ellipsis;
            }
            /* Insert Image modal — thumbnail on the left, filename +
               vault-relative path on the right. */
            .treefacts-image-item {
                display: flex;
                gap: 12px;
                align-items: center;
                padding: 6px 14px;
                line-height: 1.25;
            }
            .treefacts-image-item-thumb {
                width: 48px;
                height: 48px;
                object-fit: cover;
                border-radius: 4px;
                background: var(--t-border, rgba(255, 255, 255, 0.06));
                flex: 0 0 auto;
            }
            .treefacts-image-item-meta {
                min-width: 0;
                flex: 1 1 auto;
            }
            .treefacts-image-item-title {
                font-size: 14px;
                font-weight: 500;
                white-space: nowrap;
                overflow: hidden;
                text-overflow: ellipsis;
            }
            .treefacts-image-item-path {
                font-size: 11px;
                color: var(--t-text-dim, rgba(255, 255, 255, 0.40));
                margin-top: 1px;
                font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
                white-space: nowrap;
                overflow: hidden;
                text-overflow: ellipsis;
            }
            /* Inline image resize popover (Slice 4). Floats next to a
               clicked image; same surface palette as the modals. */
            .treefacts-image-popover {
                position: fixed;
                z-index: 1000;
                display: flex;
                gap: 6px;
                align-items: center;
                padding: 6px 8px;
                border-radius: 6px;
                background: var(--t-surface-alt, rgba(30, 30, 30, 0.95));
                border: 1px solid var(--t-border, rgba(255, 255, 255, 0.10));
                color: var(--t-text, #e6e6e6);
                font-size: 13px;
                box-shadow: 0 8px 24px rgba(0, 0, 0, 0.35);
            }
            .treefacts-image-popover-label { color: var(--t-text-dim, rgba(255, 255, 255, 0.55)); }
            .treefacts-image-popover-input {
                width: 80px;
                padding: 2px 6px;
                border-radius: 4px;
                border: 1px solid var(--t-border, rgba(255, 255, 255, 0.15));
                background: var(--t-surface, rgba(0, 0, 0, 0.30));
                color: inherit;
                font: inherit;
            }
            .treefacts-image-popover-apply,
            .treefacts-image-popover-clear {
                padding: 2px 10px;
                border-radius: 4px;
                border: 1px solid var(--t-border, rgba(255, 255, 255, 0.15));
                background: var(--t-surface, rgba(0, 0, 0, 0.20));
                color: inherit;
                font: inherit;
                cursor: pointer;
            }
            .treefacts-image-popover-apply:hover,
            .treefacts-image-popover-clear:hover {
                background: var(--t-border, rgba(255, 255, 255, 0.10));
            }
            /* Hotkeys-modal stylesheet ships with the toolkit's
               [ToolkitHotkeysModal]; no app-side injection needed. */
            /* Keyboard-focus highlight on the layout-preset tiles — same
               surface as :hover so mouse + keyboard agree. */
            .dt-layout-preset-tile.is-focused,
            .dt-layout-preset-tile.is-focused:focus,
            .dt-layout-preset-tile.is-focused:focus-visible {
                background: var(--t-surface-alt, rgba(255, 255, 255, 0.08));
                border-color: var(--t-accent, rgba(255, 255, 255, 0.20));
                color: var(--t-text, #e6e6e6);
                outline: none;
            }
        """.trimIndent()
        document.head?.appendChild(style)
    }

    /**
     * `true` when the up-one-level action has somewhere to go: either the
     * pane is currently zoomed (so up walks the bullet tree) or the active
     * file is a non-root file (so up navigates to the parent file).
     */
    private fun canNavigateUp(
        paneVm: MainViewModel,
        backing: se.soderbjorn.treefacts.main.PaneBackingViewModel.State,
    ): Boolean {
        if (backing.zoomedLineId != null) return true
        val fileRel = backing.activeFileRel
        if (fileRel.isEmpty()) return false
        return parentFileOf(fileRel, paneVm.rootFileName) != null
    }

    /**
     * `true` when the pane is already showing the configured root file
     * with no zoom — i.e. there's nowhere to go via "home".
     */
    private fun isAtRootFileWithNoZoom(
        paneVm: MainViewModel,
        backing: se.soderbjorn.treefacts.main.PaneBackingViewModel.State,
    ): Boolean {
        if (backing.zoomedLineId != null) return false
        return backing.activeFileRel == paneVm.rootFileName
    }

    /**
     * Renders the in-pane content. Every pane is an editor onto the
     * shared global document — the original `editorPaneId` special-case
     * is gone (#15). Each pane id gets its own [MainScreen] backed by
     * its own [MainViewModel] / [PaneBackingViewModel] so zoom
     * navigation, selection, and caret are pane-local while every edit
     * mutates the shared [documentRegistry].
     */
    private fun renderPaneContent(id: String, slot: HTMLElement) {
        val container = document.createElement("div") as HTMLElement
        container.style.apply {
            // Flex item in the outer pane-content wrapper. `flex: 1 1 auto`
            // + `min-height: 0` is what lets the editor's scroll wrapper
            // (a flex child further down) actually overflow and scroll
            // instead of growing the chain to content height.
            setProperty("flex", "1 1 auto")
            setProperty("min-height", "0")
            setProperty("min-width", "0")
            background = "var(--t-bg, #1e1e1e)"
            color = "var(--t-text, #e6e6e6)"
            setProperty("overflow", "hidden")
        }
        slot.appendChild(container)
        // Reuse the existing pane editor when present so the user's zoom
        // focus and selection survive tab switches and re-renders. Build
        // a fresh per-pane VM stack on first render.
        ensurePaneViewModel(id)
        val screen = paneEditors.getOrPut(id) {
            MainScreen(
                paneViewModels.getValue(id),
                scope,
                onShiftClickInternalLink = { href ->
                    val tabId = layoutState.activeTabId ?: return@MainScreen
                    openLinkInNewPane(tabId, sourcePaneId = id, href = href)
                },
            )
        }
        screen.render(container)
    }

    /**
     * Adds a new pane to [tabId] at a randomised position with the highest
     * z-index so it lands on top. Persisted via [TabState.floatingPanes]
     * so panes survive reload. In the floats-only model every pane lives
     * here — there is no separate split tree.
     */
    private fun addFloatingPane(
        tabId: String,
        parentPaneId: String? = lastFocusedPaneIdByTab[tabId],
    ): String? {
        val cur = tabLayouts[tabId] ?: return null
        val existingIds = cur.floatingPanes.map { it.id }.toSet()
        var n = existingIds.size + 1
        var newId = "$tabId-pane-$n"
        while (newId in existingIds) {
            n++
            newId = "$tabId-pane-$n"
        }
        val topZ = cur.floatingPanes.maxOfOrNull { it.zIndex } ?: 0
        val spec = se.soderbjorn.lunula.web.layout.randomFloatingPaneSpec(
            id = newId,
            // Leave the spec title null so the chrome falls through to
            // the zoom path / "Home" label. Setting "Untitled" here would
            // pollute the chrome with a placeholder string for every
            // freshly-added pane.
            title = null,
            zIndex = topZ + 1,
        )
        // Adding a pane unmaximizes any existing maximized pane (mirrors
        // termtastic) so the user is never trapped in a full-bleed view
        // when they want to start working with another pane.
        tabLayouts[tabId] = cur.copy(
            floatingPanes = cur.floatingPanes.withNoneMaximized() + spec,
        )
        // Record parent linkage for Auto layout. The originating pane
        // (active at creation) gets to keep the second-largest slot so
        // it isn't demoted just because the new child stole focus.
        if (parentPaneId != null && parentPaneId != newId) {
            parentByPane[newId] = parentPaneId
        }
        // Promote the new pane to "focused" before persistLayoutState's
        // toolkit-notify fires, so the resulting snapshot's activePaneId
        // points at the new pane and the renderer's focus class lands
        // there. Setting it after the persist call would land the focus
        // on the old pane until the next mutation.
        lastFocusedPaneIdByTab[tabId] = newId
        persistLayoutState()
        return newId
    }

    /**
     * Opens the TreeFacts internal link [href] in a new pane spawned
     * from [sourcePaneId]. Wired via [MainScreen.onShiftClickInternalLink]
     * so shift-clicking a `#treefacts-bullet=…` link creates a new pane
     * rooted at the link target, leaving the originating pane
     * untouched. Auto layout (if active) immediately re-tiles to fit
     * both panes.
     */
    private fun openLinkInNewPane(tabId: String, sourcePaneId: String, href: String) {
        val newId = addFloatingPane(tabId, parentPaneId = sourcePaneId) ?: return
        ensurePaneViewModel(newId)
        val paneVm = paneViewModels[newId] ?: return
        // The brand-new pane's document hasn't loaded yet —
        // [PaneBackingViewModel.navigateToLink] silently no-ops when
        // `state.isLoaded == false`. Wait for the first loaded
        // emission before dispatching the navigation so the new pane
        // actually lands on the link target instead of the root.
        scope.launch {
            paneVm.stateFlow.first { it.backingState?.isLoaded == true }
            paneVm.navigateToLink(href)
        }
    }

    /** Bumps [paneId]'s z-index to `max(existing) + 1` so it lands on top. */
    private fun bringFloatingPaneToFront(tabId: String, paneId: String) {
        val cur = tabLayouts[tabId] ?: return
        val topZ = cur.floatingPanes.maxOfOrNull { it.zIndex } ?: 0
        // Already on top — skip the re-render to keep mousedown handlers cheap.
        if (cur.floatingPanes.firstOrNull { it.id == paneId }?.zIndex == topZ) return
        tabLayouts[tabId] = cur.copy(
            floatingPanes = cur.floatingPanes.map { f ->
                if (f.id == paneId) f.copy(zIndex = topZ + 1) else f
            },
        )
        persistLayoutState()
        notifyToolkitTabs?.invoke()
    }

    /**
     * Moves the pane identified by [sourcePaneId] to the tab identified
     * by [destTabId]. Floats-only model: searches each tab's float list
     * for the source, then moves the spec verbatim to the destination
     * (geometry, title, max/min flags preserved; zIndex bumped to land on
     * top of any existing floats in the destination). No-op when the
     * source isn't found, when moving the only pane out of a tab (the
     * tab would become empty), or when source and destination are the
     * same tab.
     */
    private fun movePaneToTab(sourcePaneId: String, destTabId: String) {
        if (destTabId !in tabLayouts.keys) return
        var sourceTabId: String? = null
        var existingFloat: FloatingPaneSpec? = null
        for ((tabId, layout) in tabLayouts) {
            val match = layout.floatingPanes.firstOrNull { it.id == sourcePaneId }
            if (match != null) {
                sourceTabId = tabId
                existingFloat = match
                break
            }
        }
        if (sourceTabId == null || sourceTabId == destTabId || existingFloat == null) return
        val sourceLayout = tabLayouts[sourceTabId] ?: return
        // Don't strand a tab with zero panes — keep the source if removing
        // this pane would leave it empty.
        if (sourceLayout.floatingPanes.size <= 1) return
        tabLayouts[sourceTabId] = sourceLayout.copy(
            floatingPanes = sourceLayout.floatingPanes.filterNot { it.id == sourcePaneId },
        )
        val destLayout = tabLayouts[destTabId] ?: return
        val topZ = destLayout.floatingPanes.maxOfOrNull { it.zIndex } ?: 0
        tabLayouts[destTabId] = destLayout.copy(
            floatingPanes = destLayout.floatingPanes + existingFloat.copy(zIndex = topZ + 1),
        )
        // Activate the destination tab so the user sees the pane land.
        layoutState = layoutState.copy(activeTabId = destTabId)
        persistLayoutState()
        notifyToolkitTabs?.invoke()
    }

    /** Flips the matching float's `isMaximized` flag. The toolkit re-paints
     *  the pane full-bleed (CSS `.dt-maximized`) and disables drag/resize. */
    private fun toggleFloatingPaneMaximized(tabId: String, paneId: String) {
        val cur = tabLayouts[tabId] ?: return
        if (cur.floatingPanes.none { it.id == paneId }) return
        tabLayouts[tabId] = cur.copy(
            floatingPanes = cur.floatingPanes.map { f ->
                if (f.id == paneId) f.copy(isMaximized = !f.isMaximized) else f
            },
        )
        persistLayoutState()
        notifyToolkitTabs?.invoke()
    }

    /**
     * Sets the matching float's `isMinimized` flag. Minimized panes are
     * skipped from the rendered overlay layer; the host surfaces them in
     * its sidebar so the user can restore them by clicking the row.
     */
    private fun setFloatingPaneMinimized(tabId: String, paneId: String, minimized: Boolean) {
        val cur = tabLayouts[tabId] ?: return
        if (cur.floatingPanes.none { it.id == paneId }) return
        tabLayouts[tabId] = cur.copy(
            floatingPanes = cur.floatingPanes.map { f ->
                if (f.id == paneId) f.copy(isMinimized = minimized) else f
            },
        )
        persistLayoutState()
        // Re-render the active pane host (overlay re-paints) AND refresh
        // the sidebar in place so the minimised row appears/disappears
        // without tearing down the whole shell (no rebuildShell).
        notifyToolkitTabs?.invoke()
    }

    /**
     * Removes [paneId] from the float list. Closing the last pane in a
     * tab cascades to closing the tab itself (mirrors termtastic, where
     * a close-confirmed pane always disappears) — except when it is also
     * the last visible tab, in which case the tab is left with no panes
     * so the close gesture is visibly honoured. The user can spawn a
     * fresh pane via the topbar "+" button.
     */
    private fun closeFloatingPane(tabId: String, paneId: String) {
        val cur = tabLayouts[tabId] ?: return
        if (cur.floatingPanes.none { it.id == paneId }) return
        val remaining = cur.floatingPanes.filterNot { it.id == paneId }
        // Drop the closed pane's view-model so memory + zoom-path
        // collectors don't outlive their pane. Release the pane's
        // active document back to the registry first — when the last
        // pane on a file is closed, this flushes a final save and
        // shuts down the document's autosave loop.
        val removedVm = paneViewModels.remove(paneId)
        if (removedVm != null) scope.launch { removedVm.release() }
        paneEditors.remove(paneId)
        starredModals.remove(paneId)?.dispose()
        insertLinkModals.remove(paneId)?.close()
        navigateToModals.remove(paneId)?.close()
        insertImageModals.remove(paneId)?.close()
        if (remaining.isEmpty() && layoutState.tabs.size > 1) {
            // Last pane in a non-last tab: cascade to closing the tab.
            closeTab(tabId)
            return
        }
        tabLayouts[tabId] = cur.copy(floatingPanes = remaining)
        // Drop the closed pane's parent linkage; preserve other entries
        // so a chain of recorded parents survives sibling-only removals.
        parentByPane.remove(paneId)
        persistLayoutState()
        // Toolkit's mountAppShell owns runtime layout (preset enforcement,
        // re-tile on add/remove). TreeFacts only persists tab/pane identity
        // and pushes a fresh snapshot through [persistLayoutState].
    }

    /**
     * Walk a persisted [PaneNodeJson] tree and turn each leaf into a
     * [FloatingPaneSpec]. Used by [hydrateLayoutState] to migrate any
     * legacy split tree on disk into the floats-only runtime model.
     * The first leaf becomes the "primary" full-bleed maximised pane;
     * subsequent leaves cascade with ascending z-index.
     */
    private fun collectTreeLeafSpecs(
        tree: se.soderbjorn.lunula.store.PaneNodeJson,
    ): List<FloatingPaneSpec> {
        val collected = mutableListOf<Pair<String, String?>>()
        fun walk(n: se.soderbjorn.lunula.store.PaneNodeJson) {
            when (n) {
                is se.soderbjorn.lunula.store.PaneNodeJson.Leaf ->
                    collected += n.id to n.title
                is se.soderbjorn.lunula.store.PaneNodeJson.Split -> {
                    walk(n.first); walk(n.second)
                }
            }
        }
        walk(tree)
        return collected.mapIndexed { i, (id, title) ->
            if (i == 0) {
                FloatingPaneSpec(
                    id = id, title = title,
                    xPct = 0.0, yPct = 0.0, widthPct = 1.0, heightPct = 1.0,
                    zIndex = 1, isMaximized = true,
                )
            } else {
                val offset = (i - 1) * 0.05
                FloatingPaneSpec(
                    id = id, title = title,
                    xPct = (0.10 + offset).coerceAtMost(0.55),
                    yPct = (0.10 + offset).coerceAtMost(0.45),
                    widthPct = 0.45, heightPct = 0.55,
                    zIndex = i + 1,
                )
            }
        }
    }

    /**
     * Snapshot the in-memory tab + float layout into [layoutState]'s
     * persistable shape, write through the toolkit [Persister]
     * (Electron-IPC or localStorage), and notify the toolkit-shell tab
     * source so its rendered chrome catches up.
     */
    private fun persistLayoutState() {
        val snapTabs = layoutState.tabs.map { tab ->
            val layout = tabLayouts[tab.id]
            if (layout == null) tab
            else tab.copy(
                tree = null,
                expandedLeafId = null,
                floatingPanes = layout.floatingPanes.map { f ->
                    se.soderbjorn.lunula.store.FloatingPaneJson(
                        id = f.id,
                        title = f.title,
                        xPct = f.xPct,
                        yPct = f.yPct,
                        widthPct = f.widthPct,
                        heightPct = f.heightPct,
                        zIndex = f.zIndex,
                        isMaximized = f.isMaximized,
                        isMinimized = f.isMinimized,
                    )
                },
                layoutPreset = activePresetByTab[tab.id]?.key,
            )
        }
        layoutState = layoutState.copy(tabs = snapTabs)
        val json = layoutState.toJsonString()
        scope.launch { persister.write(PersistKeys.LAYOUT, json) }
        notifyToolkitTabs?.invoke()
    }

    companion object {
        /** Three-stripe palette glyph — opens the ThemeManager. */
        private const val ICON_PALETTE: String =
            "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
                "stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" " +
                "stroke-linejoin=\"round\">" +
                "<circle cx=\"12\" cy=\"12\" r=\"9\"/>" +
                "<circle cx=\"7.5\" cy=\"10.5\" r=\"1.2\" fill=\"currentColor\" stroke=\"none\"/>" +
                "<circle cx=\"12\" cy=\"7\" r=\"1.2\" fill=\"currentColor\" stroke=\"none\"/>" +
                "<circle cx=\"16.5\" cy=\"10.5\" r=\"1.2\" fill=\"currentColor\" stroke=\"none\"/>" +
                "<circle cx=\"15\" cy=\"15\" r=\"1.2\" fill=\"currentColor\" stroke=\"none\"/></svg>"

        /** Page-with-fold icon used for sidebar rows representing notes/tabs. */
        private const val ICON_NOTE: String =
            "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" " +
                "stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" " +
                "stroke-linejoin=\"round\">" +
                "<path d=\"M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z\"/>" +
                "<polyline points=\"14 3 14 9 20 9\"/></svg>"

        /**
         * CSS class added to a [PaneAction]'s extra classes when the
         * action should render as visually-inert. The toolkit's
         * [PaneAction] has no native `disabled` flag, so treefacts tags
         * the rendered button itself and the stylesheet (injected by
         * [ensureTreeFactsChromeStyles]) dims it and disables pointer
         * events. Used by [buildPaneNavActions] to keep back/forward
         * (and up/home) in fixed positions for muscle memory while
         * showing whether they're currently actionable.
         */
        private const val DISABLED_CLASS: String = "treefacts-pane-action-disabled"

        /** Left-arrow glyph for the zoom-history "back" button. */
        private const val ICON_BACK: String =
            "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" " +
                "stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" " +
                "stroke-linejoin=\"round\">" +
                "<line x1=\"19\" y1=\"12\" x2=\"5\" y2=\"12\"/>" +
                "<polyline points=\"12 19 5 12 12 5\"/></svg>"

        /** Right-arrow glyph for the zoom-history "forward" button. */
        private const val ICON_FORWARD: String =
            "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" " +
                "stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" " +
                "stroke-linejoin=\"round\">" +
                "<line x1=\"5\" y1=\"12\" x2=\"19\" y2=\"12\"/>" +
                "<polyline points=\"12 5 19 12 12 19\"/></svg>"

        /** Five-point outline-star glyph for the Starred toolbar button. */
        internal const val ICON_STAR: String =
            "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" " +
                "stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" " +
                "stroke-linejoin=\"round\">" +
                "<polygon points=\"12 2 15 9 22 9.5 17 14.5 18.5 21.5 12 18 5.5 21.5 7 14.5 2 9.5 9 9\"/>" +
                "</svg>"

        /** Filled star variant — shown when "Add to starred" is active. */
        internal const val ICON_STAR_FILLED: String =
            "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"currentColor\" " +
                "stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" " +
                "stroke-linejoin=\"round\">" +
                "<polygon points=\"12 2 15 9 22 9.5 17 14.5 18.5 21.5 12 18 5.5 21.5 7 14.5 2 9.5 9 9\"/>" +
                "</svg>"
    }
}
