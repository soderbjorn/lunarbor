/*
 * SubtreeCodec.kt
 * ---------------
 * Pure helpers used by `NoteRepository` to translate between an in-memory
 * composed outline and a directory tree of `.md` files connected by
 * `[Title](Title/Title.md)` markdown links.
 *
 * The on-disk format is plain CommonMark: each promoted bullet is a list
 * item whose entire content is an inline markdown link pointing at a
 * sibling subdirectory file. Parsing/formatting that link is what this
 * file does.
 *
 * No I/O, no state — every function here is total and deterministic. This
 * is deliberate: the repository is the only thing that touches the
 * filesystem and the only thing that holds mutable promotion state.
 * Keeping the codec pure makes round-trip testing (parse → format → parse)
 * trivial.
 *
 * commonMain only.
 */

package se.soderbjorn.notegrow.data

import se.soderbjorn.notegrow.main.DocumentLayout

/**
 * Parsed form of a `* [Title](path)` markdown-link bullet that promotes
 * its children into a separate file.
 *
 * @property line The original line as it appeared in the parent file.
 * @property bulletText The bullet content the editor sees in place of the
 *   raw link — `<indent>* <Title>`. Reconstructed from the link's label
 *   so renderers, cursor logic, and selection helpers can treat the row
 *   as an ordinary bullet.
 * @property refPath The link's URL, relative to the parent file's
 *   directory. Always shaped `<Name>/<Name>.md`.
 * @property indent Leading-space count of the line.
 */
data class SubtreeRef(
    val line: String,
    val bulletText: String,
    val refPath: String,
    val indent: Int,
)

/** Result of [findSubtrees]: every bullet's row, end, indent, and descendant count. */
data class SubtreeMeasurement(
    val startRow: Int,
    val endRowInclusive: Int,
    val indent: Int,
    val descendantCount: Int,
)

/**
 * Pure helpers for the auto-promotion pipeline.
 *
 * ### Callers
 * - `NoteRepository.load` invokes [parseRef] on every line while resolving
 *   nested `.md` files into one composed outline.
 * - `NoteRepository.save` invokes [findSubtrees], [reindentBy], [safeFilename],
 *   and [uniqueFilename] when deciding which subtrees to spin out, rename, or
 *   inline back.
 *
 * ### The `#notegrow` URL fragment
 *
 * A markdown link bullet is treated as a Notegrow promoted-ref boundary
 * **iff** its URL ends in the literal fragment `#notegrow`. Plain markdown
 * links (`[Foo](Foo.md)`) and links with any other fragment
 * (`[Foo](Foo.md#section)`) render as literal link bullets — Notegrow
 * never reads or rewrites their target files.
 *
 * The fragment is invisible to the human reader in every CommonMark
 * viewer (Obsidian, VS Code, GitHub, …): the link still navigates to the
 * file, the unresolved `#notegrow` anchor is silently ignored. This is
 * what lets a Notegrow tree round-trip through arbitrary markdown
 * tooling without ceremony — no per-file frontmatter, no custom syntax,
 * just a stale heading anchor that other tools shrug off.
 */
object SubtreeCodec {

    /** URL fragment that distinguishes a Notegrow promoted-ref bullet. */
    const val NOTEGROW_FRAGMENT: String = "#notegrow"

    /**
     * A markdown link bullet's parts, returned by [parseAnyLinkBullet].
     * Distinct from [SubtreeRef] because this captures the URL verbatim
     * (including any fragment), which the caller may want to inspect to
     * decide whether the bullet is a Notegrow promoted ref, a hand-authored
     * cross-reference (URL has a fragment other than `#notegrow`), or a
     * legacy bare-URL link to a file (no fragment at all).
     *
     * @property indent Leading-space count of the line.
     * @property bulletText The bullet's display form (`<indent>* <label>`)
     *   — what the editor would show in place of the raw markdown.
     * @property url The link's URL, **including** any `#…` fragment.
     */
    data class LinkBullet(
        val indent: Int,
        val bulletText: String,
        val url: String,
    )

    /**
     * Detects a `* [Title](url#notegrow)` bullet and returns its parts.
     *
     * The accepted shape is: indent, `* `, `[`, label (no unescaped `]`),
     * `]`, `(`, URL (either bare or wrapped in `<…>` to allow spaces),
     * `)`, optional trailing whitespace, AND the URL must end with the
     * exact `#notegrow` fragment ([NOTEGROW_FRAGMENT]). Other markdown
     * link bullets (no fragment, or any other fragment) return `null`
     * here — they are not promoted refs and the editor renders them as
     * literal links. Returned [SubtreeRef.refPath] has the fragment
     * stripped; downstream code sees only the file path.
     *
     * The label is unescaped (CommonMark backslash escapes for `[`,
     * `]`, `(`, `)`, `\` are resolved).
     *
     * @return A [SubtreeRef] when [line] is a Notegrow promoted-ref
     *   bullet, or `null` otherwise.
     */
    fun parseRef(line: String): SubtreeRef? {
        val bullet = parseAnyLinkBullet(line) ?: return null
        // Only links carrying the exact `#notegrow` fragment are Notegrow
        // promoted refs. Strip the fragment before storing the path so
        // callers can pass `refPath` straight to the filesystem.
        if (!bullet.url.endsWith(NOTEGROW_FRAGMENT)) return null
        val refPath = bullet.url.substring(0, bullet.url.length - NOTEGROW_FRAGMENT.length)
        if (refPath.isBlank()) return null
        return SubtreeRef(
            line = line,
            bulletText = bullet.bulletText,
            refPath = refPath,
            indent = bullet.indent,
        )
    }

    /**
     * Parses any markdown-link bullet of the form
     * `<indent>* [Label](url)` (URL optionally wrapped in `<…>`),
     * regardless of whether the URL carries a `#notegrow` fragment.
     *
     * Returned for both promoted-ref bullets *and* hand-authored
     * cross-references / legacy bare-URL bullets — the caller inspects
     * [LinkBullet.url] to decide which kind it has. Use [parseRef] when
     * you only want promoted refs.
     *
     * Returns `null` when the line is not a markdown-link bullet at all
     * (plain bullets, non-bullet lines, malformed link syntax, empty
     * label, or empty URL).
     */
    fun parseAnyLinkBullet(line: String): LinkBullet? {
        val indent = DocumentLayout.bulletAsteriskColumn(line)
        if (indent < 0) return null
        val afterMarker = indent + 2
        if (afterMarker >= line.length) return null
        // Trim only the trailing whitespace; leading is `<indent>* `.
        val trimmedRight = line.trimEnd()
        if (trimmedRight.length <= afterMarker) return null
        if (trimmedRight[afterMarker] != '[') return null

        // Find the matching `]` for the label, honoring backslash escapes.
        val labelStart = afterMarker + 1
        var i = labelStart
        val labelBuilder = StringBuilder()
        while (i < trimmedRight.length) {
            val ch = trimmedRight[i]
            if (ch == '\\' && i + 1 < trimmedRight.length) {
                val next = trimmedRight[i + 1]
                if (next == '[' || next == ']' || next == '(' || next == ')' || next == '\\') {
                    labelBuilder.append(next)
                    i += 2
                    continue
                }
            }
            if (ch == ']') break
            labelBuilder.append(ch)
            i++
        }
        if (i >= trimmedRight.length || trimmedRight[i] != ']') return null
        // Next must be `(`, no space allowed (CommonMark requires no space
        // between `]` and `(` for inline links).
        if (i + 1 >= trimmedRight.length || trimmedRight[i + 1] != '(') return null

        val urlStart = i + 2
        // Match the closing `)` — last `)` of the line should be it. We
        // don't support nested parens in URLs (rare, and CommonMark's
        // rules are permissive only for balanced parens; keep it simple).
        if (!trimmedRight.endsWith(")")) return null
        val urlEnd = trimmedRight.length - 1
        if (urlEnd <= urlStart) return null
        var url = trimmedRight.substring(urlStart, urlEnd)
        if (url.startsWith("<") && url.endsWith(">")) {
            url = url.substring(1, url.length - 1)
        }
        if (url.isBlank()) return null

        val label = labelBuilder.toString()
        if (label.isEmpty()) return null

        val bulletText = " ".repeat(indent) + "* " + label
        return LinkBullet(indent = indent, bulletText = bulletText, url = url)
    }

    /**
     * Renders a Notegrow promoted-ref markdown-link bullet for [title]
     * pointing at [refPath]. The emitted URL always carries the
     * [NOTEGROW_FRAGMENT] suffix so [parseRef] will recognize it as a
     * Notegrow ref on the next load.
     *
     * @param indent Leading-space count for the rendered line.
     * @param title Display label. Will be backslash-escaped per CommonMark
     *   for the `[`, `]`, `(`, `)`, and `\` characters.
     * @param refPath URL to point at, **without** the `#notegrow`
     *   fragment — this function appends it. Wrapped in `<…>` if the
     *   resulting URL contains a space, paren, `<`, or `>`.
     */
    fun formatRef(indent: Int, title: String, refPath: String): String =
        " ".repeat(indent) + "* [" + escapeLinkLabel(title) + "](" + formatLinkUrl(refPath + NOTEGROW_FRAGMENT) + ")"

    /**
     * Renders a plain markdown-link bullet (no `#notegrow` fragment) so the
     * link is treated as foreign by [parseRef] and never auto-spliced.
     *
     * Used for the Starred bookmarks file, where the link's purpose is
     * navigation rather than subtree promotion. The caller may include a
     * non-Notegrow fragment in [href] (e.g. `#L=42`) to encode an
     * intra-document anchor.
     *
     * @param indent Leading-space count for the rendered line.
     * @param label Display label. Backslash-escaped per CommonMark for
     *   `[`, `]`, `(`, `)`, and `\`.
     * @param href URL to point at, verbatim. Wrapped in `<…>` when it
     *   contains a space, paren, `<`, or `>`.
     */
    fun formatPlainLinkBullet(indent: Int, label: String, href: String): String =
        " ".repeat(indent) + "* [" + escapeLinkLabel(label) + "](" + formatLinkUrl(href) + ")"

    /**
     * Computes per-bullet metrics over the whole [lines] list.
     *
     * Each entry corresponds to a bullet line in [lines]; non-bullet lines are
     * skipped. The walk is single-pass and uses [DocumentLayout.subtreeEnd]
     * for the end-row computation, so behaviour matches what the editor's
     * zoom feature considers a "subtree".
     */
    fun findSubtrees(lines: List<String>): List<SubtreeMeasurement> {
        val out = ArrayList<SubtreeMeasurement>(lines.size)
        for (i in lines.indices) {
            val indent = DocumentLayout.bulletAsteriskColumn(lines[i])
            if (indent < 0) continue
            val end = DocumentLayout.subtreeEnd(lines, i, indent)
            out += SubtreeMeasurement(
                startRow = i,
                endRowInclusive = end,
                indent = indent,
                descendantCount = end - i,
            )
        }
        return out
    }

    /**
     * Returns [lines] with each bullet line's leading whitespace shifted by
     * [delta] *characters*. Positive [delta] indents (used when inlining a
     * promoted child file's contents into a parent), negative outdents (used
     * when extracting a subtree to a child file at indent 0). Non-bullet
     * lines are returned unchanged.
     *
     * Bullets whose indent would drop below zero stay at column 0; this keeps
     * the operation total but should not happen in practice because the
     * caller computes [delta] from a real subtree's root indent.
     */
    fun reindentBy(lines: List<String>, delta: Int): List<String> {
        if (delta == 0) return lines
        return lines.map { line ->
            val indent = DocumentLayout.bulletAsteriskColumn(line)
            if (indent < 0) {
                line
            } else if (delta > 0) {
                " ".repeat(delta) + line
            } else {
                val drop = (-delta).coerceAtMost(indent)
                line.substring(drop)
            }
        }
    }

    /**
     * Extracts the title text from a bullet line — i.e. the content after the
     * leading indent and `"* "` marker. Returns the empty string when [line]
     * is not a bullet or carries no title yet.
     *
     * If [line] is a markdown-link bullet, the link's label is returned (so
     * the title matches what the editor displays, not the raw link source).
     */
    fun titleOf(line: String): String {
        val indent = DocumentLayout.bulletAsteriskColumn(line)
        if (indent < 0) return ""
        val ref = parseRef(line)
        if (ref != null) {
            // bulletText is `<indent>* <label>`; slice off the marker.
            val titleStart = indent + 2
            return if (titleStart >= ref.bulletText.length) "" else ref.bulletText.substring(titleStart)
        }
        val titleStart = indent + 2
        if (titleStart >= line.length) return ""
        return line.substring(titleStart)
    }

    // -------------------------------------------------------- link helpers

    private fun escapeLinkLabel(label: String): String {
        val sb = StringBuilder(label.length)
        for (ch in label) {
            when (ch) {
                '\\', '[', ']', '(', ')' -> {
                    sb.append('\\'); sb.append(ch)
                }
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun formatLinkUrl(path: String): String {
        val needsAngle = path.any { it == ' ' || it == '(' || it == ')' || it == '<' || it == '>' }
        return if (needsAngle) "<$path>" else path
    }

    // -------------------------------------------------------------- filenames

    private val FILESYSTEM_ILLEGAL = Regex("[\u0000/]")
    private val WHITESPACE_RUN = Regex("\\s+")
    private const val MAX_FILENAME_BYTES: Int = 200
    private const val UNTITLED: String = "untitled"

    /**
     * Returns [title] reshaped into a filesystem-safe basename, preserving as
     * much of the original (case, spaces, non-ASCII) as possible. Strategy:
     *
     * 1. Strip leading/trailing whitespace.
     * 2. Replace `/` and `NUL` (the only characters APFS forbids) with `-`.
     * 3. Strip leading dots so the file isn't hidden in Finder/CLI listings.
     * 4. Collapse runs of whitespace to a single space.
     * 5. Cap to [MAX_FILENAME_BYTES] UTF-8 bytes (POSIX allows 255, but we
     *    leave headroom for the `.md` extension and a possible ` 2`
     *    disambiguator).
     * 6. Fall back to `untitled` if the result is empty.
     */
    fun safeFilename(title: String): String {
        var s = title.trim()
        s = FILESYSTEM_ILLEGAL.replace(s, "-")
        s = s.trimStart('.')
        s = WHITESPACE_RUN.replace(s, " ")
        s = trimToByteBudget(s, MAX_FILENAME_BYTES)
        return if (s.isEmpty()) UNTITLED else s
    }

    /**
     * Returns [base] if it's not in [used], otherwise `base 2`, `base 3`, …
     * until a free name is found. The returned name is **not** automatically
     * added to [used] — callers add it themselves once they've committed to
     * the filename, since they may need to roll back in error paths.
     */
    fun uniqueFilename(base: String, used: Set<String>): String {
        if (base !in used) return base
        var n = 2
        while (true) {
            val candidate = "$base $n"
            if (candidate !in used) return candidate
            n++
        }
    }

    /**
     * Trims [s] from the right one Char at a time until its UTF-8 encoding fits
     * within [maxBytes]. Strings already within budget are returned unchanged.
     */
    private fun trimToByteBudget(s: String, maxBytes: Int): String {
        if (utf8Bytes(s) <= maxBytes) return s
        var trimmed = s
        while (trimmed.isNotEmpty() && utf8Bytes(trimmed) > maxBytes) {
            trimmed = trimmed.substring(0, trimmed.length - 1)
        }
        return trimmed.trimEnd()
    }

    /** Counts the byte length of [s] when encoded as UTF-8 (no allocation of the bytes). */
    private fun utf8Bytes(s: String): Int {
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val c = s[i].code
            bytes += when {
                c < 0x80 -> 1
                c < 0x800 -> 2
                c in 0xD800..0xDBFF && i + 1 < s.length && s[i + 1].code in 0xDC00..0xDFFF -> {
                    i++
                    4
                }
                else -> 3
            }
            i++
        }
        return bytes
    }
}
