/*
 * GraphLayout.kt (commonMain)
 * ---------------------------
 * Where 3D mode's map shapes (LBR-11) put the bodies of a [VaultGraph]:
 *
 *  - **Crown** — the tree grows up and out from the root: depth is height,
 *    every area gets a slice of the circle sized by how much it holds.
 *  - **Cone** — Robertson's cone trees: each node's children hang in a
 *    ring below it, the ring as wide as its children need.
 *  - **Galaxy** — a force layout seeded from the crown, where `lunarbor:`
 *    links pull as well as branches, so linked nodes drift together even
 *    across areas.
 *
 * All three are deterministic: positions come from folder paths and sibling
 * order (a string hash stands in for randomness), never from a clock, so
 * the same vault always gives the same map and spatial memory holds.
 *
 * Folding: a folded node's children are not placed; they live inside its
 * ring ([visibleAnchor]).
 *
 * Units are abstract world units (a body is about 1 across), y up.
 * commonMain only — no DOM, no state, no I/O; tested in `GraphLayoutTest`.
 */

package se.soderbjorn.lunarbor.main

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The shapes 3D mode can show: the page space and the three maps. */
enum class SpaceShape(val label: String) {
    PAGES("Pages"),
    CROWN("Crown"),
    CONE("Cone"),
    GALAXY("Galaxy");

    /** `true` for the map shapes, drawn by the web `MapView`. */
    val isMap: Boolean get() = this != PAGES

    /** The shape after this one, wrapping (the L key, ⌃⌘2). */
    fun next(): SpaceShape = entries[(ordinal + 1) % entries.size]

    companion object {
        /** The shape named [name] (an enum name), or [PAGES]. */
        fun of(name: String?): SpaceShape = entries.firstOrNull { it.name == name } ?: PAGES
    }
}

/**
 * Places bodies. Called by the web `MapView` whenever the graph, the folds
 * or the shape change.
 */
object GraphLayout {
    /**
     * Positions of every body of [graph] that is not inside a folded node,
     * for [shape] (a map shape; [SpaceShape.PAGES] lays out as Crown).
     *
     * @param folded Node ids whose children are hidden inside their ring.
     */
    fun layout(graph: VaultGraph, folded: Set<String>, shape: SpaceShape): Map<String, SpaceVec> {
        if (graph.root == null) return emptyMap()
        return when (shape) {
            SpaceShape.CONE -> cone(graph, folded)
            SpaceShape.GALAXY -> galaxy(graph, folded)
            else -> crown(graph, folded)
        }
    }

    /**
     * The body [id] is drawn as: itself, or its outermost folded ancestor
     * when one hides it. `null` when [id] is not in [graph].
     */
    fun visibleAnchor(graph: VaultGraph, folded: Set<String>, id: String): String? {
        var n = graph.nodes[id] ?: return null
        var anchor = id
        while (true) {
            val parentId = n.parent ?: return anchor
            if (parentId in folded) anchor = parentId
            n = graph.nodes[parentId] ?: return anchor
        }
    }

    /** A deterministic number in 0..1 from [s] (FNV-1a). */
    fun hash01(s: String): Double {
        var h = 0x811c9dc5.toInt()
        for (c in s) {
            h = h xor c.code
            h *= 0x01000193
        }
        return (h.toLong() and 0xffffffffL).toDouble() / 4294967296.0
    }

    private fun kidsOf(graph: VaultGraph, folded: Set<String>, id: String): List<String> =
        if (id in folded) emptyList() else graph.nodes[id]?.children.orEmpty().filter { it in graph.nodes }

    // ------------------------------------------------------------- crown

    /** See the file header. Ring radii grow with how many bodies share a depth. */
    private fun crown(graph: VaultGraph, folded: Set<String>): Map<String, SpaceVec> {
        val weight = HashMap<String, Double>()
        val perDepth = HashMap<Int, Int>()
        fun weigh(id: String, d: Int): Double {
            perDepth[d] = (perDepth[d] ?: 0) + 1
            val k = kidsOf(graph, folded, id)
            val w = if (k.isEmpty()) 1.0 else k.sumOf { weigh(it, d + 1) }
            weight[id] = w
            return w
        }
        weigh("", 0)
        val maxDepth = perDepth.keys.maxOrNull() ?: 0
        val radius = DoubleArray(maxDepth + 1)
        for (d in 1..maxDepth) {
            val base = 6 + (d - 1) * 8.5
            val fit = (perDepth[d] ?: 0) * 2.4 / (2 * PI)
            radius[d] = max(max(base, fit), radius[d - 1] + 6)
        }
        val out = LinkedHashMap<String, SpaceVec>()
        fun place(id: String, a0: Double, a1: Double, d: Int) {
            val a = (a0 + a1) / 2
            val r = radius[d]
            val y = d * 7.0 - 16 + if (d > 0) (hash01("$id|y") - 0.5) * 3.2 else 0.0
            out[id] = SpaceVec(cos(a) * r, y, sin(a) * r)
            val w = weight[id] ?: 1.0
            var acc = a0
            for (k in kidsOf(graph, folded, id)) {
                val span = (a1 - a0) * (weight[k] ?: 1.0) / w
                place(k, acc, acc + span, d + 1)
                acc += span
            }
        }
        place("", 0.0, 2 * PI, 0)
        return out
    }

    // -------------------------------------------------------------- cone

    /** See the file header. */
    private fun cone(graph: VaultGraph, folded: Set<String>): Map<String, SpaceVec> {
        val gap = 1.1
        val ringR = HashMap<String, Double>()
        val bodyR = HashMap<String, Double>()
        fun size(id: String): Double {
            val k = kidsOf(graph, folded, id)
            if (k.isEmpty()) {
                bodyR[id] = 1.5
                return 1.5
            }
            var sum = 0.0
            var widest = 0.0
            for (c in k) {
                val r = size(c)
                sum += r * 2 + gap
                widest = max(widest, r)
            }
            val ring = max(sum / (2 * PI), 2.8)
            ringR[id] = ring
            val r = ring + widest * 0.5
            bodyR[id] = r
            return r
        }
        size("")
        val out = LinkedHashMap<String, SpaceVec>()
        fun place(id: String, x: Double, y: Double, z: Double) {
            out[id] = SpaceVec(x, y, z)
            val k = kidsOf(graph, folded, id)
            if (k.isEmpty()) return
            val total = k.sumOf { (bodyR[it] ?: 1.5) * 2 + gap }
            val rot = hash01(id) * 2 * PI
            val ring = ringR[id] ?: 2.8
            var acc = 0.0
            for (c in k) {
                val w = (bodyR[c] ?: 1.5) * 2 + gap
                val a = rot + (acc + w / 2) / total * 2 * PI
                acc += w
                place(c, x + cos(a) * ring, y - 9, z + sin(a) * ring)
            }
        }
        place("", 0.0, 16.0, 0.0)
        return out
    }

    // ------------------------------------------------------------ galaxy

    /**
     * See the file header. Repulsion only reaches [REPULSION_REACH] (a grid
     * of that cell size keeps each step near-linear), so a large vault
     * still lays out in a moment; fewer steps for more bodies.
     */
    private fun galaxy(graph: VaultGraph, folded: Set<String>): Map<String, SpaceVec> {
        val crown = crown(graph, folded)
        val ids = crown.keys.toList()
        val n = ids.size
        val index = HashMap<String, Int>(n * 2).also { m -> ids.forEachIndexed { i, id -> m[id] = i } }
        val px = DoubleArray(n)
        val py = DoubleArray(n)
        val pz = DoubleArray(n)
        ids.forEachIndexed { i, id ->
            val c = crown.getValue(id)
            px[i] = c.x * 0.9 + (hash01("$id|x") - 0.5) * 4
            py[i] = (c.y + 16) * 0.12 + (hash01("$id|q") - 0.5) * 3
            pz[i] = c.z * 0.9 + (hash01("$id|z") - 0.5) * 4
        }
        // Springs: (a, b, rest length, stiffness).
        val springs = ArrayList<DoubleArray>()
        for (id in ids) {
            val p = graph.nodes[id]?.parent ?: continue
            val pi = index[p] ?: continue
            springs += doubleArrayOf(index.getValue(id).toDouble(), pi.toDouble(), 6.0, 0.07)
        }
        for ((a, b) in graph.links) {
            val ia = visibleAnchor(graph, folded, a)?.let { index[it] } ?: continue
            val ib = visibleAnchor(graph, folded, b)?.let { index[it] } ?: continue
            if (ia != ib) springs += doubleArrayOf(ia.toDouble(), ib.toDouble(), 12.0, 0.035)
        }
        val vx = DoubleArray(n)
        val vy = DoubleArray(n)
        val vz = DoubleArray(n)
        val fx = DoubleArray(n)
        val fy = DoubleArray(n)
        val fz = DoubleArray(n)
        val steps = if (n <= 400) 320 else max(90, 320 * 400 / n)
        val root = index[""] ?: 0
        val cell = REPULSION_REACH
        for (it in 0 until steps) {
            fx.fill(0.0); fy.fill(0.0); fz.fill(0.0)
            val grid = HashMap<Long, MutableList<Int>>()
            fun key(x: Int, y: Int, z: Int): Long = ((x + 4096).toLong() shl 26) or ((y + 4096).toLong() shl 13) or (z + 4096).toLong()
            for (i in 0 until n) {
                grid.getOrPut(key(floor(px[i] / cell).toInt(), floor(py[i] / cell).toInt(), floor(pz[i] / cell).toInt())) { ArrayList() } += i
            }
            for (i in 0 until n) {
                val cx = floor(px[i] / cell).toInt()
                val cy = floor(py[i] / cell).toInt()
                val cz = floor(pz[i] / cell).toInt()
                for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                    val bucket = grid[key(cx + dx, cy + dy, cz + dz)] ?: continue
                    for (j in bucket) {
                        if (j <= i) continue
                        val ddx = px[i] - px[j]
                        val ddy = py[i] - py[j]
                        val ddz = pz[i] - pz[j]
                        val d2 = ddx * ddx + ddy * ddy + ddz * ddz + 0.05
                        if (d2 > cell * cell) continue
                        val s = min(55 / d2, 4.0) / sqrt(d2)
                        fx[i] += ddx * s; fy[i] += ddy * s; fz[i] += ddz * s
                        fx[j] -= ddx * s; fy[j] -= ddy * s; fz[j] -= ddz * s
                    }
                }
            }
            for (sp in springs) {
                val a = sp[0].toInt()
                val b = sp[1].toInt()
                val ddx = px[b] - px[a]
                val ddy = py[b] - py[a]
                val ddz = pz[b] - pz[a]
                val d = sqrt(ddx * ddx + ddy * ddy + ddz * ddz) + 1e-4
                val s = (d - sp[2]) * sp[3] / d
                fx[a] += ddx * s; fy[a] += ddy * s; fz[a] += ddz * s
                fx[b] -= ddx * s; fy[b] -= ddy * s; fz[b] -= ddz * s
            }
            val cool = 1 - it.toDouble() / (steps * 1.25)
            for (i in 0 until n) {
                fx[i] -= px[i] * 0.004
                fz[i] -= pz[i] * 0.004
                fy[i] -= py[i] * 0.05
                vx[i] = (vx[i] + fx[i] * cool) * 0.8
                vy[i] = (vy[i] + fy[i] * cool) * 0.8
                vz[i] = (vz[i] + fz[i] * cool) * 0.8
                px[i] += vx[i]; py[i] += vy[i]; pz[i] += vz[i]
            }
            px[root] = 0.0; py[root] = 0.0; pz[root] = 0.0
        }
        val out = LinkedHashMap<String, SpaceVec>()
        ids.forEachIndexed { i, id -> out[id] = SpaceVec(px[i], py[i] - 2, pz[i]) }
        return out
    }

    /** How far bodies push each other apart in the galaxy, in world units. */
    const val REPULSION_REACH: Double = 18.0

    /**
     * The folds a freshly built map starts with, so a big vault opens
     * readable but full: nodes are unfolded breadth first (shallowest
     * first, in outline order) as long as the bodies on screen stay within
     * [FOLD_ALL_BELOW]; a node whose children would not fit stays folded,
     * and a later, smaller one may still open. The root is always open, so
     * a vault whose top is one or two nodes deep never opens as a handful
     * of folded bodies. Called by `MapView` once every listing has landed.
     *
     * @param graph The map's graph ([VaultGraph.nodes] in breadth-first order).
     * @return The ids of the nodes that start folded.
     */
    fun defaultFolds(graph: VaultGraph): Set<String> {
        val folded = graph.nodes.values.filter { it.children.isNotEmpty() }.mapTo(HashSet()) { it.id }
        val root = graph.root ?: return folded
        folded.remove(root.id)
        var shown = 1 + root.children.size
        val queue = ArrayDeque(root.children)
        while (queue.isNotEmpty()) {
            val node = graph.nodes[queue.removeFirst()] ?: continue
            if (node.children.isEmpty() || shown + node.children.size > FOLD_ALL_BELOW) continue
            folded.remove(node.id)
            shown += node.children.size
            queue.addAll(node.children)
        }
        return folded
    }

    /** How many bodies [defaultFolds] leaves on screen at most (the root's children always show). */
    const val FOLD_ALL_BELOW: Int = 300
}
