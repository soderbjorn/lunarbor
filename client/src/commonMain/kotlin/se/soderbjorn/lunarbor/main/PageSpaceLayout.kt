/*
 * PageSpaceLayout.kt (commonMain)
 * -------------------------------
 * The pure rules behind 3D mode's "Pages" space (web `main/space/`):
 * every node's page hangs at a fixed place, the page a window is on sits
 * in front of its camera at 1:1, its child nodes' pages hang behind it in a
 * left and a right column, and grandchildren hang behind those.
 *
 * Two halves, both pure:
 *
 *  - **Layout** ([PageSpaceLayout]): page and camera positions from the
 *    tree around a page and the view size. Deterministic — the same tree
 *    and view always give the same places, so Back returns exactly where
 *    the window was.
 *  - **Model** ([PageSpaceModel]): which pages there are around the page a
 *    pane shows — its child nodes (with the bullets their previews list)
 *    and grandchildren — read from the open outline's rows, falling back
 *    to the registry's cached node listings (`requestLinkPreview`) for
 *    folders that are not loaded. `PaneBackingViewModel.spacePageOf`
 *    feeds it. What the privacy mode hides (LBR-10) is never a page and
 *    never a preview row.
 *
 * Coordinates are CSS pixels in three.js's frame: x right, y up, z towards
 * the viewer. A page's position is its centre.
 *
 * commonMain only — no DOM, no state, no I/O.
 */

package se.soderbjorn.lunarbor.main

import se.soderbjorn.lunarbor.data.InlineMarkdownTokenizer
import se.soderbjorn.lunarbor.data.LineMarkdownPrefix
import se.soderbjorn.lunarbor.data.SearchNode
import se.soderbjorn.lunarbor.data.LunicleNode
import se.soderbjorn.lunarbor.data.SubtreeCodec
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A point (or offset, or direction) in page space, in CSS pixels.
 *
 * @property x Rightwards.
 * @property y Upwards.
 * @property z Towards the viewer; pages further away have smaller `z`.
 */
data class SpaceVec(val x: Double, val y: Double, val z: Double) {
    /** Component-wise sum. */
    operator fun plus(o: SpaceVec): SpaceVec = SpaceVec(x + o.x, y + o.y, z + o.z)

    /** Component-wise difference. */
    operator fun minus(o: SpaceVec): SpaceVec = SpaceVec(x - o.x, y - o.y, z - o.z)

    /** Scaled by [k]. */
    operator fun times(k: Double): SpaceVec = SpaceVec(x * k, y * k, z * k)

    /** Pointing the other way. */
    operator fun unaryMinus(): SpaceVec = SpaceVec(-x, -y, -z)

    /** Dot product. */
    fun dot(o: SpaceVec): Double = x * o.x + y * o.y + z * o.z

    /** Cross product (right-handed). */
    fun cross(o: SpaceVec): SpaceVec = SpaceVec(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    /** Euclidean length. */
    val length: Double get() = sqrt(dot(this))

    /** This direction at unit length, or `null` for a (near) zero vector. */
    fun normalized(): SpaceVec? {
        val l = length
        return if (l < 1e-9) null else times(1 / l)
    }

    companion object {
        /** The origin. */
        val ZERO: SpaceVec = SpaceVec(0.0, 0.0, 0.0)

        /** Linear interpolation from [a] (t = 0) to [b] (t = 1). */
        fun lerp(a: SpaceVec, b: SpaceVec, t: Double): SpaceVec = a + (b - a) * t
    }
}

/**
 * The sizes one view lays its pages out with, from [PageSpaceLayout.geometry].
 *
 * @property viewWidth The view's width in CSS pixels.
 * @property viewHeight The view's height in CSS pixels.
 * @property pageWidth Every page's width (the current page's editor width).
 * @property pageHeight Every page's height.
 * @property cameraDistance How far in front of a page the camera sits for
 *   that page to show at exactly one screen pixel per CSS pixel.
 */
data class PageSpaceGeometry(
    val viewWidth: Double,
    val viewHeight: Double,
    val pageWidth: Double,
    val pageHeight: Double,
    val cameraDistance: Double,
) {
    /** The scale a page one column back ([PageSpaceLayout.DEPTH_STEP]) is drawn at. */
    val columnScale: Double get() = cameraDistance / (cameraDistance + PageSpaceLayout.DEPTH_STEP)
}

/**
 * The pages around one page, as the layout needs them: a key per page and
 * its child pages in outline order.
 *
 * @property key The page's identity ([PageSpaceKeys]).
 * @property children Its child pages, in outline order.
 */
data class SpaceTree(val key: String, val children: List<SpaceTree> = emptyList())

/**
 * Where pages hang. Called by the web `PageSpaceView` on every navigation,
 * relayout (a page sprouting or going away) and resize; tested in
 * `PageSpaceLayoutTest`.
 */
object PageSpaceLayout {
    /** Vertical field of view of every view's camera, in degrees. */
    const val FOV_DEGREES: Double = 50.0

    /** How much further back a page's child columns hang than the page. */
    const val DEPTH_STEP: Double = 1600.0

    /** How much further back each further page in a column hangs. */
    const val COLUMN_STEP: Double = 260.0

    /** Gap above the current page, inside the view. */
    const val TOP_PAD: Double = 12.0

    /** Gap below the current page, inside the view. */
    const val BOTTOM_PAD: Double = 12.0

    /** The widest a page gets, so child pages still show beside it. */
    const val MAX_PAGE_WIDTH: Double = 760.0

    /** Room left beside a page that has to shrink to fit a narrow view. */
    const val SIDE_MARGIN: Double = 24.0

    /** The share of a view's width a page takes, so child pages show beside it. */
    const val PAGE_WIDTH_SHARE: Double = 0.55

    /** The narrowest a page gets for the sake of [PAGE_WIDTH_SHARE]. */
    const val MIN_PAGE_WIDTH: Double = 380.0

    /** The smallest page height, for very short views (a page never outgrows its view otherwise). */
    const val MIN_PAGE_HEIGHT: Double = 80.0

    /**
     * The sizes a view of [viewWidth] × [viewHeight] CSS pixels lays out with.
     * A page takes [PAGE_WIDTH_SHARE] of the width (at least
     * [MIN_PAGE_WIDTH], at most [MAX_PAGE_WIDTH], never more than fits), so
     * there is room for child pages beside it. Both sizes are clamped to at
     * least one pixel.
     */
    fun geometry(viewWidth: Double, viewHeight: Double): PageSpaceGeometry {
        val w = max(1.0, viewWidth)
        val h = max(1.0, viewHeight)
        return PageSpaceGeometry(
            viewWidth = w,
            viewHeight = h,
            pageWidth = round(max(1.0, min(min(MAX_PAGE_WIDTH, w - SIDE_MARGIN), max(MIN_PAGE_WIDTH, w * PAGE_WIDTH_SHARE)))),
            pageHeight = round(max(MIN_PAGE_HEIGHT, h - TOP_PAD - BOTTOM_PAD)),
            cameraDistance = (h / 2) / tan(FOV_DEGREES / 2 * PI / 180),
        )
    }

    /**
     * How far to the side a page's child columns hang: far enough that a
     * child page, drawn one column back, lands beside the page and not
     * behind it — towards the view's edge when there is room.
     */
    fun sideOffset(g: PageSpaceGeometry): Double {
        val k = g.columnScale
        val projected = max(g.pageWidth / 2 + g.pageWidth * k / 2 + 24, g.viewWidth / 2 - g.pageWidth * k / 2 - 14)
        return projected / k
    }

    /**
     * Where a page's [count] child pages hang, relative to the page: the
     * first half (rounded up) in a column on the left, the rest on the
     * right, each column one [DEPTH_STEP] back, centred on the page, top to
     * bottom in outline order, and each further page [COLUMN_STEP] further
     * back. Pages in a column are spaced to fit the view's height.
     *
     * @return One offset per child, in order; empty for `count <= 0`.
     */
    fun childOffsets(count: Int, g: PageSpaceGeometry): List<SpaceVec> {
        if (count <= 0) return emptyList()
        val half = ceil(count / 2.0).toInt()
        return columnOffsets(half, -1.0, g) + columnOffsets(count - half, 1.0, g)
    }

    /**
     * One column of [count] pages on one side ([direction] -1 left, 1 right)
     * of their parent: one [DEPTH_STEP] back, centred on the parent, top to
     * bottom, each further page [COLUMN_STEP] further back, spaced to fit
     * the view's height.
     */
    fun columnOffsets(count: Int, direction: Double, g: PageSpaceGeometry): List<SpaceVec> {
        if (count <= 0) return emptyList()
        val k = g.columnScale
        val side = sideOffset(g)
        val spacingProjected = if (count > 1) {
            min(g.pageHeight * k + 18, max(0.0, g.viewHeight - 40 - g.pageHeight * k) / (count - 1))
        } else {
            0.0
        }
        val spacing = spacingProjected / k
        return (0 until count).map { j ->
            SpaceVec(x = direction * side, y = ((count - 1) / 2.0 - j) * spacing, z = -DEPTH_STEP - j * COLUMN_STEP)
        }
    }

    /**
     * Positions of [tree]'s page and every page under it (to any depth the
     * tree has), with [tree]'s page at [origin]. Its children split left
     * and right ([childOffsets]); deeper pages hang in one column on their
     * parent's outer side ([columnOffsets]), so none hides behind the page
     * in front. A key that turns up twice keeps its first place.
     */
    fun layout(tree: SpaceTree, g: PageSpaceGeometry, origin: SpaceVec = SpaceVec.ZERO): Map<String, SpaceVec> {
        val out = LinkedHashMap<String, SpaceVec>()
        placeSubtree(tree, origin, null, g, out)
        return out
    }

    /**
     * Places [node] at [at] and everything under it into [out], as [layout]
     * does: split left and right when [direction] is `null` (a page laid
     * out as the centre), else one column on that side (-1 left, 1 right).
     * Keys already in [out] keep their place, with their subtrees.
     */
    private fun placeSubtree(node: SpaceTree, at: SpaceVec, direction: Double?, g: PageSpaceGeometry, out: MutableMap<String, SpaceVec>) {
        if (node.key in out) return
        out[node.key] = at
        val offsets = if (direction == null) childOffsets(node.children.size, g) else columnOffsets(node.children.size, direction, g)
        node.children.forEachIndexed { i, c -> placeSubtree(c, at + offsets[i], if (offsets[i].x < 0) -1.0 else 1.0, g, out) }
    }

    /**
     * Positions of every page of [tree] (the whole vault) by Pages' own
     * rules, anchored at the page [currentKey] at [currentAt]: its subtree
     * exactly as [layout] lays it out there, then each ancestor in turn
     * where going up would put it ([parentPosition]) with its other
     * branches in their usual columns. For free flight over the whole
     * vault — places further from the current page are approximate (deep
     * columns of different branches may cross).
     *
     * A [currentKey] not in [tree] lays [tree] out from [currentAt] as a
     * centre page.
     */
    fun wholeLayout(tree: SpaceTree, currentKey: String, currentAt: SpaceVec, g: PageSpaceGeometry): Map<String, SpaceVec> {
        val path = ArrayList<SpaceTree>()
        fun find(n: SpaceTree): Boolean {
            path += n
            if (n.key == currentKey) return true
            for (c in n.children) if (find(c)) return true
            path.removeAt(path.lastIndex)
            return false
        }
        if (!find(tree)) return layout(tree, g, currentAt)
        val out = LinkedHashMap<String, SpaceVec>()
        placeSubtree(path.last(), currentAt, null, g, out)
        for (k in path.size - 2 downTo 0) {
            val parent = path[k]
            val child = path[k + 1]
            val n = parent.children.size
            val i = parent.children.indexOfFirst { it.key == child.key }
            val at = parentPosition(out.getValue(child.key), i, n, g)
            if (parent.key !in out) out[parent.key] = at
            val offsets = childOffsets(n, g)
            parent.children.forEachIndexed { j, c ->
                if (j != i) placeSubtree(c, at + offsets[j], if (offsets[j].x < 0) -1.0 else 1.0, g, out)
            }
        }
        return out
    }

    /**
     * Where the camera sits to show the page at [page] at 1:1, its top
     * [TOP_PAD] below the view's top edge.
     */
    fun cameraFor(page: SpaceVec, g: PageSpaceGeometry): SpaceVec =
        SpaceVec(page.x, page.y - round(g.viewHeight / 2 - TOP_PAD - g.pageHeight / 2), page.z + g.cameraDistance)

    /**
     * Where a parent page hangs when its child page (at [child], the
     * [index]th of [siblingCount] child pages) is already placed: the
     * inverse of [childOffsets]. Used when a window goes up to a page it
     * has not placed yet, so the page it left stays where it was.
     */
    fun parentPosition(child: SpaceVec, index: Int, siblingCount: Int, g: PageSpaceGeometry): SpaceVec {
        val offsets = childOffsets(siblingCount, g)
        val offset = offsets.getOrNull(index) ?: return child + SpaceVec(0.0, 0.0, DEPTH_STEP)
        return child - offset
    }

    /**
     * Where a page that is neither a child nor the parent of the page at
     * [from] hangs the first time a window jumps to it (a link, a search
     * result): straight ahead, two columns deeper, a little to the side
     * picked from its [key], so jumps to different pages do not stack.
     */
    fun jumpPosition(from: SpaceVec, key: String, g: PageSpaceGeometry): SpaceVec {
        var h = 0
        for (c in key) h = (h * 31 + c.code)
        val lane = (abs(h) % 5) - 2
        return from + SpaceVec(lane * g.pageWidth * 0.6, 0.0, -2 * DEPTH_STEP)
    }

    /**
     * How long a flight of [distance] CSS pixels lasts, in seconds: from
     * 0.75 s for a short hop up to 1.5 s for a long one.
     */
    fun flightSeconds(distance: Double): Double = min(1.5, 0.75 + distance / 9000)
}

/**
 * Page identities. Node pages are keyed by their folder, so the same node
 * is the same page whichever outline a window reached it through; a
 * zoomed leaf (no folder yet) by its file and row id; a note, image,
 * drawing or web page by its file.
 */
object PageSpaceKeys {
    /** The page of the node stored in [folderRel] (`""` = the vault root). */
    fun ofFolder(folderRel: String): String = "n:$folderRel"

    /** The page of a `.md` note, image, drawing or web page. */
    fun ofFile(fileRel: String): String = "f:$fileRel"

    /** The page of a zoomed item that has no folder yet. */
    fun ofLine(fileRel: String, id: LineId): String = "z:$fileRel#${id.value}"
}

/**
 * One row of a page preview: an item of that node, in outline order.
 *
 * @property title The item's plain text.
 * @property depth Its nesting under the previewed node: 0 for direct
 *   children.
 * @property lineId Its row id in the open outline, when it is loaded
 *   there; clicking its dot zooms the pane in place.
 * @property folderRel Its folder when it is folder-backed; clicking its
 *   dot goes there otherwise.
 */
data class SpaceItem(
    val title: String,
    val depth: Int,
    val lineId: LineId? = null,
    val folderRel: String? = null,
)

/**
 * A page around the current one: a child node (or, nested, a grandchild).
 *
 * @property key Its identity ([PageSpaceKeys]).
 * @property title Its item's plain text.
 * @property lineId Its row id in the open outline, or `null` when it is
 *   only known from a node listing.
 * @property folderRel Its folder, or `null` while it has none (an item
 *   that just got its first child and has not been saved yet).
 * @property items What its preview lists, in outline order.
 * @property children Its own child pages (grandchildren of the current
 *   page), each without children of its own.
 */
data class SpaceChild(
    val key: String,
    val title: String,
    val lineId: LineId?,
    val folderRel: String?,
    val items: List<SpaceItem>,
    val children: List<SpaceChild> = emptyList(),
) {
    /** This page and its children as a [SpaceTree]. */
    fun tree(): SpaceTree = SpaceTree(key, children.map { it.tree() })
}

/**
 * The page a pane is on and the pages around it, from
 * `PaneBackingViewModel.spacePageOf`.
 *
 * @property key Its identity ([PageSpaceKeys]).
 * @property title Its plain title (`Home` for the vault root).
 * @property parentKey Its parent node's page, or `null` at the root.
 * @property children Its child pages, in outline order.
 * @property items Its own items as a preview lists them — what the page
 *   shows once the window has left it.
 */
data class SpacePage(
    val key: String,
    val title: String,
    val parentKey: String?,
    val children: List<SpaceChild>,
    val items: List<SpaceItem> = emptyList(),
) {
    /** This page and the pages under it as a [SpaceTree]. */
    fun tree(): SpaceTree = SpaceTree(key, children.map { it.tree() })
}

/**
 * Builds [SpaceChild] lists from an outline's rows. Called by
 * `PaneBackingViewModel.spacePageOf`; tested in `PageSpaceLayoutTest`.
 */
object PageSpaceModel {
    /** At most this many rows in one page preview. */
    const val MAX_PREVIEW_ITEMS: Int = 80

    /**
     * The child pages among the items in rows [startRow]..[endRow] of
     * [lines] — the subtree of the page's item, or the whole outline at
     * its root. An item is a page when it has rows under it, a folder
     * ([folderOf]) or children not loaded yet ([unloaded]).
     *
     * A page's preview lists its rows when they are loaded, else its
     * folder's cached listing ([previewOf]; `null` while unknown — the
     * caller asks for it and comes back). With [depth] above 1, each page
     * also gets its own child pages, from rows or listings the same way.
     *
     * @param fileRel The outline's file, for keys of items without folders.
     * @param hidden The rows a privacy mode hides ([PrivacyLayout.hiddenRows]),
     *   or `null`: hidden items are neither pages nor preview rows. Listings
     *   from [previewOf] must come already filtered.
     * @param depth How many levels of pages to return: 1 for children only,
     *   2 for children and grandchildren.
     */
    fun childrenOf(
        lines: List<String>,
        lineIds: List<LineId>,
        startRow: Int,
        endRow: Int,
        fileRel: String,
        folderOf: (LineId) -> String?,
        unloaded: Set<LineId>,
        previewOf: (String) -> List<LinkPreviewItem>?,
        hidden: BooleanArray? = null,
        depth: Int = 2,
    ): List<SpaceChild> {
        val out = mutableListOf<SpaceChild>()
        var r = max(0, startRow)
        val last = min(endRow, lines.lastIndex)
        while (r <= last) {
            val col = DocumentLayout.itemColumn(lines, r)
            if (col < 0) {
                r++
                continue
            }
            val itemEnd = DocumentLayout.itemLastRow(lines, r)
            val subEnd = min(last, DocumentLayout.subtreeEnd(lines, r, col))
            val id = lineIds.getOrNull(r)
            val folder = id?.let(folderOf)
            val hasRows = subEnd > itemEnd && (itemEnd + 1..subEnd).any { !PrivacyLayout.isHidden(hidden, it) }
            if (PrivacyLayout.isHidden(hidden, r)) {
                // Hidden with its whole subtree: no page, no preview.
            } else if (id != null && (hasRows || folder != null || id in unloaded)) {
                val items = if (hasRows) {
                    itemsOf(lines, lineIds, itemEnd + 1, subEnd, folderOf, hidden)
                } else {
                    folder?.let(previewOf)?.let(::itemsOfListing).orEmpty()
                }
                val grand = when {
                    depth <= 1 -> emptyList()
                    hasRows -> childrenOf(lines, lineIds, itemEnd + 1, subEnd, fileRel, folderOf, unloaded, previewOf, hidden, depth - 1)
                    folder != null -> childrenOfListing(folder, previewOf, depth - 1)
                    else -> emptyList()
                }
                out += SpaceChild(
                    key = folder?.let(PageSpaceKeys::ofFolder) ?: PageSpaceKeys.ofLine(fileRel, id),
                    title = plainTitle(SubtreeCodec.itemTitleOf(lines, r)),
                    lineId = id,
                    folderRel = folder,
                    items = items,
                    children = grand,
                )
            }
            r = max(subEnd, itemEnd) + 1
        }
        return out
    }

    /**
     * The child pages of the node in [folderRel], from its cached listing
     * ([previewOf]): every folder-backed item. Empty while the listing is
     * unknown.
     */
    fun childrenOfListing(
        folderRel: String,
        previewOf: (String) -> List<LinkPreviewItem>?,
        depth: Int = 1,
    ): List<SpaceChild> {
        val listing = previewOf(folderRel) ?: return emptyList()
        return listing.mapNotNull { item ->
            val path = item.pathRel ?: return@mapNotNull null
            SpaceChild(
                key = PageSpaceKeys.ofFolder(path),
                title = item.title,
                lineId = null,
                folderRel = path,
                items = previewOf(path)?.let(::itemsOfListing).orEmpty(),
                children = if (depth > 1) childrenOfListing(path, previewOf, depth - 1) else emptyList(),
            )
        }
    }

    /** A node listing as preview rows, all at depth 0. */
    fun itemsOfListing(listing: List<LinkPreviewItem>): List<SpaceItem> =
        listing.take(MAX_PREVIEW_ITEMS).map { SpaceItem(it.title, 0, folderRel = it.pathRel) }

    /**
     * The items in rows [startRow]..[endRow] as preview rows, nested by
     * indentation (depth 0 for the shallowest), leaving out the rows in
     * [hidden] (a privacy mode's, [PrivacyLayout.hiddenRows]).
     */
    fun itemsOf(
        lines: List<String>,
        lineIds: List<LineId>,
        startRow: Int,
        endRow: Int,
        folderOf: (LineId) -> String?,
        hidden: BooleanArray? = null,
    ): List<SpaceItem> {
        val out = mutableListOf<SpaceItem>()
        val columns = ArrayList<Int>()
        for (r in max(0, startRow)..min(endRow, lines.lastIndex)) {
            if (PrivacyLayout.isHidden(hidden, r)) continue
            val col = DocumentLayout.itemColumn(lines, r)
            if (col < 0) continue
            while (columns.isNotEmpty() && columns.last() >= col) columns.removeAt(columns.lastIndex)
            val depth = columns.size
            columns += col
            if (out.size >= MAX_PREVIEW_ITEMS) break
            val id = lineIds.getOrNull(r)
            out += SpaceItem(
                title = plainTitle(SubtreeCodec.itemTitleOf(lines, r)),
                depth = depth,
                lineId = id,
                folderRel = id?.let(folderOf),
            )
        }
        return out
    }

    /**
     * An item's title as plain text: line-level prefix (heading, quote),
     * inline Markdown and a search node's query removed.
     */
    fun plainTitle(raw: String): String {
        val stripped = LunicleNode.stripQueries(raw)
        val prefix = LineMarkdownPrefix.detect(stripped, 0)
        return InlineMarkdownTokenizer.tokenize(stripped.substring(prefix.markerEnd)).displayText.trim()
    }
}
