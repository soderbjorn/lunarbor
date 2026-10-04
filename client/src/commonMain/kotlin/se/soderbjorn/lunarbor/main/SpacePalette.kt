/*
 * SpacePalette.kt (commonMain)
 * ----------------------------
 * 3D mode's own colours (LBR-11). 3D mode is deliberately more colourful
 * than the theme: every area of the vault gets a vivid hue of its own, used
 * for its bodies on the maps (Crown, Cone, Galaxy) and for its pages' edges
 * and threads in Pages, and each branch inside an area turns that hue
 * clearly further. The theme still decides the background, text and chrome.
 *
 * The areas are the children of the first node that branches: a vault whose
 * root holds a single node (Home → Main → …) takes Main's children as its
 * areas, so it is not all one colour. Hues are spread evenly around the
 * colour wheel in outline order (golden-angle steps, so neighbours always
 * differ and adding an area never recolours the earlier ones). The order is
 * read from unfiltered listings, so turning a privacy mode on never shifts a
 * colour — which would hint at what is hidden.
 *
 * commonMain only — pure; tested in `VaultGraphTest`.
 */

package se.soderbjorn.lunarbor.main

/** Area hues for 3D mode. Called by the web `MapView` and `PageSpaceView`. */
object SpacePalette {
    /** Golden-angle step, as a fraction of the wheel. */
    private const val STEP: Double = 0.618033988749895

    /** Where the first area's hue starts (a warm orange-red). */
    private const val START: Double = 0.03

    /**
     * Hue (0..1) per area folder (vault-relative paths), in outline order
     * ([areaFolders]). Areas not listed fall back to [hueOf]'s hash.
     */
    fun areaHues(areaFolders: List<String>): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        for (f in areaFolders) {
            if (f.isEmpty() || f in out) continue
            val h = START + out.size * STEP
            out[f] = h - kotlin.math.floor(h)
        }
        return out
    }

    /**
     * Area hues from the vault's unfiltered listings: the areas are the
     * children of the first node with more than one child, following a
     * chain of single children down from the root.
     *
     * @param foldersOf A node folder's child node folders (vault-relative,
     *   outline order, unfiltered), or `null` while it is still unread.
     */
    fun areaHues(foldersOf: (String) -> List<String>?): Map<String, Double> {
        var at = ""
        var kids = foldersOf(at) ?: return emptyMap()
        repeat(MAX_CHAIN) {
            if (kids.size != 1) return areaHues(kids)
            at = kids[0]
            kids = foldersOf(at) ?: return areaHues(listOf(at))
        }
        return areaHues(kids)
    }

    /**
     * The hue (0..1) of the vault path [pathRel], or `null` for the root and
     * the single-child chain above the areas (drawn in the theme's text
     * colour):
     *  - an area node, and files directly in it, take the area's hue;
     *  - each branch below an area (the area's child nodes) takes a hue of
     *    its own from anywhere on the wheel, so the map is many colours, not
     *    shades of a few; nodes deeper in a branch vary a little around it.
     */
    fun hueOf(pathRel: String, hues: Map<String, Double>): Double? {
        if (pathRel.isEmpty()) return null
        val area = hues.keys.firstOrNull { pathRel == it || pathRel.startsWith("$it/") }
        if (area == null) {
            // A file at the root, or a chain node above the areas (or a file in one).
            if ('/' !in pathRel) return if (hues.keys.any { it.startsWith("$pathRel/") } || hues.isEmpty() || isFile(pathRel)) null else hashHue(pathRel)
            val parent = pathRel.substringBeforeLast('/')
            if (hues.keys.any { it.startsWith("$pathRel/") || it.startsWith("$parent/") }) return null
            return hashHue(pathRel.substringBefore('/'))
        }
        val rest = pathRel.removePrefix(area).removePrefix("/")
        if (rest.isEmpty() || ('/' !in rest && isFile(rest))) return hues.getValue(area)
        val branch = rest.substringBefore('/')
        val base = hashHue("$area/$branch")
        val deeper = rest.removePrefix(branch).removePrefix("/")
        if (deeper.isEmpty() || ('/' !in deeper && isFile(deeper))) return base
        val h = base + (GraphLayout.hash01(pathRel.lowercase()) - 0.5) * 0.08
        return h - kotlin.math.floor(h)
    }

    /** A hue anywhere on the wheel for [key], the same every time. */
    private fun hashHue(key: String): Double = GraphLayout.hash01(key.lowercase())

    /** `true` for a file name rather than a node folder (an outline, a note, an image, …). */
    private fun isFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in FILE_EXTENSIONS
    }

    private val FILE_EXTENSIONS = setOf("md", "png", "jpg", "jpeg", "gif", "webp", "svg", "excalidraw", "html", "htm", "pdf")

    /** At most this many single-child nodes are skipped to find the areas. */
    private const val MAX_CHAIN: Int = 8

    /**
     * The vault path a 3D page key ([PageSpaceKeys]) stands for: a node's
     * folder, or a file (a note, or the outline of a zoomed leaf).
     */
    fun pathOfKey(key: String): String = when {
        key.startsWith("n:") -> key.substring(2)
        key.startsWith("f:") -> key.substring(2)
        key.startsWith("z:") -> key.substring(2).substringBefore('#')
        else -> ""
    }
}
