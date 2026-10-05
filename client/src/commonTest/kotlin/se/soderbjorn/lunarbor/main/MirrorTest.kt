/*
 * MirrorTest.kt (commonTest)
 * --------------------------
 * Mirrors and nodes open in several places, end to end on
 * [InMemoryFileSystem]:
 *
 *  - a link bullet to a node is a mirror ([Document.isMirror]): it folds
 *    open onto the node's own items, edits to them are saved to the
 *    node's outline, and the mirror row stays the link it is;
 *  - deleting an open mirror deletes the link only;
 *  - a folder is loaded once per document: a mirror of a node open on the
 *    page stays folded;
 *  - a node open in two documents keeps both documents' edits (a save
 *    never writes a node it did not change, `NoteRepository.save`'s
 *    `baseBodies`) and the other document follows
 *    (`DocumentRegistry.refreshOtherHolders`).
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
class MirrorTest {

    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })

    private suspend fun seed(rel: String, content: String) = fs.writeFile("$root/$rel", content)
    private fun disk(rel: String) = fs.read(root, rel)

    private suspend fun TestScope.pane(registry: DocumentRegistry, fileRel: String = "_node.md"): PaneBackingViewModel {
        val pane = PaneBackingViewModel(registry, backgroundScope, fileRel)
        pane.stateFlow.first { it.isLoaded }
        runCurrent()
        return pane
    }

    private val PaneBackingViewModel.lines get() = stateFlow.value.lines
    private fun PaneBackingViewModel.id(row: Int) = stateFlow.value.documentState!!.lineIds[row]

    private suspend fun seedRecipes() {
        seed("_node.md", "- Groceries\n- [Soups](Recipes/Soups/_node.md)\n- Recipes [↳](<Recipes/_node.md>)\n")
        seed("Recipes/_node.md", "- Soups [↳](<Soups/_node.md>)\n")
        seed("Recipes/Soups/_node.md", "- Tomato\n- Pea [↳](<Pea/_node.md>)\n")
        seed("Recipes/Soups/Pea/_node.md", "- green\n")
    }

    @Test
    fun a_link_bullet_to_a_node_is_a_mirror_that_edits_the_node() = runTest {
        seedRecipes()
        val registry = DocumentRegistry(repo, backgroundScope)
        val p = pane(registry)
        assertTrue(p.isMirror(p.id(1)))
        assertFalse(p.isMirror(p.id(0)))
        assertTrue(p.isPromotedRef(p.id(1)))

        p.toggleCollapse(p.id(1))
        runCurrent()
        assertEquals(listOf("* Groceries", "* [Soups](Recipes/Soups/_node.md)", "  * Tomato", "  * Pea", "* Recipes"), p.lines)

        // Typing in the mirror edits the node itself.
        p.moveTo(2, p.lines[2].length)
        p.insertText(" soup")
        p.insertNewline()
        p.insertText("Leek")
        registry.flushAll()
        assertEquals("- Tomato soup\n- Leek\n- Pea [↳](<Pea/_node.md>)\n", disk("Recipes/Soups/_node.md"))
        // The page keeps the mirror as the link it is.
        assertEquals("- Groceries\n- [Soups](Recipes/Soups/_node.md)\n- Recipes [↳](<Recipes/_node.md>)\n", disk("_node.md"))
        assertEquals("- green\n", disk("Recipes/Soups/Pea/_node.md"))
    }

    @Test
    fun deleting_an_open_mirror_deletes_only_the_link() = runTest {
        seedRecipes()
        val registry = DocumentRegistry(repo, backgroundScope)
        val p = pane(registry)
        p.toggleCollapse(p.id(1))
        runCurrent()
        p.toggleCollapse(p.id(3))
        runCurrent()
        assertEquals("    * green", p.lines[4])
        // Select the mirror with everything under it and delete it.
        p.moveTo(0, p.lines[0].length)
        p.moveTo(4, p.lines[4].length, extend = true)
        p.backspace()
        registry.flushAll()
        assertEquals("- Groceries\n- Recipes [↳](<Recipes/_node.md>)\n", disk("_node.md"))
        assertEquals("- Tomato\n- Pea [↳](<Pea/_node.md>)\n", disk("Recipes/Soups/_node.md"))
        assertEquals("- green\n", disk("Recipes/Soups/Pea/_node.md"))
        assertTrue(fs.dirs.none { it.contains("/.trash") })
    }

    @Test
    fun a_mirror_of_a_node_open_on_the_page_stays_folded() = runTest {
        seed("_node.md", "- [Recipes](./Recipes/_node.md)\n- Recipes [↳](<Recipes/_node.md>)\n")
        seed("Recipes/_node.md", "- Pasta\n")
        val registry = DocumentRegistry(repo, backgroundScope)
        val p = pane(registry)
        assertTrue(p.isMirror(p.id(0)))
        p.toggleCollapse(p.id(1))
        runCurrent()
        assertEquals(listOf("* [Recipes](./Recipes/_node.md)", "* Recipes", "  * Pasta"), p.lines)
        p.toggleCollapse(p.id(0))
        runCurrent()
        assertEquals(listOf("* [Recipes](./Recipes/_node.md)", "* Recipes", "  * Pasta"), p.lines)
        registry.flushAll()
        assertEquals("- Pasta\n", disk("Recipes/_node.md"))
    }

    @Test
    fun a_link_to_a_missing_node_or_a_file_is_no_mirror() = runTest {
        seed("_node.md", "- [gone](./Missing/_node.md)\n- [note](Note.md)\n- [a](./A/_node.md) and [b](./B/_node.md)\n")
        seed("Note.md", "text")
        val registry = DocumentRegistry(repo, backgroundScope)
        val p = pane(registry)
        for (row in 0..2) assertFalse(p.isMirror(p.id(row)))
    }

    @Test
    fun a_node_open_in_two_places_keeps_both_edits() = runTest {
        seed("_node.md", "- Groceries\n- Recipes [↳](<Recipes/_node.md>)\n")
        seed("Recipes/_node.md", "- Pasta\n")
        val registry = DocumentRegistry(repo, backgroundScope)
        val home = pane(registry)
        home.toggleCollapse(home.id(1))
        runCurrent()
        val recipes = pane(registry, "Recipes/_node.md")

        recipes.moveTo(0, recipes.lines[0].length)
        recipes.insertText("!")
        registry.flushAll()
        runCurrent()
        assertEquals("- Pasta!\n", disk("Recipes/_node.md"))
        // Home follows the other window's save.
        assertEquals("  * Pasta!", home.lines[2])

        // An edit elsewhere on Home never puts back its old copy of Recipes.
        home.moveTo(0, home.lines[0].length)
        home.insertText("?")
        registry.flushAll()
        assertEquals("- Pasta!\n", disk("Recipes/_node.md"))
        assertEquals("- Groceries?\n- Recipes [↳](<Recipes/_node.md>)\n", disk("_node.md"))
    }

    @Test
    fun a_stale_copy_of_an_empty_node_never_empties_it_again() = runTest {
        seed("_node.md", "- Groceries\n- [Inbox](./Inbox/_node.md)\n")
        seed("Inbox/_node.md", "")
        val registry = DocumentRegistry(repo, backgroundScope)
        val home = pane(registry)
        home.toggleCollapse(home.id(1))
        runCurrent()
        val inbox = pane(registry, "Inbox/_node.md")
        inbox.insertText("Call Anna")
        // Home saves an edit of its own before it has seen the new item.
        home.moveTo(0, home.lines[0].length)
        home.insertText("!")
        registry.flushAll()
        runCurrent()
        assertEquals("- Call Anna\n", disk("Inbox/_node.md"))
        assertNull(home.lines.firstOrNull { it.trim() == "*" })
    }

    @Test
    fun an_edit_in_a_mirror_reaches_a_window_open_on_the_node() = runTest {
        seedRecipes()
        val registry = DocumentRegistry(repo, backgroundScope)
        val home = pane(registry)
        val soups = pane(registry, "Recipes/Soups/_node.md")
        home.toggleCollapse(home.id(1))
        runCurrent()
        home.moveTo(2, home.lines[2].length)
        home.insertText(" soup")
        registry.flushAll()
        runCurrent()
        assertEquals("* Tomato soup", soups.lines[0])
    }

    @Test
    fun zooming_into_a_node_its_open_mirror_holds_shows_and_keeps_its_items() = runTest {
        seed("_node.md", "- [Recipes](./Recipes/_node.md)\n- Recipes [↳](<Recipes/_node.md>)\n")
        seed("Recipes/_node.md", "- Pasta\n- Soups [↳](<Soups/_node.md>)\n")
        seed("Recipes/Soups/_node.md", "- Tomato\n")
        val registry = DocumentRegistry(repo, backgroundScope)
        val p = pane(registry)
        // The mirror is open first and holds the folder.
        p.toggleCollapse(p.id(0))
        runCurrent()
        assertEquals("  * Pasta", p.lines[1])
        // Zooming into the real bullet shows its items, no empty placeholder.
        p.zoomInto(p.lines.indexOf("* Recipes"))
        runCurrent()
        assertNull(p.lines.firstOrNull { it.trim() == "*" }, p.lines.toString())
        // The mirror lets go of the node; its own bullet shows the items.
        assertEquals(listOf("* [Recipes](./Recipes/_node.md)", "* Recipes", "  * Pasta", "  * Soups"), p.lines)
        // Saving the page never empties the node on disk.
        p.moveTo(2, p.lines[2].length)
        p.insertText("!")
        registry.flushAll()
        runCurrent()
        assertEquals("- Pasta!\n- Soups [↳](<Soups/_node.md>)\n", disk("Recipes/_node.md"))
        assertEquals("- Tomato\n", disk("Recipes/Soups/_node.md"))
    }

    @Test
    fun a_folded_mirror_lets_its_node_unfold_in_place_after_saves() = runTest {
        seed("_node.md", "- [Recipes](./Recipes/_node.md)\n- Recipes [↳](<Recipes/_node.md>)\n")
        seed("Recipes/_node.md", "- Pasta\n")
        val registry = DocumentRegistry(repo, backgroundScope)
        val p = pane(registry)
        val mirror = p.id(0)
        val real = p.id(1)
        p.toggleCollapse(mirror)
        runCurrent()
        p.toggleCollapse(mirror)
        runCurrent()
        p.moveTo(0, p.lines[0].length)
        p.insertText(" !")
        registry.flushAll()
        runCurrent()
        p.toggleCollapse(real)
        runCurrent()
        assertEquals(listOf("* [Recipes](./Recipes/_node.md) !", "* Recipes", "  * Pasta"), p.lines)
        p.moveTo(0, p.lines[0].length)
        p.insertText("?")
        registry.flushAll()
        runCurrent()
        assertEquals("- Pasta\n", disk("Recipes/_node.md"))
    }

    @Test
    fun editing_a_page_with_a_folded_mirror_never_empties_its_node() = runTest {
        seed("_node.md", "- Groceries\n- [Recipes](./Recipes/_node.md)\n")
        seed("Recipes/_node.md", "- Pasta\n")
        val registry = DocumentRegistry(repo, backgroundScope)
        val p = pane(registry)
        for (ch in listOf("!", "?", ".")) {
            p.moveTo(0, p.lines[0].length)
            p.insertText(ch)
            registry.flushAll()
            runCurrent()
        }
        assertEquals("- Pasta\n", disk("Recipes/_node.md"))
        // Unfolding it still shows the node's items.
        p.toggleCollapse(p.id(1))
        runCurrent()
        assertEquals(listOf("* Groceries!?.", "* [Recipes](./Recipes/_node.md)", "  * Pasta"), p.lines)
    }

    @Test
    fun an_open_mirror_keeps_saving_its_edits() = runTest {
        seed("_node.md", "- Groceries\n- [Recipes](./Recipes/_node.md)\n")
        seed("Recipes/_node.md", "- Pasta\n")
        val registry = DocumentRegistry(repo, backgroundScope)
        val p = pane(registry)
        p.toggleCollapse(p.id(1))
        runCurrent()
        for (ch in listOf("1", "2")) {
            p.moveTo(2, p.lines[2].length)
            p.insertText(ch)
            registry.flushAll()
            runCurrent()
        }
        assertEquals("- Pasta12\n", disk("Recipes/_node.md"))
    }
}
