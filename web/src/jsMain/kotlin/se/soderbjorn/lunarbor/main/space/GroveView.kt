/*
 * GroveView.kt (jsMain)
 * ---------------------
 * One window's view into 3D mode's "Grove" space — the variant of Pages
 * laid out over the whole vault: its own camera, its own CSS3D renderer
 * and the pages it shows. `SpaceMode` makes one per shown window while
 * the Grove shape is on and drives them all from one render loop.
 *
 * The space: every node with children is a page — a slab of real size and
 * a little thickness — at a fixed place and angle in one tree spanning the
 * whole vault (`GroveLayout`, commonMain). The root page faces the
 * camera; each page's child pages hang further out in a cone round the
 * direction it faces away from its reader, each turned to face back along
 * its branch. Opening a page flies the camera to face it square on at
 * 1:1; Back flies the same way back, since places never depend on where
 * the window has been.
 *
 * What a view shows:
 *  - **The live page** — the window's real `MainScreen` (title, editor,
 *    search, folder contents, drawings, images, web pages), reparented into
 *    a CSS3D object. It sits wherever the window is: on navigation it
 *    moves to the new page's place at once, so typing reaches the new page
 *    from the start of the flight.
 *  - **Previews** near the window's page — its child and grandchild pages
 *    (from the open outline, so an indent sprouts a page), its parent and
 *    ancestors and its siblings — as read-only outlines; clicking one, or
 *    a dot in it, opens it in the window. The page being left stays as a
 *    preview until its flight ends.
 *  - **Slabs** — every other page within reach ([MAX_SLABS], nearest
 *    first): a plain card with the page's title, clickable too.
 *  - **Colour** — every page wears its area's hue (`SpaceMode.areaColor`):
 *    a coloured top edge, glow, slab edges and item dots.
 *
 * **Free flight** ([PageFlight], ⌥⌘F or ⌃⌘4, or the strip's Fly button): the
 * camera becomes the maps' spaceship; landing (F, C, ⌥⌘F) flies it back
 * to face the window's page, and Enter (or a click) opens the page ahead,
 * whose border is highlighted (`is-aimed`). After a pick the camera holds
 * where the ship stopped until the navigation arrives, so the flight
 * there starts from the ship.
 * While flying, the title slabs follow the ship (the nearest to the point
 * ahead of it), so the far vault fills in as it goes.
 *
 * The whole-vault tree comes from `SpaceMode.pagesGraph` (the maps' node
 * graph, privacy-filtered). Until every listing has landed the view lays
 * out the window's page and its children alone; once the graph is whole,
 * it switches to the global layout in one cut (the camera stays on the
 * page, the surroundings change), and later changes glide.
 *
 * Hard-won rules (from the prototype):
 *  - The three.js camera never moves; the world group gets the inverse of
 *    the logical camera's pose. Chrome stops hit-testing a CSS3D layer
 *    whose camera has moved behind the viewer.
 *  - Chrome hit-tests CSS3D siblings in DOM order, not depth: every page
 *    gets a z-index from its depth before the camera, the live page the
 *    highest, and pages behind the camera ignore the pointer.
 *  - A new page object is positioned and rendered once before anything in
 *    it takes focus (elements attach on render).
 *  - Every frame resets stray `scrollTop` / `scrollLeft` a focused caret
 *    puts on the `overflow: hidden` containers.
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
import se.soderbjorn.lunarbor.main.LineId
import se.soderbjorn.lunarbor.main.MainScreen
import se.soderbjorn.lunarbor.main.MainViewModel
import se.soderbjorn.lunarbor.main.PageSpaceGeometry
import se.soderbjorn.lunarbor.main.GroveLayout
import se.soderbjorn.lunarbor.main.PageSpaceLayout
import se.soderbjorn.lunarbor.main.PaneBackingViewModel
import se.soderbjorn.lunarbor.main.SpaceChild
import se.soderbjorn.lunarbor.main.SpaceItem
import se.soderbjorn.lunarbor.main.SpacePage
import se.soderbjorn.lunarbor.main.SpacePalette
import se.soderbjorn.lunarbor.main.SpacePose
import se.soderbjorn.lunarbor.main.SpaceQuat
import se.soderbjorn.lunarbor.main.SpaceTree
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

/**
 * One window's view into the Grove space.
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
 * @param mode The owning mode: render loop, focus, the vault's page graph.
 * @param scope Scope for the state collectors; cancelled by [dispose].
 */
internal class GroveView(
    override val paneId: String,
    private val lib: ThreeLib,
    private val vm: MainViewModel,
    private val screen: MainScreen,
    private val mode: SpaceMode,
    private val scope: CoroutineScope,
) : SpaceWindowView {
    /** The view's box in the space overlay, positioned by [place]. */
    override val element: HTMLElement = div("lunarbor-space-view is-grove")

    private val cssRenderer: Css3DRenderer3 = lib.css3dRenderer()
    private val scene: Object3 = lib.scene()

    /** Everything placed in the view; given the inverse of the logical camera's pose. */
    private val world: Object3 = lib.group()

    /** The three.js camera — fixed at `(0, 0, cameraDistance)`, see the file header. */
    override val camera: Camera3 = lib.perspectiveCamera(PageSpaceLayout.FOV_DEGREES, 1.0, 1.0, 4_000_000.0)

    /** The view's rectangle inside the overlay, in CSS pixels. */
    override var x = 0; private set
    override var y = 0; private set
    override var w = 1; private set
    override var h = 1; private set

    /** Page and camera sizes for the current rectangle. */
    var geometry: PageSpaceGeometry = PageSpaceLayout.geometry(1.0, 1.0); private set

    /** Where the camera logically is and how it is turned. */
    private var cam: SpacePose = GroveLayout.cameraFor(SpacePose.ROOT, geometry)

    /** The flight in progress, or `null` at rest (the camera then faces the live page). */
    private var flight: Flight? = null

    /** The page the window is on, or `null` until its first state has loaded. */
    override var page: SpacePage? = null; private set

    /** `(activeFileRel, zoomedLineId)` of [page]: a change is a navigation. */
    private var signature: Pair<String, LineId?>? = null

    /** The tree last laid out, the geometry it was laid out for, and its poses. */
    private var tree: SpaceTree? = null
    private var treeGeometry: PageSpaceGeometry? = null
    private var poses: Map<String, SpacePose> = emptyMap()

    /** Parent and children of each page of [tree]. */
    private var parentOf: Map<String, String> = emptyMap()
    private var childrenOf: Map<String, List<String>> = emptyMap()

    /** The vault graph [tree] was built from (titles of pages away from the window). */
    private var graph: VaultGraph = VaultGraph.EMPTY

    /** `true` once laid out over the whole vault (the graph had fully loaded). */
    private var global = false

    /** The window's live page. */
    private val live = LivePage()

    /** Preview pages and slabs by key. */
    private val previews = HashMap<String, PreviewPage>()

    /** The page being flown away from, its pose and content, kept until the flight ends. */
    private var leaving: String? = null
    private var leavingPose: SpacePose? = null
    private var leavingPage: SpacePage? = null

    private val jobs = mutableListOf<Job>()

    /** Pending `setTimeout` of a coalesced [scheduleRefresh] (typing, listings landing). */
    private var refreshHandle: Int? = null

    private val scrollListener: (Event) -> Unit = { mode.requestFrame() }

    /** Free flight (⌥⌘F): while on, the ship owns the camera. */
    private val freeFlight = PageFlight(element, onLand = { land() }, onEngage = { engage() }, requestFrame = { mode.requestFrame() })

    /** Seconds since the slabs last followed the ship. */
    private var sinceSlabs = 0.0

    /**
     * `performance.now()` until which the camera holds still after a pick
     * in free flight, waiting for the navigation; 0 when not holding.
     */
    private var holdUntil = 0.0

    /** The card whose border is highlighted as the page ahead. */
    private var aimedEl: HTMLElement? = null

    init {
        cssRenderer.domElement.className = "lunarbor-space-css3d"
        element.appendChild(cssRenderer.domElement)
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
        relayout(cut = true)
        renderLiveHead()
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
        jobs += scope.launch {
            mode.privacyFlow.collect { scheduleRefresh() }
        }
        mode.requestFrame()
    }

    /**
     * Gives the editor back to its pane and frees the view. With [focus],
     * the pane's editor takes the keyboard (the focused window only).
     */
    override fun dispose(focus: Boolean) {
        freeFlight.dispose()
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

    /** Grove's camera turns: the starfield surrounds it. */
    override val roundBackdrop: Boolean get() = true

    override fun toggleFlight() {
        if (freeFlight.isOn) {
            land()
            return
        }
        flight = null
        holdUntil = 0.0
        freeFlight.takeOff(cam.position, cam.forward, cam.up)
        mode.requestFrame()
    }

    /** Lands: the ship stops and the camera flies back to face the window's page. */
    private fun land() {
        if (!freeFlight.isOn) return
        freeFlight.stop()
        returnFlight()
    }

    /** Flies the camera from wherever it is back to face the window's page. */
    private fun returnFlight() {
        holdUntil = 0.0
        markAimed(null)
        syncPreviews(snap = false, sprout = false)
        val goal = GroveLayout.cameraFor(live.pose, geometry)
        val dist = (cam.position - goal.position).length
        if (!prefersReducedMotion() && dist >= 1) {
            val mid = SpaceVec.lerp(cam.position, goal.position, 0.5)
            val back = (cam.normal + goal.normal).normalized() ?: cam.up
            flight = Flight(cam, goal, mid + back * (dist * 0.3), 0.0, GroveLayout.flightSeconds(dist))
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

    /** Highlights the border of the page [key] (the page ahead), or none. */
    private fun markAimed(key: String?) {
        val el = key?.let { if (it == page?.key) live.frame else previews[it]?.card }
        if (el === aimedEl) return
        aimedEl?.classList?.remove("is-aimed")
        el?.classList?.add("is-aimed")
        aimedEl = el
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
        goTo(pv.lineId, pv.folderRel)
        screen.focusEditorAtCaret()
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
        live.size()
        previews.values.forEach { it.size() }
        if (page == null) return
        flight = null
        freeFlight.stop()
        relayout(cut = true)
        renderNow()
    }

    /**
     * Lays the pages out again from the window's [page] and the vault graph
     * ([SpaceMode.pagesGraph]) and brings the previews in line. With [cut]
     * (start, resize, the switch to the whole-vault layout) the live page,
     * previews and camera jump to their places; otherwise they glide.
     */
    private fun relayout(cut: Boolean) {
        val p = page
        val g = mode.pagesGraph()
        val whole = g.root != null && g.nodes.values.all { it.loaded }
        graph = g
        val t = GroveLayout.treeOf(if (whole) g else VaultGraph.EMPTY, p)
        val snap = cut || (whole && !global)
        global = whole
        if (t != tree || treeGeometry != geometry) {
            tree = t
            treeGeometry = geometry
            poses = GroveLayout.layout(t, geometry)
            val parents = HashMap<String, String>()
            val kids = HashMap<String, List<String>>()
            fun walk(n: SpaceTree) {
                kids[n.key] = n.children.map { it.key }
                for (c in n.children) {
                    if (c.key in kids || c.key in parents) continue
                    parents[c.key] = n.key
                    walk(c)
                }
            }
            walk(t)
            parentOf = parents
            childrenOf = kids
        }
        val target = p?.let { poses[it.key] } ?: SpacePose.ROOT
        if (snap) {
            live.pose = target
            if (!freeFlight.isOn) {
                flight = null
                cam = GroveLayout.cameraFor(target, geometry)
            }
        }
        syncPreviews(snap = snap, sprout = !snap)
        mode.requestFrame()
    }

    // ------------------------------------------------------------- state

    /**
     * Follows one pane state: a new page flies, the same page refreshes
     * (coalesced, so typing stays cheap).
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
        val from = page
        page = next
        if (from == null || from.key == next.key) {
            relayout(cut = from == null)
        } else {
            flyFrom(from)
        }
        renderLiveHead()
        mode.onViewNavigated()
    }

    /** Re-reads the page around the window after [REFRESH_MS]; repeated calls coalesce. */
    private fun scheduleRefresh() {
        if (refreshHandle != null) return
        refreshHandle = window.setTimeout({
            refreshHandle = null
            val st = vm.currentBackingState
            if (st.activeFileRel to st.zoomedLineId != signature) return@setTimeout
            val p = vm.spacePageOf(st) ?: return@setTimeout
            page = p
            relayout(cut = false)
        }, REFRESH_MS)
    }

    /**
     * Starts the flight away from [from] to [page]: the live page moves to
     * the new page's place at once, a preview takes over [from]'s, and the
     * camera flies along a curve, turning as it goes.
     */
    private fun flyFrom(from: SpacePage) {
        // Navigating lands the ship: the flight starts from where it is.
        freeFlight.stop()
        holdUntil = 0.0
        markAimed(null)
        val fromPose = live.pose
        leaving = from.key
        leavingPose = fromPose
        leavingPage = from
        relayout(cut = false)
        val toPose = page?.let { poses[it.key] } ?: fromPose
        live.pose = toPose
        syncPreviews(snap = false, sprout = false)
        val goal = GroveLayout.cameraFor(toPose, geometry)
        val dist = (cam.position - goal.position).length
        if (prefersReducedMotion() || dist < 1) {
            cam = goal
            endFlight()
        } else {
            val mid = SpaceVec.lerp(cam.position, goal.position, 0.5)
            val back = (cam.normal + goal.normal).normalized() ?: cam.up
            flight = Flight(cam, goal, mid + back * (dist * 0.3), 0.0, GroveLayout.flightSeconds(dist))
        }
        renderNow()
        mode.requestFrame()
    }

    private fun endFlight() {
        flight = null
        leaving = null
        leavingPose = null
        leavingPage = null
        syncPreviews(snap = false, sprout = false)
    }

    /**
     * Makes the previews match the window's surroundings: full previews for
     * the pages near the window's page (children, grandchildren, ancestors,
     * siblings — at most [MAX_FULL]) and the page being left; title slabs
     * for the [MAX_SLABS] other pages nearest to it — or, while flying, to
     * the point ahead of the ship ([slabsAround]). With [snap] every
     * preview jumps to its place; with [sprout] a page new to the view
     * grows in from its parent's place.
     */
    private fun syncPreviews(snap: Boolean, sprout: Boolean) {
        val p = page ?: return
        val current = p.key
        val full = LinkedHashSet<String>()
        val kids = childrenOf[current].orEmpty()
        full += kids
        for (k in kids) full += childrenOf[k].orEmpty()
        var up = parentOf[current]
        while (up != null) {
            full += up
            up = parentOf[up]
        }
        parentOf[current]?.let { full += childrenOf[it].orEmpty() }
        full.remove(current)
        val wanted = LinkedHashMap<String, Boolean>() // key → is a full preview
        for (k in full) {
            if (wanted.size >= MAX_FULL) break
            if (k in poses) wanted[k] = true
        }
        leaving?.takeIf { it != current }?.let { wanted[it] = true }
        val here = slabsAround()
        poses.entries
            .filter { it.key != current && it.key !in wanted }
            .sortedBy { (it.value.position - here).length }
            .take(MAX_SLABS)
            .forEach { wanted[it.key] = false }

        val byKey = HashMap<String, SpaceChild>()
        for (c in p.children) {
            byKey[c.key] = c
            for (gc in c.children) byKey.getOrPut(gc.key) { gc }
        }
        for ((key, isFull) in wanted) {
            val target = if (key == leaving) leavingPose else poses[key]
            target ?: continue
            var pv = previews[key]
            val fresh = pv == null
            if (pv == null) {
                pv = PreviewPage(key)
                previews[key] = pv
            }
            val child = byKey[key]
            val gone = leavingPage?.takeIf { key == leaving }
            when {
                gone != null -> {
                    pv.lineId = null
                    pv.folderRel = folderOfKey(key)
                    pv.fill(gone.title, gone.items, slab = false)
                }
                child != null -> {
                    pv.lineId = child.lineId
                    pv.folderRel = child.folderRel
                    pv.fill(child.title, child.items, slab = !isFull)
                }
                else -> {
                    val folder = folderOfKey(key)
                    pv.lineId = null
                    pv.folderRel = folder
                    val title = folder?.let { graph.nodes[it]?.title } ?: ""
                    val items = if (isFull && folder != null) mode.pageItems(folder) else emptyList()
                    pv.fill(title, items, slab = !isFull)
                }
            }
            if (!pv.inScene) {
                val from = parentOf[key]?.let { poses[it] }
                pv.pose = if (sprout && fresh && from != null && !snap) from else target
                pv.scale = if (sprout && fresh && from != null && !snap) 0.0 else 1.0
                addToScene(pv)
            } else if (snap) {
                pv.pose = target
                pv.scale = 1.0
            }
        }
        for ((key, pv) in previews.toList()) {
            if (key in wanted) continue
            if (pv.inScene) removeFromScene(pv)
            previews.remove(key)
        }
        renderFocus()
    }

    /** Where the slabs gather: ahead of the ship while flying, else round the window's page. */
    private fun slabsAround(): SpaceVec =
        if (freeFlight.isOn) cam.position + cam.forward * (geometry.cameraDistance * 3) else poses[page?.key]?.position ?: SpaceVec.ZERO

    private fun addToScene(pv: PreviewPage) {
        pv.size()
        pv.slot.setAttribute("data-page-key", pv.key)
        applyPose(pv.obj, pv.pose, pv.scale)
        world.add(pv.obj)
        pv.inScene = true
    }

    private fun removeFromScene(pv: PreviewPage) {
        world.remove(pv.obj)
        pv.slot.remove()
        pv.inScene = false
    }

    // ------------------------------------------------------------ frames

    /**
     * Advances one frame of [dt] seconds: pages glide and turn to their
     * places and grow in, the flight moves on, the view re-renders.
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
            val t = (if (pv.key == leaving) leavingPose else poses[pv.key]) ?: pv.pose
            val next = glide(pv.pose, t, k)
            pv.pose = next
            pv.scale = if (1 - pv.scale < 0.002) 1.0 else pv.scale + (1 - pv.scale) * ks
            if (next != t || pv.scale < 1.0) moving = true
        }
        page?.let { poses[it.key] }?.let { t ->
            val next = glide(live.pose, t, k)
            live.pose = next
            if (next != t) moving = true
        }
        val f = flight
        if (freeFlight.isOn) {
            if (freeFlight.step(dt)) moving = true
            cam = freeFlight.pose()
            sinceSlabs += dt
            if (sinceSlabs > SLAB_FOLLOW_S) {
                sinceSlabs = 0.0
                syncPreviews(snap = false, sprout = false)
            }
        } else if (holdUntil > 0) {
            // A pick waits for its navigation; past the wait it lands.
            if (window.performance.now() >= holdUntil) returnFlight() else moving = true
        } else if (f != null) {
            val t = min(1.0, f.t + dt / f.duration)
            flight = f.copy(t = t)
            val e = if (t < 0.5) 4 * t * t * t else 1 - pow3(-2 * t + 2) / 2
            val u = 1 - e
            val pos = f.from.position * (u * u) + f.ctrl * (2 * u * e) + f.to.position * (e * e)
            cam = poseOf(pos, SpaceQuat.slerp(f.from.rotation, f.to.rotation, e))
            if (t >= 1) {
                cam = f.to
                endFlight()
            } else {
                moving = true
            }
        } else {
            // At rest the camera faces the live page, wherever it glides.
            cam = GroveLayout.cameraFor(live.pose, geometry)
        }
        renderNow()
        if (freeFlight.isOn) {
            val pages = HashMap<String, SpaceVec>()
            for (pv in previews.values) if (pv.inScene && pv.key != leaving) pages[pv.key] = pv.pose.position
            page?.let { pages[it.key] = live.pose.position }
            markAimed(freeFlight.aim(pages, cam, geometry.cameraDistance, w, h, geometry.pageWidth))
        }
        return moving
    }

    /**
     * Applies every pose, orders the pages for hit-testing and renders the
     * CSS3D layer, then clears stray scroll offsets.
     */
    fun renderNow() {
        for (pv in previews.values) {
            if (pv.inScene) applyPose(pv.obj, pv.pose, pv.scale)
        }
        applyPose(live.obj, live.pose, 1.0)
        orderHits()
        applyCamera()
        cssRenderer.render(scene, camera)
        val de = cssRenderer.domElement
        if (de.scrollTop != 0.0 || de.scrollLeft != 0.0) { de.scrollTop = 0.0; de.scrollLeft = 0.0 }
        if (element.scrollTop != 0.0 || element.scrollLeft != 0.0) { element.scrollTop = 0.0; element.scrollLeft = 0.0 }
    }

    /**
     * Turns and moves the shared background with this view's camera, so it
     * rotates with it; it moves only a fraction ([BACKDROP_PARALLAX]) of the
     * camera's travel, so the vault never outruns the stars.
     */
    override fun applyBackdropOffset(backdropWorld: Object3) {
        val q = cam.rotation.conjugate()
        val p = q.rotate(-cam.position * BACKDROP_PARALLAX)
        backdropWorld.quaternion.set(q.x, q.y, q.z, q.w)
        backdropWorld.position.set(p.x, p.y, p.z + geometry.cameraDistance)
        backdropWorld.updateMatrixWorld(true)
    }

    private fun applyCamera() {
        val d = geometry.cameraDistance
        camera.position.set(0.0, 0.0, d)
        val q = cam.rotation.conjugate()
        val p = q.rotate(-cam.position)
        world.quaternion.set(q.x, q.y, q.z, q.w)
        world.position.set(p.x, p.y, p.z + d)
        camera.updateMatrixWorld(true)
        scene.updateMatrixWorld(true)
    }

    /**
     * Stacks pages by their depth before the camera (the live page on top)
     * and lets pages at or behind the camera, or seen from behind, ignore
     * the pointer — see the file header.
     */
    private fun orderHits() {
        live.slot.style.zIndex = "3000000"
        val eye = cam.position
        val fwd = cam.forward
        for (pv in previews.values) {
            if (!pv.inScene) continue
            val depth = (pv.pose.position - eye).dot(fwd)
            val facing = (eye - pv.pose.position).dot(pv.pose.normal) > 0
            val z = max(1, min(2_000_000, round(2_000_000 - depth).toInt())).toString()
            if (pv.slot.style.zIndex != z) pv.slot.style.zIndex = z
            val pe = if (depth < 1 || !facing || pv.key == leaving) "none" else "auto"
            if (pv.card.style.getPropertyValue("pointer-events") != pe) pv.card.style.setProperty("pointer-events", pe)
            val opacity = when {
                pv.key == leaving -> "0.7"
                pv.isSlab -> "0.6"
                else -> "1"
            }
            // On the card: opacity on a preserve-3d slot would flatten its edges.
            if (pv.card.style.opacity != opacity) pv.card.style.opacity = opacity
        }
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
        tint(live.box, page?.key)
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
     * Opens a page shown in a preview or slab in the window: in place when
     * its row is in the open outline, the root outline for the vault root,
     * else through its folder (a vault link, which zooms in place under
     * the open outline or opens the node's outline).
     */
    private fun goTo(lineId: LineId?, folderRel: String?) {
        // A pick in free flight: keep holding until the navigation arrives.
        if (holdUntil > 0) hold(NAV_HOLD_MS)
        mode.focusPane(paneId)
        val ids = vm.currentBackingState.documentState?.lineIds
        val row = lineId?.let { ids?.indexOf(it) }?.takeIf { it >= 0 }
        when {
            row != null -> vm.zoomInto(row)
            folderRel == "" -> vm.navigateHome()
            folderRel != null -> vm.navigateToLink(LunarborLink.rooted(folderRel))
        }
    }

    /** Sizes a page's slot (and a preview card's cap) for the current [geometry]. */
    private fun sizeSlot(slot: HTMLElement, card: HTMLElement?) {
        slot.style.width = "${geometry.pageWidth}px"
        slot.style.height = "${geometry.pageHeight}px"
        card?.style?.maxHeight = "${round(geometry.pageHeight * GroveLayout.PREVIEW_HEIGHT_FACTOR)}px"
    }

    /**
     * A page's slab: a box holding the [card] plus its four edges and back,
     * turned into the depth by CSS ([GroveLayout.THICKNESS]).
     */
    private fun slabBox(card: HTMLElement): HTMLElement {
        val box = div("lunarbor-space-box")
        box.appendChild(card)
        for (side in listOf("is-left", "is-right", "is-top", "is-bottom", "is-back")) {
            box.appendChild(div("lunarbor-space-edge $side"))
        }
        return box
    }

    /**
     * The window's live page: a page-sized transparent slot (what the CSS3D
     * object places) holding a slab whose card is as tall as its content —
     * at most the slot, scrolling inside beyond that — with a header and
     * the pane's editor. A drawing or web page fills the slot ([fitContent]).
     */
    private inner class LivePage {
        val slot: HTMLElement = div("lunarbor-space-slot is-live")
        val frame: HTMLElement = div("lunarbor-space-page is-live")
        val head: HTMLElement = div("lunarbor-space-head")
        private val bodyWrap: HTMLElement = div("lunarbor-space-live-body")
        val box: HTMLElement

        /** Where the pane's `MainScreen` mounts ([MainScreen.mountInSpace]). */
        val host: HTMLElement = div("lunarbor-space-live-host")
        val obj: Object3

        /** Where the live page is now (glides to the window's page). */
        var pose: SpacePose = SpacePose.ROOT

        init {
            frame.appendChild(head)
            bodyWrap.appendChild(host)
            frame.appendChild(bodyWrap)
            box = slabBox(frame)
            slot.appendChild(box)
            obj = lib.css3dObject(slot)
            // CSS3DObject turns text selection off on its element; the
            // live page is the editor, where selecting must work.
            slot.style.setProperty("user-select", "auto")
        }

        fun size() = sizeSlot(slot, null)

        /**
         * Sizes the card to its content (`true`), or to the whole slot for
         * views that need the height — a drawing or a web page.
         */
        fun fitContent(fit: Boolean) {
            slot.classList.toggle("is-fill", !fit)
            frame.classList.toggle("is-fill", !fit)
        }
    }

    /**
     * A read-only page: its title and its items as a simple outline in the
     * editor's fonts (a full preview), or its title alone (a slab).
     *
     * @param key Its page key.
     */
    private inner class PreviewPage(val key: String) {
        /** The page-sized transparent slot the CSS3D object places. */
        val slot: HTMLElement = div("lunarbor-space-slot is-preview")

        /** The visible card, as tall as its items (at most [GroveLayout.PREVIEW_HEIGHT_FACTOR] slots). */
        val card: HTMLElement = div("lunarbor-space-page")
        private val box: HTMLElement
        /** The header row: dimmed arrows and the page's breadcrumb ([fillSpaceHead]). */
        private val head: HTMLElement = div("lunarbor-space-head")
        private val body: HTMLElement = div("lunarbor-space-preview-body")

        /** The page's big title, styled like the live page's (`.lunarbor-title`), so it never pops in or out in a flight. */
        private val headline: HTMLElement = div("lunarbor-space-preview-headline")
        val obj: Object3

        /** Where the page is now (glides and turns to its target). */
        var pose: SpacePose = SpacePose.ROOT

        /** Its scale (grows from 0 when it sprouts). */
        var scale: Double = 1.0
        var inScene: Boolean = false

        /** `true` when drawn as a title-only slab. */
        var isSlab: Boolean = false; private set

        /** Its item's row id in the open outline, for [goTo]. */
        var lineId: LineId? = null

        /** Its folder, for [goTo]. */
        var folderRel: String? = null

        /** What [fill] last drew, to skip identical redraws. */
        private var drawn: Any? = null

        private var items: List<SpaceItem> = emptyList()

        init {
            card.appendChild(head)
            card.appendChild(headline)
            card.appendChild(body)
            box = slabBox(card)
            slot.appendChild(box)
            obj = lib.css3dObject(slot)
            tint(box, key)
            card.addEventListener("mousedown", { e ->
                val me = e as MouseEvent
                if (me.button.toInt() != 0) return@addEventListener
                me.preventDefault()
                me.stopPropagation()
                if (key == leaving) return@addEventListener
                val target = me.target as? org.w3c.dom.Element
                val row = target?.closest(".lunarbor-space-item-dot")?.closest(".lunarbor-space-item") as? HTMLElement
                val index = row?.getAttribute("data-index")?.toIntOrNull()
                val item = index?.let { items.getOrNull(it) }
                if (item != null && (item.lineId != null || item.folderRel != null)) {
                    goTo(item.lineId, item.folderRel)
                } else {
                    goTo(lineId, folderRel)
                }
            })
        }

        fun size() = sizeSlot(slot, card)

        /** Draws [titleText] and, unless a [slab], [list]; a no-op when nothing changed. */
        fun fill(titleText: String, list: List<SpaceItem>, slab: Boolean) {
            val folder = folderRel ?: folderOfKey(key)
            val sig = listOf(titleText, if (slab) null else list, slab, folder)
            if (sig == drawn) return
            drawn = sig
            isSlab = slab
            card.classList.toggle("is-slab", slab)
            items = if (slab) emptyList() else list
            val shown = titleText.ifEmpty { if (key == ROOT_KEY) "Home" else "Untitled" }
            fillSpaceHead(head, null, previewCrumbs(folder, shown)) { mode.focusPane(paneId) }
            headline.textContent = shown
            body.innerHTML = ""
            if (slab) return
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
     * A camera flight: a quadratic Bézier from [from]'s position to [to]'s
     * through [ctrl] (pulled back on long hops), turning from one's
     * rotation to the other's, eased in and out.
     *
     * @property t Progress, 0..1.
     * @property duration Seconds.
     */
    private data class Flight(val from: SpacePose, val to: SpacePose, val ctrl: SpaceVec, val t: Double, val duration: Double)

    private companion object {
        /** A preview's indent per level: the editor's (`EditorStyle.indentStepPx`). */
        const val INDENT_STEP_PX = 30

        /** Delay before a non-navigation refresh (typing, listings landing). */
        const val REFRESH_MS = 120

        /** At most this many full previews around the window's page. */
        const val MAX_FULL = 60

        /** At most this many title-only slabs, nearest first. */
        const val MAX_SLABS = 160

        /** How often (seconds) the slabs follow the ship while flying. */
        const val SLAB_FOLLOW_S = 0.5

        /** How long a click in free flight holds the camera, waiting to see whether it opens a page. */
        const val CLICK_HOLD_MS = 400

        /** How long a pick in free flight holds the camera, waiting for its navigation. */
        const val NAV_HOLD_MS = 2500

        /** How much of the camera's travel the starfield follows. */
        const val BACKDROP_PARALLAX = 0.12

        /** The vault root's page key. */
        const val ROOT_KEY = "n:"

        /** The node folder a page key names, or `null` for a note, file or folderless item. */
        fun folderOfKey(key: String): String? = if (key.startsWith("n:")) key.removePrefix("n:") else null

        /** Moves [obj] to [pose], turned like it, at [scale]. */
        fun applyPose(obj: Object3, pose: SpacePose, scale: Double) {
            val p = pose.position
            val q = pose.rotation
            obj.position.set(p.x, p.y, p.z)
            obj.quaternion.set(q.x, q.y, q.z, q.w)
            obj.scale.setScalar(max(scale, 0.001))
        }

        /** A pose from a position and a rotation. */
        fun poseOf(position: SpaceVec, q: SpaceQuat): SpacePose = SpacePose(
            position,
            q.rotate(SpaceVec(1.0, 0.0, 0.0)),
            q.rotate(SpaceVec(0.0, 1.0, 0.0)),
            q.rotate(SpaceVec(0.0, 0.0, 1.0)),
        )

        /** One step ([k] of the way) from [a] towards [b], snapping when close. */
        fun glide(a: SpacePose, b: SpacePose, k: Double): SpacePose {
            if (a == b) return b
            val pos = SpaceVec.lerp(a.position, b.position, k)
            val turned = SpaceQuat.slerp(a.rotation, b.rotation, k)
            val next = poseOf(pos, turned)
            val close = (pos - b.position).length < 0.4 && (next.normal - b.normal).length < 1e-4 && (next.up - b.up).length < 1e-4
            return if (close) b else next
        }

        fun pow3(v: Double) = v * v * v
    }
}
