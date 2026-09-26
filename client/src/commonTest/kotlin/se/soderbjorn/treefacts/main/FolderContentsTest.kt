/*
 * FolderContentsTest.kt (commonTest)
 * ----------------------------------
 * Tests for TRF-6 "Folder contents list below the bullets". Pins the
 * ticket's "done when" list against the real stack — a
 * [PaneBackingViewModel] over a [DocumentRegistry] + [NoteRepository] on
 * [InMemoryFileSystem]:
 *
 *  - referenced folders, dotfiles and `.trash` never appear; everything
 *    else does, folders first, each group in natural, case-insensitive
 *    name order;
 *  - a file added behind the app's back shows up at the next refresh;
 *  - the count badge matches the listing;
 *
 * plus the "New Markdown file" command, opening a folder as a node, and
 * which folder a pane lists as it zooms.
 */

package se.soderbjorn.treefacts.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import se.soderbjorn.treefacts.data.NoteRepository
import se.soderbjorn.treefacts.data.VaultEntryKind
import se.soderbjorn.treefacts.testing.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FolderContentsTest {

    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })

    private lateinit var registry: DocumentRegistry

    private suspend fun seed(rel: String, content: String) = fs.writeFile("$root/$rel", content)

    /**
     * A vault whose `Recipes` node holds one of everything: a bullet with
     * its own folder (`Soups`), a foreign folder, a TreeFacts folder no
     * bullet references, an empty folder, notes that test natural order,
     * an image, another file, and a dotfile.
     */
    private suspend fun seedVault() {
        seed(".treefacts", "* Groceries\n+ [Recipes](Recipes)\n")
        seed("Recipes/.treefacts", "* Pasta\n+ [Soups](Soups)\n")
        seed("Recipes/Soups/.treefacts", "* Tomato\n")
        seed("Recipes/Note 10.md", "ten")
        seed("Recipes/Note 2.md", "two")
        seed("Recipes/photo.PNG", "img")
        seed("Recipes/data.csv", "a,b")
        seed("Recipes/.DS_Store", "")
        seed("Recipes/Foreign/readme.txt", "hi")
        seed("Recipes/Orphan/.treefacts", "* lost\n")
        fs.ensureDirectory("$root/Recipes/archive")
        seed(".trash/1970-01-01 00.00.00 Old/.treefacts", "* old\n")
        seed("Loose.md", "loose")
    }

    /** Opens a pane on the root outline and waits for it to load. */
    private suspend fun TestScope.pane(): PaneBackingViewModel {
        registry = DocumentRegistry(repo, backgroundScope)
        val pane = PaneBackingViewModel(registry, backgroundScope, ".treefacts")
        pane.stateFlow.first { it.isLoaded }
        runCurrent()
        return pane
    }

    /** The contents list of [folder] once its listing has been read. */
    private fun TestScope.listed(p: PaneBackingViewModel, folder: String): List<String> {
        p.folderContentsOf(p.stateFlow.value, folder)
        runCurrent()
        return p.folderContentsOf(p.stateFlow.value, folder)!!.map { it.name }
    }

    private val PaneBackingViewModel.lines get() = stateFlow.value.lines
    private fun PaneBackingViewModel.id(row: Int) = stateFlow.value.documentState!!.lineIds[row]

    // ---------------------------------------------------------- what shows

    @Test
    fun referenced_folders_and_dotfiles_are_hidden_and_the_rest_is_ordered() = runTest {
        seedVault()
        val p = pane()
        assertEquals(
            listOf("archive", "Foreign", "Orphan", "data.csv", "Note 2", "Note 10", "photo.PNG"),
            listed(p, "Recipes"),
        )
        val kinds = p.folderContentsOf(p.stateFlow.value, "Recipes")!!.map { it.kind }
        assertEquals(
            listOf(
                VaultEntryKind.FOLDER, VaultEntryKind.FOLDER, VaultEntryKind.FOLDER,
                VaultEntryKind.FILE, VaultEntryKind.MARKDOWN, VaultEntryKind.MARKDOWN, VaultEntryKind.IMAGE,
            ),
            kinds,
        )
    }

    @Test
    fun the_root_hides_its_bullets_folders_the_trash_and_its_outline() = runTest {
        seedVault()
        val p = pane()
        assertEquals(listOf("Loose"), listed(p, ""))
    }

    @Test
    fun a_folder_reference_matches_case_insensitively() = runTest {
        seed(".treefacts", "+ [Trip](trip)\n")
        seed("Trip/.treefacts", "* Pack\n")
        seed("Trip Notes/.treefacts", "* x\n")
        val p = pane()
        assertEquals(listOf("Trip Notes"), listed(p, ""))
    }

    @Test
    fun natural_order_is_numeric_and_case_insensitive() {
        val names = listOf("note 10", "Note 2", "note 1", "B", "a", "Note 02b", "Note 2a")
        assertEquals(
            listOf("a", "B", "note 1", "Note 2", "Note 2a", "Note 02b", "note 10"),
            names.sortedWith { x, y -> FolderContents.naturalCompare(x, y) },
        )
    }

    // ------------------------------------------------------- current node

    @Test
    fun the_listed_folder_follows_the_zoom() = runTest {
        seedVault()
        val p = pane()
        assertEquals("", p.currentNodeFolder())
        // Zoom into the folder-backed "Recipes": its folder is listed.
        p.zoomInto(1)
        runCurrent()
        assertEquals("Recipes", p.currentNodeFolder())
        // Zoom into the leaf "Groceries": no folder, no list.
        p.zoomOut()
        p.zoomInto(0)
        assertNull(p.currentNodeFolder())
    }

    @Test
    fun clicking_a_folder_opens_it_as_a_node() = runTest {
        seedVault()
        val p = pane()
        p.openFolderAsNode("Recipes/Foreign")
        p.stateFlow.first { it.activeFileRel == "Recipes/Foreign/.treefacts" && it.isLoaded }
        assertEquals("Recipes/Foreign", p.currentNodeFolder())
        assertEquals(listOf("readme.txt"), listed(p, "Recipes/Foreign"))
        // Opening a foreign folder does not make it a TreeFacts folder.
        runCurrent()
        assertNull(fs.read(root, "Recipes/Foreign/.treefacts"))
    }

    // ------------------------------------------------------------ refresh

    @Test
    fun a_file_added_outside_the_app_shows_at_the_next_refresh() = runTest {
        seedVault()
        val p = pane()
        assertEquals(listOf("Loose"), listed(p, ""))
        seed("From Finder.md", "new")
        registry.refreshVaultListings()
        runCurrent()
        assertEquals(listOf("From Finder", "Loose"), listed(p, ""))

        // Navigation re-reads the folder it lands on, too.
        seed("Zebra.pdf", "%PDF")
        p.refreshCurrentFolderListing()
        runCurrent()
        assertEquals(listOf("From Finder", "Loose", "Zebra.pdf"), listed(p, ""))
    }

    // -------------------------------------------------------------- badge

    @Test
    fun the_badge_counts_what_the_list_shows() = runTest {
        seedVault()
        val p = pane()
        val recipes = p.id(1)
        // Folded: no badge.
        assertTrue(recipes in p.stateFlow.value.collapsedIds)
        p.toggleCollapse(recipes)
        runCurrent()
        p.folderContentsOfBullet(p.stateFlow.value, recipes)
        runCurrent()
        val entries = p.folderContentsOfBullet(p.stateFlow.value, recipes)!!
        assertEquals(listed(p, "Recipes"), entries.map { it.name })
        assertEquals("3 folders, 4 files", FolderContents.badgeLabel(entries))
        // A leaf has no folder and so no badge.
        assertNull(p.folderContentsOfBullet(p.stateFlow.value, p.id(0)))
    }

    @Test
    fun badge_wording() {
        fun entries(folders: Int, files: Int) =
            List(folders) { se.soderbjorn.treefacts.data.VaultEntry("d$it", "d$it", VaultEntryKind.FOLDER) } +
                List(files) { se.soderbjorn.treefacts.data.VaultEntry("f$it", "f$it", VaultEntryKind.FILE) }
        assertNull(FolderContents.badgeLabel(emptyList()))
        assertEquals("1 file", FolderContents.badgeLabel(entries(0, 1)))
        assertEquals("3 files", FolderContents.badgeLabel(entries(0, 3)))
        assertEquals("1 folder", FolderContents.badgeLabel(entries(1, 0)))
        assertEquals("2 folders, 1 file", FolderContents.badgeLabel(entries(2, 1)))
    }

    // ------------------------------------------------- New Markdown file

    @Test
    fun new_markdown_file_creates_untitled_then_untitled_2_and_opens_it() = runTest {
        seedVault()
        val p = pane()
        p.zoomInto(1)
        runCurrent()
        p.newMarkdownFile()
        runCurrent()
        p.stateFlow.first { it.activeFileRel == "Recipes/Untitled.md" && it.isLoaded }
        assertEquals("", fs.read(root, "Recipes/Untitled.md"))

        // Back in Recipes, a second one gets the next free name.
        p.zoomBack()
        runCurrent()
        p.stateFlow.first { it.activeFileRel == ".treefacts" && it.isLoaded }
        runCurrent()
        p.zoomInto(1)
        runCurrent()
        assertEquals("Recipes", p.currentNodeFolder())
        p.newMarkdownFile()
        runCurrent()
        p.stateFlow.first { it.activeFileRel == "Recipes/Untitled 2.md" && it.isLoaded }
        val names = listed(p, "Recipes")
        assertTrue("Untitled" in names && "Untitled 2" in names)
    }

    @Test
    fun new_markdown_file_in_a_zoomed_leaf_does_nothing() = runTest {
        seedVault()
        val p = pane()
        p.zoomInto(0)
        p.newMarkdownFile()
        runCurrent()
        assertEquals(".treefacts", p.stateFlow.value.activeFileRel)
        assertTrue(fs.files.keys.none { it.endsWith("Untitled.md") })
    }

    @Test
    fun untitled_names_skip_taken_ones_case_insensitively() {
        assertEquals("Untitled.md", NoteRepository.untitledNoteName(emptySet()))
        assertEquals("Untitled 2.md", NoteRepository.untitledNoteName(setOf("untitled.md")))
        assertEquals("Untitled 3.md", NoteRepository.untitledNoteName(setOf("untitled.md", "untitled 2.md")))
    }
}
