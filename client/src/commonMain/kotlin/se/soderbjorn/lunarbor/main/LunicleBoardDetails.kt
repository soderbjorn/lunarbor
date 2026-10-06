/* LunicleBoardDetails.kt (commonMain)
 *
 * The pure rules of an unfolded board issue's children (LBR-31): its
 * description, edited as multi-line rows, and its comments. Tested directly
 * and through a pane (`LunicleBoardDetailsTest`).
 *
 *  - **Description** ([LunicleDescription]): the issue's Markdown, one row
 *    per line ([LunicleDescription.linesOf] / [LunicleDescription.textOf]
 *    round-trip exactly). While the caret is in it the lines are pane state
 *    ([LunicleDescriptionEdit], `PaneBackingViewModel.State.lunicleDescriptionEdit`):
 *    Enter adds a line (continuing a `* ` / `- ` / `1.` list item, ending
 *    the list on an empty one), Backspace at a line's start and Delete at
 *    its end join lines, a multi-line paste goes in verbatim, and ↑ on the
 *    first line / ↓ on the last leave it ([LunicleDescription.apply]). A
 *    large description (16+ lines, as a large block) shows its first
 *    [LunicleDescription.PREVIEW_ROWS] rows until the pane expands it.
 *  - **Comments** ([LunicleComments]): oldest first, the body as one inline
 *    row, `author · when` with a relative time ("just now", "5m", "3h",
 *    "2d", "Mon", "4 Oct"); a comment that arrived since the pane last saw
 *    the issue is highlighted briefly ([LunicleComments.arrivals]); a
 *    failed post's text goes back into the "Comment…" input
 *    ([LunicleComments.mergeDraft]).
 *
 * The pane's intents (`PaneBackingViewModel.beginLunicleDescription`,
 * `editLunicleDescription`, `leaveLunicleRow`, `lunicleEnter`) compose these
 * with the board cache's writes (`LunicleBoards.setDescription` /
 * `addComment`); the web view's `LunicleBoardCursor` holds the caret line's
 * text in a real field.
 *
 * commonMain only — no platform imports. */
package se.soderbjorn.lunarbor.main

import se.soderbjorn.lunarbor.lunicle.LunicleBoardKey
import se.soderbjorn.lunarbor.lunicle.LunicleBoardLayout

/**
 * An issue's description being edited in a pane (LBR-31): its lines and
 * which one the caret is on. The caret line's live text is in the view's
 * field; [lines] holds it as of the last line change.
 *
 * @property board The board the issue is on.
 * @property issueId The issue.
 * @property original The description shown when the edit started (an
 *   optimistic one included): leaving sends a change only when the text
 *   differs from it, and a board description that differs from it while
 *   the edit runs is a remote change ([LunicleDescription.changedRemotely]).
 * @property lines The lines, one row each.
 * @property line The caret's line (0-based, within [lines]).
 */
data class LunicleDescriptionEdit(
    val board: LunicleBoardKey,
    val issueId: Long,
    val original: String,
    val lines: List<String>,
    val line: Int,
)

/**
 * Where the caret goes within a description being edited.
 *
 * @property line The line.
 * @property col The column within it.
 * @property text The line's text (what the field starts with).
 */
data class LunicleDescriptionCaret(val line: Int, val col: Int, val text: String)

/** One key on a description line ([LunicleDescription.apply]). */
enum class LunicleDescriptionAction {
    /** Arrow Up: the line above, or out of the description on the first line. */
    UP,

    /** Arrow Down: the line below, or out of the description on the last line. */
    DOWN,

    /** Enter: a new line (continuing a list item). */
    ENTER,

    /** Backspace at the line's start: joins it onto the line above. */
    BACKSPACE,

    /** Delete at the line's end: joins the line below onto it. */
    DELETE,

    /** A multi-line paste: put in verbatim, one row per line. */
    PASTE,
}

/** What a [LunicleDescriptionAction] did. */
sealed interface LunicleDescriptionStep {
    /** The caret moves to [caret] (the lines may have changed). */
    data class Caret(val caret: LunicleDescriptionCaret) : LunicleDescriptionStep

    /** ↑ on the first line: the caret leaves the description upwards. */
    data object LeaveUp : LunicleDescriptionStep

    /** ↓ on the last line: the caret leaves the description downwards. */
    data object LeaveDown : LunicleDescriptionStep

    /** Nothing to do (Backspace on the first line's start, Delete at the last line's end). */
    data object Ignore : LunicleDescriptionStep
}

/**
 * An unfolded issue's description as the view draws it (LBR-31).
 *
 * @property lines Its rows, one per Markdown line — the edit's lines while
 *   this pane edits it ([LunicleDescriptionEdit.lines]).
 * @property caretLine The line drawn raw (the caret's) while this pane
 *   edits it, else `null` (every line rendered).
 * @property editable `true` when it can be edited here: a write token,
 *   Lunicle's `canEdit` and `canComment`.
 * @property hiddenRows Rows past the preview of a large description this
 *   pane has not expanded (0 when it shows whole).
 * @property large `true` for a large description ([LunicleDescription.isLarge]):
 *   it has an expand / collapse control.
 */
data class LunicleDescriptionView(
    val lines: List<String>,
    val caretLine: Int?,
    val editable: Boolean,
    val hiddenRows: Int,
    val large: Boolean,
) {
    /** `true` when there is no text at all: "Add a description" is drawn. */
    val isEmpty: Boolean get() = lines.all { it.isBlank() }

    /** The rows on screen: all of them, or the preview of a large one. */
    val shownLines: List<String> get() = if (hiddenRows > 0) lines.take(lines.size - hiddenRows) else lines
}

/**
 * One comment of an unfolded issue as the view draws it (LBR-31).
 *
 * @property id Its id; a negative local id while its post is under way.
 * @property body Its Markdown as one inline row ([LunicleComments.inlineBody]).
 * @property author Who wrote it.
 * @property agentName The agent that wrote it, if one did.
 * @property createdAt When (epoch ms).
 * @property pending `true` while its post is under way (optimistic).
 * @property arrivedAt When it arrived from Lunicle since the pane last saw
 *   the issue (epoch ms): it is highlighted for [LunicleComments.ARRIVAL_MS]
 *   from then; `null` for none.
 */
data class LunicleCommentView(
    val id: Long,
    val body: String,
    val author: String,
    val agentName: String?,
    val createdAt: Long,
    val pending: Boolean = false,
    val arrivedAt: Long? = null,
)

/** Description lines and their editing rules (see the file header). */
object LunicleDescription {

    /** Rows a large description shows until expanded (as a large block's). */
    const val PREVIEW_ROWS: Int = BlockLayout.PREVIEW_ROWS

    /** Lines from which a description is large (as a large block). */
    const val LARGE_ROWS: Int = BlockLayout.FOLD_MIN_ROWS

    /**
     * [text]'s lines, one row each (`\r\n` and `\r` read as `\n`). An empty
     * description is one empty line. [textOf] gives [text] back.
     */
    fun linesOf(text: String): List<String> = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')

    /** The Markdown text of [lines]: joined with `\n`, nothing added or trimmed. */
    fun textOf(lines: List<String>): String = lines.joinToString("\n")

    /** `true` when [lineCount] lines make a large description. */
    fun isLarge(lineCount: Int): Boolean = lineCount >= LARGE_ROWS

    /** Rows a description of [lineCount] lines hides: past the preview, unless [expanded]. */
    fun hiddenRows(lineCount: Int, expanded: Boolean): Int =
        if (isLarge(lineCount) && !expanded) lineCount - PREVIEW_ROWS else 0

    /** `true` when the caret on [line] sits in the rows a collapsed large description hides. */
    fun isHidden(lineCount: Int, line: Int): Boolean = isLarge(lineCount) && line >= PREVIEW_ROWS

    /**
     * `true` when the description the board now shows ([shown]) is no
     * longer the one an edit started from ([edit]'s original): someone
     * changed it in Lunicle meanwhile. The edit is kept, and leaving it
     * sends it (the user's commit wins).
     */
    fun changedRemotely(edit: LunicleDescriptionEdit, shown: String?): Boolean =
        shown != null && shown != edit.original

    /**
     * One [action] on line [line] of [lines], whose live text is [text] with
     * the selection [start]..[end] (columns in [text]).
     *
     * @param pasted The pasted text, for [LunicleDescriptionAction.PASTE].
     * @return The lines afterwards (with [text] in place) and what happened.
     */
    fun apply(
        lines: List<String>,
        line: Int,
        action: LunicleDescriptionAction,
        text: String,
        start: Int,
        end: Int,
        pasted: String = "",
    ): Pair<List<String>, LunicleDescriptionStep> {
        val at = line.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        val current = lines.toMutableList().also { if (it.isEmpty()) it += text else it[at] = text }
        val s = start.coerceIn(0, text.length)
        val e = end.coerceIn(s, text.length)
        return when (action) {
            LunicleDescriptionAction.UP -> {
                if (at == 0) current to LunicleDescriptionStep.LeaveUp
                else current to caret(current, at - 1, s)
            }
            LunicleDescriptionAction.DOWN -> {
                if (at >= current.size - 1) current to LunicleDescriptionStep.LeaveDown
                else current to caret(current, at + 1, s)
            }
            LunicleDescriptionAction.ENTER -> enter(current, at, text, s, e)
            LunicleDescriptionAction.BACKSPACE -> {
                if (at == 0 || s != 0 || e != 0) return current to LunicleDescriptionStep.Ignore
                val above = current[at - 1]
                current[at - 1] = above + text
                current.removeAt(at)
                current to LunicleDescriptionStep.Caret(LunicleDescriptionCaret(at - 1, above.length, current[at - 1]))
            }
            LunicleDescriptionAction.DELETE -> {
                if (at >= current.size - 1 || s != text.length || e != text.length) return current to LunicleDescriptionStep.Ignore
                current[at] = text + current[at + 1]
                current.removeAt(at + 1)
                current to LunicleDescriptionStep.Caret(LunicleDescriptionCaret(at, text.length, current[at]))
            }
            LunicleDescriptionAction.PASTE -> {
                val parts = linesOf(pasted)
                val before = text.substring(0, s)
                val after = text.substring(e)
                val inserted = if (parts.size == 1) {
                    listOf(before + parts[0] + after)
                } else {
                    listOf(before + parts.first()) + parts.subList(1, parts.size - 1) + (parts.last() + after)
                }
                current.removeAt(at)
                current.addAll(at, inserted)
                val last = at + inserted.size - 1
                val col = if (parts.size == 1) before.length + parts[0].length else parts.last().length
                current to LunicleDescriptionStep.Caret(LunicleDescriptionCaret(last, col, current[last]))
            }
        }
    }

    /** The caret on [line] of [lines] at column [col], clamped to the line. */
    private fun caret(lines: List<String>, line: Int, col: Int): LunicleDescriptionStep =
        LunicleDescriptionStep.Caret(LunicleDescriptionCaret(line, col.coerceAtMost(lines[line].length), lines[line]))

    /** A list item's prefix: indent, marker, one space. */
    private val LIST = Regex("""^(\s*)([*+-]|(\d+)([.)]))\s""")

    /**
     * Enter on line [at] (live text [text], selection [s]..[e]): the text
     * after the selection goes to a new line below. On a list item the new
     * line continues the list (a number counts up); Enter on an item with
     * nothing after its marker ends the list instead (the marker goes).
     */
    private fun enter(lines: MutableList<String>, at: Int, text: String, s: Int, e: Int): Pair<List<String>, LunicleDescriptionStep> {
        val before = text.substring(0, s)
        val after = text.substring(e)
        val list = LIST.find(text)
        if (list != null && s >= list.range.last + 1) {
            val prefixEnd = list.range.last + 1
            if (text.substring(prefixEnd).isBlank()) {
                // Enter on an empty item: the list ends here.
                lines[at] = ""
                return lines to LunicleDescriptionStep.Caret(LunicleDescriptionCaret(at, 0, ""))
            }
            val number = list.groupValues[3].toIntOrNull()
            val marker = if (number != null) "${number + 1}${list.groupValues[4]}" else list.groupValues[2]
            val prefix = list.groupValues[1] + marker + " "
            lines[at] = before
            lines.add(at + 1, prefix + after)
            return lines to LunicleDescriptionStep.Caret(LunicleDescriptionCaret(at + 1, prefix.length, lines[at + 1]))
        }
        lines[at] = before
        lines.add(at + 1, after)
        return lines to LunicleDescriptionStep.Caret(LunicleDescriptionCaret(at + 1, 0, after))
    }
}

/** Comment rows: order, text, relative times, arrivals (see the file header). */
object LunicleComments {

    /** How long a comment that arrived from Lunicle stays highlighted. */
    const val ARRIVAL_MS: Long = 4_000

    /** The "Comment…" row's placeholder. */
    const val PLACEHOLDER: String = "Comment…"

    /** [comments] oldest first (by `createdAt`; the order Lunicle sent kept within one). */
    fun <T> ordered(comments: List<T>, createdAt: (T) -> Long): List<T> = comments.sortedBy(createdAt)

    /** A comment's Markdown as one inline row: its lines trimmed, blank ones dropped, joined by spaces. */
    fun inlineBody(body: String): String =
        LunicleDescription.linesOf(body).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    /**
     * "just now" (under a minute), "5m", "3h", "1d" / "2d", then the
     * weekday ("Mon") within a week, then the date ("4 Oct", with the year
     * when it is not this year's) — in the user's local time.
     *
     * @param at When the comment was written (epoch ms).
     * @param now The clock (epoch ms).
     * @param utcOffsetMinutes The user's time zone offset (minutes east of UTC).
     */
    fun whenText(at: Long, now: Long, utcOffsetMinutes: Int = 0): String {
        val s = ((now - at) / 1000).coerceAtLeast(0)
        if (s < 60) return "just now"
        if (s < 3600) return "${s / 60}m"
        if (s < 86_400) return "${s / 3600}h"
        val offset = utcOffsetMinutes * 60_000L
        val day = (at + offset).floorDiv(86_400_000L)
        val today = (now + offset).floorDiv(86_400_000L)
        val days = (today - day).coerceAtLeast(1)
        if (days < 3) return "${days}d"
        if (days < 7) return WEEKDAYS[((day + 4) % 7).toInt().let { if (it < 0) it + 7 else it }]
        val (y, m, d) = civil(day)
        val (thisYear, _, _) = civil(today)
        return if (y == thisYear) "$d ${MONTHS[m - 1]}" else "$d ${MONTHS[m - 1]} $y"
    }

    /** `author · when` (the agent in brackets when one wrote it), dimmed after a comment's text. */
    fun metaText(author: String, agentName: String?, at: Long, now: Long, utcOffsetMinutes: Int = 0): String {
        val who = agentName?.let { "$author ($it)" } ?: author
        return "$who · ${whenText(at, now, utcOffsetMinutes)}"
    }

    /**
     * The "Comment…" input's text after a failed post: the [restored] text
     * first, then whatever was [typed] since.
     */
    fun mergeDraft(restored: String, typed: String): String = when {
        typed.isBlank() -> restored
        restored.isBlank() -> typed
        else -> "$restored $typed"
    }

    /**
     * The comments of an issue that arrived since the pane last saw it:
     * ids in [ids] that are not in [seen] and not this app's own ([own]).
     * Nothing when the pane never saw the issue's comments ([seen] `null`),
     * nor for posts still under way (negative ids).
     */
    fun arrivals(seen: Set<Long>?, ids: List<Long>, own: Set<Long>): Set<Long> =
        if (seen == null) emptySet() else ids.filterTo(LinkedHashSet()) { it > 0 && it !in seen && it !in own }

    /** Days since the epoch → (year, month 1–12, day). */
    private fun civil(days: Long): Triple<Long, Int, Int> {
        val (y, m, d) = LunicleBoardLayout.dateText(days * 86_400_000L).split('-').map { it.toLong() }
        return Triple(y, m.toInt(), d.toInt())
    }

    private val WEEKDAYS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
}
