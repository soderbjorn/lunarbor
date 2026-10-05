/*
 * LunarborLink.kt (commonMain)
 * --------------------------
 * Pure codec for links between the vault's nodes and files (TRF-8).
 *
 * **In the files** a link is a plain relative Markdown link, as GitHub,
 * Obsidian and any other Markdown tool read it: relative to the folder the
 * line is stored in, a node named by its outline file.
 *
 *     * See [soups](Recipes/Soups/_node.md)      → node Recipes/Soups (from the root)
 *     * Back to [pasta](../Pasta/_node.md)       → from inside Recipes/Soups
 *     * Photo: [granola](Recipes/granola.jpg)    → a file in Recipes
 *     * Plan in [Budget 2027](Budget%202027.md)
 *
 * [resolve] reads one against its line's folder; [relative] writes one.
 * Older vaults wrote `lunarbor:/Recipes/Soups`, and a `/Recipes/Soups`
 * destination is vault-rooted (as an image `src` is): both are still read,
 * and [rebaseText] turns them into relative links.
 *
 * **In the app** a resolved link is vault-rooted, `/Recipes/Soups`
 * ([rooted] / [parseRooted]): what the view puts in `data-href`, what
 * Starred, the link search and navigation pass around. It never needs a
 * base, and a file may hold it too (it becomes relative on the next save).
 *
 * Either way the path is made of the folder and file names exactly as
 * they are on disk (so already [FolderName]-encoded):
 *
 *     /Recipes/Soups            → folder Recipes/Soups
 *     /Budget%202027.md         → a file
 *     /                         → the vault root
 *
 * On top of the on-disk names, each segment is percent-encoded for the
 * characters that would break an inline Markdown link destination or be
 * ambiguous in a path: whitespace and control characters, `%`, `(`, `)`,
 * `<`, `>`, `[`, `]`, `\`, `#`, `?`. `%` is always encoded so a folder name
 * that itself holds an escape (`Q3%2FQ4 plan`) round-trips
 * (`/Q3%252FQ4%20plan`). Everything else, non-ASCII included, is kept
 * verbatim so links stay readable in other Markdown tools. An encoded
 * target therefore never contains a space, bracket, parenthesis or
 * backslash, which is what lets [rewriteText] edit raw file text — outline
 * files included — without parsing the Markdown around it.
 *
 * Paths address folders and files, not bullets: a folder-backed bullet is
 * addressed by its folder. When a save renames or moves a folder or file,
 * `DocumentRegistry` rewrites every link that points at or through the old
 * path, and every link in a file that moved with it, with [rebaseText]
 * (see [PathMove]); `Document` does the same for rows a save moved into
 * another folder.
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
 * The link codec: relative links in files, vault-rooted links in the app.
 *
 * ### Callers
 * - `PaneBackingViewModel` formats links for Insert Link / "Insert Mirror…"
 *   and parses them when a link is clicked or drawn.
 * - `NoteRepository` formats Starred entries.
 * - `DocumentRegistry` / `VaultIndex` rewrite and index links.
 */
object LunarborLink {

    /** How older vaults wrote a link (`lunarbor:/Recipes/Soups`); still read, never written. */
    private const val LEGACY_PREFIX: String = "lunarbor:/"

    /**
     * Characters (besides whitespace and controls) always percent-encoded in
     * a segment. `:` too, so a relative link's first segment never reads as
     * a URL scheme.
     */
    private const val ENCODED: String = "%()<>[]\\#?:"

    /** `true` when [url] is an older vault's `lunarbor:/…` link. */
    fun isLegacyLink(url: String): Boolean = url.startsWith(LEGACY_PREFIX)

    /**
     * `true` when [href] is vault-rooted (`/…`, not a `//host` URL) — how
     * the app passes a resolved link around ([rooted]). Whether or not it
     * is well formed.
     */
    fun isRooted(href: String): Boolean = href.startsWith("/") && !href.startsWith("//")

    /**
     * Parses the vault-rooted [href] ([rooted]) into the vault-relative
     * path it names.
     *
     * @return The path (`""` for `/`, the vault root), or `null` when
     *   [href] is not vault-rooted or is malformed (see [resolve]).
     */
    fun parseRooted(href: String): String? = if (isRooted(href)) resolve(href, "") else null

    /** The vault path an older vault's `lunarbor:/…` link names, or `null` (see [parseRooted]). */
    private fun parseLegacy(url: String): String? =
        if (isLegacyLink(url)) parseRooted(url.substring(LEGACY_PREFIX.length - 1)) else null

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
     * The vault-relative [pathRel] (on-disk names, `/`-separated; `""` for
     * the vault root) as a vault-rooted link, each segment percent-encoded
     * ([encodeSegment]): `/Recipes/Soups`, `/Budget%202027.md`, `/`. The
     * form the app passes resolved links around in — `data-href`, Starred,
     * the link search, navigation — and a valid destination in a file too.
     */
    fun rooted(pathRel: String): String {
        if (pathRel.isEmpty()) return "/"
        return "/" + pathRel.split('/').joinToString("/") { encodeSegment(it) }
    }

    /**
     * Percent-encodes one on-disk name for use in a link path: UTF-8
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

    // ------------------------------------------------------------ in files

    /** The outline file that names a node in a relative link. */
    private const val OUTLINE: String = "_node.md"

    /** A destination starting with a URL scheme (`https:`, `mailto:`, `obsidian:`, …). */
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    /** A last path segment with a file extension (`plan.md`, `shot.png`). */
    private val FILE_NAME = Regex("^.+\\.[A-Za-z0-9]{1,8}$")

    /**
     * The vault path a link destination written in a file names, read
     * against [baseFolder] — the folder the line is stored in.
     *
     * - A relative path (`Soups/_node.md`, `../Pasta/_node.md`, `plan.md`,
     *   `./x`) resolves against [baseFolder]; a trailing `_node.md` names
     *   its folder (the node), so `_node.md` alone is [baseFolder] itself.
     * - `/…` is vault-rooted, `lunarbor:/…` an older vault's link.
     * - `<…>` around it, `%XX` escapes, and a `#fragment` or `?query` after
     *   it are allowed.
     *
     * @return The vault-relative path (`""` for the root), or `null` for an
     *   external link (a URL scheme, `//host`, `#anchor`), a malformed one,
     *   or one that climbs out of the vault.
     */
    fun resolve(dest: String, baseFolder: String): String? {
        var d = dest.trim()
        if (d.length >= 2 && d.startsWith("<") && d.endsWith(">")) d = d.substring(1, d.length - 1).trim()
        if (d.isEmpty()) return null
        if (isLegacyLink(d)) return parseLegacy(d)
        if (d.startsWith("#") || d.startsWith("//") || SCHEME.containsMatchIn(d)) return null
        d = d.substringBefore('#').substringBefore('?')
        if (d.isEmpty()) return null
        val segs = ArrayList<String>()
        if (!d.startsWith("/") && baseFolder.isNotEmpty()) segs += baseFolder.split('/')
        for (raw in d.split('/')) {
            if (raw.isEmpty() || raw == ".") continue
            val seg = decodeSegment(raw) ?: return null
            if (seg == "..") {
                if (segs.isEmpty()) return null
                segs.removeAt(segs.lastIndex)
                continue
            }
            if (seg.isEmpty() || seg == "." || '/' in seg) return null
            segs += seg
        }
        if (segs.lastOrNull() == OUTLINE) segs.removeAt(segs.lastIndex)
        return segs.joinToString("/")
    }

    /**
     * Writes a link to [pathRel] as a file in [baseFolder] holds it:
     * relative, each segment percent-encoded ([encodeSegment]), so it never
     * holds a space, bracket, parenthesis or backslash. A node ([isFolder])
     * is named by its outline file, `…/_node.md`, which is what other
     * Markdown tools open. A node in a direct subfolder is written
     * `./Plan/_node.md`: `Plan/_node.md` at the end of a bullet would read as
     * the bullet's own child link (`SubtreeCodec`).
     *
     * @param pathRel The target, vault-relative (`""` for the root).
     * @param isFolder `true` for a folder (a node), `false` for a file.
     * @param baseFolder The folder the line is stored in.
     */
    fun relative(pathRel: String, isFolder: Boolean, baseFolder: String): String {
        val target = (if (pathRel.isEmpty()) emptyList() else pathRel.split('/')) +
            (if (isFolder) listOf(OUTLINE) else emptyList())
        val base = if (baseFolder.isEmpty()) emptyList() else baseFolder.split('/')
        var common = 0
        while (common < base.size && common < target.size - 1 && base[common] == target[common]) common++
        val parts = List(base.size - common) { ".." } + target.drop(common).map { encodeSegment(it) }
        val childOutline = isFolder && parts.size == 2 && parts[0] != ".."
        return (if (childOutline) "./" else "") + parts.joinToString("/")
    }

    /**
     * Whether a target written without `_node.md` is best linked as a
     * folder: `false` when its name has a file extension. For links whose
     * kind is not known (an older vault's `lunarbor:/`, a moved target).
     */
    fun looksLikeFolder(pathRel: String): Boolean = !FILE_NAME.matches(pathRel.substringAfterLast('/'))

    /**
     * One link to the vault found by [findLinks].
     *
     * @property start Index of the destination in the text (its `<` when
     *   bracketed).
     * @property end Index just past the destination (past its `>`).
     * @property pathRel The vault-relative path it names.
     * @property dest The destination as written (without `<>`).
     * @property isRelative `false` for a `/…` (or an older `lunarbor:/…`) destination.
     * @property namesOutline `true` when it names a node by its `_node.md`.
     */
    data class Occurrence(
        val start: Int,
        val end: Int,
        val pathRel: String,
        val dest: String = "",
        val isRelative: Boolean = false,
        val namesOutline: Boolean = false,
    )

    /**
     * Every Markdown link in [text] that names a place in the vault
     * ([resolve] against [baseFolder]): `[label](destination)`, inline in
     * a line or inside an outline bullet's title or a block. Images
     * (`![alt](src)`) and an outline's child links (`[↳](<…/_node.md>)`, its
     * structure) are not links here; nor is anything external.
     *
     * @param baseFolder The folder the text's lines are stored in.
     */
    fun findLinks(text: String, baseFolder: String): List<Occurrence> {
        if (!text.contains("](")) return emptyList()
        val out = ArrayList<Occurrence>()
        var i = text.indexOf("](")
        while (i >= 0) {
            val open = labelStart(text, i)
            val destStart = i + 2
            val (destEnd, dest) = destinationAt(text, destStart) ?: run {
                i = text.indexOf("](", destStart)
                null
            } ?: continue
            if (open >= 0 && !(open > 0 && text[open - 1] == '!') && text.substring(open + 1, i) != CHILD_LINK_LABEL) {
                val path = resolve(dest, baseFolder)
                if (path != null) {
                    val relative = !isLegacyLink(dest) && !dest.startsWith("/")
                    val outline = dest.substringBefore('#').substringBefore('?').let { it == OUTLINE || it.endsWith("/$OUTLINE") }
                    out += Occurrence(destStart, destEnd, path, dest, relative, outline)
                }
            }
            i = text.indexOf("](", destEnd)
        }
        return out
    }

    /** The label of an outline's child link (`SubtreeCodec`); never a link here. */
    private const val CHILD_LINK_LABEL: String = "↳"

    /** Index of the `[` whose label ends at the `]` at [close], or -1 (same line, escapes and nesting honoured). */
    private fun labelStart(text: String, close: Int): Int {
        var depth = 0
        var j = close - 1
        while (j >= 0) {
            val c = text[j]
            if (c == '\n') return -1
            var slashes = 0
            while (j - 1 - slashes >= 0 && text[j - 1 - slashes] == '\\') slashes++
            if (slashes % 2 == 0) {
                if (c == ']') depth++
                if (c == '[') {
                    if (depth == 0) return j
                    depth--
                }
            }
            j--
        }
        return -1
    }

    /**
     * The destination starting at [from] (right after `](`): `<…>` up to
     * `>`, or up to the first whitespace or `)`. Must be followed by `)`
     * (or by a space and a title). Returns its end and its text without
     * `<>`, or `null` when it is no destination.
     */
    private fun destinationAt(text: String, from: Int): Pair<Int, String>? {
        if (from >= text.length) return null
        if (text[from] == '<') {
            val close = text.indexOf('>', from + 1)
            if (close < 0 || '\n' in text.substring(from, close)) return null
            val after = close + 1
            if (after >= text.length || (text[after] != ')' && text[after] != ' ')) return null
            return after to text.substring(from + 1, close)
        }
        var end = from
        while (end < text.length && text[end] != ')' && !text[end].isWhitespace()) end++
        if (end == from || end >= text.length || (text[end] != ')' && text[end] != ' ')) return null
        return end to text.substring(from, end)
    }

    /**
     * The folder the lines of the vault file [fileRel] are stored in, which
     * their links are relative to: an outline's node folder, a note's
     * folder (both its parent).
     */
    fun baseOfFile(fileRel: String): String = fileRel.substringBeforeLast('/', missingDelimiterValue = "")

    /** The paths of every link in [text] (see [findLinks]). */
    fun linkPathsIn(text: String, baseFolder: String): Set<String> =
        findLinks(text, baseFolder).mapTo(LinkedHashSet()) { it.pathRel }

    /**
     * Rewrites the links in [text] so they are right after a move:
     *
     * - the text was written relative to [oldBase] and is now stored in
     *   [newBase] (a row moved to another folder, a file moved with its
     *   folder);
     * - a target that [moves] renamed or moved is linked at its new path
     *   (moves into or out of the trash are ignored — a link to a trashed
     *   folder stays as it is and shows as broken, and undo brings it back);
     * - a link written the old ways (`lunarbor:/…`, `/…`) becomes relative.
     *
     * A link whose target and base are unchanged stays exactly as written.
     *
     * @param isFolder For a target written without `_node.md` in the old
     *   ways: whether it is a folder (the default guesses by its name,
     *   [looksLikeFolder]).
     * @return The new text, or `null` when nothing changed.
     */
    fun rebaseText(
        text: String,
        oldBase: String,
        newBase: String,
        moves: List<PathMove> = emptyList(),
        isFolder: (String) -> Boolean = ::looksLikeFolder,
    ): String? {
        val links = findLinks(text, oldBase)
        if (links.isEmpty()) return null
        val live = moves.filter { !it.touchesTrash }
        val sb = StringBuilder(text.length + 16)
        var last = 0
        var changed = false
        for (occ in links) {
            val target = remap(occ.pathRel, live) ?: occ.pathRel
            if (occ.isRelative && target == occ.pathRel && oldBase == newBase) continue
            val folder = occ.namesOutline || (!occ.isRelative && isFolder(target)) ||
                (occ.isRelative && target.isEmpty())
            val dest = relative(target, folder, newBase)
            if (dest == occ.dest && text[occ.start] != '<') continue
            sb.append(text, last, occ.start).append(dest)
            last = occ.end
            changed = true
        }
        if (!changed) return null
        sb.append(text, last, text.length)
        return sb.toString()
    }

    /**
     * [text] with every link to the vault written vault-rooted (`/…`), so
     * it means the same wherever it is pasted. Called when rows are copied
     * to the clipboard; the next save writes them relative again.
     *
     * @param baseFolder The folder [text]'s lines are stored in.
     */
    fun rootedText(text: String, baseFolder: String): String {
        val links = findLinks(text, baseFolder)
        if (links.isEmpty()) return text
        val sb = StringBuilder(text.length + 16)
        var last = 0
        for (occ in links) {
            // From the root, a direct subfolder's node reads `./Plan/_node.md`
            // (see [relative]); rooted, the `./` is noise: `/Plan/_node.md`.
            val dest = "/" + relative(occ.pathRel, occ.namesOutline, "").removePrefix("./")
            sb.append(text, last, occ.start).append(dest)
            last = occ.end
        }
        sb.append(text, last, text.length)
        return sb.toString()
    }
}
