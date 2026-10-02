/*
 * LinkSource.kt (commonMain)
 * --------------------------
 * Finds the source of the link under a column of a raw line — a Markdown
 * link `[label](url)`, a wiki link `[[Name]]` ([WikiLink]) or a bare
 * `https://…` URL ([bareUrlEndAt]) — so the
 * editor can change or remove a link as a whole (the web view's link
 * popup: "Change link…", "Edit text", "Remove link").
 *
 * commonMain only — no DOM, Android UI, or UIKit imports. Pure.
 */

package se.soderbjorn.lunarbor.data

/**
 * One link's source span on a line.
 *
 * @property start Column of the link's first character (its `[`, or a
 *   bare URL's `h`).
 * @property end Column one past its last (the closing `)` or `]]`, or a
 *   bare URL's end).
 * @property label The text the link shows, as Markdown ready to go
 *   between `[` and `]` (a Markdown link's label as written; a wiki
 *   link's alias or name, escaped; a bare URL itself).
 * @property labelEnd Column right after the label's last character — where
 *   the caret goes to edit the text (before `]` or `]]`; a bare URL's
 *   end).
 * @property isWiki `true` for a `[[…]]` wiki link.
 */
data class LinkSource(
    val start: Int,
    val end: Int,
    val label: String,
    val labelEnd: Int,
    val isWiki: Boolean,
) {
    companion object {
        /**
         * The link on [line] whose span holds [col] (either edge
         * included), or `null`. Images (`![alt](src)`) are not links; a
         * URL inside a bracketed link belongs to that link.
         *
         * Called by `PaneBackingViewModel.retargetLinkAt`,
         * `removeLinkAt` and `editLinkTextAt`.
         */
        fun at(line: String, col: Int): LinkSource? {
            val bracketed = ArrayList<LinkSource>()
            var i = line.indexOf('[')
            while (i >= 0) {
                val found = parseAt(line, i)
                if (found != null) {
                    if (col in found.start..found.end) return found
                    bracketed += found
                    i = line.indexOf('[', found.end)
                } else {
                    i = line.indexOf('[', i + 1)
                }
            }
            var h = line.indexOf("http")
            while (h in 0..col) {
                val end = bareUrlEndAt(line, h)
                if (end != null && bracketed.none { h in it.start until it.end }) {
                    if (col in h..end) return LinkSource(h, end, line.substring(h, end), end, isWiki = false)
                    h = line.indexOf("http", end)
                } else {
                    h = line.indexOf("http", h + 1)
                }
            }
            return null
        }

        private fun parseAt(line: String, i: Int): LinkSource? {
            WikiLink.endAt(line, i)?.let { end ->
                val inner = line.substring(i + 2, end - 2)
                val alias = inner.substringAfter('|', "").trim()
                val shown = alias.ifEmpty { WikiLink.nameOf(inner) }
                return LinkSource(i, end, SubtreeCodec.escapeLabel(shown), end - 2, isWiki = true)
            }
            if (i > 0 && line[i - 1] == '!') return null
            val syntax = parseLinkSyntaxAtTopLevel(line, i) ?: return null
            return LinkSource(i, syntax.closingParen + 1, line.substring(i + 1, syntax.labelEnd), syntax.labelEnd, isWiki = false)
        }
    }
}
