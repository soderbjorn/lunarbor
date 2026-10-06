/*
 * AppShell.kt (jsMain)
 * --------------------
 * Top-level shell for the lunarbor web app, built on lunula.
 *
 * The chrome — top bar, tab strip, kebab menu, left sidebar's
 * tabs→panes tree, layout renderer, theme manager sidebar — comes
 * from the toolkit's `mountAppShell(AppShellSpec(...))` one-call
 * assembler (the toolkit bottom bar is disabled). Lunarbor
 * contributes:
 *
 *  - The per-pane note editor (rendered through [renderPaneContent]).
 *  - A typed `LayoutState` source ([LunarborTabSource]) for tab +
 *    pane identity (lunarbor has its own document-model-derived shape;
 *    the toolkit's local-mode tab list isn't expressive enough).
 *  - Top-bar actions before the toolkit's own: Starred, the command
 *    palette (Cmd-P) and the 3D mode cube (⌃⌘3), via
 *    `extraTopbarBeforeStandard`.
 *  - 3D mode ([SpaceMode]): this shell is its [SpaceHost] — it hands
 *    over the active tab's windows, their editors and breadcrumbs, and
 *    tells the mode whenever tabs, windows or focus change.
 *  - Lunarbor-specific keyboard shortcuts (Cmd-P / Cmd-/ / Cmd-O /
 *    Cmd-S) and the Electron-menu `lunarbor:show-hotkeys` bridge.
 *  - Per-pane navigation: Back / Forward before a breadcrumb of the
 *    pane's whole location (vault root → folders → file → zoom path).
 *
 * The post-revamp toolkit theme system is global (no per-pane
 * sections), so lunarbor no longer forwards a pane→section map; the
 * toolkit's `mountAppShell` owns the whole theme/settings surface.
 *
 * Persistence — theme, ui settings, layout — routes through the
 * lunula `Persister` injected by [JsAppGraph]: Electron
 * IPC when `globalThis.darknessApi` is present, namespaced
 * `localStorage` otherwise.
 *
 * commonMain rules: this file is jsMain only (touches the DOM).
 */

package se.soderbjorn.lunarbor.main

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.await
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
import se.soderbjorn.lunula.web.showConfirmDialog
import se.soderbjorn.lunula.web.layout.FloatingPaneSpec
import se.soderbjorn.lunula.web.layout.GridSpec
import se.soderbjorn.lunula.web.layout.LayoutPreset
import se.soderbjorn.lunula.web.layout.PaneLayout
import se.soderbjorn.lunula.web.layout.PaneAction
import se.soderbjorn.lunula.web.layout.PaneTitleSegment
import se.soderbjorn.lunula.web.layout.withNoneMaximized
import se.soderbjorn.lunula.web.shell.AppShellHandle
import se.soderbjorn.lunula.web.settings.FontSurfaceId
import se.soderbjorn.lunula.web.shell.AppShellSpec
import se.soderbjorn.lunula.web.shell.PaneAddMenuItem
import se.soderbjorn.lunula.web.shell.PaneOverflowSpec
import se.soderbjorn.lunula.web.shell.TopbarAction
import se.soderbjorn.lunarbor.demo.isDemoMode
import se.soderbjorn.lunula.web.shell.mountAppShell
import se.soderbjorn.lunarbor.data.FolderName
import se.soderbjorn.lunarbor.data.InlineMarkdownTokenizer
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.main.space.SpaceHost
import se.soderbjorn.lunarbor.main.space.isSpaceModeEnabled
import se.soderbjorn.lunarbor.main.space.SpaceMode
import se.soderbjorn.lunarbor.main.space.SpacePane
import se.soderbjorn.lunarbor.main.space.SpaceTab

/**
 * Top-level shell that wires the lunula windowing system
 * around the lunarbor editor. One instance per app startup;
 * instantiated in [se.soderbjorn.lunarbor.Main].
 *
 * @param scope coroutine scope shared with the embedded [MainScreen]
 *   for its paint loop and used for [Persister] reads/writes.
 * @param documentRegistry The shared registry that hands out
 *   [se.soderbjorn.lunarbor.main.Document] instances. Each pane
 *   acquires its current file from here; two panes pointed at the
 *   same file share one Document so concurrent edits stay live.
 * @param fileSystem The app's [FileSystem] (from `JsAppGraph`), handed
 *   to each [StarredModal] so its private repository reads the same
 *   vault as the registry — the in-memory one in the browser demo.
 * @param persister Toolkit-canonical KV bridge for theme / layout /
 *   ui-settings (see [PersistKeys]). Backed by Electron IPC inside
 *   the desktop wrapper, namespaced `localStorage` in a plain browser.
 *   The toolkit's `mountAppShell` reads `UI_SETTINGS` /
 *   `LAYOUT_STATE` / `THEME_SNAPSHOT` itself; lunarbor uses this
 *   handle for `LAYOUT` (its typed [LayoutState] shape, owned by
 *   the app when a `TabSource` is supplied).
 * @param newsUpdates The app's News & updates checker ([startNewsUpdates]);
 *   its bell joins the topbar. `null` in the browser demo: no bell.
 * @param lunicleService The app's Lunicle connections and clients
 *   (`JsAppGraph.lunicleService`, LBR-26); App settings → Lunicle edits its
 *   connections. `null` in the browser demo: no Lunicle at all.
 */
class AppShell(
    private val scope: CoroutineScope,
    private val documentRegistry: se.soderbjorn.lunarbor.main.DocumentRegistry,
    private val fileSystem: se.soderbjorn.lunarbor.platform.FileSystem,
    private val persister: Persister,
    private val newsUpdates: se.soderbjorn.lunarbor.newsupdates.NewsUpdatesBackingViewModel? = null,
    private val lunicleService: se.soderbjorn.lunarbor.lunicle.LunicleService? = null,
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
     * Per-pane Back / Forward button pairs shown in the header's leading
     * slot, keyed by leaf pane id. Built once by [paneNavCluster] and
     * updated in place on every chrome render; dropped when the pane
     * closes.
     */
    private val paneNavClusters: MutableMap<String, HTMLElement> = mutableMapOf()

    /**
     * Per pane, the [chromeKeyOf] its header was last drawn with (by
     * [paneBreadcrumbSegments]). The navigation collector in
     * [ensurePaneViewModel] redraws the header when the pane's key moves
     * away from it — including a header drawn before the pane's state
     * existed, as on a restart, which would otherwise keep its bare title.
     */
    private val chromeDrawnKeys: MutableMap<String, List<Any?>> = mutableMapOf()

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
     * removed in [closeFloatingPane].
     */
    private val starredModals: MutableMap<String, StarredModal> = mutableMapOf()

    /**
     * Per-pane Insert Link / "Insert Mirror…" modals, keyed by
     * `"<paneId>|<placeholder>"`. Same lifecycle
     * pattern as [starredModals]: lazily created on first open from the
     * command palette, reused thereafter, cleared in [closeFloatingPane].
     */
    private val insertLinkModals: MutableMap<String, LinkSearchModal> = mutableMapOf()

    /**
     * Per-pane Navigate-to modals (Cmd-O / "Navigate to" command).
     * Same lifecycle pattern as [insertLinkModals]: lazy first-open
     * create, reuse thereafter, cleared in [closeFloatingPane].
     */
    private val navigateToModals: MutableMap<String, LinkSearchModal> = mutableMapOf()

    /**
     * Per-pane Insert Image modals ("Insert Image" command). Same lifecycle
     * pattern as [insertLinkModals]: lazy first-open create, reuse
     * thereafter, cleared in [closeFloatingPane].
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
    private val topbarStarredModalLazy: Lazy<StarredModal> = lazy {
        StarredModal(
            parentScope = scope,
            activePaneVmProvider = { resolveOrCreateFocusedPaneVm() },
            vaultRoot = documentRegistry.rootDirectory,
            fileSystem = fileSystem,
        )
    }

    /** See [topbarStarredModalLazy]; created on first use. */
    private val topbarStarredModal: StarredModal by topbarStarredModalLazy

    /**
     * Per-pane [MainViewModel] handles, keyed by leaf pane id. Maintained
     * alongside [paneEditors] so the toolkit-rendered pane chrome
     * callbacks ([paneNavCluster], [paneBreadcrumbSegments],
     * [paneSidebarLabel]) can wire the Back / Forward buttons and the
     * clickable breadcrumb title to the pane's own state without
     * crossing through the screen.
     *
     * Populated lazily on first render of each pane in [renderPaneContent];
     * an entry is missing until the pane has rendered at least once, so
     * action handlers that look up here must tolerate `null`.
     */
    private val paneViewModels: MutableMap<String, MainViewModel> = mutableMapOf()

    /**
     * Lunarbor's typed layout state — tab list, per-tab floating panes
     * (id + title + on-disk file ref). Hydrated from
     * `persister.read(LAYOUT)` at boot; mutated by tab/pane intents
     * and re-persisted via [persistLayoutState]. The toolkit's
     * `mountAppShell` skips its own LAYOUT key when a `TabSource` is
     * supplied, so this is the authoritative tab/pane source.
     */
    private var layoutState: LayoutState = LayoutState.defaults()

    /**
     * Pushes a fresh [se.soderbjorn.lunula.web.shell.TabListSnapshot]
     * to the toolkit shell when lunarbor's [LayoutState] mutates.
     * Captured by [render] when it constructs the [LunarborTabSource].
     */
    private var notifyToolkitTabs: (() -> Unit)? = null

    /**
     * 3D mode ("Pages", [SpaceMode]): on / off and single / split, its
     * views and render loop. Told about every layout change through
     * [notifyToolkitTabs]; see [spaceHost].
     */
    private val spaceMode: SpaceMode by lazy {
        SpaceMode(spaceHost, scope, persister, documentRegistry)
    }

    /** Pending `requestAnimationFrame` of a coalesced [SpaceMode.onLayoutChanged]. */
    private var spaceLayoutFrame: Int? = null

    /** Per-tab pane layout (floats only). Keyed by tab id. */
    private val tabLayouts: MutableMap<String, PaneLayout> = mutableMapOf()

    /**
     * The most recently focused pane id per tab. The toolkit's
     * `mountAppShell` owns *runtime* focus through its layout
     * renderer; lunarbor only tracks an *advisory* last-focus per
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
     * surface of that pane's chrome — the `.lunarbor-title` headline
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

    // Cmd/Ctrl+/ is bound through the toolkit's [HotkeyRegistry] in
    // [installHotkeysShortcut]; the Electron `lunarbor:show-hotkeys` menu
    // bridge is [installHotkeysMenuBridge]. Both open the keyboard-shortcuts
    // sidebar ([openHotkeysSidebar]).

    /** Document-level Cmd/Ctrl+O listener installed in [render] — opens
     *  the Navigate-to modal for the focused pane. Tracked so the
     *  listener can be re-installed idempotently. */
    private var navigateToShortcutHandler: ((Event) -> Unit)? = null
    private var searchShortcutHandler: ((Event) -> Unit)? = null

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
     * Where every pane is (file + zoom), persisted so panes reopen where
     * they were after a restart or a vault change. Loaded at boot before
     * the first pane renders; see [ensurePaneViewModel] (restore + record)
     * and [closeFloatingPane] (forget).
     */
    private val paneLocations: PaneLocationStore = PaneLocationStore(persister, scope) {
        tabLayouts.values.flatMap { layout -> layout.floatingPanes.map { it.id } }.toSet()
    }

    /** Pending debounced write of the fold memory ([loadFoldMemory]). */
    private var foldMemorySave: Job? = null

    /**
     * Seeds [DocumentRegistry.foldMemory] from the persister (key
     * [FOLD_MEMORY_KEY]: `{ "vault": "<root dir>", "folders": [...] }`,
     * ignored when stored for another vault) and writes it back, debounced,
     * on every change — so panes reopen with the folds they were left in.
     * Called once at boot, before the first pane renders.
     */
    private suspend fun loadFoldMemory() {
        val memory = documentRegistry.foldMemory
        val vault = documentRegistry.rootDirectory
        val raw = persister.read(FOLD_MEMORY_KEY)
        val parsed: dynamic = try {
            raw?.let { JSON.parse<dynamic>(it) }
        } catch (_: Throwable) {
            null
        }
        val folders: dynamic = parsed?.folders
        if (parsed?.vault == vault && js("Array.isArray(folders)") as Boolean) {
            memory.load((folders as Array<Any?>).filterIsInstance<String>())
        }
        memory.onChanged = {
            foldMemorySave?.cancel()
            foldMemorySave = scope.launch {
                delay(FOLD_MEMORY_SAVE_DEBOUNCE_MS)
                val out: dynamic = js("({})")
                out.vault = vault
                out.folders = memory.snapshot().sorted().toTypedArray()
                persister.write(FOLD_MEMORY_KEY, JSON.stringify(out))
            }
        }
    }

    /**
     * Seeds [DocumentRegistry.dailyTemplate] (LBR-21) from the persister
     * (key [DAILY_TEMPLATE_KEY]: `{ "<vault root dir>": "<template folder>" }`,
     * per vault like [PRIVACY_MODE_KEY]) and writes it back whenever it
     * changes — the palette's "Use as daily template" / "Stop using as
     * daily template", or a move, rename or trashing of the template node.
     * A setting with no settings UI. Called once at boot, before the first
     * pane renders.
     */
    private suspend fun loadDailyTemplate() {
        val template = documentRegistry.dailyTemplate
        val vault = documentRegistry.rootDirectory
        suspend fun readAll(): dynamic = try {
            persister.read(DAILY_TEMPLATE_KEY)?.let { JSON.parse<dynamic>(it) }
        } catch (_: Throwable) {
            null
        }
        val stored: dynamic = readAll()
        template.load(if (stored == null) null else stored[vault] as? String)
        template.onChanged = {
            scope.launch {
                val all: dynamic = readAll() ?: js("({})")
                val folder = template.folder
                if (folder == null) js("delete all[vault]") else all[vault] = folder
                persister.write(DAILY_TEMPLATE_KEY, JSON.stringify(all))
            }
        }
    }

    /**
     * Reads the vault's privacy modes and puts the app back in the mode it
     * was left in for this vault (persister key [PRIVACY_MODE_KEY]; a mode
     * that no longer exists falls back to "No privacy"), then writes the
     * current mode back whenever it changes. Called once at boot, before
     * the first pane renders, so nothing hidden is ever shown.
     */
    private suspend fun loadPrivacyMode() {
        val vault = documentRegistry.rootDirectory
        documentRegistry.loadPrivacyModes()
        val stored: dynamic = try {
            persister.read(PRIVACY_MODE_KEY)?.let { JSON.parse<dynamic>(it) }
        } catch (_: Throwable) {
            null
        }
        val id = if (stored == null) null else stored[vault] as? String
        if (id != null) documentRegistry.setPrivacyMode(id)
        scope.launch {
            documentRegistry.privacyFlow.map { it.currentId }.distinctUntilChanged().drop(1).collect { current ->
                val all: dynamic = try {
                    persister.read(PRIVACY_MODE_KEY)?.let { JSON.parse<dynamic>(it) }
                } catch (_: Throwable) {
                    null
                } ?: js("({})")
                if (current == null) js("delete all[vault]") else all[vault] = current
                persister.write(PRIVACY_MODE_KEY, JSON.stringify(all))
            }
        }
    }

    /**
     * Panes still moving to their restored location ([ensurePaneViewModel]).
     * Their intermediate root location is not recorded, so a quit mid-restore
     * never overwrites the stored place with the root.
     */
    private val restoringPanes: MutableSet<String> = mutableSetOf()

    /**
     * Panes whose persisted search ([PaneLocationStore.search]) is not
     * reapplied yet. Their search state is not recorded meanwhile, so the
     * pane's initial closed search never erases the stored one.
     */
    private val restoringSearches: MutableSet<String> = mutableSetOf()

    /**
     * Boots the shell into [root]. Safe to call once.
     *
     * Lunarbor contributes the persistence-aware [LayoutState] (tabs +
     * floating panes), per-pane editor body, palette button, and
     * lunarbor-only keyboard shortcuts, plus the sidebar brand logo
     * ([buildAppLogo]) whose dot pulses while unsaved edits pend.
     * Everything else — top bar, tab strip, kebab menu, layout
     * dropdown, new-pane button, appearance toggle, theme manager
     * sidebar, layout renderer, pane chrome — comes from
     * [se.soderbjorn.lunula.web.shell.mountAppShell] (the toolkit
     * bottom bar is disabled; the brand lives in the sidebar logo).
     */
    fun render(root: HTMLElement) {
        injectLunulaStyles()
        ensureLunarborChromeStyles()
        rootEl = root

        installPaletteShortcut()
        installChromeSelectionTracker()
        installHotkeysShortcut()
        installSpaceShortcuts()
        installTodayShortcut()
        installNavigateToShortcut()
        installSearchShortcuts()
        installStarredShortcut()
        installEditorKeyDelegate()
        installHotkeysMenuBridge()
        installFolderRefreshOnFocus()

        val tabSource = LunarborTabSource(
            onTabSelected = { id ->
                layoutState = layoutState.copy(activeTabId = id)
                persistLayoutState()
            },
            onTabAdded = { addTab() },
            onTabClosed = { id -> closeTab(id) },
            onTabRenamed = { id, label -> renameTab(id, label) },
            onTabReordered = { sourceId, targetId, before -> reorderTab(sourceId, targetId, before) },
            onTabHiddenSet = { id, hidden -> setTabHidden(id, hidden) },
            onTabHiddenFromSidebarSet = { id, hidden -> setTabHiddenFromSidebar(id, hidden) },
            onPaneMoved = { tabId, paneId, targetTabId -> movePaneToTab(tabId, paneId, targetTabId) },
            onPaneSelected = { tabId, paneId ->
                lastFocusedPaneIdByTab[tabId] = paneId
                if (layoutState.activeTabId != tabId) {
                    layoutState = layoutState.copy(activeTabId = tabId)
                    persistLayoutState()
                } else {
                    notifyToolkitTabs?.invoke()
                }
                paneEditors[paneId]?.focusEditor()
            },
            onPaneFocused = { tabId, paneId ->
                if (lastFocusedPaneIdByTab[tabId] != paneId) {
                    lastFocusedPaneIdByTab[tabId] = paneId
                    notifyToolkitTabs?.invoke()
                }
            },
            onPaneClosed = { tabId, paneId -> closeFloatingPane(tabId, paneId) },
            onPaneAdded = { tabId ->
                // `addFloatingPane` already calls `persistLayoutState`,
                // which calls `notifyToolkitTabs`. Don't re-notify here
                // — a second rerender right behind the first wipes the
                // restore-from-maximize CSS transition the first
                // rerender just kicked off.
                openWindowAtCurrentLocation(tabId)
            },
            // "New window" leads the "+" menu and is its default (the
            // plain click above does the same).
            paneAddMenuItems = { tabId ->
                listOf(
                    PaneAddMenuItem(
                        id = "new-window",
                        label = "New window",
                        iconHtml = ICON_NEW_WINDOW,
                        isDefault = true,
                    ) { openWindowAtCurrentLocation(tabId) },
                )
            },
        )
        notifyToolkitTabs = {
            tabSource.notify(layoutState, activePaneByTab = lastFocusedPaneIdByTab)
            scheduleSpaceLayout()
        }

        shellHandle = mountAppShell(
            AppShellSpec(
                rootContainer = root,
                title = "Lunarbor",
                persister = persister,
                // What the editor paints when the user has picked nothing, so
                // Appearance → Fonts names the sizes actually on screen: 17px
                // text (`EditorStyle.fontSize`) and 16px code (about the
                // 0.95em code used to be). Code's font stays the system
                // monospace (`system` — SF Mono on a Mac), so none is named.
                defaultProseFontSizePx = { 17 },
                defaultMonoFontSizePx = { 16 },
                // Headings are sized from the text size (h1 1.6em …), never
                // from `--dt-font-display-size`, so their line offers a font
                // but no size.
                fontSizeHidden = setOf(FontSurfaceId.Headings),
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
                paneActions = { _, paneId -> buildPaneActions(paneId) },
                // The pane `⋮` menu: just the toolkit's Move to tab ▸
                // (panes have no rename — their title is the breadcrumb).
                paneOverflowMenu = { _, _ -> PaneOverflowSpec(includeRename = false) },
                // Back / Forward sit in the header's leading slot, right
                // before the breadcrumb, and stay visible: history and
                // path read as one navigation group (the toolkit's
                // trailing strip is hover-revealed and shared with the
                // window controls).
                paneHeaderBadge = { _, paneId -> paneNavCluster(paneId) },
                // The breadcrumb is the pane's whole location — vault
                // root, node folders, open file, zoom ancestors, zoom
                // target — and every segment but the last navigates, so
                // it also covers "up" and "home".
                paneTitleSegments = { _, paneId -> paneBreadcrumbSegments(paneId) },
                extraTopbarBeforeStandard = listOf(
                    TopbarAction(
                        id = "lunarbor-topbar-starred",
                        iconHtml = ICON_STAR,
                        label = "Starred",
                        onActivate = { topbarStarredModal.open() },
                    ),
                    // The command palette, also Cmd-P.
                    TopbarAction(
                        id = "lunarbor-topbar-palette",
                        iconHtml = ICON_COMMAND,
                        label = "Command palette (⌘P)",
                        onActivate = { commandPalette.open() },
                    ),
                    // 3D mode (space/SpaceMode.kt), also ⌃⌘3. Hidden by CSS
                    // until App settings → "Enable 3D mode" turns it on.
                    spaceTopbarAction(),
                ) + listOfNotNull(
                    // News & updates (desktop only; NewsUpdates.kt).
                    newsUpdates?.let { newsTopbarAction(it) },
                ),
                // Brand logo (dot + "lunarbor" wordmark, termtastic-style)
                // pinned to the top of the left sidebar. The factory returns
                // a cached element so toolkit rerenders re-parent the same
                // node and the dot's save-state pulse survives rebuilds.
                sidebarHeader = { buildAppLogo() },
                // No bottom bar: its only content in lunarbor was the
                // toolkit's default app-name label (the tiny "Lunarbor"
                // in the lower right). The brand moved to the sidebar
                // logo above, so the strip earns nothing.
                showBottomBar = false,
                // The Settings sidebar's "Custom title bar" toggle only
                // makes sense in Electron — gate it on the preload-injected
                // `darknessApi`. In a plain browser this resolves to
                // `undefined` and the toggle stays hidden.
                isElectron = (js("typeof globalThis !== 'undefined' && globalThis.darknessApi != null") as Boolean),
                // The topbar gear: jumps to Themes / Appearance / Keyboard
                // Shortcuts, plus the Vault, Backup, Agent access and Lunicle sections
                // (AppSettingsContent.kt).
                appSettingsContent = {
                    buildAppSettingsContent(
                        AppSettingsHandlers(
                            scope = scope,
                            openHotkeys = { openHotkeysSidebar() },
                            hasUnsavedEdits = { documentRegistry.unsavedFilesFlow.value.isNotEmpty() },
                            switchVault = { dir -> switchVault(dir) },
                            flushEdits = { documentRegistry.flushAll() },
                            privacyModes = { documentRegistry.privacyFlow.value.modes },
                            lunicle = lunicleService,
                            openPrivacy = { openPrivacyDialog(scope, documentRegistry) },
                            spaceModeEnabled = { isSpaceModeEnabled },
                            setSpaceModeEnabled = { spaceMode.setEnabled(it) },
                        ),
                    )
                },
                // Keyboard-shortcuts reference, opened by Cmd-/, the macOS
                // menu and the App settings jump — no topbar button.
                hotkeysContent = { buildHotkeysSidebarContent() },
            ),
            scope = scope,
        )

        // Hydrate lunarbor's typed LayoutState from the toolkit's
        // Persister (Electron-IPC or localStorage) and push the first
        // snapshot through the TabSource. mountAppShell is launched
        // synchronously above; if the toolkit subscribes before this
        // load finishes, LunarborTabSource pushes its empty
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
            // Before the first snapshot: panes render right after it, and
            // [ensurePaneViewModel] opens each at its stored location.
            paneLocations.load { documentRegistry.fileExists(it) }
            loadFoldMemory()
            loadDailyTemplate()
            // Before any pane shows: the privacy mode decides what can.
            loadPrivacyMode()
            tabSource.notify(layoutState)
            // Back into 3D mode if it was on — once the panes have rendered.
            window.requestAnimationFrame { spaceMode.restore() }
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
                .collect { unsaved ->
                    setAppLogoUnsaved(unsaved)
                    refreshOpenVaultSection(unsaved)
                }
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
     * Bind `Cmd/Ctrl+/` to [openHotkeysSidebar] through the toolkit's
     * shared [HotkeyRegistry]. Idempotent — the registry's
     * replace-on-register semantics mean re-installing on a subsequent
     * boot pass overwrites the previous binding.
     */
    private fun installHotkeysShortcut() {
        val isMac = se.soderbjorn.lunula.web.hotkey.isMacPlatform()
        val chord = se.soderbjorn.lunula.web.hotkey.Hotkey(key = "/", meta = isMac, ctrl = !isMac)
        se.soderbjorn.lunula.web.hotkey.HotkeyRegistry.register(chord) { openHotkeysSidebar() }
    }

    /**
     * Registers 3D mode's three configurable actions with the toolkit's
     * [se.soderbjorn.lunula.web.hotkey.HotkeyBindings] (so the Keyboard
     * Shortcuts sidebar lists them and they can be rebound): toggle 3D
     * mode (⌃⌘3; Ctrl-Alt-3 off the Mac) and switch between the focused
     * window alone and all of the tab's windows (⌃⌘1; Ctrl-Alt-1), and
     * step to the next shape — Pages, Crown, Cone, Galaxy (⌃⌘2; Ctrl-Alt-2).
     * Both do nothing while the feature is off ([SpaceMode.setEnabled]).
     */
    private fun installSpaceShortcuts() {
        val isMac = se.soderbjorn.lunula.web.hotkey.isMacPlatform()
        fun chord(key: String) = se.soderbjorn.lunula.web.hotkey.Hotkey(key = key, ctrl = true, meta = isMac, alt = !isMac)
        se.soderbjorn.lunula.web.hotkey.HotkeyBindings.registerAction(
            se.soderbjorn.lunula.web.hotkey.HotkeyActionSpec(SPACE_TOGGLE_ACTION, "Toggle 3D mode", listOf(chord("3"))),
        ) { spaceMode.toggle() }
        se.soderbjorn.lunula.web.hotkey.HotkeyBindings.registerAction(
            se.soderbjorn.lunula.web.hotkey.HotkeyActionSpec(SPACE_SPLIT_ACTION, "3D mode: focused window or all windows", listOf(chord("1"))),
        ) { spaceMode.toggleSplit() }
        se.soderbjorn.lunula.web.hotkey.HotkeyBindings.registerAction(
            se.soderbjorn.lunula.web.hotkey.HotkeyActionSpec(SPACE_SHAPE_ACTION, "3D mode: next shape", listOf(chord("2"))),
        ) { spaceMode.nextShape() }
    }

    /**
     * Registers the Today command's configurable hotkey ([TODAY_ACTION]):
     * ⌘D (Ctrl-D off the Mac) — "D" for day. Nothing else in the app or
     * the toolkit binds it (the Starred modal's star toggle is ⌘S); in a
     * plain browser tab the browser's bookmark shortcut may win, which
     * only matters for the website demo. Listed (and rebindable) in the
     * Keyboard Shortcuts sidebar.
     */
    private fun installTodayShortcut() {
        val isMac = se.soderbjorn.lunula.web.hotkey.isMacPlatform()
        val chord = se.soderbjorn.lunula.web.hotkey.Hotkey(key = "d", meta = isMac, ctrl = !isMac)
        se.soderbjorn.lunula.web.hotkey.HotkeyBindings.registerAction(
            se.soderbjorn.lunula.web.hotkey.HotkeyActionSpec(TODAY_ACTION, "Today", listOf(chord)),
        ) { goToToday() }
    }

    /**
     * The Today command (palette "Today", [TODAY_ACTION]): takes the
     * focused pane to today's journal item — the user's local date — via
     * [MainViewModel.navigateToToday], then focuses its editor. When the
     * privacy mode hides the journal, nothing moves and a short notice
     * says so, without naming the mode or the tags.
     */
    /**
     * "Insert Lunicle board…" (LBR-27): asks for a connection — skipped
     * when exactly one has a token — then for one of its projects (from
     * `projects()`), and inserts `Lunicle board {{lunicle: <conn>/<KEY>}}`
     * into the focused pane ([MainViewModel.insertLunicleBoard]). The
     * pickers are one-off command palettes. Explains itself in a dialog
     * when there is no connection or the projects cannot be listed.
     */
    private fun insertLunicleBoard() {
        val service = lunicleService ?: return
        val paneId = focusedPaneId() ?: return
        scope.launch {
            val connections = service.refreshConnections().connections.filter { it.hasToken }
            when (connections.size) {
                0 -> showConfirmDialog(
                    title = "No Lunicle connection",
                    message = "Add a connection with a token in App settings → Lunicle first.",
                    cancelLabel = "Close",
                )
                1 -> pickLunicleProject(paneId, connections.single())
                else -> CommandPalette(placeholder = "Lunicle connection…") {
                    connections.map { c ->
                        CommandPalette.Command(id = "lunicle-${c.id}", title = "${c.name} — ${c.baseUrl}", run = { pickLunicleProject(paneId, c) })
                    }
                }.open()
            }
        }
    }

    /** The project step of [insertLunicleBoard], for [connection]. */
    private fun pickLunicleProject(paneId: String, connection: se.soderbjorn.lunarbor.lunicle.LunicleConnection) {
        val service = lunicleService ?: return
        scope.launch {
            when (val r = service.client(connection.id).projects()) {
                is se.soderbjorn.lunarbor.lunicle.LunicleResult.Failure -> showConfirmDialog(
                    title = "Couldn't list the projects",
                    message = se.soderbjorn.lunarbor.lunicle.LunicleBoardLayout.errorText(r.error),
                    cancelLabel = "Close",
                )
                is se.soderbjorn.lunarbor.lunicle.LunicleResult.Ok -> if (r.value.isEmpty()) {
                    showConfirmDialog(
                        title = "No projects",
                        message = "The token of “${connection.name}” sees no projects.",
                        cancelLabel = "Close",
                    )
                } else {
                    CommandPalette(placeholder = "Project in ${connection.name}…") {
                        r.value.map { p ->
                            CommandPalette.Command(id = "project-${p.id}", title = "${p.keyPrefix} — ${p.name}", run = {
                                paneViewModels[paneId]?.insertLunicleBoard(connection.name, p.keyPrefix)
                                paneEditors[paneId]?.focusEditor()
                            })
                        }
                    }.open()
                }
            }
        }
    }

    private fun goToToday() {
        val paneId = focusedPaneId() ?: return
        val vm = paneViewModels[paneId] ?: return
        scope.launch {
            when (vm.navigateToToday(localToday())) {
                PaneBackingViewModel.TodayOutcome.HIDDEN -> showConfirmDialog(
                    title = "Today can't be opened",
                    message = "The journal isn't shown in the current view.",
                    cancelLabel = "Close",
                )
                PaneBackingViewModel.TodayOutcome.OPENED -> paneEditors[paneId]?.focusEditor()
                else -> Unit
            }
        }
    }

    /**
     * "Previous day" / "Next day" (LBR-20): takes the focused pane to the
     * nearest existing journal day before or after the one it is on —
     * "Next day" reaching today opens today as the Today command does —
     * via [MainViewModel.navigateToAdjacentDay], then focuses its editor.
     * Nowhere to go changes nothing, silently.
     *
     * @param forward `true` for "Next day".
     */
    private fun goToAdjacentDay(forward: Boolean) {
        val paneId = focusedPaneId() ?: return
        val vm = paneViewModels[paneId] ?: return
        scope.launch {
            if (vm.navigateToAdjacentDay(forward, localToday()) == PaneBackingViewModel.TodayOutcome.OPENED) {
                paneEditors[paneId]?.focusEditor()
            }
        }
    }

    /**
     * Tells 3D mode the layout changed, on the next frame — after the
     * toolkit has rebuilt its panes — and once per frame however many
     * notifications arrive.
     */
    private fun scheduleSpaceLayout() {
        if (!spaceMode.isActive || spaceLayoutFrame != null) return
        spaceLayoutFrame = window.requestAnimationFrame {
            spaceLayoutFrame = null
            spaceMode.onLayoutChanged()
        }
    }

    /**
     * What 3D mode reads from the shell ([SpaceHost]): the active tab's
     * windows at their floating-pane geometry, focus, each window's view
     * model, editor and breadcrumb.
     */
    private val spaceHost: SpaceHost = object : SpaceHost {
        override fun spacePanes(): List<SpacePane> {
            val tabId = layoutState.activeTabId ?: return emptyList()
            val floats = tabLayouts[tabId]?.floatingPanes.orEmpty()
            // Where the toolkit actually drew each window (its layout preset
            // can place panes away from their stored specs), as fractions of
            // the pane area; the stored spec until the pane is drawn.
            val area = (rootEl ?: document.body)?.querySelector(".dt-pane-area") as? HTMLElement
            val ar = area?.getBoundingClientRect()
            return floats.mapIndexedNotNull { i, f ->
                if (f.isMinimized) return@mapIndexedNotNull null
                val label = "Window ${i + 1}"
                val el = area?.querySelector(".dt-pane-floating[data-pane-id='${f.id}']") as? HTMLElement
                val r = el?.getBoundingClientRect()
                when {
                    ar != null && r != null && ar.width > 0 && ar.height > 0 && r.width > 0 -> SpacePane(
                        f.id, label,
                        (r.left - ar.left) / ar.width, (r.top - ar.top) / ar.height,
                        r.width / ar.width, r.height / ar.height,
                        if (f.isMaximized) Int.MAX_VALUE / 2 else f.zIndex,
                    )
                    f.isMaximized -> SpacePane(f.id, label, 0.0, 0.0, 1.0, 1.0, Int.MAX_VALUE / 2)
                    else -> SpacePane(f.id, label, f.xPct, f.yPct, f.widthPct, f.heightPct, f.zIndex)
                }
            }
        }

        override fun focusedPaneId(): String? = this@AppShell.focusedPaneId()

        override fun focusPane(paneId: String) {
            val tabId = layoutState.activeTabId ?: return
            // Raised as a click raises it in 2D, so overlapping views stack alike.
            bringFloatingPaneToFront(tabId, paneId)
            if (lastFocusedPaneIdByTab[tabId] == paneId) return
            lastFocusedPaneIdByTab[tabId] = paneId
            notifyToolkitTabs?.invoke()
        }

        override fun viewModelOf(paneId: String): MainViewModel? {
            ensurePaneViewModel(paneId)
            return paneViewModels[paneId]
        }

        override fun screenOf(paneId: String): MainScreen? {
            ensurePaneViewModel(paneId)
            return paneScreen(paneId)
        }

        override fun breadcrumbOf(paneId: String): List<PaneTitleSegment> = paneBreadcrumbSegments(paneId)

        override fun spaceTabs(): List<SpaceTab> =
            layoutState.tabs.filter { !it.isHidden }.map { t ->
                SpaceTab(t.id, t.title, tabLayouts[t.id]?.floatingPanes?.size ?: 0, t.id == layoutState.activeTabId)
            }

        override fun selectTab(tabId: String) {
            if (layoutState.tabs.none { it.id == tabId }) return
            layoutState = layoutState.copy(activeTabId = tabId)
            persistLayoutState()
        }

        override fun openPalette() = commandPalette.open()

    }

    /**
     * Opens the keyboard-shortcuts sidebar ([buildHotkeysSidebarContent]);
     * the toolkit closes any other right-side panel first. Called by Cmd-/,
     * the macOS `Lunarbor → Hotkeys…` menu item and App settings.
     */
    private fun openHotkeysSidebar() {
        shellHandle?.openHotkeysSidebar()
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
            val paneId = focusedPaneId() ?: return@lambda
            val mainScreen = paneEditors[paneId] ?: return@lambda
            // Enter / Escape act on a highlighted search-node result, which
            // a search node's read-only page has with the focus on <body>;
            // Escape leaves a board node's rows (LBR-28) the same way, and
            // Enter on a column name or a title adds a draft (LBR-29); a
            // typed key on a title that cannot be edited says why.
            val onHit = (mainScreen.isOnSearchNodeHit && (ke.key == "Enter" || ke.key == "Escape")) ||
                (mainScreen.isOnLunicleBoardRow && (ke.key == "Escape" || ke.key == "Enter" || ke.key.length == 1))
            if (!onHit && !shouldDelegateToEditor(ke)) return@lambda
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
    /**
     * Document-level search keys for the focused pane: Cmd/Ctrl-F opens
     * its "Search this tree" field — or, already open, focuses it with its
     * text selected — and Escape closes an open search. Capture phase, so
     * they work wherever focus is (the editor, the search field, the
     * result list, `<body>`). Idempotent.
     */
    private fun installSearchShortcuts() {
        if (searchShortcutHandler != null) return
        val handler: (Event) -> Unit = lambda@{ e ->
            val ke = e as? org.w3c.dom.events.KeyboardEvent ?: return@lambda
            val screen = focusedPaneId()?.let { paneEditors[it] } ?: return@lambda
            val isCmdF = (ke.metaKey || ke.ctrlKey) && !ke.altKey && !ke.shiftKey &&
                ke.key.equals("f", ignoreCase = true)
            if (isCmdF) {
                ke.preventDefault()
                ke.stopPropagation()
                screen.openSearch()
                return@lambda
            }
            // Escape closes an open search wherever focus is — the result
            // list holds no focus of its own — except in another text
            // field (a modal's input), which owns its Escape. The search
            // field and the editor handle their own Escape first.
            if (ke.key == "Escape" && !ke.metaKey && !ke.ctrlKey && !ke.altKey && !ke.shiftKey &&
                screen.isSearchOpen
            ) {
                val target = ke.target as? org.w3c.dom.Node
                if (target != null && isInsideEditable(target) && !screen.editorContains(target)) return@lambda
                ke.preventDefault()
                ke.stopPropagation()
                screen.closeSearch()
            }
        }
        searchShortcutHandler = handler
        document.addEventListener("keydown", handler, /* capture = */ true)
    }

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
     * (bookmarks) modal for the focused pane — or, when that modal is
     * already open, stars / un-stars the pane's current place
     * ([StarredModal.toggleStarredFromShortcut]). Capture phase so the
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
            // A second Cmd+S inside an open Starred modal (the pane's or
            // the top bar's) stars / un-stars the current place instead
            // of reopening it.
            val open = starredModals.values.firstOrNull { it.isOpen }
                ?: topbarStarredModalLazy.takeIf { it.isInitialized() }?.value?.takeIf { it.isOpen }
            if (open != null) {
                open.toggleStarredFromShortcut()
                return@lambda
            }
            val paneId = focusedPaneId() ?: return@lambda
            openStarredModal(paneId)
        }
        starredShortcutHandler = handler
        document.addEventListener("keydown", handler, /* capture = */ true)
    }

    /**
     * Re-reads every cached folder listing whenever the window regains
     * focus (TRF-6), so a file added in Finder — or by any other program
     * — shows up in the folder contents lists and count badges as soon as
     * the user comes back to the app. Delegates to
     * [DocumentRegistry.refreshVaultListings].
     */
    private fun installFolderRefreshOnFocus() {
        window.addEventListener("focus", { _: Event ->
            scope.launch { documentRegistry.refreshVaultListings() }
            // Board nodes on screen (LBR-27) re-read their boards too.
            documentRegistry.lunicleBoards?.refreshShown()
        })
    }

    /**
     * Subscribes to the Electron preload's `lunarbor:show-hotkeys` channel,
     * dispatched when the user picks `Lunarbor → Hotkeys…` from the macOS
     * application menu. No-op when running in a plain browser (no
     * `darknessApi.onShowHotkeys` global) — the in-app `Cmd+/` shortcut
     * still works there.
     */
    private fun installHotkeysMenuBridge() {
        val api = js("globalThis.darknessApi") ?: return
        val onShow = js("api && api.onShowHotkeys") ?: return
        if (js("typeof onShow !== 'function'") as Boolean) return
        val callback: () -> Unit = { openHotkeysSidebar() }
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
        addStyleCmd("heading-1", "Heading 1") { it.applyLineStyle(se.soderbjorn.lunarbor.data.LineStyle.HEADING_1) }
        addStyleCmd("heading-2", "Heading 2") { it.applyLineStyle(se.soderbjorn.lunarbor.data.LineStyle.HEADING_2) }
        addStyleCmd("heading-3", "Heading 3") { it.applyLineStyle(se.soderbjorn.lunarbor.data.LineStyle.HEADING_3) }
        addStyleCmd("heading-4", "Heading 4") { it.applyLineStyle(se.soderbjorn.lunarbor.data.LineStyle.HEADING_4) }
        addStyleCmd("heading-5", "Heading 5") { it.applyLineStyle(se.soderbjorn.lunarbor.data.LineStyle.HEADING_5) }
        addStyleCmd("heading-6", "Heading 6") { it.applyLineStyle(se.soderbjorn.lunarbor.data.LineStyle.HEADING_6) }
        addStyleCmd("quote", "Quote") { it.applyLineStyle(se.soderbjorn.lunarbor.data.LineStyle.QUOTE) }

        // Inline styles
        addStyleCmd("bold", "Bold") { it.applyInlineStyle(se.soderbjorn.lunarbor.data.InlineStyle.BOLD) }
        addStyleCmd("italic", "Italic") { it.applyInlineStyle(se.soderbjorn.lunarbor.data.InlineStyle.ITALIC) }
        addStyleCmd("strikethrough", "Strikethrough") {
            it.applyInlineStyle(se.soderbjorn.lunarbor.data.InlineStyle.STRIKETHROUGH)
        }
        addStyleCmd("inline-code", "Inline code") {
            it.applyInlineStyle(se.soderbjorn.lunarbor.data.InlineStyle.INLINE_CODE)
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
        // TRF-8: the same Insert Link search, named for linking a node —
        // whole vault, from the root, however deep the pane is zoomed.
        out += CommandPalette.Command(
            id = "link-to-node",
            title = "Insert Mirror…",
            run = {
                val paneId = focusedPaneId()
                if (paneId != null) openInsertLinkModal(paneId, placeholder = "Mirror a node…")
            },
        )
        // Daily notes (LBR-19): today's journal item, prepared if missing.
        out += CommandPalette.Command(id = "today", title = "Today", run = { goToToday() })
        // LBR-20: offered only on a journal day (or inside one); palette
        // only, no hotkeys (decided in the ticket).
        if (focusedPaneViewModel()?.let { it.journalDayOf(it.currentBackingState) } != null) {
            out += CommandPalette.Command(id = "previous-day", title = "Previous day", run = { goToAdjacentDay(forward = false) })
            out += CommandPalette.Command(id = "next-day", title = "Next day", run = { goToAdjacentDay(forward = true) })
        }
        // LBR-21: the daily template — any node page; "Stop using…" on the
        // template's own page instead.
        focusedPaneViewModel()?.let { vm ->
            val st = vm.currentBackingState
            if (vm.isDailyTemplatePage(st)) {
                out += CommandPalette.Command(
                    id = "stop-daily-template",
                    title = "Stop using as daily template",
                    run = { vm.setDailyTemplate(false) },
                )
            } else if (vm.canUseAsDailyTemplate(st)) {
                out += CommandPalette.Command(
                    id = "use-daily-template",
                    title = "Use as daily template",
                    run = { vm.setDailyTemplate(true) },
                )
            }
        }
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
        // Blocks (TRF-5): a bordered free-Markdown block after the
        // caret's bullet, and its removal (both undoable).
        addStyleCmd("insert-block", "Insert block") { it.insertBlock() }
        addStyleCmd("delete-block", "Delete block") { it.deleteBlockAtCursor() }
        // A search node with an example expression, selected for editing.
        addStyleCmd("insert-search-node", "Insert search node") { it.insertSearchNode() }
        // A board node (LBR-27): pick a connection (skipped when there is
        // one) and a project. Electron only — the demo has no Lunicle.
        if (lunicleService != null) {
            out += CommandPalette.Command(id = "insert-lunicle-board", title = "Insert Lunicle board…", run = { insertLunicleBoard() })
        }
        // Unfold the page node's direct children (one level); fold every
        // item under it, at every depth (large blocks' previews stay).
        addStyleCmd("expand-all-children", "Expand all children") { it.expandChildren() }
        addStyleCmd("collapse-all-children", "Collapse children and grandchildren") { it.collapseChildrenAndGrandchildren() }
        // The page node's direct children by name, each with its subtree.
        addStyleCmd("sort-children-by-name", "Sort children by name") { it.sortChildrenByName() }
        addStyleCmd("sort-children-by-name-reversed", "Sort children by name, reversed") { it.sortChildrenByName(reverse = true) }
        // Done state (LBR-24): strike / unstrike the caret's items, and the
        // pane's "Hide done items" view filter — outlines only.
        focusedPaneViewModel()?.let { vm ->
            val st = vm.currentBackingState
            // While the pane search lists results, or the arrow keys are on
            // a search node's results, Toggle done acts on the highlighted
            // one, where it is stored (LBR-22).
            val screen = focusedPaneId()?.let { paneEditors[it] }
            val paneHit = if (st.isSearchActive) screen?.selectedSearchHit else null
            val hit = paneHit ?: screen?.selectedSearchNodeHit
            if (hit != null) {
                if (hit.canToggleDone) {
                    out += CommandPalette.Command(
                        id = "toggle-done",
                        title = "Toggle done",
                        run = {
                            if (paneHit != null) screen?.toggleSelectedSearchHitDone() else screen?.toggleSelectedSearchNodeHitDone()
                        },
                    )
                }
            } else if (vm.canToggleDone(st)) {
                addStyleCmd("toggle-done", "Toggle done") { it.toggleDone() }
            }
            if (!st.isMarkdownMode && !st.isFileView) {
                if (st.hideDone) {
                    addStyleCmd("show-done-items", "Show done items") { it.setHideDone(false) }
                } else {
                    addStyleCmd("hide-done-items", "Hide done items") { it.setHideDone(true) }
                }
            }
        }
        // The node the page is (zoom target, or a node's own outline), with
        // everything under it, after asking; the pane goes up a level. On a
        // note, image, drawing or other file, the same command trashes the
        // file instead.
        focusedPaneViewModel()?.let { vm -> vm.pageFile(vm.currentBackingState) }?.let { file ->
            val name = NoteRepository.displayNameOf(file).ifBlank { file.substringAfterLast('/') }
            addStyleCmd("delete-this-node", "Delete this file") { vm ->
                showConfirmDialog(
                    title = "Move “$name” to the trash?",
                    message = "The file goes to the vault's .trash folder. Links to it will show as broken.",
                    confirmLabel = "Move to Trash",
                    cancelLabel = "Cancel",
                    destructive = true,
                    onConfirm = {
                        scope.launch {
                            val error = vm.trashPageFile() ?: return@launch
                            showConfirmDialog(
                                title = "The file was kept",
                                message = "“$name” was not moved to the trash. $error",
                                cancelLabel = "Close",
                            )
                        }
                    },
                )
            }
        }
        focusedPaneViewModel()?.let { vm -> vm.pageNodeTitle(vm.currentBackingState) }?.let { title ->
            addStyleCmd("delete-this-node", "Delete this node") { vm ->
                showConfirmDialog(
                    title = "Delete “${title.ifBlank { "(untitled)" }}”?",
                    message = "This node and everything under it will be deleted (moved to the vault's trash). ⌘Z brings it back.",
                    confirmLabel = "Delete",
                    cancelLabel = "Cancel",
                    destructive = true,
                    onConfirm = { vm.deletePageNode() },
                )
            }
        }
        // Finder, showing what the pane is on selected in its parent: the
        // open note, image, drawing or page, or the node's folder (the
        // root node's own `_node.md`, as the vault root can't be
        // revealed).
        addStyleCmd("reveal-in-finder", "Reveal in Finder") { vm ->
            scope.launch {
                val path = vm.currentLocationPath() ?: return@launch
                vm.revealInFinder(path.ifEmpty { documentRegistry.rootFileName })
            }
        }
        // Privacy modes (LBR-10): pick the current one, edit the modes.
        out += CommandPalette.Command(
            id = "configure-privacy",
            title = "Configure privacy",
            run = { openPrivacyDialog(scope, documentRegistry) },
        )
        // One switch per mode other than the current one, plus "None"
        // while a mode is on.
        val privacy = documentRegistry.privacyFlow.value
        if (privacy.currentId != null) {
            out += CommandPalette.Command(
                id = "privacy-mode:none",
                title = "Privacy mode: None",
                run = { scope.launch { documentRegistry.setPrivacyMode(null) } },
            )
        }
        for (mode in privacy.modes) {
            if (mode.id == privacy.currentId) continue
            out += CommandPalette.Command(
                id = "privacy-mode:${mode.id}",
                title = "Privacy mode: ${mode.name}",
                run = { scope.launch { documentRegistry.setPrivacyMode(mode.id) } },
            )
        }
        // The pane's search field (also the header's magnifier and Cmd-F).
        out += CommandPalette.Command(
            id = "search-in-pane",
            title = "Search this tree",
            run = { focusedPaneId()?.let { paneEditors[it]?.openSearch() } },
        )
        // Every block in the page's whole tree (folders loaded all the
        // way down) becomes bullets, one per line (undoable).
        addStyleCmd("convert-block-to-nodes", "Convert block to nodes") { it.convertBlockToNodes() }
        // TEMPORARY: every block under the page (folders loaded all the
        // way down) loses the imported notes' `---` / `![[…]]` frame.
        addStyleCmd("clean-up-blocks-temp", "Clean up blocks (temporary)") { it.cleanUpBlocks() }
        // A block holding a Markdown file's text: the system file chooser
        // picks the file (anywhere, not only in the vault); its text is
        // copied in, the file is left alone.
        addStyleCmd("insert-markdown-file-as-block", "Insert Markdown file as block…") { vm ->
            pickMarkdownFileText { text -> vm.insertMarkdownAsBlock(text) }
        }
        // Folder contents (TRF-6): an empty `Untitled.md` (then
        // `Untitled 2.md`, …) in the current node's folder, opened in the
        // focused pane.
        addStyleCmd("new-markdown-file", "New Markdown file") { it.newMarkdownFile() }
        // An empty `Untitled.excalidraw` (then `Untitled 2.excalidraw`, …)
        // in the same place, opened in the focused pane's drawing editor.
        addStyleCmd("new-excalidraw-drawing", "New Excalidraw drawing") { it.newDrawingFile() }
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
        // command palette only ships lunarbor-specific commands.

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
     * (if any), the active file's path from the vault root
     * ([filePathLabel]), and the current zoom path so each row reads like
     * `"Home / Work / My Note / Recipes / Pasta"`.
     *
     * Panes of a tab that has not been shown yet have no view model (it
     * is built when the pane renders), and a pane that is still moving to
     * its restored location shows the root for a moment; both read their
     * stored location ([paneLocations]) instead, so the row names where
     * the pane is rather than `Home`.
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
        val stored = paneLocations[paneId]
        val backing = paneViewModels[paneId]?.stateFlow?.value?.backingState
        val booted = backing != null && (backing.isLoaded || backing.isImageView || backing.isHtmlView) && paneId !in restoringPanes
        val fileLabel: String
        val path: String?
        if (!booted && stored != null) {
            // What the privacy mode hides is not named, even for a pane
            // that has not moved off it yet: the file shows as its nearest
            // visible node, the zoom path stops before a hidden title.
            val filter = documentRegistry.privacyFilter
            var file = stored.fileRel
            while (file.isNotEmpty() && file != documentRegistry.rootFileName && documentRegistry.isPathHidden(file)) {
                val folder = if (NoteRepository.isOutlineFile(file)) NoteRepository.folderOfOutline(file) else file
                val parent = folder.substringBeforeLast('/', "")
                file = if (parent.isEmpty()) documentRegistry.rootFileName else NoteRepository.outlineFileOf(parent)
            }
            fileLabel = filePathLabel(file)
            path = if (file != stored.fileRel) null else stored.zoomTitlePath
                .takeWhile { !filter.hides(se.soderbjorn.lunarbor.data.TextIndex.tagKeysOfRow("* $it")) }
                .map { FolderName.withoutTags(InlineMarkdownTokenizer.tokenize(it)).ifBlank { "(untitled)" } }
                .takeIf { it.isNotEmpty() }
                ?.joinToString(" / ")
        } else {
            fileLabel = activeFileDisplayName(paneId)
            path = zoomPathStringForPane(paneId)
        }
        val own = float?.title?.ifBlank { null }?.takeUnless { it == "Untitled" }
        val combined = if (path != null) "$fileLabel / $path" else fileLabel
        return if (own != null) "$own / $combined" else combined
    }

    /**
     * Reconstructs lunarbor's [LayoutState] from the persisted JSON read
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
        // floats. Without this sync, [LunarborTabSource.notify]
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
     * lunarbor only owns pane *identity* (id + title + the file the
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
    // Wired into [LunarborTabSource]'s callbacks in [render]. Each
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

    /**
     * Sets a tab's `isHidden` flag: hidden tabs leave the tab strip and
     * are listed under the toolkit's overflow menu ("Unlisted tabs"),
     * from where they can be activated or shown again. Persisted with the
     * rest of [layoutState].
     *
     * Called by [LunarborTabSource] from the tab menu's "Hide / Show in
     * tab bar" row.
     *
     * @param id the tab to change.
     * @param hidden `true` to hide the tab from the strip.
     */
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
     * Sets a tab's `isHiddenFromSidebar` flag. Unlike [setTabHidden], the
     * tab stays in the strip and remains the active tab if it was — only
     * the left sidebar tree skips it on its next render. Persisted with
     * the rest of [layoutState].
     *
     * Called by [LunarborTabSource] from the tab menu's "Hide / Show in
     * side bar" row.
     *
     * @param id the tab to change.
     * @param hidden `true` to leave the tab out of the sidebar tree.
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
     * Builds the breadcrumb for [paneId]'s pane header: the pane's whole
     * location, root-most first.
     *
     *  1. The file part ([MainViewModel.fileBreadcrumb]): `Home`, each
     *     node folder, then the open file. Every folder segment opens
     *     that node; the open file's segment clears the zoom when the
     *     pane is zoomed and is inert otherwise.
     *  2. When zoomed: each bullet ancestor (outer-to-inner, via
     *     `zoomTo(ancestor.lineId)`), then the zoom target, inert.
     *
     * Clicking the parent segment is "up" and `Home` is "home", which is
     * why the header has no up / home buttons (the keyboard shortcuts
     * remain). Empty until the pane's document state exists, so the
     * header shows its plain title while booting.
     *
     * Called by the toolkit's `paneTitleSegments` callback on every
     * chrome render.
     */
    private fun paneBreadcrumbSegments(paneId: String): List<PaneTitleSegment> {
        // Ensure the pane's view-model exists before reading its state.
        // On the very first chrome render for a freshly-added pane
        // (where `renderPaneContent` hasn't mounted yet) the VM is absent;
        // creating it here also starts the collector that refreshes the
        // chrome on navigation. `ensurePaneViewModel` is idempotent.
        ensurePaneViewModel(paneId)
        val vm = paneViewModels[paneId] ?: return emptyList()
        chromeDrawnKeys[paneId] = chromeKeyOf(vm, vm.stateFlow.value.backingState)
        val backing = vm.stateFlow.value.backingState ?: return emptyList()
        val zoom = vm.zoomInfo(backing)
        val files = vm.fileBreadcrumb(backing)
        val segments = mutableListOf<PaneTitleSegment>()
        for ((i, crumb) in files.withIndex()) {
            val onClick: (() -> Unit)? = when {
                i < files.lastIndex -> ({ vm.navigateToVaultFile(crumb.fileRel) })
                zoom != null -> ({ vm.zoomTo(null) })
                else -> null
            }
            segments += PaneTitleSegment(label = crumb.label, onClick = onClick)
        }
        if (zoom == null) return segments
        // Line-level markers (`# `, `> `) are already stripped by
        // `bulletAncestors` / `zoomInfo`. We additionally flatten inline
        // markers (`**bold**`, `*italic*`, `` `code` ``, `~~strike~~`,
        // `[label](href)`) via the same tokenizer the editor's paint
        // loop uses, so the breadcrumb shows clean, plain-text labels
        // regardless of the underlying bullet's formatting. `#tags` are
        // left out too: they label the item (and show as pills on its
        // row and the page title), they don't name it. Navigation is
        // keyed on `lineId`, so the text-stripping never affects where a
        // click takes you. Node queries (`{{search: …}}`, `{{lunicle: …}}`)
        // are no part of a name either.
        fun flat(s: String) = FolderName.withoutTags(
            InlineMarkdownTokenizer.tokenize(se.soderbjorn.lunarbor.data.LunicleNode.stripQueries(s)),
        )
        for (ancestor in vm.bulletAncestors(backing)) {
            segments += PaneTitleSegment(
                label = flat(ancestor.titleText).ifBlank { "(untitled)" },
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
     * The pane header's Back / Forward pair for [paneId], placed in the
     * toolkit's leading badge slot (between the pane icon and the
     * breadcrumb). Built once per pane and updated in place: each call
     * re-reads the pane's history stacks and dims the button whose stack
     * is empty ([DISABLED_CLASS]) — the buttons never move, for muscle
     * memory.
     *
     * The click handlers read the live state, so a stale element can
     * never navigate somewhere the stacks no longer allow. Mousedowns are
     * stopped so a press never starts the header's pane-drag gesture.
     *
     * Called by the toolkit's `paneHeaderBadge` callback on every chrome
     * render (the navigation collector in [ensurePaneViewModel] triggers
     * one whenever the stacks change).
     */
    private fun paneNavCluster(paneId: String): HTMLElement {
        val cluster = paneNavClusters.getOrPut(paneId) {
            val el = document.createElement("span") as HTMLElement
            el.className = "lunarbor-pane-nav"
            fun button(cls: String, tooltip: String, icon: String, go: (MainViewModel) -> Unit): HTMLElement {
                val b = document.createElement("button") as HTMLElement
                b.setAttribute("type", "button")
                b.className = "dt-pane-action $cls"
                b.setAttribute("title", tooltip)
                b.setAttribute("aria-label", tooltip)
                b.setAttribute("draggable", "false")
                b.innerHTML = icon
                b.addEventListener("mousedown", { ev -> ev.stopPropagation() })
                b.addEventListener("click", { ev ->
                    ev.stopPropagation()
                    paneViewModels[paneId]?.let(go)
                })
                el.appendChild(b)
                return b
            }
            button("lunarbor-pane-nav-back", "Back (⌥⌘←)", ICON_BACK) { vm ->
                val st = vm.stateFlow.value.backingState
                if (st != null && vm.canZoomBack(st)) vm.zoomBack()
            }
            button("lunarbor-pane-nav-forward", "Forward (⌥⌘→)", ICON_FORWARD) { vm ->
                val st = vm.stateFlow.value.backingState
                if (st != null && vm.canZoomForward(st)) vm.zoomForward()
            }
            el
        }
        val vm = paneViewModels[paneId]
        val backing = vm?.stateFlow?.value?.backingState
        val canBack = backing != null && vm.canZoomBack(backing)
        val canForward = backing != null && vm.canZoomForward(backing)
        cluster.querySelector(".lunarbor-pane-nav-back")?.classList?.toggle(DISABLED_CLASS, !canBack)
        cluster.querySelector(".lunarbor-pane-nav-forward")?.classList?.toggle(DISABLED_CLASS, !canForward)
        return cluster
    }

    /**
     * Lazily creates the pane's [MainViewModel] + [PaneBackingViewModel]
     * the first time it's needed. Used by [renderPaneContent] when
     * mounting the editor DOM, and by the pane-chrome callbacks
     * ([paneBreadcrumbSegments], [paneSidebarLabel]) which need to read
     * the pane's zoom path before content has mounted.
     *
     * Also installs the per-pane zoom-path collector that triggers a
     * chrome + sidebar refresh on every zoom transition.
     */
    private fun ensurePaneViewModel(paneId: String, initialFileRel: String? = null) {
        if (paneId in paneViewModels) return
        // No explicit start: reopen where the pane was ([paneLocations]).
        // An image or drawing has no document, so that pane starts at the root and
        // moves there once loaded.
        val restore = if (initialFileRel == null) paneLocations[paneId] else null
        val startFile = initialFileRel
            ?: restore?.fileRel?.takeUnless { NoteRepository.isFileViewPath(it) }
            ?: documentRegistry.rootFileName
        val docView = se.soderbjorn.lunarbor.main.PaneBackingViewModel(
            documentRegistry,
            scope,
            initialFileRel = startFile,
        )
        val paneVm = se.soderbjorn.lunarbor.main.MainViewModel(scope, docView)
        paneViewModels[paneId] = paneVm
        // "Hide done items" (LBR-24) is pane state, kept with the location.
        if (initialFileRel == null && paneLocations.isHideDone(paneId)) paneVm.setHideDone(true)
        scope.launch {
            paneVm.stateFlow
                .mapNotNull { it.backingState?.hideDone }
                .distinctUntilChanged()
                .collect { on -> if (paneViewModels[paneId] === paneVm) paneLocations.recordHideDone(paneId, on) }
        }
        // The pane's open search comes back too, once it is where it was
        // (the search filters that page). Until then its search state is
        // not recorded, so the pane's initial "closed" can't erase it.
        val restoreSearch = if (initialFileRel == null) paneLocations.search(paneId) else null
        val restoreScroll = if (initialFileRel == null) paneLocations.scroll(paneId) else null
        val restoreCaret = if (initialFileRel == null) paneLocations.caret(paneId) else null
        if (restoreSearch != null || restoreScroll != null || restoreCaret != null) restoringSearches += paneId
        fun applyRestoredSearch() {
            if (restoreSearch == null && restoreScroll == null && restoreCaret == null) return
            scope.launch {
                val ready = paneVm.stateFlow.first { it.backingState?.let { b -> b.isLoaded || b.isImageView || b.isHtmlView } == true }
                if (paneViewModels[paneId] === paneVm && ready.backingState?.let { it.isImageView || it.isHtmlView } == false && restoreSearch != null) {
                    if (paneLocations.isSearchReversed(paneId)) paneVm.setSearchReversed(true)
                    paneVm.setSearchQuery(restoreSearch)
                    // Scroll the result list once it is there.
                    // (No hits within 1.5 s: nothing to scroll anyway.)
                    kotlinx.coroutines.withTimeoutOrNull(1_500) {
                        paneVm.stateFlow.first { st ->
                            st.backingState?.let { b -> !b.isSearchActive || b.searchHits.isNotEmpty() } == true
                        }
                    }
                }
                // The caret before the scroll, so the scroll wins over the
                // caret's scroll-into-view.
                if (paneViewModels[paneId] === paneVm && restoreCaret != null) {
                    paneVm.restoreCaret(restoreCaret, restore ?: paneVm.currentLocation())
                }
                if (paneViewModels[paneId] === paneVm && restoreScroll != null) {
                    paneVm.restoreScroll(restoreScroll, restore ?: paneVm.currentLocation())
                }
                restoringSearches -= paneId
            }
        }
        if (restore != null && (restore.fileRel != startFile || restore.zoomTitlePath.isNotEmpty())) {
            restoringPanes += paneId
            paneVm.openLocation(restore).invokeOnCompletion {
                restoringPanes -= paneId
                if (paneViewModels[paneId] === paneVm) paneLocations.record(paneId, paneVm.currentLocation())
                applyRestoredSearch()
            }
        } else {
            applyRestoredSearch()
        }
        // Remember the pane's caret, so a window reopens with it in place.
        scope.launch {
            paneVm.stateFlow
                .map { it.backingState?.let { b -> Triple(b.activeFileRel, b.cursorRow, b.cursorCol) } }
                .distinctUntilChanged()
                .collect {
                    if (paneId in restoringSearches || paneId in restoringPanes || paneViewModels[paneId] !== paneVm) return@collect
                    paneVm.currentCaret()?.let { c -> paneLocations.recordCaret(paneId, c) }
                }
        }
        // Remember the pane's search text (null once closed), so a window
        // reopens with its search as it was.
        scope.launch {
            paneVm.stateFlow
                .map { it.backingState?.let { b -> b.searchQuery to b.searchReversed } }
                .distinctUntilChanged()
                .collect { search ->
                    if (paneId in restoringSearches || paneViewModels[paneId] !== paneVm) return@collect
                    paneLocations.recordSearch(paneId, search?.first, search?.second == true)
                }
        }
        // Remember where the pane is after every navigation (file switch or
        // zoom change), so it reopens there after a restart.
        scope.launch {
            paneVm.stateFlow
                .map { state ->
                    val b = state.backingState
                    Triple(b?.activeFileRel, b?.zoomedLineId, b != null && (b.isLoaded || b.isImageView || b.isHtmlView))
                }
                .distinctUntilChanged()
                .collect { (_, _, ready) ->
                    if (!ready || paneId in restoringPanes || paneViewModels[paneId] !== paneVm) return@collect
                    paneLocations.record(paneId, paneVm.currentLocation())
                }
        }
        // Refresh chrome + sidebar whenever this pane's zoom path or
        // back/forward stack availability changes. Path changes drive the
        // breadcrumb; stack-availability changes drive whether the
        // back/forward toolbar buttons appear. Distinct-by-tuple keeps
        // every keystroke from triggering a full chrome rebuild — only
        // true navigation transitions re-render. A key the header already
        // shows ([chromeDrawnKeys]) is skipped: a redundant rerender would
        // wipe any in-flight CSS transition (e.g. the new-pane entry
        // pop-in or the restore-from-maximize on an existing pane).
        scope.launch {
            paneVm.stateFlow
                .map { state -> chromeKeyOf(paneVm, state.backingState) }
                .distinctUntilChanged()
                .collect { key ->
                    if (chromeDrawnKeys[paneId] == key) return@collect
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
                        // navigation animation starts.
                        paneEditors[paneId]?.prepareNavigationCrossfade()
                        notifyToolkitTabs?.invoke()
                    }
                    notifyToolkitTabs?.invoke()
                    // Rebuild the per-pane chrome header so its breadcrumb
                    // and Back / Forward buttons reflect the new location
                    // and stack availability. `notifyToolkitTabs` only
                    // pushes a fresh TabListSnapshot (tab + pane
                    // structure); it doesn't re-invoke the header
                    // callbacks. `shellHandle.refresh()` does — see
                    // [AppShellHandle.refresh].
                    shellHandle?.refresh()
                }
        }
    }

    /**
     * Everything a pane's header shows that navigation changes: the
     * active file (its breadcrumb), the zoom path, and whether Back /
     * Forward can go anywhere. See [chromeDrawnKeys].
     */
    private fun chromeKeyOf(vm: MainViewModel, backing: PaneBackingViewModel.State?): List<Any?> =
        listOf(
            backing?.activeFileRel,
            backing?.let { vm.zoomPathSegments(it) } ?: emptyList<String>(),
            backing != null && vm.canZoomBack(backing),
            backing != null && vm.canZoomForward(backing),
        )

    /**
     * Path label of the file currently loaded in [paneId] — see
     * [filePathLabel]. Falls back to "Home" when the pane's view model
     * hasn't booted yet.
     */
    private fun activeFileDisplayName(paneId: String): String {
        val backing = paneViewModels[paneId]?.stateFlow?.value?.backingState ?: return "Home"
        return filePathLabel(backing.activeFileRel)
    }

    /**
     * [fileRel] as the sidebar names it: its whole path from the vault
     * root, e.g. `Home / Work / Notes`, the same segments as the pane
     * header's breadcrumb ([fileBreadcrumbOf]). So a pane that opened a
     * deep file directly reads like one that zoomed down to it.
     */
    private fun filePathLabel(fileRel: String): String =
        fileBreadcrumbOf(fileRel, documentRegistry.rootFileName)
            .joinToString(" / ") { it.label.ifBlank { "(untitled)" } }

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
     * - `.lunarbor-title` — the big zoom headline above the editor.
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
                val chromeEl = startEl?.closest(".lunarbor-title, .dt-pane-breadcrumb-segment")
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
     * Builds the trailing-action strip for [paneId]'s pane header: the
     * Style button, before the toolkit's window controls. Navigation is
     * not here — Back / Forward are in the leading slot
     * ([paneNavCluster]) and the breadcrumb covers up / home.
     *
     * Since the toolkit's [PaneAction] has no native `disabled` flag, an
     * inert Style button gets a no-op handler and [DISABLED_CLASS]; the
     * matching CSS rule injected by [ensureLunarborChromeStyles] paints
     * it grayed-out.
     */
    private fun buildPaneActions(paneId: String): List<PaneAction> {
        val paneVm = paneViewModels[paneId]
        // Style is enabled whenever the pane has a VM AND the user
        // does not currently have a live DOM selection on a read-only
        // chrome surface of this pane (the `.lunarbor-title` headline
        // or one of the `.dt-pane-breadcrumb-segment` labels). In the
        // latter case the user is visually addressing text they cannot
        // edit — opening the Style dropdown would silently apply the
        // chosen style to the editor's stale model cursor, which is
        // confusing. The chrome-selection flag is maintained by
        // [refreshChromeSelectionState], wired off `selectionchange`.
        val canStyle = paneVm != null && paneId !in panesWithChromeSelection
        // The toolkit auto-inserts a separator between this list and its
        // standard window-control cluster — no manual `separator()` needed.
        return listOf(
            PaneAction(
                iconHtml = PaneSearchBar.ICON_SEARCH,
                tooltip = "Search this tree (⌘F)",
                handler = { paneEditors[paneId]?.openSearch() },
                extraClass = "lunarbor-pane-action-search",
            ),
            PaneAction(
                iconHtml = StyleDropdownIcons.TOOLBAR_STYLE,
                tooltip = "Style",
                handler = if (canStyle) ({ openStyleMenu(paneId) }) else ({}),
                extraClass = "lunarbor-pane-action-style" + if (!canStyle) " $DISABLED_CLASS" else "",
            )
        )
    }

    /**
     * Toggles the style dropdown for [paneId]. Looks up the rendered
     * Style toolbar button by class within the pane's chrome and anchors
     * the popover under it. Lazily creates a [StyleDropdown] per pane.
     */
    private fun openStyleMenu(paneId: String) {
        val paneVm = paneViewModels[paneId] ?: return
        val button = document.querySelector(
            "[data-pane-id='$paneId'] .dt-pane-action.lunarbor-pane-action-style"
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
            fileSystem = fileSystem,
            )
        }
        modal.open()
    }

    /**
     * Opens the per-pane Insert Link modal — for both "Insert Link" and
     * "Insert Mirror…", which differ only in [placeholder]. Same lifecycle
     * pattern as [openStarredModal] — lazy first-open create, reuse
     * thereafter, one modal per pane and placeholder.
     */
    private fun openInsertLinkModal(paneId: String, placeholder: String = "Find a node or file to link…") {
        if (paneViewModels[paneId] == null) return
        val modal = insertLinkModals.getOrPut("$paneId|$placeholder") {
            LinkSearchModal.forInsertLink(
                parentScope = scope,
                activePaneVmProvider = { paneViewModels[paneId] },
                placeholder = placeholder,
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
     * cleared in [closeFloatingPane].
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
     * The top-bar cube that toggles 3D mode ([SpaceMode.toggle], also ⌃⌘3).
     * Its id, `lunarbor-topbar-space`, is what the chrome CSS hides while
     * 3D mode is disabled ([ensureLunarborChromeStyles]).
     *
     * In the browser demo ([isDemoMode]) the bare cube is dressed as in
     * Lunamux's web demo: a visible "3D Mode" label, a small "EXPERIMENTAL"
     * tag and an accent glow that stops by itself after ~15 s — website
     * visitors have no reason to hover an unlabelled icon, and 3D mode is
     * the demo's showpiece. The desktop app keeps the quiet icon.
     *
     * Called once, while building the shell spec in [render].
     *
     * @return the cube's [TopbarAction]; in the demo it carries its own element.
     */
    private fun spaceTopbarAction(): TopbarAction {
        val label = "3D mode (⌃⌘3)"
        if (!isDemoMode()) {
            return TopbarAction(
                id = SPACE_TOPBAR_ID,
                iconHtml = ICON_CUBE,
                label = label,
                onActivate = { spaceMode.toggle() },
            )
        }
        // Styled like the toolkit's own action buttons (transparent, no
        // border), widened into a labelled pill.
        val button = document.createElement("button") as HTMLElement
        button.id = SPACE_TOPBAR_ID
        button.setAttribute("type", "button")
        button.title = label
        button.setAttribute("aria-label", label)
        button.innerHTML = ICON_CUBE
        button.style.cssText = "display:inline-flex;align-items:center;gap:6px;padding:4px 8px;" +
            "background:transparent;border:0;border-radius:6px;color:inherit;cursor:pointer;"
        button.addEventListener("click", { spaceMode.toggle() })

        val text = document.createElement("span") as HTMLElement
        text.textContent = "3D Mode"
        text.style.cssText = "font-size:12px;font-weight:600;line-height:normal;white-space:nowrap;"
        button.appendChild(text)

        val tag = document.createElement("span") as HTMLElement
        tag.textContent = "EXPERIMENTAL"
        tag.style.cssText = "align-self:flex-start;margin-top:1px;font-size:8px;font-weight:700;" +
            "line-height:normal;letter-spacing:0.5px;color:var(--t-accent, #7aa2ff);"
        button.appendChild(tag)

        // A slow accent glow swell for the first ~15 s (inline `style=`
        // cannot declare @keyframes, so they ride in a <style>).
        val glow = document.createElement("style") as HTMLElement
        glow.textContent = "@keyframes lunarbor-space-demo-glow{" +
            "0%,100%{box-shadow:0 0 0 transparent;}" +
            "50%{box-shadow:0 0 12px color-mix(in srgb, var(--t-accent, #7aa2ff) 55%, transparent);}}"
        document.head?.appendChild(glow)
        button.style.setProperty("animation", "lunarbor-space-demo-glow 3s ease-in-out infinite")
        window.setTimeout({ button.style.removeProperty("animation") }, 15_000)

        return TopbarAction(id = SPACE_TOPBAR_ID, label = label, onActivate = { spaceMode.toggle() }, element = button)
    }

    /**
     * Injects lunarbor-only chrome styles that aren't part of the toolkit
     * stylesheet: the disabled state for header buttons (Back / Forward
     * stay in place when inert, dimmed instead of removed), the Back /
     * Forward group in the header's leading slot, and the sentence-case
     * breadcrumb with `›` separators. Idempotent via the element id guard.
     */
    private fun ensureLunarborChromeStyles() {
        if (document.getElementById("lunarbor-chrome-style") != null) return
        val style = document.createElement("style") as HTMLElement
        style.id = "lunarbor-chrome-style"
        style.textContent = """
            body[data-lunarbor-space] .lunarbor-space-cube { color: var(--t-accent, #7aa2ff); }
            body:not([data-lunarbor-space-enabled]) #lunarbor-topbar-space { display: none !important; }
            .dt-pane-action.$DISABLED_CLASS {
                opacity: 0.32;
                pointer-events: none;
                cursor: default;
            }
            /* ── Sidebar brand logo (termtastic-style) ──────────────────
               Status dot + lowercase "lunarbor" wordmark in the left
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
                   override back so the wordmark reads "lunarbor". */
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
            /* ── Pane header navigation ─────────────────────────────
               Back / Forward (AppShell.paneNavCluster) sit in the
               toolkit's leading badge slot, right before the breadcrumb,
               so history and location read as one group. Unlike the
               trailing action strip they are always visible. */
            .lunarbor-pane-nav {
                display: inline-flex;
                align-items: center;
                gap: 0;
                margin: -4px 0 -4px -2px;
            }
            .lunarbor-pane-nav .dt-pane-action {
                cursor: pointer;
            }
            /* Sidebar pane rows show the pane's path (`Home / Recipes /
               Soups`), whose informative end is the tail: clip them from
               the START, like the header. Only pane rows (they carry
               `data-pane-id`), not tab rows. `direction: rtl` moves the
               clip to the left edge; the LRI … PDI pair around the text
               isolates it as left-to-right, so punctuation at either end
               (`(untitled)`, a trailing `?`) is not reordered by the RTL
               base direction — the job `<bdi>` does in the pane header. */
            .dt-sidebar-row[data-pane-id] .dt-sidebar-row-label {
                direction: rtl;
                text-align: left;
            }
            .dt-sidebar-row[data-pane-id] .dt-sidebar-row-label::before {
                content: "\2066";
            }
            .dt-sidebar-row[data-pane-id] .dt-sidebar-row-label::after {
                content: "\2069";
            }
            /* A minimized pane's dock chip reuses the leading badge; the
               buttons mean nothing there. */
            .dt-pane-dock-item-badge:has(.lunarbor-pane-nav) {
                display: none;
            }
            /* The breadcrumb names bullets and notes, which read badly in
               ALL CAPS; sentence case, a touch larger, `›` separators. */
            .dt-pane-header {
                --dt-pane-title-transform: none;
                --dt-pane-title-tracking: 0;
                --dt-pane-title-size: 12.5px;
            }
            /* The breadcrumb is the pane's up / home navigation, so each
               segment's hit area spans the full header height (not just
               its 14px line of text) and meets its neighbours at the
               separator — aiming a little high, low or beside a word
               still lands on it instead of starting a pane drag. The row
               reaches into the header's vertical padding (9px at the
               default density) and the segments pad back out, so the
               text does not move. Both boxes stay inside the header, and
               the segments stay inside the row, so nothing is clipped. */
            .dt-pane-header .dt-pane-title.dt-pane-title-breadcrumbs {
                align-self: stretch;
                align-items: center;
                gap: 0;
                margin-block: -9px;
            }
            .dt-pane-header .dt-pane-breadcrumb-segment {
                padding: 13px 4px;
            }
            /* The toolkit paints a hovered segment in the theme's body
               text colour, which nearly vanishes on an active pane's
               light accent header. Keep the header's own text colour;
               the underline alone marks the hover. */
            .dt-pane-header .dt-pane-breadcrumb-segment-link:hover {
                color: inherit;
            }
            .dt-pane-header .dt-pane-breadcrumb-separator {
                font-size: 0;
                align-self: center;
            }
            .dt-pane-header .dt-pane-breadcrumb-separator::after {
                content: "›";
                font-size: 14px;
                line-height: 1;
                padding: 0 1px;
            }
            /* StarredModal reuses the palette backdrop + panel so it
               looks identical to the Cmd-O navigation modal. The few
               .lunarbor-starred-* rules below tweak the header bar
               that replaces the palette's text input — there's no
               type-to-filter for bookmarks, so the slot is repurposed
               as a title + Add + Close row. */
            .lunarbor-starred-header {
                display: flex;
                align-items: center;
                gap: 12px;
                padding: 10px 14px;
                border-bottom: 1px solid var(--t-border, rgba(255, 255, 255, 0.10));
            }
            .lunarbor-starred-title {
                font-size: 14px;
                font-weight: 600;
                opacity: 0.85;
                margin-right: auto;
            }
            .lunarbor-starred-add {
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
            .lunarbor-starred-add:hover {
                background: rgba(255, 255, 255, 0.06);
            }
            .lunarbor-starred-add.is-active {
                background: rgba(255, 200, 60, 0.18);
                border-color: rgba(255, 200, 60, 0.55);
                color: rgb(255, 210, 90);
            }
            .lunarbor-starred-add-icon {
                display: inline-flex;
                width: 14px;
                height: 14px;
            }
            .lunarbor-starred-close {
                background: transparent;
                border: none;
                color: inherit;
                font-size: 22px;
                line-height: 1;
                padding: 0 4px;
                cursor: pointer;
                opacity: 0.7;
            }
            .lunarbor-starred-close:hover { opacity: 1; }
            /* Command palette (Cmd-P). Surface colors match the toolkit
               variables so the palette inherits the active theme. */
            .lunarbor-palette-backdrop {
                position: fixed;
                inset: 0;
                background: rgba(0, 0, 0, 0.45);
                z-index: 2147483641;
                display: flex;
                align-items: flex-start;
                justify-content: center;
                padding-top: 12vh;
            }
            .lunarbor-palette-panel {
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
            .lunarbor-palette-input {
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
            .lunarbor-palette-input::placeholder {
                color: var(--t-text-dim, rgba(255, 255, 255, 0.45));
            }
            .lunarbor-palette-list {
                flex: 1 1 auto;
                min-height: 0;
                overflow-y: auto;
                padding: 4px;
            }
            .lunarbor-palette-item {
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
            .lunarbor-palette-item.is-active {
                background: color-mix(in srgb, var(--t-accent, #5ab0ff) 22%, transparent);
                color: var(--t-text, inherit);
            }
            .lunarbor-palette-empty {
                padding: 16px;
                font-size: 12px;
                color: var(--t-text-dim, rgba(255, 255, 255, 0.55));
                text-align: center;
            }
            /* Insert Link modal — same visuals as the palette plus a
               two-line layout: bold title + muted breadcrumb. */
            .lunarbor-link-item {
                padding: 7px 14px;
                line-height: 1.25;
            }
            .lunarbor-link-item-title {
                font-size: 15px;
                font-weight: 500;
            }
            .lunarbor-link-item-crumb {
                font-size: 12px;
                color: var(--t-text-dim, rgba(255, 255, 255, 0.55));
                margin-top: 2px;
                white-space: nowrap;
                overflow: hidden;
                text-overflow: ellipsis;
            }
            .lunarbor-link-item-path {
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
            .lunarbor-image-item {
                display: flex;
                gap: 12px;
                align-items: center;
                padding: 6px 14px;
                line-height: 1.25;
            }
            .lunarbor-image-item-thumb {
                width: 48px;
                height: 48px;
                object-fit: cover;
                border-radius: 4px;
                background: var(--t-border, rgba(255, 255, 255, 0.06));
                flex: 0 0 auto;
            }
            .lunarbor-drawing-thumb {
                display: flex;
                align-items: center;
                justify-content: center;
                overflow: hidden;
                font-size: 9px;
                color: var(--t-text-dim, rgba(255, 255, 255, 0.55));
            }
            .lunarbor-drawing-thumb svg {
                width: 100%;
                height: 100%;
            }
            .lunarbor-image-item-meta {
                min-width: 0;
                flex: 1 1 auto;
            }
            .lunarbor-image-item-title {
                font-size: 14px;
                font-weight: 500;
                white-space: nowrap;
                overflow: hidden;
                text-overflow: ellipsis;
            }
            .lunarbor-image-item-path {
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
            .lunarbor-image-popover {
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
            .lunarbor-image-popover-label { color: var(--t-text-dim, rgba(255, 255, 255, 0.55)); }
            .lunarbor-image-popover-input {
                width: 80px;
                padding: 2px 6px;
                border-radius: 4px;
                border: 1px solid var(--t-border, rgba(255, 255, 255, 0.15));
                background: var(--t-surface, rgba(0, 0, 0, 0.30));
                color: inherit;
                font: inherit;
            }
            .lunarbor-image-popover-apply,
            .lunarbor-image-popover-clear {
                padding: 2px 10px;
                border-radius: 4px;
                border: 1px solid var(--t-border, rgba(255, 255, 255, 0.15));
                background: var(--t-surface, rgba(0, 0, 0, 0.20));
                color: inherit;
                font: inherit;
                cursor: pointer;
            }
            .lunarbor-image-popover-apply:hover,
            .lunarbor-image-popover-clear:hover {
                background: var(--t-border, rgba(255, 255, 255, 0.10));
            }
            .lunarbor-link-popup {
                position: fixed;
                z-index: 1000;
                display: flex;
                padding: 2px;
                border-radius: 7px;
                background: var(--t-surface-alt, rgba(30, 30, 30, 0.95));
                border: 1px solid var(--t-border, rgba(255, 255, 255, 0.10));
                box-shadow: 0 6px 18px rgba(0, 0, 0, 0.30);
            }
            .lunarbor-link-popup-button {
                padding: 3px 10px;
                border: 0;
                border-radius: 5px;
                background: transparent;
                color: var(--t-text, #e6e6e6);
                font: 600 12px/1.4 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
                cursor: pointer;
                white-space: nowrap;
            }
            .lunarbor-link-popup-button:hover {
                background: color-mix(in srgb, var(--t-accent) 18%, transparent);
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
        paneScreen(id).render(container)
    }

    /**
     * The pane's editor view, built on first use and cached in
     * [paneEditors]. The pane's view model must exist
     * ([ensurePaneViewModel]). Called by [renderPaneContent] and by 3D mode
     * ([spaceHost]), which can show a window before the toolkit renders it.
     */
    private fun paneScreen(id: String): MainScreen =
        paneEditors.getOrPut(id) {
            MainScreen(
                paneViewModels.getValue(id),
                scope,
                onOpenLinkInNewPane = { href ->
                    val tabId = layoutState.activeTabId ?: return@MainScreen
                    openLinkInNewPane(tabId, sourcePaneId = id, href = href)
                },
                onScrollSettled = { top ->
                    if (id !in restoringSearches && id !in restoringPanes) paneLocations.recordScroll(id, top)
                },
                onOpenSearchHit = { hit ->
                    val tabId = layoutState.activeTabId ?: return@MainScreen
                    openSearchHitInNewPane(tabId, sourcePaneId = id, hit = hit)
                },
                onOpenLocationInNewPane = { location ->
                    val tabId = layoutState.activeTabId ?: return@MainScreen
                    openLocationInNewPane(tabId, sourcePaneId = id, location = location)
                },
            )
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
     * Opens the Lunarbor internal link [href] in a new pane spawned
     * from [sourcePaneId]. Wired via [MainScreen.onOpenLinkInNewPane]
     * so a Shift- / ⌘-press or a right-click on a vault link
     * (`OpenGesture`) creates a new pane
     * rooted at the link target, leaving the originating pane
     * untouched. Auto layout (if active) immediately re-tiles to fit
     * both panes.
     */
    private fun openLinkInNewPane(tabId: String, sourcePaneId: String, href: String) {
        val newId = addFloatingPane(tabId, parentPaneId = sourcePaneId) ?: return
        ensurePaneViewModel(newId)
        val paneVm = paneViewModels[newId] ?: return
        // The brand-new pane's document hasn't loaded yet; wait for the
        // first loaded emission before dispatching the navigation so the
        // link's zoom lands on a loaded outline.
        scope.launch {
            paneVm.stateFlow.first { it.backingState?.isLoaded == true }
            paneVm.navigateToLink(href)
        }
    }

    /**
     * Opens the search result [hit] in a new pane spawned from
     * [sourcePaneId]: the result's file, with the caret on its line
     * ([MainViewModel.openSearchHit]). The searching pane keeps its list,
     * so several results can be opened side by side.
     */
    private fun openSearchHitInNewPane(tabId: String, sourcePaneId: String, hit: se.soderbjorn.lunarbor.data.TextHit) {
        val newId = addFloatingPane(tabId, parentPaneId = sourcePaneId) ?: return
        ensurePaneViewModel(newId, initialFileRel = hit.fileRel)
        paneViewModels[newId]?.openSearchHit(hit)
    }

    /**
     * "New window" in the "+" menu (and a plain click on "+"): adds a pane
     * to [tabId] that opens where the tab's focused pane is — same file,
     * same zoom ([MainViewModel.currentLocation] /
     * [MainViewModel.openLocation]). The new pane has its own history and
     * shares the open [Document]s, so edits show in both at once. With no
     * focused pane it opens at the root outline.
     */
    private fun openWindowAtCurrentLocation(tabId: String) {
        // The tab's focused pane (falling back to its first pane — nothing
        // is recorded until a pane has been clicked).
        val sourceId = if (tabId == layoutState.activeTabId) focusedPaneId() else lastFocusedPaneIdByTab[tabId]
        val location = sourceId?.let { paneViewModels[it]?.currentLocation() }
        openLocationInNewPane(tabId, sourceId, location)
    }

    /**
     * Adds a pane to [tabId], spawned from [sourcePaneId], that opens at
     * [location] (the root outline when `null`) and takes the focus.
     * Called by [openWindowAtCurrentLocation] and when a bullet's dot is
     * right-clicked (its item, `MainViewModel.locationOfRow`).
     */
    private fun openLocationInNewPane(
        tabId: String,
        sourcePaneId: String?,
        location: PaneBackingViewModel.FileHistoryEntry?,
    ) {
        val sourceId = sourcePaneId
        // `addFloatingPane` persists, which re-notifies the toolkit; don't
        // notify again (a second rerender wipes the restore-from-maximize
        // transition the first one started).
        val newId = addFloatingPane(tabId, parentPaneId = sourceId) ?: return
        // Start on the location's file directly, so the pane doesn't load
        // the root first. An image or drawing has no document: start at the root and
        // move there once loaded.
        val file = location?.fileRel?.takeIf { it.isNotEmpty() && !NoteRepository.isFileViewPath(it) }
            ?: documentRegistry.rootFileName
        ensurePaneViewModel(newId, initialFileRel = file)
        lastFocusedPaneIdByTab[tabId] = newId
        if (location != null) paneViewModels[newId]?.openLocation(location)
    }

    // ── Agent access (MCP) ─────────────────────────────────────────

    /**
     * This shell's tabs and windows for the MCP window tools
     * ([se.soderbjorn.lunarbor.mcp.McpTools]): the same mutators the tab
     * strip, "+" menu and pane close buttons use, so an agent's changes
     * persist and repaint like the user's. Paths are vault-relative and
     * opened as vault links would be ([MainViewModel.navigateToLink]).
     *
     * Called once by `Main.kt`, which hands it to the MCP tools.
     */
    fun agentWorkspace(): se.soderbjorn.lunarbor.mcp.AgentWorkspace = object : se.soderbjorn.lunarbor.mcp.AgentWorkspace {
        override suspend fun tabs(): List<se.soderbjorn.lunarbor.mcp.AgentWorkspace.Tab> = layoutState.tabs.map { t ->
            val focused = if (t.id == layoutState.activeTabId) focusedPaneId() else lastFocusedPaneIdByTab[t.id]
            val panes = tabLayouts[t.id]?.floatingPanes.orEmpty().sortedBy { it.zIndex }
            se.soderbjorn.lunarbor.mcp.AgentWorkspace.Tab(
                id = t.id,
                title = t.title,
                isActive = t.id == layoutState.activeTabId,
                windows = panes.map { p ->
                    // A pane that has not loaded (another tab's) is where its
                    // stored location says: the outline's folder, then down
                    // its zoom path — not the outline's folder alone, which
                    // would scope a deep zoom by the outline it starts in.
                    val location = paneViewModels[p.id]?.currentLocationPath()
                        ?: paneLocations[p.id]?.let { stored ->
                            val rel = stored.fileRel
                            if (NoteRepository.isOutlineFile(rel)) {
                                documentRegistry.folderOfZoomPath(NoteRepository.folderOfOutline(rel), stored.zoomTitlePath)
                            } else {
                                rel
                            }
                        }
                    se.soderbjorn.lunarbor.mcp.AgentWorkspace.Window(
                        id = p.id,
                        location = location,
                        title = p.title ?: paneSidebarLabel(p.id),
                        isFocused = p.id == focused,
                    )
                },
            )
        }

        override suspend fun openWindow(tabId: String?, path: String): String {
            val tab = tabId ?: layoutState.activeTabId ?: return "!No tab is open."
            if (tabLayouts[tab] == null) return "!No tab $tab."
            if (layoutState.activeTabId != tab) {
                layoutState = layoutState.copy(activeTabId = tab)
                persistLayoutState()
            }
            val newId = addFloatingPane(tab) ?: return "!The window could not be opened."
            ensurePaneViewModel(newId)
            showPath(newId, path)
            return newId
        }

        override suspend fun showInWindow(windowId: String, path: String): String? {
            if (tabOfPane(windowId) == null) return "No window $windowId."
            ensurePaneViewModel(windowId)
            showPath(windowId, path)
            return null
        }

        override suspend fun closeWindow(windowId: String): String? {
            val tab = tabOfPane(windowId) ?: return "No window $windowId."
            closeFloatingPane(tab, windowId)
            return null
        }

        override suspend fun newTab(title: String?, path: String?): String {
            addTab()
            val tab = layoutState.activeTabId ?: return "!The tab could not be opened."
            if (!title.isNullOrBlank()) renameTab(tab, title)
            val pane = tabLayouts[tab]?.floatingPanes?.firstOrNull()?.id
            if (pane != null && path != null) {
                ensurePaneViewModel(pane)
                showPath(pane, path)
            }
            return tab
        }

        override suspend fun selectTab(tabId: String): String? {
            if (layoutState.tabs.none { it.id == tabId }) return "No tab $tabId."
            layoutState = layoutState.copy(activeTabId = tabId)
            persistLayoutState()
            return null
        }

        override suspend fun renameTab(tabId: String, title: String): String? {
            if (layoutState.tabs.none { it.id == tabId }) return "No tab $tabId."
            this@AppShell.renameTab(tabId, title)
            return null
        }

        override suspend fun closeTab(tabId: String): String? {
            if (layoutState.tabs.none { it.id == tabId }) return "No tab $tabId."
            if (layoutState.tabs.size <= 1) return "The last tab cannot be closed."
            // Close its windows one by one, as their close buttons would, so
            // each releases its document; the last one closes the tab.
            val panes = tabLayouts[tabId]?.floatingPanes?.map { it.id }.orEmpty()
            for (id in panes) closeFloatingPane(tabId, id)
            if (layoutState.tabs.any { it.id == tabId }) this@AppShell.closeTab(tabId)
            return null
        }
    }

    /** The tab holding the window [paneId], or `null`. */
    private fun tabOfPane(paneId: String): String? =
        tabLayouts.entries.firstOrNull { (_, layout) -> layout.floatingPanes.any { it.id == paneId } }?.key

    /**
     * Moves the pane [paneId] to the vault path [path] once its first
     * document has loaded: the root through [MainViewModel.navigateHome],
     * anything else as a vault link click.
     */
    private suspend fun showPath(paneId: String, path: String) {
        val vm = paneViewModels[paneId] ?: return
        vm.stateFlow.first { it.backingState?.isLoaded == true || it.backingState?.let { b -> b.isImageView || b.isHtmlView } == true }
        if (path.isEmpty()) vm.navigateHome() else vm.navigateToLink(se.soderbjorn.lunarbor.data.LunarborLink.rooted(path))
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
     * Moves [paneId] from [sourceTabId] into [destTabId], as Lunamux does:
     * the pane keeps its view-model, location and history, lands on top of
     * the destination's panes (any maximized one there is restored so the
     * arrival is visible) and becomes that tab's focused pane. The active
     * tab does not change, and moving a tab's last pane leaves that tab
     * empty (the "+" menu adds a pane to it again). Both tabs re-tile
     * through the toolkit when their preset is Auto.
     *
     * Called by [LunarborTabSource] from the pane `⋮` menu's
     * **Move to tab ▸** submenu. No-op when either tab is unknown, the two
     * are the same, or the pane is not in [sourceTabId].
     *
     * @param sourceTabId the tab the pane is in now.
     * @param paneId the pane to move.
     * @param destTabId the tab to move it to.
     */
    private fun movePaneToTab(sourceTabId: String, paneId: String, destTabId: String) {
        if (sourceTabId == destTabId) return
        val sourceLayout = tabLayouts[sourceTabId] ?: return
        val destLayout = tabLayouts[destTabId] ?: return
        val moved = sourceLayout.floatingPanes.firstOrNull { it.id == paneId } ?: return
        tabLayouts[sourceTabId] = sourceLayout.copy(
            floatingPanes = sourceLayout.floatingPanes.filterNot { it.id == paneId },
        )
        val topZ = destLayout.floatingPanes.maxOfOrNull { it.zIndex } ?: 0
        tabLayouts[destTabId] = destLayout.copy(
            floatingPanes = destLayout.floatingPanes.withNoneMaximized() +
                moved.copy(zIndex = topZ + 1, isMaximized = false, isMinimized = false),
        )
        // The pane it was opened from stays behind, so Auto layout no
        // longer ranks it next to that parent.
        parentByPane.remove(paneId)
        if (lastFocusedPaneIdByTab[sourceTabId] == paneId) lastFocusedPaneIdByTab.remove(sourceTabId)
        lastFocusedPaneIdByTab[destTabId] = paneId
        persistLayoutState()
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
        paneLocations.forget(paneId)
        paneEditors.remove(paneId)
        paneNavClusters.remove(paneId)
        chromeDrawnKeys.remove(paneId)
        starredModals.remove(paneId)?.dispose()
        insertLinkModals.keys.filter { it.startsWith("$paneId|") }.forEach { insertLinkModals.remove(it)?.close() }
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
        // re-tile on add/remove). Lunarbor only persists tab/pane identity
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
     * Moves the app to the vault at [newRoot] (App settings → Vault →
     * Change…).
     *
     * 1. Refuses while edits are pending, then flushes every document.
     * 2. Rebases each pane's location onto [newRoot] ([VaultRelocation]):
     *    panes showing something inside the new vault keep their place,
     *    the others close (a tab left without panes reopens at Home).
     * 3. Writes the pane locations and the layout, then hands [newRoot] to
     *    the Electron main process (`noteApi.setVault`), which persists it
     *    as `vaultPath` in `lunarbor.json` and reopens the window against
     *    it — every listing and document is then read from the new vault.
     *
     * Called by the App settings sidebar ([AppSettingsHandlers.switchVault]).
     *
     * @param newRoot Absolute path of the folder the user picked.
     * @return An error message to show, or `null` when the window is
     *   reopening (or the folder is already the vault).
     */
    private suspend fun switchVault(newRoot: String): String? {
        val bridge = js("globalThis.noteApi")
        if (bridge == null || js("typeof bridge.setVault !== 'function'") as Boolean) {
            return "Changing the vault needs the desktop app."
        }
        val oldRoot = documentRegistry.rootDirectory
        if (newRoot.trimEnd('/') == oldRoot.trimEnd('/')) return null
        if (documentRegistry.unsavedFilesFlow.value.isNotEmpty()) {
            return "Edits are still being saved — try again in a moment."
        }
        documentRegistry.flushAll()

        val kept = mutableMapOf<String, PaneBackingViewModel.FileHistoryEntry>()
        val closing = mutableListOf<Pair<String, String>>()
        for (tab in layoutState.tabs) {
            for (pane in tabLayouts[tab.id]?.floatingPanes.orEmpty()) {
                val vm = paneViewModels[pane.id]
                val location = vm?.currentLocation()
                    ?: paneLocations[pane.id]
                    ?: PaneBackingViewModel.FileHistoryEntry(documentRegistry.rootFileName)
                val moved = VaultRelocation.relocate(oldRoot, newRoot, location, vm?.currentLocationPath())
                if (moved != null) kept[pane.id] = moved else closing += tab.id to pane.id
            }
        }
        for ((tabId, paneId) in closing) closeFloatingPane(tabId, paneId)
        paneLocations.replaceAll(kept)
        persister.write(PersistKeys.LAYOUT, layoutState.toJsonString())
        // Closing panes released their documents; make sure those final
        // saves are on disk before the window goes away.
        documentRegistry.flushAll()

        val result = (bridge.setVault(newRoot) as kotlin.js.Promise<String>).await()
        return result.ifEmpty { null }
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
        /** Persister key of the fold memory ([loadFoldMemory]). */
        internal const val FOLD_MEMORY_KEY: String = "lunarborOpenFolders"

        /**
         * Persister key of the app's current privacy mode, per vault:
         * `{ "<vault root dir>": "<mode id>" }` ([loadPrivacyMode]).
         */
        private const val PRIVACY_MODE_KEY: String = "lunarborPrivacyMode"

        /**
         * Persister key of the daily template's node folder, per vault:
         * `{ "<vault root dir>": "<folder>" }` ([loadDailyTemplate], LBR-21).
         */
        private const val DAILY_TEMPLATE_KEY: String = "lunarborDailyTemplate"

        /** Debounce between a fold change and its write. */
        private const val FOLD_MEMORY_SAVE_DEBOUNCE_MS: Long = 500

        /** ⌘ glyph: the top-bar button that opens the command palette. */
        private const val ICON_COMMAND: String =
            "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
                "stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
                "<path d=\"M15 6v12a3 3 0 1 0 3-3H6a3 3 0 1 0 3 3V6a3 3 0 1 0-3 3h12a3 3 0 1 0-3-3\"/></svg>"

        /** DOM id of the 3D mode cube ([spaceTopbarAction]); the chrome CSS hides it while disabled. */
        private const val SPACE_TOPBAR_ID: String = "lunarbor-topbar-space"

        /** Cube glyph: the top-bar button that toggles 3D mode (Lunamux's `ICON_CUBE`). */
        private const val ICON_CUBE: String =
            "<svg class=\"lunarbor-space-cube\" viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
                "stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\">" +
                "<path d=\"M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z\"/>" +
                "<polyline points=\"3.27 6.96 12 12.01 20.73 6.96\"/><line x1=\"12\" y1=\"22.08\" x2=\"12\" y2=\"12\"/></svg>"

        /** Hotkey action id: toggle 3D mode ([installSpaceShortcuts]). */
        internal const val SPACE_TOGGLE_ACTION: String = "lunarbor.space.toggle"

        /** Hotkey action id: 3D mode's focused-window / all-windows switch ([installSpaceShortcuts]). */
        internal const val SPACE_SPLIT_ACTION: String = "lunarbor.space.split"

        /** Hotkey action id: the Today command, ⌘D ([installTodayShortcut]). */
        internal const val TODAY_ACTION: String = "lunarbor.today"

        /** Hotkey action id: 3D mode's next shape — Pages, Crown, Cone, Galaxy (⌃⌘2). */
        internal const val SPACE_SHAPE_ACTION: String = "lunarbor.space.shape"

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
         * [PaneAction] has no native `disabled` flag, so lunarbor tags
         * the rendered button itself and the stylesheet (injected by
         * [ensureLunarborChromeStyles]) dims it and disables pointer
         * events. Used by [paneNavCluster] to keep Back / Forward in
         * fixed positions for muscle memory while showing whether
         * they're currently actionable, and by [buildPaneActions] for an
         * inert Style button.
         */
        private const val DISABLED_CLASS: String = "lunarbor-pane-action-disabled"

        /** Left-chevron glyph for the header's Back button. */
        private const val ICON_BACK: String =
            "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
                "stroke=\"currentColor\" stroke-width=\"2.2\" stroke-linecap=\"round\" " +
                "stroke-linejoin=\"round\">" +
                "<polyline points=\"15 18 9 12 15 6\"/></svg>"

        /** Right-chevron glyph for the header's Forward button. */
        private const val ICON_FORWARD: String =
            "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
                "stroke=\"currentColor\" stroke-width=\"2.2\" stroke-linecap=\"round\" " +
                "stroke-linejoin=\"round\">" +
                "<polyline points=\"9 18 15 12 9 6\"/></svg>"

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

/**
 * Inline SVG for the "+" menu's "New window" row: a window with a title
 * bar and a `+`.
 */
private const val ICON_NEW_WINDOW: String =
    "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\" aria-hidden=\"true\">" +
        "<rect x=\"3\" y=\"4\" width=\"18\" height=\"16\" rx=\"2\"/>" +
        "<line x1=\"3\" y1=\"9\" x2=\"21\" y2=\"9\"/>" +
        "<line x1=\"12\" y1=\"12\" x2=\"12\" y2=\"18\"/>" +
        "<line x1=\"9\" y1=\"15\" x2=\"15\" y2=\"15\"/></svg>"

/**
 * Opens the system file chooser for one Markdown file and hands its text
 * to [onPicked]; nothing happens when the chooser is cancelled. A
 * throwaway `<input type="file">` — Electron shows it as the native open
 * dialog, so no main-process IPC is needed, and the file may live
 * anywhere on disk.
 *
 * Called by the "Insert Markdown file as block…" palette command.
 *
 * @param onPicked Receives the file's raw text (UTF-8).
 */
private fun pickMarkdownFileText(onPicked: (String) -> Unit) {
    val input = document.createElement("input").asDynamic()
    input.type = "file"
    input.accept = ".md,.markdown,.mdown,.txt,text/markdown,text/plain"
    input.style.display = "none"
    input.addEventListener("change", { _: Event ->
        val file = input.files?.item(0)
        input.remove()
        if (file != null) {
            (file.text() as kotlin.js.Promise<String>).then { text -> onPicked(text) }
        }
    })
    input.addEventListener("cancel", { _: Event -> input.remove() })
    document.body?.appendChild(input as Node)
    input.click()
}

/**
 * Today's date on the user's clock (local time, not UTC), for the Today
 * command ([AppShell] `goToToday`).
 */
internal fun localToday(): CalendarDate {
    val now: dynamic = js("new Date()")
    return CalendarDate(now.getFullYear() as Int, (now.getMonth() as Int) + 1, now.getDate() as Int)
}
