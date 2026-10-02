/*
 * ZoomFoldTest.kt (commonTest)
 * ----------------------------
 * Zooming never leaves a fold changed behind it: a folded bullet the pane
 * zooms into (or opens on the way to a zoom) folds again once the zoom
 * leaves it ([ZoomNavigation.refoldLeftBehind]), while a bullet that was
 * open before stays open. Runs against the real stack — a
 * [PaneBackingViewModel] over a [DocumentRegistry] + [NoteRepository] on
 * [InMemoryFileSystem].
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
class ZoomFoldTest {

    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })

    private suspend fun seed(rel: String, content: String) = fs.writeFile("$root/$rel", content)

    /** Root: a leaf, then `Recipes` (folder-backed, holding `Soups`). */
    private suspend fun seedVault() {
        seed("_node.md", "- Groceries\n- Recipes [↳](<Recipes/_node.md>)\n")
        seed("Recipes/_node.md", "- Pasta\n- Soups [↳](<Soups/_node.md>)\n")
        seed("Recipes/Soups/_node.md", "- Tomato\n")
    }

    /** Opens a pane on the root outline and waits for it to load. */
    private suspend fun TestScope.pane(): PaneBackingViewModel {
        val registry = DocumentRegistry(repo, backgroundScope)
        val pane = PaneBackingViewModel(registry, backgroundScope, "_node.md")
        pane.stateFlow.first { it.isLoaded }
        runCurrent()
        return pane
    }

    private fun PaneBackingViewModel.idOf(text: String): LineId {
        val s = stateFlow.value
        return s.documentState!!.lineIds[s.lines.indexOfFirst { it.trimStart().removePrefix("* ") == text }]
    }

    private fun PaneBackingViewModel.rowOf(text: String): Int = stateFlow.value.lines.indexOfFirst {
        it.trimStart().removePrefix("* ") == text
    }

    private fun PaneBackingViewModel.isFolded(id: LineId) = id in stateFlow.value.collapsedIds

    @Test
    fun a_folded_bullet_zoomed_into_is_folded_again_after_back() = runTest {
        seedVault()
        val p = pane()
        p.zoomInto(p.rowOf("Recipes"))
        runCurrent()
        val recipes = p.idOf("Recipes")
        assertEquals(recipes, p.stateFlow.value.zoomedLineId)
        assertFalse(p.isFolded(recipes))

        p.zoomBack()
        runCurrent()
        assertNull(p.stateFlow.value.zoomedLineId)
        assertTrue(p.isFolded(recipes))

        // Forward opens it again; its children are still loaded.
        p.zoomForward()
        runCurrent()
        assertEquals(recipes, p.stateFlow.value.zoomedLineId)
        assertFalse(p.isFolded(recipes))
        assertTrue(p.rowOf("Pasta") >= 0)

        p.zoomOut()
        runCurrent()
        assertTrue(p.isFolded(recipes))
    }

    @Test
    fun nested_zooms_fold_each_level_as_the_zoom_leaves_it() = runTest {
        seedVault()
        val p = pane()
        p.zoomInto(p.rowOf("Recipes"))
        runCurrent()
        p.zoomInto(p.rowOf("Soups"))
        runCurrent()
        val recipes = p.idOf("Recipes")
        val soups = p.idOf("Soups")
        assertEquals(soups, p.stateFlow.value.zoomedLineId)
        assertFalse(p.isFolded(recipes))

        // Back to Recipes: Soups folds, Recipes (the zoom) stays open.
        p.zoomBack()
        runCurrent()
        assertTrue(p.isFolded(soups))
        assertFalse(p.isFolded(recipes))

        p.zoomBack()
        runCurrent()
        assertTrue(p.isFolded(recipes))
    }

    @Test
    fun an_open_bullet_stays_open_after_a_zoom() = runTest {
        seedVault()
        val p = pane()
        val recipes = p.idOf("Recipes")
        p.toggleCollapse(recipes)
        runCurrent()
        assertFalse(p.isFolded(recipes))

        p.zoomInto(p.rowOf("Recipes"))
        runCurrent()
        p.zoomBack()
        runCurrent()
        assertFalse(p.isFolded(recipes))
    }

    @Test
    fun unfolding_by_hand_after_a_zoom_keeps_it_open() = runTest {
        seedVault()
        val p = pane()
        p.zoomInto(p.rowOf("Recipes"))
        runCurrent()
        p.zoomBack()
        runCurrent()
        val recipes = p.idOf("Recipes")
        assertTrue(p.isFolded(recipes))

        // The pane still holds the children: the toggle only shows them.
        p.toggleCollapse(recipes)
        runCurrent()
        assertFalse(p.isFolded(recipes))
        assertTrue(p.rowOf("Pasta") >= 0)

        // Now open by hand, it survives another zoom round-trip.
        p.zoomInto(p.rowOf("Recipes"))
        runCurrent()
        p.zoomBack()
        runCurrent()
        assertFalse(p.isFolded(recipes))

        // And folding by hand still works.
        p.toggleCollapse(recipes)
        runCurrent()
        assertTrue(p.isFolded(recipes))
    }
}
