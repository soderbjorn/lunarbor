/*
 * SpacePalette.kt (commonMain)
 * ----------------------------
 * 3D mode's own colours (LBR-11). 3D mode is deliberately more colourful
 * than the theme: every top-level area of the vault gets a vivid hue of its
 * own, used for its bodies on the maps (Crown, Cone, Galaxy) and for its
 * pages' edges and threads in Pages. The theme still decides the
 * background, text and chrome.
 *
 * Hues are spread evenly around the colour wheel in the root's outline
 * order (golden-angle steps, so neighbours always differ and adding an area
 * never recolours the earlier ones). The order is read from the root's
 * unfiltered listing, so turning a privacy mode on never shifts a colour —
 * which would hint at what is hidden.
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
     * Hue (0..1) per top-level folder, from the root's items in outline
     * order ([rootFolders]: their folders, `/`-free). Areas the root does
     * not list fall back to [hueOf]'s hash.
     */
    fun areaHues(rootFolders: List<String>): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        for (f in rootFolders) {
            if (f.isEmpty() || f in out) continue
            val h = START + out.size * STEP
            out[f] = h - kotlin.math.floor(h)
        }
        return out
    }

    /**
     * The hue (0..1) of the vault path [pathRel] — its top-level area's —
     * or `null` at the root (which is drawn in the theme's text colour).
     */
    fun hueOf(pathRel: String, hues: Map<String, Double>): Double? {
        val area = pathRel.substringBefore('/')
        if (area.isEmpty()) return null
        hues[area]?.let { return it }
        // A file at the root (a note, the root outline) belongs to the root.
        if ('/' !in pathRel) return null
        return GraphLayout.hash01(area.lowercase())
    }

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
