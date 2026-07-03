/*
 * LinkUrl.kt
 * ----------
 * Pure codec for the URL part of TreeFacts's user-inserted markdown links.
 *
 * TreeFacts's outline tree has a stable address space: every node (a file
 * root or an inline bullet) has a title, and a node is identified by the
 * `/`-separated sequence of titles from some scope to the node. We persist
 * those addresses inside markdown links as a URL fragment of the form
 *
 *     #treefacts-bullet=<title-path>
 *
 * Encoding the address as a fragment-only URL keeps the link valid
 * CommonMark for other tools (Obsidian, GitHub, VS Code) — they just see
 * an unknown in-page anchor and degrade to "stay on this page", instead
 * of trying and failing to open a file.
 *
 * Why title paths, not file paths? Because bullets in TreeFacts can be
 * promoted to their own file or demoted back to inline at any time. A link
 * pinned to "the file that currently hosts this bullet" would break the
 * moment the user reshapes the tree. A title-path link survives both.
 *
 * No I/O. Side-effect free. commonMain only.
 */

package se.soderbjorn.treefacts.data

/**
 * Parsed form of a TreeFacts link URL.
 *
 * @property segments The `/`-separated title segments, in order. Each
 *   segment is the unencoded title (percent-encoding has been resolved).
 *   The literal segment `..` denotes "walk one level up" before consuming
 *   further segments — standard URL relative-path semantics.
 * @property isAbsolute When `true`, the path was originally introduced by
 *   a leading `/` and is interpreted from the vault root. When `false`,
 *   it is interpreted relative to the *parent* of the bullet currently
 *   containing the cursor (so a single-segment relative URL names a
 *   sibling).
 */
data class LinkUrl(
    val segments: List<String>,
    val isAbsolute: Boolean,
) {
    companion object {

        /**
         * URL-fragment prefix that marks a TreeFacts title-path link. The
         * fragment is invisible to other CommonMark tools — they treat
         * the URL as an unknown in-page anchor and ignore it.
         */
        const val PREFIX: String = "#treefacts-bullet="

        /**
         * Tries to parse [url] as a TreeFacts title-path link. Returns the
         * decoded form on success, or `null` when [url] does not begin
         * with [PREFIX] (i.e. it is some other kind of markdown URL — a
         * file path, an `https://` link, a plain `#section` anchor, …).
         *
         * Empty path bodies (`#treefacts-bullet=` or `#treefacts-bullet=/`)
         * also return `null` — the resolver has nothing to walk.
         */
        fun parse(url: String): LinkUrl? {
            if (!url.startsWith(PREFIX)) return null
            val raw = url.substring(PREFIX.length)
            if (raw.isEmpty()) return null
            val isAbsolute = raw.startsWith("/")
            val body = if (isAbsolute) raw.substring(1) else raw
            if (body.isEmpty()) return null
            val parts = body.split("/")
            // An empty segment (`A//B`) is a malformed URL; treat as not-ours.
            if (parts.any { it.isEmpty() }) return null
            return LinkUrl(
                segments = parts.map(::decode),
                isAbsolute = isAbsolute,
            )
        }

        /**
         * Renders [segments] back into a `#treefacts-bullet=…` URL. When
         * [isAbsolute] is `true`, prepends a leading `/`. Each segment is
         * percent-encoded for `/`, `#`, and `%` so titles containing any
         * of those characters round-trip cleanly.
         *
         * `..` segments are emitted verbatim (they are navigation, not
         * titles).
         */
        fun format(segments: List<String>, isAbsolute: Boolean): String {
            val body = segments.joinToString("/") { seg ->
                if (seg == "..") seg else encode(seg)
            }
            return if (isAbsolute) "$PREFIX/$body" else "$PREFIX$body"
        }

        // ------------------------------------------------------------ encoding

        /**
         * Percent-encodes the structural separators (`/`, `#`) and the
         * encoder's own escape character (`%`) so a title containing any
         * of them survives the round-trip. Other characters — including
         * spaces, parens, non-ASCII — are passed through verbatim.
         *
         * Spaces inside the URL are safe in markdown when the URL is
         * wrapped in `<…>`; `SubtreeCodec.formatLinkUrl` already handles
         * that wrapping at the link-emission layer.
         */
        private fun encode(s: String): String {
            if (s.none { it == '/' || it == '#' || it == '%' }) return s
            val sb = StringBuilder(s.length + 4)
            for (ch in s) {
                when (ch) {
                    '/' -> sb.append("%2F")
                    '#' -> sb.append("%23")
                    '%' -> sb.append("%25")
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }

        /**
         * Inverse of [encode]. Recognises only the three escapes [encode]
         * emits (`%2F`, `%23`, `%25`, case-insensitive); leaves any other
         * `%XX` sequences untouched so a title that *literally* contains
         * a `%` followed by hex digits is not silently mangled.
         */
        private fun decode(s: String): String {
            if ('%' !in s) return s
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                if (ch == '%' && i + 2 < s.length) {
                    val hi = s[i + 1]
                    val lo = s[i + 2]
                    val replaced = when {
                        (hi == '2' && (lo == 'F' || lo == 'f')) -> '/'
                        (hi == '2' && lo == '3') -> '#'
                        (hi == '2' && lo == '5') -> '%'
                        else -> null
                    }
                    if (replaced != null) {
                        sb.append(replaced)
                        i += 3
                        continue
                    }
                }
                sb.append(ch)
                i++
            }
            return sb.toString()
        }
    }
}
