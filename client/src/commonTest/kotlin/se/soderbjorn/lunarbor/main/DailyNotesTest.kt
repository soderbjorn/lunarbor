/*
 * DailyNotesTest.kt (commonTest)
 * ------------------------------
 * Daily notes (LBR-18 / LBR-19): the ISO week and title rules of
 * [DailyNotes], and the Today command ([PaneBackingViewModel.navigateToToday])
 * against the real stack — a [PaneBackingViewModel] over a
 * [DocumentRegistry] + [NoteRepository] on [InMemoryFileSystem]: reuse of
 * an existing `Journal` / year / week, newest-first insertion, and the
 * pending rows that leave nothing behind — on screen or on disk — when
 * the day is left untouched. "Previous day" / "Next day" (LBR-20):
 * recognising a day, stepping over gaps, weeks and years, today as the
 * step after the latest day, and days the privacy mode hides.
 */

package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.data.PrivacyMode
import se.soderbjorn.lunarbor.main.PaneBackingViewModel.TodayOutcome
import se.soderbjorn.lunarbor.testing.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DailyNotesTest {

    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })
    private val today = CalendarDate(2026, 10, 5)

    private suspend fun seed(rel: String, content: String) = fs.writeFile("$root/$rel", content)
    private suspend fun disk(rel: String): String? = fs.readFileIfExists("$root/$rel")

    private fun TestScope.registry() = DocumentRegistry(repo, backgroundScope)

    private suspend fun TestScope.pane(registry: DocumentRegistry, file: String = "_node.md"): PaneBackingViewModel {
        val pane = PaneBackingViewModel(registry, backgroundScope, file)
        pane.stateFlow.first { it.isLoaded }
        runCurrent()
        return pane
    }

    private val PaneBackingViewModel.lines get() = stateFlow.value.lines

    /** Lets every autosave run, then flushes what is left. */
    private suspend fun TestScope.settle(registry: DocumentRegistry) {
        advanceTimeBy(10_000)
        runCurrent()
        registry.flushAll()
        runCurrent()
    }

    // ------------------------------------------------------------ the rules

    @Test
    fun weeks_and_years_follow_iso_8601_around_new_year() {
        assertEquals(IsoWeek(2020, 53), DailyNotes.isoWeekOf(CalendarDate(2020, 12, 31)))
        assertEquals(IsoWeek(2020, 53), DailyNotes.isoWeekOf(CalendarDate(2021, 1, 3)))
        assertEquals(IsoWeek(2021, 1), DailyNotes.isoWeekOf(CalendarDate(2021, 1, 4)))
        assertEquals(IsoWeek(2026, 1), DailyNotes.isoWeekOf(CalendarDate(2025, 12, 29)))
        assertEquals(IsoWeek(2026, 41), DailyNotes.isoWeekOf(today))
        assertEquals(
            listOf("Journal", "2020", "Week 53", "2021-01-03 Sunday"),
            DailyNotes.titlePath(CalendarDate(2021, 1, 3)),
        )
        assertEquals(
            listOf("Journal", "2026", "Week 01", "2025-12-29 Monday"),
            DailyNotes.titlePath(CalendarDate(2025, 12, 29)),
        )
    }

    @Test
    fun titles_are_padded_weeks_and_iso_dates_with_the_weekday() {
        assertEquals("2026", DailyNotes.yearTitle(today))
        assertEquals("Week 41", DailyNotes.weekTitle(today))
        assertEquals("Week 01", DailyNotes.weekTitle(CalendarDate(2026, 1, 1)))
        assertEquals("2026-10-05 Monday", DailyNotes.dayTitle(today))
        assertEquals("2024-02-29 Thursday", DailyNotes.dayTitle(CalendarDate(2024, 2, 29)))
        assertEquals("2026-10-11 Sunday", DailyNotes.dayTitle(CalendarDate(2026, 10, 11)))
    }

    @Test
    fun calendar_dates_round_trip_through_epoch_days() {
        assertEquals(0L, CalendarDate(1970, 1, 1).epochDay)
        assertEquals(4, CalendarDate(1970, 1, 1).isoDayOfWeek) // a Thursday
        for (day in listOf(-800_000L, -1L, 0L, 59L, 11_000L, 20_731L, 2_000_000L)) {
            assertEquals(day, CalendarDate.ofEpochDay(day).epochDay)
        }
        assertEquals(CalendarDate(2025, 1, 1), CalendarDate(2024, 12, 31).plusDays(1))
    }

    @Test
    fun journal_is_matched_by_its_name_text_ignoring_case_and_tags() {
        assertTrue(DailyNotes.matchesTitle("**journal**", "Journal"))
        assertTrue(DailyNotes.matchesTitle("Journal #mine", "Journal"))
        assertEquals(1, DailyNotes.findChild(listOf("* A", "* JOURNAL", "  * x"), -1, "Journal"))
        assertEquals(-1, DailyNotes.findChild(listOf("* A", "  * Journal"), -1, "Journal"))
    }

    // ------------------------------------------------------------ the command

    @Test
    fun a_missing_day_is_prepared_and_never_written_while_untouched() = runTest {
        seed("_node.md", "- A\n")
        val registry = registry()
        val p = pane(registry)

        assertEquals(TodayOutcome.OPENED, p.navigateToToday(today))
        runCurrent()
        assertEquals(
            listOf("* A", "* Journal", "  * 2026", "    * Week 41", "      * 2026-10-05 Monday", "        * "),
            p.lines,
        )
        val s = p.stateFlow.value
        assertEquals(4, s.documentState!!.lineIds.indexOf(s.zoomedLineId))
        assertEquals(5, s.cursorRow)
        assertNotNull(s.pendingRowsGroup)

        // Autosave runs: nothing reaches the disk.
        settle(registry)
        assertEquals("- A\n", disk("_node.md"))
        assertNull(disk("Journal/_node.md"))

        // Already there: nothing happens.
        assertEquals(TodayOutcome.ALREADY_THERE, p.navigateToToday(today))

        // Leaving untouched removes the day, the week, the year and Journal.
        p.zoomOut()
        runCurrent()
        assertEquals(listOf("* A"), p.lines)
        settle(registry)
        assertEquals("- A\n", disk("_node.md"))
        assertNull(disk("Journal/_node.md"))

        // Back does not resurrect it.
        p.zoomBack()
        runCurrent()
        assertEquals(listOf("* A"), p.lines)
    }

    @Test
    fun typing_into_the_day_keeps_the_whole_path() = runTest {
        seed("_node.md", "- A\n")
        val registry = registry()
        val p = pane(registry)
        p.navigateToToday(today)
        runCurrent()

        p.insertChar('x')
        runCurrent()
        assertNull(p.stateFlow.value.pendingRowsGroup)
        p.zoomOut()
        runCurrent()
        settle(registry)
        assertEquals("- A\n- Journal [↳](<Journal/_node.md>)\n", disk("_node.md")?.substringAfterLast("---\n"))
        assertEquals("- 2026 [↳](<2026/_node.md>)\n", disk("Journal/_node.md")?.substringAfterLast("---\n"))
        assertEquals(
            "- x\n",
            disk("Journal/2026/Week 41/2026-10-05 Monday/_node.md")?.substringAfterLast("---\n"),
        )
    }

    @Test
    fun an_existing_journal_year_and_week_are_reused_and_the_day_goes_first() = runTest {
        seed("_node.md", "- journal [↳](<journal/_node.md>)\n- B\n")
        seed("journal/_node.md", "- 2026 [↳](<2026/_node.md>)\n- 2025\n")
        seed("journal/2026/_node.md", "- Week 41 [↳](<Week 41/_node.md>)\n- Week 40\n")
        seed("journal/2026/Week 41/_node.md", "- 2026-10-04 Sunday\n")
        val registry = registry()
        val p = pane(registry)

        assertEquals(TodayOutcome.OPENED, p.navigateToToday(today))
        runCurrent()
        assertEquals(
            listOf(
                "* journal",
                "  * 2026",
                "    * Week 41",
                "      * 2026-10-05 Monday",
                "        * ",
                "      * 2026-10-04 Sunday",
                "    * Week 40",
                "  * 2025",
                "* B",
            ),
            p.lines,
        )
        settle(registry)
        assertEquals("- 2026-10-04 Sunday\n", disk("journal/2026/Week 41/_node.md")?.substringAfterLast("---\n"))

        // Untouched: only the prepared day goes; the existing items stay.
        p.zoomOut()
        runCurrent()
        assertTrue(p.lines.none { "2026-10-05" in it })
        assertTrue("      * 2026-10-04 Sunday" in p.lines)
        settle(registry)
        assertEquals("- 2026-10-04 Sunday\n", disk("journal/2026/Week 41/_node.md")?.substringAfterLast("---\n"))
    }

    @Test
    fun a_new_year_goes_first_and_an_untouched_one_is_removed_with_its_week() = runTest {
        seed("_node.md", "- Journal [↳](<Journal/_node.md>)\n")
        seed("Journal/_node.md", "- 2025 [↳](<2025/_node.md>)\n")
        seed("Journal/2025/_node.md", "- Week 52\n")
        val registry = registry()
        val p = pane(registry)

        p.navigateToToday(today)
        runCurrent()
        assertEquals(
            listOf("* Journal", "  * 2026", "    * Week 41", "      * 2026-10-05 Monday", "        * ", "  * 2025"),
            p.lines.filter { "Week 52" !in it },
        )
        p.zoomOut()
        runCurrent()
        assertTrue(p.lines.none { "2026" in it })
        settle(registry)
        assertEquals("- 2025 [↳](<2025/_node.md>)\n", disk("Journal/_node.md")?.substringAfterLast("---\n"))
        assertNull(disk("Journal/2026/_node.md"))
    }

    @Test
    fun an_existing_empty_day_gets_only_a_throwaway_placeholder() = runTest {
        seed("_node.md", "- Journal [↳](<Journal/_node.md>)\n")
        seed("Journal/_node.md", "- 2026 [↳](<2026/_node.md>)\n")
        seed("Journal/2026/_node.md", "- Week 41 [↳](<Week 41/_node.md>)\n")
        seed("Journal/2026/Week 41/_node.md", "- 2026-10-05 Monday\n")
        val registry = registry()
        val p = pane(registry)

        p.navigateToToday(today)
        runCurrent()
        val s = p.stateFlow.value
        assertEquals("        * ", p.lines[s.cursorRow])
        p.zoomOut()
        runCurrent()
        settle(registry)
        assertEquals("- 2026-10-05 Monday\n", disk("Journal/2026/Week 41/_node.md")?.substringAfterLast("---\n"))
        assertTrue(p.lines.none { it == "        * " })
    }

    @Test
    fun from_another_file_it_opens_the_root_outline_and_back_returns() = runTest {
        seed("_node.md", "- Work [↳](<Work/_node.md>)\n")
        seed("Work/_node.md", "- plan\n")
        val registry = registry()
        val p = pane(registry, "Work/_node.md")

        assertEquals(TodayOutcome.OPENED, p.navigateToToday(today))
        runCurrent()
        assertEquals("_node.md", p.stateFlow.value.activeFileRel)
        assertEquals("      * 2026-10-05 Monday", p.lines[p.lines.indexOfFirst { "2026-10-05" in it }])

        p.zoomBack()
        runCurrent()
        assertEquals("Work/_node.md", p.stateFlow.value.activeFileRel)
        settle(registry)
        assertEquals("- Work [↳](<Work/_node.md>)\n", disk("_node.md")?.substringAfterLast("---\n"))
    }

    @Test
    fun two_panes_share_the_prepared_day_until_the_last_leaves() = runTest {
        seed("_node.md", "- A\n")
        val registry = registry()
        val a = pane(registry)
        val b = pane(registry)

        a.navigateToToday(today)
        runCurrent()
        assertEquals(TodayOutcome.OPENED, b.navigateToToday(today))
        runCurrent()
        assertEquals(6, b.lines.size) // joined, not prepared twice
        assertEquals(b.stateFlow.value.zoomedLineId, a.stateFlow.value.zoomedLineId)

        a.zoomOut()
        runCurrent()
        assertEquals(6, b.lines.size)
        b.zoomOut()
        runCurrent()
        assertEquals(listOf("* A"), a.lines)
    }

    @Test
    fun a_hidden_journal_is_neither_revealed_nor_created() = runTest {
        seed("_node.md", "- A\n- Journal #private\n  * 2026\n")
        val registry = registry()
        val mode = PrivacyMode("m1", "Colleagues", listOf("private"))
        registry.setPrivacyModes(listOf(mode))
        registry.setPrivacyMode(mode.id)
        val p = pane(registry)
        runCurrent()

        assertEquals(TodayOutcome.HIDDEN, p.navigateToToday(today))
        runCurrent()
        assertNull(p.stateFlow.value.zoomedLineId)
        assertTrue(p.lines.none { "Week" in it })
    }

    @Test
    fun the_breadcrumb_up_to_an_untouched_week_lands_on_the_nearest_survivor() = runTest {
        seed("_node.md", "- Journal [↳](<Journal/_node.md>)\n")
        seed("Journal/_node.md", "- 2026 [↳](<2026/_node.md>)\n")
        seed("Journal/2026/_node.md", "- Week 40\n")
        val registry = registry()
        val p = pane(registry)
        p.navigateToToday(today)
        runCurrent()
        val lines = p.lines
        val ids = p.stateFlow.value.documentState!!.lineIds
        val week = ids[lines.indexOf("    * Week 41")]
        val year = ids[lines.indexOf("  * 2026")]

        p.zoomTo(week)
        runCurrent()
        assertEquals(year, p.stateFlow.value.zoomedLineId)
        assertTrue(p.lines.none { "Week 41" in it })
    }

    // ------------------------------------------- Previous day / Next day (LBR-20)

    private fun day(y: Int, m: Int, d: Int) =
        CalendarDate(y, m, d).let { DailyNotes.JournalDay(it, DailyNotes.titlePath(it)) }

    @Test
    fun a_day_is_recognised_by_its_title_and_place() {
        assertEquals(today, DailyNotes.dateOfDayTitle("2026-10-05 Monday"))
        assertEquals(today, DailyNotes.dateOfDayTitle("**2026-10-05 monday** #work"))
        assertNull(DailyNotes.dateOfDayTitle("2026-10-05 Tuesday")) // wrong weekday
        assertNull(DailyNotes.dateOfDayTitle("2026-02-30 Monday"))
        assertNull(DailyNotes.dateOfDayTitle("2026-10-05"))
        assertNull(DailyNotes.dateOfDayTitle("Meeting 2026-10-05 Monday"))
        assertTrue(DailyNotes.isWeekTitle("Week 01"))
        assertTrue(DailyNotes.isWeekTitle("week 7"))
        assertTrue(!DailyNotes.isWeekTitle("Week 54"))
        assertTrue(DailyNotes.isYearTitle("2026"))
        assertTrue(!DailyNotes.isYearTitle("Year 2026"))

        assertEquals(today, DailyNotes.dayOfTitlePath(listOf("Journal", "2026", "Week 41", "2026-10-05 Monday")))
        assertEquals(today, DailyNotes.dayOfTitlePath(listOf("journal", "2026", "Week 41", "2026-10-05 Monday", "Meeting")))
        assertNull(DailyNotes.dayOfTitlePath(listOf("Journal", "2026", "Week 41")))
        assertNull(DailyNotes.dayOfTitlePath(listOf("Work", "2026", "Week 41", "2026-10-05 Monday")))
        assertNull(DailyNotes.dayOfTitlePath(listOf("Journal", "2026", "2026-10-05 Monday", "x")))
    }

    @Test
    fun steps_skip_gaps_and_stop_at_the_ends() {
        val days = listOf(day(2025, 12, 19), day(2025, 12, 29), day(2026, 1, 5), day(2026, 1, 9))
        val from = CalendarDate(2026, 1, 5)
        assertEquals(DailyNotes.DayStep.ToDay(day(2025, 12, 29)), DailyNotes.stepFrom(days, from, false, today))
        assertEquals(DailyNotes.DayStep.ToDay(day(2026, 1, 9)), DailyNotes.stepFrom(days, from, true, today))
        assertNull(DailyNotes.stepFrom(days, CalendarDate(2025, 12, 19), false, today))
        // Next from the latest day before today: today.
        assertEquals(DailyNotes.DayStep.ToToday, DailyNotes.stepFrom(days, CalendarDate(2026, 1, 9), true, today))
        // Next from today with nothing later: nothing.
        assertNull(DailyNotes.stepFrom(days + day(2026, 10, 5), today, true, today))
        // A planned future day after today: today comes first, then the future day.
        val future = days + day(2026, 10, 20)
        assertEquals(DailyNotes.DayStep.ToToday, DailyNotes.stepFrom(future, CalendarDate(2026, 1, 9), true, today))
        assertEquals(DailyNotes.DayStep.ToDay(day(2026, 10, 20)), DailyNotes.stepFrom(future, today, true, today))
    }

    /** The title of the item the pane is zoomed into. */
    private val PaneBackingViewModel.zoomedTitle: String?
        get() {
            val s = stateFlow.value
            val row = s.documentState?.lineIds?.indexOf(s.zoomedLineId ?: return null) ?: return null
            return s.lines.getOrNull(row)?.trim()?.removePrefix("* ")
        }

    @Test
    fun previous_and_next_day_skip_gaps_across_weeks_and_years() = runTest {
        seed("_node.md", "- Journal [↳](<Journal/_node.md>)\n")
        seed("Journal/_node.md", "- 2026 [↳](<2026/_node.md>)\n- 2025 [↳](<2025/_node.md>)\n")
        seed("Journal/2026/_node.md", "- Week 02 [↳](<Week 02/_node.md>)\n- Week 01 [↳](<Week 01/_node.md>)\n")
        seed(
            "Journal/2026/Week 02/_node.md",
            "- 2026-01-09 Friday [↳](<2026-01-09 Friday/_node.md>)\n- 2026-01-05 Monday\n",
        )
        seed("Journal/2026/Week 02/2026-01-09 Friday/_node.md", "- notes\n")
        seed("Journal/2026/Week 01/_node.md", "- 2025-12-29 Monday\n")
        seed("Journal/2025/_node.md", "- Week 51 [↳](<Week 51/_node.md>)\n")
        seed("Journal/2025/Week 51/_node.md", "- 2025-12-19 Friday\n")
        val registry = registry()
        // Inside a day: on its own folder's outline.
        val p = pane(registry, "Journal/2026/Week 02/2026-01-09 Friday/_node.md")
        assertEquals(CalendarDate(2026, 1, 9), p.journalDayOf())

        // Gap within a week (Tue–Thu missing).
        assertEquals(TodayOutcome.OPENED, p.navigateToAdjacentDay(forward = false, today))
        runCurrent()
        assertEquals("_node.md", p.stateFlow.value.activeFileRel)
        assertEquals("2026-01-05 Monday", p.zoomedTitle)
        // Across a week boundary.
        assertEquals(TodayOutcome.OPENED, p.navigateToAdjacentDay(forward = false, today))
        runCurrent()
        assertEquals("2025-12-29 Monday", p.zoomedTitle)
        // Across a year boundary (and a whole missing week).
        assertEquals(TodayOutcome.OPENED, p.navigateToAdjacentDay(forward = false, today))
        runCurrent()
        assertEquals("2025-12-19 Friday", p.zoomedTitle)
        // No previous day: nothing happens.
        assertEquals(TodayOutcome.NO_DAY, p.navigateToAdjacentDay(forward = false, today))
        runCurrent()
        assertEquals("2025-12-19 Friday", p.zoomedTitle)
        // And forward again, across the year.
        assertEquals(TodayOutcome.OPENED, p.navigateToAdjacentDay(forward = true, today))
        runCurrent()
        assertEquals("2025-12-29 Monday", p.zoomedTitle)

        // History: Back walks the days, then back to the day's folder.
        p.zoomBack()
        runCurrent()
        assertEquals("2025-12-19 Friday", p.zoomedTitle)

        // Untouched days got only throwaway placeholders: nothing written.
        p.zoomOut()
        runCurrent()
        settle(registry)
        assertEquals("- 2025-12-29 Monday\n", disk("Journal/2026/Week 01/_node.md")?.substringAfterLast("---\n"))
        assertEquals("- 2025-12-19 Friday\n", disk("Journal/2025/Week 51/_node.md")?.substringAfterLast("---\n"))
    }

    @Test
    fun next_day_from_the_latest_day_goes_to_today() = runTest {
        seed("_node.md", "- Journal [↳](<Journal/_node.md>)\n")
        seed("Journal/_node.md", "- 2026 [↳](<2026/_node.md>)\n")
        seed("Journal/2026/_node.md", "- Week 40 [↳](<Week 40/_node.md>)\n")
        seed("Journal/2026/Week 40/_node.md", "- 2026-10-02 Friday [↳](<2026-10-02 Friday/_node.md>)\n")
        seed("Journal/2026/Week 40/2026-10-02 Friday/_node.md", "- done\n")
        val registry = registry()
        val p = pane(registry, "Journal/2026/Week 40/2026-10-02 Friday/_node.md")

        assertEquals(TodayOutcome.OPENED, p.navigateToAdjacentDay(forward = true, today))
        runCurrent()
        assertEquals("2026-10-05 Monday", p.zoomedTitle)
        assertNotNull(p.stateFlow.value.pendingRowsGroup) // prepared like Today
        assertEquals(today, p.journalDayOf())
        // Today is the latest: nothing after it.
        assertEquals(TodayOutcome.NO_DAY, p.navigateToAdjacentDay(forward = true, today))
        // Previous from the untouched today goes back to Friday; today is dropped.
        assertEquals(TodayOutcome.OPENED, p.navigateToAdjacentDay(forward = false, today))
        runCurrent()
        assertEquals("2026-10-02 Friday", p.zoomedTitle)
        assertTrue(p.lines.none { "Week 41" in it })
        settle(registry)
        assertNull(disk("Journal/2026/Week 41/_node.md"))
    }

    @Test
    fun hidden_days_and_weeks_are_skipped() = runTest {
        seed("_node.md", "- Journal [↳](<Journal/_node.md>)\n")
        seed("Journal/_node.md", "- 2026 [↳](<2026/_node.md>)\n")
        seed(
            "Journal/2026/_node.md",
            "- Week 41 [↳](<Week 41/_node.md>)\n- Week 40 #private [↳](<Week 40/_node.md>)\n- Week 39 [↳](<Week 39/_node.md>)\n",
        )
        seed("Journal/2026/Week 41/_node.md", "- 2026-10-05 Monday [↳](<2026-10-05 Monday/_node.md>)\n")
        seed("Journal/2026/Week 41/2026-10-05 Monday/_node.md", "- x\n")
        seed("Journal/2026/Week 40/_node.md", "- 2026-10-02 Friday\n")
        seed("Journal/2026/Week 39/_node.md", "- 2026-09-25 Friday #private\n- 2026-09-24 Thursday\n")
        val registry = registry()
        val mode = PrivacyMode("m1", "Colleagues", listOf("private"))
        registry.setPrivacyModes(listOf(mode))
        registry.setPrivacyMode(mode.id)
        val p = pane(registry, "Journal/2026/Week 41/2026-10-05 Monday/_node.md")
        runCurrent()

        assertEquals(TodayOutcome.OPENED, p.navigateToAdjacentDay(forward = false, today))
        runCurrent()
        assertEquals("2026-09-24 Thursday", p.zoomedTitle)
        assertEquals(TodayOutcome.NO_DAY, p.navigateToAdjacentDay(forward = false, today))
    }

    @Test
    fun not_on_a_day_nothing_happens() = runTest {
        seed("_node.md", "- Journal [↳](<Journal/_node.md>)\n- A\n")
        seed("Journal/_node.md", "- 2026 [↳](<2026/_node.md>)\n")
        seed("Journal/2026/_node.md", "- Week 41\n")
        val registry = registry()
        val p = pane(registry)
        assertNull(p.journalDayOf())
        assertEquals(TodayOutcome.NO_DAY, p.navigateToAdjacentDay(forward = true, today))
        assertNull(p.stateFlow.value.zoomedLineId)
    }
}
