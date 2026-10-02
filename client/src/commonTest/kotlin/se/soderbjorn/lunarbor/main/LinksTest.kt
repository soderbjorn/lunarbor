/*
 * LinksTest.kt (commonTest)
 * -------------------------
 * Tests for TRF-8 "Links and starred items on folder paths", against the
 * real stack — [PaneBackingViewModel] / [Document] over [DocumentRegistry]
 * + [NoteRepository] on [InMemoryFileSystem]. Pins the ticket's "done
 * when" list:
 *
 *  - renaming a node, moving it under another parent, or renaming its
 *    parent keeps every link and Starred entry pointing at it (or at files
 *    inside it) working after the next save — in closed files on disk and
 *    in open documents alike;
 *  - "Link to node…" searches the whole vault and a link to a node under
 *    `Private/`, clicked from under `Work/`, zooms there;
 *  - the search never offers a leaf bullet or an empty folder;
 *  - a link whose target was moved outside the app shows as broken and
 *    stays in the text.
 */

package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.data.LunarborLink
import se.soderbjorn.lunarbor.data.VaultEntryKind
import se.soderbjorn.lunarbor.testing.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LinksTest {

    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })

    private lateinit var registry: DocumentRegistry

    private suspend fun seed(rel: String, content: String) = fs.writeFile("$root/$rel", content)

    private fun read(rel: String): String? = fs.read(root, rel)

    private fun dirExists(rel: String): Boolean = "$root/$rel" in fs.dirs

    private fun TestScope.newRegistry(): DocumentRegistry {
        registry = DocumentRegistry(repo, backgroundScope)
        return registry
    }

    /** Opens a pane on [fileRel] and waits for it to load. */
    private suspend fun TestScope.pane(fileRel: String = "_node.md"): PaneBackingViewModel {
        if (!::registry.isInitialized) newRegistry()
        val pane = PaneBackingViewModel(registry, backgroundScope, fileRel)
        pane.stateFlow.first { it.isLoaded }
        runCurrent()
        return pane
    }

    /** Opens [fileRel] as a bare document through the registry. */
    private suspend fun TestScope.doc(fileRel: String = "_node.md"): Document {
        if (!::registry.isInitialized) newRegistry()
        val d = registry.acquire(fileRel)
        d.stateFlow.first { it.isLoaded }
        return d
    }

    /** Saves [pane]'s document, then any follow-up save the link rewrite caused. */
    private suspend fun TestScope.flush(pane: PaneBackingViewModel) {
        val file = pane.stateFlow.value.activeFileRel
        val d = registry.acquire(file)
        d.flush()
        runCurrent()
        d.flush()
        registry.release(file)
        runCurrent()
    }

    private val PaneBackingViewModel.lines get() = stateFlow.value.lines
    private fun PaneBackingViewModel.id(row: Int) = stateFlow.value.documentState!!.lineIds[row]
    private fun Document.lines() = stateFlow.value.lines
    private fun Document.id(row: Int) = stateFlow.value.lineIds[row]

    private fun Document.setLine(row: Int, text: String) {
        delete(row, 0, row, lines()[row].length)
        insertText(row, 0, text)
    }

    /** A vault with a node, a file inside it, and links to both from everywhere. */
    private suspend fun seedRecipes() {
        seed("_node.md", "- Recipes [↳](<Recipes/_node.md>)\n- Notes [↳](<Notes/_node.md>)\n- Root sees [soups](lunarbor:/Recipes/Soups)\n")
        seed("Recipes/_node.md", "- Soups [↳](<Soups/_node.md>)\n- Cakes\n")
        seed("Recipes/Soups/_node.md", "- Tomato\n")
        fs.writeBinary("$root/Recipes/Soups/granola.jpg", byteArrayOf(1))
        seed("Notes/_node.md", "- Photo: [granola](lunarbor:/Recipes/Soups/granola.jpg)\n")
        seed("Notes/Plan.md", "Plan: see [soups](lunarbor:/Recipes/Soups) and [it](<lunarbor:/Recipes/Soups/granola.jpg>).\n")
        seed(
            NoteRepository.STARRED_FILE_NAME,
            "* [Soups](lunarbor:/Recipes/Soups)\n* [Granola](lunarbor:/Recipes/Soups/granola.jpg)\n",
        )
    }

    // ----------------------------------------------------------- the rewrite

    @Test
    fun renaming_a_node_rewrites_links_and_starred_entries_everywhere() = runTest {
        seedRecipes()
        val p = pane("Recipes/_node.md")
        val d = doc("Recipes/_node.md")
        d.setLine(0, "* Soup stock")
        flush(p)

        assertTrue(dirExists("Recipes/Soup stock"))
        assertFalse(dirExists("Recipes/Soups"))
        assertEquals(
            "- Recipes [↳](<Recipes/_node.md>)\n- Notes [↳](<Notes/_node.md>)\n- Root sees [soups](lunarbor:/Recipes/Soup%20stock)\n",
            read("_node.md"),
        )
        assertEquals("- Photo: [granola](lunarbor:/Recipes/Soup%20stock/granola.jpg)\n", read("Notes/_node.md"))
        assertEquals(
            "Plan: see [soups](lunarbor:/Recipes/Soup%20stock) and [it](<lunarbor:/Recipes/Soup%20stock/granola.jpg>).\n",
            read("Notes/Plan.md"),
        )
        assertEquals(
            "* [Soups](lunarbor:/Recipes/Soup%20stock)\n* [Granola](lunarbor:/Recipes/Soup%20stock/granola.jpg)\n",
            read(NoteRepository.STARRED_FILE_NAME),
        )
        registry.release("Recipes/_node.md")
    }

    @Test
    fun renaming_the_parent_rewrites_links_through_it_to_nested_folders_and_files() = runTest {
        seedRecipes()
        val p = pane()
        val d = doc()
        d.setLine(0, "* Food")
        flush(p)

        assertTrue(dirExists("Food/Soups"))
        assertTrue(p.lines.contains("* Root sees [soups](lunarbor:/Food/Soups)"), p.lines.toString())
        assertEquals(
            "- Food [↳](<Food/_node.md>)\n- Notes [↳](<Notes/_node.md>)\n- Root sees [soups](lunarbor:/Food/Soups)\n",
            read("_node.md"),
        )
        assertEquals("- Photo: [granola](lunarbor:/Food/Soups/granola.jpg)\n", read("Notes/_node.md"))
        assertEquals(
            "* [Soups](lunarbor:/Food/Soups)\n* [Granola](lunarbor:/Food/Soups/granola.jpg)\n",
            read(NoteRepository.STARRED_FILE_NAME),
        )
        registry.release("_node.md")
    }

    @Test
    fun moving_a_node_under_another_parent_rewrites_links_in_open_and_closed_files() = runTest {
        seed("_node.md", "- Work [↳](<Work/_node.md>)\n- Private [↳](<Private/_node.md>)\n")
        seed("Work/_node.md", "- Plan [↳](<Plan/_node.md>)\n- See [plan](lunarbor:/Work/Plan)\n")
        seed("Work/Plan/_node.md", "- step one\n")
        seed("Private/_node.md", "- Health\n")
        seed("Elsewhere.md", "[plan](lunarbor:/Work/Plan)\n")
        val p = pane()
        val d = doc()
        // Expand Work and Private so the drag happens in one document.
        d.acquireExpansion(d.id(0))
        val privateRow = d.lines().indexOf("* Private")
        d.acquireExpansion(d.id(privateRow))
        assertEquals(
            listOf("* Work", "  * Plan", "  * See [plan](lunarbor:/Work/Plan)", "* Private", "  * Health"),
            d.lines(),
        )
        // Drag "Plan" (collapsed) under Private, after Health.
        d.moveRows(1, 1, d.lines().size, listOf("  * Plan"))
        flush(p)

        assertTrue(dirExists("Private/Plan"))
        assertFalse(dirExists("Work/Plan"))
        // The link lives in Work's outline, which the open root document
        // holds in memory: rewritten there and saved with it.
        assertEquals("- See [plan](lunarbor:/Private/Plan)\n", read("Work/_node.md"))
        assertEquals("[plan](lunarbor:/Private/Plan)\n", read("Elsewhere.md"))
        registry.release("_node.md")
    }

    @Test
    fun a_closed_file_moved_with_its_folder_is_still_rewritten() = runTest {
        // The file holding the link sits inside the folder that moves.
        seed("_node.md", "- A [↳](<A/_node.md>)\n")
        seed("A/_node.md", "- B [↳](<B/_node.md>)\n")
        seed("A/B/_node.md", "- self [b](lunarbor:/A/B) and [c](lunarbor:/A/C.md)\n")
        seed("A/C.md", "c")
        val p = pane()
        // Build the link index before the move, so the moved-along key matters.
        registry.vaultIndex.ensureLinkIndex()
        assertEquals(setOf("A/B", "A/C.md"), registry.vaultIndex.linksIn("A/B/_node.md"))
        doc().setLine(0, "* Z")
        flush(p)
        assertEquals("- self [b](lunarbor:/Z/B) and [c](lunarbor:/Z/C.md)\n", read("Z/B/_node.md"))
        assertEquals(setOf("Z/B", "Z/C.md"), registry.vaultIndex.linksIn("Z/B/_node.md"))
        registry.release("_node.md")
    }

    @Test
    fun deleting_a_node_leaves_links_to_it_untouched_and_broken() = runTest {
        seedRecipes()
        // Without the image the folder holds only bullets and goes to the
        // trash whole.
        fs.deleteFile("$root/Recipes/Soups/granola.jpg")
        val p = pane("Recipes/_node.md")
        val d = doc("Recipes/_node.md")
        d.deleteLine(0)
        flush(p)
        assertFalse(dirExists("Recipes/Soups"))
        assertTrue(read(NoteRepository.STARRED_FILE_NAME)!!.contains("lunarbor:/Recipes/Soups)"))
        assertEquals("- Photo: [granola](lunarbor:/Recipes/Soups/granola.jpg)\n", read("Notes/_node.md"))
        registry.release("Recipes/_node.md")
    }

    @Test
    fun deleting_a_node_whose_folder_holds_an_image_keeps_links_to_the_image_working() = runTest {
        seedRecipes()
        val p = pane("Recipes/_node.md")
        val d = doc("Recipes/_node.md")
        d.deleteLine(0)
        flush(p)
        // Only the bullets went to the trash; the image stayed put, so the
        // links to it (and to its folder) still resolve, unchanged.
        assertTrue(dirExists("Recipes/Soups"))
        assertNull(read("Recipes/Soups/_node.md"))
        assertEquals("- Photo: [granola](lunarbor:/Recipes/Soups/granola.jpg)\n", read("Notes/_node.md"))
        assertFalse(p.isLinkBroken(p.stateFlow.value, "lunarbor:/Recipes/Soups/granola.jpg"))
        runCurrent()
        assertFalse(p.isLinkBroken(p.stateFlow.value, "lunarbor:/Recipes/Soups/granola.jpg"))
        registry.release("Recipes/_node.md")
    }

    // ---------------------------------------------------------- broken links

    @Test
    fun a_link_whose_target_moved_in_finder_shows_as_broken_and_stays() = runTest {
        seedRecipes()
        val p = pane()
        val href = "lunarbor:/Recipes/Soups"
        assertFalse(p.isLinkBroken(p.stateFlow.value, href))
        runCurrent()
        assertEquals(true, p.stateFlow.value.linkStatus["Recipes/Soups"])
        assertFalse(p.isLinkBroken(p.stateFlow.value, href))

        // Moved outside the app; the app notices at its next refresh
        // (window focus or any save).
        fs.moveDirectory("$root/Recipes/Soups", "$root/Recipes/Moved away")
        registry.refreshVaultListings()
        runCurrent()
        assertTrue(p.isLinkBroken(p.stateFlow.value, href))

        // Clicking it does nothing, and the text is never touched.
        p.navigateToLink(href)
        runCurrent()
        assertEquals("_node.md", p.stateFlow.value.activeFileRel)
        flush(p)
        assertTrue(read("_node.md")!!.contains("[soups](lunarbor:/Recipes/Soups)"))
        p.isLinkBroken(p.stateFlow.value, "lunarbor:/nowhere/at%20all")
        runCurrent()
        assertTrue(p.isLinkBroken(p.stateFlow.value, "lunarbor:/nowhere/at%20all"))
        assertFalse(p.isLinkBroken(p.stateFlow.value, "https://example.com"))
    }

    // ------------------------------------------------------------ the search

    @Test
    fun search_covers_the_whole_vault_and_never_offers_leaves_or_empty_folders() = runTest {
        seed("_node.md", "- Work [↳](<Work/_node.md>)\n- Private [↳](<Private/_node.md>)\n- Loose leaf idea\n")
        seed("Work/_node.md", "- Project **X** [↳](<Project X/_node.md>)\n")
        seed("Work/Project X/_node.md", "- Leaf task\n")
        seed("Private/_node.md", "- Doctor [↳](<Doctor/_node.md>)\n")
        seed("Private/Doctor/_node.md", "- Call\n")
        seed("Private/Budget 2027.md", "# Budget")
        fs.ensureDirectory("$root/Private/Empty folder")
        fs.writeBinary("$root/Foreign/scan.pdf", byteArrayOf(1))
        val p = pane("Work/Project X/_node.md")
        val index = p.vaultIndex

        val doctor = index.search("doc").single()
        assertEquals("Private/Doctor", doctor.pathRel)
        assertEquals(VaultEntryKind.FOLDER, doctor.kind)
        assertEquals(listOf("Private"), doctor.crumbs)
        // The bullet's plain-text title, not its Markdown.
        assertEquals("Project X", index.search("project").single().title)
        assertEquals("Private/Budget 2027.md", index.search("budget").single().pathRel)
        assertEquals("Foreign/scan.pdf", index.search("scan").single().pathRel)
        assertEquals("Foreign", index.search("foreign").single().pathRel)
        // Leaf bullets have no folder; empty folders are skipped.
        assertTrue(index.search("leaf").isEmpty())
        assertTrue(index.search("call").isEmpty())
        assertTrue(index.search("empty").isEmpty())
        assertTrue(index.search("   ").isEmpty())
    }

    @Test
    fun a_bullet_that_just_got_children_is_found_once_the_search_is_prepared() = runTest {
        seed("_node.md", "- Garden\n- Roses\n")
        val p = pane()
        assertTrue(p.vaultIndex.search("garden").isEmpty())
        p.moveTo(1, 2)
        p.indentLine()
        p.prepareLinkSearch()
        assertEquals("Garden", p.vaultIndex.search("garden").single().pathRel)
    }

    // ------------------------------------------------- insert and navigate

    @Test
    fun link_to_node_from_under_work_links_a_node_under_private_and_the_click_zooms_there() = runTest {
        seed("_node.md", "- Work [↳](<Work/_node.md>)\n- Private [↳](<Private/_node.md>)\n")
        seed("Work/_node.md", "- Meeting notes\n")
        seed("Private/_node.md", "- Health [↳](<Health/_node.md>)\n")
        seed("Private/Health/_node.md", "- Doctor [↳](<Doctor/_node.md>)\n")
        seed("Private/Health/Doctor/_node.md", "- Call on Monday\n")
        val p = pane()
        // Zoom into Work.
        p.zoomInto(0)
        runCurrent()
        val work = p.id(0)
        assertEquals(work, p.stateFlow.value.zoomedLineId)
        val meeting = p.lines.indexOf("  * Meeting notes")
        p.moveTo(meeting, p.lines[meeting].length)

        p.prepareLinkSearch()
        val hit = p.vaultIndex.search("doctor").single()
        p.insertText(" with ")
        p.insertLinkTo(hit)
        assertEquals("  * Meeting notes with [Doctor](lunarbor:/Private/Health/Doctor)", p.lines[meeting])

        p.navigateToLink("lunarbor:/Private/Health/Doctor")
        runCurrent()
        val s = p.stateFlow.value
        assertEquals("_node.md", s.activeFileRel)
        val zoomed = assertNotNull(s.zoomedLineId)
        val row = s.documentState!!.lineIds.indexOf(zoomed)
        assertEquals("    * Doctor", s.lines[row])
        assertTrue(s.lines.contains("      * Call on Monday"), s.lines.toString())
        // Back returns to Work.
        p.zoomBack()
        runCurrent()
        assertEquals(work, p.stateFlow.value.zoomedLineId)
    }

    @Test
    fun a_link_from_another_outline_opens_the_parent_node_zoomed_into_the_bullet() = runTest {
        seed("_node.md", "- Work [↳](<Work/_node.md>)\n- Private [↳](<Private/_node.md>)\n")
        seed("Work/_node.md", "- Meeting\n")
        seed("Private/_node.md", "- Health\n- Doctor [↳](<Doctor/_node.md>)\n")
        seed("Private/Doctor/_node.md", "- Call\n")
        val p = pane("Work/_node.md")
        p.navigateToLink("lunarbor:/Private/Doctor")
        runCurrent()
        val s = p.stateFlow.value
        assertEquals("Private/_node.md", s.activeFileRel)
        val row = s.documentState!!.lineIds.indexOf(assertNotNull(s.zoomedLineId))
        assertEquals("* Doctor", s.lines[row])
        assertEquals(listOf("Work/_node.md"), s.fileHistory.map { it.fileRel })
    }

    @Test
    fun links_to_foreign_folders_notes_images_and_other_files_open_them() = runTest {
        seed("_node.md", "- hi\n")
        seed("Foreign/readme.md", "hello")
        fs.writeBinary("$root/Foreign/photo.png", byteArrayOf(1))
        fs.writeBinary("$root/Foreign/scan.pdf", byteArrayOf(1))
        val p = pane()

        p.navigateToLink("lunarbor:/Foreign")
        runCurrent()
        assertEquals("Foreign/_node.md", p.stateFlow.value.activeFileRel)

        p.navigateToLink("lunarbor:/Foreign/readme.md")
        runCurrent()
        assertEquals("Foreign/readme.md", p.stateFlow.value.activeFileRel)

        p.navigateToLink(LunarborLink.format("Foreign/photo.png"))
        runCurrent()
        assertTrue(p.stateFlow.value.isImageView)

        val external = ArrayList<String>()
        p.navigateToLink("lunarbor:/Foreign/scan.pdf", openExternally = { external += it })
        runCurrent()
        assertEquals(listOf("Foreign/scan.pdf"), external)
        assertEquals("Foreign/photo.png", p.stateFlow.value.activeFileRel)
    }

    // --------------------------------------------------------------- starred

    @Test
    fun starring_stores_the_tf_path_of_the_current_node() = runTest {
        seed("_node.md", "- Recipes [↳](<Recipes/_node.md>)\n")
        seed("Recipes/_node.md", "- Soups [↳](<Soups/_node.md>)\n")
        seed("Recipes/Soups/_node.md", "- Tomato\n")
        val p = pane()
        p.navigateToLink("lunarbor:/Recipes/Soups")
        runCurrent()
        assertEquals("Recipes/Soups", p.currentLocationPath())
        p.toggleStarred(starred = false)
        assertEquals("* [Soups](lunarbor:/Recipes/Soups)\n", read(NoteRepository.STARRED_FILE_NAME))
        // The registry saw the write: a rename rewrites the new entry too.
        val d = doc()
        val row = d.lines().indexOf("  * Soups")
        d.setLine(row, "  * Broths")
        flush(p)
        assertEquals("* [Soups](lunarbor:/Recipes/Broths)\n", read(NoteRepository.STARRED_FILE_NAME))
        p.toggleStarred(starred = true)
        assertEquals("", read(NoteRepository.STARRED_FILE_NAME))
        registry.release("_node.md")
    }

    @Test
    fun starred_md_is_hidden_from_the_folder_listing_and_search_but_a_nested_one_is_not() = runTest {
        seed("_node.md", "- Recipes [↳](<Recipes/_node.md>)\n")
        seed("Recipes/_node.md", "- Tomato\n")
        seed(NoteRepository.STARRED_FILE_NAME, "* [Recipes](lunarbor:/Recipes)\n")
        seed("Plan.md", "x\n")
        seed("Recipes/Starred.md", "x\n")
        assertEquals(listOf("Plan.md"), repo.listVaultLevel("").filter { !it.isDirectory }.map { it.pathRel })
        assertEquals(listOf("Recipes/Starred.md"), repo.listVaultLevel("Recipes").map { it.pathRel })
        val targets = repo.listLinkTargets().map { it.pathRel }
        assertFalse(NoteRepository.STARRED_FILE_NAME in targets)
        assertTrue("Plan.md" in targets)
        assertTrue("Recipes/Starred.md" in targets)
    }
}
