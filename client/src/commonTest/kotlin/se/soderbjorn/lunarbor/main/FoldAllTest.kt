/*
 * FoldAllTest.kt (commonTest)
 * ---------------------------
 * "Expand all children" ([PaneBackingViewModel.expandChildren]: the
 * page node's direct children, one level) and "Collapse children and
 * grandchildren" ([PaneBackingViewModel.collapseChildrenAndGrandchildren]:
 * every fold under the page's node, at every depth).
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
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FoldAllTest {

    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })

    private suspend fun seed(rel: String, content: String) = fs.writeFile("$root/$rel", content)

    /** Root → Recipes → Soups → Cold, each folder-backed. */
    private suspend fun seedVault() {
        seed("_node.md", "- Groceries\n- Recipes [↳](<Recipes/_node.md>)\n")
        seed("Recipes/_node.md", "- Pasta\n- Soups [↳](<Soups/_node.md>)\n")
        seed("Recipes/Soups/_node.md", "- Tomato\n- Cold [↳](<Cold/_node.md>)\n")
        seed("Recipes/Soups/Cold/_node.md", "- Gazpacho\n")
    }

    private suspend fun TestScope.pane(
        registry: DocumentRegistry = DocumentRegistry(repo, backgroundScope),
    ): PaneBackingViewModel {
        val pane = PaneBackingViewModel(registry, backgroundScope, "_node.md")
        pane.stateFlow.first { it.isLoaded }
        runCurrent()
        return pane
    }

    private fun PaneBackingViewModel.rowOf(text: String): Int = stateFlow.value.lines.indexOfFirst {
        it.trimStart().removePrefix("* ") == text
    }

    private fun PaneBackingViewModel.idOf(text: String): LineId =
        stateFlow.value.documentState!!.lineIds[rowOf(text)]

    private fun PaneBackingViewModel.isShown(text: String): Boolean {
        val r = rowOf(text)
        return r >= 0 && r in visibleRows(stateFlow.value, 0, stateFlow.value.lines.lastIndex)
    }

    @Test
    fun expand_opens_one_level_and_collapse_folds_every_level() = runTest {
        seedVault()
        val p = pane()
        assertFalse(p.isShown("Pasta"))

        p.expandChildren()
        runCurrent()
        assertTrue(p.isShown("Pasta"))
        assertTrue(p.isShown("Soups"))
        // Only one level: Soups stays folded.
        assertFalse(p.isShown("Tomato"))

        // Unfold the rest by hand, then collapse everything.
        p.toggleCollapse(p.idOf("Soups"))
        runCurrent()
        p.toggleCollapse(p.idOf("Cold"))
        runCurrent()
        assertTrue(p.isShown("Gazpacho"))

        p.collapseChildrenAndGrandchildren()
        runCurrent()
        assertTrue(p.isShown("Recipes"))
        assertFalse(p.isShown("Pasta"))
        for (t in listOf("Recipes", "Soups", "Cold")) assertTrue(p.idOf(t) in p.stateFlow.value.collapsedIds, t)

        // Expanding again opens Recipes only; the deeper folds stay.
        p.expandChildren()
        runCurrent()
        assertTrue(p.isShown("Soups"))
        assertFalse(p.isShown("Tomato"))
    }

    @Test
    fun zoomed_in_only_the_zoomed_node_s_children_change() = runTest {
        seedVault()
        val p = pane()
        p.zoomInto(p.rowOf("Recipes"))
        runCurrent()
        p.expandChildren()
        runCurrent()
        assertTrue(p.isShown("Tomato"))
        assertFalse(p.isShown("Gazpacho"))
        val recipes = p.idOf("Recipes")
        assertEquals(recipes, p.stateFlow.value.zoomedLineId)

        p.toggleCollapse(p.idOf("Cold"))
        runCurrent()
        p.collapseChildrenAndGrandchildren()
        runCurrent()
        assertTrue(p.idOf("Soups") in p.stateFlow.value.collapsedIds)
        assertTrue(p.idOf("Cold") in p.stateFlow.value.collapsedIds)
        // The zoom target itself is not folded.
        assertFalse(recipes in p.stateFlow.value.collapsedIds)
    }

    @Test
    fun collapse_also_folds_remembered_items_that_are_not_loaded() = runTest {
        seedVault()
        val registry = DocumentRegistry(repo, backgroundScope)
        // Soups and Cold were left open earlier; Recipes was folded, so
        // neither is loaded on the page.
        registry.foldMemory.load(listOf("Recipes/Soups", "Recipes/Soups/Cold"))
        val p = pane(registry)
        assertFalse(p.isShown("Soups"))

        p.collapseChildrenAndGrandchildren()
        runCurrent()
        p.toggleCollapse(p.idOf("Recipes"))
        runCurrent()
        assertTrue(p.isShown("Soups"))
        assertFalse(p.isShown("Tomato"))
        assertTrue(registry.foldMemory.snapshot().none { it.startsWith("Recipes/") })
    }
}
