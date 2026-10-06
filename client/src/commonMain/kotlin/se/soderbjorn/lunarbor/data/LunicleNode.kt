/*
 * LunicleNode.kt (commonMain)
 * ---------------------------
 * Board nodes (LBR-27, epic LBR-25): a bullet whose text holds a Lunicle
 * project reference in double braces shows that project's board under
 * itself, live, as read-only outline rows:
 *
 *     * Lunicle board {{lunicle: work/FRA}}
 *     * Sprint {{lunicle: FRA}}            (the only connection)
 *
 * `<connection>` is a connection's name from App settings → Lunicle (the
 * part before the `/`, optional when exactly one connection exists);
 * `<KEY>` is the project's key prefix (the `FRA` of `FRA-12`), which
 * survives a project rename. Modelled on [SearchNode].
 *
 * The `{{lunicle: …}}` part is a reference, not title: it is left out of
 * folder names and titles ([FolderName.plainTextOf]), breadcrumbs and the
 * text index ([stripQuery]). The board's rows are never part of the
 * document, never saved and never indexed — `DocumentRegistry`'s
 * `LunicleBoards` fetches them and the web view draws them.
 *
 * Pure: commonMain only.
 */

package se.soderbjorn.lunarbor.data

/**
 * A board node's reference, parsed.
 *
 * @property connection The connection's name as written, or `null` when the
 *   reference names none (then the only connection is meant).
 * @property key The project's key prefix as written (e.g. `FRA`), or `""`
 *   when the reference is malformed ([isValid] is then `false`).
 * @property raw The text between `{{lunicle:` and `}}`, trimmed.
 */
data class LunicleNodeRef(val connection: String?, val key: String, val raw: String) {
    /** `true` when [raw] had the `[<connection>/]<KEY>` shape. */
    val isValid: Boolean get() = key.isNotEmpty()
}

/** Helpers for the `{{lunicle: …}}` part of a board node's text. */
object LunicleNode {
    private val QUERY = Regex("""\{\{lunicle:([^}]*)\}\}""")

    /** `[<connection>/]<KEY>`: a connection name as `LunicleHost` allows it, and a key prefix. */
    private val REF = Regex("""^(?:([A-Za-z0-9][A-Za-z0-9_-]*)\s*/\s*)?([A-Za-z0-9]+)$""")

    /**
     * The reference in [text] (a bullet's text after its `* `), or `null`
     * when it holds none. The first `{{lunicle: …}}` counts; one whose
     * inside is not `[<connection>/]<KEY>` comes back with an empty
     * [LunicleNodeRef.key], so the node can say what is wrong.
     */
    fun refOf(text: String): LunicleNodeRef? {
        val inside = QUERY.find(text)?.groupValues?.get(1)?.trim() ?: return null
        val m = REF.find(inside) ?: return LunicleNodeRef(null, "", inside)
        return LunicleNodeRef(m.groupValues[1].takeIf { it.isNotEmpty() }, m.groupValues[2], inside)
    }

    /** [text] without its `{{lunicle: …}}` parts (and the spaces they leave). */
    fun stripQuery(text: String): String =
        if ("{{lunicle:" !in text) text else QUERY.replace(text, "").replace(Regex("  +"), " ").trim()

    /**
     * Column range of the first `{{lunicle: …}}` in [text] at or after
     * [from], braces included, or `null`. Used by the tokenizer to style
     * it as a chip.
     */
    fun rangeIn(text: String, from: Int = 0): IntRange? = QUERY.find(text, from)?.range

    /** The reference as "Insert Lunicle board…" writes it: always the full form. */
    fun format(connection: String, key: String): String = "{{lunicle: $connection/$key}}"

    /**
     * [text] with both node queries — `{{search: …}}` ([SearchNode]) and
     * `{{lunicle: …}}` — taken out: what titles, folder names, breadcrumbs
     * and the text index use.
     */
    fun stripQueries(text: String): String = stripQuery(SearchNode.stripQuery(text))
}
