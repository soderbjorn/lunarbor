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
 *    or a dot in it, zooms the window there.
 *  - **Threads** — an SVG overlay drawing a curve from each child bullet's
 *    dot on the live page to that child's page.
 *
 * The camera follows the pane's state: when `(activeFileRel, zoomedLineId)`
 * changes to another page, the view flies. Editing never moves it. Page
 * places come from `PageSpaceLayout` (commonMain), relative to the page the
 * window is on; places a view has used are remembered, so Back flies back
 * to exactly where the window was.
 *
 * Hard-won rules (from the prototype):
 *  - The three.js camera never moves; the world group moves the opposite
 *    way. Chrome stops hit-testing a CSS3D layer whose camera has moved
 *    behind the viewer.
 *  - Chrome hit-tests CSS3D siblings in DOM order, not depth: every page
 *    gets a z-index from its distance, the live page the highest, and pages
 *    behind the camera ignore the pointer.
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
import se.soderbjorn.lunarbor.main.PageSpaceLayout
import se.soderbjorn.lunarbor.main.PaneBackingViewModel
import se.soderbjorn.lunarbor.main.SpaceChild
import se.soderbjorn.lunarbor.main.SpaceItem
import se.soderbjorn.lunarbor.main.SpacePage
import se.soderbjorn.lunarbor.main.SpaceVec
import se.soderbjorn.lunarbor.main.space.three.Camera3
import se.soderbjorn.lunarbor.main.space.three.Css3DRenderer3
import se.soderbjorn.lunarbor.main.space.three.Object3
import se.soderbjorn.lunarbor.main.space.three.ThreeLib
import se.soderbjorn.lunula.web.layout.PaneTitleSegment
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
 * @param mode The owning mode: render loop, focus, window badges.
 * @param scope Scope for the state collectors; cancelled by [dispose].
 */
internal class PageSpaceView(
    val paneId: String,
    private val lib: ThreeLib,
    private val vm: MainViewModel,
    private val screen: MainScreen,
    private val mode: SpaceMode,
    private val scope: CoroutineScope,
) {
    /** The view's box in the space overlay, positioned by [place]. */
    val element: HTMLElement = div("lunarbor-space-view")

    private val cssRenderer: Css3DRenderer3 = lib.css3dRenderer()
    private val scene: Object3 = lib.scene()

    /** Everything placed in the view; moved opposite to the logical camera. */
    private val world: Object3 = lib.group()

    /** The three.js camera — fixed at `(0, 0, cameraDistance)`, see the file header. */
    val camera: Camera3 = lib.perspectiveCamera(PageSpaceLayout.FOV_DEGREES, 1.0, 1.0, 400_000.0)

    private val threads = document.createElementNS(SVG_NS, "svg") as org.w3c.dom.Element

    /** The view's rectangle inside the overlay, in CSS pixels. */
    var x = 0; private set
    var y = 0; private set
    var w = 1; private set
    var h = 1; private set

    /** Page and camera sizes for the current rectangle. */
    var geometry: PageSpaceGeometry = PageSpaceLayout.geometry(1.0, 1.0); private set

    /** Where the camera logically is (the world moves by its negation). */
    private var cam: SpaceVec = SpaceVec.ZERO

    /** The flight in progress, or `null` at rest. */
    private var flight: Flight? = null

    /** The page the window is on, or `null` until its first state has loaded. */
    var page: SpacePage? = null; private set

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
        element.addEventListener("mousedown", { _ -> mode.focusPane(paneId) }, true)
        element.addEventListener("focusin", { _ -> mode.focusPane(paneId) })
    }

    /**
     * Mounts the window's editor on the live page and starts following the
     * pane. Call once, after the view's element is in the document and
     * [place]d.
     */
    fun start() {
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
    fun dispose(focus: Boolean) {
        jobs.forEach { it.cancel() }
        jobs.clear()
        refreshHandle?.let { window.clearTimeout(it) }
        screen.scrollElement?.removeEventListener("scroll", scrollListener)
        screen.leaveSpace(focus)
        element.remove()
    }

    /** Puts the keyboard in the live page's editor, caret where the pane has it. */
    fun focusEditor() = screen.focusEditor()

    // ------------------------------------------------------------ layout

    /**
     * Moves the view to [nx], [ny] (inside the overlay) and sizes it to
     * [nw] × [nh]. A resize is not navigation: pages and camera jump to
     * their new places without gliding.
     */
    fun place(nx: Int, ny: Int, nw: Int, nh: Int) {
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
        if (f != null) flight = f.copy(to = PageSpaceLayout.cameraFor(at, geometry)) else cam = PageSpaceLayout.cameraFor(at, geometry)
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
            endFlight()
        } else {
            val mid = SpaceVec((cam.x + goal.x) / 2, (cam.y + goal.y) / 2, (cam.z + goal.z) / 2 + dist * 0.3)
            flight = Flight(cam, goal, mid, 0.0, PageSpaceLayout.flightSeconds(dist))
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
        for ((key, pair) in wanted) {
            val (child, depth) = pair
            val at = targets[key] ?: continue
            var pv = previews[key]
            val fresh = pv == null
            if (pv == null) {
                pv = PreviewPage(key)
                previews[key] = pv
            }
            pv.lineId = child.lineId
            pv.folderRel = child.folderRel
            pv.depth = depth
            pv.fill(child.title, child.items, child)
            if (!pv.inScene) {
                val from = parents[key]?.let { placed[it] }
                if (sprout && fresh && from != null) {
                    pv.cur = from
                    pv.scale = 0.0
                } else {
                    pv.cur = at
                    pv.scale = 1.0
                }
                addToScene(pv)
            }
        }
        for ((key, pv) in previews.toList()) {
            if (key == leaving) continue
            if (key !in wanted && pv.inScene) removeFromScene(pv)
            if (key !in wanted && key != leaving) previews.remove(key)
        }
        renderBadges()
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
    fun tick(dt: Double): Boolean {
        val reduced = prefersReducedMotion()
        val k = if (reduced) 1.0 else 1 - exp(-dt * 6)
        val ks = if (reduced) 1.0 else 1 - exp(-dt * 3.5)
        var moving = false
        for (pv in previews.values) {
            if (!pv.inScene) continue
            val t = if (pv.key == leaving) pv.cur else targets[pv.key] ?: pv.cur
            val next = lerp(pv.cur, t, k)
            pv.cur = if (distance(next, t) < 0.4) t else next
            pv.scale = if (1 - pv.scale < 0.002) 1.0 else pv.scale + (1 - pv.scale) * ks
            if (pv.cur != t || pv.scale < 1.0) moving = true
        }
        val f = flight
        if (f != null) {
            val t = min(1.0, f.t + dt / f.duration)
            flight = f.copy(t = t)
            val e = if (t < 0.5) 4 * t * t * t else 1 - pow3(-2 * t + 2) / 2
            val u = 1 - e
            cam = SpaceVec(
                u * u * f.from.x + 2 * u * e * f.ctrl.x + e * e * f.to.x,
                u * u * f.from.y + 2 * u * e * f.ctrl.y + e * e * f.to.y,
                u * u * f.from.z + 2 * u * e * f.ctrl.z + e * e * f.to.z,
            )
            if (t >= 1) {
                cam = f.to
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
        }
        renderNow()
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
    fun applyBackdropOffset(backdropWorld: Object3) {
        val d = geometry.cameraDistance
        backdropWorld.position.set(-cam.x, -cam.y, d - cam.z)
        backdropWorld.updateMatrixWorld(true)
    }

    private fun applyCamera() {
        val d = geometry.cameraDistance
        camera.position.set(0.0, 0.0, d)
        world.position.set(-cam.x, -cam.y, d - cam.z)
        camera.updateMatrixWorld(true)
        scene.updateMatrixWorld(true)
    }

    /**
     * Stacks pages by distance from the camera (the live page on top) and
     * lets pages at or behind the camera ignore the pointer — see the file
     * header.
     */
    private fun orderHits() {
        live.slot.style.zIndex = "100000"
        for (pv in previews.values) {
            if (!pv.inScene) continue
            val behind = pv.cur.z > cam.z - 1
            val z = max(1, round(50_000 - (cam.z - pv.cur.z)).toInt()).toString()
            if (pv.el.style.zIndex != z) pv.el.style.zIndex = z
            val pe = if (behind || pv.key == leaving) "none" else "auto"
            if (pv.card.style.getPropertyValue("pointer-events") != pe) pv.card.style.setProperty("pointer-events", pe)
            val opacity = when (pv.depth) {
                -1 -> "0.7"
                2 -> "0.45"
                else -> "1"
            }
            if (pv.el.style.opacity != opacity) pv.el.style.opacity = opacity
        }
    }

    /**
     * Redraws the threads: from the live page's edge, level with each
     * child bullet's dot (clamped to the page), to that child's page.
     */
    private fun updateThreads() {
        val p = page
        if (p == null || flight != null) {
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
            var yy = pr.top + 30
            val row = c.lineId?.let { ids?.indexOf(it) }?.takeIf { it >= 0 }
            val dot = row?.let { screen.bulletElementOfRow(it) }
            if (dot != null) {
                val r = dot.getBoundingClientRect()
                yy = max(br.top + 6, min(br.bottom - 6, r.top + r.height / 2))
            }
            val x1 = (if (side > 0) pr.right else pr.left) - box.left
            val y1 = yy - box.top
            val x2 = (if (side > 0) kr.left else kr.right) - box.left
            val y2 = kr.top + min(24.0, kr.height / 2) - box.top
            val bend = max(30.0, kotlin.math.abs(x2 - x1) * 0.45)
            sb.append("<path class=\"lunarbor-space-thread\" d=\"M${f1(x1)} ${f1(y1)} C${f1(x1 + side * bend)} ${f1(y1)} ${f1(x2 - side * bend)} ${f1(y2)} ${f1(x2)} ${f1(y2)}\"/>")
            sb.append("<circle class=\"lunarbor-space-thread-end\" cx=\"${f1(x1)}\" cy=\"${f1(y1)}\" r=\"3\"/>")
            sb.append("<circle class=\"lunarbor-space-thread-end\" cx=\"${f1(x2)}\" cy=\"${f1(y2)}\" r=\"2.5\"/>")
        }
        val html = sb.toString()
        if (threads.innerHTML != html) threads.innerHTML = html
    }

    // ------------------------------------------------------------- heads

    /**
     * Rebuilds the live page's header: Back / Forward, the breadcrumb of
     * the window's whole location (every segment but the last navigates)
     * and the window badges.
     */
    private fun renderLiveHead() {
        // The page key and child pages on the view, for tests and inspection.
        element.setAttribute("data-page-key", page?.key ?: "")
        element.setAttribute("data-children", page?.children?.joinToString(",") { it.key } ?: "")
        val head = live.head
        head.innerHTML = ""
        val state = vm.currentBackingState
        head.appendChild(navButton("‹", "Back (⌥⌘←)", vm.canZoomBack(state)) { vm.zoomBack() })
        head.appendChild(navButton("›", "Forward (⌥⌘→)", vm.canZoomForward(state)) { vm.zoomForward() })
        val crumbs = div("lunarbor-space-crumbs")
        val segments: List<PaneTitleSegment> = mode.breadcrumbOf(paneId)
        segments.forEachIndexed { i, seg ->
            if (i > 0) crumbs.appendChild(span("lunarbor-space-crumb-sep", "›"))
            val onClick = seg.onClick
            val el = span(if (onClick != null && i < segments.lastIndex) "lunarbor-space-crumb is-link" else "lunarbor-space-crumb", seg.label)
            if (onClick != null && i < segments.lastIndex) {
                el.addEventListener("mousedown", { e ->
                    e.preventDefault()
                    mode.focusPane(paneId)
                    onClick()
                })
            }
            crumbs.appendChild(el)
        }
        head.appendChild(crumbs)
        head.appendChild(live.badges)
        renderBadges()
    }

    /** Refreshes the window badges on every page this view shows ([SpaceMode.badgesFor]). */
    fun renderBadges() {
        val p = page
        fillBadges(live.badges, p?.key)
        for (pv in previews.values) if (pv.inScene) fillBadges(pv.badges, pv.key)
        live.frame.classList.toggle("is-focused", mode.isFocused(paneId))
    }

    private fun fillBadges(host: HTMLElement, key: String?) {
        val badges = if (key == null) emptyList() else mode.badgesFor(key)
        val sig = badges.joinToString("|") { "${it.first}:${it.second}" }
        if (host.getAttribute("data-sig") == sig) return
        host.setAttribute("data-sig", sig)
        host.innerHTML = ""
        for ((label, focused) in badges) host.appendChild(span(if (focused) "lunarbor-space-badge is-focused" else "lunarbor-space-badge", label))
    }

    private fun navButton(glyph: String, title: String, enabled: Boolean, go: () -> Unit): HTMLElement {
        val b = document.createElement("button") as HTMLElement
        b.className = "lunarbor-space-nav"
        b.textContent = glyph
        b.title = title
        b.setAttribute("aria-label", title)
        if (!enabled) b.setAttribute("disabled", "")
        b.addEventListener("mousedown", { e ->
            e.preventDefault()
            if (!enabled) return@addEventListener
            mode.focusPane(paneId)
            go()
        })
        return b
    }

    // ---------------------------------------------------------- previews

    /**
     * Zooms the window to an item shown in a preview: in place when its
     * row is in the open outline, else through its folder (a `lunarbor:`
     * link, which expands bullets on the way or opens the node's outline).
     */
    private fun goTo(lineId: LineId?, folderRel: String?) {
        mode.focusPane(paneId)
        val ids = vm.currentBackingState.documentState?.lineIds
        val row = lineId?.let { ids?.indexOf(it) }?.takeIf { it >= 0 }
        when {
            row != null -> vm.zoomInto(row)
            folderRel != null -> vm.navigateToLink(LunarborLink.format(folderRel))
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
        val badges: HTMLElement = div("lunarbor-space-badges")
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
     * A read-only page: a header (the page's title and window badges) and
     * its items as a simple outline in the editor's fonts.
     *
     * @param key Its page key (changes when [rekeyPreviews] carries it over).
     */
    private inner class PreviewPage(var key: String) {
        /** The page-sized transparent slot the CSS3D object places. */
        val el: HTMLElement = div("lunarbor-space-slot")

        /** The visible card, as tall as its items (at most the slot). */
        val card: HTMLElement = div("lunarbor-space-page")
        val badges: HTMLElement = div("lunarbor-space-badges")
        private val title: HTMLElement = div("lunarbor-space-preview-title")
        private val body: HTMLElement = div("lunarbor-space-preview-body")
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

        init {
            val head = div("lunarbor-space-head")
            head.appendChild(title)
            head.appendChild(badges)
            card.appendChild(head)
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
                    goTo(lineId, folderRel)
                }
            })
        }

        private var items: List<SpaceItem> = emptyList()

        /** Draws [titleText] and [list]; a no-op when nothing changed. */
        fun fill(titleText: String, list: List<SpaceItem>, child: SpaceChild?) {
            val sig = Triple(titleText, list, child?.children?.map { it.key })
            if (sig == drawn) return
            drawn = sig
            items = list
            title.textContent = titleText.ifEmpty { "Untitled" }
            body.innerHTML = ""
            if (list.isEmpty()) {
                body.appendChild(div("lunarbor-space-preview-empty").also { it.textContent = "…" })
                return
            }
            list.forEachIndexed { i, item ->
                val row = div("lunarbor-space-item")
                row.setAttribute("data-index", i.toString())
                row.style.paddingLeft = "${item.depth * 20}px"
                val opens = item.lineId != null || item.folderRel != null
                val dot = span(if (item.folderRel != null) "lunarbor-space-item-dot is-node" else "lunarbor-space-item-dot", "")
                if (!opens) dot.classList.add("is-inert")
                row.appendChild(dot)
                row.appendChild(span("lunarbor-space-item-text", item.title.ifEmpty { " " }))
                body.appendChild(row)
            }
        }
    }

    /**
     * A camera flight: a quadratic Bézier from [from] to [to] through
     * [ctrl] (pulled back on long hops), eased in and out.
     *
     * @property t Progress, 0..1.
     * @property duration Seconds.
     */
    private data class Flight(val from: SpaceVec, val to: SpaceVec, val ctrl: SpaceVec, val t: Double, val duration: Double)

    private fun allChildren(p: SpacePage): List<SpaceChild> = p.children + p.children.flatMap { it.children }

    private companion object {
        const val SVG_NS = "http://www.w3.org/2000/svg"

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
