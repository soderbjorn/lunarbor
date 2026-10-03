/*
 * LunarborLink.kt (commonMain)
 * --------------------------
 * Pure codec for Lunarbor's own link targets: `lunarbor:` paths (TRF-8).
 *
 * A link target is a path from the vault root, made of the folder and file
 * names exactly as they are on disk (so already [FolderName]-encoded):
 *
 *     * See [soups](lunarbor:/Recipes/Soups)          → folder Recipes/Soups
 *     * Photo: [granola](lunarbor:/Recipes/granola.jpg) → a file in Recipes
 *     * Plan in [Budget 2027](lunarbor:/Budget%202027.md)
 *     * [Home](lunarbor:/)                             → the vault root
 *
 * On top of the on-disk names, each segment is percent-encoded for the
 * characters that would break an inline Markdown link destination or be
 * ambiguous in a path: whitespace and control characters, `%`, `(`, `)`,
 * `<`, `>`, `[`, `]`, `\`, `#`, `?`. `%` is always encoded so a folder name
 * that itself holds an escape (`Q3%2FQ4 plan`) round-trips
 * (`lunarbor:/Q3%252FQ4%20plan`). Everything else, non-ASCII included, is kept
 * verbatim so links stay readable in other Markdown tools. An encoded
 * target therefore never contains a space, bracket, parenthesis or
 * backslash, which is what lets [rewriteText] edit raw file text — outline
 * files included — without parsing the Markdown around it.
 *
 * Paths address folders and files, not bullets: a folder-backed bullet is
 * addressed by its folder. When a save renames or moves a folder or file,
 * `DocumentRegistry` rewrites every link that points at or through the old
 * path with [rewriteText] (see [PathMove]).
 *
 * No I/O. Side-effect free. commonMain only.
 */

package se.soderbjorn.lunarbor.data

/**
 * One rename or move of a folder or file, vault-relative, as applied by a
 * save: everything at or under [from] is now at the same place under [to].
 *
 * ### Callers
 * - Built by `Document` after each save from the folders it renamed,
 *   moved or trashed and the images it moved.
 * - Consumed by `DocumentRegistry` (link rewrite, link-index upkeep) and
 *   [LunarborLink.remap] / [LunarborLink.rewriteText].
 *
 * @property from The old vault-relative path (never `""`).
 * @property to The new vault-relative path.
 */
data class PathMove(val from: String, val to: String) {
    /** `true` when the move goes into or comes out of the trash. */
    val touchesTrash: Boolean get() = NoteRepository.isInTrash(from) || NoteRepository.isInTrash(to)
}

/**
 * The `lunarbor:` link codec.
 *
 * ### Callers
 * - `PaneBackingViewModel` formats links for Insert Link / "Link to node…"
 *   and parses them when a link is clicked or drawn.
 * - `NoteRepository` formats Starred entries.
 * - `DocumentRegistry` / `VaultIndex` rewrite and index links.
 */
object LunarborLink {

    /** Every Lunarbor link starts with this: scheme plus the root `/`. */
    const val PREFIX: String = "lunarbor:/"

    /** Characters (besides whitespace and controls) always percent-encoded in a segment. */
    private const val ENCODED: String = "%()<>[]\\#?"

    /** `true` when [url] is a Lunarbor link (whether or not it is well formed). */
    fun isLunarborLink(url: String): Boolean = url.startsWith(PREFIX)

    /**
     * Parses [url] into the vault-relative path it names.
     *
     * @return The path (`""` for `lunarbor:/`, the vault root), or `null` when
     *   [url] is not a `lunarbor:` link or is malformed: an empty, `.` or `..`
     *   segment, or a segment that decodes to something containing `/`.
     */
    fun parse(url: String): String? {
        if (!isLunarborLink(url)) return null
        val body = url.substring(PREFIX.length).removeSuffix("/")
        if (body.isEmpty()) return ""
        val out = ArrayList<String>()
        for (raw in body.split('/')) {
            val seg = decodeSegment(raw) ?: return null
            if (seg.isEmpty() || seg == "." || seg == ".." || '/' in seg) return null
            out += seg
        }
        return out.joinToString("/")
    }

    /**
     * The vault-relative [pathRel] as a person reads it: `/`, then each
     * on-disk name turned back into its title text ([FolderName.decode]:
     * `Work%3A Pizza` reads `Work: Pizza`), `/`-separated. No link encoding
     * — `%20` and friends are for the file, never for the screen.
     *
     * Called by the link search's result rows (`LinkSearchModal`) and the
     * link hover card (`LinkHoverPopup`) to say where a link goes.
     *
     * @param pathRel On-disk names, `/`-separated; `""` for the vault root.
     * @return `/` for the root, else e.g. `/Recipes/Soups` or `/Work: Pizza/plan.md`.
     */
    fun displayPath(pathRel: String): String =
        "/" + if (pathRel.isEmpty()) "" else pathRel.split('/').joinToString("/") { FolderName.decode(it) }

    /**
     * Formats the vault-relative [pathRel] (on-disk names, `/`-separated;
     * `""` for the vault root) as a `lunarbor:` link.
     */
    fun format(pathRel: String): String {
        if (pathRel.isEmpty()) return PREFIX
        return PREFIX + pathRel.split('/').joinToString("/") { encodeSegment(it) }
    }

    /**
     * Percent-encodes one on-disk name for use in a `lunarbor:` path: UTF-8
     * `%XX` for whitespace, control characters and [ENCODED]; everything
     * else verbatim.
     */
    fun encodeSegment(name: String): String {
        val sb = StringBuilder(name.length + 8)
        for (ch in name) {
            if (ch in ENCODED || ch.isWhitespace() || ch.code < 0x20 || ch.code == 0x7F) {
                for (b in ch.toString().encodeToByteArray()) {
                    val v = b.toInt() and 0xFF
                    sb.append('%').append(HEX[v shr 4]).append(HEX[v and 0xF])
                }
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Reverses [encodeSegment]: every `%XX` becomes its byte and the bytes
     * are read as UTF-8.
     *
     * @return The on-disk name, or `null` when a `%` is not followed by
     *   two hex digits.
     */
    fun decodeSegment(segment: String): String? {
        if ('%' !in segment) return segment
        val bytes = ArrayList<Byte>(segment.length)
        var i = 0
        while (i < segment.length) {
            val ch = segment[i]
            if (ch == '%') {
                if (i + 2 > segment.lastIndex) return null
                val hi = hex(segment[i + 1])
                val lo = hex(segment[i + 2])
                if (hi < 0 || lo < 0) return null
                bytes += ((hi shl 4) or lo).toByte()
                i += 3
                continue
            }
            val end = if (ch.isHighSurrogate() && i + 1 < segment.length) i + 2 else i + 1
            for (b in segment.substring(i, end).encodeToByteArray()) bytes += b
            i = end
        }
        return bytes.toByteArray().decodeToString()
    }

    private const val HEX = "0123456789ABCDEF"

    private fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'F' -> c - 'A' + 10
        in 'a'..'f' -> c - 'a' + 10
        else -> -1
    }

    // --------------------------------------------------------------- moves

    /**
     * Where [pathRel] is after [moves]: the move with the longest [PathMove.from]
     * that is [pathRel] itself or one of its ancestor folders wins, and the
     * rest of the path is kept below its [PathMove.to]. Longest-first makes
     * one save's moves compose — a folder moved on its own inside a renamed
     * parent is listed with its full old and new paths.
     *
     * @return The new path, or `null` when no move touches [pathRel].
     */
    fun remap(pathRel: String, moves: List<PathMove>): String? {
        var best: PathMove? = null
        for (m in moves) {
            if (m.from.isEmpty()) continue
            val hit = pathRel == m.from || pathRel.startsWith(m.from + "/")
            if (hit && (best == null || m.from.length > best.from.length)) best = m
        }
        val b = best ?: return null
        return b.to + pathRel.substring(b.from.length)
    }

    /**
     * One `lunarbor:` link found by [findLinks] in a piece of text.
     *
     * @property start Index of the `l` of `lunarbor:/` in the text.
     * @property end Index just past the link target.
     * @property pathRel The decoded vault-relative path.
     */
    data class Occurrence(val start: Int, val end: Int, val pathRel: String)

    /**
     * Every well-formed `lunarbor:` link target in [text] that sits right after
     * `(` or `(<` — i.e. in a Markdown link or image destination, whether
     * inline in a line or inside an outline bullet's title.
     * The target runs up to the first `)`, `>`, whitespace, `[`, `]` or `\`,
     * none of which an encoded target contains.
     */
    fun findLinks(text: String): List<Occurrence> {
        if (!text.contains(PREFIX)) return emptyList()
        val out = ArrayList<Occurrence>()
        var from = 0
        while (true) {
            val at = text.indexOf(PREFIX, from)
            if (at < 0) break
            from = at + PREFIX.length
            val opens = (at >= 1 && text[at - 1] == '(') ||
                (at >= 2 && text[at - 1] == '<' && text[at - 2] == '(')
            if (!opens) continue
            var end = at + PREFIX.length
            while (end < text.length && text[end] !in ")>[]\\" && !text[end].isWhitespace()) end++
            val path = parse(text.substring(at, end)) ?: continue
            out += Occurrence(at, end, path)
            from = end
        }
        return out
    }

    /** The decoded target paths of every link in [text] (see [findLinks]). */
    fun linkPathsIn(text: String): Set<String> = findLinks(text).mapTo(LinkedHashSet()) { it.pathRel }

    /**
     * Rewrites every `lunarbor:` link in [text] whose target [remap]s under
     * [moves]. Moves into or out of the trash are ignored — a link to a
     * trashed folder stays as it is and shows as broken (undo brings the
     * folder back, and the link with it).
     *
     * @return The new text, or `null` when nothing changed.
     */
    fun rewriteText(text: String, moves: List<PathMove>): String? {
        val live = moves.filter { !it.touchesTrash }
        if (live.isEmpty()) return null
        val links = findLinks(text)
        if (links.isEmpty()) return null
        val sb = StringBuilder(text.length + 16)
        var last = 0
        var changed = false
        for (occ in links) {
            val moved = remap(occ.pathRel, live) ?: continue
            if (moved == occ.pathRel) continue
            sb.append(text, last, occ.start).append(format(moved))
            last = occ.end
            changed = true
        }
        if (!changed) return null
        sb.append(text, last, text.length)
        return sb.toString()
    }
}
