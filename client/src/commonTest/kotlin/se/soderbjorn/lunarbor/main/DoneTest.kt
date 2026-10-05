/*
 * DoneTest.kt (commonTest)
 * ------------------------
 * The done state of LBR-24 beyond the pure text rule (`DoneStateTest`):
 * done rows of an open outline ([DoneLayout]) with inheritance, the text
 * index's done flag across files (`is:done` / `is:open`), Toggle done on
 * the caret's item and on a selection (with undo), and a pane's "Hide done
 * items". Pane tests run against the real stack — a [PaneBackingViewModel]
 * over a [DocumentRegistry] + [NoteRepository] on [InMemoryFileSystem].
 */

package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.data.SearchQuery
import se.soderbjorn.lunarbor.data.TextIndex
import se.soderbjorn.lunarbor.data.TextScope
import se.soderbjorn.lunarbor.testing.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DoneTest {

    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })

    private suspend fun seed(rel: String, content: String) = fs.writeFile("$root/$rel", content)

    private suspend fun TestScope.pane(): Pair<DocumentRegistry, PaneBackingViewModel> {
        val registry = DocumentRegistry(repo, backgroundScope)
        val pane = PaneBackingViewModel(registry, backgroundScope, "_node.md")
        pane.stateFlow.first { it.isLoaded }
        runCurrent()
        return registry to pane
    }

    private val PaneBackingViewModel.lines get() = stateFlow.value.lines

    /** Indents each of [rows] one level, in memory. */
    private fun PaneBackingViewModel.indent(vararg rows: Int) {
        for (row in rows) {
            moveTo(row, lines[row].length)
            indentLine()
        }
    }
    private fun PaneBackingViewModel.shown() =
        visibleRows(stateFlow.value, 0, lines.lastIndex).map { lines[it] }

    // ------------------------------------------------------------ layout

    @Test
    fun a_done_item_and_everything_under_it_are_done_rows() {
        val lines = listOf(
            "* Open",
            "* ~~Done~~ #todo",
            "  * child",
            "    * grandchild",
            "* Partly ~~struck~~",
            "  * child",
            "${BlockLayout.FIRST}~~Block title~~",
            "${BlockLayout.NEXT}second row",
            "* after",
        )
        val done = DoneLayout.doneRows(lines)!!
        assertEquals(
            listOf(false, true, true, true, false, false, true, true, false),
            lines.indices.map { done[it] },
        )
        assertNull(DoneLayout.doneRows(listOf("* a", "  * b")))
        // From a later row: done-ness above it does not count.
        val fromChild = DoneLayout.hiddenRows(lines, 2)
        assertFalse(DoneLayout.isMarked(fromChild, 2))
        assertTrue(DoneLayout.isMarked(fromChild, 6))
    }

    // ------------------------------------------------------------ index

    @Test
    fun the_index_knows_done_lines_inherited_across_folders() {
        val idx = TextIndex(listFiles = { emptyList() }, readText = { null })
        idx.noteText("_node.md", "- Open task #todo\n- ~~Project~~ #todo [↳](<Project/_node.md>)\n- Other [↳](<Other/_node.md>)\n")
        idx.noteText("Project/_node.md", "- Sub [↳](<Sub/_node.md>)\n- step #todo\n")
        idx.noteText("Project/Sub/_node.md", "- deep #todo\n")
        idx.noteText("Other/_node.md", "- ~~ticked~~ #todo\n- pending #todo\n")
        idx.noteText("Other/Note.md", "- ~~note done~~ #todo\n- note open #todo\n")
        fun q(query: String) = SearchQuery.parse(query).expr
        val open = idx.search(TextScope.Tree(""), q("#todo is:open"))
        assertEquals(listOf("Open task #todo", "pending #todo", "- note open #todo"), open.hits.map { it.text })
        assertTrue(open.hits.none { it.done })
        val done = idx.search(TextScope.Tree(""), q("#todo is:done"))
        // The done project stands for the lines under it (it is a hit).
        assertEquals(listOf("Project #todo", "ticked #todo", "- note done #todo"), done.hits.map { it.text })
        assertTrue(done.hits.all { it.done })
        // Inside the project every line is done by inheritance (`Sub`
        // inherits the project's tag, so it stands for `deep`).
        val inProject = idx.search(TextScope.Tree("Project"), q("#todo"))
        assertEquals(listOf("Sub", "step #todo"), inProject.hits.map { it.text })
        assertEquals(listOf("deep #todo"), idx.search(TextScope.Tree("Project"), q("deep")).hits.map { it.text })
        assertTrue(idx.search(TextScope.Tree("Project"), q("deep")).hits.single().done)
        assertTrue(inProject.hits.all { it.done })
        assertEquals(0, idx.search(TextScope.Tree("Project"), q("#todo -is:done")).total)
        assertTrue(idx.isFolderDone("Project/Sub"))
        assertFalse(idx.isFolderDone("Other"))
        // Unticking the project opens everything under it again.
        idx.noteText("_node.md", "- Open task #todo\n- Project #todo [↳](<Project/_node.md>)\n- Other [↳](<Other/_node.md>)\n")
        assertEquals(2, idx.search(TextScope.Tree("Project"), q("#todo is:open")).total)
    }

    // ------------------------------------------------------------ toggle

    @Test
    fun toggle_done_strikes_the_caret_item_keeping_tags_outside_and_undo_restores() = runTest {
        seed("_node.md", "- Buy oat milk #todo\n- Other\n")
        val (registry, p) = pane()
        p.moveTo(0, 6)
        p.toggleDone()
        assertEquals("* ~~Buy oat milk~~ #todo", p.lines[0])
        // The caret stays on its word.
        assertEquals(8, p.stateFlow.value.cursorCol)
        assertTrue(p.isRowDone(p.stateFlow.value, 0))
        assertFalse(p.isRowDone(p.stateFlow.value, 1))
        p.undo()
        assertEquals("* Buy oat milk #todo", p.lines[0])
        p.redo()
        p.toggleDone()
        assertEquals("* Buy oat milk #todo", p.lines[0])
        p.toggleDone()
        registry.flushAll()
        assertEquals("- ~~Buy oat milk~~ #todo\n- Other\n", fs.read(root, "_node.md"))
    }

    @Test
    fun toggle_done_on_a_selection_marks_every_item_then_unmarks_them_all() = runTest {
        seed("_node.md", "- a\n- ~~b~~\n- c #x\n- d\n")
        val (_, p) = pane()
        p.setSelection(0, 2, 2, 3)
        p.toggleDone()
        assertEquals(listOf("* ~~a~~", "* ~~b~~", "* ~~c~~ #x", "* d"), p.lines)
        p.toggleDone()
        assertEquals(listOf("* a", "* b", "* c #x", "* d"), p.lines)
        // One edit each: one undo brings back the marked state.
        p.undo()
        assertEquals(listOf("* ~~a~~", "* ~~b~~", "* ~~c~~ #x", "* d"), p.lines)
    }

    @Test
    fun a_child_done_only_by_inheritance_is_struck_on_its_own() = runTest {
        seed("_node.md", "- ~~Parent~~\n- child\n")
        val (_, p) = pane()
        p.indent(1)
        assertTrue(p.isRowDone(p.stateFlow.value, 1))
        p.moveTo(1, 6)
        p.toggleDone()
        assertEquals(listOf("* ~~Parent~~", "  * ~~child~~"), p.lines)
    }

    @Test
    fun toggle_done_is_not_offered_in_markdown_mode() = runTest {
        seed("_node.md", "- x\n")
        seed("Note.md", "Buy milk\n")
        val (_, p) = pane()
        p.navigateToVaultFile("Note.md")
        p.stateFlow.first { it.isLoaded && it.activeFileRel == "Note.md" }
        runCurrent()
        assertFalse(p.canToggleDone())
        p.moveTo(0, 2)
        p.toggleDone()
        assertEquals("Buy milk", p.lines[0])
    }

    // ------------------------------------------------------------ hide done

    @Test
    fun hide_done_items_leaves_done_subtrees_off_screen_without_blocking_edits() = runTest {
        seed("_node.md", "- Open\n- ~~Done~~\n- child\n- Last\n")
        val (_, p) = pane()
        p.indent(2)
        p.moveTo(2, 4)
        p.setHideDone(true)
        assertEquals(listOf("* Open", "* Last"), p.shown())
        // The caret left the hidden rows.
        assertTrue(p.stateFlow.value.cursorRow !in 1..2)
        // Toggling a visible item done hides it too; undo brings it back.
        p.moveTo(3, 4)
        p.toggleDone()
        assertEquals(listOf("* Open"), p.shown())
        p.undo()
        assertEquals(listOf("* Open", "* Last"), p.shown())
        p.setHideDone(false)
        assertEquals(p.lines, p.shown())
    }

    @Test
    fun a_done_zoom_target_still_shows_its_children_with_done_items_hidden() = runTest {
        seed("_node.md", "- ~~Project~~\n- a\n- ~~b~~\n")
        val (_, p) = pane()
        p.indent(1, 2)
        p.zoomInto(0)
        runCurrent()
        p.setHideDone(true)
        val s = p.stateFlow.value
        val zoom = p.zoomInfo(s)!!
        assertEquals(listOf("  * a"), p.visibleRows(s, zoom.startRow, zoom.endRowInclusive).map { s.lines[it] })
    }

    @Test
    fun a_page_with_only_done_items_gets_a_row_to_type_in() = runTest {
        seed("_node.md", "- ~~a~~\n- ~~b~~\n")
        val (_, p) = pane()
        p.setHideDone(true)
        runCurrent()
        advanceTimeBy(10)
        assertEquals(listOf("* "), p.shown())
    }
}
