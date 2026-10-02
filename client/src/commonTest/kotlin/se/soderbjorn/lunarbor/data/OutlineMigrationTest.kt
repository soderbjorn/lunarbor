/*
 * OutlineMigrationTest.kt (commonTest)
 * NoteRepository.migrateLegacyOutlines: a vault written before `_node.md`
 * is converted in place, the old files kept in the trash, and a converted
 * vault loads exactly as it did. Also NoteRepository.currentPathOf, which
 * maps remembered window locations.
 */

package se.soderbjorn.lunarbor.data

import kotlinx.coroutines.test.runTest
import se.soderbjorn.lunarbor.testing.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutlineMigrationTest {

    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })

    private suspend fun seed(rel: String, content: String) = fs.writeFile("$root/$rel", content)
    private suspend fun read(rel: String): String? = fs.readFileIfExists("$root/$rel")

    /** Everything under `.trash/<stamp> format migration/`, by path below it. */
    private suspend fun backups(): Map<String, String> {
        val dir = fs.listDirectory("$root/.trash").single { it.endsWith("format migration") }
        val out = HashMap<String, String>()
        suspend fun walk(rel: String) {
            for (e in fs.listDirectoryEntries("$root/.trash/$dir/$rel".removeSuffix("/"))) {
                val child = if (rel.isEmpty()) e.name else "$rel/${e.name}"
                if (e.isDirectory) walk(child) else out[child] = read(".trash/$dir/$child")!!
            }
        }
        walk("")
        return out
    }

    @Test
    fun a_legacy_vault_is_converted_and_the_old_files_are_kept_in_the_trash() = runTest {
        val rootLegacy = "* Milk\n+ [Recipes](Recipes)\n:::\n**Packing**: passport\n:::\n"
        val soupsLegacy = "* Tomato\n::: [Cold soups](Cold soups)\nCold soups\n:::\n"
        seed("node.lunarbor", rootLegacy)
        seed("Recipes/node.lunarbor", "+ [Soups](Soups)\n* 1. Pasta\n")
        seed("Recipes/Soups/node.lunarbor", soupsLegacy)
        seed("Recipes/Soups/Cold soups/node.lunarbor", "* Gazpacho\n")
        seed("Recipes/notes.md", "# Notes\n")

        assertEquals(4, repo.migrateLegacyOutlines())

        assertEquals("- Milk\n- Recipes [↳](<Recipes/_node.md>)\n> **Packing**: passport\n", read("_node.md"))
        assertEquals("- Soups [↳](<Soups/_node.md>)\n- 1\\. Pasta\n", read("Recipes/_node.md"))
        assertEquals("- Tomato\n> Cold soups\n> [↳](<Cold soups/_node.md>)\n", read("Recipes/Soups/_node.md"))
        assertEquals("- Gazpacho\n", read("Recipes/Soups/Cold soups/_node.md"))
        assertEquals("# Notes\n", read("Recipes/notes.md"))
        for (old in listOf("node.lunarbor", "Recipes/node.lunarbor", "Recipes/Soups/node.lunarbor")) {
            assertNull(read(old), old)
        }
        assertEquals(
            mapOf(
                "node.lunarbor" to rootLegacy,
                "Recipes/node.lunarbor" to "+ [Soups](Soups)\n* 1. Pasta\n",
                "Recipes/Soups/node.lunarbor" to soupsLegacy,
                "Recipes/Soups/Cold soups/node.lunarbor" to "* Gazpacho\n",
            ),
            backups(),
        )
    }

    @Test
    fun the_converted_vault_loads_the_same_outline() = runTest {
        val legacy = "* Milk\n+ [Recipes](Recipes)\n:::\n## Packing\n* passport\n:::\n"
        seed("node.lunarbor", legacy)
        seed("Recipes/node.lunarbor", "* Pasta\n")
        // What the old app showed for the root: its legacy items, composed.
        val before = SubtreeCodec.composeNodeLines(SubtreeCodec.parseLegacyNodeFile(legacy), indent = 0)
        repo.migrateLegacyOutlines()
        val after = repo.loadFile(NoteRepository.OUTLINE_FILE_NAME)
        assertEquals(before.lines, after.lines)
        assertEquals(before.folderByRow, after.promotedByRow.mapValues { it.value.folderRel })
    }

    @Test
    fun a_second_run_does_nothing_and_a_user_note_with_the_new_name_steps_aside() = runTest {
        seed("node.lunarbor", "* A\n")
        seed("_node.md", "my own note\n")
        assertEquals(1, repo.migrateLegacyOutlines())
        assertEquals("- A\n", read("_node.md"))
        assertEquals("my own note\n", read("_node (2).md"))
        assertEquals(0, repo.migrateLegacyOutlines())
        assertEquals("- A\n", read("_node.md"))
    }

    @Test
    fun the_trash_is_never_migrated() = runTest {
        seed(".trash/2026-01-01 Old/node.lunarbor", "* old\n")
        assertEquals(0, repo.migrateLegacyOutlines())
        assertTrue(read(".trash/2026-01-01 Old/node.lunarbor") != null)
    }

    @Test
    fun remembered_paths_to_legacy_outlines_map_to_the_new_name() {
        assertEquals("_node.md", NoteRepository.currentPathOf("node.lunarbor"))
        assertEquals("Recipes/Soups/_node.md", NoteRepository.currentPathOf("Recipes/Soups/node.lunarbor"))
        assertEquals("Recipes/notes.md", NoteRepository.currentPathOf("Recipes/notes.md"))
    }
}
