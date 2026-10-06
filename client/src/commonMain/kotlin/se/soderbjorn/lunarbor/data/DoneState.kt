/*
 * DoneState.kt (commonMain)
 * -------------------------
 * The done state of an outline item (LBR-24): an item whose **whole title**
 * is struck through (`~~…~~`, GFM strikethrough) is done, and so is
 * everything under it. No format change — GitHub and Obsidian draw the
 * same line struck through.
 *
 * "Whole title" ignores what sits at either end of it that is not text:
 * whitespace, `#tags` and a search node's `{{search: …}}`. So
 * `~~Buy oat milk~~ #todo` is done, `Buy ~~oat~~ milk` is not. For a
 * block, its first row decides; a code row is never done.
 *
 * Pure helpers on one line's text, used by:
 * - `TextIndex` (the done flag of every indexed line, `is:done` in search),
 * - `DoneLayout` (which rows of an open outline are done: dimmed, hidden
 *   by "Hide done items"),
 * - `PaneBackingViewModel.toggleDone` (wraps / unwraps the title), and
 *   LBR-22's Toggle done on a search hit's stored line.
 *
 * commonMain only — no DOM, Android UI, or UIKit imports.
 */

package se.soderbjorn.lunarbor.data

import se.soderbjorn.lunarbor.main.BlockLayout
import se.soderbjorn.lunarbor.main.DocumentLayout

/**
 * Done detection and the title wrap / unwrap rule.
 *
 * Two levels: `*Text` functions work on an item's title text alone (what
 * follows the bullet marker and any heading prefix); `*Row` functions work
 * on a whole row as `Document.lines` holds it (`<indent>* title`, a block
 * row with its hidden marker, or a plain line).
 */
object DoneState {
    private const val MARK = "~~"

    /**
     * The part of [text] the done rule looks at: [text] with leading and
     * trailing whitespace, `#tags`, `{{search: …}}` queries and
     * `{{lunicle: …}}` references ([LunicleNode]) cut off, as
     * a half-open `[first, last)` column pair. Equal ends when nothing is
     * left (an empty title, or one made only of tags).
     */
    internal fun coreRange(text: String): Pair<Int, Int> {
        var start = 0
        var end = text.length
        while (true) {
            while (start < end && text[start].isWhitespace()) start++
            if (start >= end) break
            val query = SearchNode.rangeIn(text, start)?.takeIf { it.first == start }
                ?: LunicleNode.rangeIn(text, start)
            if (query != null && query.first == start && query.last < end) {
                start = query.last + 1
                continue
            }
            var tokenEnd = start
            while (tokenEnd < end && !text[tokenEnd].isWhitespace()) tokenEnd++
            if (isTagToken(text, start, tokenEnd)) {
                start = tokenEnd
                continue
            }
            break
        }
        while (end > start) {
            while (end > start && text[end - 1].isWhitespace()) end--
            if (end <= start) break
            if (text.startsWith("}}", end - 2)) {
                val openSearch = text.lastIndexOf("{{search:", end - 1)
                val openBoard = text.lastIndexOf("{{lunicle:", end - 1)
                val open = maxOf(openSearch, openBoard)
                val query = when {
                    open < start -> null
                    open == openSearch -> SearchNode.rangeIn(text, open)
                    else -> LunicleNode.rangeIn(text, open)
                }
                if (query != null && query.first == open && query.last == end - 1) {
                    end = open
                    continue
                }
            }
            var tokenStart = end
            while (tokenStart > start && !text[tokenStart - 1].isWhitespace()) tokenStart--
            if (isTagToken(text, tokenStart, end)) {
                end = tokenStart
                continue
            }
            break
        }
        return start to maxOf(start, end)
    }

    /**
     * `true` when `text[from, to)` is one `#tag` as the tokenizer reads it:
     * `#`, a letter, then letters, digits, `_` or `-`.
     */
    private fun isTagToken(text: String, from: Int, to: Int): Boolean {
        if (to - from < 2 || text[from] != '#' || !text[from + 1].isLetter()) return false
        for (i in from + 2 until to) {
            val c = text[i]
            if (!(c.isLetterOrDigit() || c == '_' || c == '-')) return false
        }
        return true
    }

    /**
     * The struck text inside [core] when it is one `~~…~~` span covering
     * all of it — non-blank, no `~~` inside, no space right inside the
     * markers (GFM would not strike it) — else `null`.
     */
    private fun struckInner(core: String): String? {
        if (core.length < 5 || !core.startsWith(MARK) || !core.endsWith(MARK)) return null
        val inner = core.substring(2, core.length - 2)
        if (inner.isBlank() || inner.trim() != inner || MARK in inner || inner.startsWith("~") || inner.endsWith("~")) return null
        return inner
    }

    /**
     * `true` when the title [text] is done: its whole text, apart from
     * tags, search queries and whitespace at either end, is struck through.
     */
    fun isDoneText(text: String): Boolean {
        if (MARK !in text) return false
        val (start, end) = coreRange(text)
        return struckInner(text.substring(start, end)) != null
    }

    /**
     * The title [text] made done ([done] `true`: its whole title wrapped in
     * `~~`, tags and search queries kept outside; strike markers already
     * inside it dropped first, so `Buy ~~oat~~ milk` becomes
     * `~~Buy oat milk~~`) or not done (the outer `~~` removed). Returns
     * [text] unchanged when it already is as asked, or when there is no
     * title to strike (empty, or only tags).
     */
    fun withDoneText(text: String, done: Boolean): String {
        val (start, end) = coreRange(text)
        val core = text.substring(start, end)
        val inner = struckInner(core)
        if ((inner != null) == done) return text
        val replaced = if (done) {
            val plain = core.replace(MARK, "").trim()
            if (plain.isEmpty()) return text
            MARK + plain + MARK
        } else {
            inner!!
        }
        return text.substring(0, start) + replaced + text.substring(end)
    }

    /** [text] toggled: done when it was not, not done when it was. */
    fun toggledText(text: String): String = withDoneText(text, !isDoneText(text))

    /**
     * Column where the inline title of the row [raw] starts — past the
     * indent, bullet marker, block marker, a block row's list prefix and
     * any heading / quote prefix ([DocumentLayout.caretStartCol]) — or
     * `null` for a code row, which is verbatim and never done.
     */
    fun titleStartOf(raw: String): Int? {
        if (BlockLayout.isCodeLine(raw)) return null
        return DocumentLayout.caretStartCol(raw)
    }

    /**
     * `true` when the row [raw] (as `Document.lines` holds it) is done by
     * its own title. For a block, pass its first row.
     */
    fun isDoneRow(raw: String): Boolean {
        if (MARK !in raw) return false
        val start = titleStartOf(raw) ?: return false
        return isDoneText(raw.substring(start))
    }

    /**
     * The row [raw] with its title made done or not ([withDoneText]);
     * everything before the title (indent, markers, prefixes) is kept.
     * Unchanged for a code row.
     */
    fun withDoneRow(raw: String, done: Boolean): String {
        val start = titleStartOf(raw) ?: return raw
        val title = raw.substring(start)
        val next = withDoneText(title, done)
        return if (next == title) raw else raw.substring(0, start) + next
    }

    /** The row [raw] toggled ([withDoneRow] with the opposite of [isDoneRow]). */
    fun toggledRow(raw: String): String = withDoneRow(raw, !isDoneRow(raw))

    /**
     * `true` when the line [raw] of a plain `.md` note is done: its text
     * after a heading / quote prefix and a list marker (`- `, `* `, `+ `,
     * `1. `, `1) `, with an optional task box `[ ]` / `[x]`) is struck
     * through as a whole. Note lines inherit nothing.
     */
    fun isDoneNoteLine(raw: String): Boolean {
        if (MARK !in raw) return false
        var i = LineMarkdownPrefix.detect(raw.trimStart(), 0).markerEnd + (raw.length - raw.trimStart().length)
        while (i < raw.length && raw[i] == ' ') i++
        val listEnd = when {
            i + 1 < raw.length && raw[i] in "-*+" && raw[i + 1] == ' ' -> i + 2
            else -> {
                var j = i
                while (j < raw.length && raw[j].isDigit()) j++
                if (j > i && j + 1 < raw.length && (raw[j] == '.' || raw[j] == ')') && raw[j + 1] == ' ') j + 2 else i
            }
        }
        var t = listEnd
        if (raw.startsWith("[ ] ", t) || raw.startsWith("[x] ", t) || raw.startsWith("[X] ", t)) t += 4
        return isDoneText(raw.substring(t))
    }
}
