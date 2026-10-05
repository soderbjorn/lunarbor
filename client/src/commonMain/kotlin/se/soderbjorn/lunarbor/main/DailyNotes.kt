/*
 * DailyNotes.kt
 * -------------
 * Pure rules behind daily notes (LBR-18): where today's node lives and
 * how its items are titled, plus the row arithmetic the "Today" command
 * ([PaneBackingViewModel.navigateToToday]) uses to find or prepare that
 * path in the root outline.
 *
 * The path is `/Journal/<year>/Week <NN>/<YYYY-MM-DD Weekday>`, all
 * ordinary nodes:
 *  - `Journal` — a root item, matched by its name text ([FolderName.nameTextOf],
 *    case-insensitive), so `Journal #private` still counts;
 *  - `<year>` — the ISO 8601 week-numbering year, so a week is never split
 *    across two year nodes (2025-12-29 is 2026 › Week 01);
 *  - `Week <NN>` — the ISO week, zero-padded so it sorts in Finder;
 *  - `<YYYY-MM-DD Weekday>` — the ISO date plus the full English weekday.
 *
 * Dates are a small [CalendarDate] value rather than a library type: the
 * project has no date-time dependency and the maths needed is a few lines.
 * Every function takes the date as a parameter, so "today" (the user's
 * local date) is decided by the platform layer and the rules are testable.
 *
 * "Previous day" / "Next day" (LBR-20) recognise a day by its place and
 * title ([dayOfTitlePath], [dateOfDayTitle]) and step between the days
 * that exist ([stepFrom]).
 *
 * commonMain only — no DOM or platform imports.
 */

package se.soderbjorn.lunarbor.main

import se.soderbjorn.lunarbor.data.FolderName
import se.soderbjorn.lunarbor.data.SubtreeCodec

/**
 * A date on the proleptic Gregorian calendar, without time or zone.
 *
 * Created by the web layer from the local clock (`new Date()`'s local
 * year, month and day) and by tests.
 *
 * @property year The calendar year, e.g. `2026`.
 * @property month `1`..`12`.
 * @property day `1`..the month's length; checked on construction.
 */
data class CalendarDate(val year: Int, val month: Int, val day: Int) {
    init {
        require(month in 1..12) { "month out of range: $month" }
        require(day in 1..daysInMonth(year, month)) { "day out of range: $year-$month-$day" }
    }

    /** Days since 1970-01-01 (negative before it). */
    val epochDay: Long
        get() {
            // Howard Hinnant's days_from_civil.
            val y = (if (month <= 2) year - 1 else year).toLong()
            val era = DateMath.floorDiv(y, 400L)
            val yoe = y - era * 400
            val mp = (month + 9) % 12
            val doy = (153 * mp + 2) / 5 + day - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146097 + doe - 719468
        }

    /** ISO day of the week: `1` = Monday … `7` = Sunday. */
    val isoDayOfWeek: Int
        get() = (DateMath.floorMod(epochDay + 3, 7L) + 1).toInt()

    /** 1-based day of the year. */
    val dayOfYear: Int get() = (epochDay - CalendarDate(year, 1, 1).epochDay).toInt() + 1

    /** This date moved by [days] (negative goes back). */
    fun plusDays(days: Long): CalendarDate = ofEpochDay(epochDay + days)

    /** `YYYY-MM-DD`, e.g. `2026-10-05`. */
    override fun toString(): String =
        "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"

    companion object {
        /** The date [epochDay] days after 1970-01-01. Inverse of [CalendarDate.epochDay]. */
        fun ofEpochDay(epochDay: Long): CalendarDate {
            // Howard Hinnant's civil_from_days.
            val z = epochDay + 719468
            val era = DateMath.floorDiv(z, 146097L)
            val doe = z - era * 146097
            val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
            val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
            val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
            return CalendarDate(y, m, d)
        }

        /** Days in [month] of [year], leap years included. */
        fun daysInMonth(year: Int, month: Int): Int = when (month) {
            2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
    }
}

/** `DateMath.floorDiv` / `floorMod` for every target (commonMain has no `java.lang.Math`). */
private object DateMath {
    fun floorDiv(a: Long, b: Long): Long {
        val q = a / b
        return if ((a % b != 0L) && ((a < 0) != (b < 0))) q - 1 else q
    }

    fun floorMod(a: Long, b: Long): Long = a - floorDiv(a, b) * b
}

/**
 * An ISO 8601 week: the week-numbering year and the week in it.
 *
 * @property weekYear The year the week's Thursday falls in — not always
 *   the calendar year of the date (2021-01-03 is in 2020's week 53).
 * @property week `1`..`53`.
 */
data class IsoWeek(val weekYear: Int, val week: Int)

/**
 * The daily-notes rules. See the file header.
 *
 * ### Callers
 * - [PaneBackingViewModel.navigateToToday] — titles of today's path, the
 *   matching of existing items and where missing ones go.
 * - [PaneBackingViewModel.navigateToAdjacentDay] — which day a pane is on
 *   and which day comes before or after it.
 * - Tests (`DailyNotesTest`).
 */
object DailyNotes {

    /** Title of the root item that holds the journal. */
    const val JOURNAL_TITLE: String = "Journal"

    private val WEEKDAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    /** The ISO week [date] belongs to (weeks start on Monday; week 1 holds the year's first Thursday). */
    fun isoWeekOf(date: CalendarDate): IsoWeek {
        val thursday = date.plusDays((4 - date.isoDayOfWeek).toLong())
        return IsoWeek(thursday.year, (thursday.dayOfYear - 1) / 7 + 1)
    }

    /** Title of [date]'s year item: its ISO week-numbering year, e.g. `2026`. */
    fun yearTitle(date: CalendarDate): String = isoWeekOf(date).weekYear.toString()

    /** Title of [date]'s week item, e.g. `Week 01`, `Week 41`. */
    fun weekTitle(date: CalendarDate): String = "Week " + isoWeekOf(date).week.toString().padStart(2, '0')

    /** Title of [date]'s day item, e.g. `2026-10-05 Monday`. */
    fun dayTitle(date: CalendarDate): String = "$date ${WEEKDAYS[date.isoDayOfWeek - 1]}"

    /**
     * Titles from the root down to [date]'s day item:
     * `[Journal, 2026, Week 41, 2026-10-05 Monday]`.
     */
    fun titlePath(date: CalendarDate): List<String> =
        listOf(JOURNAL_TITLE, yearTitle(date), weekTitle(date), dayTitle(date))

    /**
     * `true` when an item titled [itemTitle] (its raw title, inline
     * Markdown and `#tags` included) is the path item [wanted]: the same
     * name text ([FolderName.nameTextOf]), ignoring case and surrounding
     * spaces.
     */
    fun matchesTitle(itemTitle: String, wanted: String): Boolean =
        FolderName.nameTextOf(itemTitle).trim().equals(wanted.trim(), ignoreCase = true)

    /**
     * Rows of the direct child items of [parentRow] in [lines] (the
     * document's items at the top level when [parentRow] is `-1`), in
     * order. A block counts as one item, at its first row.
     */
    fun childItemRows(lines: List<String>, parentRow: Int): List<Int> {
        val start: Int
        val end: Int
        if (parentRow < 0) {
            start = 0
            end = lines.lastIndex
        } else {
            val col = DocumentLayout.itemColumn(lines, parentRow)
            if (col < 0) return emptyList()
            start = DocumentLayout.itemLastRow(lines, parentRow) + 1
            end = DocumentLayout.subtreeEnd(lines, parentRow, col)
        }
        val out = ArrayList<Int>()
        var childCol = -1
        var r = start
        while (r <= end) {
            val col = DocumentLayout.itemColumn(lines, r)
            if (col >= 0 && (childCol < 0 || col < childCol)) {
                // The shallowest item column under the parent is the child level.
                childCol = col
                out.clear()
            }
            if (col >= 0 && col == childCol) out += r
            r = if (col >= 0) DocumentLayout.itemLastRow(lines, r) + 1 else r + 1
        }
        return out
    }

    /**
     * The first direct child item of [parentRow] (`-1`: the top level)
     * whose title [matchesTitle] [title], or `-1`.
     */
    fun findChild(lines: List<String>, parentRow: Int, title: String): Int =
        childItemRows(lines, parentRow).firstOrNull { matchesTitle(SubtreeCodec.itemTitleOf(lines, it), title) } ?: -1

    /**
     * Where a new first child of [parentRow] goes — newest first: right
     * after the parent's own rows.
     */
    fun firstChildRow(lines: List<String>, parentRow: Int): Int = DocumentLayout.itemLastRow(lines, parentRow) + 1

    /**
     * Where a new last top-level item goes (`Journal` when the root has
     * none): after the document's last row, but before a trailing run of
     * empty top-level bullets (an empty outline is one empty bullet), so
     * the new item never sits under an empty one.
     */
    fun lastTopLevelRow(lines: List<String>): Int {
        var at = lines.size
        while (at > 0 && DocumentLayout.isEmptyBulletLine(lines[at - 1]) &&
            DocumentLayout.indentOf(lines[at - 1]) == 0
        ) at--
        return at
    }

    // ------------------------------------------------- recognising a day (LBR-20)

    private val DAY_TITLE = Regex("""^(\d{4})-(\d{2})-(\d{2}) ([A-Za-z]+)$""")
    private val YEAR_TITLE = Regex("""^\d{4}$""")
    private val WEEK_TITLE = Regex("""^week (\d{1,2})$""", RegexOption.IGNORE_CASE)

    /**
     * The date a day item titled [title] stands for, or `null` when the
     * title is not `YYYY-MM-DD Weekday` ([dayTitle]'s shape): a real
     * date, with its own weekday (any case). Compared on the name text
     * ([FolderName.nameTextOf]), so inline Markdown and `#tags` on the
     * day do not stop it from counting.
     */
    fun dateOfDayTitle(title: String): CalendarDate? {
        val m = DAY_TITLE.matchEntire(FolderName.nameTextOf(title).trim()) ?: return null
        val (y, mo, d, weekday) = m.destructured
        val month = mo.toInt()
        val day = d.toInt()
        if (month !in 1..12 || day !in 1..CalendarDate.daysInMonth(y.toInt(), month)) return null
        val date = CalendarDate(y.toInt(), month, day)
        return date.takeIf { WEEKDAYS[it.isoDayOfWeek - 1].equals(weekday, ignoreCase = true) }
    }

    /** `true` when [title] names a year item: four digits (name text, as [dateOfDayTitle]). */
    fun isYearTitle(title: String): Boolean = YEAR_TITLE.matches(FolderName.nameTextOf(title).trim())

    /** `true` when [title] names a week item: `Week 1` … `Week 53` (`Week 01` as written; any case). */
    fun isWeekTitle(title: String): Boolean {
        val m = WEEK_TITLE.matchEntire(FolderName.nameTextOf(title).trim()) ?: return false
        return m.groupValues[1].toInt() in 1..53
    }

    /**
     * The day a pane is on, from its location's titles from the root down
     * ([titles]: the folder names, then the zoom path): `Journal` › a
     * year › a `Week NN` › a day title ([dateOfDayTitle]), and anything
     * under the day (the pane is "inside" it). `null` anywhere else.
     *
     * Called by `PaneBackingViewModel.journalDayOf`.
     */
    fun dayOfTitlePath(titles: List<String>): CalendarDate? {
        if (titles.size < 4) return null
        if (!matchesTitle(titles[0], JOURNAL_TITLE) || !isYearTitle(titles[1]) || !isWeekTitle(titles[2])) return null
        return dateOfDayTitle(titles[3])
    }

    /**
     * A day item that exists in the journal.
     *
     * @property date The date its title names.
     * @property titlePath Its item titles from the root down, as written
     *   (`Journal`, year, week, day) — where it actually is, so a day filed
     *   under an unexpected week is still found.
     */
    data class JournalDay(val date: CalendarDate, val titlePath: List<String>)

    /** Where "Previous day" / "Next day" go ([stepFrom]). */
    sealed class DayStep {
        /** To the existing [day]. */
        data class ToDay(val day: JournalDay) : DayStep()

        /** To today, through the Today command (prepared if missing). */
        object ToToday : DayStep()
    }

    /**
     * Where "Previous day" ([forward] `false`) or "Next day" ([forward]
     * `true`) goes from the day [from]: the nearest day in [days] before
     * or after it, skipping gaps of any length (weeks and years included).
     * Going forward, today counts as a day even when it does not exist yet
     * — the Today command prepares it — whenever it lies after [from] and
     * no existing day comes before it; so "Next day" from the latest day,
     * when that is before today, goes to today. `null` when there is
     * nowhere to go.
     *
     * @param days The journal's existing, visible days, any order.
     * @param today The user's local date.
     */
    fun stepFrom(days: Collection<JournalDay>, from: CalendarDate, forward: Boolean, today: CalendarDate): DayStep? {
        val key = from.epochDay
        if (!forward) {
            return days.filter { it.date.epochDay < key }.maxByOrNull { it.date.epochDay }?.let { DayStep.ToDay(it) }
        }
        val next = days.filter { it.date.epochDay > key }.minByOrNull { it.date.epochDay }
        val t = today.epochDay
        return when {
            next != null && next.date.epochDay <= t -> DayStep.ToDay(next)
            t > key -> DayStep.ToToday
            else -> next?.let { DayStep.ToDay(it) }
        }
    }

    /**
     * The rows to insert for the missing path items [titles] (outermost
     * first), each nested under the one before, the first at [indent],
     * followed by the new day's children under the last: the daily
     * template's rows ([templateRows], LBR-21) when there are any, else an
     * empty placeholder bullet — the line the caret lands on, as in a leaf
     * zoom.
     *
     * @param templateRows The template's rows with its own items at column
     *   0 ([DocumentRegistry.dailyTemplateRows]), indented here under the
     *   last title; `null` or empty for the placeholder. Ignored when
     *   [titles] is empty (an existing item gets only the placeholder).
     */
    fun preparedRows(titles: List<String>, indent: Int, templateRows: List<String>? = null): List<String> {
        val out = ArrayList<String>(titles.size + 1 + (templateRows?.size ?: 0))
        for ((i, title) in titles.withIndex()) out += " ".repeat(indent + i * PaneBackingViewModel.TAB_SIZE) + "* " + title
        val childPad = " ".repeat(indent + titles.size * PaneBackingViewModel.TAB_SIZE)
        if (titles.isNotEmpty() && !templateRows.isNullOrEmpty()) {
            for (row in templateRows) out += childPad + row
        } else {
            out += "$childPad* "
        }
        return out
    }
}
