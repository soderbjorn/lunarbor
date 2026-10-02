/*
 * ExternalChangesTest.kt (commonTest)
 * -----------------------------------
 * Tests for picking up changes made to the vault outside the app (a
 * coding agent editing `_node.md` files): [DocumentRegistry.applyExternalChanges]
 * reloads the open documents a change concerns ([Document.isAffectedBy],
 * [Document.reloadFromDisk]), keeping line ids, expansions and zoom
 * steady, dropping unsaved edits and the panes' undo history — unless
 * the disk holds exactly what the document shows or last saved (an echo,
 * a sync client re-touching files), which keeps them. Also pins
 * the row matcher, [Document.matchIds].
 */

package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.testing.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ExternalChangesTest {

    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })

    private suspend fun seed(rel: String, content: String) = fs.writeFile("$root/$rel", content)

    private suspend fun TestScope.pane(registry: DocumentRegistry, fileRel: String = "_node.md"): PaneBackingViewModel {
        val pane = PaneBackingViewModel(registry, backgroundScope, fileRel)
        pane.stateFlow.first { it.isLoaded }
        runCurrent()
        return pane
    }

    private fun PaneBackingViewModel.doc() = stateFlow.value.documentState!!

    @Test
    fun an_edited_outline_is_reloaded_and_unchanged_rows_keep_their_ids() = runTest {
        seed("_node.md", "- A\n- B\n- C\n")
        val registry = DocumentRegistry(repo, backgroundScope)
        val p = pane(registry)
        val before = p.doc()
        seed("_node.md", "- A\n- New\n- B\n- C\n")
        registry.applyExternalChanges(listOf("_node.md"))
        runCurrent()
        val after = p.doc()
        assertEquals(listOf("* A", "* New", "* B", "* C"), after.lines)
        assertEquals(before.lineIds[0], after.lineIds[0])
        assertEquals(before.lineIds[1], after.lineIds[2])
        assertEquals(before.lineIds[2], after.lineIds[3])
        assertEquals(1, after.reloadCount)
    }

    @Test
    fun unsaved_edits_and_undo_are_dropped_the_disk_wins() = runTest {
        seed("_node.md", "- A\n")
        val registry = DocumentRegistry(repo, backgroundScope)
        val p = pane(registry)
        p.insertChar('x')
        runCurrent()
        assertTrue(p.canUndo())
        seed("_node.md", "- Agent\n")
        registry.applyExternalChanges(listOf("_node.md"))
        runCurrent()
        assertEquals(listOf("* Agent"), p.doc().lines)
        assertFalse(p.canUndo())
        assertTrue(registry.unsavedFilesFlow.value.isEmpty())
        // Nothing left to save: the agent's text stays on disk.
        registry.flushAll()
        assertEquals("- Agent\n", fs.read(root, "_node.md"))
    }

    @Test
    fun a_change_inside_an_expanded_node_reloads_and_keeps_the_zoom() = runTest {
        seed("_node.md", "- Groceries\n- Recipes [↳](<Recipes/_node.md>)\n")
        seed("Recipes/_node.md", "- Pasta\n")
        val registry = DocumentRegistry(repo, backgroundScope)
        val p = pane(registry)
        p.zoomInto(1)
        runCurrent()
        val zoomed = p.stateFlow.value.zoomedLineId
        assertEquals(listOf("* Groceries", "* Recipes", "  * Pasta"), p.doc().lines)
        seed("Recipes/_node.md", "- Pasta\n- Soup\n")
        registry.applyExternalChanges(listOf("Recipes/_node.md"))
        runCurrent()
        assertEquals(listOf("* Groceries", "* Recipes", "  * Pasta", "  * Soup"), p.doc().lines)
        assertEquals(zoomed, p.stateFlow.value.zoomedLineId)
        assertFalse(p.doc().lineIds[1] in p.doc().unloadedRefIds)
    }

    @Test
    fun a_new_nested_node_written_by_an_agent_shows_up() = runTest {
        seed("_node.md", "- Recipes\n")
        val registry = DocumentRegistry(repo, backgroundScope)
        val p = pane(registry)
        seed("Recipes/_node.md", "- Soup\n")
        seed("_node.md", "- Recipes [↳](<Recipes/_node.md>)\n")
        registry.applyExternalChanges(listOf("Recipes", "Recipes/_node.md", "_node.md"))
        runCurrent()
        p.zoomInto(0)
        runCurrent()
        assertEquals(listOf("* Recipes", "  * Soup"), p.doc().lines)
    }

    @Test
    fun unrelated_changes_leave_open_documents_alone() = runTest {
        seed("_node.md", "- A\n")
        seed("Other/_node.md", "- x\n")
        val registry = DocumentRegistry(repo, backgroundScope)
        val p = pane(registry)
        p.insertChar('y')
        runCurrent()
        seed("Other/_node.md", "- changed\n")
        registry.applyExternalChanges(listOf("Other/_node.md", ".trash/x/_node.md"))
        runCurrent()
        assertEquals(0, p.doc().reloadCount)
        assertTrue(p.canUndo())
    }

    @Test
    fun an_echo_of_the_last_save_keeps_unsaved_edits_and_undo() = runTest {
        seed("_node.md", "- A\n")
        val registry = DocumentRegistry(repo, backgroundScope)
        val p = pane(registry)
        p.insertChar('x')
        runCurrent()
        registry.flushAll()
        p.insertChar('y')
        runCurrent()
        val typed = p.doc().lines
        // A sync client re-touches the file we just saved, unchanged.
        registry.applyExternalChanges(listOf("_node.md"))
        runCurrent()
        assertEquals(typed, p.doc().lines)
        assertEquals(0, p.doc().reloadCount)
        assertTrue(p.canUndo())
        assertFalse(registry.unsavedFilesFlow.value.isEmpty())
    }

    @Test
    fun touching_a_collapsed_child_folder_unchanged_keeps_unsaved_edits() = runTest {
        seed("_node.md", "- Groceries\n- Recipes [↳](<Recipes/_node.md>)\n")
        seed("Recipes/_node.md", "- Pasta\n")
        val registry = DocumentRegistry(repo, backgroundScope)
        val p = pane(registry)
        p.insertChar('x')
        runCurrent()
        val typed = p.doc().lines
        registry.applyExternalChanges(listOf("Recipes", "Recipes/_node.md"))
        runCurrent()
        assertEquals(typed, p.doc().lines)
        assertEquals(0, p.doc().reloadCount)
    }

    @Test
    fun a_folder_renamed_outside_reloads_even_when_the_titles_match() = runTest {
        seed("_node.md", "- Recipes [↳](<Recipes/_node.md>)\n")
        seed("Recipes/_node.md", "- Pasta\n")
        val registry = DocumentRegistry(repo, backgroundScope)
        val p = pane(registry)
        fs.moveDirectory("$root/Recipes", "$root/Recipes 2")
        seed("_node.md", "- Recipes [↳](<Recipes 2/_node.md>)\n")
        registry.applyExternalChanges(listOf("Recipes", "Recipes 2", "_node.md"))
        runCurrent()
        assertEquals(1, p.doc().reloadCount)
        p.zoomInto(0)
        runCurrent()
        assertEquals(listOf("* Recipes", "  * Pasta"), p.doc().lines)
    }

    @Test
    fun a_markdown_note_is_reloaded_too() = runTest {
        seed("Note.md", "one\ntwo")
        val registry = DocumentRegistry(repo, backgroundScope)
        val p = pane(registry, "Note.md")
        seed("Note.md", "one\ntwo\nthree")
        registry.applyExternalChanges(listOf("Note.md"))
        runCurrent()
        assertEquals(listOf("one", "two", "three"), p.doc().lines)
    }

    @Test
    fun match_ids_pairs_folders_anywhere_and_text_in_order() {
        val old = listOf("T:a", "F:X", "T:b", "T:c")
        val new = listOf("F:X", "T:a", "T:z", "T:c")
        val m = Document.matchIds(old, new)
        assertEquals(1, m[0])
        assertEquals(0, m[1])
        assertNull(m[2])
        assertEquals(3, m[3])
    }
}
