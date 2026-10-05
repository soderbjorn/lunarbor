/*
 * HitDoneToggleTest.kt (commonTest)
 * ---------------------------------
 * Toggle done on search result rows (LBR-22) against the real stack — a
 * [PaneBackingViewModel] over a [DocumentRegistry] + [NoteRepository] on
 * [InMemoryFileSystem]: a hit in another file, in a folder-backed item's
 * folder (folded, and expanded in the pane's own document), a hit done
 * only by inheritance, the toast's Undo, the `is:open` lists (pane search
 * and search node) shrinking, the lines it is not offered on, and a
 * prepared day (LBR-21) staying a throwaway when a toggle edits another
 * file's line — or a line of the very document holding the day.
 */

package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.data.SearchQuery
import se.soderbjorn.lunarbor.data.TextHit
import se.soderbjorn.lunarbor.data.TextScope
import se.soderbjorn.lunarbor.testing.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class HitDoneToggleTest {

    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })

    private suspend fun seed(rel: String, content: String) = fs.writeFile("$root/$rel", content)
    private fun disk(rel: String): String? = fs.read(root, rel)

    private suspend fun TestScope.pane(registry: DocumentRegistry, file: String = "_node.md"): PaneBackingViewModel {
        val pane = PaneBackingViewModel(registry, backgroundScope, file)
        pane.stateFlow.first { it.isLoaded }
        runCurrent()
        return pane
    }

    private val PaneBackingViewModel.lines get() = stateFlow.value.lines

    private suspend fun DocumentRegistry.hits(query: String, under: String = ""): List<TextHit> =
        searchText(TextScope.Tree(under), SearchQuery.parse(query).expr).hits

    private suspend fun DocumentRegistry.hit(text: String): TextHit =
        hits(text).single { it.text == text }

    /** Runs [PaneBackingViewModel.toggleDoneOnHit] to completion. */
    private suspend fun TestScope.toggle(p: PaneBackingViewModel, hit: TextHit) {
        p.toggleDoneOnHit(hit).join()
        runCurrent()
    }

    /** A root with a folded project holding tasks, a code block and a note. */
    private suspend fun seedVault() {
        seed("_node.md", "- Inbox #todo\n- Project [↳](<Project/_node.md>)\n")
        seed(
            "Project/_node.md",
            "- Write report #todo #work\n- Call Bob #todo\n> ```\n> code #todo\n> ```\n",
        )
        seed("Project/Note.md", "- note task #todo\n")
    }

    @Test
    fun a_hit_in_another_file_is_toggled_where_it_is_stored_with_its_tags_kept() = runTest {
        seedVault()
        val registry = DocumentRegistry(repo, backgroundScope)
        val p = pane(registry)
        val hit = registry.hit("Write report #todo #work")
        assertTrue(hit.canToggleDone)
        assertFalse(hit.done)

        toggle(p, hit)
        assertEquals(
            "- ~~Write report~~ #todo #work\n- Call Bob #todo\n> ```\n> code #todo\n> ```\n",
            disk("Project/_node.md"),
        )
        // The pane's own page is untouched, and its undo stack too.
        assertEquals(listOf("* Inbox #todo", "* Project"), p.lines)
        assertFalse(p.canUndo())
        val toast = assertNotNull(p.stateFlow.value.hitDoneToast)
        assertTrue(toast.toggle.done)
        // The index knows at once: the line is done now.
        assertTrue(registry.hit("Write report #todo #work").done)

        // Toggling again unstrikes it.
        toggle(p, registry.hit("Write report #todo #work"))
        assertEquals("- Write report #todo #work\n", disk("Project/_node.md")!!.lines().first() + "\n")
        assertFalse(p.stateFlow.value.hitDoneToast!!.toggle.done)
    }

    @Test
    fun a_hit_in_a_folder_expanded_in_the_pane_is_edited_in_the_open_document() = runTest {
        seedVault()
        val registry = DocumentRegistry(repo, backgroundScope)
        val p = pane(registry)
        // Unfold the project: its items are spliced into the root's document.
        p.toggleCollapse(p.stateFlow.value.documentState!!.lineIds[1])
        p.stateFlow.first { s -> s.lines.any { "Call Bob" in it } }
        runCurrent()
        val row = p.lines.indexOf("  * Call Bob #todo")
        assertTrue(row > 0, p.lines.joinToString("\n"))

        toggle(p, registry.hit("Call Bob #todo"))
        // The pane sees it at once, in its own rows.
        assertEquals("  * ~~Call Bob~~ #todo", p.lines[row])
        assertTrue(disk("Project/_node.md")!!.contains("- ~~Call Bob~~ #todo\n"))
        assertFalse(p.canUndo())
        // Edited in place, not reloaded from disk (which would drop a
        // prepared day's pending rows).
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(0, p.stateFlow.value.documentState!!.reloadCount)
    }

    @Test
    fun a_hit_done_only_by_inheritance_strikes_its_own_title() = runTest {
        seed("_node.md", "- ~~Project~~ [↳](<Project/_node.md>)\n")
        seed("Project/_node.md", "- step #todo\n")
        val registry = DocumentRegistry(repo, backgroundScope)
        val p = pane(registry)
        val hit = registry.hit("step #todo")
        assertTrue(hit.done)

        toggle(p, hit)
        assertEquals("- ~~step~~ #todo\n", disk("Project/_node.md"))
        // The parent is left alone.
        assertEquals("- ~~Project~~ [↳](<Project/_node.md>)\n", disk("_node.md"))
        assertTrue(p.stateFlow.value.hitDoneToast!!.toggle.done)
    }

    @Test
    fun undo_from_the_toast_puts_the_line_back() = runTest {
        seedVault()
        val registry = DocumentRegistry(repo, backgroundScope)
        val p = pane(registry)
        val before = disk("Project/_node.md")
        toggle(p, registry.hit("Call Bob #todo"))
        assertTrue(disk("Project/_node.md")!!.contains("~~Call Bob~~"))

        p.undoHitDoneToggle().join()
        runCurrent()
        assertEquals(before, disk("Project/_node.md"))
        assertNull(p.stateFlow.value.hitDoneToast)
        assertFalse(registry.hit("Call Bob #todo").done)
        // A second Undo has nothing to undo.
        p.undoHitDoneToggle().join()
        assertEquals(before, disk("Project/_node.md"))
    }

    @Test
    fun an_is_open_list_shrinks_after_the_toggle() = runTest {
        seedVault()
        val registry = DocumentRegistry(repo, backgroundScope)
        val p = pane(registry)
        p.setSearchQuery("#todo is:open")
        advanceTimeBy(500)
        runCurrent()
        val shown = p.stateFlow.value.searchHits.map { it.text }
        assertTrue("Call Bob #todo" in shown, shown.toString())
        // A search node over the same tree.
        val key = DocumentRegistry.SearchNodeKey(TextScope.Tree(""), "#todo is:open")
        registry.requestSearchNode(key)
        runCurrent()
        val nodeBefore = registry.searchNodeResultsFlow.value[key]!!.total

        toggle(p, p.stateFlow.value.searchHits.single { it.text == "Call Bob #todo" })
        advanceTimeBy(500)
        runCurrent()
        val after = p.stateFlow.value.searchHits.map { it.text }
        assertEquals(shown - "Call Bob #todo", after)
        // The search node re-ran at once, not only after its usual pause.
        assertEquals(nodeBefore - 1, registry.searchNodeResultsFlow.value[key]!!.total)
    }

    @Test
    fun note_lines_and_code_rows_are_not_offered() = runTest {
        seedVault()
        val registry = DocumentRegistry(repo, backgroundScope)
        val p = pane(registry)
        val note = registry.hit("- note task #todo")
        assertFalse(note.canToggleDone)
        val code = registry.hits("code").single()
        assertFalse(code.canToggleDone)
        val before = fs.tree(root).associateWith { disk(it) }
        toggle(p, note)
        toggle(p, code)
        // Even asked directly, the registry refuses them.
        assertNull(registry.toggleDoneOnHit(note.copy(canToggleDone = true)))
        assertNull(registry.toggleDoneOnHit(code.copy(canToggleDone = true)))
        assertEquals(before, fs.tree(root).associateWith { disk(it) })
        assertNull(p.stateFlow.value.hitDoneToast)
    }

    @Test
    fun a_row_of_the_pane_document_is_found_past_pending_rows() = runTest {
        // Today prepares a day in the root's document (pending rows at its
        // top level) before the root's own items; a hit in the root's file
        // counts only the items on disk.
        seed("_node.md", "- Inbox #todo\n- Later #todo\n")
        val registry = DocumentRegistry(repo, backgroundScope)
        val p = pane(registry)
        p.navigateToToday(CalendarDate(2026, 10, 5))
        runCurrent()
        val doc = registry.acquire("_node.md")
        assertTrue(doc.hasPendingRows)

        toggle(p, registry.hit("Later #todo"))
        assertEquals("- Inbox #todo\n- ~~Later~~ #todo\n", disk("_node.md"))
        assertTrue(doc.hasPendingRows)
        registry.release("_node.md")
    }

    @Test
    fun a_toggle_on_another_files_line_never_makes_a_prepared_day_real() = runTest {
        // LBR-21's rule: the first edit of a prepared day's own rows makes
        // it real; a toggle from its open-tasks list edits a line elsewhere.
        seed(
            "_node.md",
            "- Templates [↳](<Templates/_node.md>)\n- Tasks [↳](<Tasks/_node.md>)\n- Top task #todo\n",
        )
        seed("Templates/_node.md", "- Daily [↳](<Daily/_node.md>)\n")
        seed("Templates/Daily/_node.md", "- Open tasks {{search: #todo is:open}}\n")
        seed("Tasks/_node.md", "- Folded task #todo\n- Open task #todo\n")
        val registry = DocumentRegistry(repo, backgroundScope)
        val t = pane(registry, "Templates/Daily/_node.md")
        t.setDailyTemplate(true)
        runCurrent()
        val p = pane(registry)
        p.navigateToToday(CalendarDate(2026, 10, 5))
        runCurrent()
        val group = assertNotNull(p.stateFlow.value.pendingRowsGroup)

        // A line in a folded folder (another document), a line of the very
        // document holding the day, and — unfolded — a line spliced into it.
        val doc = registry.acquire("_node.md")
        toggle(p, registry.hit("Folded task #todo"))
        assertTrue(doc.isPendingGroup(group), "after folded")
        toggle(p, registry.hit("Top task #todo"))
        assertTrue(doc.isPendingGroup(group), "after top")
        // Another pane on the root unfolds Tasks in the shared document.
        val other = pane(registry)
        val tasksId = other.stateFlow.value.documentState!!.let { it.lineIds[it.lines.indexOf("* Tasks")] }
        other.toggleCollapse(tasksId)
        other.stateFlow.first { s -> s.lines.any { "Open task #todo" in it } }
        runCurrent()
        assertTrue(doc.isPendingGroup(group), "after unfold")
        toggle(p, registry.hit("Open task #todo"))
        assertTrue(doc.isPendingGroup(group), "after open")
        advanceTimeBy(10_000)
        runCurrent()
        registry.flushAll()
        runCurrent()

        assertEquals("- ~~Folded task~~ #todo\n- ~~Open task~~ #todo\n", disk("Tasks/_node.md"))
        assertTrue(disk("_node.md")!!.contains("- ~~Top task~~ #todo\n"))
        // The day is still a throwaway: pending, nothing of it on disk.
        assertEquals(group, p.stateFlow.value.pendingRowsGroup)
        assertTrue(doc.isPendingGroup(group))
        assertTrue(p.lines.any { "Open tasks" in it })
        assertNull(disk("Journal/_node.md"))
        assertFalse(disk("_node.md")!!.contains("Journal"))
        registry.release("_node.md")
    }
}
