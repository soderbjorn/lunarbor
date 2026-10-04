/*
 * MapView.kt (jsMain)
 * -------------------
 * 3D mode's map shapes (LBR-11: Crown, Cone, Galaxy): the vault's node
 * tree as bodies in space, with branches to their parents, `lunarbor:`
 * links as arcs between them, leaf bullets as dust around their node and
 * the active tab's windows as cards joined by a line to the node each one
 * shows. `SpaceMode` shows it in place of the page views when a map shape
 * is picked, and drives it from its render loop ([tick]).
 *
 * Where things come from:
 *  - the graph ([VaultGraphBuilder]) from the registry's node listings
 *    (`requestLinkPreview`, which this asks for folder by folder; only a
 *    progress card shows until every listing has landed) and its link index
 *    (`linkIndexSnapshot`);
 *  - positions from [GraphLayout] (commonMain, pure, deterministic);
 *  - folds are the map's own (F), starting from
 *    [GraphLayout.defaultFolds] so a big vault opens readable;
 *  - colours from [SpacePalette]: each top-level area a vivid hue of its
 *    own (3D mode is meant to be more colourful than the theme), each
 *    body a little lighter or shifted, branches in their child's hue and
 *    link arcs blending from one end's hue to the other's.
 *
 * **Privacy (LBR-10):** the graph is built with the app's current privacy
 * filter — an item carrying a hiding tag, or a hidden folder, is left out
 * with its subtree, and links whose file or target is hidden are dropped —
 * and rebuilt whenever `DocumentRegistry.privacyFlow` changes. Window
 * cards sit on the nearest visible body.
 *
 * Input: drag to orbit, right- or Shift-drag to pan, scroll to zoom;
 * click a body to select it and fly there; click it again, or double-click
 * a body, to open its page in Pages to edit it (in the focused window). Keys (while the map has focus): ← → siblings, ↑ parent,
 * ↓ child, ⏎ open in the focused window, P open and show its page, E back
 * to the page of the focused window, F fold, L next shape, ? help
 * ([showSpaceHelp]).
 *
 * Rendering: one WebGL canvas (instanced spheres, one line buffer each
 * for branches and links, points for glow, dust and stars) plus DOM labels
 * (a budget of [LABEL_BUDGET], greedy, never overlapping) and window cards.
 * Frames run only while something moves. Unlike the page views this
 * camera moves freely — there is no CSS3D layer to hit-test here.
 *
 * three.js is reached through [ThreeLib.raw] (dynamic) — experimental code,
 * kept inside this file.
 *
 * jsMain only. No document logic: opening a node is the pane's
 * `MainViewModel.navigateToLink`.
 */

package se.soderbjorn.lunarbor.main.space

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.WheelEvent
import se.soderbjorn.lunarbor.data.LunarborLink
import se.soderbjorn.lunarbor.main.DocumentRegistry
import se.soderbjorn.lunarbor.main.GraphLayout
import se.soderbjorn.lunarbor.main.LinkPreviewItem
import se.soderbjorn.lunarbor.main.SpacePalette
import se.soderbjorn.lunarbor.main.SpaceShape
import se.soderbjorn.lunarbor.main.SpaceVec
import se.soderbjorn.lunarbor.main.VaultGraph
import se.soderbjorn.lunarbor.main.VaultGraphBuilder
import se.soderbjorn.lunarbor.main.space.three.ThreeLib
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The map shapes' view. One per open 3D mode, built lazily by [SpaceMode]
 * the first time a map shape is shown.
 *
 * @param lib The loaded three.js library.
 * @param mode The owning mode: render loop, shape changes, windows.
 * @param registry Listings, links and the privacy mode.
 * @param scope Scope for collectors and link fetches; cancelled by [dispose].
 */
internal class MapView(
    private val lib: ThreeLib,
    private val mode: SpaceMode,
    private val registry: DocumentRegistry,
    private val scope: CoroutineScope,
) {
    /** The map's box in the overlay (absolutely placed by CSS). */
    val element: HTMLElement = div("lunarbor-space-map")

    private val T: dynamic = lib.raw
    private val renderer: dynamic = createRenderer()
    private val scene: dynamic = construct(T.Scene)
    private val camera: dynamic = construct(T.PerspectiveCamera, FOV, 1.0, 0.1, 6000.0)
    private val labelsHost = div("lunarbor-map-labels")
    private val wires = document.createElementNS(SVG_NS, "svg")
    private val windowsHost = div("lunarbor-map-windows")
    private val hud = div("lunarbor-map-hud")
    private val bar = div("lunarbor-map-bar")

    /** The centred progress card shown while node listings are still being read ([renderHud]). */
    private val loading = div("lunarbor-map-loading")

    private var width = 1
    private var height = 1

    /** The shape on screen. */
    var shape: SpaceShape = SpaceShape.CROWN
        private set

    private var graph: VaultGraph = VaultGraph.EMPTY

    /** Area hues ([SpacePalette.areaHues]) from the root's unfiltered listing. */
    private var hues: Map<String, Double> = emptyMap()
    private var folded: MutableSet<String> = HashSet()
    private var foldsSeeded = false
    private val bodies = LinkedHashMap<String, Body>()
    private var selected: String? = null

    /** The body the camera keeps centred while it moves (a selection, a morph). */
    private var follow: String? = null

    // Camera: orbit around a target, current and goal.
    private var target = SpaceVec.ZERO
    private var goalTarget = SpaceVec.ZERO
    private var theta = 0.6
    private var goalTheta = 0.6
    private var phi = 1.05
    private var goalPhi = 1.05
    private var radius = 120.0
    private var goalRadius = 120.0
    private var framed = false

    /**
     * Set once the user moves the camera (orbit, pan, zoom, a selection):
     * from then on a growing graph no longer reframes the view.
     */
    private var userMoved = false

    private var linksByFile: Map<String, Set<String>> = emptyMap()
    private var linksFetchedAt = -1e9
    private var linksJob: Job? = null

    /** `true` while [fetchLinks] waits for the link index (the HUD says "reading links…"). */
    private var linksReading = false

    /** When [open] last ran (`performance.now()`), so a double-click does not open twice. */
    private var openedAt = -1e9

    /** The graph and folds [relayout] last placed; a rebuild that yields both unchanged skips the relayout. */
    private var placed: Pair<VaultGraph, Set<String>>? = null

    /**
     * Set when the link index should be read once every listing has
     * landed ([rebuild]). The link scan reads every note in the vault, and
     * the main process's file reads share one small thread pool, so on a
     * slow disk (a cloud-synced vault) starting it first would queue the
     * listings behind it and leave the map empty for many seconds.
     */
    private var linksWanted = false
    private var rebuildHandle: Int? = null
    private val jobs = mutableListOf<Job>()
    private var dark = true
    private var isShown = false

    // Scene objects.
    private val sphere: dynamic = construct(T.SphereGeometry, 1.0, 24, 16)
    private val bodyMaterial: dynamic = standardMaterial()
    private val bodyMesh: dynamic = construct(T.InstancedMesh, sphere, bodyMaterial, VaultGraphBuilder.MAX_NODES + 1)
    private val ringMesh: dynamic = construct(
        T.InstancedMesh,
        construct(T.TorusGeometry, 1.75, 0.06, 8, 56),
        basicMaterial(0xffffff, 0.8),
        VaultGraphBuilder.MAX_NODES + 1,
    )
    private val selectMesh: dynamic = construct(T.Mesh, construct(T.TorusGeometry, 1.0, 0.035, 8, 72), basicMaterial(0xffffff, 0.95))
    private val dummy: dynamic = construct(T.Object3D)
    private val tmp: dynamic = construct(T.Vector3)
    private val color: dynamic = construct(T.Color)
    private val branches = Lines(0.4)
    private val linkLines = Lines(0.6)
    private val glow = Dots(VaultGraphBuilder.MAX_NODES + 1, 3.6, 0.5)
    private val dust = Dots(DUST_CAP, 0.35, 0.75)
    private val labels = HashMap<String, HTMLElement>()

    /** The distant starfield (built by [buildScene]; declared before `init` so it survives it). */
    private var stars: dynamic = null

    // Pointer state.
    private var dragStart: Pair<Double, Double>? = null
    private var dragLast: Pair<Double, Double>? = null
    private var dragPan = false
    private var dragged = false

    init {
        element.tabIndex = 0
        element.setAttribute("aria-label", "Map of the vault")
        // `renderer` is dynamic: no `?.let` (that would call a JS method named `let`).
        if (renderer != null) {
            val canvas = renderer.domElement.unsafeCast<HTMLCanvasElement>()
            canvas.className = "lunarbor-map-canvas"
            element.appendChild(canvas)
        } else {
            element.appendChild(div("lunarbor-map-empty").also { it.textContent = "The map needs WebGL, which is not available here." })
        }
        wires.setAttribute("class", "lunarbor-map-wires")
        wires.setAttribute("aria-hidden", "true")
        element.appendChild(labelsHost)
        element.appendChild(wires)
        element.appendChild(windowsHost)
        element.appendChild(hud)
        element.appendChild(bar)
        element.appendChild(loading)
        buildScene()
        installInput()
    }

    // ------------------------------------------------------------ lifecycle

    /**
     * Shows the map in [newShape]: starts following listings, links and the
     * privacy mode (first show), relays out and takes the keyboard.
     */
    fun show(newShape: SpaceShape) {
        val changed = newShape != shape
        shape = newShape
        element.style.display = ""
        if (!isShown) {
            isShown = true
            jobs += scope.launch { registry.linkPreviewsFlow.collect { scheduleRebuild() } }
            jobs += scope.launch {
                var lastRevision = -1
                var lastFilter: Any? = null
                registry.privacyFlow.collect { pv ->
                    if (pv.filter != lastFilter || pv.revision != lastRevision) {
                        val first = lastFilter == null
                        lastFilter = pv.filter
                        lastRevision = pv.revision
                        if (!first) linksWanted = true
                        scheduleRebuild(0)
                    }
                }
            }
            linksWanted = true
            rebuild()
        } else if (changed) {
            relayout(reframe = true)
        }
        renderHud()
        focus()
        mode.requestFrame()
    }

    /** Hides the map (a switch to Pages); collectors keep running cheaply. */
    fun hide() {
        element.style.display = "none"
    }

    /** Frees the WebGL context and stops every collector. */
    fun dispose() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        linksJob?.cancel()
        rebuildHandle?.let { window.clearTimeout(it) }
        renderer?.dispose()
        element.remove()
    }

    /** Puts the keyboard on the map (its keys work only while it has focus). */
    fun focus() {
        element.focus()
    }

    /** Sizes the map to [w] × [h] CSS pixels. */
    fun place(w: Int, h: Int) {
        if (w == width && h == height) return
        width = max(1, w)
        height = max(1, h)
        renderer?.setSize(width, height)
        camera.aspect = width.toDouble() / height
        camera.updateProjectionMatrix()
        mode.requestFrame()
    }

    /** The tab's windows or focus changed: redraw the window cards. */
    fun onLayoutChanged() {
        renderHud()
        mode.requestFrame()
    }

    /** Re-reads the theme's colours (bodies, lines, glow blending). */
    fun applyTheme() {
        val css = window.getComputedStyle(document.documentElement!!)
        dark = luminanceOfCss(css.getPropertyValue("--t-bg").trim().ifEmpty { "#1e1e1e" }) < 0.5
        val accent = normalizeCssColor(css.getPropertyValue("--t-accent").trim().ifEmpty { "#7aa2ff" })
        val dim = normalizeCssColor(css.getPropertyValue("--t-text-dim").trim().ifEmpty { "#8a8f9c" })
        selectMesh.material.color.setStyle(accent)
        val blend = if (dark) T.AdditiveBlending else T.NormalBlending
        branches.material.opacity = if (dark) 0.5 else 0.6
        linkLines.material.opacity = if (dark) 0.85 else 0.7
        linkLines.material.blending = blend
        linkLines.material.needsUpdate = true
        glow.material.blending = blend
        glow.material.opacity = if (dark) 0.5 else 0.22
        glow.material.needsUpdate = true
        dust.material.blending = blend
        dust.material.needsUpdate = true
        stars?.material?.color?.setStyle(dim)
        stars?.material?.blending = blend
        stars?.material?.opacity = if (dark) 0.8 else 0.35
        stars?.material?.needsUpdate = true
        for (b in bodies.values) b.colored = false
        mode.requestFrame()
    }

    // --------------------------------------------------------------- graph

    private fun scheduleRebuild(delay: Int = REBUILD_MS) {
        if (rebuildHandle != null) return
        rebuildHandle = window.setTimeout({
            rebuildHandle = null
            rebuild()
        }, delay)
    }

    /**
     * Builds the graph from the listings known now (asking for the rest)
     * under the current privacy filter, recomputes links and relays out.
     */
    private fun rebuild() {
        val filter = registry.privacyFilter
        val hiddenItem: (String, LinkPreviewItem) -> Boolean = { _, item ->
            filter.isActive && (filter.hides(item.tagKeys) || item.pathRel?.let { registry.isPathHidden(it, filter) } == true)
        }
        val g = VaultGraphBuilder.build(ROOT_TITLE, { registry.requestLinkPreview(it) }, hiddenItem)
        hues = SpacePalette.areaHues(registry.requestLinkPreview("").orEmpty().mapNotNull { it.pathRel })
        val links = VaultGraphBuilder.linkEdges(g, linksByFile) { filter.isActive && registry.isPathHidden(it, filter) }
        graph = g.copy(links = links)
        if (!foldsSeeded && g.nodes.values.all { it.loaded }) {
            foldsSeeded = true
            folded = GraphLayout.defaultFolds(graph).toMutableSet()
        }
        folded.retainAll(graph.nodes.keys)
        if (selected != null && selected !in graph.nodes) selected = null
        if (follow != null && follow !in graph.nodes) follow = null
        // Links only once the bodies are in (see [linksWanted]).
        if (g.nodes.values.all { it.loaded }) {
            if (linksWanted) {
                linksWanted = false
                fetchLinks(force = true)
            } else if (window.performance.now() - linksFetchedAt > LINKS_REFRESH_MS) {
                fetchLinks(force = false)
            }
        }
        // While listings are still being read only the progress card shows: the
        // map appears once, whole, instead of growing in jerky steps.
        if (!g.nodes.values.all { it.loaded }) {
            renderHud()
            return
        }
        // Listing refreshes (after every save, on focus) mostly change nothing:
        // skip the relayout then, which costs a noticeable pause on a big map.
        if (placed == graph to folded.toSet()) {
            renderHud()
            return
        }
        // Keep the whole map in view while listings land, until the user takes over.
        relayout(reframe = !framed || !userMoved)
    }

    /** Reads the link index (after saving open documents) and rebuilds when it lands. */
    private fun fetchLinks(force: Boolean) {
        if (!force && linksJob?.isActive == true) return
        linksFetchedAt = window.performance.now()
        linksJob?.cancel()
        linksReading = true
        linksJob = scope.launch {
            val fresh = try {
                registry.linkIndexSnapshot()
            } catch (t: Throwable) {
                linksReading = false
                console.warn("Lunarbor: map could not read links", t)
                return@launch
            }
            linksReading = false
            if (fresh != linksByFile) {
                linksByFile = fresh
                scheduleRebuild(0)
            } else {
                renderHud()
            }
        }
    }

    /**
     * Gives every visible body its target in the current shape; new bodies
     * start at their parent's place, folded-away ones fly into their
     * anchor and go.
     */
    private fun relayout(reframe: Boolean) {
        placed = graph to folded.toSet()
        val positions = GraphLayout.layout(graph, folded, shape)
        for ((id, b) in bodies.toList()) {
            if (id !in positions) {
                val anchor = GraphLayout.visibleAnchor(graph, folded, id)?.let { positions[it] }
                if (anchor == null) bodies.remove(id) else { b.target = anchor; b.leaving = true }
            }
        }
        for ((id, p) in positions) {
            val node = graph.nodes[id] ?: continue
            val b = bodies.getOrPut(id) {
                Body(id).also { nb ->
                    val from = node.parent?.let { bodies[it]?.cur } ?: p
                    nb.cur = from
                    nb.scale = 0.0
                }
            }
            b.leaving = false
            b.target = p
            b.size = sizeOf(id)
            b.colored = false
        }
        if (reframe && positions.isNotEmpty()) frame(positions)
        renderHud()
        mode.requestFrame()
    }

    /** Points the camera at the whole map (or keeps following the selection). */
    private fun frame(positions: Map<String, SpaceVec>) {
        framed = true
        val sel = selected?.let { positions[it] }
        if (sel != null) {
            follow = selected
            return
        }
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var minZ = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
        for (p in positions.values) {
            minX = min(minX, p.x); minY = min(minY, p.y); minZ = min(minZ, p.z)
            maxX = max(maxX, p.x); maxY = max(maxY, p.y); maxZ = max(maxZ, p.z)
        }
        follow = null
        goalTarget = SpaceVec((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2)
        val extent = max(max(maxX - minX, maxZ - minZ), (maxY - minY) * 1.2)
        val aspect = max(0.6, min(1.0, width.toDouble() / height))
        goalRadius = (extent * 0.5 / tan(FOV / 2 * PI / 180)) * 0.8 / aspect + 8
    }

    /** A body's radius: the root largest, others by how much is under them. */
    private fun sizeOf(id: String): Double {
        if (id.isEmpty()) return 1.8
        val n = graph.nodes[id] ?: return 0.6
        val sub = if (id in folded) graph.subtreeOf(id).size else 1 + n.children.size
        return min(1.8, 0.55 + 0.26 * ln(1.0 + sub + n.leafCount / 4.0) / ln(2.0))
    }

    // ------------------------------------------------------------- frames

    /**
     * Advances [dt] seconds: bodies glide to their places, the camera to
     * its goal; then draws. Called by [SpaceMode]'s render loop.
     *
     * @return `true` while anything still moves.
     */
    fun tick(dt: Double): Boolean {
        if (!isShown || element.style.display == "none") return false
        val reduced = prefersReducedMotion()
        val k = if (reduced) 1.0 else 1 - exp(-dt * 4.0)
        val kc = if (reduced) 1.0 else 1 - exp(-dt * 5.0)
        var moving = false
        for ((id, b) in bodies.toList()) {
            val next = lerp(b.cur, b.target, k)
            b.cur = if (dist(next, b.target) < 0.01) b.target else next
            b.scale = if (b.leaving) b.scale * (1 - k) else if (1 - b.scale < 0.01) 1.0 else b.scale + (1 - b.scale) * k
            if (b.leaving && (b.scale < 0.02 || b.cur == b.target)) {
                bodies.remove(id)
                continue
            }
            if (b.cur != b.target || (b.scale < 1.0 && !b.leaving) || b.leaving) moving = true
        }
        follow?.let { f -> bodies[f]?.let { goalTarget = it.cur } }
        val nt = lerp(target, goalTarget, kc)
        target = if (dist(nt, goalTarget) < 0.005 * max(1.0, goalRadius / 50)) goalTarget else nt
        theta = approach(theta, goalTheta, kc)
        phi = approach(phi, goalPhi, kc)
        radius = approach(radius, goalRadius, kc)
        if (target != goalTarget || theta != goalTheta || phi != goalPhi || radius != goalRadius) moving = true
        render()
        return moving
    }

    private fun approach(a: Double, b: Double, k: Double): Double {
        val n = a + (b - a) * k
        return if (abs(n - b) < 1e-4 * max(1.0, abs(b))) b else n
    }

    /** Applies everything to the scene, renders, then places labels and window cards. */
    private fun render() {
        val r = renderer ?: return
        camera.position.set(
            target.x + radius * sin(phi) * cos(theta),
            target.y + radius * cos(phi),
            target.z + radius * sin(phi) * sin(theta),
        )
        camera.lookAt(target.x, target.y, target.z)
        camera.updateMatrixWorld()
        writeBodies()
        writeLines()
        r.render(scene, camera)
        project()
        placeLabels()
        placeWindows()
    }

    private fun writeBodies() {
        var i = 0
        var rings = 0
        var dots = 0
        for (b in bodies.values) {
            val s = b.size * b.scale
            dummy.position.set(b.cur.x, b.cur.y, b.cur.z)
            dummy.rotation.set(0.0, 0.0, 0.0)
            dummy.scale.set(s, s, s)
            dummy.updateMatrix()
            bodyMesh.setMatrixAt(i, dummy.matrix)
            if (!b.colored) {
                bodyColor(b.id)
                bodyMesh.setColorAt(i, color)
                b.r = color.r as Double; b.g = color.g as Double; b.b = color.b as Double
                b.colored = true
                b.slot = -1
            }
            if (b.slot != i) {
                color.setRGB(b.r, b.g, b.b)
                bodyMesh.setColorAt(i, color)
                b.slot = i
            }
            glow.set(i, b.cur, b.r, b.g, b.b)
            if (b.id in folded && !b.leaving) {
                dummy.rotation.set(PI / 2 - 0.38, GraphLayout.hash01(b.id + "|r") * 0.6 - 0.3, 0.0)
                dummy.updateMatrix()
                ringMesh.setMatrixAt(rings, dummy.matrix)
                ringMesh.setColorAt(rings, color.setRGB(b.r, b.g, b.b))
                rings++
            }
            val leaves = if (b.leaving) 0 else min(graph.nodes[b.id]?.leafCount ?: 0, DUST_PER_BODY)
            for (j in 0 until leaves) {
                if (dots >= DUST_CAP) break
                val a = GraphLayout.hash01("${b.id}|d$j") * 2 * PI
                val rr = s * (2.0 + GraphLayout.hash01("${b.id}|r$j") * 1.4)
                val yy = (GraphLayout.hash01("${b.id}|h$j") - 0.5) * s * 1.2
                dust.set(dots, SpaceVec(b.cur.x + cos(a) * rr, b.cur.y + yy, b.cur.z + sin(a) * rr), b.r, b.g, b.b)
                dots++
            }
            i++
        }
        bodyMesh.count = i
        bodyMesh.instanceMatrix.needsUpdate = true
        if (bodyMesh.instanceColor != null) bodyMesh.instanceColor.needsUpdate = true
        ringMesh.count = rings
        ringMesh.instanceMatrix.needsUpdate = true
        if (ringMesh.instanceColor != null) ringMesh.instanceColor.needsUpdate = true
        glow.commit(i)
        dust.commit(dots)
        val sel = selected?.let { bodies[it] }
        selectMesh.visible = sel != null
        if (sel != null) {
            selectMesh.position.set(sel.cur.x, sel.cur.y, sel.cur.z)
            val s = sel.size * sel.scale * 1.7
            selectMesh.scale.set(s, s, s)
            selectMesh.quaternion.copy(camera.quaternion)
        }
    }

    private fun writeLines() {
        branches.begin()
        for (b in bodies.values) {
            val p = graph.nodes[b.id]?.parent ?: continue
            val pb = bodies[p] ?: continue
            branches.segment(pb.cur, b.cur, pb.r, pb.g, pb.b, b.r, b.g, b.b)
        }
        branches.commit()
        linkLines.begin()
        for ((a, c) in graph.links) {
            val pa = GraphLayout.visibleAnchor(graph, folded, a)?.let { bodies[it] } ?: continue
            val pc = GraphLayout.visibleAnchor(graph, folded, c)?.let { bodies[it] } ?: continue
            if (pa === pc) continue
            // An arc bowed upwards (outwards from the root in the cone).
            val mid = SpaceVec((pa.cur.x + pc.cur.x) / 2, (pa.cur.y + pc.cur.y) / 2, (pa.cur.z + pc.cur.z) / 2)
            val lift = dist(pa.cur, pc.cur) * 0.28
            val ctrl = SpaceVec(mid.x * 1.12, mid.y + lift, mid.z * 1.12)
            var prev = pa.cur
            var prevT = 0.0
            for (s in 1..ARC_STEPS) {
                val t = s.toDouble() / ARC_STEPS
                val u = 1 - t
                val q = SpaceVec(
                    u * u * pa.cur.x + 2 * u * t * ctrl.x + t * t * pc.cur.x,
                    u * u * pa.cur.y + 2 * u * t * ctrl.y + t * t * pc.cur.y,
                    u * u * pa.cur.z + 2 * u * t * ctrl.z + t * t * pc.cur.z,
                )
                linkLines.segment(
                    prev, q,
                    mix(pa.r, pc.r, prevT), mix(pa.g, pc.g, prevT), mix(pa.b, pc.b, prevT),
                    mix(pa.r, pc.r, t), mix(pa.g, pc.g, t), mix(pa.b, pc.b, t),
                )
                prev = q
                prevT = t
            }
        }
        linkLines.commit()
    }

    /** Sets [color] for body [id]: the root in the theme's text colour, others by area hue. */
    private fun bodyColor(id: String) {
        val node = graph.nodes[id]
        if (id.isEmpty() || node == null) {
            color.setStyle(if (dark) "#f4f1e6" else "#5b5446")
            return
        }
        val base = SpacePalette.hueOf(id, hues) ?: GraphLayout.hash01(node.area.lowercase())
        // Siblings vary a little around their area's hue; deeper bodies are lighter.
        val hue = (base + (GraphLayout.hash01("$id|hue") - 0.5) * 0.06 + 1) % 1
        val light = (if (dark) 0.55 else 0.45) + min(node.depth - 1, 4) * 0.03
        val sat = if (dark) 0.88 else 0.78
        color.setHSL(hue, if (node.loaded) sat else sat * 0.35, if (node.loaded) light else light * 0.85)
    }

    // ---------------------------------------------------------- projection

    /** Screen positions of every body ([Body.sx], [Body.sy], [Body.sr]), in view pixels. */
    private fun project() {
        val halfH = height / 2.0
        val focal = halfH / tan(FOV / 2 * PI / 180)
        val cx = camera.position.x as Double
        val cy = camera.position.y as Double
        val cz = camera.position.z as Double
        for (b in bodies.values) {
            tmp.set(b.cur.x, b.cur.y, b.cur.z)
            tmp.project(camera)
            val nz = tmp.z as Double
            b.front = nz > -1 && nz < 1
            b.sx = ((tmp.x as Double) + 1) / 2 * width
            b.sy = (1 - (tmp.y as Double)) / 2 * height
            val d = sqrt((b.cur.x - cx) * (b.cur.x - cx) + (b.cur.y - cy) * (b.cur.y - cy) + (b.cur.z - cz) * (b.cur.z - cz))
            b.dist = d
            b.sr = if (d > 0.01) b.size * b.scale * focal / d else 0.0
        }
    }

    /** The body under the pointer at ([x], [y]) view pixels, or `null`. */
    private fun pick(x: Double, y: Double): String? {
        var best: Body? = null
        var bestD = Double.MAX_VALUE
        for (b in bodies.values) {
            if (!b.front || b.leaving) continue
            val dx = b.sx - x
            val dy = b.sy - y
            val d = sqrt(dx * dx + dy * dy)
            if (d <= max(10.0, b.sr + 5) && (d < bestD - 0.5 || (abs(d - bestD) <= 0.5 && b.dist < (best?.dist ?: Double.MAX_VALUE)))) {
                best = b
                bestD = d
            }
        }
        return best?.id
    }

    /**
     * Shows at most [LABEL_BUDGET] labels: the selection, the windows'
     * bodies, the selection's family and the top levels first, then the
     * biggest on screen; a label that would overlap one already placed is
     * skipped.
     */
    private fun placeLabels() {
        val windowBodies = windowAnchors().values.toSet()
        val sel = selected?.let { graph.nodes[it] }
        val family = HashSet<String>()
        if (sel != null) {
            family += sel.children
            sel.parent?.let { family += it }
        }
        val candidates = bodies.values.filter { it.front && !it.leaving && it.scale > 0.5 && it.sx > -40 && it.sx < width + 40 && it.sy > -20 && it.sy < height + 20 }
        val scored = candidates.map { b ->
            val node = graph.nodes[b.id]
            val score = when {
                b.id == selected -> 1e7
                b.id in windowBodies -> 1e6
                b.id in family -> 1e5 + b.sr
                (node?.depth ?: 9) <= 1 -> 1e4 - (node?.depth ?: 0) * 100 + b.sr
                else -> b.sr * 100
            }
            b to score
        }.sortedByDescending { it.second }
        val placed = ArrayList<DoubleArray>()
        val shown = HashSet<String>()
        for ((b, _) in scored) {
            if (shown.size >= LABEL_BUDGET) break
            val node = graph.nodes[b.id] ?: continue
            val text = labelText(b.id)
            val w = text.length * 6.6 + 22
            val h = 20.0
            val x = b.sx - w / 2
            val y = b.sy + max(b.sr, 3.0) + 4
            val rect = doubleArrayOf(x, y, x + w, y + h)
            if (b.id != selected && placed.any { overlaps(it, rect) }) continue
            placed += rect
            shown += b.id
            val el = labels.getOrPut(b.id) { newLabel(b.id) }
            if (el.getAttribute("data-text") != text) {
                el.setAttribute("data-text", text)
                el.lastElementChild?.textContent = text
            }
            el.classList.toggle("is-selected", b.id == selected)
            el.classList.toggle("is-top", node.depth <= 1)
            el.style.setProperty("--c", cssColorOf(b))
            el.style.transform = "translate(${round1(b.sx)}px, ${round1(y)}px) translate(-50%, 0)"
            el.style.opacity = if (b.id == selected || b.id in windowBodies) "1" else (0.55 + 0.45 * min(1.0, b.sr / 6)).toString()
            if (el.style.display == "none") el.style.display = ""
        }
        for ((id, el) in labels.toList()) {
            if (id !in shown) {
                if (id !in bodies) {
                    el.remove()
                    labels.remove(id)
                } else if (el.style.display != "none") {
                    el.style.display = "none"
                }
            }
        }
    }

    private fun labelText(id: String): String {
        val node = graph.nodes[id] ?: return ""
        return if (id in folded && node.children.isNotEmpty()) "${node.title} · ${graph.subtreeOf(id).size - 1}" else node.title
    }

    private fun newLabel(id: String): HTMLElement {
        val el = div("lunarbor-map-label")
        el.appendChild(span("lunarbor-map-label-dot", ""))
        el.appendChild(span("lunarbor-map-label-text", ""))
        el.addEventListener("mousedown", { e ->
            val me = e as MouseEvent
            if (me.button.toInt() != 0) return@addEventListener
            me.preventDefault()
            me.stopPropagation()
            focus()
            select(id, fly = true)
        })
        el.addEventListener("dblclick", { e ->
            e.preventDefault()
            e.stopPropagation()
            open(id, showPage = false)
        })
        labelsHost.appendChild(el)
        return el
    }

    private fun cssColorOf(b: Body): String =
        "rgb(${(b.r * 255).toInt().coerceIn(0, 255)}, ${(b.g * 255).toInt().coerceIn(0, 255)}, ${(b.b * 255).toInt().coerceIn(0, 255)})"

    private fun overlaps(a: DoubleArray, b: DoubleArray): Boolean =
        a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]

    // ------------------------------------------------------------- windows

    /** Each shown window's body: the node it is on, or its nearest visible ancestor. */
    private fun windowAnchors(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (p in mode.mapPanes()) {
            val vm = mode.viewModelOf(p.id) ?: continue
            val st = vm.currentBackingState
            val folder = vm.currentNodeFolder(st) ?: st.activeFileRel.substringBeforeLast('/', "")
            val anchor = graph.anchorOf(folder)?.let { GraphLayout.visibleAnchor(graph, folded, it) } ?: continue
            out[p.id] = anchor
        }
        return out
    }

    /**
     * Places a card per window of the tab beside the body it shows, with a
     * line to the body; a body off screen pins its card to the nearest edge.
     */
    private fun placeWindows() {
        val anchors = windowAnchors()
        val focused = mode.focusedPaneId()
        val panes = mode.mapPanes()
        val sig = panes.joinToString("|") { "${it.id}:${it.label}:${anchors[it.id]}:${it.id == focused}" }
        if (windowsHost.getAttribute("data-sig") != sig) {
            windowsHost.setAttribute("data-sig", sig)
            windowsHost.innerHTML = ""
            // A window moved or loaded: the dock's window chips name its page.
            mode.refreshDock()
            for (p in panes) {
                val anchor = anchors[p.id] ?: continue
                val card = div(if (p.id == focused) "lunarbor-map-window is-focused" else "lunarbor-map-window")
                card.setAttribute("data-pane", p.id)
                card.appendChild(span("lunarbor-map-window-title", graph.nodes[anchor]?.title ?: ""))
                card.title = "${p.label} — click to focus, double-click to write in it"
                card.addEventListener("mousedown", { e ->
                    e.preventDefault()
                    e.stopPropagation()
                    mode.focusPaneFromMap(p.id)
                    select(anchor, fly = true)
                    focus()
                })
                card.addEventListener("dblclick", { e ->
                    e.preventDefault()
                    mode.focusPaneFromMap(p.id)
                    mode.setShape(SpaceShape.PAGES)
                })
                windowsHost.appendChild(card)
            }
        }
        val sb = StringBuilder()
        val perBody = HashMap<String, Int>()
        for (p in panes) {
            val anchor = anchors[p.id] ?: continue
            val b = bodies[anchor] ?: continue
            val card = windowsHost.querySelector("[data-pane=\"${p.id}\"]") as? HTMLElement ?: continue
            val nth = perBody.getOrPut(anchor) { 0 }
            perBody[anchor] = nth + 1
            val cw = max(card.offsetWidth, 60).toDouble()
            val ch = max(card.offsetHeight, 22).toDouble()
            var bx = b.sx
            var by = b.sy
            if (!b.front) {
                // Behind the camera: mirror, so the card points the way.
                bx = width - bx
                by = height - by
            }
            val off = max(b.sr, 4.0) + 26
            var x = bx + off
            var y = by - off - ch - nth * (ch + 6)
            val pad = 8.0
            x = x.coerceIn(pad, max(pad, width - cw - pad))
            y = y.coerceIn(pad + 26, max(pad + 26, height - ch - pad - 44))
            card.style.transform = "translate(${round1(x)}px, ${round1(y)}px)"
            val ax = if (bx < x) x else if (bx > x + cw) x + cw else bx
            val ay = if (by < y) y else y + ch
            sb.append("<line class=\"lunarbor-map-wire${if (p.id == focused) " is-focused" else ""}\" x1=\"${round1(ax)}\" y1=\"${round1(ay)}\" x2=\"${round1(bx)}\" y2=\"${round1(by)}\"/>")
        }
        val html = sb.toString()
        if (wires.innerHTML != html) wires.innerHTML = html
    }

    // --------------------------------------------------------- navigation

    /** Selects [id] (or nothing); with [fly], the camera flies there and follows it. */
    private fun select(id: String?, fly: Boolean) {
        selected = id
        if (id != null) userMoved = true
        if (id != null && fly) {
            follow = id
            val node = graph.nodes[id]
            val sub = if (node == null) 1 else graph.subtreeOf(id).size
            goalRadius = (16 + 7 * sqrt(sub.toDouble())).coerceIn(16.0, 260.0)
        }
        renderHud()
        mode.requestFrame()
    }

    /**
     * Opens [id] in the focused window ([se.soderbjorn.lunarbor.main.MainViewModel.navigateToLink]
     * to its folder); with [showPage], switches to Pages to write there.
     */
    private fun open(id: String, showPage: Boolean) {
        openedAt = window.performance.now()
        val vm = mode.focusedPaneId()?.let { mode.viewModelOf(it) } ?: return
        selected = id
        // With [showPage], Pages opens once the window has arrived, so its
        // view starts on that page instead of flying there from the old one.
        val arrived: () -> Unit = {
            if (showPage) mode.setShape(SpaceShape.PAGES)
            mode.requestFrame()
        }
        if (id.isEmpty()) {
            vm.navigateHome()
            arrived()
        } else {
            vm.navigateToLink(LunarborLink.format(id)) { arrived() }
        }
        renderHud()
        mode.requestFrame()
    }

    /** Folds or unfolds [id] (bodies under it fly in or out). */
    private fun toggleFold(id: String) {
        val node = graph.nodes[id] ?: return
        if (node.children.isEmpty()) return
        if (!folded.remove(id)) folded += id
        relayout(reframe = false)
    }

    /** Keyboard walk: siblings, parent, first child. */
    private fun walk(key: String) {
        val cur = selected?.let { graph.nodes[it] } ?: graph.root ?: return
        if (selected == null) {
            select(cur.id, fly = true)
            return
        }
        val next: String? = when (key) {
            "ArrowUp" -> cur.parent
            "ArrowDown" -> {
                if (cur.id in folded) toggleFold(cur.id)
                cur.children.firstOrNull()
            }
            "ArrowLeft", "ArrowRight" -> {
                val sibs = cur.parent?.let { graph.nodes[it]?.children }.orEmpty()
                val i = sibs.indexOf(cur.id)
                if (sibs.isEmpty() || i < 0) null
                else sibs[(i + (if (key == "ArrowRight") 1 else -1) + sibs.size) % sibs.size]
            }
            else -> null
        }
        if (next != null) select(next, fly = true)
    }

    // --------------------------------------------------------------- input

    private fun installInput() {
        element.addEventListener("contextmenu", { e -> e.preventDefault() })
        element.addEventListener("mousedown", { e ->
            val me = e as MouseEvent
            val t = me.target as? org.w3c.dom.Element
            if (t != null && (t.closest(".lunarbor-map-window, .lunarbor-map-label, .lunarbor-map-bar button") != null)) return@addEventListener
            focus()
            dragStart = me.clientX.toDouble() to me.clientY.toDouble()
            dragLast = dragStart
            dragPan = me.button.toInt() == 2 || me.shiftKey
            dragged = false
            me.preventDefault()
            window.addEventListener("mousemove", onMove)
            window.addEventListener("mouseup", onUp)
        })
        element.addEventListener("dblclick", { e ->
            val me = e as MouseEvent
            val t = me.target as? org.w3c.dom.Element
            if (t != null && t.closest(".lunarbor-map-window, .lunarbor-map-label, .lunarbor-map-bar, .lunarbor-map-hud") != null) return@addEventListener
            val box = element.getBoundingClientRect()
            // The click before it may already have opened the body (a click on the selection).
            if (window.performance.now() - openedAt < 600) return@addEventListener
            pick(me.clientX - box.left, me.clientY - box.top)?.let { open(it, showPage = true) }
        })
        element.addEventListener("wheel", { e ->
            val we = e as WheelEvent
            we.preventDefault()
            // A trackpad pinch arrives as a ctrl-wheel with small deltas: zoom much harder per unit.
            val rate = when {
                we.deltaMode == 1 -> 0.04
                we.ctrlKey -> PINCH_ZOOM_RATE
                else -> WHEEL_ZOOM_RATE
            }
            val factor = exp(we.deltaY * rate)
            goalRadius = (goalRadius * factor).coerceIn(4.0, 4000.0)
            userMoved = true
            mode.requestFrame()
        }, js("({ passive: false })"))
        element.addEventListener("keydown", { e -> onKey(e as KeyboardEvent) })
    }

    private val onMove: (Event) -> Unit = { e ->
        val me = e as MouseEvent
        val last = dragLast
        val start = dragStart
        if (last != null && start != null) {
            val x = me.clientX.toDouble()
            val y = me.clientY.toDouble()
            if (!dragged && (abs(x - start.first) > CLICK_SLOP || abs(y - start.second) > CLICK_SLOP)) {
                dragged = true
                userMoved = true
            }
            if (dragged) {
                val dx = x - last.first
                val dy = y - last.second
                if (dragPan) pan(dx, dy) else {
                    goalTheta += dx * 0.006
                    goalPhi = (goalPhi - dy * 0.006).coerceIn(0.12, PI - 0.12)
                    theta = goalTheta
                    phi = goalPhi
                }
                mode.requestFrame()
            }
            dragLast = x to y
        }
    }

    private val onUp: (Event) -> Unit = { e ->
        val me = e as MouseEvent
        window.removeEventListener("mousemove", onMove)
        window.removeEventListener("mouseup", onUp)
        if (!dragged && dragStart != null && me.button.toInt() == 0) {
            val box = element.getBoundingClientRect()
            val hit = pick(me.clientX - box.left, me.clientY - box.top)
            // A click on the body already selected opens it for editing.
            if (hit != null && hit == selected && me.detail <= 1) open(hit, showPage = true)
            else select(hit, fly = hit != null)
        }
        dragStart = null
        dragLast = null
    }

    /** Moves the target sideways / up in screen space (and stops following). */
    private fun pan(dx: Double, dy: Double) {
        follow = null
        val scale = radius * 2 * tan(FOV / 2 * PI / 180) / height
        // Camera right = (-sin θ, 0, cos θ); camera up ≈ world up tilted by φ.
        val rx = -sin(theta)
        val rz = cos(theta)
        val ux = -cos(phi) * cos(theta)
        val uy = sin(phi)
        val uz = -cos(phi) * sin(theta)
        val mx = (-dx * rx + dy * ux) * scale
        val my = (dy * uy) * scale
        val mz = (-dx * rz + dy * uz) * scale
        goalTarget = SpaceVec(goalTarget.x + mx, goalTarget.y + my, goalTarget.z + mz)
        target = goalTarget
    }

    private fun onKey(e: KeyboardEvent) {
        if (e.metaKey || e.ctrlKey || e.altKey) return
        val sel = selected
        val handled = when (e.key) {
            "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight" -> { walk(e.key); true }
            "Enter" -> { sel?.let { open(it, showPage = false) }; sel != null }
            "p", "P" -> { sel?.let { open(it, showPage = true) }; sel != null }
            "e", "E" -> { mode.setShape(SpaceShape.PAGES); true }
            "f", "F" -> { sel?.let { toggleFold(it) }; sel != null }
            "l", "L" -> { mode.nextShape(); true }
            "?" -> { showSpaceHelp(shape); true }
            "Home" -> { select(null, fly = false); userMoved = false; relayout(reframe = true); true }
            else -> false
        }
        if (handled) {
            e.preventDefault()
            e.stopPropagation()
        }
    }

    // ----------------------------------------------------------------- HUD

    /** The shape and count (top left) and the selection's actions (bottom). */
    /**
     * Shows the progress card while node listings are still being read
     * (a large or cloud-synced vault can take a while), hides it after.
     * Called by [renderHud].
     *
     * @param total Bodies known so far.
     * @param unread Those whose listing has not landed yet.
     */
    private fun renderLoading(total: Int, unread: Int) {
        loading.style.display = if (unread == 0) "none" else ""
        if (unread == 0) return
        val read = total - unread
        loading.innerHTML = ""
        loading.appendChild(span("lunarbor-map-loading-text", "Reading the vault… $read of $total nodes"))
        val track = div("lunarbor-map-loading-track")
        // The total grows as listings land (each names its children), so the bar can step back a little.
        track.appendChild(div("lunarbor-map-loading-fill").also { it.style.width = "${(100.0 * read / max(1, total)).coerceIn(4.0, 100.0)}%" })
        loading.appendChild(track)
    }

    private fun renderHud() {
        val total = graph.nodes.size
        val loading = graph.nodes.values.count { !it.loaded }
        renderLoading(total, loading)
        hud.innerHTML = ""
        hud.appendChild(span("lunarbor-map-hud-shape", shape.label))
        hud.appendChild(span("lunarbor-map-hud-count", buildString {
            append("$total node${if (total == 1) "" else "s"}")
            if (graph.links.isNotEmpty()) append(" · ${graph.links.size} link${if (graph.links.size == 1) "" else "s"}")
            if (loading > 0) append(" · reading $loading…")
            else if (linksReading || linksWanted) append(" · reading links…")
            if (graph.truncated) append(" · first ${VaultGraphBuilder.MAX_NODES} shown")
        }))
        hud.appendChild(span("lunarbor-map-hud-keys", "Click a selected body or double-click to edit · drag to orbit · ⇧/right-drag to pan · scroll to zoom · ←→↑↓ walk · ⏎ open · F fold · L shape · ? help"))
        bar.innerHTML = ""
        val id = selected
        val node = id?.let { graph.nodes[it] }
        bar.style.display = if (node == null) "none" else ""
        if (node == null) return
        val crumbs = div("lunarbor-map-crumbs")
        val chain = generateSequence(node) { n -> n.parent?.let { graph.nodes[it] } }.toList().asReversed()
        chain.forEachIndexed { i, n ->
            if (i > 0) crumbs.appendChild(span("lunarbor-map-crumb-sep", "›"))
            val c = span(if (i == chain.lastIndex) "lunarbor-map-crumb is-current" else "lunarbor-map-crumb", n.title)
            if (i < chain.lastIndex) c.addEventListener("mousedown", { e -> e.preventDefault(); select(n.id, fly = true) })
            crumbs.appendChild(c)
        }
        bar.appendChild(crumbs)
        fun action(label: String, key: String, go: () -> Unit) {
            val b = document.createElement("button") as HTMLElement
            b.className = "lunarbor-space-button"
            b.innerHTML = "<span>$label</span><kbd>$key</kbd>"
            b.addEventListener("click", { _ -> go(); focus() })
            bar.appendChild(b)
        }
        action("Edit page", "P") { open(node.id, showPage = true) }
        action("Open in window", "⏎") { open(node.id, showPage = false) }
        if (node.children.isNotEmpty()) action(if (node.id in folded) "Unfold" else "Fold", "F") { toggleFold(node.id) }
    }

    // --------------------------------------------------------------- scene

    private fun buildScene() {
        scene.add(construct(T.AmbientLight, 0xffffff, 0.6))
        val sun = construct(T.DirectionalLight, 0xffffff, 1.0)
        sun.position.set(-30.0, 60.0, 40.0)
        scene.add(sun)
        val moon = construct(T.PointLight, 0xfff2da, 1.6, 0.0, 0.0)
        scene.add(moon)
        bodyMesh.count = 0
        bodyMesh.frustumCulled = false
        scene.add(bodyMesh)
        ringMesh.count = 0
        ringMesh.frustumCulled = false
        scene.add(ringMesh)
        selectMesh.visible = false
        scene.add(selectMesh)
        scene.add(branches.obj)
        scene.add(linkLines.obj)
        scene.add(glow.obj)
        scene.add(dust.obj)
        // Distant stars on a sphere, the same every time.
        val n = 2200
        val pos = float32(n * 3)
        for (i in 0 until n) {
            val u = GraphLayout.hash01("s$i") * 2 - 1
            val th = GraphLayout.hash01("t$i") * 2 * PI
            val r = 1800 + GraphLayout.hash01("r$i") * 1800
            val q = sqrt(1 - u * u)
            pos[i * 3] = cos(th) * q * r
            pos[i * 3 + 1] = u * r
            pos[i * 3 + 2] = sin(th) * q * r
        }
        val g = construct(T.BufferGeometry)
        g.setAttribute("position", construct(T.BufferAttribute, pos, 3))
        val params: dynamic = js("({})")
        params.size = 1.6
        params.sizeAttenuation = false
        params.transparent = true
        params.depthWrite = false
        params.map = construct(T.CanvasTexture, dotSprite())
        stars = construct(T.Points, g, construct(T.PointsMaterial, params))
        stars.frustumCulled = false
        scene.add(stars)
        applyTheme()
    }

    private fun createRenderer(): dynamic {
        val t = T
        return try {
            val r = js("new t.WebGLRenderer({ antialias: true, alpha: true })")
            r.setClearColor(0, 0.0)
            r.setPixelRatio(min(2.0, window.devicePixelRatio))
            r
        } catch (_: Throwable) {
            null
        }
    }

    private fun standardMaterial(): dynamic {
        val p: dynamic = js("({})")
        p.roughness = 0.55
        p.metalness = 0.05
        p.emissive = construct(T.Color, 0x101014)
        return construct(T.MeshStandardMaterial, p)
    }

    private fun basicMaterial(hex: Int, opacity: Double): dynamic {
        val p: dynamic = js("({})")
        p.color = hex
        p.transparent = true
        p.opacity = opacity
        return construct(T.MeshBasicMaterial, p)
    }

    /** A growable, per-vertex-coloured line-segment buffer, refilled every drawn frame. */
    private inner class Lines(opacity: Double) {
        private var capacity = 0
        private var array: dynamic = null
        private var colors: dynamic = null
        private var count = 0
        private val geometry: dynamic = construct(T.BufferGeometry)
        val material: dynamic = run {
            val p: dynamic = js("({})")
            p.color = 0xffffff
            p.transparent = true
            p.opacity = opacity
            p.depthWrite = false
            p.vertexColors = true
            construct(T.LineBasicMaterial, p)
        }
        val obj: dynamic = construct(T.LineSegments, geometry, material)

        init {
            obj.frustumCulled = false
        }

        fun begin() {
            count = 0
        }

        /** A segment from [a] (coloured [ar], [ag], [ab]) to [b] (coloured [br], [bg], [bb]). */
        fun segment(a: SpaceVec, b: SpaceVec, ar: Double, ag: Double, ab: Double, br: Double, bg: Double, bb: Double) {
            if ((count + 1) * 6 > capacity) grow()
            val o = count * 6
            array[o] = a.x; array[o + 1] = a.y; array[o + 2] = a.z
            array[o + 3] = b.x; array[o + 4] = b.y; array[o + 5] = b.z
            colors[o] = ar; colors[o + 1] = ag; colors[o + 2] = ab
            colors[o + 3] = br; colors[o + 4] = bg; colors[o + 5] = bb
            count++
        }

        private fun grow() {
            val next = max(6 * 256, capacity * 2)
            val fresh = float32(next)
            val freshColors = float32(next)
            if (array != null) {
                fresh.set(array)
                freshColors.set(colors)
            }
            array = fresh
            colors = freshColors
            capacity = next
            val attr = construct(T.BufferAttribute, array, 3)
            attr.setUsage(T.DynamicDrawUsage)
            geometry.setAttribute("position", attr)
            val colorAttr = construct(T.BufferAttribute, colors, 3)
            colorAttr.setUsage(T.DynamicDrawUsage)
            geometry.setAttribute("color", colorAttr)
        }

        fun commit() {
            if (array == null) grow()
            geometry.setDrawRange(0, count * 2)
            geometry.attributes.position.needsUpdate = true
            geometry.attributes.color.needsUpdate = true
        }
    }

    /** A fixed-size, per-point-coloured sprite cloud (glow, dust). */
    private inner class Dots(cap: Int, size: Double, opacity: Double) {
        private val pos: dynamic = float32(cap * 3)
        private val col: dynamic = float32(cap * 3)
        private val geometry: dynamic = construct(T.BufferGeometry)
        val material: dynamic
        val obj: dynamic

        init {
            val pa = construct(T.BufferAttribute, pos, 3)
            pa.setUsage(T.DynamicDrawUsage)
            val ca = construct(T.BufferAttribute, col, 3)
            ca.setUsage(T.DynamicDrawUsage)
            geometry.setAttribute("position", pa)
            geometry.setAttribute("color", ca)
            val p: dynamic = js("({})")
            p.size = size
            p.sizeAttenuation = true
            p.vertexColors = true
            p.transparent = true
            p.depthWrite = false
            p.opacity = opacity
            p.map = construct(T.CanvasTexture, dotSprite())
            material = construct(T.PointsMaterial, p)
            obj = construct(T.Points, geometry, material)
            obj.frustumCulled = false
        }

        fun set(i: Int, at: SpaceVec, r: Double, g: Double, b: Double) {
            val o = i * 3
            pos[o] = at.x; pos[o + 1] = at.y; pos[o + 2] = at.z
            col[o] = r; col[o + 1] = g; col[o + 2] = b
        }

        fun commit(n: Int) {
            geometry.setDrawRange(0, n)
            geometry.attributes.position.needsUpdate = true
            geometry.attributes.color.needsUpdate = true
        }
    }

    /**
     * One drawn body: where it is, where it is going and how it last
     * projected.
     */
    private class Body(val id: String) {
        var cur: SpaceVec = SpaceVec.ZERO
        var target: SpaceVec = SpaceVec.ZERO
        var scale = 1.0
        var size = 1.0
        var leaving = false
        var colored = false
        var slot = -1
        var r = 1.0
        var g = 1.0
        var b = 1.0
        var sx = 0.0
        var sy = 0.0
        var sr = 0.0
        var dist = 0.0
        var front = false
    }

    private companion object {
        const val SVG_NS = "http://www.w3.org/2000/svg"
        const val FOV = 50.0
        const val ROOT_TITLE = "Home"
        const val LABEL_BUDGET = 40
        const val REBUILD_MS = 220

        /** How far (px) the pointer may move between press and release and still count as a click. */
        const val CLICK_SLOP = 5

        /** Zoom per wheel pixel of a mouse wheel or two-finger scroll (`exp(deltaY * rate)`). */
        const val WHEEL_ZOOM_RATE = 0.003

        /** Zoom per wheel pixel of a trackpad pinch (a ctrl-wheel; its deltas are small). */
        const val PINCH_ZOOM_RATE = 0.018

        const val LINKS_REFRESH_MS = 4000.0
        const val ARC_STEPS = 12
        const val DUST_PER_BODY = 14
        const val DUST_CAP = 12000

        fun mix(a: Double, b: Double, t: Double) = a + (b - a) * t

        fun lerp(a: SpaceVec, b: SpaceVec, k: Double) = SpaceVec(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k, a.z + (b.z - a.z) * k)

        fun dist(a: SpaceVec, b: SpaceVec): Double {
            val dx = a.x - b.x
            val dy = a.y - b.y
            val dz = a.z - b.z
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        fun round1(v: Double): String = (kotlin.math.round(v * 10) / 10).toString()

        /** A zeroed `Float32Array` of [length] (a function, so minified names never reach `js()`). */
        fun float32(length: Int): dynamic {
            val len = length
            return js("new Float32Array(len)")
        }

        /** `new ctor(...args)` for a three.js class reached dynamically. */
        fun construct(ctor: dynamic, vararg args: dynamic): dynamic {
            val a = args
            return js("Reflect.construct(ctor, a)")
        }

        /** A soft round white dot (tinted by the material or vertex colours). */
        fun dotSprite(): HTMLCanvasElement {
            val c = document.createElement("canvas") as HTMLCanvasElement
            c.width = 64
            c.height = 64
            val ctx = c.getContext("2d") as CanvasRenderingContext2D
            val g = ctx.createRadialGradient(32.0, 32.0, 0.0, 32.0, 32.0, 32.0)
            g.addColorStop(0.0, "rgba(255,255,255,1)")
            g.addColorStop(0.35, "rgba(255,255,255,0.5)")
            g.addColorStop(1.0, "rgba(255,255,255,0)")
            ctx.fillStyle = g
            ctx.fillRect(0.0, 0.0, 64.0, 64.0)
            return c
        }
    }
}

/** 3D mode's map CSS (appended to the space stylesheet by `ensureSpaceStyles`). */
internal val MAP_CSS: String = """
.lunarbor-space.is-map .lunarbor-space-backdrop, .lunarbor-space.is-map .lunarbor-space-views { display: none; }
.lunarbor-space-map { position: absolute; inset: 36px 8px 48px 8px; overflow: hidden; outline: none; border-radius: 10px; cursor: grab; }
.lunarbor-space-map:active { cursor: grabbing; }
.lunarbor-map-canvas { position: absolute; inset: 0; width: 100%; height: 100%; display: block; }
.lunarbor-map-empty { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; color: var(--t-text-dim, #9aa0a6); font: 14px var(--dt-font-prop, system-ui, sans-serif); }
.lunarbor-map-labels, .lunarbor-map-windows { position: absolute; inset: 0; pointer-events: none; }
.lunarbor-map-wires { position: absolute; inset: 0; width: 100%; height: 100%; pointer-events: none; overflow: visible; }
.lunarbor-map-wire { stroke: var(--t-text-dim, #9aa0a6); stroke-opacity: .55; stroke-width: 1; stroke-dasharray: 3 3; }
.lunarbor-map-wire.is-focused { stroke: var(--t-accent, #7aa2ff); stroke-opacity: .9; stroke-dasharray: none; }
.lunarbor-map-label {
    position: absolute; left: 0; top: 0; pointer-events: auto; cursor: pointer; white-space: nowrap;
    display: inline-flex; align-items: center; gap: 5px; padding: 1px 7px 1px 5px; border-radius: 6px;
    font: 11.5px var(--dt-font-prop, system-ui, sans-serif); color: var(--t-text, #e6e6e6);
    background: color-mix(in srgb, var(--t-bg, #1e1e1e) 62%, transparent);
    max-width: 220px; overflow: hidden; text-overflow: ellipsis; user-select: none;
}
.lunarbor-map-label.is-top { font-weight: 600; font-size: 12.5px; }
.lunarbor-map-label.is-selected { box-shadow: 0 0 0 1px var(--t-accent, #7aa2ff); background: color-mix(in srgb, var(--t-surface, #252526) 90%, transparent); }
.lunarbor-map-label-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--c, currentColor); flex: none; }
.lunarbor-map-label-text { overflow: hidden; text-overflow: ellipsis; }
.lunarbor-map-window {
    position: absolute; left: 0; top: 0; pointer-events: auto; cursor: pointer; white-space: nowrap;
    display: inline-flex; align-items: center; gap: 6px; padding: 4px 9px; border-radius: 8px;
    font: 12px var(--dt-font-prop, system-ui, sans-serif); color: var(--t-text, #e6e6e6);
    background: color-mix(in srgb, var(--t-surface, #252526) 88%, transparent);
    border: 1px solid var(--t-border, rgba(255,255,255,.14)); box-shadow: 0 8px 24px rgba(0,0,0,.25);
    max-width: 240px;
}
.lunarbor-map-window.is-focused { border-color: var(--t-accent, #7aa2ff); }
.lunarbor-map-window-title { overflow: hidden; text-overflow: ellipsis; }
.lunarbor-map-loading {
    position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%); pointer-events: none;
    display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 12px 16px; border-radius: 10px;
    background: color-mix(in srgb, var(--t-surface, #252526) 80%, transparent); border: 1px solid var(--t-border, rgba(255,255,255,.12));
    font: 12.5px var(--dt-font-prop, system-ui, sans-serif); color: var(--t-text-dim, #9aa0a6);
}
.lunarbor-map-loading-track { width: 220px; height: 4px; border-radius: 2px; overflow: hidden; background: color-mix(in srgb, var(--t-text, #e6e6e6) 12%, transparent); }
.lunarbor-map-loading-fill { height: 100%; border-radius: 2px; background: var(--t-accent, #7aa2ff); transition: width .25s ease; }
@media (prefers-reduced-motion: reduce) { .lunarbor-map-loading-fill { transition: none; } }
.lunarbor-map-hud {
    position: absolute; left: 12px; top: 10px; right: 12px; display: flex; gap: 10px; align-items: baseline; pointer-events: none;
    font: 12px var(--dt-font-prop, system-ui, sans-serif); color: var(--t-text-dim, #9aa0a6);
}
.lunarbor-map-hud-shape { color: var(--t-text, #e6e6e6); font-weight: 600; font-size: 13px; }
.lunarbor-map-hud-keys { margin-left: auto; opacity: .75; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; min-width: 0; }
.lunarbor-map-bar {
    position: absolute; left: 12px; bottom: 10px; right: 12px; display: flex; gap: 6px; align-items: center;
    font: 12px var(--dt-font-prop, system-ui, sans-serif);
}
.lunarbor-map-crumbs { flex: 1; min-width: 0; display: flex; gap: 4px; align-items: center; overflow: hidden; white-space: nowrap; color: var(--t-text-dim, #9aa0a6); }
.lunarbor-map-crumb { cursor: pointer; overflow: hidden; text-overflow: ellipsis; }
.lunarbor-map-crumb:hover { color: var(--t-text, #e6e6e6); text-decoration: underline; }
.lunarbor-map-crumb.is-current { color: var(--t-text, #e6e6e6); cursor: default; text-decoration: none; font-weight: 600; }
.lunarbor-map-crumb-sep { opacity: .6; }
.lunarbor-space-shapes { display: inline-flex; gap: 2px; margin-right: auto; -webkit-app-region: no-drag; }
.lunarbor-space-shape {
    -webkit-app-region: no-drag; border: 0; background: none; border-radius: 6px; padding: 4px 9px; cursor: pointer;
    color: var(--t-text-dim, #9aa0a6); font: 12px var(--dt-font-prop, system-ui, sans-serif);
}
.lunarbor-space-shape:hover { color: var(--t-text, #e6e6e6); }
.lunarbor-space-shape[aria-pressed="true"] { color: var(--t-text, #e6e6e6); background: color-mix(in srgb, var(--t-surface, #252526) 85%, transparent); box-shadow: inset 0 0 0 1px var(--t-border, rgba(255,255,255,.12)); }
.lunarbor-space.is-map .lunarbor-space-split { display: none; }
"""
