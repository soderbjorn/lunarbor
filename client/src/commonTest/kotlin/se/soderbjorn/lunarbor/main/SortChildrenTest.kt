/*
 * SortChildrenTest.kt (commonTest)
 * --------------------------------
 * "Sort children by name" ([PaneBackingViewModel.sortChildrenByName]): the
 * page node's direct children in natural name order, each with its
 * subtree, deeper levels untouched, undoable, saved as the outline's order.
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SortChildrenTest {

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

    private fun PaneBackingViewModel.texts(): List<String> = stateFlow.value.lines

    @Test
    fun root_children_sort_naturally_with_their_subtrees_and_undo_restores() = runTest {
        seed("_node.md", "- note 10\n- **Banana**\n-\n- apple\n- Note 2\n")
        val (registry, p) = pane()
        // Give "apple" a child in memory, so its subtree has to travel.
        val apple = p.texts().indexOf("* apple")
        p.moveTo(apple, p.texts()[apple].length)
        p.insertNewline()
        p.indentLine()
        p.insertChar('x')
        val before = p.texts()

        p.sortChildrenByName()
        assertEquals(listOf("* apple", "  * x", "* **Banana**", "* Note 2", "* note 10", "* "), p.texts())
        assertEquals("  * x", p.texts()[p.stateFlow.value.cursorRow])

        p.undo()
        assertEquals(before, p.texts())

        p.sortChildrenByName()
        registry.flushAll()
        // The untitled bullet, now last, is not saved (as any trailing empty bullet).
        assertEquals("- apple [↳](<apple/_node.md>)\n- **Banana**\n- Note 2\n- note 10\n", fs.read(root, "_node.md"))
        assertEquals("- x\n", fs.read(root, "apple/_node.md"))
    }

    @Test
    fun zoomed_only_the_zoom_target_s_children_are_sorted() = runTest {
        seed("_node.md", "- Zed\n- List [↳](<List/_node.md>)\n- Alpha\n")
        seed("List/_node.md", "- c\n- b\n- a [↳](<a/_node.md>)\n")
        seed("List/a/_node.md", "- z\n- y\n")
        val (_, p) = pane()
        p.zoomInto(p.texts().indexOf("* List"))
        runCurrent()
        p.toggleCollapse(p.stateFlow.value.documentState!!.lineIds[p.texts().indexOf("  * a")])
        runCurrent()

        p.sortChildrenByName()
        assertEquals(
            listOf("* Zed", "* List", "  * a", "    * z", "    * y", "  * b", "  * c", "* Alpha"),
            p.texts(),
        )
    }

    @Test
    fun reversed_sorts_z_to_a_with_untitled_still_last() = runTest {
        seed("_node.md", "- note 10\n- **Banana**\n-\n- apple\n- Note 2\n")
        val (_, p) = pane()

        p.sortChildrenByName(reverse = true)
        assertEquals(listOf("* note 10", "* Note 2", "* **Banana**", "* apple", "* "), p.texts())

        p.undo()
        assertEquals(listOf("* note 10", "* **Banana**", "* ", "* apple", "* Note 2"), p.texts())
    }
}
