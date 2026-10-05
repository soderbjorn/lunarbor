/*
 * RelativeLinksTest.kt (commonTest)
 * ---------------------------------
 * Links written as relative Markdown links ([LunarborLink.relative]), end to
 * end on [InMemoryFileSystem]:
 *
 *  - Insert Link writes a link relative to the caret row's folder;
 *  - a row moved into another folder (indent) gets its links rewritten
 *    relative to where it is now, after the save ([Document.linkBaseOf]);
 *    undo brings back the old text with its old meaning;
 *  - links written the old ways (`lunarbor:/…`, `/…`) become relative on save;
 *  - copied rows carry vault-rooted links, so a paste anywhere means the
 *    same, and the save writes them relative to where they landed;
 *  - a link to a direct child node at the end of a bullet stays a link;
 *  - the one-time migration ([NoteRepository.migrateRelativeLinks]).
 */

package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import se.soderbjorn.lunarbor.data.LinkTarget
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.data.VaultEntryKind
import se.soderbjorn.lunarbor.testing.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RelativeLinksTest {

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

    /** Saves everything, twice: a save that rewrites links makes one more. */
    private suspend fun TestScope.saveAll(registry: DocumentRegistry) {
        registry.flushAll()
        runCurrent()
        registry.flushAll()
        runCurrent()
    }

    private val PaneBackingViewModel.lines get() = stateFlow.value.lines
    private fun PaneBackingViewModel.id(row: Int) = stateFlow.value.documentState!!.lineIds[row]

    @Test
    fun insert_link_writes_a_link_relative_to_the_rows_folder() = runTest {
        seed("_node.md", "- Recipes [↳](<Recipes/_node.md>)\n- Notes [↳](<Notes/_node.md>)\n")
        seed("Recipes/_node.md", "- Pasta [↳](<Pasta/_node.md>)\n")
        seed("Recipes/Pasta/_node.md", "- x\n")
        seed("Notes/_node.md", "- todo\n")
        val registry = DocumentRegistry(repo, backgroundScope)
        val p = pane(registry, "Notes/_node.md")
        p.moveTo(0, p.lines[0].length)
        p.insertText(" ")
        p.insertLinkTo(LinkTarget("Recipes/Pasta", "Pasta", VaultEntryKind.FOLDER))
        saveAll(registry)
        assertEquals("- todo [Pasta](../Recipes/Pasta/_node.md)\n", disk("Notes/_node.md"))
        // Drawn and followed as the vault path.
        assertEquals("lunarbor:/Recipes/Pasta", p.linkHrefOf(0, "../Recipes/Pasta/_node.md"))
    }

    @Test
    fun a_row_moved_into_another_folder_gets_its_links_rewritten() = runTest {
        seed("_node.md", "- Recipes [↳](<Recipes/_node.md>)\n- see [pasta](Recipes/Pasta/_node.md)\n")
        seed("Recipes/_node.md", "- Pasta [↳](<Pasta/_node.md>)\n")
        seed("Recipes/Pasta/_node.md", "- x\n")
        val registry = DocumentRegistry(repo, backgroundScope)
        val p = pane(registry)
        p.toggleCollapse(p.id(0))
        runCurrent()
        assertEquals(listOf("* Recipes", "  * Pasta", "* see [pasta](Recipes/Pasta/_node.md)"), p.lines)
        // Indent the link row under Recipes: it is now stored in Recipes/.
        p.moveTo(2, 0)
        p.indentLine()
        // Until the save, it still means what it meant.
        assertEquals("lunarbor:/Recipes/Pasta", p.linkHrefOf(2, "Recipes/Pasta/_node.md"))
        saveAll(registry)
        assertEquals("  * see [pasta](./Pasta/_node.md)", p.lines[2])
        assertEquals("- Pasta [↳](<Pasta/_node.md>)\n- see [pasta](./Pasta/_node.md)\n", disk("Recipes/_node.md"))
        assertEquals("lunarbor:/Recipes/Pasta", p.linkHrefOf(2, "./Pasta/_node.md"))

        // Undo the indent: the old text comes back, with its old meaning.
        p.undo()
        runCurrent()
        val row = p.lines.indexOfFirst { "see [pasta]" in it }
        val dest = Regex("\\]\\(([^)]*)\\)").find(p.lines[row])!!.groupValues[1]
        assertEquals("lunarbor:/Recipes/Pasta", p.linkHrefOf(row, dest))
        saveAll(registry)
        val again = p.lines.indexOfFirst { "see [pasta]" in it }
        val destAgain = Regex("\\]\\(([^)]*)\\)").find(p.lines[again])!!.groupValues[1]
        assertEquals("lunarbor:/Recipes/Pasta", p.linkHrefOf(again, destAgain))
    }

    @Test
    fun old_style_links_become_relative_on_save() = runTest {
        seed("_node.md", "- Recipes [↳](<Recipes/_node.md>)\n")
        seed("Recipes/_node.md", "- a [p](lunarbor:/Recipes/Pasta) b [f](/Recipes/shot.png) c [w](https://x.test)\n")
        seed("Recipes/Pasta/_node.md", "- x\n")
        seed("Recipes/shot.png", "PNG")
        val registry = DocumentRegistry(repo, backgroundScope)
        val p = pane(registry, "Recipes/_node.md")
        p.moveTo(0, p.lines[0].length)
        p.insertText("!")
        saveAll(registry)
        assertEquals("- a [p](./Pasta/_node.md) b [f](shot.png) c [w](https://x.test)!\n", disk("Recipes/_node.md"))
    }

    @Test
    fun copied_rows_carry_rooted_links_and_paste_anywhere() = runTest {
        seed("_node.md", "- Recipes [↳](<Recipes/_node.md>)\n- Notes [↳](<Notes/_node.md>)\n")
        seed("Recipes/_node.md", "- see [pasta](./Pasta/_node.md)\n- Pasta [↳](<Pasta/_node.md>)\n")
        seed("Recipes/Pasta/_node.md", "- x\n")
        seed("Notes/_node.md", "- todo\n")
        val registry = DocumentRegistry(repo, backgroundScope)
        val recipes = pane(registry, "Recipes/_node.md")
        recipes.moveTo(0, 0)
        recipes.moveTo(0, recipes.lines[0].length, extend = true)
        val copied = recipes.getSelectedText()
        assertEquals("see [pasta](/Recipes/Pasta/_node.md)", copied)

        val notes = pane(registry, "Notes/_node.md")
        notes.moveTo(0, notes.lines[0].length)
        notes.insertNewline()
        notes.insertText(copied!!)
        saveAll(registry)
        assertEquals("- todo\n- see [pasta](../Recipes/Pasta/_node.md)\n", disk("Notes/_node.md"))
    }

    @Test
    fun a_link_to_a_child_node_at_the_end_of_a_bullet_stays_a_link() = runTest {
        seed("_node.md", "- Recipes [↳](<Recipes/_node.md>)\n- see [r](./Recipes/_node.md)\n")
        seed("Recipes/_node.md", "- x\n")
        val registry = DocumentRegistry(repo, backgroundScope)
        val p = pane(registry)
        assertEquals(listOf("* Recipes", "* see [r](./Recipes/_node.md)"), p.lines)
        p.moveTo(0, p.lines[0].length)
        p.insertText("!")
        saveAll(registry)
        assertEquals("- Recipes! [↳](<Recipes!/_node.md>)\n- see [r](./Recipes!/_node.md)\n", disk("_node.md"))
    }

    @Test
    fun the_migration_writes_every_old_link_relative_and_keeps_the_originals() = runTest {
        seed("_node.md", "- Recipes [↳](<Recipes/_node.md>)\n- see [r](lunarbor:/Recipes)\n")
        seed("Recipes/_node.md", "- Pasta [↳](<Pasta/_node.md>)\n- [home](lunarbor:/) [p](lunarbor:/Recipes/Pasta)\n> block [n](lunarbor:/Notes/Plan.md)\n")
        seed("Recipes/Pasta/_node.md", "- x\n")
        seed("Notes/Plan.md", "See [pasta](lunarbor:/Recipes/Pasta) and ![img](lunarbor:/Recipes/a.png)\n")
        seed(NoteRepository.STARRED_FILE_NAME, "* [Pasta](lunarbor:/Recipes/Pasta)\n")
        val result = repo.migrateRelativeLinks()
        assertEquals(4, result.rewrittenFiles.size)
        assertEquals("- Recipes [↳](<Recipes/_node.md>)\n- see [r](./Recipes/_node.md)\n", disk("_node.md"))
        assertEquals(
            "- Pasta [↳](<Pasta/_node.md>)\n- [home](../_node.md) [p](./Pasta/_node.md)\n> block [n](../Notes/Plan.md)\n",
            disk("Recipes/_node.md"),
        )
        // Images are not links here and keep their own rules.
        assertEquals("See [pasta](../Recipes/Pasta/_node.md) and ![img](lunarbor:/Recipes/a.png)\n", disk("Notes/Plan.md"))
        assertEquals("* [Pasta](Recipes/Pasta/_node.md)\n", disk(NoteRepository.STARRED_FILE_NAME))
        // Every node still loads as it did: the converted links are content.
        assertEquals(2, repo.nodeItemsOf("").size)
        val backup = assertNotNull(result.backupFolder)
        assertTrue(fs.files["$root/$backup/_node.md"]!!.contains("lunarbor:/Recipes"))
        // Running it again changes nothing.
        assertEquals(0, repo.migrateRelativeLinks().rewrittenFiles.size)
    }
}
