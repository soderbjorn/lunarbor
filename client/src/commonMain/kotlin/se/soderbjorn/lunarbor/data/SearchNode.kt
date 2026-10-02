/*
 * SearchNode.kt (commonMain)
 * --------------------------
 * Search nodes: a bullet whose text holds a search expression in double
 * braces lists the lines matching it under itself, live:
 *
 *     * Urgent work {{search: #work AND (#urgent OR #today)}}
 *
 * The expression is the `SearchQuery` language of the "Search this tree"
 * field; it searches the tree the bullet sits in (the folder its line is
 * stored in, and below) unless it names another with `in:`. Results are
 * computed, never written: `DocumentRegistry` re-runs each shown search
 * node's query a few seconds after the text index changes, and the web view
 * draws them as read-only link rows under the bullet.
 *
 * The `{{search: …}}` part is query, not title: it is left out of folder
 * names and titles ([FolderName.plainTextOf] strips it) and out of the text
 * index ([stripQuery]), so a search node never finds itself by its query.
 *
 * Pure: commonMain only.
 */

package se.soderbjorn.lunarbor.data

/** Helpers for the `{{search: …}}` part of a search node's text. */
object SearchNode {
    private val QUERY = Regex("""\{\{search:([^}]*)\}\}""")

    /**
     * The search expression in [text] (a bullet's text after its `* `), or
     * `null` when it holds none. The first `{{search: …}}` counts.
     */
    fun queryOf(text: String): String? = QUERY.find(text)?.groupValues?.get(1)?.trim()

    /** [text] without its `{{search: …}}` parts (and the spaces they leave). */
    fun stripQuery(text: String): String =
        if ("{{search:" !in text) text else QUERY.replace(text, "").replace(Regex("  +"), " ").trim()

    /**
     * Column range of the first `{{search: …}}` in [text], braces
     * included, or `null`. Used by the tokenizer to style it as a chip.
     */
    fun rangeIn(text: String, from: Int = 0): IntRange? = QUERY.find(text, from)?.range
}
