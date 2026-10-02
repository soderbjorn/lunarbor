/*
 * DeletePageNodeTest.kt (commonTest)
 * ----------------------------------
 * "Delete this node" ([PaneBackingViewModel.deletePageNode]): the node the
 * page is — the zoom target, or a node's own outline — deleted with its
 * subtree from one level up, undoable, its folder trashed on save; on a
 * file, "Delete this file" ([PaneBackingViewModel.trashPageFile]).
 * Runs against the real stack — a [PaneBackingViewModel] over a
 * [DocumentRegistry] + [NoteRepository] on [InMemoryFileSystem].
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DeletePageNodeTest {

    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })

    private suspend fun seed(rel: String, content: String) = fs.writeFile("$root/$rel", content)

    private suspend fun TestScope.pane(file: String = "_node.md"): Pair<DocumentRegistry, PaneBackingViewModel> {
        val registry = DocumentRegistry(repo, backgroundScope)
        val pane = PaneBackingViewModel(registry, backgroundScope, file)
        pane.stateFlow.first { it.isLoaded }
        runCurrent()
        return registry to pane
    }

    private fun PaneBackingViewModel.texts(): List<String> = stateFlow.value.lines

    @Test
    fun the_zoom_target_goes_with_its_subtree_and_undo_brings_it_back() = runTest {
        seed("_node.md", "- A\n- **B**\n  * b1\n    * b2\n- C\n")
        val (_, p) = pane()
        p.zoomInto(1)
        runCurrent()
        assertEquals("B", p.pageNodeTitle())
        val before = p.texts()

        p.deletePageNode()
        runCurrent()
        assertNull(p.stateFlow.value.zoomedLineId)
        assertEquals(listOf("* A", "* C"), p.texts())
        assertEquals("* A", p.texts()[p.stateFlow.value.cursorRow])

        p.undo()
        assertEquals(before, p.texts())
    }

    @Test
    fun a_nodes_own_outline_is_deleted_from_its_parent_and_trashed() = runTest {
        seed("_node.md", "- A\n- Work [↳](<Work/_node.md>)\n- C\n")
        seed("Work/_node.md", "- plan\n")
        val (registry, p) = pane("Work/_node.md")
        assertEquals("Work", p.pageNodeTitle())

        p.deletePageNode().join()
        runCurrent()
        assertEquals("_node.md", p.stateFlow.value.activeFileRel)
        assertEquals(listOf("* A", "* C"), p.texts())
        // Back cannot reopen the deleted node.
        assertFalse(p.stateFlow.value.fileHistory.any { it.fileRel.startsWith("Work/") })

        registry.flushAll()
        runCurrent()
        assertNull(fs.readFileIfExists("$root/Work/_node.md"))
    }

    @Test
    fun nothing_to_delete_at_the_root() = runTest {
        seed("_node.md", "- A\n")
        val (_, p) = pane()
        assertNull(p.pageNodeTitle())
        p.deletePageNode().join()
        assertEquals(listOf("* A"), p.texts())
    }

    @Test
    fun a_note_on_screen_goes_to_the_trash_and_the_pane_to_its_node() = runTest {
        seed("_node.md", "- Work [↳](<Work/_node.md>)\n")
        seed("Work/_node.md", "- plan\n")
        seed("Work/Ideas.md", "# Ideas\n\nsome\n")
        val (_, p) = pane("Work/Ideas.md")
        assertNull(p.pageNodeTitle())
        assertEquals("Work/Ideas.md", p.pageFile())

        assertNull(p.trashPageFile())
        runCurrent()
        assertEquals("Work/_node.md", p.stateFlow.value.activeFileRel)
        assertNull(fs.readFileIfExists("$root/Work/Ideas.md"))
        assertFalse(p.stateFlow.value.fileHistory.any { it.fileRel == "Work/Ideas.md" })
        // Not on an outline.
        assertNull(p.pageFile())
    }
}
