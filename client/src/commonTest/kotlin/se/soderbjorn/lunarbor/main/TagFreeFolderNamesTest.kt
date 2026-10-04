/*
 * TagFreeFolderNamesTest.kt (commonTest)
 * --------------------------------------
 * Pins the one-time vault migration that names node folders without the
 * item's `#tags` (`NoteRepository.migrateTagFreeFolderNames`): folders named
 * by the old rule are renamed, nested renames compose, `lunarbor:` links and
 * Starred follow, originals are kept in the trash, folders named any other
 * way are left alone, and a second run changes nothing. Also checks that a
 * save after the migration keeps the new names.
 */

package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.test.runTest
import se.soderbjorn.lunarbor.data.LunarborLink
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.data.PathMove
import se.soderbjorn.lunarbor.testing.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TagFreeFolderNamesTest {

    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })
    private val backup = ".trash/1970-01-01 00.00.00 format migration"

    private suspend fun seed(rel: String, content: String) {
        val dir = rel.substringBeforeLast('/', "")
        fs.ensureDirectory(if (dir.isEmpty()) root else "$root/$dir")
        fs.writeFile("$root/$rel", content)
    }

    private fun read(rel: String): String? = fs.read(root, rel)

    @Test
    fun tagged_folders_are_renamed_nested_and_links_follow() = runTest {
        seed("_node.md", "- Main [↳](<Main/_node.md>)\n")
        seed("Main/_node.md", "- 1-1 #sensitive [↳](<1-1 #sensitive/_node.md>)\n- Plain [↳](<Plain/_node.md>)\n")
        seed("Main/1-1 #sensitive/_node.md", "- Meet #x Bob [↳](<Meet #x Bob/_node.md>)\n")
        seed("Main/1-1 #sensitive/Meet #x Bob/_node.md", "- note\n")
        seed("Main/1-1 #sensitive/Meet #x Bob/shot.png", "png")
        seed("Main/Plain/_node.md", "- See [it](lunarbor:/Main/1-1%20%23sensitive/Meet%20%23x%20Bob)\n")
        seed("Starred.md", "* [1-1](lunarbor:/Main/1-1%20%23sensitive)\n")

        val result = repo.migrateTagFreeFolderNames()

        assertEquals(
            listOf(
                PathMove("Main/1-1 #sensitive", "Main/1-1"),
                PathMove("Main/1-1 #sensitive/Meet #x Bob", "Main/1-1/Meet Bob"),
            ),
            result.moves,
        )
        assertEquals("- 1-1 #sensitive [↳](<1-1/_node.md>)\n- Plain [↳](<Plain/_node.md>)\n", read("Main/_node.md"))
        assertEquals("- Meet #x Bob [↳](<Meet Bob/_node.md>)\n", read("Main/1-1/_node.md"))
        assertEquals("png", read("Main/1-1/Meet Bob/shot.png"))
        assertNull(read("Main/1-1 #sensitive/_node.md"))
        assertEquals("- See [it](lunarbor:/Main/1-1/Meet%20Bob)\n", read("Main/Plain/_node.md"))
        assertEquals("* [1-1](lunarbor:/Main/1-1)\n", read("Starred.md"))
        // Originals kept.
        assertEquals(
            "- 1-1 #sensitive [↳](<1-1 #sensitive/_node.md>)\n- Plain [↳](<Plain/_node.md>)\n",
            read("$backup/Main/_node.md"),
        )
        assertEquals("* [1-1](lunarbor:/Main/1-1%20%23sensitive)\n", read("$backup/Starred.md"))
        // Remembered paths map through the moves.
        assertEquals("Main/1-1/Meet Bob/_node.md", LunarborLink.remap("Main/1-1 #sensitive/Meet #x Bob/_node.md", result.moves))
        assertEquals("Main/1-1/_node.md", LunarborLink.remap("Main/1-1 #sensitive/_node.md", result.moves))
    }

    @Test
    fun a_taken_name_gets_a_suffix_and_other_names_are_left_alone() = runTest {
        seed("_node.md", "- A #t [↳](<A #t/_node.md>)\n- B #t [↳](<Hand named/_node.md>)\n")
        seed("A #t/_node.md", "- x\n")
        seed("A/notes.md", "loose")
        seed("Hand named/_node.md", "- y\n")

        val result = repo.migrateTagFreeFolderNames()

        assertEquals(listOf(PathMove("A #t", "A (2)")), result.moves)
        assertEquals("- A #t [↳](<A (2)/_node.md>)\n- B #t [↳](<Hand named/_node.md>)\n", read("_node.md"))
        assertEquals("loose", read("A/notes.md"))
        assertEquals("- y\n", read("Hand named/_node.md"))
    }

    @Test
    fun a_second_run_and_an_untagged_vault_change_nothing() = runTest {
        seed("_node.md", "- A #t [↳](<A #t/_node.md>)\n- B [↳](<B/_node.md>)\n")
        seed("A #t/_node.md", "- x\n")
        seed("B/_node.md", "- y\n")
        repo.migrateTagFreeFolderNames()
        val before = fs.files.toMap()

        val again = repo.migrateTagFreeFolderNames()

        assertTrue(again.moves.isEmpty())
        assertTrue(again.rewrittenFiles.isEmpty())
        assertNull(again.backupFolder)
        assertEquals(before, fs.files.toMap())
    }

    @Test
    fun a_block_with_children_is_renamed_by_its_first_line() = runTest {
        seed("_node.md", "> Ideas #wip\n> more\n> [↳](<Ideas #wip/_node.md>)\n")
        seed("Ideas #wip/_node.md", "- child\n")

        val result = repo.migrateTagFreeFolderNames()

        assertEquals(listOf(PathMove("Ideas #wip", "Ideas")), result.moves)
        assertEquals("> Ideas #wip\n> more\n> [↳](<Ideas/_node.md>)\n", read("_node.md"))
        assertEquals("- child\n", read("Ideas/_node.md"))
    }
}
