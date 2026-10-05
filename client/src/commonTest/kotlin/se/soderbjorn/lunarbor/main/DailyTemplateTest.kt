/*
 * DailyTemplateTest.kt (commonTest)
 * ---------------------------------
 * The daily template (LBR-21) against the real stack — panes over a
 * [DocumentRegistry] + [NoteRepository] on [InMemoryFileSystem]: choosing
 * the template, a new day starting as a copy of its items (subtrees,
 * blocks, search nodes, links re-based), the copy staying a throwaway
 * until the first edit of the day's own rows (nothing on disk, nothing in
 * `.trash`), shared pending rows across panes, and the template's folder
 * followed through moves, forgotten when trashed, ignored when gone or
 * hidden by the privacy mode.
 */

package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.data.PathMove
import se.soderbjorn.lunarbor.data.PrivacyMode
import se.soderbjorn.lunarbor.main.PaneBackingViewModel.TodayOutcome
import se.soderbjorn.lunarbor.testing.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DailyTemplateTest {

    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })
    private val today = CalendarDate(2026, 10, 5)
    private val dayFolder = "Journal/2026/Week 41/2026-10-05 Monday"

    private suspend fun seed(rel: String, content: String) = fs.writeFile("$root/$rel", content)
    private fun disk(rel: String): String? = fs.read(root, rel)

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

    /** Nothing at all under `.trash`. */
    private fun assertTrashEmpty() {
        assertTrue(fs.tree(root).none { it.startsWith(".trash") }, fs.tree(root).joinToString("\n"))
    }

    /** A vault with a `Templates › Daily` node: a search node, a parent with a child, a block, a link. */
    private suspend fun seedTemplate() {
        seed("_node.md", "- A\n- Templates [↳](<Templates/_node.md>)\n- Recipes [↳](<Recipes/_node.md>)\n")
        seed("Recipes/_node.md", "- soup\n")
        seed("Templates/_node.md", "- Daily [↳](<Daily/_node.md>)\n")
        seed(
            "Templates/Daily/_node.md",
            "- Open tasks {{search: #todo is:open in:/Journal}}\n" +
                "- Meetings [↳](<Meetings/_node.md>)\n" +
                "> **Mood**\n" +
                "- See [recipes](../../Recipes/_node.md)\n",
        )
        seed("Templates/Daily/Meetings/_node.md", "- standup\n")
    }

    /** Makes `Templates › Daily` the template from a pane on its outline. */
    private suspend fun TestScope.useDailyTemplate(registry: DocumentRegistry) {
        val t = pane(registry, "Templates/Daily/_node.md")
        assertTrue(t.canUseAsDailyTemplate())
        t.setDailyTemplate(true)
        runCurrent()
        assertEquals("Templates/Daily", registry.dailyTemplate.folder)
        assertTrue(t.isDailyTemplatePage())
        assertFalse(t.canUseAsDailyTemplate())
    }

    /** The day's rows (after the day item) as prepared under the root outline. */
    private val expectedDayRows = listOf(
        "        * Open tasks {{search: #todo is:open in:/Journal}}",
        "        * Meetings",
        "          * standup",
        "        " + BlockLayout.firstLine(0, "**Mood**"),
        "        * See [recipes](/Recipes/_node.md)",
    )

    @Test
    fun a_new_day_starts_as_a_copy_of_the_template_with_fresh_ids() = runTest {
        seedTemplate()
        val registry = registry()
        useDailyTemplate(registry)
        val p = pane(registry)

        assertEquals(TodayOutcome.OPENED, p.navigateToToday(today))
        runCurrent()
        val lines = p.lines
        val dayRow = lines.indexOf("      * 2026-10-05 Monday")
        assertTrue(dayRow >= 0, lines.joinToString("\n"))
        assertEquals(expectedDayRows, lines.subList(dayRow + 1, dayRow + 1 + expectedDayRows.size))
        val s = p.stateFlow.value
        // Caret on the day's first child; every copied row open.
        assertEquals(dayRow + 1, s.cursorRow)
        val ids = s.documentState!!.lineIds
        val copied = ids.subList(dayRow + 1, dayRow + 1 + expectedDayRows.size)
        assertEquals(copied.size, copied.toSet().size)
        assertTrue(copied.none { it in s.collapsedIds })
        val doc = registry.acquire("_node.md")
        assertTrue(copied.all { doc.isPending(it) })
        registry.release("_node.md")
    }

    @Test
    fun an_existing_day_is_never_changed() = runTest {
        seedTemplate()
        seed("_node.md", "- Journal [↳](<Journal/_node.md>)\n- Templates [↳](<Templates/_node.md>)\n")
        seed("Journal/_node.md", "- 2026 [↳](<2026/_node.md>)\n")
        seed("Journal/2026/_node.md", "- Week 41 [↳](<Week 41/_node.md>)\n")
        seed("Journal/2026/Week 41/_node.md", "- 2026-10-05 Monday\n")
        val registry = registry()
        useDailyTemplate(registry)
        val p = pane(registry)

        p.navigateToToday(today)
        runCurrent()
        assertTrue(p.lines.none { "Open tasks" in it })
        assertEquals("        * ", p.lines[p.stateFlow.value.cursorRow])
    }

    @Test
    fun nothing_is_written_before_the_first_edit_and_leaving_leaves_no_trace() = runTest {
        seedTemplate()
        val registry = registry()
        useDailyTemplate(registry)
        val p = pane(registry)
        val before = fs.tree(root)

        p.navigateToToday(today)
        runCurrent()
        settle(registry)
        assertNull(disk("Journal/_node.md"))
        assertEquals(before, fs.tree(root))

        p.zoomOut()
        runCurrent()
        assertTrue(p.lines.none { "Journal" in it || "Open tasks" in it }, p.lines.joinToString("\n"))
        settle(registry)
        assertEquals(before, fs.tree(root))
        assertTrashEmpty()
    }

    @Test
    fun the_first_edit_makes_the_day_and_everything_copied_real() = runTest {
        seedTemplate()
        val registry = registry()
        useDailyTemplate(registry)
        val p = pane(registry)
        p.navigateToToday(today)
        runCurrent()

        // Type at the end of the first copied row.
        p.moveLineEnd()
        p.insertChar('!')
        runCurrent()
        assertNull(p.stateFlow.value.pendingRowsGroup)
        p.zoomOut()
        runCurrent()
        settle(registry)
        val day = disk("$dayFolder/_node.md")
        assertNotNull(day, fs.tree(root).joinToString("\n"))
        assertEquals(
            "- Open tasks {{search: #todo is:open in:/Journal}}!\n" +
                "- Meetings [↳](<Meetings/_node.md>)\n" +
                "> **Mood**\n" +
                "- See [recipes](../../../../Recipes/_node.md)\n",
            day,
        )
        assertEquals("- standup\n", disk("$dayFolder/Meetings/_node.md"))
        // The template itself is untouched.
        assertEquals("- standup\n", disk("Templates/Daily/Meetings/_node.md"))
        assertTrashEmpty()
    }

    @Test
    fun edits_outside_the_day_do_not_make_it_real() = runTest {
        seedTemplate()
        val registry = registry()
        useDailyTemplate(registry)
        val p = pane(registry)
        p.navigateToToday(today)
        runCurrent()
        val doc = registry.acquire("_node.md")
        val st = doc.stateFlow.value
        val firstCopied = st.lines.indexOf(expectedDayRows.first())
        val aRow = st.lines.indexOf("* A")

        // A line elsewhere in the same document changes — as Toggle done on
        // a search-node result rewrites the line where the task is stored
        // (LBR-22) — with the caret on the day: still a throwaway.
        val ticked = st.lines.toMutableList().also { it[aRow] = "* ~~A~~" }
        assertFalse(doc.commitPendingRowsEditedBetween(st.lines, st.lineIds, ticked, st.lineIds))
        assertTrue(doc.isPending(st.lineIds[firstCopied]))

        // Another pane typing elsewhere in the same document: still a throwaway.
        val other = pane(registry)
        other.moveTo(aRow, 3)
        other.insertChar('x')
        runCurrent()
        assertNotNull(p.stateFlow.value.pendingRowsGroup)
        assertTrue(doc.isPending(st.lineIds[firstCopied]))

        // Toggling done on a row of the day is an edit of the day.
        val done = st.lines.toMutableList().also { it[firstCopied] = "        * ~~Open tasks~~" }
        assertTrue(doc.commitPendingRowsEditedBetween(st.lines, st.lineIds, done, st.lineIds))
        assertFalse(doc.isPending(st.lineIds[firstCopied]))
        registry.release("_node.md")
    }

    @Test
    fun two_panes_share_the_copy_until_the_last_one_leaves() = runTest {
        seedTemplate()
        val registry = registry()
        useDailyTemplate(registry)
        val a = pane(registry)
        val b = pane(registry)

        a.navigateToToday(today)
        runCurrent()
        val size = a.lines.size
        assertEquals(TodayOutcome.OPENED, b.navigateToToday(today))
        runCurrent()
        assertEquals(size, b.lines.size) // joined, not copied twice
        a.zoomOut()
        runCurrent()
        assertEquals(size, b.lines.size)
        b.zoomOut()
        runCurrent()
        assertTrue(a.lines.none { "Open tasks" in it || "Journal" in it })
        settle(registry)
        assertNull(disk("Journal/_node.md"))
        assertTrashEmpty()
    }

    @Test
    fun the_template_follows_moves_and_is_forgotten_when_trashed() = runTest {
        val template = DailyTemplate()
        var changes = 0
        template.onChanged = { changes++ }
        template.set("Templates/Daily")
        template.applyMoves(listOf(PathMove("Templates", "Setup")))
        assertEquals("Setup/Daily", template.folder)
        template.applyMoves(listOf(PathMove("Setup/Daily", "Setup/Day plan")))
        assertEquals("Setup/Day plan", template.folder)
        template.applyMoves(listOf(PathMove("Other", "Else")))
        assertEquals("Setup/Day plan", template.folder)
        // Only its outline trashed (the folder kept for its files): forgotten.
        template.applyMoves(listOf(PathMove("Setup/Day plan/_node.md", ".trash/1 Day plan/_node.md")))
        assertNull(template.folder)
        assertEquals(4, changes)
        template.set("A")
        template.applyMoves(listOf(PathMove("A", ".trash/1 A")))
        assertNull(template.folder)
        // The root is never the template.
        template.set("")
        assertNull(template.folder)
    }

    @Test
    fun a_renamed_template_node_is_followed_and_a_deleted_one_forgotten() = runTest {
        seedTemplate()
        val registry = registry()
        seed("Templates/_node.md", "- Daily [↳](<Daily/_node.md>)\n- Other\n")
        useDailyTemplate(registry)
        val t = pane(registry, "Templates/_node.md")
        assertEquals("* Daily", t.lines[0])

        // Rename the template's bullet: the save renames the folder.
        t.moveTo(0, t.lines[0].length)
        t.insertChar('!')
        runCurrent()
        settle(registry)
        assertEquals("Templates/Daily!", registry.dailyTemplate.folder)

        // Delete it (a multi-row selection takes the whole item): the save
        // trashes the folder, and the template is gone.
        t.moveTo(0, 0)
        t.moveTo(1, t.lines[1].length, extend = true)
        t.backspace()
        runCurrent()
        settle(registry)
        assertNull(registry.dailyTemplate.folder, fs.tree(root).joinToString("\n"))
    }

    @Test
    fun a_template_that_no_longer_exists_means_no_template() = runTest {
        seed("_node.md", "- A\n")
        val registry = registry()
        registry.dailyTemplate.load("Gone/Daily")
        val p = pane(registry)
        p.navigateToToday(today)
        runCurrent()
        assertEquals(
            listOf("* A", "* Journal", "  * 2026", "    * Week 41", "      * 2026-10-05 Monday", "        * "),
            p.lines,
        )
    }

    @Test
    fun a_hidden_template_is_not_applied_and_hidden_items_are_left_out() = runTest {
        seedTemplate()
        seed(
            "Templates/Daily/_node.md",
            "- Open tasks {{search: #todo is:open in:/Journal}}\n- Salary #private\n  * x\n",
        )
        val registry = registry()
        useDailyTemplate(registry)
        val mode = PrivacyMode("m1", "Colleagues", listOf("private"))
        registry.setPrivacyModes(listOf(mode))
        registry.setPrivacyMode(mode.id)
        val p = pane(registry)
        runCurrent()

        // Visible template: its hidden item is not copied.
        p.navigateToToday(today)
        runCurrent()
        assertTrue(p.lines.any { "Open tasks" in it })
        assertTrue(p.lines.none { "Salary" in it || it.trim() == "* x" })
        p.zoomOut()
        runCurrent()

    }

    @Test
    fun a_hidden_template_node_is_not_applied() = runTest {
        seedTemplate()
        seed("Templates/_node.md", "- Daily #private [↳](<Daily/_node.md>)\n")
        val registry = registry()
        registry.dailyTemplate.load("Templates/Daily")
        val mode = PrivacyMode("m1", "Colleagues", listOf("private"))
        registry.setPrivacyModes(listOf(mode))
        registry.setPrivacyMode(mode.id)
        val p = pane(registry)
        runCurrent()

        assertEquals(TodayOutcome.OPENED, p.navigateToToday(today))
        runCurrent()
        assertTrue(p.lines.none { "Open tasks" in it }, p.lines.joinToString("\n"))
        assertEquals("        * ", p.lines[p.stateFlow.value.cursorRow])
    }

    @Test
    fun the_template_is_offered_on_node_pages_only() = runTest {
        seedTemplate()
        val registry = registry()
        val rootPane = pane(registry)
        assertFalse(rootPane.canUseAsDailyTemplate()) // the root
        val idx = rootPane.lines.indexOf("* Templates")
        rootPane.zoomInto(idx)
        runCurrent()
        assertTrue(rootPane.canUseAsDailyTemplate()) // a zoomed node
        rootPane.setDailyTemplate(true)
        runCurrent()
        assertEquals("Templates", registry.dailyTemplate.folder)
        assertTrue(rootPane.isDailyTemplatePage())
        rootPane.setDailyTemplate(false)
        runCurrent()
        assertNull(registry.dailyTemplate.folder)
    }
}
