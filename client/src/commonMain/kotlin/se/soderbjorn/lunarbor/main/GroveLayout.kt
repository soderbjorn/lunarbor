/*
 * GroveLayout.kt (commonMain)
 * ---------------------------
 * The pure rules behind 3D mode's "Grove" space (web `main/space/GroveView.kt`),
 * the variant of Pages laid out over the whole vault: every node with
 * children is a page — a slab of real size and a little thickness — at a
 * fixed place and angle in one tree. Home faces the camera at the origin;
 * its child pages hang further out round the direction it faces away from
 * its reader — left, right, up, down and every diagonal, as many as there
 * are — each turned to face back towards it, and their children hang
 * further out again round them.
 *
 *  - **Poses** ([SpacePose], [SpaceQuat]): a position and an orthonormal
 *    frame per page; the camera has one too, and flights turn it
 *    (spherical interpolation).
 *  - **Layout** ([GroveLayout.layout]): radial from Home — seen from the
 *    root, every subtree owns a cell of directions of its own, so no page
 *    ever overlaps another however many children a node has; a big branch
 *    simply pushes its own deeper pages further out. Deterministic — the
 *    same tree and view always give the same places, so Back flies
 *    exactly back.
 *  - **Tree** ([GroveLayout.treeOf]): the whole vault's pages from the
 *    vault's node graph (the maps' [VaultGraph]) with the window's live
 *    page ([SpacePage], from `PaneBackingViewModel.spacePageOf`) spliced in.
 *
 * Shares [SpaceVec], [PageSpaceGeometry], [SpaceTree], [SpacePage] and
 * [PageSpaceKeys] with Pages (`PageSpaceLayout.kt`).
 *
 * commonMain only — no DOM, no state, no I/O.
 */

package se.soderbjorn.lunarbor.main

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A rotation as a unit quaternion — how a page or the camera is turned.
 * Used for flights ([slerp]) and handed to three.js as is.
 */
data class SpaceQuat(val x: Double, val y: Double, val z: Double, val w: Double) {
    /** The inverse rotation (for a unit quaternion). */
    fun conjugate(): SpaceQuat = SpaceQuat(-x, -y, -z, w)

    /** This rotation after [o] (Hamilton product `this · o`). */
    operator fun times(o: SpaceQuat): SpaceQuat = SpaceQuat(
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
        w * o.w - x * o.x - y * o.y - z * o.z,
    )

    /** [v] turned by this rotation. */
    fun rotate(v: SpaceVec): SpaceVec {
        val u = SpaceVec(x, y, z)
        val t = u.cross(v) * 2.0
        return v + t * w + u.cross(t)
    }

    companion object {
        /** No rotation. */
        val IDENTITY: SpaceQuat = SpaceQuat(0.0, 0.0, 0.0, 1.0)

        /**
         * The rotation taking the x, y and z axes to [right], [up] and
         * [normal] (an orthonormal right-handed basis).
         */
        fun fromBasis(right: SpaceVec, up: SpaceVec, normal: SpaceVec): SpaceQuat {
            val m00 = right.x; val m01 = up.x; val m02 = normal.x
            val m10 = right.y; val m11 = up.y; val m12 = normal.y
            val m20 = right.z; val m21 = up.z; val m22 = normal.z
            val trace = m00 + m11 + m22
            val q = when {
                trace > 0 -> {
                    val s = 0.5 / sqrt(trace + 1.0)
                    SpaceQuat((m21 - m12) * s, (m02 - m20) * s, (m10 - m01) * s, 0.25 / s)
                }
                m00 > m11 && m00 > m22 -> {
                    val s = 2.0 * sqrt(1.0 + m00 - m11 - m22)
                    SpaceQuat(0.25 * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
                }
                m11 > m22 -> {
                    val s = 2.0 * sqrt(1.0 + m11 - m00 - m22)
                    SpaceQuat((m01 + m10) / s, 0.25 * s, (m12 + m21) / s, (m02 - m20) / s)
                }
                else -> {
                    val s = 2.0 * sqrt(1.0 + m22 - m00 - m11)
                    SpaceQuat((m02 + m20) / s, (m12 + m21) / s, 0.25 * s, (m10 - m01) / s)
                }
            }
            return q.unit()
        }

        /** Spherical interpolation from [a] (t = 0) to [b] (t = 1), the short way round. */
        fun slerp(a: SpaceQuat, b0: SpaceQuat, t: Double): SpaceQuat {
            var cosHalf = a.x * b0.x + a.y * b0.y + a.z * b0.z + a.w * b0.w
            val b = if (cosHalf < 0) { cosHalf = -cosHalf; SpaceQuat(-b0.x, -b0.y, -b0.z, -b0.w) } else b0
            if (cosHalf > 0.9995) {
                return SpaceQuat(
                    a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t, a.w + (b.w - a.w) * t,
                ).unit()
            }
            val half = acos(cosHalf.coerceIn(-1.0, 1.0))
            val sinHalf = sin(half)
            val ka = sin((1 - t) * half) / sinHalf
            val kb = sin(t * half) / sinHalf
            return SpaceQuat(a.x * ka + b.x * kb, a.y * ka + b.y * kb, a.z * ka + b.z * kb, a.w * ka + b.w * kb)
        }

        private fun SpaceQuat.unit(): SpaceQuat {
            val l = sqrt(x * x + y * y + z * z + w * w)
            return if (l < 1e-12) IDENTITY else SpaceQuat(x / l, y / l, z / l, w / l)
        }
    }
}

/**
 * Where a page (or the camera) is and how it is turned: an orthonormal,
 * right-handed frame.
 *
 * @property position A page's slot centre, or the camera's eye.
 * @property right The page's rightward direction (text runs this way).
 * @property up The page's upward direction.
 * @property normal The way the page faces — towards whoever reads it. A
 *   camera looks along `-normal`.
 */
data class SpacePose(
    val position: SpaceVec,
    val right: SpaceVec = SpaceVec(1.0, 0.0, 0.0),
    val up: SpaceVec = SpaceVec(0.0, 1.0, 0.0),
    val normal: SpaceVec = SpaceVec(0.0, 0.0, 1.0),
) {
    /** The direction away from the reader: where this page's child pages hang. */
    val forward: SpaceVec get() = -normal

    /** The frame as a rotation from the x / y / z axes. */
    val rotation: SpaceQuat get() = SpaceQuat.fromBasis(right, up, normal)

    companion object {
        /** The root page: at the origin, facing the viewer. */
        val ROOT: SpacePose = SpacePose(SpaceVec.ZERO)
    }
}

/**
 * Where Grove's pages hang. Called by the web `GroveView` whenever the
 * tree changes (a page sprouting, listings landing, the privacy mode) and
 * on resize; tested in `GroveLayoutTest`. Sizes come from
 * [PageSpaceLayout.geometry], as in Pages.
 */
object GroveLayout {
    /** How tall a preview card may grow, in page heights. */
    const val PREVIEW_HEIGHT_FACTOR: Double = 1.5

    /** A page's thickness (its slab edge), in CSS pixels. */
    const val THICKNESS: Double = 14.0

    /** Space between neighbouring pages, as a share of the page width. */
    const val GAP_SHARE: Double = 0.25

    /** The cone round the root's axis kept free (behind the root page), in radians (20°). */
    const val HOLE: Double = 20 * PI / 180

    /** How far off the root's axis pages may hang, in radians (100°: a little behind its plane). */
    const val CAP: Double = 100 * PI / 180

    /** The root's child pages hang at least this far from it. */
    const val FIRST_RADIUS: Double = 4000.0

    /**
     * The radius of a sphere round a page's slot centre that holds the
     * page whatever its height (a preview card grows downwards from the
     * slot's top) and thickness.
     */
    fun pageRadius(g: PageSpaceGeometry): Double {
        val below = g.pageHeight * (PREVIEW_HEIGHT_FACTOR - 0.5)
        return sqrt(g.pageWidth * g.pageWidth / 4 + below * below) + THICKNESS
    }

    /** The space kept between neighbouring pages. */
    fun gap(g: PageSpaceGeometry): Double = g.pageWidth * GAP_SHARE

    /**
     * A region of directions as seen from the root page: polar angles
     * [thetaA]..[thetaB] off the root's axis (`-z`, straight away from its
     * reader) and azimuths [phiA]..[phiB] (radians, 0 = right, π/2 = up;
     * [phiB] may be below [phiA] — the order children are laid out in).
     */
    private data class Cell(val thetaA: Double, val thetaB: Double, val phiA: Double, val phiB: Double) {
        val theta: Double get() = (thetaA + thetaB) / 2
        val phi: Double get() = (phiA + phiB) / 2

        /**
         * The roomiest spot for a page in the cell, at its middle azimuth:
         * `(polar angle, half-angle of the widest cone round it that stays
         * in the cell)`. The cell is a wedge, wider further from the
         * axis, so the spot leans outwards when that gives more room.
         */
        fun spot(): Pair<Double, Double> {
            var best = theta to -1.0
            for (j in 0..SPOT_SAMPLES) {
                val t = thetaA + (thetaB - thetaA) * j / SPOT_SAMPLES
                val room = min(min(t - thetaA, thetaB - t), sin(t) * abs(phiB - phiA) / 2)
                if (room > best.second) best = t to room
            }
            return best
        }
    }

    /** How many polar angles [Cell.spot] tries. */
    private const val SPOT_SAMPLES = 16

    /**
     * A pose for every page of [tree], its root page at the origin facing
     * the viewer. A key that turns up twice keeps its first place.
     *
     * The layout is radial, from the root: every page owns a cell of
     * directions ([Cell]) and everything under it stays in that cell, so
     * no two subtrees can ever meet. The root's children share the ring
     * between [HOLE] and [CAP] off its axis — the first on the left, then
     * over the top to the right and round the bottom — and every page's
     * children split its cell between them in order, by how many pages
     * each holds (an ordered treemap on the sphere, cut across the cell's
     * longer way; shares grow with the cube root of a branch's pages). A
     * page hangs at its cell's roomiest spot ([Cell.spot]), a step ([pageRadius]
     * twice plus a gap) further out than its parent and far enough that
     * it fits its cell; it faces its parent, its `up` as close to its
     * parent's as that allows. A big branch thus pushes only its own
     * deeper pages further out.
     */
    fun layout(tree: SpaceTree, g: PageSpaceGeometry): Map<String, SpacePose> {
        val out = LinkedHashMap<String, SpacePose>()
        // Each key once, first come first served.
        val seen = HashSet<String>().also { it += tree.key }
        val kidsOf = HashMap<SpaceTree, List<SpaceTree>>()
        val weight = HashMap<SpaceTree, Double>()
        fun prune(n: SpaceTree): Double {
            val kept = n.children.filter { seen.add(it.key) }
            kidsOf[n] = kept
            val w = 1.0 + kept.sumOf { prune(it) }
            weight[n] = w
            return w
        }
        prune(tree)
        val step = 2 * pageRadius(g) + gap(g)
        fun place(n: SpaceTree, pose: SpacePose, radius: Double, cell: Cell, first: Boolean) {
            out[n.key] = pose
            val kids = kidsOf[n].orEmpty()
            if (kids.isEmpty()) return
            // A branch's share grows with the cube root of its pages: a lone
            // page beside a big branch still gets room near its parent.
            val cells = split(kids.map { weight.getValue(it).pow(1.0 / 3) }, cell)
            kids.forEachIndexed { i, c ->
                val cl = cells[i]
                val (theta, room) = cl.spot()
                val dir = SpaceVec(sin(theta) * cos(cl.phi), sin(theta) * sin(cl.phi), -cos(theta))
                val fit = (pageRadius(g) + gap(g) / 2) / sin(min(PI / 2, max(1e-4, room)))
                val r = max(max(radius + step, if (first) FIRST_RADIUS else 0.0), fit)
                val at = dir * r
                val normal = (pose.position - at).normalized() ?: -dir
                val up = (pose.up - normal * pose.up.dot(normal)).normalized()
                    ?: (normal.cross(pose.right)).normalized()
                    ?: pose.up
                place(c, SpacePose(at, up.cross(normal), up, normal), r, cl, first = false)
            }
        }
        place(tree, SpacePose.ROOT, 0.0, Cell(HOLE, CAP, 1.5 * PI, -0.5 * PI), first = true)
        return out
    }

    /**
     * Splits [cell] among children of [weights] (in order) into cells of
     * matching solid angle: halves of the weight in turn, each cut across
     * the cell's longer way — across the azimuths (in order) or at a polar
     * angle (the earlier half nearer the axis).
     */
    private fun split(weights: List<Double>, cell: Cell): List<Cell> {
        if (weights.size <= 1) return listOf(cell)
        val total = weights.sum()
        var k = 1
        var acc = weights[0]
        var best = abs(acc - total / 2)
        var run = acc
        for (i in 2 until weights.size) {
            run += weights[i - 1]
            val e = abs(run - total / 2)
            if (e < best) { best = e; k = i; acc = run }
        }
        val f = acc / total
        val wide = abs(cell.phiB - cell.phiA) * sin(cell.theta) >= cell.thetaB - cell.thetaA
        val (a, b) = if (wide) {
            val m = cell.phiA + (cell.phiB - cell.phiA) * f
            cell.copy(phiB = m) to cell.copy(phiA = m)
        } else {
            val cm = cos(cell.thetaA) - f * (cos(cell.thetaA) - cos(cell.thetaB))
            val m = acos(cm.coerceIn(-1.0, 1.0))
            cell.copy(thetaB = m) to cell.copy(thetaA = m)
        }
        return split(weights.subList(0, k), a) + split(weights.subList(k, weights.size), b)
    }

    /**
     * Where the camera sits to show the page at [page] square on at 1:1,
     * its top [PageSpaceLayout.TOP_PAD] below the view's top edge: [PageSpaceGeometry.cameraDistance]
     * in front of it, turned like the page.
     */
    fun cameraFor(page: SpacePose, g: PageSpaceGeometry): SpacePose {
        val drop = round(g.viewHeight / 2 - PageSpaceLayout.TOP_PAD - g.pageHeight / 2)
        return page.copy(position = page.position + page.normal * g.cameraDistance - page.up * drop)
    }

    /**
     * How long a flight of [distance] CSS pixels lasts, in seconds: from
     * 0.75 s for a short hop up to 2 s for a long one (Grove's distances
     * are larger than Pages').
     */
    fun flightSeconds(distance: Double): Double = min(2.0, 0.75 + distance / 20000)

    /**
     * The whole vault's pages as one [SpaceTree] for [PageSpaceLayout.layout]:
     * every node of [graph] (already privacy-filtered and in outline
     * order), keyed by folder, with the live [page] spliced in — its own
     * child pages come from the open outline ([SpacePage.children]), so an
     * indent sprouts a page before any save; a child that the graph knows
     * with deeper pages keeps the graph's subtree. A page the graph has no
     * node for (a zoomed leaf, a `.md` note, a file) hangs as the last
     * child of its parent's node ([SpacePage.parentKey], else the nearest
     * ancestor the graph has, else the root).
     *
     * With an empty [graph] (still loading) the tree is [page] and its
     * child pages alone; with no [page] either, the bare root.
     */
    fun treeOf(graph: VaultGraph, page: SpacePage?): SpaceTree {
        val rootKey = PageSpaceKeys.ofFolder("")
        fun liveTree(p: SpacePage) = SpaceTree(p.key, p.children.map(::childTree))
        if (graph.root == null) return page?.let(::liveTree) ?: SpaceTree(rootKey)
        val pageFolder = page?.key?.takeIf { it.startsWith("n:") }?.removePrefix("n:")
        val pageInGraph = pageFolder != null && pageFolder in graph.nodes
        // Where a page outside the graph hangs.
        val hostKey: String? = if (page == null || pageInGraph) {
            null
        } else {
            val parentFolder = page.parentKey?.takeIf { it.startsWith("n:") }?.removePrefix("n:") ?: ""
            PageSpaceKeys.ofFolder(graph.anchorOf(parentFolder) ?: "")
        }
        lateinit var build: (String) -> SpaceTree
        fun withGraph(c: SpaceChild): SpaceTree {
            val f = c.folderRel
            if (f != null && f in graph.nodes) {
                val t = build(f)
                if (t.children.isNotEmpty() || c.children.isEmpty()) return t
            }
            return SpaceTree(c.key, c.children.map(::withGraph))
        }
        // The live page's children consult the graph for their deeper pages.
        fun liveWithGraph(p: SpacePage) = SpaceTree(p.key, p.children.map(::withGraph))
        build = { id ->
            val key = PageSpaceKeys.ofFolder(id)
            if (page != null && key == page.key) {
                liveWithGraph(page)
            } else {
                val kids = graph.nodes[id]?.children.orEmpty().map(build)
                SpaceTree(key, if (page != null && key == hostKey) kids + liveWithGraph(page) else kids)
            }
        }
        return build("")
    }

    /** A child page and the pages under it, as the outline knows them. */
    private fun childTree(c: SpaceChild): SpaceTree = SpaceTree(c.key, c.children.map(::childTree))
}
