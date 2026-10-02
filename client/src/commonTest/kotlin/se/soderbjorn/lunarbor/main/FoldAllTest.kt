/*
 * FoldAllTest.kt (commonTest)
 * ---------------------------
 * "Expand all children" / "Collapse all children"
 * ([PaneBackingViewModel.setAllChildrenFolded]): every fold under the
 * page's node, at every depth, loading folder-backed children on the way.
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

    private suspend fun TestScope.pane(): PaneBackingViewModel {
        val registry = DocumentRegistry(repo, backgroundScope)
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
    fun expand_all_opens_every_level_and_collapse_all_folds_them_again() = runTest {
        seedVault()
        val p = pane()
        assertFalse(p.isShown("Pasta"))

        p.setAllChildrenFolded(false)
        runCurrent()
        for (t in listOf("Pasta", "Soups", "Tomato", "Cold", "Gazpacho")) assertTrue(p.isShown(t), t)
        assertTrue(p.stateFlow.value.collapsedIds.isEmpty())

        p.setAllChildrenFolded(true)
        runCurrent()
        assertTrue(p.isShown("Recipes"))
        assertFalse(p.isShown("Pasta"))
        for (t in listOf("Recipes", "Soups", "Cold")) assertTrue(p.idOf(t) in p.stateFlow.value.collapsedIds, t)

        // Unfolding by hand one level keeps the deeper folds.
        p.toggleCollapse(p.idOf("Recipes"))
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
        p.setAllChildrenFolded(false)
        runCurrent()
        assertTrue(p.isShown("Gazpacho"))
        val recipes = p.idOf("Recipes")
        assertEquals(recipes, p.stateFlow.value.zoomedLineId)

        p.setAllChildrenFolded(true)
        runCurrent()
        assertTrue(p.idOf("Soups") in p.stateFlow.value.collapsedIds)
        // The zoom target itself is not folded.
        assertFalse(recipes in p.stateFlow.value.collapsedIds)
    }
}
