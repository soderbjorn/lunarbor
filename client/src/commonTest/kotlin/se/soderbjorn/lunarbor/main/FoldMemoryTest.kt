/*
 * FoldMemoryTest.kt (commonTest)
 * ------------------------------
 * A page comes back with the folds it was left in ([FoldMemory]): after
 * the pane visits another file, and in a fresh pane over a fresh registry
 * seeded with the remembered folders (a restart). Runs against the real
 * stack on [InMemoryFileSystem].
 */

package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.data.PathMove
import se.soderbjorn.lunarbor.testing.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FoldMemoryTest {

    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })

    private suspend fun seed(rel: String, content: String) = fs.writeFile("$root/$rel", content)

    /** Root → Recipes → Soups → Cold, each folder-backed; plus a note. */
    private suspend fun seedVault() {
        seed("_node.md", "- Groceries\n- Recipes [↳](<Recipes/_node.md>)\n")
        seed("Recipes/_node.md", "- Pasta\n- Soups [↳](<Soups/_node.md>)\n")
        seed("Recipes/Soups/_node.md", "- Tomato\n- Cold [↳](<Cold/_node.md>)\n")
        seed("Recipes/Soups/Cold/_node.md", "- Gazpacho\n")
        seed("Note.md", "hello\n")
    }

    private suspend fun TestScope.pane(registry: DocumentRegistry): PaneBackingViewModel {
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
    fun folds_come_back_after_another_file() = runTest {
        seedVault()
        val registry = DocumentRegistry(repo, backgroundScope)
        val p = pane(registry)
        p.toggleCollapse(p.idOf("Recipes"))
        runCurrent()
        p.toggleCollapse(p.idOf("Soups"))
        runCurrent()
        assertTrue(p.isShown("Cold"))
        assertFalse(p.isShown("Gazpacho"))

        p.navigateToVaultFile("Note.md")
        runCurrent()
        p.navigateToVaultFile("_node.md")
        runCurrent()
        p.stateFlow.first { it.isLoaded && it.activeFileRel == "_node.md" }
        runCurrent()
        assertTrue(p.isShown("Cold"))
        assertFalse(p.isShown("Gazpacho"))

        // Folding Recipes is remembered too; Soups stays remembered open.
        p.toggleCollapse(p.idOf("Recipes"))
        runCurrent()
        assertEquals(setOf("Recipes/Soups"), registry.foldMemory.snapshot())
    }

    @Test
    fun folds_come_back_after_a_restart() = runTest {
        seedVault()
        val first = DocumentRegistry(repo, backgroundScope)
        val p = pane(first)
        p.setAllChildrenFolded(false)
        runCurrent()
        val remembered = first.foldMemory.snapshot()
        assertEquals(setOf("Recipes", "Recipes/Soups", "Recipes/Soups/Cold"), remembered)

        val second = DocumentRegistry(repo, backgroundScope)
        second.foldMemory.load(remembered)
        val q = pane(second)
        assertTrue(q.isShown("Gazpacho"))
    }

    @Test
    fun a_moved_folder_stays_remembered() {
        val memory = FoldMemory()
        memory.load(listOf("Recipes", "Recipes/Soups", "Other"))
        memory.applyMoves(listOf(PathMove("Recipes", "Food")))
        assertEquals(setOf("Food", "Food/Soups", "Other"), memory.snapshot())
        memory.applyMoves(listOf(PathMove("Other", ".trash/1 Other")))
        assertEquals(setOf("Food", "Food/Soups"), memory.snapshot())
    }
}
