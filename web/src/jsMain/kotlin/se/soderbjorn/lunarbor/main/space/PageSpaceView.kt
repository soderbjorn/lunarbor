/*
 * PageSpaceView.kt (jsMain)
 * -------------------------
 * One window's view into 3D mode's page space (LBR-11, "Pages"): its own
 * camera, its own CSS3D renderer, the pages it shows and the threads
 * between them. `SpaceMode` makes one per shown window and drives them all
 * from one render loop.
 *
 * What a view shows:
 *  - **The live page** — the window's real `MainScreen` (title, editor,
 *    search, folder contents, drawings, images, web pages), reparented into
 *    a CSS3D object at exactly 1:1 in front of the camera. It sits wherever
 *    the window is: on navigation it moves to the new page's place at once,
 *    so typing reaches the new page from the start of the flight.
 *  - **Previews** — read-only outlines of the child and grandchild pages
 *    (`PageSpaceModel`, through `PaneBackingViewModel.spacePageOf`), and of
 *    the page left behind while the camera flies away from it. Clicking one,
 *    or a dot in it, zooms the window there. The rest of the vault hangs
 *    round them as free flight lays it out ([aroundPlaces]: siblings,
 *    other branches), once every listing has landed — never a page that
 *    would show over the window's own ([staysBehind]). Only the
 *    [NAV_FULL] nearest (children first) show their bullets, the next
 *    [NAV_SLABS] are title cards, the rest [FarPages].
 *  - **Threads** — an SVG overlay drawing a curve from each child bullet's
 *    dot on the live page to that child's page.
 *  - **Colour** — every page wears its area's hue (`SpaceMode.areaColor`,
 *    3D mode's own palette): a coloured top edge and glow, item dots, and
 *    the thread leading to it.
 *
 * The camera follows the pane's state: when `(activeFileRel, zoomedLineId)`
 * changes to another page, the view flies. Editing never moves it.
 *
 * **Free flight** ([PageFlight], ⌥⌘F or ⌃⌘4, or the strip's Fly button): the
 * camera becomes the maps' spaceship and may turn; landing (F, C, ⌥⌘F)
 * flies it back, turning upright again, to face the window's page, and
 * Enter (or a click) opens the page ahead, whose border is highlighted
 * (`is-aimed`). While flying the view paints the **whole vault** by
 * Pages' own rules ([PageSpaceLayout.wholeLayout], anchored at the
 * window's page): the [FLIGHT_FULL] pages nearest the ship as previews,
 * the next [FLIGHT_SLABS] as title cards, every other page as a small
 * card without text drawn in WebGL behind the pages ([FarPages], through
 * [backdropExtra]) — re-ranked as the ship moves, with some slack
 * ([KEEP_FACTOR]) so pages near the cut do not flicker between the three,
 * and previews that leave kept with their DOM ([PARK_CAP]). After a pick the
 * camera holds where the ship stopped until the navigation arrives, so
 * the flight there starts from the ship. Page
 * places come from `PageSpaceLayout` (commonMain), relative to the page the
 * window is on; places a view has used are remembered, so Back flies back
 * to exactly where the window was.
 *
 * Hard-won rules (from the prototype):
 *  - The three.js camera never moves; the world group moves the opposite
 *    way. Chrome stops hit-testing a CSS3D layer whose camera has moved
 *    behind the viewer.
 *  - Chrome hit-tests CSS3D siblings in DOM order, not depth: every page
 *    gets a z-index by its distance rank, the live page the highest, and pages
 *    behind the camera ignore the pointer.
 *  - A new page object is positioned and rendered once before anything in
 *    it takes focus (elements attach on render).
 *  - Every frame resets stray `scrollTop` / `scrollLeft` a focused caret
 *    puts on the `overflow: hidden` containers.
 *  - Frame rate: the cards are nearly as tall as the view and a flight moves
 *    the camera mostly in depth, so each card's size on screen changes every
 *    frame. While anything moves the previews keep their raster
 *    (`is-moving` → `will-change: transform`), and a preview the camera
 *    comes close to fades out and hides ([NEAR_FADE], [NEAR_HIDE]).
 *
 * jsMain only: DOM and three.js. No document logic — intents go to the
 * pane's `MainViewModel`.
 */

package se.soderbjorn.lunarbor.main.space

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.MouseEvent
import se.soderbjorn.lunarbor.data.LunarborLink
import se.soderbjorn.lunarbor.main.GroveLayout
import se.soderbjorn.lunarbor.main.LineId
import se.soderbjorn.lunarbor.main.MainScreen
import se.soderbjorn.lunarbor.main.MainViewModel
import se.soderbjorn.lunarbor.main.PageSpaceGeometry
import se.soderbjorn.lunarbor.main.PageSpaceKeys
import se.soderbjorn.lunarbor.main.PageSpaceLayout
import se.soderbjorn.lunarbor.main.PaneBackingViewModel
import se.soderbjorn.lunarbor.main.SpaceChild
import se.soderbjorn.lunarbor.main.SpaceItem
import se.soderbjorn.lunarbor.main.SpacePalette
import se.soderbjorn.lunarbor.main.SpacePage
import se.soderbjorn.lunarbor.main.SpacePose
import se.soderbjorn.lunarbor.main.SpaceQuat
import se.soderbjorn.lunarbor.main.SpaceVec
import se.soderbjorn.lunarbor.main.VaultGraph
import se.soderbjorn.lunarbor.main.space.three.Camera3
import se.soderbjorn.lunarbor.main.space.three.Css3DRenderer3
import se.soderbjorn.lunarbor.main.space.three.Object3
import se.soderbjorn.lunarbor.main.space.three.ThreeLib
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/**
 * One window's view into the page space.
 *
 * ### Callers
 * - Built and disposed by [SpaceMode] as views are shown and hidden.
 * - [SpaceMode]'s render loop calls [tick] every frame while anything moves.
 * - [SpaceMode.layoutViews] calls [place] when the view's rectangle changes.
 *
 * @param paneId The toolkit pane (window) this view shows.
 * @param lib The loaded three.js library.
 * @param vm The pane's view model: state to follow, intents to send.
 * @param screen The pane's editor view, mounted on the live page.
 * @param mode The owning mode: render loop, focus.
 * @param scope Scope for the state collectors; cancelled by [dispose].
 */
internal class PageSpaceView(
    override val paneId: String,
    private val lib: ThreeLib,
    private val vm: MainViewModel,
    private val screen: MainScreen,
    private val mode: SpaceMode,
    private val scope: CoroutineScope,
) : SpaceWindowView {
    /** The view's box in the space overlay, positioned by [place]. */
    override val element: HTMLElement = div("lunarbor-space-view")

    private val cssRenderer: Css3DRenderer3 = lib.css3dRenderer()
    private val scene: Object3 = lib.scene()

    /** Everything placed in the view; moved opposite to the logical camera. */
    private val world: Object3 = lib.group()

    /** The three.js camera — fixed at `(0, 0, cameraDistance)`, see the file header. */
    override val camera: Camera3 = lib.perspectiveCamera(PageSpaceLayout.FOV_DEGREES, 1.0, 1.0, 400_000.0)

    private val threads = document.createElementNS(SVG_NS, "svg") as org.w3c.dom.Element

    /** The view's rectangle inside the overlay, in CSS pixels. */
    override var x = 0; private set
    override var y = 0; private set
    override var w = 1; private set
    override var h = 1; private set

    /** Page and camera sizes for the current rectangle. */
    var geometry: PageSpaceGeometry = PageSpaceLayout.geometry(1.0, 1.0); private set

    /** Where the camera logically is (the world moves by its negation). */
    private var cam: SpaceVec = SpaceVec.ZERO

    /** How the camera is turned: upright ([SpaceQuat.IDENTITY]) except in and after free flight. */
    private var rot: SpaceQuat = SpaceQuat.IDENTITY

    /** Free flight (⌥⌘F): while on, the ship owns the camera. */
    private val freeFlight = PageFlight(element, onLand = { land() }, onEngage = { engage() }, requestFrame = { mode.requestFrame() })

    /**
     * Every page's place while flying (and until the flight back ends), by
     * [PageSpaceLayout.wholeLayout]; empty otherwise.
     */
    private var flightPlaces: Map<String, SpaceVec> = emptyMap()

    /** The vault graph [flightPlaces] was laid out from. */
    private var flightGraph: VaultGraph? = null

    /** Seconds since the flight's previews were last re-ranked. */
    private var sinceFlightSync = 0.0

    /** The pages too far for the DOM, drawn in WebGL. */
    private val far = FarPages()

    /**
     * `performance.now()` until which the camera holds still after a pick
     * in free flight, waiting for the navigation; 0 when not holding.
     */
    private var holdUntil = 0.0

    /** The card whose border is highlighted as the page ahead. */
    private var aimedEl: HTMLElement? = null

    /** The flight in progress, or `null` at rest. */
    private var flight: Flight? = null

    /** The page the window is on, or `null` until its first state has loaded. */
    override var page: SpacePage? = null; private set

    /** `(activeFileRel, zoomedLineId)` of [page]: a change is a navigation. */
    private var signature: Pair<String, LineId?>? = null

    /** Every place this view has hung a page, by key — Back returns there. */
    private val placed = HashMap<String, SpaceVec>()

    /** Where the pages around [page] belong now ([PageSpaceLayout.layout]). */
    private var targets: Map<String, SpaceVec> = emptyMap()

    /** The window's live page. */
    private val live = LivePage()

    /** Preview pages by key: children, grandchildren and a page being left. */
    private val previews = HashMap<String, PreviewPage>()

    /** Free flight: previews off the scene, kept with their DOM ([park]). */
    private val parked = LinkedHashMap<String, PreviewPage>()

    /**
     * Free flight: where the ship took off, until it first moves or turns —
     * meanwhile [flightSync] keeps the view as it was ([syncAround]).
     */
    private var takeOffAt: SpaceVec? = null

    /** Free flight: the near set [far] was last drawn for. */
    private var farFor: Set<String>? = null

    /** The page being flown away from, kept as a preview until the flight ends. */
    private var leaving: String? = null

    private val jobs = mutableListOf<Job>()

    /** Pending `setTimeout` of a coalesced [refresh] (typing, listings landing). */
    private var refreshHandle: Int? = null

    private val scrollListener: (Event) -> Unit = { mode.requestFrame() }

    init {
        cssRenderer.domElement.className = "lunarbor-space-css3d"
        threads.setAttribute("class", "lunarbor-space-threads")
        threads.setAttribute("aria-hidden", "true")
        element.appendChild(cssRenderer.domElement)
        element.appendChild(threads)
        scene.add(world)
        world.add(live.obj)
        // Clicking anywhere in the view focuses its window (capture phase,
        // before the editor or a preview handles the press).
        element.addEventListener("mousedown", { e ->
            if (freeFlight.isOn) {
                if (live.slot.contains(e.target as? org.w3c.dom.Node)) {
                    // The window's own page: no caret, no edit — just land.
                    e.preventDefault()
                    e.stopPropagation()
                    land()
                    return@addEventListener
                }
                // Elsewhere the ship stops and the camera holds: a click on a
                // page then flies there from here; anything else lands.
                freeFlight.stop()
                hold(CLICK_HOLD_MS)
            }
            mode.focusPane(paneId)
        }, true)
        element.addEventListener("focusin", { _ -> mode.focusPane(paneId) })
    }

    /**
     * Mounts the window's editor on the live page and starts following the
     * pane. Call once, after the view's element is in the document and
     * [place]d.
     */
    override fun start() {
        val now = vm.currentBackingState
        page = vm.spacePageOf(now)
        signature = now.activeFileRel to now.zoomedLineId
        val origin = SpaceVec.ZERO
        page?.let { p ->
            placed[p.key] = origin
            targets = PageSpaceLayout.layout(p.tree(), geometry, origin)
            placed.putAll(targets)
        }
        cam = PageSpaceLayout.cameraFor(origin, geometry)
        live.obj.position.set(origin.x, origin.y, origin.z)
        renderLiveHead()
        syncPreviews(sprout = false)
        // Attach the live page before the editor moves in, so it can keep focus.
        renderNow()
        screen.mountInSpace(live.host)
        screen.scrollElement?.addEventListener("scroll", scrollListener)
        jobs += scope.launch {
            vm.stateFlow.collect { st -> st.backingState?.let { onState(it) } }
        }
        jobs += scope.launch {
            mode.linkPreviewsFlow.collect { scheduleRefresh() }
        }
        mode.requestFrame()
    }

    /**
     * Gives the editor back to its pane and frees the view. With [focus],
     * the pane's editor takes the keyboard (the focused window only).
     */
    override fun dispose(focus: Boolean) {
        freeFlight.dispose()
        far.dispose()
        jobs.forEach { it.cancel() }
        jobs.clear()
        refreshHandle?.let { window.clearTimeout(it) }
        screen.scrollElement?.removeEventListener("scroll", scrollListener)
        screen.leaveSpace(focus)
        element.remove()
    }

    /** Puts the keyboard in the live page's editor, caret where the pane has it. */
    override fun focusEditor() = screen.focusEditorAtCaret()

    // ------------------------------------------------------- free flight

    override val roundBackdrop: Boolean get() = freeFlight.isOn || rot != SpaceQuat.IDENTITY

    override fun toggleFlight() {
        if (freeFlight.isOn) {
            land()
            return
        }
        flight = null
        holdUntil = 0.0
        freeFlight.takeOff(cam, rot.rotate(SpaceVec(0.0, 0.0, -1.0)), rot.rotate(SpaceVec(0.0, 1.0, 0.0)))
        takeOffAt = cam
        flightSync()
        mode.requestFrame()
    }

    /** The far pages' rectangles while there are any, drawn with the starfield. */
    override val backdropExtra: Object3? get() = far.objectIfShown

    /**
     * Lands: the ship stops and the camera flies back, turning upright,
     * to face the window's page — the one it is zoomed to now.
     */
    private fun land() {
        if (!freeFlight.isOn) return
        freeFlight.stop()
        returnFlight()
    }

    /** Flies the camera from wherever it is back to face the window's page, upright. */
    private fun returnFlight() {
        holdUntil = 0.0
        markAimed(null)
        val at = page?.let { placed[it.key] } ?: SpaceVec.ZERO
        val goal = PageSpaceLayout.cameraFor(at, geometry)
        val dist = distance(cam, goal)
        if (prefersReducedMotion() || dist < 1) {
            cam = goal
            rot = SpaceQuat.IDENTITY
            endFlight()
        } else {
            val mid = SpaceVec((cam.x + goal.x) / 2, (cam.y + goal.y) / 2, (cam.z + goal.z) / 2 + dist * 0.3)
            flight = Flight(cam, goal, mid, 0.0, PageSpaceLayout.flightSeconds(dist), rot)
        }
        screen.focusEditorAtCaret()
        mode.requestFrame()
    }

    /** Holds the camera where it is for [ms] (a pick waiting for its navigation). */
    private fun hold(ms: Int) {
        flight = null
        markAimed(null)
        holdUntil = window.performance.now() + ms
        mode.requestFrame()
    }

    /**
     * Enter while flying: opens the page ahead in the window (the flight
     * there starts from the ship), or lands when that is the window's own
     * page or there is none.
     */
    private fun engage() {
        val key = freeFlight.target
        val pv = key?.let { previews[it] }
        if (pv == null || key == page?.key || key == leaving || (pv.lineId == null && pv.folderRel == null)) {
            land()
            return
        }
        freeFlight.stop()
        hold(NAV_HOLD_MS)
        // The flight lands on the page where it was seen.
        placed[key] = pv.cur
        goTo(pv.lineId, pv.folderRel)
        screen.focusEditorAtCaret()
    }

    /**
     * Re-ranks the whole vault round the ship ([flightPlaces], laid out
     * again when the vault graph changed): the [FLIGHT_FULL] pages nearest
     * the camera become previews with their bullets, the next
     * [FLIGHT_SLABS] title cards, and all others far rectangles.
     */
    private fun flightSync() {
        val p = page ?: return
        val graph = mode.pagesGraph()
        if (graph !== flightGraph || flightPlaces.isEmpty()) {
            flightGraph = graph
            val at = placed[p.key] ?: SpaceVec.ZERO
            flightPlaces = PageSpaceLayout.wholeLayout(GroveLayout.treeOf(graph, p), p.key, at, geometry)
        }
        // Until the ship moves, the view stays exactly as it was before take-off.
        takeOffAt?.let { at ->
            if (distance(cam, at) < 2 && kotlin.math.abs(rot.w) > 0.99999) {
                syncAround(sprout = false)
                return
            }
            takeOffAt = null
            // syncAround drew its own far cards: draw the flight's.
            farFor = null
        }
        val eye = cam
        val ranked = flightPlaces.entries
            .filter { it.key != p.key && it.key != leaving }
            .map { it.key to it.value }
            .sortedBy { (_, at) -> distance(at, eye) }
        val byKey = HashMap<String, SpaceChild>()
        for (c in p.children) {
            byKey[c.key] = c
            for (gc in c.children) byKey.getOrPut(gc.key) { gc }
        }
        // Hysteresis: a page already shown stays (and a full one stays full)
        // until it falls well past the cut, so pages near the boundary do
        // not pop between card, title card and rectangle on every re-rank —
        // from a distance many pages lie at nearly the same range.
        val budget = FLIGHT_FULL + FLIGHT_SLABS
        val near = LinkedHashSet<String>()
        ranked.forEachIndexed { i, (key, at) ->
            val shown = previews[key]?.inScene == true
            if (i >= budget && !(shown && i < budget * KEEP_FACTOR)) return@forEachIndexed
            near += key
            var pv = previews[key]
            if (pv == null) {
                pv = parked.remove(key) ?: PreviewPage(key)
                previews[key] = pv
            }
            val wasFull = shown && !pv.card.classList.contains("is-slab")
            val full = i < FLIGHT_FULL || (wasFull && i < FLIGHT_FULL * KEEP_FACTOR)
            val child = byKey[key]
            val folder = child?.folderRel ?: folderOfKey(key)
            pv.lineId = child?.lineId
            pv.folderRel = folder
            pv.depth = if (full) 1 else 2
            val title = child?.title ?: folder?.let { graph.nodes[it]?.title } ?: ""
            if (full) {
                pv.fill(title, child?.items ?: folder?.let { mode.pageItems(it) }.orEmpty(), child)
            } else if (!pv.isFilled) {
                pv.fill(title, emptyList(), null)
            }
            // A title card: header and title only, no glow (cheap to draw in
            // numbers). Bullets it already has stay, hidden, for when it is
            // full again.
            pv.card.classList.toggle("is-slab", !full)
            if (!pv.inScene) {
                pv.cur = targets[key] ?: at
                pv.scale = 1.0
                addToScene(pv)
            }
        }
        for ((key, pv) in previews.toList()) {
            if (key in near || key == leaving) continue
            if (pv.inScene) removeFromScene(pv)
            previews.remove(key)
            park(key, pv)
        }
        // The far rectangles are everything else: redrawn only when that set changed.
        if (near != farFor) {
            farFor = near
            far.show(ranked.filter { it.first !in near })
        }
        renderFocus()
    }

    /**
     * Keeps a preview that left the scene in free flight, its DOM built,
     * so a page coming back near the ship needs no redraw; the oldest go
     * past [PARK_CAP].
     */
    private fun park(key: String, pv: PreviewPage) {
        parked.remove(key)
        parked[key] = pv
        while (parked.size > PARK_CAP) parked.remove(parked.keys.first())
    }

    /** Ends the whole-vault painting of free flight: back to the window's own previews. */
    private fun clearFlightPages() {
        if (flightPlaces.isEmpty()) return
        flightPlaces = emptyMap()
        flightGraph = null
        farFor = null
        takeOffAt = null
        parked.clear()
        far.clear()
    }

    /** Highlights the border of the page [key] (the page ahead), or none. */
    private fun markAimed(key: String?) {
        val el = key?.let { if (it == page?.key) live.frame else previews[it]?.card }
        if (el === aimedEl) return
        aimedEl?.classList?.remove("is-aimed")
        el?.classList?.add("is-aimed")
        aimedEl = el
    }

    // ------------------------------------------------------------ layout

    /**
     * Moves the view to [nx], [ny] (inside the overlay) and sizes it to
     * [nw] × [nh]. A resize is not navigation: pages and camera jump to
     * their new places without gliding.
     */
    override fun place(nx: Int, ny: Int, nw: Int, nh: Int) {
        if (nx == x && ny == y && nw == w && nh == h) return
        val resized = nw != w || nh != h
        x = nx; y = ny; w = max(1, nw); h = max(1, nh)
        element.style.left = "${x}px"
        element.style.top = "${y}px"
        element.style.width = "${w}px"
        element.style.height = "${h}px"
        if (!resized) return
        geometry = PageSpaceLayout.geometry(w.toDouble(), h.toDouble())
        cssRenderer.setSize(w, h)
        camera.aspect = w.toDouble() / h
        camera.updateProjectionMatrix()
        sizePage(live.slot)
        previews.values.forEach { sizePage(it.el) }
        val p = page ?: return
        val at = placed[p.key] ?: SpaceVec.ZERO
        targets = PageSpaceLayout.layout(p.tree(), geometry, at)
        placed.putAll(targets)
        for ((key, pv) in previews) targets[key]?.let { pv.cur = it; pv.scale = 1.0 }
        val f = flight
        if (f != null) {
            flight = f.copy(to = PageSpaceLayout.cameraFor(at, geometry))
        } else if (!freeFlight.isOn) {
            cam = PageSpaceLayout.cameraFor(at, geometry)
        }
        renderNow()
    }

    private fun sizePage(el: HTMLElement) {
        el.style.width = "${geometry.pageWidth}px"
        el.style.height = "${geometry.pageHeight}px"
    }

    // ------------------------------------------------------------- state

    /**
     * Follows one pane state: a new page flies, the same page refreshes
     * (at once when its child pages changed — an indent sprouts a page —
     * else coalesced, so typing stays cheap).
     */
    private fun onState(state: PaneBackingViewModel.State) {
        live.fitContent(!(state.isDrawingView || state.isHtmlView))
        val sig = state.activeFileRel to state.zoomedLineId
        val old = signature
        // A note renamed in place is not a navigation.
        val renamed = old != null && old != sig && old.second == sig.second && vm.renamedTo(old.first) == sig.first
        if (renamed) signature = sig
        val moved = old != null && old != sig && !renamed
        if (!moved) {
            scheduleRefresh()
            return
        }
        val next = vm.spacePageOf(state) ?: return // still loading: fly once it has
        signature = sig
        val current = page
        if (current == null || current.key == next.key) {
            apply(next, sprout = false)
        } else {
            flyTo(current, next)
        }
        mode.onViewNavigated()
    }

    /** Re-reads the page around the window after [REFRESH_MS]; repeated calls coalesce. */
    private fun scheduleRefresh() {
        if (refreshHandle != null) return
        refreshHandle = window.setTimeout({
            refreshHandle = null
            val st = vm.currentBackingState
            if (st.activeFileRel to st.zoomedLineId != signature) return@setTimeout
            vm.spacePageOf(st)?.let { apply(it, sprout = true) }
        }, REFRESH_MS)
    }

    /**
     * Shows [next] — the same page as [page], perhaps under a new key (a
     * zoomed leaf that got its folder) — relaying out its child pages:
     * new ones grow in from their parent ([sprout]), moved ones glide.
     */
    private fun apply(next: SpacePage, sprout: Boolean) {
        val prev = page
        page = next
        val at = (prev?.let { placed[it.key] }) ?: placed[next.key] ?: SpaceVec.ZERO
        if (prev != null && prev.key != next.key) placed[next.key] = at
        rekeyPreviews(next)
        targets = PageSpaceLayout.layout(next.tree(), geometry, at)
        placed.putAll(targets)
        renderLiveHead()
        syncPreviews(sprout)
        mode.requestFrame()
    }

    /**
     * Starts the flight from [from] to [to]: the live page moves to [to]'s
     * place at once, a preview takes over [from]'s, and the camera flies.
     */
    private fun flyTo(from: SpacePage, to: SpacePage) {
        // Navigating lands the ship: the flight starts from where it is.
        freeFlight.stop()
        holdUntil = 0.0
        markAimed(null)
        val fromAt = placed[from.key] ?: SpaceVec.ZERO
        val toAt = placed[to.key] ?: targets[to.key] ?: placeFor(from, fromAt, to)
        placed[to.key] = toAt
        page = to
        leaving = from.key
        // The page left behind becomes a preview where the live page was.
        val pv = previews.getOrPut(from.key) { PreviewPage(from.key) }
        pv.fill(from.title, from.items, null)
        pv.lineId = null
        pv.cur = fromAt
        pv.scale = 1.0
        pv.depth = -1
        if (!pv.inScene) addToScene(pv)
        rekeyPreviews(to)
        targets = PageSpaceLayout.layout(to.tree(), geometry, toAt)
        placed.putAll(targets)
        live.obj.position.set(toAt.x, toAt.y, toAt.z)
        renderLiveHead()
        syncPreviews(sprout = false)
        val goal = PageSpaceLayout.cameraFor(toAt, geometry)
        val dist = distance(cam, goal)
        if (prefersReducedMotion() || dist < 1) {
            cam = goal
            rot = SpaceQuat.IDENTITY
            endFlight()
        } else {
            val mid = SpaceVec((cam.x + goal.x) / 2, (cam.y + goal.y) / 2, (cam.z + goal.z) / 2 + dist * 0.3)
            flight = Flight(cam, goal, mid, 0.0, PageSpaceLayout.flightSeconds(dist), rot)
        }
        renderNow()
        mode.requestFrame()
    }

    /**
     * Where [to] hangs the first time this view goes there from [from] at
     * [fromAt]: above [from] when it is [from]'s parent (so [from] keeps its
     * place among [to]'s children), in a new child slot when it is a child
     * [from] does not list yet (a note, a leaf just zoomed into), otherwise
     * a little ahead ([PageSpaceLayout.jumpPosition]).
     */
    private fun placeFor(from: SpacePage, fromAt: SpaceVec, to: SpacePage): SpaceVec {
        if (from.parentKey == to.key) {
            val i = to.children.indexOfFirst { it.key == from.key }
            if (i >= 0) return PageSpaceLayout.parentPosition(fromAt, i, to.children.size, geometry)
        }
        if (to.parentKey == from.key) {
            val n = from.children.size
            return fromAt + PageSpaceLayout.childOffsets(n + 1, geometry)[n]
        }
        return PageSpaceLayout.jumpPosition(fromAt, to.key, geometry)
    }

    private fun endFlight() {
        flight = null
        if (!freeFlight.isOn && holdUntil == 0.0) clearFlightPages()
        val gone = leaving
        leaving = null
        if (gone != null && gone !in targets) previews[gone]?.let { removeFromScene(it) }
        syncPreviews(sprout = false)
    }

    /**
     * A child page whose key changed under it (an item that got its folder
     * on save) keeps its preview and place: matched by row id.
     */
    private fun rekeyPreviews(next: SpacePage) {
        for (c in allChildren(next)) {
            val id = c.lineId ?: continue
            if (c.key in previews) continue
            // Snapshot as pairs: a Kotlin/JS map entry dies with any change to the map.
            val (oldKey, pv) = previews.toList().firstOrNull { (k, pv) -> pv.lineId == id && k != c.key } ?: continue
            previews.remove(oldKey)
            pv.key = c.key
            previews[c.key] = pv
            placed[oldKey]?.let { placed[c.key] = it }
        }
    }

    /**
     * Makes the previews match [targets]: one per page around the current
     * one (filled from its [SpaceChild]), the page being left kept until
     * its flight ends. With [sprout], a page new to the view grows in from
     * its parent's place; otherwise it appears at its own.
     */
    private fun syncPreviews(sprout: Boolean) {
        // Flying (or flying back): the whole vault, ranked round the ship.
        if (flightPlaces.isNotEmpty()) {
            flightSync()
            return
        }
        syncAround(sprout)
    }

    /**
     * [syncPreviews] outside free flight: the page's children and
     * grandchildren and the rest of the vault round it ([aroundPlaces],
     * [staysBehind]) — also free flight's first view, until the ship moves
     * ([takeOffAt]), so taking off changes nothing on screen.
     */
    private fun syncAround(sprout: Boolean) {
        val p = page ?: return
        val wanted = HashMap<String, Pair<SpaceChild, Int>>()
        val parents = HashMap<String, String>()
        for (c in p.children) {
            wanted[c.key] = c to 1
            parents[c.key] = p.key
            for (g in c.children) {
                if (g.key !in wanted) wanted[g.key] = g to 2
                parents.getOrPut(g.key) { c.key }
            }
        }
        wanted.remove(p.key)
        // Every page round the camera, not just this page's children and
        // grandchildren: the rest of the vault hangs where free flight shows
        // it ([aroundPlaces]), so siblings and other branches stay in view.
        // Only the nearest are DOM cards — children first, then by distance
        // from where the camera faces the page — the rest far cards.
        val eye = placed[p.key]?.let { PageSpaceLayout.cameraFor(it, geometry) } ?: cam
        val entries = ArrayList<Around>()
        for ((key, pair) in wanted) {
            val at = targets[key] ?: continue
            entries += Around(key, at, pair.first, pair.second)
        }
        placed[p.key]?.let { here ->
            for ((key, at) in aroundPlaces(p, here)) {
                if (key == p.key || key == leaving || key in wanted || !staysBehind(at, here)) continue
                entries += Around(key, at, null, 2)
            }
        }
        val ranked = entries.sortedWith(compareBy<Around> { if (it.depth == 1) 0 else 1 }.thenBy { distance(it.at, eye) })
        val cards = ranked.take(NAV_FULL + NAV_SLABS)
        val fullKeys = ranked.take(NAV_FULL).mapTo(HashSet()) { it.key }
        val shownKeys = cards.mapTo(HashSet()) { it.key }
        val graph = if (cards.any { it.child == null }) mode.pagesGraph() else null
        aroundTargets = cards.filter { it.child == null }.associate { it.key to it.at }
        for (e in cards) {
            val key = e.key
            var pv = previews[key]
            val fresh = pv == null
            if (pv == null) {
                pv = PreviewPage(key)
                previews[key] = pv
            }
            val full = key in fullKeys
            val child = e.child
            if (child != null) {
                pv.lineId = child.lineId
                pv.folderRel = child.folderRel
            } else {
                pv.lineId = null
                pv.folderRel = folderOfKey(key)
            }
            pv.depth = e.depth
            // A title card: header and title only, no glow (cheap to draw in numbers).
            pv.card.classList.toggle("is-slab", !full)
            if (child != null) {
                pv.fill(child.title, if (full) child.items else emptyList(), if (full) child else null)
            } else {
                val folder = pv.folderRel
                val title = folder?.let { graph?.nodes?.get(it)?.title }.orEmpty()
                pv.fill(title, if (full) folder?.let { mode.pageItems(it) }.orEmpty() else emptyList(), null)
            }
            if (!pv.inScene) {
                val from = parents[key]?.let { placed[it] }
                if (sprout && fresh && from != null) {
                    pv.cur = from
                    pv.scale = 0.0
                } else {
                    pv.cur = e.at
                    pv.scale = 1.0
                }
                addToScene(pv)
            }
        }
        for ((key, pv) in previews.toList()) {
            if (key == leaving || key in shownKeys) continue
            if (pv.inScene) removeFromScene(pv)
            previews.remove(key)
        }
        far.show(ranked.drop(NAV_FULL + NAV_SLABS).map { it.key to it.at })
        renderFocus()
    }

    /**
     * `true` when a page of the rest of the vault at [at] never shows over
     * the window's page at [here]: it hangs clearly behind it (the opaque
     * live card covers it), or level with it or nearer but beside it, past
     * its width (perspective only pushes a nearer page further out). Other
     * branches' columns can cross the window's page ([PageSpaceLayout.wholeLayout]);
     * those pages are left out, so the window's page is always in front.
     */
    private fun staysBehind(at: SpaceVec, here: SpaceVec): Boolean {
        if (at.z < here.z - BEHIND_MARGIN) return true
        return kotlin.math.abs(at.x - here.x) >= geometry.pageWidth + BEHIND_MARGIN
    }

    /**
     * A page [syncPreviews] may show: its [key] and place [at], its
     * [child] when it is one of the page's children or grandchildren
     * (`null` for the rest of the vault), and [depth] (1 a child, 2
     * anything else — drawn fainter).
     */
    private class Around(val key: String, val at: SpaceVec, val child: SpaceChild?, val depth: Int)

    /** Where the shown pages of the rest of the vault glide to ([tick]); the others come from [targets]. */
    private var aroundTargets: Map<String, SpaceVec> = emptyMap()

    /** [aroundPlaces]' last result and what it was laid out from. */
    private var around: Pair<List<Any?>, Map<String, SpaceVec>>? = null

    /**
     * Where every page of the vault hangs round the window's page [p] at
     * [at] — free flight's layout ([PageSpaceLayout.wholeLayout]): its
     * subtree as [targets] has it, each ancestor where going up puts it,
     * their other branches in their columns. The same places whichever
     * page of a branch the window is on, so pages stay put as it moves.
     * Empty until every listing has landed (as Grove waits), so the
     * surroundings never shift as the vault loads; cached until the
     * graph, the page, its place or the view's size change.
     */
    private fun aroundPlaces(p: SpacePage, at: SpaceVec): Map<String, SpaceVec> {
        val graph = mode.pagesGraph()
        if (graph.root == null || !graph.nodes.values.all { it.loaded }) return emptyMap()
        val sig = listOf(graph, p, at, geometry)
        around?.let { (s, places) -> if (s.size == sig.size && s[0] === graph && s.drop(1) == sig.drop(1)) return places }
        val places = PageSpaceLayout.wholeLayout(GroveLayout.treeOf(graph, p), p.key, at, geometry)
        around = sig to places
        return places
    }

    private fun addToScene(pv: PreviewPage) {
        sizePage(pv.el)
        pv.el.setAttribute("data-page-key", pv.key)
        pv.obj.position.set(pv.cur.x, pv.cur.y, pv.cur.z)
        pv.obj.scale.setScalar(max(pv.scale, 0.001))
        world.add(pv.obj)
        pv.inScene = true
    }

    private fun removeFromScene(pv: PreviewPage) {
        world.remove(pv.obj)
        pv.el.remove()
        pv.inScene = false
    }

    // ------------------------------------------------------------ frames

    /**
     * Advances one frame of [dt] seconds: pages glide to their places and
     * grow in, the flight moves on, the view re-renders.
     *
     * @return `true` while anything is still moving.
     */
    override fun tick(dt: Double): Boolean {
        val reduced = prefersReducedMotion()
        val k = if (reduced) 1.0 else 1 - exp(-dt * 6)
        val ks = if (reduced) 1.0 else 1 - exp(-dt * 3.5)
        var moving = false
        for (pv in previews.values) {
            if (!pv.inScene) continue
            val t = if (pv.key == leaving) pv.cur else targets[pv.key] ?: aroundTargets[pv.key] ?: pv.cur
            val next = lerp(pv.cur, t, k)
            pv.cur = if (distance(next, t) < 0.4) t else next
            pv.scale = if (1 - pv.scale < 0.002) 1.0 else pv.scale + (1 - pv.scale) * ks
            if (pv.cur != t || pv.scale < 1.0) moving = true
        }
        val f = flight
        if (freeFlight.isOn) {
            if (freeFlight.step(dt)) moving = true
            val pose = freeFlight.pose()
            cam = pose.position
            rot = pose.rotation
        } else if (holdUntil > 0) {
            // A pick waits for its navigation; past the wait it lands.
            if (window.performance.now() >= holdUntil) returnFlight() else moving = true
        } else if (f != null) {
            val t = min(1.0, f.t + dt / f.duration)
            flight = f.copy(t = t)
            val e = if (t < 0.5) 4 * t * t * t else 1 - pow3(-2 * t + 2) / 2
            val u = 1 - e
            cam = SpaceVec(
                u * u * f.from.x + 2 * u * e * f.ctrl.x + e * e * f.to.x,
                u * u * f.from.y + 2 * u * e * f.ctrl.y + e * e * f.to.y,
                u * u * f.from.z + 2 * u * e * f.ctrl.z + e * e * f.to.z,
            )
            rot = if (f.fromRot == SpaceQuat.IDENTITY) f.fromRot else SpaceQuat.slerp(f.fromRot, SpaceQuat.IDENTITY, e)
            if (t >= 1) {
                cam = f.to
                rot = SpaceQuat.IDENTITY
                endFlight()
            } else {
                moving = true
            }
        } else {
            val goal = page?.let { placed[it.key] }?.let { PageSpaceLayout.cameraFor(it, geometry) }
            if (goal != null && cam != goal) {
                val next = lerp(cam, goal, k)
                cam = if (distance(next, goal) < 0.4) goal else next
                if (cam != goal) moving = true
            }
            if (rot != SpaceQuat.IDENTITY) {
                val next = SpaceQuat.slerp(rot, SpaceQuat.IDENTITY, k)
                rot = if (kotlin.math.abs(next.w) > 0.999999) SpaceQuat.IDENTITY else next
                moving = true
            }
        }
        if (flightPlaces.isNotEmpty()) {
            sinceFlightSync += dt
            if (sinceFlightSync > FLIGHT_SYNC_S) {
                sinceFlightSync = 0.0
                flightSync()
            }
        }
        renderNow()
        if (freeFlight.isOn) {
            val pages = HashMap<String, SpaceVec>()
            for (pv in previews.values) if (pv.inScene && pv.key != leaving) pages[pv.key] = pv.cur
            page?.let { p -> placed[p.key]?.let { pages[p.key] = it } }
            markAimed(freeFlight.aim(pages, camPose(), geometry.cameraDistance, w, h, geometry.pageWidth))
        }
        // While anything moves, the previews keep the raster they have
        // (`will-change: transform`): a flight changes every card's size on
        // screen each frame, and Chrome would otherwise redraw each one —
        // text, glow — at its new scale every frame. At rest they redraw
        // once, sharp.
        element.classList.toggle("is-moving", moving)
        return moving
    }

    /**
     * Applies every position, orders the pages for hit-testing, renders the
     * CSS3D layer, clears stray scroll offsets and redraws the threads.
     */
    fun renderNow() {
        for (pv in previews.values) {
            if (!pv.inScene) continue
            pv.obj.position.set(pv.cur.x, pv.cur.y, pv.cur.z)
            pv.obj.scale.setScalar(max(pv.scale, 0.001))
        }
        orderHits()
        applyCamera()
        cssRenderer.render(scene, camera)
        val de = cssRenderer.domElement
        if (de.scrollTop != 0.0 || de.scrollLeft != 0.0) { de.scrollTop = 0.0; de.scrollLeft = 0.0 }
        if (element.scrollTop != 0.0 || element.scrollLeft != 0.0) { element.scrollTop = 0.0; element.scrollLeft = 0.0 }
        updateThreads()
    }

    /** The world offset the shared background is drawn with, so it moves with this view. */
    override fun applyBackdropOffset(backdropWorld: Object3) {
        val d = geometry.cameraDistance
        val q = rot.conjugate()
        val p = q.rotate(-cam)
        backdropWorld.quaternion.set(q.x, q.y, q.z, q.w)
        backdropWorld.position.set(p.x, p.y, p.z + d)
        backdropWorld.updateMatrixWorld(true)
    }

    /** The world gets the inverse of the camera's pose — see the file header. */
    private fun applyCamera() {
        val d = geometry.cameraDistance
        camera.position.set(0.0, 0.0, d)
        val q = rot.conjugate()
        val p = q.rotate(-cam)
        world.quaternion.set(q.x, q.y, q.z, q.w)
        world.position.set(p.x, p.y, p.z + d)
        camera.updateMatrixWorld(true)
        scene.updateMatrixWorld(true)
    }

    /** The camera as a pose (position and frame). */
    private fun camPose(): SpacePose = SpacePose(
        cam,
        rot.rotate(SpaceVec(1.0, 0.0, 0.0)),
        rot.rotate(SpaceVec(0.0, 1.0, 0.0)),
        rot.rotate(SpaceVec(0.0, 0.0, 1.0)),
    )

    /**
     * Stacks pages by distance from the camera (the live page on top) and
     * lets pages at or behind the camera ignore the pointer — see the file
     * header.
     */
    private fun orderHits() {
        live.slot.style.zIndex = "100000"
        val fwd = rot.rotate(SpaceVec(0.0, 0.0, -1.0))
        // z-index by rank, not distance: it changes only when the order does,
        // so a flight does not restyle every page on every frame.
        val byDepth = previews.values.filter { it.inScene }.map { it to (it.cur - cam).dot(fwd) }.sortedBy { it.second }
        byDepth.forEachIndexed { rank, (pv, depth) ->
            val behind = depth < 1
            val z = max(1, 50_000 - rank).toString()
            if (pv.el.style.zIndex != z) pv.el.style.zIndex = z
            val pe = if (behind || pv.key == leaving) "none" else "auto"
            if (pv.card.style.getPropertyValue("pointer-events") != pe) pv.card.style.setProperty("pointer-events", pe)
            val opacity = when (pv.depth) {
                -1 -> "0.7"
                2 -> "0.45"
                else -> "1"
            }
            // Only the content fades (`--lb-fade`): a translucent card would
            // let the cards behind it show through its text.
            if (pv.card.style.getPropertyValue("--lb-fade") != opacity) pv.card.style.setProperty("--lb-fade", opacity)
            // A page the camera comes close to (flying past or through it)
            // fades out and is then hidden: near the camera a page-sized card
            // would cover several screens, each frame drawn at that size.
            val near = (depth - NEAR_HIDE * geometry.cameraDistance) / ((NEAR_FADE - NEAR_HIDE) * geometry.cameraDistance)
            val slotOpacity = when {
                near >= 1 -> ""
                near <= 0 -> "0"
                else -> ((round(near * 20) / 20).coerceIn(0.05, 0.95)).toString()
            }
            if (pv.el.style.opacity != slotOpacity) pv.el.style.opacity = slotOpacity
            val visibility = if (slotOpacity == "0") "hidden" else ""
            if (pv.el.style.visibility != visibility) pv.el.style.visibility = visibility
        }
    }

    /**
     * Redraws the threads: from the live page's edge, level with each
     * child bullet's dot (clamped to the page), to that child's page —
     * none to a page that shows behind the live page.
     */
    private fun updateThreads() {
        val p = page
        if (p == null || flight != null || freeFlight.isOn || rot != SpaceQuat.IDENTITY) {
            if (threads.innerHTML.isNotEmpty()) threads.innerHTML = ""
            return
        }
        val box = element.getBoundingClientRect()
        val pr = live.frame.getBoundingClientRect()
        val br = live.host.getBoundingClientRect()
        val ids = vm.currentBackingState.documentState?.lineIds
        val liveAt = placed[p.key] ?: SpaceVec.ZERO
        val sb = StringBuilder()
        for (c in p.children) {
            val pv = previews[c.key] ?: continue
            if (!pv.inScene || pv.scale < 0.3) continue
            val side = if (pv.cur.x < liveAt.x) -1 else 1
            val kr = pv.card.getBoundingClientRect()
            // A page far back in its column shows behind the live page; the
            // overlay would draw its thread across the editor, so it gets none.
            val endX = if (side > 0) kr.left else kr.right
            val endY = kr.top + min(24.0, kr.height / 2)
            if (endX > pr.left && endX < pr.right && endY > pr.top && endY < pr.bottom) continue
            var yy = pr.top + 30
            val row = c.lineId?.let { ids?.indexOf(it) }?.takeIf { it >= 0 }
            val dot = row?.let { screen.bulletElementOfRow(it) }
            if (dot != null) {
                val r = dot.getBoundingClientRect()
                yy = max(br.top + 6, min(br.bottom - 6, r.top + r.height / 2))
            }
            val x1 = (if (side > 0) pr.right else pr.left) - box.left
            val y1 = yy - box.top
            val x2 = endX - box.left
            val y2 = endY - box.top
            val bend = max(30.0, kotlin.math.abs(x2 - x1) * 0.45)
            val tint = mode.areaColor(SpacePalette.pathOfKey(c.key))?.let { " style=\"color: $it\"" } ?: ""
            sb.append("<g$tint>")
            sb.append("<path class=\"lunarbor-space-thread\" d=\"M${f1(x1)} ${f1(y1)} C${f1(x1 + side * bend)} ${f1(y1)} ${f1(x2 - side * bend)} ${f1(y2)} ${f1(x2)} ${f1(y2)}\"/>")
            sb.append("<circle class=\"lunarbor-space-thread-end\" cx=\"${f1(x1)}\" cy=\"${f1(y1)}\" r=\"3\"/>")
            sb.append("<circle class=\"lunarbor-space-thread-end\" cx=\"${f1(x2)}\" cy=\"${f1(y2)}\" r=\"2.5\"/>")
            sb.append("</g>")
        }
        val html = sb.toString()
        if (threads.innerHTML != html) threads.innerHTML = html
    }

    // ------------------------------------------------------------- heads

    /**
     * Rebuilds the live page's header: Back / Forward, the breadcrumb of
     * the window's whole location (every segment but the last navigates).
     */
    private fun renderLiveHead() {
        // The page key and child pages on the view, for tests and inspection.
        element.setAttribute("data-page-key", page?.key ?: "")
        element.setAttribute("data-children", page?.children?.joinToString(",") { it.key } ?: "")
        tint(live.frame, page?.key)
        fillSpaceHead(live.head, vm, liveCrumbs()) { mode.focusPane(paneId) }
        renderFocus()
    }

    /** Outlines the live page while its window is the tab's focused one ([SpaceMode.isFocused]). */
    override fun renderFocus() {
        live.frame.classList.toggle("is-focused", mode.isFocused(paneId))
    }

    /** Sets (or clears, at the root) the area colour `--lb-area` on [el] for page [key]. */
    private fun tint(el: HTMLElement, key: String?) {
        val c = key?.let { mode.areaColor(SpacePalette.pathOfKey(it)) }
        if (c == null) el.style.removeProperty("--lb-area") else el.style.setProperty("--lb-area", c)
    }

    // ---------------------------------------------------------- previews

    /** The window's breadcrumb ([SpaceMode.breadcrumbOf]) as header segments. */
    private fun liveCrumbs(): List<SpaceCrumb> = mode.breadcrumbOf(paneId).map { SpaceCrumb(it.label, it.onClick) }

    /**
     * A preview's breadcrumb: its folder's path ([folderCrumbs]), or — a
     * page with no folder yet — the window's breadcrumb with [title] after it.
     */
    private fun previewCrumbs(folderRel: String?, title: String): List<SpaceCrumb> =
        folderRel?.let { folderCrumbs(it, title, vm) } ?: (liveCrumbs() + SpaceCrumb(title, null))

    /**
     * Zooms the window to an item shown in a preview: in place when its
     * row is in the open outline, else through its folder (a vault
     * link, which expands bullets on the way or opens the node's outline).
     */
    private fun goTo(lineId: LineId?, folderRel: String?) {
        // A pick in free flight: keep holding until the navigation arrives.
        if (holdUntil > 0) hold(NAV_HOLD_MS)
        mode.focusPane(paneId)
        val ids = vm.currentBackingState.documentState?.lineIds
        val row = lineId?.let { ids?.indexOf(it) }?.takeIf { it >= 0 }
        when {
            row != null -> vm.zoomInto(row)
            folderRel != null -> vm.navigateToLink(LunarborLink.rooted(folderRel))
        }
    }

    /**
     * The window's live page: a page-sized transparent slot (what the CSS3D
     * object places) holding a card that is as tall as its content — at
     * most the slot, scrolling inside beyond that — with a header and the
     * pane's editor. A drawing or web page fills the slot ([fitContent]).
     */
    private inner class LivePage {
        val slot: HTMLElement = div("lunarbor-space-slot")
        val frame: HTMLElement = div("lunarbor-space-page is-live")
        val head: HTMLElement = div("lunarbor-space-head")
        private val bodyWrap: HTMLElement = div("lunarbor-space-live-body")

        /** Where the pane's `MainScreen` mounts ([MainScreen.mountInSpace]). */
        val host: HTMLElement = div("lunarbor-space-live-host")
        val obj: Object3

        init {
            frame.appendChild(head)
            bodyWrap.appendChild(host)
            frame.appendChild(bodyWrap)
            slot.appendChild(frame)
            obj = lib.css3dObject(slot)
            // CSS3DObject turns text selection off on its element; the
            // live page is the editor, where selecting must work.
            slot.style.setProperty("user-select", "auto")
        }

        /**
         * Sizes the card to its content (`true`), or to the whole slot for
         * views that need the height — a drawing or a web page.
         */
        fun fitContent(fit: Boolean) {
            frame.classList.toggle("is-fill", !fit)
        }
    }

    /**
     * A read-only page: a header (the page's title) and
     * its items as a simple outline in the editor's fonts.
     *
     * @param key Its page key (changes when [rekeyPreviews] carries it over).
     */
    private inner class PreviewPage(var key: String) {
        /** The page-sized transparent slot the CSS3D object places. */
        val el: HTMLElement = div("lunarbor-space-slot is-preview")

        /** The visible card, as tall as its items (at most the slot). */
        val card: HTMLElement = div("lunarbor-space-page")
        /** The header row: dimmed arrows and the page's breadcrumb ([fillSpaceHead]). */
        private val head: HTMLElement = div("lunarbor-space-head")
        private val body: HTMLElement = div("lunarbor-space-preview-body")

        /** The page's big title, styled like the live page's (`.lunarbor-title`), so it never pops in or out in a flight. */
        private val headline: HTMLElement = div("lunarbor-space-preview-headline")
        val obj: Object3

        /** Where the page is now (glides to its target). */
        var cur: SpaceVec = SpaceVec.ZERO

        /** Its scale (grows from 0 when it sprouts). */
        var scale: Double = 1.0
        var inScene: Boolean = false

        /** 1 for a child page, 2 for a grandchild, -1 for the page being left. */
        var depth: Int = 1

        /** Its item's row id in the open outline, for [goTo] and [rekeyPreviews]. */
        var lineId: LineId? = null

        /** Its folder, for [goTo]. */
        var folderRel: String? = null

        /** What [fill] last drew, to skip identical redraws. */
        private var drawn: Any? = null

        /** `true` once [fill] has drawn anything. */
        val isFilled: Boolean get() = drawn != null

        init {
            card.appendChild(head)
            card.appendChild(headline)
            card.appendChild(body)
            el.appendChild(card)
            obj = lib.css3dObject(el)
            card.addEventListener("mousedown", { e ->
                val me = e as MouseEvent
                if (me.button.toInt() != 0) return@addEventListener
                me.preventDefault()
                me.stopPropagation()
                if (depth == -1) return@addEventListener
                val target = me.target as? org.w3c.dom.Element
                val row = target?.closest(".lunarbor-space-item-dot")?.closest(".lunarbor-space-item") as? HTMLElement
                val index = row?.getAttribute("data-index")?.toIntOrNull()
                val item = index?.let { items.getOrNull(it) }
                if (item != null && (item.lineId != null || item.folderRel != null)) {
                    goTo(item.lineId, item.folderRel)
                } else {
                    // The flight lands on the page where it was seen.
                    placed[key] = cur
                    goTo(lineId, folderRel)
                }
            })
        }

        private var items: List<SpaceItem> = emptyList()

        /** Draws [titleText] and [list]; a no-op when nothing changed. */
        fun fill(titleText: String, list: List<SpaceItem>, child: SpaceChild?) {
            val folder = folderRel ?: folderOfKey(key)
            val sig = listOf(titleText, list, child?.children?.map { it.key }, folder)
            if (sig == drawn) return
            drawn = sig
            tint(card, key)
            items = list
            val shown = titleText.ifEmpty { if (key == PageSpaceKeys.ofFolder("")) "Home" else "Untitled" }
            fillSpaceHead(head, null, previewCrumbs(folder, shown)) { mode.focusPane(paneId) }
            headline.textContent = shown
            body.innerHTML = ""
            if (list.isEmpty()) {
                body.appendChild(div("lunarbor-space-preview-empty").also { it.textContent = "…" })
                return
            }
            list.forEachIndexed { i, item ->
                val row = div("lunarbor-space-item")
                row.setAttribute("data-index", i.toString())
                // The editor's hanging indent (OutlinePaintLoop): 30 px a level, the dot hung left of the text.
                row.style.paddingLeft = "calc(${item.depth * INDENT_STEP_PX}px + 1.15em)"
                val opens = item.lineId != null || item.folderRel != null
                val dot = span(if (item.folderRel != null) "lunarbor-space-item-dot is-node" else "lunarbor-space-item-dot", "")
                if (!opens) dot.classList.add("is-inert")
                row.appendChild(dot)
                row.appendChild(document.createTextNode(" "))
                row.appendChild(span("lunarbor-space-item-text", item.title.ifEmpty { " " }))
                body.appendChild(row)
            }
        }
    }

    /**
     * A camera flight: a quadratic Bézier from [from] to [to] through
     * [ctrl] (pulled back on long hops), eased in and out, turning from
     * [fromRot] back upright (a landing from free flight).
     *
     * @property t Progress, 0..1.
     * @property duration Seconds.
     */
    private data class Flight(
        val from: SpaceVec,
        val to: SpaceVec,
        val ctrl: SpaceVec,
        val t: Double,
        val duration: Double,
        val fromRot: SpaceQuat = SpaceQuat.IDENTITY,
    )

    private fun allChildren(p: SpacePage): List<SpaceChild> = p.children + p.children.flatMap { it.children }

    /**
     * The pages too far from the ship for the DOM, while flying: each a
     * small card drawn in WebGL — a title card's shape and colours (the
     * editor's background, a faint outline in its area's hue, the area's
     * hue along its top edge) without its text — behind the pages with
     * the starfield ([backdropExtra]). Far away, so behind is where they
     * belong.
     *
     * One instanced mesh, three instances a page (outline, body, top
     * edge), drawn far to near without depth writes: painter's order, so
     * a nearer card always covers a farther one, and the parts of one
     * card never fight in the depth buffer (its near plane makes that
     * coarse at these distances).
     */
    private inner class FarPages {
        private val three: dynamic = lib.raw
        private val mesh: dynamic
        private val dummy: dynamic = construct(three.Object3D)
        private val color: dynamic = construct(three.Color)
        private val bg: dynamic = construct(three.Color)
        private val area: dynamic = construct(three.Color)
        private var shown = false

        /** Each page's hue, worked out once per flight ([clear] forgets them). */
        private val hues = HashMap<String, Double?>()

        init {
            val params: dynamic = js("({})")
            params.color = 0xffffff
            params.transparent = true
            params.opacity = FAR_OPACITY
            params.depthWrite = false
            params.side = three.DoubleSide
            mesh = construct(three.InstancedMesh, construct(three.PlaneGeometry, 1.0, 1.0), construct(three.MeshBasicMaterial, params), FAR_CAP * 3)
            mesh.count = 0
            mesh.frustumCulled = false
        }

        /** The mesh while it draws anything, else `null`. */
        val objectIfShown: Object3? get() = if (shown) mesh.unsafeCast<Object3>() else null

        /** Places instance [i] as a [w] × [h] rectangle centred at ([x], [y], [z]) in [c]. */
        private fun part(i: Int, x: Double, y: Double, z: Double, w: Double, h: Double, c: dynamic) {
            dummy.position.set(x, y, z)
            dummy.scale.set(w, h, 1.0)
            dummy.updateMatrix()
            mesh.setMatrixAt(i, dummy.matrix)
            mesh.setColorAt(i, c)
        }

        /** Draws a card for each of [pages] (key → slot centre), at most [FAR_CAP], far ones first. */
        fun show(pages: List<Pair<String, SpaceVec>>) {
            val dark = mode.isDarkTheme
            // The cards' own background (`.lunarbor-space-page`: --t-bg).
            val css = try {
                window.getComputedStyle(element).getPropertyValue("--t-bg").trim()
            } catch (_: Throwable) {
                ""
            }
            bg.set(if (dark) 0x1e1e1e else 0xffffff)
            if (css.startsWith("#") || css.startsWith("rgb") || css.startsWith("hsl")) {
                try { bg.setStyle(css) } catch (_: Throwable) { }
            }
            val pw = geometry.pageWidth
            val top = geometry.pageHeight / 2
            val eye = cam
            val sorted = pages.take(FAR_CAP).sortedByDescending { (_, at) -> distance(at, eye) }
            var i = 0
            for ((key, at) in sorted) {
                val hue = hues.getOrPut(key) { mode.areaHue(SpacePalette.pathOfKey(key)) }
                // As `SpaceMode.areaColor`: hsl(h 85% 62%) on dark, hsl(h 75% 45%) on light.
                if (hue == null) area.setHSL(0.6, 0.08, if (dark) 0.55 else 0.6) else area.setHSL(hue, if (dark) 0.85 else 0.75, if (dark) 0.62 else 0.45)
                val cy = at.y + top - FAR_CARD_H / 2
                // A 1px outline in the area's hue at 35% (the card's box-shadow ring).
                color.copy(bg).lerp(area, 0.35)
                part(i++, at.x, cy, at.z, pw + 2 * FAR_LINE, FAR_CARD_H + 2 * FAR_LINE, color)
                part(i++, at.x, cy, at.z, pw, FAR_CARD_H, bg)
                part(i++, at.x, at.y + top - FAR_EDGE / 2, at.z, pw + 2 * FAR_LINE, FAR_EDGE, area)
            }
            mesh.count = i
            mesh.instanceMatrix.needsUpdate = true
            if (mesh.instanceColor != null) mesh.instanceColor.needsUpdate = true
            shown = i > 0
        }

        /** Draws nothing. */
        fun clear() {
            mesh.count = 0
            shown = false
            hues.clear()
        }

        fun dispose() {
            clear()
            mesh.geometry.dispose()
            mesh.material.dispose()
        }
    }

    private companion object {
        const val SVG_NS = "http://www.w3.org/2000/svg"

        /** Free flight: pages nearest the ship drawn as previews with their bullets. */
        const val FLIGHT_FULL = 12

        /** Free flight: the next nearest, drawn as title cards ([FarPages] beyond). */
        const val FLIGHT_SLABS = 48

        /** A page's children and grandchildren drawn as previews with their bullets, nearest first. */
        const val NAV_FULL = 24

        /** The next nearest drawn as title cards ([FarPages] beyond) — a page may have hundreds. */
        const val NAV_SLABS = 48

        /** Free flight: a shown page stays (a full one stays full) until its rank passes the cut times this. */
        const val KEEP_FACTOR = 1.25

        /** Free flight: at most this many previews kept off the scene with their DOM. */
        const val PARK_CAP = 120

        /** Free flight: how often (seconds) the pages are re-ranked round the ship. */
        const val FLIGHT_SYNC_S = 0.3

        /** At most this many far cards. */
        const val FAR_CAP = 6000

        /** How far behind the window's page (or beside it) a page of the rest of the vault must hang to be shown ([staysBehind]). */
        const val BEHIND_MARGIN = 120.0

        /** A far card's height: a title card's (header and title). */
        const val FAR_CARD_H = 84.0

        /** A far card's top edge in its area's hue (thicker than the cards' 3px, so it reads from afar). */
        const val FAR_EDGE = 6.0

        /** A far card's outline width. */
        const val FAR_LINE = 2.0

        /** Far cards' opacity: a title card further back is fainter. */
        const val FAR_OPACITY = 0.8

        /** How long a click in free flight holds the camera, waiting to see whether it opens a page. */
        const val CLICK_HOLD_MS = 400

        /** A preview starts fading out this close to the camera, in camera distances ([orderHits]). */
        const val NEAR_FADE = 0.6

        /** A preview this close to the camera (or behind it), in camera distances, is hidden. */
        const val NEAR_HIDE = 0.3

        /** How long a pick in free flight holds the camera, waiting for its navigation. */
        const val NAV_HOLD_MS = 2500

        /** The node folder a page key names, or `null` for a note, file or folderless item. */
        fun folderOfKey(key: String): String? = if (key.startsWith("n:")) key.removePrefix("n:") else null

        /** `new ctor(...args)` for three.js classes reached through [ThreeLib.raw]. */
        fun construct(ctor: dynamic, vararg args: dynamic): dynamic = js("Reflect").construct(ctor, args)

        /** A preview's indent per level: the editor's (`EditorStyle.indentStepPx`). */
        const val INDENT_STEP_PX = 30

        /** Delay before a non-navigation refresh (typing, listings landing). */
        const val REFRESH_MS = 120

        fun lerp(a: SpaceVec, b: SpaceVec, k: Double) = SpaceVec(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k, a.z + (b.z - a.z) * k)

        fun distance(a: SpaceVec, b: SpaceVec): Double {
            val dx = a.x - b.x
            val dy = a.y - b.y
            val dz = a.z - b.z
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        fun pow3(v: Double) = v * v * v

        fun f1(v: Double): String = (round(v * 10) / 10).toString()
    }
}

/** A `<div>` with [cls]. */
internal fun div(cls: String): HTMLElement =
    (document.createElement("div") as HTMLElement).also { it.className = cls }

/** A `<span>` with [cls] and [text]. */
internal fun span(cls: String, text: String): HTMLElement =
    (document.createElement("span") as HTMLElement).also {
        it.className = cls
        it.textContent = text
    }

/** `true` when the user asked for reduced motion: flights become cuts. */
internal fun prefersReducedMotion(): Boolean =
    window.matchMedia("(prefers-reduced-motion: reduce)").matches
