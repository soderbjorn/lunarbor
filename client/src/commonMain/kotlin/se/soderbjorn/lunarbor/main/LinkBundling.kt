/*
 * LinkBundling.kt (commonMain)
 * ----------------------------
 * Hierarchical edge bundling (Holten, 2006) for 3D mode's map shapes
 * (LBR-11: Crown, Cone, Galaxy): instead of a free arc, a vault link is
 * drawn as a smooth curve guided by the tree path between its two ends —
 * up from one body to their lowest common ancestor and down to the other.
 * Links between the same two areas share most of that path, so they run
 * together as one strand along the branches, and the map shows which areas
 * refer to each other rather than a tangle of arcs.
 *
 * The curve is a clamped uniform cubic B-spline over the path's bodies,
 * each control point first pulled towards the straight line between the
 * ends by `1 − beta` ([DEFAULT_BETA]: tight bundles, still separable). It
 * is the same construction as d3's `curveBundle`, written from the paper.
 *
 * Folds: a link touching a folded subtree runs from the folded body that
 * stands for it ([GraphLayout.visibleAnchor]); its ancestors are always
 * on the map, so every control point has a position.
 *
 * Drawn by the web `MapView.writeLines` while bundling is on (the B key).
 * commonMain only — no DOM, no state, no I/O; tested in `LinkBundlingTest`.
 */

package se.soderbjorn.lunarbor.main

/** Bundled link curves for the 3D maps; see the file header. */
object LinkBundling {
    /** How closely a curve follows its tree path: 1 = through every body, 0 = a straight line. */
    const val DEFAULT_BETA: Double = 0.85

    /** Points sampled per B-spline segment by [curve]. */
    const val SEGMENT_STEPS: Int = 6

    /**
     * The bodies a link between [a] and [b] is guided by: [a]'s visible
     * anchor, its ancestors up to the lowest common ancestor, then down to
     * [b]'s visible anchor (both ends included, the ancestor once).
     *
     * Called by `MapView.writeLines` for every link edge of the graph.
     *
     * @param graph The map's graph.
     * @param folded Folded node ids (a link into a folded subtree starts at
     *   the folded body).
     * @param a One end's node id.
     * @param b The other end's node id.
     * @return The path, or `null` when either end is not in [graph] or both
     *   ends show as the same body (nothing to draw).
     */
    fun controlPath(graph: VaultGraph, folded: Set<String>, a: String, b: String): List<String>? {
        val from = GraphLayout.visibleAnchor(graph, folded, a) ?: return null
        val to = GraphLayout.visibleAnchor(graph, folded, b) ?: return null
        if (from == to) return null
        val up = chainOf(graph, from)
        val down = chainOf(graph, to)
        val downSet = down.toHashSet()
        val lcaIndex = up.indexOfFirst { it in downSet }
        if (lcaIndex < 0) return listOf(from, to)
        val lca = up[lcaIndex]
        val out = ArrayList<String>(lcaIndex + down.size)
        for (i in 0..lcaIndex) out += up[i]
        for (i in down.indexOf(lca) - 1 downTo 0) out += down[i]
        return out
    }

    /**
     * The bundled curve through [points] (a [controlPath]'s positions), as a
     * polyline from the first point to the last.
     *
     * Called by `MapView.writeLines`.
     *
     * @param points At least two control points, in path order.
     * @param beta Bundling strength in 0..1 (see [DEFAULT_BETA]).
     * @param steps Samples per B-spline segment (see [SEGMENT_STEPS]).
     * @return The sampled curve: starts exactly at the first point and ends
     *   exactly at the last; just the two points for a two-point path.
     */
    fun curve(points: List<SpaceVec>, beta: Double = DEFAULT_BETA, steps: Int = SEGMENT_STEPS): List<SpaceVec> {
        if (points.size < 2) return points
        val first = points.first()
        val last = points.last()
        if (points.size == 2) return listOf(first, last)
        val n = points.size - 1
        val straightened = points.mapIndexed { i, p ->
            val t = i.toDouble() / n
            SpaceVec(
                beta * p.x + (1 - beta) * (first.x + t * (last.x - first.x)),
                beta * p.y + (1 - beta) * (first.y + t * (last.y - first.y)),
                beta * p.z + (1 - beta) * (first.z + t * (last.z - first.z)),
            )
        }
        // Clamped: the ends repeated three times, so the spline starts and ends on them.
        val c = ArrayList<SpaceVec>(straightened.size + 4)
        c += first; c += first
        c += straightened
        c += last; c += last
        val out = ArrayList<SpaceVec>((c.size - 3) * steps + 1)
        out += first
        for (seg in 0 until c.size - 3) {
            val p0 = c[seg]
            val p1 = c[seg + 1]
            val p2 = c[seg + 2]
            val p3 = c[seg + 3]
            for (s in 1..steps) {
                val t = s.toDouble() / steps
                val t2 = t * t
                val t3 = t2 * t
                val b0 = (1 - t) * (1 - t) * (1 - t) / 6
                val b1 = (3 * t3 - 6 * t2 + 4) / 6
                val b2 = (-3 * t3 + 3 * t2 + 3 * t + 1) / 6
                val b3 = t3 / 6
                out += SpaceVec(
                    b0 * p0.x + b1 * p1.x + b2 * p2.x + b3 * p3.x,
                    b0 * p0.y + b1 * p1.y + b2 * p2.y + b3 * p3.y,
                    b0 * p0.z + b1 * p1.z + b2 * p2.z + b3 * p3.z,
                )
            }
        }
        out[out.size - 1] = last
        return out
    }

    /** [id] and its ancestors up to the root, nearest first. */
    private fun chainOf(graph: VaultGraph, id: String): List<String> {
        val out = ArrayList<String>()
        var cur: String? = id
        while (cur != null && cur !in out) {
            out += cur
            cur = graph.nodes[cur]?.parent
        }
        return out
    }
}
