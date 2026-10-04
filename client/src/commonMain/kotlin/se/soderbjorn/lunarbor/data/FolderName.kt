/*
 * FolderName.kt (commonMain)
 * --------------------------
 * Turns a bullet title into the name of the folder that backs it, and back.
 *
 * Every bullet with content lives in its own folder (see `NoteRepository`).
 * The folder name is derived from the title's name ([nameTextOf]: plain text,
 * `#tags` left out) — Markdown markers
 * removed — and percent-encoded where the character would be unsafe or
 * surprising on disk, so the encoding can always be reversed with [decode]:
 *
 *  - `/ \ : * ? " < > |` and `%` itself (`Q3/Q4 plan` → `Q3%2FQ4 plan`,
 *    `50% done` → `50%25 done`);
 *  - a leading dot (so the folder is never hidden);
 *  - trailing dots and spaces (Windows and some sync tools drop them);
 *  - control characters.
 *
 * Names are capped at [MAX_NAME_BYTES] UTF-8 bytes before any collision
 * suffix; sibling collisions are resolved case-insensitively (macOS file
 * systems) with ` (2)`, ` (3)`, … by [unique].
 *
 * Pure and stateless; commonMain only.
 */

package se.soderbjorn.lunarbor.data

/**
 * Folder-name codec for folder-backed bullets.
 *
 * ### Callers
 * - `NoteRepository.save` names every folder it creates or renames with
 *   [forTitle] + [unique].
 * - `VaultIndex` and the folder contents list show folder names as titles via [decode].
 */
object FolderName {

    /** Cap on an encoded name, in UTF-8 bytes, before any ` (n)` suffix. */
    const val MAX_NAME_BYTES: Int = 120

    /** Name used for a folder-backed bullet whose title is empty. */
    const val UNTITLED: String = "Untitled"

    /** Characters that are always percent-encoded, wherever they appear. */
    private const val ALWAYS_ENCODED: String = "/\\:*?\"<>|%"

    /**
     * Folder name for a bullet titled [title]: the title's plain text
     * ([plainTextOf]) run through [encode], or [UNTITLED] when that plain
     * text is empty. No collision suffix — pass the result to [unique].
     *
     * @param title The bullet's title as stored in the outline (inline
     *   Markdown allowed, no `* ` marker).
     */
    fun forTitle(title: String): String {
        val plain = nameTextOf(title)
        if (plain.isEmpty()) return UNTITLED
        return encode(plain)
    }

    /**
     * The visible text of [title] with inline Markdown markers (bold,
     * italic, links, images, …) and any line-level prefix (`# `, `> `)
     * removed — what the reader sees in the editor.
     */
    fun plainTextOf(title: String): String {
        // A search node's `{{search: …}}` is its query, not its title.
        val shown = SearchNode.stripQuery(title)
        val prefix = LineMarkdownPrefix.detect(shown, 0)
        val body = if (prefix.style != null) shown.substring(prefix.markerEnd) else shown
        return InlineMarkdownTokenizer.tokenize(body).displayText
    }

    /**
     * The item's *name*: [plainTextOf] without its `#tags` (as the
     * tokenizer recognises them), each taken out with one adjoining space
     * so `1-1 #private` is `1-1` and `Meet #x with Bob` is `Meet with Bob`.
     * Tags are labels on the item, not part of what it is called: folder
     * names ([forTitle]), breadcrumbs, pane labels, link-target titles and
     * Starred labels use this, while the item's line keeps its tags (they
     * still show as pills, are searched and drive privacy modes). A title
     * without tags gives exactly [plainTextOf]'s text.
     *
     * @param title The item's title as stored (inline Markdown allowed,
     *   no `* ` marker).
     */
    fun nameTextOf(title: String): String {
        val shown = SearchNode.stripQuery(title)
        val prefix = LineMarkdownPrefix.detect(shown, 0)
        val body = if (prefix.style != null) shown.substring(prefix.markerEnd) else shown
        return withoutTags(InlineMarkdownTokenizer.tokenize(body))
    }

    /**
     * [line]'s display text with its tag runs removed (see [nameTextOf]).
     * For text whose line-level prefix is already stripped — the
     * breadcrumb and pane-label callers pass a zoom title as is.
     */
    fun withoutTags(line: TokenizedLine): String {
        if (line.runs.none { it.isTag }) return line.displayText
        // Display ranges of the tags, in display order.
        val ranges = ArrayList<IntRange>()
        var at = 0
        for (run in line.runs) {
            if (run.isTag) ranges += at until at + run.text.length
            at += run.text.length
        }
        val sb = StringBuilder(line.displayText)
        for (r in ranges.asReversed()) {
            var start = r.first
            var end = r.last + 1
            val spaceAfter = end == sb.length || sb[end] == ' '
            if (start > 0 && sb[start - 1] == ' ' && spaceAfter) start--
            else if (start == 0 && end < sb.length && sb[end] == ' ') end++
            sb.deleteRange(start, end)
        }
        return sb.toString()
    }

    /**
     * Percent-encodes [plain] per the file-level rules and trims it to
     * [MAX_NAME_BYTES]. Trimming drops whole characters from the end of
     * the *plain* text and re-encodes, so a trailing space exposed by the
     * cut is itself encoded and no `%XX` escape is ever split.
     *
     * @return The encoded name; empty only when [plain] is empty.
     */
    fun encode(plain: String): String {
        var text = plain
        var encoded = encodeUncapped(text)
        while (utf8Length(encoded) > MAX_NAME_BYTES && text.isNotEmpty()) {
            // Step back by one code point so surrogate pairs stay whole.
            val cut = if (text.length >= 2 && text[text.length - 1].isLowSurrogate() &&
                text[text.length - 2].isHighSurrogate()
            ) 2 else 1
            text = text.substring(0, text.length - cut)
            encoded = encodeUncapped(text)
        }
        return encoded
    }

    /** Encodes without the byte cap. See [encode]. */
    private fun encodeUncapped(plain: String): String {
        if (plain.isEmpty()) return ""
        // Trailing run of dots/spaces: every char in it is encoded.
        var trailingStart = plain.length
        while (trailingStart > 0 && (plain[trailingStart - 1] == '.' || plain[trailingStart - 1] == ' ')) {
            trailingStart--
        }
        val sb = StringBuilder(plain.length + 8)
        for ((i, ch) in plain.withIndex()) {
            val mustEncode = ch in ALWAYS_ENCODED ||
                ch.code < 0x20 || ch.code == 0x7F ||
                (i == 0 && ch == '.') ||
                i >= trailingStart
            if (mustEncode) appendPercent(sb, ch) else sb.append(ch)
        }
        return sb.toString()
    }

    private fun appendPercent(sb: StringBuilder, ch: Char) {
        // Every encoded character is ASCII, so one byte.
        val hex = "0123456789ABCDEF"
        sb.append('%')
        sb.append(hex[(ch.code shr 4) and 0xF])
        sb.append(hex[ch.code and 0xF])
    }

    /**
     * Reverses [encode]: every `%XX` escape becomes its byte, and the
     * bytes are decoded as UTF-8. A malformed escape (`%` not followed by
     * two hex digits) is kept literally, so hand-made folder names that
     * happen to contain `%` still display sensibly.
     *
     * @param name A folder name as found on disk.
     * @return The title text the name stands for.
     */
    fun decode(name: String): String {
        if ('%' !in name) return name
        val bytes = ArrayList<Byte>(name.length)
        var i = 0
        while (i < name.length) {
            val ch = name[i]
            if (ch == '%' && i + 2 < name.length &&
                hexValue(name[i + 1]) >= 0 && hexValue(name[i + 2]) >= 0
            ) {
                bytes += ((hexValue(name[i + 1]) shl 4) or hexValue(name[i + 2])).toByte()
                i += 3
                continue
            }
            // Re-encode the literal char as UTF-8 bytes.
            val chunkEnd = if (ch.isHighSurrogate() && i + 1 < name.length) i + 2 else i + 1
            for (b in name.substring(i, chunkEnd).encodeToByteArray()) bytes += b
            i = chunkEnd
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'A'..'F' -> c - 'A' + 10
        in 'a'..'f' -> c - 'a' + 10
        else -> -1
    }

    /**
     * Returns [base], or `base (2)`, `base (3)`, … — the first candidate
     * whose lower-cased form is not in [usedLowercase]. Case-insensitive
     * because macOS file systems are. The caller adds the result to its
     * used set.
     *
     * @param base An encoded name from [forTitle].
     * @param usedLowercase Lower-cased names already taken in the target
     *   folder (siblings plus foreign entries on disk).
     */
    fun unique(base: String, usedLowercase: Set<String>): String {
        if (base.lowercase() !in usedLowercase) return base
        var n = 2
        while (true) {
            val candidate = "$base ($n)"
            if (candidate.lowercase() !in usedLowercase) return candidate
            n++
        }
    }

    /**
     * `true` when [name] is [base] itself or [base] with a collision
     * suffix ` (n)`. Used by `NoteRepository.save` to leave an existing
     * `Untitled (2)` alone rather than churning it back to `Untitled`.
     */
    fun isVariantOf(name: String, base: String): Boolean {
        if (name == base) return true
        if (!name.startsWith("$base (") || !name.endsWith(")")) return false
        val digits = name.substring(base.length + 2, name.length - 1)
        return digits.isNotEmpty() && digits.all { it.isDigit() } && digits.toInt() >= 2
    }

    /** UTF-8 byte length of [s] without allocating the bytes. */
    internal fun utf8Length(s: String): Int {
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
