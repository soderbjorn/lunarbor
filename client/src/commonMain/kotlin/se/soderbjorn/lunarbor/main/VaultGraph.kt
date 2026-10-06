/*
 * VaultGraph.kt (commonMain)
 * --------------------------
 * The vault as 3D mode's map shapes (LBR-11: Crown, Cone, Galaxy) see it:
 * one body per node folder — the root and every folder-backed item — with
 * its parent, children in outline order, how many leaf bullets it holds
 * (drawn as dust) and the vault links between bodies.
 *
 * Built by [VaultGraphBuilder] from the registry's cached node listings
 * (`DocumentRegistry.requestLinkPreview`, one per folder, read from disk)
 * and the link index (`VaultIndex.allLinks`). Pure: the caller hands in
 * lookups, so the builder never does I/O; a listing not loaded yet simply
 * reads as "no children yet" and the caller rebuilds when it lands.
 *
 * **Privacy (LBR-10):** an item the current privacy mode hides — a hiding
 * tag on its line, or a hidden folder — is left out with everything under
 * it: no body, no dust, no label, no link touching it. The caller passes
 * the filter as [VaultGraphBuilder.build]'s `isHidden`, and the web view
 * rebuilds the graph whenever the mode changes.
 *
 * commonMain only — no DOM, no state, no I/O.
 */

package se.soderbjorn.lunarbor.main

import se.soderbjorn.lunarbor.data.FolderName

/**
 * One body of the map: a node folder.
 *
 * @property id The node's folder, vault-relative (`""` for the root).
 * @property parent The parent node's folder, `null` for the root.
 * @property title The node's plain title without its `#tags`
 *   ([FolderName.nameTextOf]; `Home` for the root).
 * @property depth 0 for the root, 1 for its folder-backed items, …
 * @property children Folder-backed child nodes in outline order (only
 *   those in the graph: visible and within the node cap).
 * @property leafCount Visible leaf bullets directly under the node.
 * @property tagKeys Normalized tags on the node's own line.
 * @property loaded `false` while the node's listing is still being read.
 */
data class GraphNode(
    val id: String,
    val parent: String?,
    val title: String,
    val depth: Int,
    val children: List<String>,
    val leafCount: Int,
    val tagKeys: Set<String> = emptySet(),
    val loaded: Boolean = true,
) {
    /** The top-level area this node belongs to (its first path segment), `""` for the root. */
    val area: String get() = id.substringBefore('/')
}

/**
 * The map's graph.
 *
 * @property nodes Every body by folder, in breadth-first order (root first).
 * @property links Undirected vault link edges between bodies (each pair
 *   once, never a body to itself), from [VaultGraphBuilder.linkEdges].
 * @property truncated `true` when [VaultGraphBuilder.MAX_NODES] cut the walk short.
 */
data class VaultGraph(
    val nodes: Map<String, GraphNode>,
    val links: List<Pair<String, String>> = emptyList(),
    val truncated: Boolean = false,
) {
    /** The root body, or `null` for an empty graph. */
    val root: GraphNode? get() = nodes[""]

    /**
     * The body [pathRel] belongs to: itself when it is a body, else its
     * nearest ancestor folder that is one (a note's node, a hidden or
     * unloaded folder's visible ancestor). `null` only for an empty graph.
     */
    fun anchorOf(pathRel: String): String? {
        var p = pathRel
        while (true) {
            if (p in nodes) return p
            if (p.isEmpty()) return null
            p = p.substringBeforeLast('/', "")
        }
    }

    /** [id] and every body under it. */
    fun subtreeOf(id: String): List<String> {
        val out = ArrayList<String>()
        val stack = ArrayDeque<String>().also { it.addLast(id) }
        while (stack.isNotEmpty()) {
            val n = nodes[stack.removeLast()] ?: continue
            out += n.id
            for (c in n.children.asReversed()) stack.addLast(c)
        }
        return out
    }

    companion object {
        /** No bodies at all. */
        val EMPTY: VaultGraph = VaultGraph(emptyMap())
    }
}

/**
 * Builds [VaultGraph]s. Called by the web `MapView` whenever listings,
 * links or the privacy mode change; tested in `VaultGraphTest`.
 */
object VaultGraphBuilder {
    /** At most this many bodies; a bigger vault is cut off breadth-first. */
    const val MAX_NODES: Int = 5000

    /**
     * Walks the vault breadth-first from the root.
     *
     * @param rootTitle The root body's title.
     * @param listingOf A node folder's items in order, or `null` while its
     *   listing is unknown (the caller starts the read; the body shows as
     *   not [GraphNode.loaded] meanwhile). Unfiltered — [isHidden] filters.
     * @param isHidden `true` for an item of folder `parent` the privacy mode
     *   hides; it is left out with its whole subtree.
     * @param maxNodes See [MAX_NODES].
     */
    fun build(
        rootTitle: String,
        listingOf: (String) -> List<LinkPreviewItem>?,
        isHidden: (parent: String, item: LinkPreviewItem) -> Boolean = { _, _ -> false },
        maxNodes: Int = MAX_NODES,
    ): VaultGraph {
        data class Pending(val id: String, val parent: String?, val title: String, val depth: Int, val tags: Set<String>)
        val nodes = LinkedHashMap<String, GraphNode>()
        val queue = ArrayDeque<Pending>()
        queue.addLast(Pending("", null, rootTitle, 0, emptySet()))
        val seen = HashSet<String>().also { it += "" }
        var truncated = false
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            val listing = listingOf(p.id)
            val kids = ArrayList<String>()
            var leaves = 0
            for (item in listing.orEmpty()) {
                if (isHidden(p.id, item)) continue
                val path = item.pathRel
                if (path == null) {
                    leaves++
                    continue
                }
                if (!seen.add(path)) continue
                if (seen.size > maxNodes) {
                    truncated = true
                    continue
                }
                kids += path
                // Tags stay out of the label, as in breadcrumbs and pane labels.
                queue.addLast(Pending(path, p.id, FolderName.nameTextOf(item.title), p.depth + 1, item.tagKeys))
            }
            nodes[p.id] = GraphNode(p.id, p.parent, p.title.ifEmpty { "Untitled" }, p.depth, kids, leaves, p.tags, loaded = listing != null)
        }
        return VaultGraph(nodes, truncated = truncated)
    }

    /**
     * The link edges among [graph]'s bodies: for every note file and every
     * link target it links to (the link index, file → targets), an
     * edge from the file's body to the target's body ([VaultGraph.anchorOf]).
     * A link whose file or target the privacy mode hides ([isHidden]) is
     * left out, as are links within one body.
     *
     * @param linksByFile Note file → linked target paths.
     * @param isHidden `true` for a vault path the privacy mode hides.
     */
    fun linkEdges(
        graph: VaultGraph,
        linksByFile: Map<String, Set<String>>,
        isHidden: (String) -> Boolean = { false },
    ): List<Pair<String, String>> {
        val out = LinkedHashSet<Pair<String, String>>()
        for ((file, targets) in linksByFile) {
            if (file.startsWith('.') || file.contains("/.")) continue
            if (isHidden(file)) continue
            val from = graph.anchorOf(file.substringBeforeLast('/', "")) ?: continue
            for (t in targets) {
                if (isHidden(t)) continue
                val to = graph.anchorOf(t) ?: continue
                if (to == from) continue
                out += if (from < to) from to to else to to from
            }
        }
        return out.toList()
    }
}
