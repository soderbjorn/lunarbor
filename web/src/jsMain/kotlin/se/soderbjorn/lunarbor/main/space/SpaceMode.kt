/*
 * SpaceMode.kt (jsMain)
 * ---------------------
 * 3D mode (LBR-11) in four shapes ([SpaceShape]). In **Pages** every
 * node's page hangs at a fixed place in space, and each window looks into
 * that space through its own camera ([PageSpaceView]). The map shapes —
 * **Crown**, **Cone** and **Galaxy** — show the whole node tree as bodies
 * in space instead, with the tab's windows as cards beside the nodes they
 * show ([MapView]); the strip's shape switcher, L (on the map) and ⌃⌘2
 * change shape. This file owns the mode as a whole:
 *
 *  - entering and leaving (the topbar cube, ⌃⌘3, Esc when nothing else
 *    wants it), and the shape, remembered under the persister key
 *    [PERSIST_KEY] — never in the vault;
 *  - which windows are shown: only the focused one, filling the work area,
 *    or every window of the tab at its floating-pane rectangle and stacking
 *    order, proportions as in the 2D layout (⌃⌘1);
 *  - the help dialog ([showSpaceHelp]), from the strip's Help button;
 *  - the overlay: a layer over the whole window — sidebar, top bar and
 *    panes all hidden under it (menus, popups, modals and the palette
 *    still open above it) — with a slim draggable strip on top holding the
 *    leave button, and one WebGL canvas
 *    drawing every view's background (stars and dust) with a viewport and
 *    scissor per view;
 *  - the render loop, which runs only while something moves;
 *  - the theme: the overlay and pages use `--t-*` variables directly, and
 *    the WebGL specks are recoloured whenever the theme changes. On top of
 *    it 3D mode adds colours of its own ([SpacePalette], [areaColor]):
 *    each area's hue on its pages, threads and bodies, and tinted specks.
 *
 * three.js is loaded on first entry ([loadThreeLib]) into its own chunk.
 * The tab and pane structure stays `AppShell`'s: it implements [SpaceHost]
 * and tells the mode when the layout changes ([onLayoutChanged]).
 *
 * jsMain only.
 */

package se.soderbjorn.lunarbor.main.space

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.lunarbor.main.DocumentRegistry
import se.soderbjorn.lunarbor.main.LinkPreviewItem
import se.soderbjorn.lunarbor.main.MainScreen
import se.soderbjorn.lunarbor.main.MainViewModel
import se.soderbjorn.lunarbor.main.PageSpaceKeys
import se.soderbjorn.lunarbor.main.SpacePalette
import se.soderbjorn.lunarbor.main.SpaceShape
import se.soderbjorn.lunarbor.main.space.three.Object3
import se.soderbjorn.lunarbor.main.space.three.PointsMaterial3
import se.soderbjorn.lunarbor.main.space.three.ThreeLib
import se.soderbjorn.lunarbor.main.space.three.WebGLRenderer3
import se.soderbjorn.lunarbor.main.space.three.loadThreeLib
import se.soderbjorn.lunula.core.Persister
import se.soderbjorn.lunula.web.layout.PaneTitleSegment
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * One window of the active tab, as 3D mode lays it out.
 *
 * @property id The toolkit pane id.
 * @property label Its badge text, `Window 1`, `Window 2`, … in tab order.
 * @property x Left edge as a fraction (0..1) of the pane area.
 * @property y Top edge as a fraction of the pane area.
 * @property w Width as a fraction of the pane area.
 * @property h Height as a fraction of the pane area.
 * @property z Stacking order: views of overlapping windows stack as the
 *   windows do in the 2D layout.
 */
data class SpacePane(val id: String, val label: String, val x: Double, val y: Double, val w: Double, val h: Double, val z: Int = 0)

/**
 * One tab, as 3D mode's dock lists it.
 *
 * @property id The tab id.
 * @property label Its title.
 * @property windows How many windows it has (drawn as pips).
 * @property active Whether it is the tab on screen.
 */
data class SpaceTab(val id: String, val label: String, val windows: Int, val active: Boolean)

/**
 * What 3D mode needs from the app shell, which owns tabs, panes and their
 * editors. Implemented by `AppShell`.
 */
interface SpaceHost {
    /** The active tab's windows that are not minimized, in tab order. */
    fun spacePanes(): List<SpacePane>

    /** The focused window of the active tab, if any. */
    fun focusedPaneId(): String?

    /** Makes [paneId] the tab's focused window (as a click on the pane would). */
    fun focusPane(paneId: String)

    /** The window's view model, built if the window has not rendered yet. */
    fun viewModelOf(paneId: String): MainViewModel?

    /** The window's editor view, built if the window has not rendered yet. */
    fun screenOf(paneId: String): MainScreen?

    /** The window's breadcrumb: its whole location, every segment but the last clickable. */
    fun breadcrumbOf(paneId: String): List<PaneTitleSegment>

    /** The tabs shown in the tab strip, in order. */
    fun spaceTabs(): List<SpaceTab>

    /** Switches to tab [tabId] (as clicking it in the tab strip would). */
    fun selectTab(tabId: String)

    /** Opens a new window in the active tab at the focused window's place ("New window"). */
    fun newWindow()

    /** Opens the command palette. */
    fun openPalette()
}

/**
 * Whether 3D mode is turned on in App settings ("Enable 3D mode", off by
 * default). While `false` nothing reaches it: the topbar cube is hidden
 * (no `data-lunarbor-space-enabled` on `<body>`), ⌃⌘3 / ⌃⌘1 do nothing,
 * the Keyboard Shortcuts sidebar leaves out its rows, and a remembered
 * "on" is not restored. Loaded by [SpaceMode.restore], changed by
 * [SpaceMode.setEnabled]; read by `LunarborHotkeysContent`.
 */
var isSpaceModeEnabled: Boolean = false
    private set

/**
 * The 3D "Pages" mode. One per app, built by `AppShell`.
 *
 * ### Callers
 * - `AppShell`: the topbar cube and the ⌃⌘3 action call [toggle], ⌃⌘1
 *   calls [toggleSplit]; [restore] at startup; [onLayoutChanged] after
 *   every tab / pane / focus change.
 * - [PageSpaceView]s call back for frames, focus and window badges.
 *
 * @param host The app shell.
 * @param scope Scope for loading and the views' collectors.
 * @param persister Where on / off, single / split and the shape are remembered.
 * @param registry The app's registry: node listings for the page views'
 *   previews and the map, links and the privacy mode for the map.
 */
class SpaceMode(
    private val host: SpaceHost,
    private val scope: CoroutineScope,
    private val persister: Persister,
    private val registry: DocumentRegistry,
) {
    /**
     * The registry's node listings (`DocumentRegistry.linkPreviewsFlow`);
     * page views refresh their previews when it changes.
     */
    internal val linkPreviewsFlow: StateFlow<Map<String, List<LinkPreviewItem>>> get() = registry.linkPreviewsFlow

    /** The shape shown: Pages or one of the maps. Remembered. */
    var shape: SpaceShape = SpaceShape.PAGES
        private set

    /** The map shapes' view, built the first time one is shown. */
    private var map: MapView? = null
    private var shapeButtons: HTMLElement? = null

    /** `true` while 3D mode is on (or opening). */
    var isActive: Boolean = false
        private set

    /** `true` when every window of the tab has a view; `false` for the focused one alone. */
    var isSplit: Boolean = false
        private set

    private var lib: ThreeLib? = null
    private var overlay: HTMLElement? = null
    private var viewsHost: HTMLElement? = null
    private var dock: HTMLElement? = null
    private var splitButton: HTMLElement? = null
    private var backdrop: Backdrop? = null
    private val views = LinkedHashMap<String, PageSpaceView>()
    private var frameHandle: Int? = null
    private var lastFrame = 0.0
    private var themeObserver: dynamic = null

    /** Set by the capture-phase keydown when something else owns this Escape. */
    private var escapeTaken = false

    private val onResize: (Event) -> Unit = { layoutViews() }

    private val escapeCapture: (Event) -> Unit = { e ->
        val ke = e as KeyboardEvent
        if (ke.key == "Escape") escapeTaken = !isActive || somethingElseOwnsEscape()
    }

    private val escapeBubble: (Event) -> Unit = { e ->
        val ke = e as KeyboardEvent
        if (ke.key == "Escape" && isActive && !escapeTaken && !ke.defaultPrevented &&
            !ke.metaKey && !ke.ctrlKey && !ke.altKey && !ke.shiftKey
        ) {
            ke.preventDefault()
            exit()
        }
        escapeTaken = false
    }

    /**
     * Turns 3D mode back on when it was on at the last exit (and in the
     * view it was in). Called once by `AppShell` after the layout loaded.
     */
    fun restore() {
        scope.launch {
            applyEnabled(persister.read(ENABLED_KEY) == "true")
            if (!isSpaceModeEnabled) return@launch
            val saved = persister.read(PERSIST_KEY) ?: return@launch
            val obj: dynamic = try {
                JSON.parse<dynamic>(saved)
            } catch (_: Throwable) {
                return@launch
            }
            if (obj == null) return@launch
            isSplit = obj.split == true
            shape = SpaceShape.of(obj.shape as? String)
            if (obj.on == true) enter()
        }
    }

    /** `true` while the theme's background is dark (glowing colours); updated with the theme. */
    private var darkTheme = true

    /**
     * The CSS colour of the area [pathRel] lies in ([SpacePalette]: a vivid
     * hue per top-level area, more colourful than the theme on purpose), or
     * `null` at the root. Hues follow the root's unfiltered outline order,
     * so a privacy mode never shifts them. Called by [PageSpaceView] for
     * page edges, item dots and threads.
     */
    internal fun areaColor(pathRel: String): String? {
        val hues = SpacePalette.areaHues(registry.requestLinkPreview("").orEmpty().mapNotNull { it.pathRel })
        val h = SpacePalette.hueOf(pathRel, hues) ?: return null
        return if (darkTheme) "hsl(${(h * 360).toInt()} 85% 62%)" else "hsl(${(h * 360).toInt()} 75% 45%)"
    }

    /** Turns 3D mode on or off (the cube button, ⌃⌘3). */
    fun toggle() {
        if (!isSpaceModeEnabled) return
        if (isActive) exit() else scope.launch { enter() }
    }

    /**
     * Switches between the focused window alone and every window of the
     * tab (⌃⌘1). Remembered; takes effect at once when 3D mode is on.
     */
    fun toggleSplit() {
        if (!isSpaceModeEnabled) return
        isSplit = !isSplit
        persist()
        updateSplitButton()
        if (isActive) rebuildViews()
    }

    /**
     * Shows [next]: the page views for [SpaceShape.PAGES], the map for the
     * others. Remembered; takes effect at once when 3D mode is on.
     */
    fun setShape(next: SpaceShape) {
        if (!isSpaceModeEnabled || next == shape) return
        val wasMap = shape.isMap
        shape = next
        persist()
        updateShapeButtons()
        if (!isActive || lib == null) return
        overlay?.classList?.toggle("is-map", next.isMap)
        if (next.isMap) {
            if (!wasMap) {
                // The editors go back to their (hidden) panes while the map shows.
                for (v in views.values) v.dispose(focus = false)
                views.clear()
            }
            showMap()
            renderDock()
        } else {
            map?.hide()
            rebuildViews()
        }
        requestFrame()
    }

    /** The shape after the current one (L on the map, ⌃⌘2). */
    fun nextShape() = setShape(shape.next())

    /** Builds the map on first use and shows it in the current shape. */
    private fun showMap() {
        val l = lib ?: return
        val ov = overlay ?: return
        val m = map ?: MapView(l, this, registry, scope).also {
            map = it
            ov.insertBefore(it.element, ov.querySelector(".lunarbor-space-strip"))
        }
        m.show(shape)
        m.place(m.element.clientWidth, m.element.clientHeight)
    }

    /** Marks the current shape in the strip's switcher. */
    private fun updateShapeButtons() {
        val host = shapeButtons ?: return
        for (i in 0 until host.children.length) {
            val b = host.children.item(i) as HTMLElement
            b.setAttribute("aria-pressed", (b.getAttribute("data-shape") == shape.name).toString())
        }
    }

    /**
     * Turns the feature on or off (App settings → "Enable 3D mode"),
     * remembered under [ENABLED_KEY]. Turning it off leaves 3D mode first
     * when it is open.
     *
     * @param enabled The new value of [isSpaceModeEnabled].
     */
    fun setEnabled(enabled: Boolean) {
        if (!enabled && isActive) exit()
        applyEnabled(enabled)
        scope.launch { persister.write(ENABLED_KEY, enabled.toString()) }
    }

    /** Sets [isSpaceModeEnabled] and the `<body>` attribute that shows the topbar cube. */
    private fun applyEnabled(enabled: Boolean) {
        isSpaceModeEnabled = enabled
        if (enabled) document.body?.setAttribute("data-lunarbor-space-enabled", "")
        else document.body?.removeAttribute("data-lunarbor-space-enabled")
    }

    /** Shows the view the split button switches to next. */
    private fun updateSplitButton() {
        splitButton?.innerHTML = (if (isSplit) "<span>Focused window</span>" else "<span>All windows</span>") + "<kbd>⌃⌘1</kbd>"
        splitButton?.classList?.toggle("is-on", isSplit)
    }

    /**
     * Redraws the dock along the bottom: the tabs (title, a pip per window,
     * click to switch) and the active tab's windows (`1 · page title`,
     * the focused one marked; click to focus it), then "+ Window".
     */
    private fun renderDock() {
        val dk = dock ?: return
        dk.innerHTML = ""
        val tabs = div("lunarbor-space-tabs")
        host.spaceTabs().forEachIndexed { i, t ->
            val b = document.createElement("button") as HTMLElement
            b.className = "lunarbor-space-tab"
            b.setAttribute("aria-pressed", t.active.toString())
            b.appendChild(span("lunarbor-space-tab-label", t.label))
            val pips = span("lunarbor-space-pips", "")
            repeat(t.windows.coerceAtMost(8)) { pips.appendChild(document.createElement("i")) }
            b.appendChild(pips)
            if (i < 9) b.appendChild(span("lunarbor-space-tab-key", "${i + 1}"))
            b.addEventListener("click", { _ -> if (!t.active) host.selectTab(t.id) })
            tabs.appendChild(b)
        }
        dk.appendChild(tabs)
        val chips = div("lunarbor-space-chips")
        val focused = host.focusedPaneId()
        for (p in host.spacePanes()) {
            val page = views[p.id]?.page
                ?: host.viewModelOf(p.id)?.let { vm -> vm.spacePageOf(vm.currentBackingState) }
            // The root page is "Home", as in the breadcrumb.
            val title = when {
                page?.key == PageSpaceKeys.ofFolder("") -> "Home"
                page != null -> page.title
                // Not loaded yet (the map shows no editors): the breadcrumb's last segment.
                else -> host.breadcrumbOf(p.id).lastOrNull()?.label ?: ""
            }
            val chip = document.createElement("button") as HTMLElement
            chip.className = if (p.id == focused) "lunarbor-space-chip is-focused" else "lunarbor-space-chip"
            chip.textContent = "${p.label.removePrefix("Window ")} · ${title.ifEmpty { "Untitled" }}"
            chip.title = "Switch to ${p.label}"
            chip.addEventListener("click", { _ ->
                host.focusPane(p.id)
                views[p.id]?.focusEditor()
            })
            chips.appendChild(chip)
        }
        val add = document.createElement("button") as HTMLElement
        add.className = "lunarbor-space-chip is-add"
        add.textContent = "+ Window"
        add.title = "New window on this page"
        add.addEventListener("click", { _ -> host.newWindow() })
        chips.appendChild(add)
        dk.appendChild(chips)
    }

    /** Opens 3D mode: loads three.js (first time), builds the overlay and the views. */
    suspend fun enter() {
        if (isActive) return
        isActive = true
        val loaded = try {
            lib ?: loadThreeLib().also { lib = it }
        } catch (t: Throwable) {
            console.error("Lunarbor: 3D mode could not load three.js", t)
            isActive = false
            return
        }
        if (!isActive) return
        ensureSpaceStyles()
        val ov = div("lunarbor-space")
        ov.classList.toggle("is-map", shape.isMap)
        val canvasLayer = div("lunarbor-space-backdrop")
        ov.appendChild(canvasLayer)
        val vh = div("lunarbor-space-views")
        ov.appendChild(vh)
        ov.appendChild(buildStrip())
        val dk = div("lunarbor-space-dock")
        ov.appendChild(dk)
        dock = dk
        document.body?.appendChild(ov)
        document.body?.setAttribute("data-lunarbor-space", "")
        overlay = ov
        viewsHost = vh
        backdrop = Backdrop.create(loaded)?.also { canvasLayer.appendChild(it.canvas) }
        window.addEventListener("resize", onResize)
        window.addEventListener("keydown", escapeCapture, true)
        document.addEventListener("keydown", escapeBubble, false)
        observeTheme()
        persist()
        rebuildViews()
    }

    /** Closes 3D mode: every window gets its editor back where it was, caret and scroll kept. */
    fun exit() {
        if (!isActive) return
        isActive = false
        // Panes visible first: an editor under `visibility: hidden` cannot
        // take focus, and the focused one gets its caret back on the way.
        document.body?.removeAttribute("data-lunarbor-space")
        overlay?.style?.display = "none"
        val focused = host.focusedPaneId()
        for (v in views.values) v.dispose(focus = v.paneId == focused)
        views.clear()
        frameHandle?.let { window.cancelAnimationFrame(it) }
        frameHandle = null
        window.removeEventListener("resize", onResize)
        window.removeEventListener("keydown", escapeCapture, true)
        document.removeEventListener("keydown", escapeBubble, false)
        themeObserver?.disconnect()
        themeObserver = null
        backdrop?.dispose()
        backdrop = null
        map?.dispose()
        map = null
        shapeButtons = null
        overlay?.remove()
        overlay = null
        viewsHost = null
        dock = null
        splitButton = null
        persist()
    }

    /**
     * The shell's tabs, windows or focus changed: show the right windows,
     * lay them out again and mark the focused one. Cheap when nothing
     * changed for 3D mode; a no-op when it is off.
     */
    fun onLayoutChanged() {
        if (!isActive || lib == null) return
        rebuildViews()
    }

    // ------------------------------------------------------------- views

    /** The windows to show right now: the focused one, or all of the tab's. */
    private fun shownPanes(): List<SpacePane> {
        val panes = host.spacePanes()
        if (isSplit) return panes
        val focused = host.focusedPaneId()
        val one = panes.firstOrNull { it.id == focused } ?: panes.firstOrNull() ?: return emptyList()
        return listOf(one.copy(x = 0.0, y = 0.0, w = 1.0, h = 1.0))
    }

    /** Builds views for windows newly shown, disposes those no longer shown, lays all out. */
    private fun rebuildViews() {
        val l = lib ?: return
        val vh = viewsHost ?: return
        if (shape.isMap) {
            if (map == null) showMap() else map?.onLayoutChanged()
            renderDock()
            val active = document.activeElement
            if (active == null || active === document.body) map?.focus()
            requestFrame()
            return
        }
        val shown = shownPanes()
        val ids = shown.map { it.id }.toSet()
        val focused = host.focusedPaneId()
        for ((id, v) in views.toList()) {
            if (id !in ids) {
                views.remove(id)
                v.dispose(focus = false)
            }
        }
        val fresh = mutableListOf<PageSpaceView>()
        for (p in shown) {
            if (p.id in views) continue
            val vm = host.viewModelOf(p.id) ?: continue
            val screen = host.screenOf(p.id) ?: continue
            val v = PageSpaceView(p.id, l, vm, screen, this, scope)
            vh.appendChild(v.element)
            views[p.id] = v
            fresh += v
        }
        vh.classList.toggle("is-single", views.size <= 1)
        layoutViews(shown)
        fresh.forEach { it.start() }
        views.values.forEach { it.renderBadges() }
        renderDock()
        // Put the keyboard in the focused window's live page, unless
        // something else (a modal, the palette, a field) holds it.
        val active = document.activeElement
        val focusedView = focused?.let { views[it] }
        if (focusedView != null && (active == null || active === document.body || overlay?.contains(active) != true && !isTextField(active))) {
            focusedView.focusEditor()
        }
        requestFrame()
    }

    /**
     * Sizes the background to the window and places each view at its
     * window's rectangle within the views area (the window below the
     * strip), as fractions of it — the floating-pane geometry, scaled up.
     */
    private fun layoutViews(shown: List<SpacePane> = shownPanes()) {
        val vh = viewsHost ?: return
        backdrop?.setSize(window.innerWidth, window.innerHeight)
        map?.let { it.place(it.element.clientWidth, it.element.clientHeight) }
        val aw = vh.clientWidth.toDouble()
        val ah = vh.clientHeight.toDouble()
        val gap = if (views.size > 1) 3.0 else 0.0
        for (p in shown) {
            val v = views[p.id] ?: continue
            val x = round(p.x * aw + gap).toInt()
            val y = round(p.y * ah + gap).toInt()
            val w = round(p.w * aw - 2 * gap).toInt()
            val h = round(p.h * ah - 2 * gap).toInt()
            v.place(max(0, x), max(0, y), max(1, min(w, aw.toInt())), max(1, min(h, ah.toInt())))
            v.element.style.zIndex = p.z.toString()
        }
        requestFrame()
    }

    /**
     * The strip along the top: drags the window (Electron) and holds the
     * leave button, since the topbar's cube is under the space.
     */
    private fun buildStrip(): HTMLElement {
        val strip = div("lunarbor-space-strip")
        val shapes = div("lunarbor-space-shapes")
        for (s in SpaceShape.entries) {
            val b = document.createElement("button") as HTMLElement
            b.className = "lunarbor-space-shape"
            b.textContent = s.label
            b.setAttribute("data-shape", s.name)
            b.title = "${s.label} (⌃⌘2 for the next shape)"
            b.addEventListener("click", { _ -> setShape(s) })
            shapes.appendChild(b)
        }
        strip.appendChild(shapes)
        shapeButtons = shapes
        updateShapeButtons()
        fun button(html: String, title: String, onClick: () -> Unit): HTMLElement {
            val b = document.createElement("button") as HTMLElement
            b.className = "lunarbor-space-button"
            b.innerHTML = html
            b.title = title
            b.addEventListener("click", { _ -> onClick() })
            strip.appendChild(b)
            return b
        }
        button("<span>Help</span>", "How 3D mode and each view work") { showSpaceHelp(shape) }
        button("<span>Palette</span><kbd>⌘P</kbd>", "Command palette (⌘P)") { host.openPalette() }
        splitButton = button("", "The focused window alone, or all of the tab's windows (⌃⌘1)") { toggleSplit() }
        splitButton?.classList?.add("lunarbor-space-split")
        updateSplitButton()
        val leave = document.createElement("button") as HTMLElement
        leave.className = "lunarbor-space-button"
        leave.innerHTML = ICON_LEAVE + "<span>Leave 3D</span><kbd>Esc</kbd>"
        leave.title = "Leave 3D mode (Esc, ⌃⌘3)"
        leave.addEventListener("click", { _ -> exit() })
        strip.appendChild(leave)
        return strip
    }

    /** Recolours the WebGL specks when the theme's variables change on `:root`. */
    private fun observeTheme() {
        backdrop?.applyTheme()
        darkTheme = readDarkTheme()
        val root = document.documentElement ?: return
        val callback: () -> Unit = {
            darkTheme = readDarkTheme()
            backdrop?.applyTheme()
            map?.applyTheme()
            requestFrame()
        }
        themeObserver = js("new MutationObserver(function() { callback(); })")
        themeObserver.observe(root, js("({ attributes: true, attributeFilter: ['style', 'class', 'data-theme'] })"))
    }

    // ----------------------------------------------------- render loop

    /**
     * Asks for frames until nothing moves (flights, sprouting, relayout).
     * A frame with nothing moving renders once and stops, so a writing
     * session costs nothing.
     */
    internal fun requestFrame() {
        if (!isActive || frameHandle != null) return
        lastFrame = window.performance.now()
        frameHandle = window.requestAnimationFrame { now -> frame(now) }
    }

    private fun frame(now: Double) {
        frameHandle = null
        if (!isActive) return
        val dt = min(0.05, max(0.0, (now - lastFrame) / 1000))
        lastFrame = now
        var moving = false
        if (shape.isMap) {
            if (map?.tick(dt) == true) moving = true
            if (moving) frameHandle = window.requestAnimationFrame { t -> frame(t) }
            return
        }
        val bd = backdrop
        bd?.beginFrame()
        for (v in views.values) {
            if (v.tick(dt)) moving = true
            bd?.renderView(v)
        }
        bd?.endFrame()
        overlay?.let { if (it.scrollTop != 0.0 || it.scrollLeft != 0.0) { it.scrollTop = 0.0; it.scrollLeft = 0.0 } }
        if (moving) {
            frameHandle = window.requestAnimationFrame { t -> frame(t) }
        }
    }

    // ------------------------------------------------------ view support

    /** Focuses [paneId]'s window (a click in its view). */
    internal fun focusPane(paneId: String) {
        if (host.focusedPaneId() == paneId) return
        host.focusPane(paneId)
        views.values.forEach { it.renderBadges() }
    }

    /** Redraws the dock (the map calls it when a window moved or loaded). */
    internal fun refreshDock() = renderDock()

    /** The active tab's windows, for the map's window cards. */
    internal fun mapPanes(): List<SpacePane> = host.spacePanes()

    /** See [SpaceHost.focusedPaneId]. */
    internal fun focusedPaneId(): String? = host.focusedPaneId()

    /** See [SpaceHost.viewModelOf]. */
    internal fun viewModelOf(paneId: String): MainViewModel? = host.viewModelOf(paneId)

    /** Focuses [paneId] from the map (a window card); the map redraws via [onLayoutChanged]. */
    internal fun focusPaneFromMap(paneId: String) {
        if (host.focusedPaneId() != paneId) host.focusPane(paneId)
        renderDock()
    }

    /** `true` when [paneId] is the tab's focused window. */
    internal fun isFocused(paneId: String): Boolean = views.size > 1 && host.focusedPaneId() == paneId

    /** See [SpaceHost.breadcrumbOf]. */
    internal fun breadcrumbOf(paneId: String): List<PaneTitleSegment> = host.breadcrumbOf(paneId)

    /**
     * The window badges for page [key]: `(label, focused)` for every window
     * of the tab on that page, in tab order.
     */
    internal fun badgesFor(key: String): List<Pair<String, Boolean>> {
        val panes = host.spacePanes()
        if (panes.size < 2) return emptyList()
        val focused = host.focusedPaneId()
        return panes.mapNotNull { p ->
            val onIt = views[p.id]?.page?.key ?: host.viewModelOf(p.id)?.let { vm -> vm.spacePageOf(vm.currentBackingState)?.key }
            if (onIt == key) p.label to (p.id == focused) else null
        }
    }

    /** A view moved to another page: every view's badges may change. */
    internal fun onViewNavigated() {
        views.values.forEach { it.renderBadges() }
        renderDock()
    }

    // ------------------------------------------------------------ helpers

    /**
     * `true` when this Escape belongs to something else: a modal, menu,
     * the palette, the link popup or 3D mode's help is open, an open pane search, or the
     * keyboard is in a text field (a modal's input, the search field).
     */
    private fun somethingElseOwnsEscape(): Boolean {
        if (document.querySelector(".dt-modal-backdrop, .dt-menu-backdrop, .lunarbor-palette-backdrop, .lunarbor-link-popup, .lunarbor-space-help-backdrop") != null) return true
        val active = document.activeElement
        if (active != null && isTextField(active)) return true
        val focused = host.focusedPaneId() ?: return false
        return host.screenOf(focused)?.isSearchOpen == true
    }

    private fun isTextField(el: org.w3c.dom.Element): Boolean {
        val tag = el.tagName.uppercase()
        return tag == "INPUT" || tag == "TEXTAREA" || tag == "SELECT"
    }

    private fun readDarkTheme(): Boolean {
        val bg = window.getComputedStyle(document.documentElement!!).getPropertyValue("--t-bg").trim().ifEmpty { "#1e1e1e" }
        return luminanceOfCss(bg) < 0.5
    }

    private fun persist() {
        val json = "{\"on\":$isActive,\"split\":$isSplit,\"shape\":\"${shape.name}\"}"
        scope.launch { persister.write(PERSIST_KEY, json) }
    }

    companion object {
        /** Persister key for `{ "on": Boolean, "split": Boolean, "shape": SpaceShape name }`. App state, not vault content. */
        const val PERSIST_KEY: String = "lunarborSpace"

        /** Persister key of [isSpaceModeEnabled] (`"true"` / `"false"`; absent = off). */
        const val ENABLED_KEY: String = "lunarborSpaceEnabled"

        /** The leave button's cube glyph. */
        private const val ICON_LEAVE: String =
            "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" " +
                "stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\">" +
                "<path d=\"M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z\"/>" +
                "<polyline points=\"3.27 6.96 12 12.01 20.73 6.96\"/><line x1=\"12\" y1=\"22.08\" x2=\"12\" y2=\"12\"/></svg>"
    }
}

/**
 * The space's background: one transparent WebGL canvas behind every view,
 * drawing a shared scene of distant stars and nearer dust with each view's
 * camera, viewport and scissor. The overlay's CSS gradient (from the
 * theme's background colours) shows through; the specks take the theme's
 * dim text colour — glowing on a dark space, dim on a pale one.
 */
private class Backdrop(
    private val lib: ThreeLib,
    private val renderer: WebGLRenderer3,
) {
    val canvas: HTMLCanvasElement get() = renderer.domElement
    private val scene = lib.scene()
    private val world = lib.group()
    private val materials = mutableListOf<Pair<PointsMaterial3, Boolean>>()
    private var width = 1
    private var height = 1

    init {
        scene.add(world)
        val sprite = dotSprite()
        val random = Lcg(0x5eed)
        val stars = FloatArray(STARS * 3)
        for (i in 0 until STARS) {
            stars[i * 3] = ((random.next() - 0.5) * 300_000).toFloat()
            stars[i * 3 + 1] = ((random.next() - 0.5) * 170_000).toFloat()
            stars[i * 3 + 2] = (-90_000 - random.next() * 90_000).toFloat()
        }
        val dust = FloatArray(DUST * 3)
        for (i in 0 until DUST) {
            dust[i * 3] = ((random.next() - 0.5) * 30_000).toFloat()
            dust[i * 3 + 1] = ((random.next() - 0.5) * 18_000).toFloat()
            dust[i * 3 + 2] = (5_000 - random.next() * 60_000).toFloat()
        }
        // 3D mode's own colours: a share of the specks are tinted, the rest white.
        fun tints(n: Int): FloatArray {
            val c = FloatArray(n * 3)
            for (i in 0 until n) {
                val tinted = random.next() < 0.45
                val hue = random.next()
                val (r, g, b) = if (tinted) hslToRgb(hue, 0.85, 0.68) else Triple(1.0, 1.0, 1.0)
                c[i * 3] = r.toFloat(); c[i * 3 + 1] = g.toFloat(); c[i * 3 + 2] = b.toFloat()
            }
            return c
        }
        val (starPoints, starMat) = lib.points(stars, 2.2, attenuate = false, sprite = sprite, colors = tints(STARS))
        val (dustPoints, dustMat) = lib.points(dust, 16.0, attenuate = true, sprite = sprite, colors = tints(DUST))
        starPoints.frustumCulled = false
        dustPoints.frustumCulled = false
        world.add(starPoints)
        world.add(dustPoints)
        materials += starMat to true
        materials += dustMat to false
        renderer.autoClear = false
        renderer.setClearColor(0, 0.0)
        renderer.setPixelRatio(window.devicePixelRatio)
        canvas.className = "lunarbor-space-canvas"
    }

    /** Sizes the canvas to the overlay. */
    fun setSize(w: Int, h: Int) {
        if (w == width && h == height) return
        width = max(1, w)
        height = max(1, h)
        renderer.setSize(width, height)
    }

    /** Re-reads the theme: speck colour, and glow (dark) or ink (light). */
    fun applyTheme() {
        val css = window.getComputedStyle(document.documentElement!!)
        val dim = css.getPropertyValue("--t-text-dim").trim().ifEmpty { "#8a8f9c" }
        val bg = css.getPropertyValue("--t-bg").trim().ifEmpty { "#1e1e1e" }
        val dark = luminanceOf(bg) < 0.5
        for ((m, isStar) in materials) {
            // White on a dark space lets the tints show; the dim ink on a pale one.
            m.color.setStyle(if (dark) "#ffffff" else normalizeColor(dim))
            m.blending = if (dark) lib.additiveBlending else lib.normalBlending
            m.opacity = when {
                dark && isStar -> 0.85
                dark -> 0.4
                isStar -> 0.4
                else -> 0.22
            }
            m.needsUpdate = true
        }
    }

    fun beginFrame() {
        renderer.setScissorTest(false)
        renderer.setViewport(0, 0, width, height)
        renderer.clear()
        renderer.setScissorTest(true)
    }

    /** Draws the background into [view]'s rectangle with its camera. */
    fun renderView(view: PageSpaceView) {
        view.applyBackdropOffset(world)
        val gy = height - (view.y + view.h)
        renderer.setViewport(view.x, gy, view.w, view.h)
        renderer.setScissor(view.x, gy, view.w, view.h)
        renderer.render(scene, view.camera)
    }

    fun endFrame() {
        renderer.setScissorTest(false)
    }

    fun dispose() {
        renderer.dispose()
        canvas.remove()
    }

    /** A soft round dot, white, for the sprites (tinted by the material). */
    private fun dotSprite(): HTMLCanvasElement {
        val c = document.createElement("canvas") as HTMLCanvasElement
        c.width = 64
        c.height = 64
        val ctx = c.getContext("2d") as CanvasRenderingContext2D
        val g = ctx.createRadialGradient(32.0, 32.0, 0.0, 32.0, 32.0, 32.0)
        g.addColorStop(0.0, "rgba(255,255,255,1)")
        g.addColorStop(0.35, "rgba(255,255,255,0.55)")
        g.addColorStop(1.0, "rgba(255,255,255,0)")
        ctx.fillStyle = g
        ctx.fillRect(0.0, 0.0, 64.0, 64.0)
        return c
    }

    /** A tiny deterministic generator, so the starfield is the same every time. */
    private class Lcg(seed: Int) {
        private var s = seed.toLong() and 0xffffffffL
        fun next(): Double {
            s = (s * 1664525L + 1013904223L) and 0xffffffffL
            return s.toDouble() / 4294967296.0
        }
    }

    companion object {
        const val STARS = 2600
        const val DUST = 1500

        /** The backdrop, or `null` when WebGL is unavailable (the CSS gradient still shows). */
        fun create(lib: ThreeLib): Backdrop? = lib.webGLRenderer()?.let { Backdrop(lib, it) }

        /** Any CSS colour as the browser's `#rrggbb` / `rgba(…)` form (three.js parses both). */
        fun normalizeColor(css: String): String {
            val c = document.createElement("canvas") as HTMLCanvasElement
            val ctx = c.getContext("2d") as CanvasRenderingContext2D
            ctx.fillStyle = "#000"
            ctx.fillStyle = css
            val out = ctx.fillStyle as String
            // three.js warns on alpha; drop it.
            return Regex("""rgba\((\d+),\s*(\d+),\s*(\d+),[^)]*\)""").replace(out) { m ->
                "rgb(${m.groupValues[1]}, ${m.groupValues[2]}, ${m.groupValues[3]})"
            }
        }

        /** HSL (all 0..1) to linear-ish RGB (0..1), for the tinted specks. */
        fun hslToRgb(h: Double, s: Double, l: Double): Triple<Double, Double, Double> {
            val q = if (l < 0.5) l * (1 + s) else l + s - l * s
            val p = 2 * l - q
            fun ch(t0: Double): Double {
                var t = t0
                if (t < 0) t += 1.0
                if (t > 1) t -= 1.0
                return when {
                    t < 1.0 / 6 -> p + (q - p) * 6 * t
                    t < 0.5 -> q
                    t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                    else -> p
                }
            }
            return Triple(ch(h + 1.0 / 3), ch(h), ch(h - 1.0 / 3))
        }

        /** Relative luminance (0..1) of a CSS colour. */
        fun luminanceOf(css: String): Double {
            val norm = normalizeColor(css)
            val hex = Regex("^#([0-9a-fA-F]{6})$").find(norm)?.groupValues?.get(1)
            val rgb = if (hex != null) {
                listOf(hex.substring(0, 2), hex.substring(2, 4), hex.substring(4, 6)).map { it.toInt(16).toDouble() }
            } else {
                Regex("""[\d.]+""").findAll(norm).map { it.value.toDouble() }.take(3).toList()
            }
            if (rgb.size < 3) return 0.0
            return (0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]) / 255.0
        }
    }
}

/** See `Backdrop.normalizeColor`: any CSS colour as `#rrggbb` / `rgb(…)`. Used by [MapView]. */
internal fun normalizeCssColor(css: String): String = Backdrop.normalizeColor(css)

/** See `Backdrop.luminanceOf`: relative luminance (0..1) of a CSS colour. Used by [MapView]. */
internal fun luminanceOfCss(css: String): Double = Backdrop.luminanceOf(css)

/** Injects 3D mode's stylesheet once. Colours and fonts all come from the theme's variables. */
private fun ensureSpaceStyles() {
    if (document.getElementById("lunarbor-space-styles") != null) return
    val style = document.createElement("style") as HTMLElement
    style.id = "lunarbor-space-styles"
    style.textContent = SPACE_CSS + MAP_CSS
    document.head?.appendChild(style)
}

private val SPACE_CSS = """
body[data-lunarbor-space] .dt-pane-root { visibility: hidden; }
.lunarbor-space {
    position: fixed; inset: 0; z-index: 900; overflow: hidden; contain: strict;
    background-color: var(--t-bg, #1e1e1e);
    background-image:
        radial-gradient(60% 45% at 50% 28%, color-mix(in srgb, var(--t-accent, #7aa2ff) 7%, transparent), transparent 70%),
        radial-gradient(130% 100% at 50% 35%, var(--t-bg, #1e1e1e), color-mix(in srgb, var(--t-canvas, var(--t-bg, #1e1e1e)) 96%, var(--t-text, #e6e6e6) 4%));
    animation: lunarbor-space-in 160ms ease;
}
@keyframes lunarbor-space-in { from { opacity: 0; } }
@media (prefers-reduced-motion: reduce) { .lunarbor-space { animation: none; } }
.lunarbor-space-backdrop { position: absolute; inset: 0; }
.lunarbor-space-views { position: absolute; inset: 36px 8px 48px 8px; }
.lunarbor-space-strip {
    position: absolute; top: 0; left: 0; right: 0; height: 36px;
    display: flex; align-items: center; justify-content: flex-end; padding: 0 10px;
    -webkit-app-region: drag;
}
.lunarbor-space-strip { gap: 6px; }
.lunarbor-space-button {
    -webkit-app-region: no-drag; display: inline-flex; align-items: center; gap: 6px;
    padding: 4px 8px; border-radius: 7px; border: 1px solid var(--t-border, rgba(255,255,255,.12));
    background: color-mix(in srgb, var(--t-surface, #252526) 70%, transparent);
    color: var(--t-text-dim, #9aa0a6); font: 12px var(--dt-font-prop, system-ui, sans-serif); cursor: pointer;
}
.lunarbor-space-button:hover, .lunarbor-space-button.is-on { color: var(--t-text, #e6e6e6); border-color: var(--t-accent, #7aa2ff); }
.lunarbor-space-button kbd { font: inherit; font-size: 10.5px; opacity: .7; }
.lunarbor-space-dock {
    position: absolute; left: 8px; right: 8px; bottom: 6px; height: 36px;
    display: flex; align-items: center; gap: 10px; padding: 0 4px; overflow: hidden;
    font: 13px var(--dt-font-prop, system-ui, sans-serif);
}
.lunarbor-space-tabs { display: flex; gap: 3px; min-width: 0; overflow: hidden; }
.lunarbor-space-tab {
    border: 0; background: none; border-radius: 7px; padding: 5px 10px; cursor: pointer;
    display: flex; gap: 7px; align-items: center; font: inherit; font-weight: 500; color: var(--t-text-dim, #9aa0a6); white-space: nowrap;
}
.lunarbor-space-tab:hover { color: var(--t-text, #e6e6e6); }
.lunarbor-space-tab[aria-pressed="true"] {
    color: var(--t-text, #e6e6e6); background: color-mix(in srgb, var(--t-surface, #252526) 80%, transparent);
    box-shadow: inset 0 0 0 1px var(--t-border, rgba(255,255,255,.12));
}
.lunarbor-space-pips { display: flex; gap: 2px; }
.lunarbor-space-pips i { width: 5px; height: 5px; border-radius: 1.5px; background: currentColor; opacity: .55; }
.lunarbor-space-tab-key { font-size: 10px; opacity: .6; }
.lunarbor-space-chips { display: flex; gap: 4px; margin-left: auto; align-items: center; min-width: 0; overflow: hidden; }
.lunarbor-space-chip {
    border: 1px solid var(--t-border, rgba(255,255,255,.12)); background: none; border-radius: 6px; padding: 3px 8px;
    font: inherit; font-size: 12px; cursor: pointer; color: var(--t-text-dim, #9aa0a6);
    max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.lunarbor-space-chip:hover { color: var(--t-text, #e6e6e6); }
.lunarbor-space-chip.is-focused { color: var(--t-text, #e6e6e6); border-color: var(--t-accent, #7aa2ff); }
.lunarbor-space-chip.is-add { border-style: dashed; }
.lunarbor-space-backdrop { pointer-events: none; }
.lunarbor-space-canvas { display: block; width: 100%; height: 100%; }
.lunarbor-space-view { position: absolute; overflow: hidden; border-radius: 10px; }
.lunarbor-space-views:not(.is-single) .lunarbor-space-view { box-shadow: inset 0 0 0 1px var(--t-border, rgba(255,255,255,.12)); }
.lunarbor-space-css3d { position: absolute; inset: 0; }
.lunarbor-space-threads { position: absolute; inset: 0; width: 100%; height: 100%; pointer-events: none; overflow: visible; }
.lunarbor-space-threads { color: var(--t-accent, #7aa2ff); }
.lunarbor-space-thread { fill: none; stroke: currentColor; stroke-opacity: .75; stroke-width: 1.5; }
.lunarbor-space-thread-end { fill: currentColor; }
/* A page's slot is page-sized and transparent (the CSS3D object); its card
   is as tall as its content, at most the slot. Only the card takes the pointer. */
.lunarbor-space-slot { display: flex; flex-direction: column; align-items: stretch; pointer-events: none; }
.lunarbor-space-page {
    pointer-events: auto; flex: 0 1 auto; max-height: 100%; min-height: 0;
    display: flex; flex-direction: column; overflow: hidden; box-sizing: border-box;
    background: var(--t-surface, #252526); color: var(--t-text, #e6e6e6);
    border: 1px solid var(--t-border, rgba(255,255,255,.12)); border-radius: 10px;
    box-shadow: 0 18px 50px rgba(0,0,0,.28);
    font-family: var(--dt-font-prop, system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif); cursor: pointer; user-select: none;
}
.lunarbor-space-page.is-live {
    background: var(--t-bg, #1e1e1e); cursor: auto; user-select: auto;
    border-color: var(--t-accent-soft, var(--t-border, rgba(255,255,255,.2)));
}
.lunarbor-space-views:not(.is-single) .lunarbor-space-page.is-live.is-focused {
    border-color: var(--t-accent, #7aa2ff);
    box-shadow: 0 0 0 1px var(--t-accent, #7aa2ff), 0 18px 50px rgba(0,0,0,.28);
}
.lunarbor-space-head {
    flex: none; display: flex; align-items: center; gap: 6px; min-width: 0;
    padding: 6px 12px; border-bottom: 1px solid var(--t-border, rgba(255,255,255,.12));
    font-size: 12px; color: var(--t-text-dim, #9aa0a6);
}
.lunarbor-space-crumbs { flex: 1; min-width: 0; display: flex; align-items: center; gap: 4px; overflow: hidden; white-space: nowrap; }
.lunarbor-space-crumb { overflow: hidden; text-overflow: ellipsis; }
.lunarbor-space-crumb.is-link { cursor: pointer; }
.lunarbor-space-crumb.is-link:hover { color: var(--t-text, #e6e6e6); text-decoration: underline; }
.lunarbor-space-crumb:last-child { color: var(--t-text, #e6e6e6); }
.lunarbor-space-crumb-sep { opacity: .6; }
.lunarbor-space-nav {
    flex: none; width: 22px; height: 22px; padding: 0; border: 0; border-radius: 5px;
    background: transparent; color: var(--t-text-dim, #9aa0a6); font-size: 16px; line-height: 1; cursor: pointer;
}
.lunarbor-space-nav:hover:not([disabled]) { background: var(--t-surface-alt, rgba(255,255,255,.08)); color: var(--t-text, #e6e6e6); }
.lunarbor-space-nav[disabled] { opacity: .35; cursor: default; }
.lunarbor-space-badges { flex: none; display: flex; gap: 4px; }
.lunarbor-space-badge {
    font-size: 10.5px; padding: 1px 6px; border-radius: 4px;
    border: 1px solid var(--t-border, rgba(255,255,255,.12)); color: var(--t-text-dim, #9aa0a6);
}
.lunarbor-space-badge.is-focused { border-color: var(--t-accent, #7aa2ff); color: var(--t-accent, #7aa2ff); }
.lunarbor-space-preview-title { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--t-text, #e6e6e6); font-weight: 600; font-size: 13px; }
.lunarbor-space-preview-body { flex: 1; min-height: 0; overflow: hidden; padding: 14px 22px; font-size: 14px; line-height: 1.55; }
.lunarbor-space-preview-empty { color: var(--t-text-dim, #9aa0a6); }
.lunarbor-space-item { display: flex; align-items: baseline; gap: 10px; min-width: 0; }
.lunarbor-space-item-dot {
    flex: none; width: 6px; height: 6px; border-radius: 50%; transform: translateY(-2px);
    background: var(--t-text-dim, #9aa0a6);
}
.lunarbor-space-item-dot.is-node { box-shadow: 0 0 0 3px color-mix(in srgb, var(--t-text-dim, #9aa0a6) 35%, transparent); }
.lunarbor-space-item-dot:not(.is-inert):hover { background: var(--t-accent, #7aa2ff); }
.lunarbor-space-item-text { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.lunarbor-space-page { border-top: 3px solid var(--lb-area, var(--t-border, rgba(255,255,255,.12))); }
.lunarbor-space-page[style*="--lb-area"] { box-shadow: 0 0 0 1px color-mix(in srgb, var(--lb-area) 35%, transparent), 0 18px 50px rgba(0,0,0,.28), 0 0 42px color-mix(in srgb, var(--lb-area) 22%, transparent); }
.lunarbor-space-page[style*="--lb-area"] .lunarbor-space-preview-title { color: color-mix(in srgb, var(--lb-area) 55%, var(--t-text, #e6e6e6)); }
.lunarbor-space-page .lunarbor-space-item-dot { background: var(--lb-area, var(--t-text-dim, #9aa0a6)); }
.lunarbor-space-page .lunarbor-space-item-dot.is-node { box-shadow: 0 0 0 3px color-mix(in srgb, var(--lb-area, var(--t-text-dim, #9aa0a6)) 35%, transparent); }
.lunarbor-space-page.is-live.is-fill { flex: 1 1 auto; }
.lunarbor-space-live-body { flex: 0 1 auto; min-height: 0; display: flex; flex-direction: column; }
.lunarbor-space-page.is-fill .lunarbor-space-live-body { flex: 1 1 auto; }
.lunarbor-space-live-host { flex: 1 1 auto; min-height: 0; height: auto !important; }
.lunarbor-space-live-host > .lunarbor-scroll { flex: 0 1 auto !important; }
.lunarbor-space-page.is-fill .lunarbor-space-live-host > .lunarbor-scroll { flex: 1 1 auto !important; }
"""
