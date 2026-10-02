/* VaultRelocationTest.kt (commonTest)
 *
 * Pins how pane locations follow a vault change ([VaultRelocation]):
 * rebased when inside the new root, dropped when outside. */
package se.soderbjorn.lunarbor.main

import se.soderbjorn.lunarbor.main.PaneBackingViewModel.FileHistoryEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VaultRelocationTest {

    private val old = "/Users/me/vault"

    @Test
    fun rebasePath_parentKeepsEverything() {
        assertEquals("vault/Recipes/_node.md", VaultRelocation.rebasePath(old, "/Users/me", "Recipes/_node.md"))
        assertEquals("vault", VaultRelocation.rebasePath(old, "/Users/me/", ""))
    }

    @Test
    fun rebasePath_subfolderKeepsWhatIsInside() {
        assertEquals("Soups/_node.md", VaultRelocation.rebasePath(old, "$old/Recipes", "Recipes/Soups/_node.md"))
        assertEquals("", VaultRelocation.rebasePath(old, "$old/Recipes", "Recipes"))
        assertNull(VaultRelocation.rebasePath(old, "$old/Recipes", "_node.md"))
    }

    @Test
    fun rebasePath_siblingWithSharedPrefixIsOutside() {
        assertNull(VaultRelocation.rebasePath(old, "/Users/me/vault2", "a.md"))
    }

    @Test
    fun rebasePath_filesystemRoot() {
        assertEquals("Users/me/vault/a.md", VaultRelocation.rebasePath(old, "/", "a.md"))
    }

    @Test
    fun relocate_keepsZoomWhenFileInside() {
        val loc = FileHistoryEntry("_node.md", listOf("Recipes", "Soups"))
        assertEquals(
            FileHistoryEntry("vault/_node.md", listOf("Recipes", "Soups")),
            VaultRelocation.relocate(old, "/Users/me", loc, "Recipes/Soups"),
        )
    }

    @Test
    fun relocate_fallsBackToZoomedFolder() {
        val loc = FileHistoryEntry("_node.md", listOf("Recipes", "Soups"))
        assertEquals(
            FileHistoryEntry("Soups/_node.md"),
            VaultRelocation.relocate(old, "$old/Recipes", loc, "Recipes/Soups"),
        )
        assertEquals(
            FileHistoryEntry("_node.md"),
            VaultRelocation.relocate(old, "$old/Recipes", loc, "Recipes"),
        )
    }

    @Test
    fun relocate_noteKeepsItsFile() {
        val loc = FileHistoryEntry("Recipes/Plan.md")
        assertEquals(FileHistoryEntry("Plan.md"), VaultRelocation.relocate(old, "$old/Recipes", loc, "Recipes/Plan.md"))
    }

    @Test
    fun relocate_outsideCloses() {
        val loc = FileHistoryEntry("Recipes/_node.md", listOf("Soups"))
        assertNull(VaultRelocation.relocate(old, "/Users/me/other", loc, "Recipes/Soups"))
        assertNull(VaultRelocation.relocate(old, "$old/Work", loc, null))
    }
}
