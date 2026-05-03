/*
 * AppShell.kt (jsMain)
 * --------------------
 * Top-level shell for the notegrow web app, built on darkness-toolkit.
 *
 * Composes — via a single `mountAppFrame(...)` call — every shell primitive
 * the toolkit ships:
 *
 *  - `TopBar` with leading title, a full `TabBar` (add/close/rename/drag),
 *    and a trailing slot holding a palette button + appearance toggle.
 *  - `LayoutRenderer` mounted into the AppFrame's main slot, painting the
 *    active tab's `PaneTree` with full pane chrome (close + inline rename
 *    + cross-tab drag, plus a kebab menu for split/expand/restore/close).
 *  - A right-side `ThemeManager` host wired through the toolkit's
 *    `DefaultThemeManagerHost` and a `DefaultThemeManagerState`.
 *  - Boot-time + on-mutation persistence of `LayoutState` and `UiSettings`
 *    via the Electron preload's `darknessApi` IPC bridge.
 *
 * Theme persistence is loaded from `globalThis.__darknessSettings`;
 * layout persistence is loaded from `globalThis.__darknessLayoutState`.
 * Both are populated by the Electron preload at boot. When running in a
 * plain browser (no `darknessApi` global) the shell falls back to
 * defaults and persistence is a no-op.
 *
 * commonMain rules: this file is jsMain only (touches the DOM).
 */

package se.soderbjorn.notegrow.main

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import se.soderbjorn.darkness.core.Appearance
import se.soderbjorn.darkness.core.ColorScheme
import se.soderbjorn.darkness.core.DEFAULT_THEME_NAME
import se.soderbjorn.darkness.core.UiSettings
import se.soderbjorn.darkness.core.defaultThemes
import se.soderbjorn.darkness.core.recommendedColorSchemes
import se.soderbjorn.darkness.core.resolve
import se.soderbjorn.darkness.store.LayoutState
import se.soderbjorn.darkness.store.SidebarState
import se.soderbjorn.darkness.store.TabState
import se.soderbjorn.darkness.web.applyColorScheme
import se.soderbjorn.darkness.web.applyCssVars
import se.soderbjorn.darkness.web.injectDarknessToolkitStyles
import se.soderbjorn.darkness.web.isDarkActive
import se.soderbjorn.darkness.web.layout.FloatingPaneSpec
import se.soderbjorn.darkness.web.layout.LayoutPreset
import se.soderbjorn.darkness.web.layout.LayoutRenderer
import se.soderbjorn.darkness.web.layout.PaneCallbacks
import se.soderbjorn.darkness.web.layout.PaneHeaderSpec
import se.soderbjorn.darkness.web.layout.PaneLayout
import se.soderbjorn.darkness.web.layout.PaneActions
import se.soderbjorn.darkness.web.layout.PaneAction
import se.soderbjorn.darkness.web.layout.PaneTitleSegment
import se.soderbjorn.darkness.web.layout.withNoneMaximized
import se.soderbjorn.darkness.web.shell.AppFrameSpec
import se.soderbjorn.darkness.web.shell.TabBarCallbacks
import se.soderbjorn.darkness.web.shell.TabBarSpec
import se.soderbjorn.darkness.web.shell.TabSpec
import se.soderbjorn.darkness.web.shell.TopBarSpec
import se.soderbjorn.darkness.web.shell.buildLayoutPresetButton
import se.soderbjorn.darkness.web.shell.buildNewWindowButton
import se.soderbjorn.darkness.web.shell.buildThemeManagerButton
import se.soderbjorn.darkness.web.shell.mountAppFrame
import se.soderbjorn.darkness.web.shell.renderTopBar
import se.soderbjorn.darkness.web.themeeditor.DefaultThemeManagerHost
import se.soderbjorn.darkness.web.themeeditor.DefaultThemeManagerState
import se.soderbjorn.darkness.web.themeeditor.buildThemeManagerSidebar
import se.soderbjorn.darkness.web.themeeditor.isThemeManagerSidebarOpen
import se.soderbjorn.darkness.web.themeeditor.refreshThemeManager
import se.soderbjorn.darkness.web.themeeditor.toggleThemeManagerSidebar
import se.soderbjorn.darkness.web.toCssAliasMap
import se.soderbjorn.darkness.web.toCssVarMap

/**
 * Top-level shell that wires the toolkit windowing system around the
 * notegrow editor. One instance per app startup; instantiated in
 * [se.soderbjorn.notegrow.Main].
 *
 * @param viewModel platform view-model passed through to the embedded
 *   [MainScreen] inside the editor pane.
 * @param scope     coroutine scope shared with the embedded [MainScreen]
 *   for its paint loop.
 */
class AppShell(
    private val viewModel: MainViewModel,
    private val scope: CoroutineScope,
    /**
     * Singleton document VM shared across every pane. Each pane gets its
     * own `DocumentViewBackingViewModel` (and matching `MainViewModel` +
     * `MainScreen`) bound to this same backing — so all panes mutate one
     * shared document but maintain independent zoom navigation, selection,
     * and caret. Pane-specific view-models live in [paneEditors].
     */
    private val documentBackingViewModel: se.soderbjorn.notegrow.main.DocumentBackingViewModel,
) {

    /** Stable id for the editor pane that hosts the live `MainScreen`. */
    private val editorPaneId = "pane-editor"

    /** Stable id for the only tab that is allowed to host the editor pane. */
    private val defaultTabId = "tab-default"

    /** Mounted root element — kept for re-renders triggered by state changes. */
    private var rootEl: HTMLElement? = null

    /** Container holding the active tab's `LayoutRenderer` output. */
    private var paneHost: HTMLElement? = null

    /** Active layout renderer, recreated on every tab switch. */
    private var renderer: LayoutRenderer? = null

    /**
     * Per-pane editor instances. Each entry pairs a `MainViewModel` (with
     * its own `DocumentViewBackingViewModel` so zoom + selection don't
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
     * Per-pane [MainViewModel] handles, keyed by leaf pane id. Maintained
     * alongside [paneEditors] so the toolkit-rendered pane header (built by
     * [buildPaneHeaderSpec], where the [MainScreen] is not directly
     * accessible) can wire up/home action buttons to the pane's own zoom
     * state without crossing through the screen.
     *
     * Populated lazily on first render of each pane in [renderPaneContent];
     * an entry is missing until the pane has rendered at least once, so
     * action handlers that look up here must tolerate `null`.
     */
    private val paneViewModels: MutableMap<String, MainViewModel> = mutableMapOf()

    /** Latest UI settings; mirrors disk + Electron-pushed updates. */
    private var uiSettings: UiSettings = UiSettings.defaults()

    /**
     * Tab ids whose sidebar section the user has manually collapsed.
     * Independent of which tab is active — termtastic's pattern. Clicking
     * the chevron toggles membership.
     */
    private val collapsedTabs: MutableSet<String> = mutableSetOf()

    /**
     * Last UiSettings JSON we wrote to disk, used to suppress the echo
     * Electron sends back through `onUiSettingsChanged` for our own
     * writes. Without this, picking a theme paints correctly once, then
     * the echo re-seeds [themeState] and triggers a second paint that can
     * land on subtly different colours (e.g. when the persisted scheme
     * name resolves through `recommendedColorSchemes` only and a custom
     * scheme can't round-trip).
     */
    private var lastWrittenUiSettingsJson: String? = null

    /**
     * Temporary flag — when `true`, notegrow keeps its UiSettings in a
     * notegrow-private localStorage slot instead of going through the
     * shared `darknessApi` UI-settings IPC (which Electron persists to a
     * file shared with termtastic). The shared-file path currently
     * collapses per-section overrides on a write→watch round-trip when
     * the apps disagree on resolved scheme names; until that's properly
     * fixed at the storage layer, isolating notegrow's theme keeps it
     * stable. Set to `false` once the shared store round-trips cleanly.
     */
    private val perAppThemeSettings: Boolean = true

    /** localStorage key used when [perAppThemeSettings] is `true`. */
    private val perAppThemeStorageKey: String = "notegrow.uiSettings.v1"

    /**
     * Mutable theme-manager state holder. Wraps `uiSettings` plus the
     * larger custom-theme bookkeeping the manager needs.
     */
    private val themeState: DefaultThemeManagerState = DefaultThemeManagerState()

    /** Toolkit-supplied default host bound to [themeState]. */
    private val themeHost: DefaultThemeManagerHost by lazy {
        object : DefaultThemeManagerHost(themeState, onChange = { onThemeStateChange() }) {}
    }

    /** Latest layout state; mirrored to disk via Electron IPC. */
    private var layoutState: LayoutState = LayoutState.defaults()

    /** Per-tab pane layout (tree + expanded leaf id). Keyed by tab id. */
    private val tabLayouts: MutableMap<String, PaneLayout> = mutableMapOf()

    /** Trailing toolbar reference — re-rendered when appearance cycles. */
    private var appearanceButton: HTMLElement? = null

    /** Boots the shell into [root]. Safe to call once. */
    fun render(root: HTMLElement) {
        injectDarknessToolkitStyles()
        ensureNotegrowChromeStyles()
        rootEl = root

        // Load persisted state. Both globals are populated by the Electron
        // preload at boot via `additionalArguments`; absence means "first
        // launch" and we fall back to library defaults.
        uiSettings = loadInitialUiSettings()
        layoutState = loadInitialLayoutState()
        // Seed the toolkit's left-sidebar controller from the persisted
        // visibility so the slide-in animation only triggers on user
        // toggles, not on boot.
        se.soderbjorn.darkness.web.shell.leftSidebarController.setInitial(
            open = layoutState.leftSidebar.visible,
            widthPx = layoutState.leftSidebar.widthPx,
        )
        seedThemeState(uiSettings)
        applyTheme(uiSettings)

        // The host (#app) just needs viewport sizing; AppFrame handles the
        // column/row composition.
        root.style.height = "100vh"
        root.style.margin = "0"

        rebuildShell()
        subscribeToExternalThemeChanges()
    }

    // ── Mount / re-mount ────────────────────────────────────────────

    /**
     * Tears down and rebuilds the AppFrame. Called on every change that
     * affects shell shape (sidebar visibility/width, active tab swap, tab
     * list mutation). Pane-internal changes that only need a renderer
     * repaint go through [rerenderActivePane] instead.
     */
    private fun rebuildShell() {
        val root = rootEl ?: return
        val topBar = renderTopBar(
            TopBarSpec(
                leadingContent = buildLeadingTitleAndToggle(),
                tabBar = buildTabBarSpec(),
                trailingContent = buildTrailingActions(),
                isResizable = true,
                minHeightPx = 0,
                maxHeightPx = 80,
                onResize = { newHeight ->
                    // Drag to 0 effectively hides the topbar; we don't
                    // persist height yet (no schema field), but the live
                    // drag still feels right.
                    if (newHeight == 0) {
                        // Re-render so topbar comes back via toggle button
                        // path next time the user wants it.
                    }
                },
            )
        )

        val main = document.createElement("div") as HTMLElement
        main.style.apply {
            display = "flex"
            flexDirection = "column"
            flex = "1 1 auto"
            setProperty("min-height", "0")
        }
        paneHost = main

        val leftSidebarEl: HTMLElement? = if (
            se.soderbjorn.darkness.web.shell.leftSidebarController.isOpen
        ) {
            buildLeftSidebarElement()
        } else null

        val rightSidebarEl: HTMLElement? = if (isThemeManagerSidebarOpen()) {
            buildThemeManagerSidebar(
                host = themeHost,
                initialWidthPx = layoutState.rightSidebar.widthPx,
                onResize = { newWidth ->
                    layoutState = layoutState.copy(
                        rightSidebar = layoutState.rightSidebar.copy(widthPx = newWidth),
                    )
                    persistLayoutState()
                },
            )
        } else null

        mountAppFrame(
            root,
            AppFrameSpec(
                topBar = topBar,
                leftSidebar = leftSidebarEl,
                main = main,
                rightSidebar = rightSidebarEl,
                bottomBar = buildBottomBar(),
            ),
        )

        // Mount the active tab's pane layout into `main`.
        mountActivePane()

        // Re-apply the theme now that the freshly-mounted DOM contains
        // the section containers (.dt-topbar, .dt-sidebar, .dt-pane, …).
        // Without this, the toolkit's per-section paint pass has nothing
        // to target on first render and the picked Theme's distinct
        // schemes for chrome / sidebar / panes never reach the screen.
        applyTheme(uiSettings)
    }

    /**
     * Builds the left sidebar SHELL (the `<aside>`, the resize handle, the
     * boot slide-in animation). Inner content is built separately via
     * [buildLeftSidebarSections] so [softSwitchTab] can refresh just the
     * sections in place without re-mounting the sidebar (which would
     * re-trigger the slide-in animation).
     */
    private fun buildLeftSidebarElement(): HTMLElement {
        val contentWrap = document.createElement("div") as HTMLElement
        contentWrap.appendChild(buildLeftSidebarSections())
        return se.soderbjorn.darkness.web.shell.leftSidebarController.mountSidebar(
            spec = se.soderbjorn.darkness.web.shell.SidebarSpec(
                content = contentWrap,
                visible = true,
                isResizable = true,
                minWidthPx = 160,
                maxWidthPx = 480,
                onResize = { newWidth ->
                    layoutState = layoutState.copy(
                        leftSidebar = layoutState.leftSidebar.copy(widthPx = newWidth),
                    )
                    persistLayoutState()
                },
            ),
            onLeft = true,
        )
    }

    /**
     * Builds the inner sidebar sections — one collapsible section per
     * tab, with that tab's panes (every pane is a float in the floats-only
     * model; minimised floats render with a muted "(hidden)" style) as
     * section items. Click a pane row to switch tabs (if needed) and
     * focus it.
     *
     * Termtastic-style structure: the active tab's section gets the
     * `.active-tab` class so the toolkit CSS paints the active rectangle
     * background; pane rows in the active tab matching the focused pane
     * id get the `dt-active` class via [SidebarRow.isActive].
     *
     * Returns a fresh `<div>` containing the sections — caller mounts
     * it inside the sidebar's content wrap.
     */
    private fun buildLeftSidebarSections(): HTMLElement {
        val container = document.createElement("div") as HTMLElement
        for (tab in layoutState.tabs) {
            if (tab.isHidden) continue
            // `isHiddenFromSidebar` is independent of `isHidden`: a tab can
            // stay in the strip while being hidden from this tree so the
            // sidebar can be decluttered without losing tab access.
            if (tab.isHiddenFromSidebar) continue
            val isActiveTab = tab.id == layoutState.activeTabId
            val layout = tabLayouts[tab.id]

            val rows = mutableListOf<HTMLElement>()
            if (layout != null) {
                fun sidebarLabelFor(paneId: String, fallback: String?): String {
                    val fileLabel = activeFileDisplayName(paneId)
                    val path = zoomPathStringForPane(paneId)
                    val own = fallback?.ifBlank { null }?.takeUnless { it == "Untitled" }
                    val combined = if (path != null) "$fileLabel / $path" else fileLabel
                    return if (own != null) "$own / $combined" else combined
                }
                for (float in layout.floatingPanes) {
                    rows.add(buildPaneSidebarRow(
                        tabId = tab.id,
                        paneId = float.id,
                        label = sidebarLabelFor(float.id, float.title),
                        isFocused = isActiveTab && rendererFocusedPaneId() == float.id,
                        isMinimised = float.isMinimized,
                    ))
                }
            }

            val isOpen = tab.id !in collapsedTabs
            val section = se.soderbjorn.darkness.web.shell.renderSidebarSection(
                se.soderbjorn.darkness.web.shell.SidebarSectionSpec(
                    title = tab.title.ifBlank { "Untitled" },
                    isOpen = isOpen,
                    items = rows,
                    onToggle = {
                        // Match termtastic: chevron click toggles the
                        // section's collapsed state independently of which
                        // tab is active. Activating an inactive tab is a
                        // separate concern and happens via clicking a row.
                        if (tab.id in collapsedTabs) collapsedTabs.remove(tab.id)
                        else collapsedTabs.add(tab.id)
                        refreshLeftSidebarSections()
                    },
                )
            )
            section.setAttribute("data-tab", tab.id)
            if (isActiveTab) section.classList.add("active-tab")
            container.appendChild(section)
        }
        return container
    }

    /**
     * Build one `.dt-sidebar-row` for a pane (either a split-tree leaf
     * or a floating overlay). Clicking the row activates the row's tab
     * (if needed) and focuses the pane.
     *
     * @param tabId the tab the pane lives in.
     * @param paneId the pane's id.
     * @param label visible row label.
     * @param isFocused mark the row as the currently-focused pane.
     * @param isMinimised render in a muted style (italics + dim) so
     *   minimised floats read distinctly. Click on a minimised row
     *   un-minimises and brings the float back to front.
     */
    private fun buildPaneSidebarRow(
        tabId: String,
        paneId: String,
        label: String,
        isFocused: Boolean,
        isMinimised: Boolean,
    ): HTMLElement {
        val displayLabel = if (isMinimised) "$label (hidden)" else label
        return se.soderbjorn.darkness.web.shell.SidebarRow(
            label = displayLabel,
            iconHtml = ICON_NOTE,
            isActive = isFocused,
            handler = {
                if (tabId != layoutState.activeTabId) softSwitchTab(tabId)
                if (isMinimised) {
                    // Un-minimise + bring to front + focus.
                    setFloatingPaneMinimized(tabId, paneId, false)
                    bringFloatingPaneToFront(tabId, paneId)
                }
                renderer?.focusPane(paneId)
            },
            // Match the pane chrome's RTL clipping so the deepest segment of
            // long zoom paths stays visible on the right end of the row.
            labelRtl = true,
        )
    }

    /** The renderer doesn't expose its focused id; track via callback. */
    private var lastFocusedPaneIdByTab: MutableMap<String, String> = mutableMapOf()
    private fun rendererFocusedPaneId(): String? =
        layoutState.activeTabId?.let { lastFocusedPaneIdByTab[it] }

    /**
     * Re-renders the left sidebar's inner sections in place — same fast
     * path [softSwitchTab] uses, but without touching the topbar or the
     * pane host. Used after focus / minimise / float changes that the
     * sidebar tree needs to reflect.
     */
    private fun refreshLeftSidebarSections() {
        val root = rootEl ?: return
        val leftContent = root
            .querySelector(".dt-app-frame-sidebar-left .dt-sidebar-content")
            as? HTMLElement
            ?: return
        while (leftContent.firstChild != null) {
            leftContent.removeChild(leftContent.firstChild!!)
        }
        leftContent.appendChild(buildLeftSidebarSections())
    }

    /**
     * Build the bottom status bar — leading slot shows nothing yet
     * (notegrow has no usage telemetry to report); trailing slot shows
     * just the app name.
     */
    private fun buildBottomBar(): HTMLElement {
        val trailing = document.createElement("div") as HTMLElement
        trailing.style.apply {
            display = "flex"
            alignItems = "center"
        }
        val name = document.createElement("span") as HTMLElement
        name.textContent = "Notegrow"
        trailing.appendChild(name)
        return se.soderbjorn.darkness.web.shell.renderBottomBar(
            se.soderbjorn.darkness.web.shell.BottomBarSpec(
                trailingContent = trailing,
                isResizable = true,
                minHeightPx = 0,
                maxHeightPx = 80,
            )
        )
    }

    /** Mounts a fresh [LayoutRenderer] over [paneHost] for the active tab. */
    private fun mountActivePane() {
        val host = paneHost ?: return
        // Wipe any prior children — switching tabs re-creates the pane host
        // body so MainScreen's installed listeners don't double-fire.
        while (host.firstChild != null) host.removeChild(host.firstChild!!)

        val activeId = layoutState.activeTabId ?: return
        val layout = tabLayouts[activeId] ?: PaneLayout(
            floatingPanes = listOf(seedPane(activeId)),
        ).also { tabLayouts[activeId] = it }

        // No panes — show the toolkit's empty-tab placeholder. The button
        // re-enters the same spawn flow the topbar "+" uses, so the empty
        // state is recoverable in one click. Mirrors termtastic.
        if (layout.floatingPanes.isEmpty()) {
            renderer = null
            host.appendChild(
                se.soderbjorn.darkness.web.layout.renderEmptyTabPlaceholder(
                    onAdd = { addFloatingPane(activeId) },
                )
            )
            return
        }

        val callbacks = PaneCallbacks(
            contentRenderer = { id, slot -> renderPaneContent(id, slot) },
            paneHeader = { id, title -> buildPaneHeaderSpec(id, title, activeId) },
            onPaneFocused = { paneId ->
                lastFocusedPaneIdByTab[activeId] = paneId
                refreshLeftSidebarSections()
            },
            onFloatingMoved = { id, xPct, yPct ->
                val cur = tabLayouts[activeId] ?: return@PaneCallbacks
                tabLayouts[activeId] = cur.copy(
                    floatingPanes = cur.floatingPanes.map { f ->
                        if (f.id == id) f.copy(xPct = xPct, yPct = yPct) else f
                    },
                )
                persistLayoutState()
            },
            onFloatingResized = { id, w, h ->
                val cur = tabLayouts[activeId] ?: return@PaneCallbacks
                tabLayouts[activeId] = cur.copy(
                    floatingPanes = cur.floatingPanes.map { f ->
                        if (f.id == id) f.copy(widthPct = w, heightPct = h) else f
                    },
                )
                persistLayoutState()
            },
            onFloatingFocused = { id -> bringFloatingPaneToFront(activeId, id) },
            onFloatingClosed = { id -> closeFloatingPane(activeId, id) },
            onFloatingMaximizeToggled = { id -> toggleFloatingPaneMaximized(activeId, id) },
            // Intentionally no `onFloatingMinimized` wiring — the maximise
            // button doubles as a restore toggle, so a separate minimise
            // affordance in the pane chrome would only confuse the user.
            // [setFloatingPaneMinimized] still exists to un-minimise legacy
            // panes whose persisted layout has `isMinimized = true`.
        )
        renderer = LayoutRenderer(host, callbacks).also { it.render(layout) }
    }

    /**
     * Builds the seed pane every fresh tab gets: a single full-bleed
     * (maximized) float so the editor fills the canvas exactly like every
     * subsequent pane the user creates via `+`. No two pane types — every
     * pane in notegrow is a [FloatingPaneSpec], matching termtastic.
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

    /**
     * Re-renders the active tab's pane tree without rebuilding the
     * surrounding AppFrame. Fast path for splits, closes, retitles,
     * expand/restore.
     */
    private fun rerenderActivePane() {
        val activeId = layoutState.activeTabId ?: return
        val layout = tabLayouts[activeId] ?: return
        // Transitioning into / out of the empty-tab state requires swapping
        // between the toolkit's [LayoutRenderer] and the [renderEmptyTabPlaceholder]
        // element. The mount path covers both branches, so route the
        // first / last-pane transitions through it; otherwise the placeholder
        // would be left attached behind a re-rendered (but un-mounted) layout.
        if (layout.floatingPanes.isEmpty() || renderer == null) {
            mountActivePane()
            applyTheme(uiSettings)
            return
        }
        renderer?.render(layout)
        // The renderer rebuilds every `.dt-pane` element from scratch, so
        // any per-section paint we previously stamped on the windows
        // section is gone. Re-apply the theme so the new pane elements
        // pick up the active Theme's `windows` scheme.
        applyTheme(uiSettings)
    }

    /**
     * Activates [tabId] without tearing down the AppFrame. Updates the
     * persisted active id, re-mounts the active tab's pane host, and
     * re-renders the topbar + left sidebar in place so their `.dt-selected`
     * / `.active-tab` classes refresh — but leaves the right sidebar
     * (theme editor) and the bottom bar untouched.
     *
     * Replaces the previous `rebuildShell()` call on tab activation. The
     * full rebuild blew away every chrome element, which made the sidebar
     * controllers re-run their slide-in animations on every tab switch
     * (visible as a parallel left/right slide glitch) and unmounted the
     * theme editor whenever the user picked a tab while it was open.
     */
    private fun softSwitchTab(tabId: String) {
        if (layoutState.activeTabId == tabId) return
        layoutState = layoutState.copy(activeTabId = tabId)
        persistLayoutState()

        val root = rootEl
        if (root != null) {
            // Topbar swap: rebuild the topbar element with the new active
            // id and replace the slot's children. The slot keeps its
            // attachment so `.dt-app-frame-body` doesn't re-flow.
            val topSlot = root.querySelector(".dt-app-frame-topbar") as? HTMLElement
            if (topSlot != null) {
                while (topSlot.firstChild != null) topSlot.removeChild(topSlot.firstChild!!)
                topSlot.appendChild(
                    renderTopBar(
                        TopBarSpec(
                            leadingContent = buildLeadingTitleAndToggle(),
                            tabBar = buildTabBarSpec(),
                            trailingContent = buildTrailingActions(),
                            isResizable = true,
                            minHeightPx = 0,
                            maxHeightPx = 80,
                            onResize = { _ -> },
                        )
                    )
                )
            }
            // Left sidebar refresh in place — only its content (the
            // tabs / panes tree) changes; the sidebar shell stays mounted
            // so SidebarController doesn't see a re-mount + slide-in.
            val leftContent = root
                .querySelector(".dt-app-frame-sidebar-left .dt-sidebar-content")
                as? HTMLElement
            if (leftContent != null) {
                while (leftContent.firstChild != null) {
                    leftContent.removeChild(leftContent.firstChild!!)
                }
                leftContent.appendChild(buildLeftSidebarSections())
            }
        }

        // Swap the editor content for the newly-active tab.
        mountActivePane()
        // Re-apply theme so the freshly-mounted topbar + left sidebar +
        // pane elements pick up the active Theme's per-section schemes.
        applyTheme(uiSettings)
    }

    // ── TabBar wiring ───────────────────────────────────────────────

    private fun buildTabBarSpec(): TabBarSpec {
        return TabBarSpec(
            tabs = layoutState.tabs.map { t ->
                TabSpec(
                    id = t.id,
                    label = t.title.ifBlank { "Untitled" },
                    // Per-tab × in the strip is intentionally off — Close
                    // lives in the `…` overflow menu so the strip stays
                    // chromeless and matches termtastic.
                    isClosable = false,
                    isDraggable = true,
                    isRenamable = true,
                    isHidden = t.isHidden,
                    isHiddenFromSidebar = t.isHiddenFromSidebar,
                )
            },
            activeTabId = layoutState.activeTabId,
            // The `+` button is omitted: the `…` overflow menu has a "New
            // tab" entry, so showing both is redundant chrome.
            showAddButton = false,
            showOverflowMenu = true,
            callbacks = TabBarCallbacks(
                onSelect = { id ->
                    // Activating a hidden tab (only reachable via the
                    // overflow menu's "Unlisted tabs" section) goes through
                    // the full rebuild so the freshly-active tab's panes
                    // mount cleanly even though no `.dt-selected` strip
                    // entry exists for it. Visible tabs keep the soft path
                    // so the sidebar slide-in / theme editor stay intact.
                    val target = layoutState.tabs.firstOrNull { it.id == id }
                    if (target?.isHidden == true) {
                        layoutState = layoutState.copy(activeTabId = id)
                        persistLayoutState()
                        rebuildShell()
                    } else {
                        softSwitchTab(id)
                    }
                },
                onClose = { id -> closeTab(id) },
                onAdd = { addTab() },
                onReorder = { sourceId, targetId, before -> reorderTab(sourceId, targetId, before) },
                onRename = { id, newLabel -> renameTab(id, newLabel) },
                onSetHidden = { id, hidden -> setTabHidden(id, hidden) },
                onSetHiddenFromSidebar = { id, hidden -> setTabHiddenFromSidebar(id, hidden) },
                onPaneDroppedOnTab = { sourcePaneId, destTabId ->
                    movePaneToTab(sourcePaneId, destTabId)
                },
            ),
        )
    }

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
        rebuildShell()
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
        rebuildShell()
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
        rebuildShell()
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
        rebuildShell()
    }

    private fun renameTab(id: String, newLabel: String) {
        val trimmed = newLabel.trim().ifBlank { "Untitled" }
        val newTabs = layoutState.tabs.map { t ->
            if (t.id == id) t.copy(title = trimmed) else t
        }
        layoutState = layoutState.copy(tabs = newTabs)
        persistLayoutState()
        rebuildShell()
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
        rebuildShell()
    }

    // ── Pane chrome ─────────────────────────────────────────────────

    private fun buildPaneHeaderSpec(
        paneId: String,
        paneTitle: String?,
        tabId: String,
    ): PaneHeaderSpec {
        // Make sure the pane has a backing view-model BEFORE we read its
        // zoom path. Without this, on the very first paneHeader call for
        // a freshly-added pane (where renderPaneContent has not yet run),
        // `zoomPathStringForPane` returns null and the chrome falls back
        // to "Untitled" + no path. Pre-creation is cheap (an empty VM)
        // and idempotent — `ensurePaneViewModel` is `getOrPut`-style.
        ensurePaneViewModel(paneId)

        // Pane title shows the current zoom path (root / outer / current)
        // when the user has zoomed into a bullet; otherwise the pane's own
        // title. RTL alignment lets long paths clip from the LEFT so the
        // deepest segment — usually the most informative — stays visible
        // on the right end of the header.
        val pathTitle = paneTitleString(paneId, paneTitle)

        // Floats-only model: every pane is a float. The toolkit's
        // `buildFloatingPane` appends its own min/max/close window-control
        // strip, so the host only contributes navigation actions
        // (back/forward + up/home + separator).
        val actions = buildPaneNavActions(paneId)

        // When the pane is zoomed, surface the breadcrumb as clickable
        // segments so each ancestor jumps directly to that depth. Leaf
        // segment carries no `onClick` — clicks on the leaf are no-ops
        // because the user is already at that zoom level. Plain-mode
        // (joined-string) title is kept for the unzoomed/no-path case
        // so existing tooltip + RTL truncation behaviour still applies.
        val titleSegments = paneZoomTitleSegments(paneId)

        return PaneHeaderSpec(
            title = pathTitle,
            // RTL truncation is only meaningful for the plain-string
            // path. Breadcrumb mode does its own leading-segment
            // collapse, so don't double-apply.
            titleAlignRight = titleSegments.isEmpty(),
            titleSegments = titleSegments,
            // Same note glyph the sidebar uses, so the pane chrome and the
            // sidebar row read as "the same thing". Doubles as the
            // cross-tab drag handle (`isDraggable = true`) — drop it on a
            // tab in the strip to move the pane.
            leadingIcon = ICON_NOTE,
            actions = actions,
            // Rename is wired in plain-title mode only; the toolkit
            // ignores `onRename` when `titleSegments` is non-empty
            // (notegrow renames bullets via the editor body, not the
            // pane chrome). Kept here so the unzoomed pane title — which
            // shows the pane's own custom name when set — stays
            // editable via the toolkit's hover-arm gesture.
            onRename = { newTitle -> renamePane(tabId, paneId, newTitle) },
            isDraggable = true,
        )
    }

    /**
     * Builds the breadcrumb segment list for [paneId]'s pane chrome.
     *
     * Returns an empty list when the pane is not zoomed (or the document
     * hasn't loaded yet) so the caller falls back to plain-string title
     * rendering. When zoomed, returns segments in this order:
     *   1. A leading "Root" segment that clears the zoom on click.
     *   2. One segment per ancestor (outer-to-inner), each navigating
     *      via `zoomTo(ancestor.lineId)`.
     *   3. The current zoom target as a leaf segment with no click
     *      handler (the user is already at that depth).
     */
    private fun paneZoomTitleSegments(paneId: String): List<PaneTitleSegment> {
        val vm = paneViewModels[paneId] ?: return emptyList()
        val state = vm.stateFlow.value
        val backing = state.backingState ?: return emptyList()
        val zoom = vm.zoomInfo(backing) ?: return emptyList()
        val ancestors = vm.bulletAncestors(backing)
        if (ancestors.isEmpty() && zoom.titleText.isBlank()) return emptyList()
        val segments = mutableListOf<PaneTitleSegment>()
        // Leading segment is the active file's display name. Click clears
        // the zoom (back to the file's top), matching the old "Root"
        // behaviour on Root.md but generalising to any file.
        segments += PaneTitleSegment(
            label = activeFileDisplayName(paneId),
            onClick = { vm.zoomTo(null) },
        )
        for (ancestor in ancestors) {
            val label = ancestor.titleText.ifBlank { "(untitled)" }
            segments += PaneTitleSegment(
                label = label,
                onClick = { vm.zoomTo(ancestor.lineId) },
            )
        }
        segments += PaneTitleSegment(
            label = zoom.titleText.ifBlank { "(untitled)" },
            onClick = null,
        )
        return segments
    }

    /**
     * Lazily creates the pane's [MainViewModel] + [DocumentViewBackingViewModel]
     * the first time it's needed. Used by both [renderPaneContent] (when
     * mounting the editor DOM) and [buildPaneHeaderSpec] (when the chrome
     * needs to read the pane's zoom path before content has mounted).
     *
     * Also installs the per-pane zoom-path collector that triggers a
     * chrome + sidebar refresh on every zoom transition.
     */
    private fun ensurePaneViewModel(paneId: String) {
        if (paneId in paneViewModels) return
        val docView = se.soderbjorn.notegrow.main.DocumentViewBackingViewModel(
            documentBackingViewModel,
            scope,
        )
        val paneVm = se.soderbjorn.notegrow.main.MainViewModel(scope, docView)
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
                        backing?.documentState?.activeFileRel,
                        backing?.let { paneVm.zoomPathSegments(it) } ?: emptyList<String>(),
                        backing != null && paneVm.canZoomBack(backing),
                        backing != null && paneVm.canZoomForward(backing),
                    )
                }
                .distinctUntilChanged()
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
                        rerenderActivePane()
                    }
                    refreshLeftSidebarSections()
                }
        }
    }

    /**
     * Computes the pane title string used in both the pane chrome header
     * and the sidebar row for [paneId]. Always presents a path-style
     * title: when the pane is zoomed, the joined breadcrumb (outer / … /
     * current); when at document root (or before the document has
     * loaded), the muted "Root" label so the user reads pane chrome the
     * same way regardless of zoom state. The pane's own configured
     * `paneTitle`, if non-blank, prefixes the path so renamed panes can
     * still surface a custom name.
     */
    private fun paneTitleString(paneId: String, paneTitle: String?): String {
        // "Untitled" is a placeholder a previous version of notegrow
        // baked into pane creation; treat it as null so the path label
        // wins on persisted layouts that still carry it.
        val ownTitle = paneTitle?.ifBlank { null }?.takeUnless { it == "Untitled" }
        val fileLabel = activeFileDisplayName(paneId)
        val path = zoomPathStringForPane(paneId)
        val combined = if (path != null) "$fileLabel / $path" else fileLabel
        return if (ownTitle != null) "$ownTitle / $combined" else combined
    }

    /**
     * Display name of the file currently loaded in [paneId] — basename
     * minus `.md`, with the directory path stripped. Falls back to
     * "Root" when the pane's view model hasn't booted yet.
     */
    private fun activeFileDisplayName(paneId: String): String {
        val backing = paneViewModels[paneId]?.stateFlow?.value?.backingState ?: return "Root"
        val fileRel = backing.documentState?.activeFileRel ?: return "Root"
        return fileRel.substringAfterLast('/').removeSuffix(".md").ifBlank { "Root" }
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
                val fileRel = backing.documentState?.activeFileRel ?: return
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
        val fileRel = backing.documentState?.activeFileRel
        if (fileRel != null && fileRel != paneVm.rootFileName) {
            paneVm.navigateToVaultFile(paneVm.rootFileName)
        }
    }

    /**
     * Resolves the vault-relative parent file for [fileRel] using the
     * Notegrow on-disk convention that promoted subtrees live in
     * `<Name>/<Name>.md` files. The parent file is one folder shallower:
     *
     *  - `Recipes/Quick Granola/Quick Granola.md` → `Recipes/Recipes.md`
     *  - `Recipes/Recipes.md`                     → [rootFileName]
     *  - `links.md` (already root-level)          → `null`
     *
     * Path-based heuristic — does not verify the parent file actually
     * exists or contains a ref to [fileRel]. By Notegrow convention this
     * holds for every promoted file.
     *
     * @param fileRel      vault-relative path of the current file (with `.md`).
     * @param rootFileName vault-relative path of the configured root file.
     * @return the parent file's vault-relative path, or `null` when the
     *   file is already at root level (or is the root file itself).
     */
    private fun parentFileOf(fileRel: String, rootFileName: String): String? {
        if (fileRel == rootFileName) return null
        val withoutFile = fileRel.substringBeforeLast('/', "")
        if (withoutFile.isEmpty()) return null
        val parentFolder = withoutFile.substringBeforeLast('/', "")
        if (parentFolder.isEmpty()) return rootFileName
        val parentName = parentFolder.substringAfterLast('/')
        return "$parentFolder/$parentName.md"
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
     * injected by [ensureNotegrowChromeStyles] paints it grayed-out.
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
        // Style + starred share the same enable rule as the nav buttons:
        // available whenever the pane has a VM. We intentionally do NOT
        // gate on `backing.isLoaded` here — the chrome's distinct-by tuple
        // in [ensurePaneViewModel] doesn't watch `isLoaded`, so a
        // first-render-while-loading would otherwise leave the button
        // stuck disabled until a navigation event happens. The underlying
        // intents are themselves guarded against unloaded state.
        val canStyle = paneVm != null
        out += PaneAction(
            iconHtml = StyleDropdownIcons.TOOLBAR_STYLE,
            tooltip = "Style",
            handler = if (canStyle) ({ openStyleMenu(paneId) }) else ({}),
            extraClass = "notegrow-pane-action-style" + if (!canStyle) " $DISABLED_CLASS" else "",
        )
        val canStar = paneVm != null
        out += PaneAction(
            iconHtml = ICON_STAR,
            tooltip = "Starred",
            handler = if (canStar) ({ openStarredModal(paneId) }) else ({}),
            extraClass = "notegrow-pane-action-starred" + if (!canStar) " $DISABLED_CLASS" else "",
        )
        out += PaneActions.separator()
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
            "[data-pane-id='$paneId'] .dt-pane-action.notegrow-pane-action-style"
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
            )
        }
        modal.open()
    }

    /**
     * Injects notegrow-only chrome styles that aren't part of the toolkit
     * stylesheet: the disabled state for nav buttons (back/forward/up/
     * home stay in place when inert, dimmed instead of removed) and the
     * one-shot fade-in animation that plays on a navigation transition
     * (file switch or zoom change). Idempotent via the element id guard.
     */
    private fun ensureNotegrowChromeStyles() {
        if (document.getElementById("notegrow-chrome-style") != null) return
        val style = document.createElement("style") as HTMLElement
        style.id = "notegrow-chrome-style"
        style.textContent = """
            .dt-pane-action.$DISABLED_CLASS {
                opacity: 0.32;
                pointer-events: none;
                cursor: default;
            }
            @keyframes notegrow-nav-fade-in {
                from { opacity: 0; transform: translateY(2px); }
                to   { opacity: 1; transform: translateY(0); }
            }
            .notegrow-editor.notegrow-nav-fade,
            .notegrow-title.notegrow-nav-fade {
                animation: notegrow-nav-fade-in 500ms ease-out;
            }
            .notegrow-starred-backdrop {
                position: fixed;
                inset: 0;
                background: rgba(0, 0, 0, 0.45);
                z-index: 2147483640;
                display: flex;
                align-items: center;
                justify-content: center;
            }
            .notegrow-starred-panel {
                width: min(640px, 92vw);
                height: min(720px, 85vh);
                display: flex;
                flex-direction: column;
                background: var(--t-terminal-bg, #1e1e1e);
                color: var(--t-terminal-fg, #e6e6e6);
                border: 1px solid var(--t-border, rgba(255, 255, 255, 0.12));
                border-radius: 8px;
                box-shadow: 0 12px 32px rgba(0, 0, 0, 0.45);
                overflow: hidden;
            }
            .notegrow-starred-header {
                display: flex;
                align-items: center;
                gap: 12px;
                padding: 10px 12px;
                border-bottom: 1px solid var(--t-border, rgba(255, 255, 255, 0.08));
            }
            .notegrow-starred-title {
                font-size: 14px;
                font-weight: 600;
                opacity: 0.85;
                margin-right: auto;
            }
            .notegrow-starred-add {
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
            .notegrow-starred-add:hover {
                background: rgba(255, 255, 255, 0.06);
            }
            .notegrow-starred-add.is-active {
                background: rgba(255, 200, 60, 0.18);
                border-color: rgba(255, 200, 60, 0.55);
                color: rgb(255, 210, 90);
            }
            .notegrow-starred-add-icon {
                display: inline-flex;
                width: 14px;
                height: 14px;
            }
            .notegrow-starred-close {
                background: transparent;
                border: none;
                color: inherit;
                font-size: 22px;
                line-height: 1;
                padding: 0 4px;
                cursor: pointer;
                opacity: 0.7;
            }
            .notegrow-starred-close:hover { opacity: 1; }
            .notegrow-starred-body {
                flex: 1 1 auto;
                min-height: 0;
                overflow-y: auto;
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
        backing: se.soderbjorn.notegrow.main.DocumentViewBackingViewModel.State,
    ): Boolean {
        if (backing.zoomedLineId != null) return true
        val fileRel = backing.documentState?.activeFileRel ?: return false
        return parentFileOf(fileRel, paneVm.rootFileName) != null
    }

    /**
     * `true` when the pane is already showing the configured root file
     * with no zoom — i.e. there's nowhere to go via "home".
     */
    private fun isAtRootFileWithNoZoom(
        paneVm: MainViewModel,
        backing: se.soderbjorn.notegrow.main.DocumentViewBackingViewModel.State,
    ): Boolean {
        if (backing.zoomedLineId != null) return false
        return backing.documentState?.activeFileRel == paneVm.rootFileName
    }

    /**
     * Renders the in-pane content. Every pane is an editor onto the
     * shared global document — the original `editorPaneId` special-case
     * is gone (#15). Each pane id gets its own [MainScreen] backed by
     * its own [MainViewModel] / [DocumentViewBackingViewModel] so zoom
     * navigation, selection, and caret are pane-local while every edit
     * mutates the shared [documentBackingViewModel].
     */
    private fun renderPaneContent(id: String, slot: HTMLElement) {
        val container = document.createElement("div") as HTMLElement
        container.style.apply {
            width = "100%"
            height = "100%"
            background = "var(--t-terminal-bg, #1e1e1e)"
            color = "var(--t-terminal-fg, #e6e6e6)"
            setProperty("overflow", "hidden")
        }
        slot.appendChild(container)
        // Reuse the existing pane editor when present so the user's zoom
        // focus and selection survive tab switches and re-renders. Build
        // a fresh per-pane VM stack on first render.
        ensurePaneViewModel(id)
        val screen = paneEditors.getOrPut(id) {
            MainScreen(paneViewModels.getValue(id), scope)
        }
        screen.render(container)
    }

    /** Updates [paneId]'s title in the float list. */
    private fun renamePane(tabId: String, paneId: String, newTitle: String) {
        val cur = tabLayouts[tabId] ?: return
        tabLayouts[tabId] = cur.copy(
            floatingPanes = cur.floatingPanes.map { f ->
                if (f.id == paneId) f.copy(title = newTitle) else f
            },
        )
        persistLayoutState()
        rerenderActivePane()
    }

    /**
     * Adds a new pane to [tabId] at a randomised position with the highest
     * z-index so it lands on top. Persisted via [TabState.floatingPanes]
     * so panes survive reload. In the floats-only model every pane lives
     * here — there is no separate split tree.
     */
    private fun addFloatingPane(tabId: String) {
        val cur = tabLayouts[tabId] ?: return
        val existingIds = cur.floatingPanes.map { it.id }.toSet()
        var n = existingIds.size + 1
        var newId = "$tabId-pane-$n"
        while (newId in existingIds) {
            n++
            newId = "$tabId-pane-$n"
        }
        val topZ = cur.floatingPanes.maxOfOrNull { it.zIndex } ?: 0
        val spec = se.soderbjorn.darkness.web.layout.randomFloatingPaneSpec(
            id = newId,
            // Leave the spec title null so the chrome falls through to
            // the zoom path / "Root" label. Setting "Untitled" here would
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
        persistLayoutState()
        rerenderActivePane()
        refreshLeftSidebarSections()
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
        rerenderActivePane()
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
        rebuildShell()
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
        rerenderActivePane()
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
        rerenderActivePane()
        refreshLeftSidebarSections()
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
        // collectors don't outlive their pane.
        paneViewModels.remove(paneId)
        paneEditors.remove(paneId)
        starredModals.remove(paneId)?.dispose()
        if (remaining.isEmpty() && layoutState.tabs.size > 1) {
            // Last pane in a non-last tab: cascade to closing the tab.
            closeTab(tabId)
            return
        }
        tabLayouts[tabId] = cur.copy(floatingPanes = remaining)
        persistLayoutState()
        refreshLeftSidebarSections()
        rerenderActivePane()
    }

    // ── Trailing actions: palette + appearance toggle ────────────────

    private fun buildLeadingTitle(): HTMLElement {
        val title = document.createElement("div") as HTMLElement
        title.textContent = "Notegrow"
        title.style.apply {
            // Inherit the toolkit chrome font (.dt-topbar sets the UI stack);
            // forcing a different family here was leaking the editor's
            // monospace into the chrome.
            fontWeight = "600"
            fontSize = "13px"
            setProperty("letter-spacing", "0.02em")
            color = "var(--t-chrome-titleText, #e6e6e6)"
            display = "flex"
            alignItems = "center"
        }
        return title
    }

    private fun buildTrailingActions(): HTMLElement {
        val wrap = document.createElement("div") as HTMLElement
        wrap.style.apply {
            display = "flex"
            alignItems = "center"
            setProperty("gap", "4px")
        }
        // Layout-preset dropdown comes first; the "new pane" button (which
        // spawns a floating overlay) sits to its right so the trailing-area
        // ordering reads layout → new pane → appearance → palette.
        wrap.appendChild(
            buildLayoutPresetButton(
                paneCount = { activeTabPaneCount() },
                onSelect = { preset ->
                    val activeId = layoutState.activeTabId ?: return@buildLayoutPresetButton
                    applyLayoutPreset(activeId, preset)
                },
            )
        )
        // The "new pane" button spawns a FLOATING overlay pane (high
        // z-index, randomised position) on top of the existing layout —
        // matches termtastic's window-style spawn behaviour rather than
        // splitting the tree underneath. The split-pane glyph from the
        // toolkit reads the same as termtastic's #new-window-button. New
        // tabs go through the `…` overflow menu's "New tab" entry.
        wrap.appendChild(
            buildNewWindowButton(tooltip = "New pane") {
                val activeId = layoutState.activeTabId ?: return@buildNewWindowButton
                addFloatingPane(activeId)
            }
        )
        // Order matches termtastic: appearance toggle, then palette.
        // (No separate right-sidebar-toggle: the palette button already
        // opens / closes the theme manager sidebar.)
        wrap.appendChild(buildAppearanceButton())
        wrap.appendChild(buildPaletteButton())
        return wrap
    }

    /** Build the leading area: just the left-sidebar toggle button. The
     *  app name lives in the OS window title bar, not the topbar — same
     *  pattern termtastic uses. */
    private fun buildLeadingTitleAndToggle(): HTMLElement {
        val wrap = document.createElement("div") as HTMLElement
        wrap.style.apply {
            display = "flex"
            alignItems = "center"
        }
        wrap.appendChild(
            se.soderbjorn.darkness.web.shell.buildLeftSidebarToggleButton(
                isOpen = se.soderbjorn.darkness.web.shell.leftSidebarController.isOpen,
                onToggle = {
                    se.soderbjorn.darkness.web.shell.leftSidebarController.toggle(
                        requestRebuild = {
                            layoutState = layoutState.copy(
                                leftSidebar = layoutState.leftSidebar.copy(
                                    visible =
                                        se.soderbjorn.darkness.web.shell
                                            .leftSidebarController.isOpen,
                                ),
                            )
                            persistLayoutState()
                            rebuildShell()
                        },
                    )
                },
            )
        )
        return wrap
    }

    /** Pane count of the active tab — drives the layout-dropdown
     *  miniatures so the previewed slot count matches what the user
     *  currently sees. Counts only non-minimised floats; minimised panes
     *  aren't visible on canvas, so they aren't part of the arrangement. */
    private fun activeTabPaneCount(): Int {
        val activeId = layoutState.activeTabId ?: return 0
        val layout = tabLayouts[activeId] ?: return 0
        return layout.floatingPanes.count { !it.isMinimized }
    }

    /**
     * Rearranges the active tab's visible panes into the chosen preset.
     * Floats-only model: the preset's boxes are assigned directly to the
     * existing float specs (no tree rebuild). The currently-focused pane
     * lands in slot 0 (the highlighted primary in the dropdown miniature);
     * remaining panes fill the other slots in their list order so per-pane
     * state (open document, scroll position, view-models keyed on pane id)
     * survives the switch. Every visible pane is unmaximised so the
     * arrangement is actually visible — applying a preset on a maximised
     * pane would otherwise just paint one full-bleed pane on top of the
     * intended layout. Minimised panes are kept verbatim: they aren't part
     * of the visible arrangement.
     *
     * If the tab has no panes at all (shouldn't happen — close keeps the
     * floor at one — but defensive), the seed pane is materialised so
     * the user always lands on something.
     */
    private fun applyLayoutPreset(tabId: String, preset: LayoutPreset) {
        val cur = tabLayouts[tabId] ?: return
        val visible = cur.floatingPanes.filter { !it.isMinimized }
        if (visible.isEmpty()) {
            tabLayouts[tabId] = cur.copy(floatingPanes = listOf(seedPane(tabId)))
            persistLayoutState()
            rerenderActivePane()
            refreshLeftSidebarSections()
            return
        }
        val focusedId = lastFocusedPaneIdByTab[tabId]
        val ordered = if (focusedId != null && visible.any { it.id == focusedId }) {
            listOf(visible.first { it.id == focusedId }) +
                visible.filter { it.id != focusedId }
        } else visible
        val boxes = preset.computeBoxes(ordered.size)
        val rearranged = ordered.mapIndexed { index, spec ->
            val box = boxes[index]
            spec.copy(
                xPct = box.x,
                yPct = box.y,
                widthPct = box.width,
                heightPct = box.height,
                isMaximized = false,
            )
        }
        // Preserve list order for non-rearranged (minimised) floats and
        // append rearranged ones in the slot order the preset chose.
        val minimised = cur.floatingPanes.filter { it.isMinimized }
        tabLayouts[tabId] = cur.copy(
            floatingPanes = rearranged + minimised,
        )
        persistLayoutState()
        rerenderActivePane()
        refreshLeftSidebarSections()
    }

    private fun buildPaletteButton(): HTMLElement =
        buildThemeManagerButton(
            isOpen = isThemeManagerSidebarOpen(),
            tooltip = "Theme manager",
            onToggle = { toggleThemeManager() },
        )

    private fun buildAppearanceButton(): HTMLElement {
        val btn = se.soderbjorn.darkness.web.shell.buildAppearanceCycleButton(
            appearance = uiSettings.appearance,
            onCycle = { cycleAppearance() },
        )
        appearanceButton = btn
        return btn
    }

    /**
     * Repaints the appearance button with the current state's icon. Called
     * after [cycleAppearance] / external settings updates so the user sees
     * the icon change immediately. Re-uses the toolkit factory so the icon
     * stays in sync with termtastic's `#appearance-toggle`.
     */
    private fun updateAppearanceButton() {
        val btn = appearanceButton ?: return
        val parent = btn.parentElement ?: return
        val replacement = se.soderbjorn.darkness.web.shell.buildAppearanceCycleButton(
            appearance = uiSettings.appearance,
            onCycle = { cycleAppearance() },
        )
        parent.replaceChild(replacement, btn)
        appearanceButton = replacement
    }

    private fun cycleAppearance() {
        val next = when (uiSettings.appearance) {
            Appearance.Auto -> Appearance.Dark
            Appearance.Dark -> Appearance.Light
            Appearance.Light -> Appearance.Auto
        }
        uiSettings = uiSettings.copy(appearance = next)
        themeState.appearance = next
        applyTheme(uiSettings)
        updateAppearanceButton()
        persistUiSettings()
        refreshThemeManager()
    }

    private fun toggleThemeManager() {
        toggleThemeManagerSidebar(requestRebuild = ::rebuildShell)
    }

    // ── Theme persistence ───────────────────────────────────────────

    private fun seedThemeState(settings: UiSettings) {
        // Notegrow has a single `theme` field in UiSettings — not separate
        // light/dark slots like termtastic — so we mirror the active theme
        // into both slots. The toolkit's theme grid clicks resolve to
        // `setLightThemeName` / `setDarkThemeName` based on the active
        // appearance, and `onThemeStateChange` reads back from the slot
        // that matches the current appearance. Seeding both keeps the
        // grid highlight stable when toggling appearance without first
        // having opened the Theme Manager.
        themeState.mainSchemeName = settings.theme.name
        themeState.lightThemeName = settings.theme.name
        themeState.darkThemeName = settings.theme.name
        themeState.appearance = settings.appearance
    }

    private fun onThemeStateChange() {
        // The toolkit's theme grid writes the picked **theme** name (from
        // `defaultThemes` ∪ `customThemes`) into the light/dark slot that
        // matches the active appearance. To round-trip correctly across
        // every Darkness app reading the same `UiSettings` JSON, we have
        // to map the picked Theme's full composition (main scheme + every
        // per-section override) onto `UiSettings.*Theme` fields — writing
        // only `UiSettings.theme` would lose the section overrides and
        // both apps would see the wrong palette.
        val isDark = isDarkActive(themeState.appearance)
        val activeSlot = if (isDark) themeState.darkThemeName else themeState.lightThemeName
        val candidates = listOfNotNull(
            activeSlot,
            themeState.lightThemeName,
            themeState.darkThemeName,
            themeState.mainSchemeName,
        )
        // Materialise customSchemes once so `applyTheme` can resolve any
        // user-defined schemes the picked theme references.
        val customSchemesAsColor: Map<String, ColorScheme> =
            themeState.customSchemes.mapValues { it.value.toColorScheme() }
        // Look up the picked name as a Theme first (default themes, then
        // user-saved customThemes); fall back to "the name IS a scheme" so
        // a user who picked from the schemes tab still applies cleanly.
        val pickedTheme: se.soderbjorn.darkness.core.Theme? = candidates.asSequence()
            .mapNotNull { name ->
                defaultThemes.firstOrNull { it.name == name }
                    ?: themeState.customThemes[name]
            }
            .firstOrNull()
        uiSettings = if (pickedTheme != null) {
            // Sync mainSchemeName to the picked theme name (not its
            // colorScheme) so the grid filter + re-seed treat it as the
            // active selection.
            themeState.mainSchemeName = pickedTheme.name
            UiSettings.applyTheme(
                base = uiSettings.copy(appearance = themeState.appearance),
                theme = pickedTheme,
                customSchemes = customSchemesAsColor,
            )
        } else {
            // Scheme-only path: name resolved directly as a ColorScheme.
            val schemeFallback: ColorScheme = candidates.asSequence()
                .mapNotNull { name ->
                    recommendedColorSchemes.firstOrNull { it.name == name }
                        ?: customSchemesAsColor[name]
                }
                .firstOrNull()
                ?: recommendedColorSchemes.first { it.name == DEFAULT_THEME_NAME }
            themeState.mainSchemeName = schemeFallback.name
            // Reset every per-section override to null so the picked
            // scheme paints uniformly (otherwise stale section overrides
            // from a prior Theme selection would still apply).
            uiSettings.copy(
                theme = schemeFallback,
                appearance = themeState.appearance,
                sidebarTheme = null,
                terminalTheme = null,
                diffTheme = null,
                fileBrowserTheme = null,
                tabsTheme = null,
                chromeTheme = null,
                windowsTheme = null,
                activeTheme = null,
                bottomBarTheme = null,
            )
        }
        applyTheme(uiSettings)
        updateAppearanceButton()
        persistUiSettings()
        refreshThemeManager()
    }

    private fun applyTheme(settings: UiSettings) {
        val isDark = isDarkActive(settings.appearance)
        val docEl = document.documentElement as? HTMLElement ?: return
        // Toolkit helper paints both the main theme AND every per-section
        // override (sidebar / chrome / terminal / …) so the topbar bg,
        // sidebar surface, etc. all match the picked Theme — not just the
        // main scheme. Single source of truth for both apps in the family.
        se.soderbjorn.darkness.web.applyUiSettings(docEl, settings, isDark)
    }

    private fun loadInitialUiSettings(): UiSettings {
        if (perAppThemeSettings) {
            val key = perAppThemeStorageKey
            val raw = (js("(globalThis.localStorage && globalThis.localStorage.getItem(key)) || null") as? String)
                ?: return UiSettings.defaults()
            return UiSettings.fromJsonString(raw)
        }
        val raw = js("globalThis.__darknessSettings || null") as? String
            ?: return UiSettings.defaults()
        return UiSettings.fromJsonString(raw)
    }

    private fun persistUiSettings() {
        val json = uiSettings.toJsonString()
        lastWrittenUiSettingsJson = json
        if (perAppThemeSettings) {
            // Notegrow-private slot: bypass the shared Electron IPC so
            // termtastic's UiSettings file doesn't get overwritten.
            val key = perAppThemeStorageKey
            js("globalThis.localStorage && globalThis.localStorage.setItem(key, json)")
            return
        }
        val api = js("globalThis.darknessApi") ?: return
        val write = js("api && api.writeUiSettings") ?: return
        if (js("typeof write !== 'function'") as Boolean) return
        js("write.call(api, json)")
    }

    /**
     * Parses [json] through a pool that includes the user's custom schemes
     * so themes that reference custom (non-recommended) section schemes
     * keep their per-section overrides intact across a write→watch
     * round-trip. Without this, the file-watcher echo would re-parse with
     * `recommendedColorSchemes` only, drop every custom-scheme section
     * override to `null`, and visually collapse the per-area palette to
     * the main scheme.
     */
    private fun parseUiSettingsWithCustomSchemes(json: String): UiSettings {
        val customPool: List<ColorScheme> = themeState.customSchemes.values
            .map { it.toColorScheme() }
        val pool: List<ColorScheme> = recommendedColorSchemes + customPool
        val obj = runCatching {
            kotlinx.serialization.json.Json
                .parseToJsonElement(json) as? kotlinx.serialization.json.JsonObject
        }.getOrNull() ?: return UiSettings.fromJsonString(json)
        return UiSettings.resolveAgainst(obj, pool)
    }

    private fun subscribeToExternalThemeChanges() {
        // When notegrow's theme lives in its own localStorage slot, no
        // file-watcher echoes can reach us — skip the subscription so a
        // shared-file change in another app can't reset our theme.
        if (perAppThemeSettings) return
        val api = js("globalThis.darknessApi") ?: return
        val onChange = js("api && api.onUiSettingsChanged") ?: return
        if (js("typeof onChange !== 'function'") as Boolean) return
        val cb: (String) -> Unit = lambda@{ json ->
            // Suppress echoes of our own writes — Electron's settings
            // bridge fans every write back through this callback, and
            // re-seeding `themeState` from the persisted JSON would
            // overwrite the in-memory theme name (which can be a Theme)
            // with the persisted scheme name, causing a brief paint
            // flicker as the colours snap to the scheme-only resolution.
            //
            // Compare structurally (parsed UiSettings == current uiSettings)
            // rather than byte-for-byte: the file-watcher reads disk after
            // a debounce, and OS or Electron normalisation can turn the
            // bytes we wrote into a JSON that no longer string-equals our
            // in-memory copy even though it represents the same settings.
            // Parsing first also folds custom-scheme section overrides
            // back into `UiSettings` so the equality check is fair.
            val incoming = parseUiSettingsWithCustomSchemes(json)
            if (incoming == uiSettings) return@lambda
            uiSettings = incoming
            seedThemeState(uiSettings)
            applyTheme(uiSettings)
            updateAppearanceButton()
            refreshThemeManager()
        }
        js("onChange.call(api, cb)")
    }

    // ── Layout-state persistence ─────────────────────────────────────

    private fun loadInitialLayoutState(): LayoutState {
        val raw = js("globalThis.__darknessLayoutState || null") as? String
        val state = if (raw == null) LayoutState.defaults() else LayoutState.fromJsonString(raw)

        val seeded = if (state.tabs.isEmpty()) {
            // Shouldn't happen — defaults() always includes one tab — but
            // fall through gracefully.
            LayoutState.defaults()
        } else state

        for (tab in seeded.tabs) {
            // Flatten any legacy split tree into the float list so the
            // tab is purely floats-only on the runtime side, regardless
            // of what's still on disk. Persisted floats land first
            // (preserving their stored geometry); legacy tree leaves
            // become full-bleed maximised floats so the user sees the
            // pane content survive the migration. The tab's persisted
            // tree is dropped on next save (the loader builds floats
            // out of it; we never re-emit a tree).
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
            // Drop migrated leaves whose ids already exist as floats —
            // shouldn't happen in legitimate persisted data but guards
            // against duplicate ids producing two panes of the same id.
            val existingIds = persistedFloats.map { it.id }.toSet()
            val merged = persistedFloats + migratedTreeLeaves.filterNot { it.id in existingIds }
            // If every source was empty (no tree, no floats), seed the
            // tab so the user lands on a usable pane.
            val final = if (merged.isEmpty()) listOf(seedPane(tab.id)) else merged
            tabLayouts[tab.id] = PaneLayout(floatingPanes = final)
        }

        val activeId = seeded.activeTabId ?: seeded.tabs.first().id
        return seeded.copy(activeTabId = activeId)
    }

    /**
     * Walk a persisted [PaneNodeJson] tree and turn each leaf into a
     * [FloatingPaneSpec]. The first leaf becomes the "primary" full-bleed
     * maximised pane; subsequent leaves are stacked in cascade positions
     * with ascending z-index so the user sees them all without manual
     * positioning right after the migration. They are de-maximised so
     * the cascade actually shows.
     */
    private fun collectTreeLeafSpecs(
        tree: se.soderbjorn.darkness.store.PaneNodeJson,
    ): List<FloatingPaneSpec> {
        val collected = mutableListOf<Pair<String, String?>>()
        fun walk(n: se.soderbjorn.darkness.store.PaneNodeJson) {
            when (n) {
                is se.soderbjorn.darkness.store.PaneNodeJson.Leaf ->
                    collected += n.id to n.title
                is se.soderbjorn.darkness.store.PaneNodeJson.Split -> {
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

    private fun persistLayoutState() {
        // Snapshot the in-memory layout into the persistable state. The
        // tree field is always written as null in the floats-only model;
        // every pane lives in floatingPanes so it can survive an Electron
        // reload with full geometry preserved.
        val snapTabs = layoutState.tabs.map { tab ->
            val layout = tabLayouts[tab.id]
            if (layout == null) tab
            else tab.copy(
                tree = null,
                expandedLeafId = null,
                floatingPanes = layout.floatingPanes.map { f ->
                    se.soderbjorn.darkness.store.FloatingPaneJson(
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
        layoutState = layoutState.copy(
            tabs = snapTabs,
            rightSidebar = layoutState.rightSidebar.copy(visible = isThemeManagerSidebarOpen()),
        )

        val api = js("globalThis.darknessApi") ?: return
        val write = js("api && api.writeLayoutState") ?: return
        if (js("typeof write !== 'function'") as Boolean) return
        val json = layoutState.toJsonString()
        js("write.call(api, json)")
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
         * [PaneAction] has no native `disabled` flag, so notegrow tags
         * the rendered button itself and the stylesheet (injected by
         * [ensureNotegrowChromeStyles]) dims it and disables pointer
         * events. Used by [buildPaneNavActions] to keep back/forward
         * (and up/home) in fixed positions for muscle memory while
         * showing whether they're currently actionable.
         */
        private const val DISABLED_CLASS: String = "notegrow-pane-action-disabled"

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
