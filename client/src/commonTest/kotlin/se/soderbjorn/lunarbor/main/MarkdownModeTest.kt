/*
 * MarkdownModeTest.kt (commonTest)
 * --------------------------------
 * Tests for TRF-7 "Markdown mode and opening files from the folder list",
 * against the real stack — [PaneBackingViewModel] over [DocumentRegistry]
 * + [NoteRepository] on [InMemoryFileSystem]. Pins the ticket's "done
 * when" list:
 *
 *  - editing a `.md` file saves it as plain Markdown, exactly as written,
 *    and no `_node.md` file (or folder) appears next to it;
 *  - Markdown mode has no bullet behaviour: no folding, zoom or dragging;
 *  - back/forward file history covers nodes, `.md` files and images;
 *  - a pasted image lands in the current node's folder, is referenced by
 *    bare file name and appears in the folder's contents list;
 *
 * plus the image path rules ([ImagePaths]) and images following their row
 * when a save moves it to another folder.
 */

package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import se.soderbjorn.lunarbor.data.ImagePaths
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.testing.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MarkdownModeTest {

    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })

    private lateinit var registry: DocumentRegistry

    private suspend fun seed(rel: String, content: String) = fs.writeFile("$root/$rel", content)

    private fun read(rel: String): String? = fs.read(root, rel)

    private fun dirExists(rel: String): Boolean = "$root/$rel" in fs.dirs

    /** Opens a pane on [fileRel] and waits for it to load. */
    private suspend fun TestScope.pane(fileRel: String = "_node.md"): PaneBackingViewModel {
        registry = DocumentRegistry(repo, backgroundScope)
        val pane = PaneBackingViewModel(registry, backgroundScope, fileRel)
        pane.stateFlow.first { it.isLoaded }
        runCurrent()
        return pane
    }

    /** Saves the document [pane] is viewing now. */
    private suspend fun TestScope.flush(pane: PaneBackingViewModel) {
        val file = pane.stateFlow.value.activeFileRel
        val doc = registry.acquire(file)
        doc.flush()
        registry.release(file)
        runCurrent()
    }

    /** Waits until [pane] shows [fileRel] (loaded, or as the image view). */
    private suspend fun TestScope.awaitFile(pane: PaneBackingViewModel, fileRel: String) {
        pane.stateFlow.first { it.activeFileRel == fileRel && (it.isLoaded || it.isImageView) }
        runCurrent()
    }

    private val PaneBackingViewModel.lines get() = stateFlow.value.lines
    private fun PaneBackingViewModel.id(row: Int) = stateFlow.value.documentState!!.lineIds[row]

    private val png = byteArrayOf(1, 2, 3)

    // ------------------------------------------------------ Markdown mode

    @Test
    fun a_md_file_saves_as_written_and_gets_no_outline_file() = runTest {
        seed("Notes/_node.md", "- Idea\n")
        seed("Notes/Plan.md", "# Plan\n\n* one\n  * two\n\n")
        val p = pane("Notes/Plan.md")
        assertTrue(p.stateFlow.value.isMarkdownMode)
        assertEquals(listOf("# Plan", "", "* one", "  * two", "", ""), p.lines)

        p.moveTo(2, p.lines[2].length)
        p.insertText("!")
        // A nested bullet under a leaf would promote it in an outline.
        p.moveTo(3, p.lines[3].length)
        p.insertNewline()
        p.insertText("three")
        flush(p)

        val saved = read("Notes/Plan.md")!!
        assertTrue(saved.startsWith("# Plan\n\n* one!\n  * two\n"), saved)
        assertTrue(saved.endsWith("three\n\n"), "trailing blank lines are kept: $saved")
        // No promotion: the outline next to it is untouched, no folder.
        assertEquals("- Idea\n", read("Notes/_node.md"))
        assertFalse(dirExists("Notes/one"))
        assertFalse(dirExists("Notes/Plan"))
    }

    @Test
    fun a_md_file_in_a_foreign_folder_never_gets_a_lunarbor_file() = runTest {
        seed("Foreign/readme.md", "hello")
        val p = pane("Foreign/readme.md")
        p.moveTo(0, 5)
        p.insertText(" world")
        flush(p)
        assertEquals("hello world", read("Foreign/readme.md"))
        assertTrue(fs.files.keys.none { it.endsWith("/_node.md") })
    }

    @Test
    fun markdown_mode_has_no_folding_zoom_or_dragging() = runTest {
        seed("Plan.md", "* one\n  * two\n* three\n")
        val p = pane("Plan.md")
        // Nothing starts folded.
        assertTrue(p.stateFlow.value.collapsedIds.isEmpty())
        p.toggleCollapse(p.id(0))
        assertTrue(p.stateFlow.value.collapsedIds.isEmpty())
        // No zoom.
        p.zoomInto(0)
        p.zoomTo(p.id(0))
        runCurrent()
        assertNull(p.stateFlow.value.zoomedLineId)
        assertNull(p.zoomInfo())
        // No row dragging.
        p.moveLineRange(0, 1, 3, 0)
        assertEquals(listOf("* one", "  * two", "* three", ""), p.lines)
    }

    @Test
    fun an_outline_is_not_in_markdown_mode() = runTest {
        seed("_node.md", "- a\n")
        val p = pane()
        assertFalse(p.stateFlow.value.isMarkdownMode)
    }

    // --------------------------------------------------------- history

    @Test
    fun back_and_forward_cover_nodes_notes_and_images() = runTest {
        seed("_node.md", "- Groceries\n")
        seed("Recipes/_node.md", "- Pasta\n")
        seed("Recipes/Note.md", "note")
        seed("Recipes/photo.png", "img")
        val p = pane()

        p.openFolderAsNode("Recipes")
        awaitFile(p, "Recipes/_node.md")
        p.navigateToVaultFile("Recipes/Note.md")
        awaitFile(p, "Recipes/Note.md")
        assertTrue(p.stateFlow.value.isMarkdownMode)
        p.navigateToVaultFile("Recipes/photo.png")
        awaitFile(p, "Recipes/photo.png")
        assertTrue(p.stateFlow.value.isImageView)

        p.zoomBack()
        awaitFile(p, "Recipes/Note.md")
        p.zoomBack()
        awaitFile(p, "Recipes/_node.md")
        p.zoomBack()
        awaitFile(p, "_node.md")
        assertFalse(p.canZoomBack())

        p.zoomForward()
        awaitFile(p, "Recipes/_node.md")
        p.zoomForward()
        awaitFile(p, "Recipes/Note.md")
        p.zoomForward()
        awaitFile(p, "Recipes/photo.png")
        assertFalse(p.canZoomForward())
    }

    @Test
    fun back_from_a_note_returns_to_the_zoomed_node_it_was_opened_from() = runTest {
        seed("_node.md", "- Groceries\n- Recipes [↳](<Recipes/_node.md>)\n")
        seed("Recipes/_node.md", "- Pasta\n- Soups [↳](<Soups/_node.md>)\n")
        seed("Recipes/Soups/_node.md", "- Tomato\n")
        seed("Recipes/Soups/Note.md", "note")
        val p = pane()
        // Zoom two levels deep: Recipes, then Soups (both folded refs).
        p.zoomInto(1)
        runCurrent()
        p.zoomInto(p.lines.indexOf("  * Soups"))
        runCurrent()
        assertEquals("Recipes/Soups", p.currentNodeFolder())

        p.navigateToVaultFile("Recipes/Soups/Note.md")
        // The root document is released here, so its line ids are gone.
        awaitFile(p, "Recipes/Soups/Note.md")

        p.zoomBack()
        awaitFile(p, "_node.md")
        p.stateFlow.first { it.zoomedLineId != null }
        runCurrent()
        assertEquals("Recipes/Soups", p.currentNodeFolder())
        assertEquals(listOf("Recipes", "Soups"), p.zoomPathSegments())

        // Forward goes to the note again, Back again to the zoomed node.
        p.zoomForward()
        awaitFile(p, "Recipes/Soups/Note.md")
        p.zoomBack()
        awaitFile(p, "_node.md")
        p.stateFlow.first { it.zoomedLineId != null }
        runCurrent()
        assertEquals("Recipes/Soups", p.currentNodeFolder())
    }

    // ------------------------------------------------------ pasted images

    @Test
    fun a_pasted_image_lands_in_the_current_nodes_folder_and_its_list() = runTest {
        seed("_node.md", "- Groceries\n- Recipes [↳](<Recipes/_node.md>)\n")
        seed("Recipes/_node.md", "- Pasta\n")
        val p = pane()
        p.zoomInto(1)
        runCurrent()
        assertEquals("Recipes", p.currentNodeFolder())
        p.moveTo(2, p.lines[2].length)
        p.onImagePasted("shot.png", png)
        runCurrent()

        assertNotNull(read("Recipes/shot.png"))
        assertNull(read("Images/shot.png"))
        assertEquals("  * Pasta![](shot.png)", p.lines[2])
        assertEquals("Recipes/shot.png", p.resolveImageSrc(2, "shot.png"))
        val names = p.folderContentsOf(p.stateFlow.value, "Recipes")!!.map { it.name }
        assertTrue("shot.png" in names, "$names")

        // A second paste of the same name does not overwrite the first.
        p.onImagePasted("shot.png", png)
        runCurrent()
        assertNotNull(read("Recipes/shot-2.png"))
        assertTrue(p.lines[2].endsWith("![](shot-2.png)"))
    }

    @Test
    fun a_dropped_image_gets_a_line_of_its_own_at_the_drop_row() = runTest {
        seed("_node.md", "- Buy oat milk\n- \n- Trip\n")
        val p = pane()
        // The caret mid-word elsewhere never splits that text.
        p.moveTo(2, 4)
        p.onImageDropped(0, "shot.png", png)
        runCurrent()
        assertEquals(listOf("* Buy oat milk", "* ![](shot.png)", "* ", "* Trip"), p.lines)

        // An empty row takes the image itself.
        p.onImageDropped(2, "pic.png", png)
        runCurrent()
        assertEquals(listOf("* Buy oat milk", "* ![](shot.png)", "* ![](pic.png)", "* Trip"), p.lines)
    }

    @Test
    fun a_pasted_image_at_the_root_lands_in_the_vault_root() = runTest {
        seed("_node.md", "- Groceries\n")
        val p = pane()
        p.moveTo(0, p.lines[0].length)
        p.onImagePasted("shot.png", png)
        runCurrent()
        assertNotNull(read("shot.png"))
        assertEquals(listOf("shot.png"), p.folderContentsOf(p.stateFlow.value, "")!!.map { it.name })
    }

    @Test
    fun a_pasted_image_in_a_md_note_lands_next_to_the_note() = runTest {
        seed("Recipes/_node.md", "- Pasta\n")
        seed("Recipes/Note.md", "Look:")
        val p = pane("Recipes/Note.md")
        p.moveTo(0, 5)
        p.onImagePasted("shot.png", png)
        flush(p)
        assertNotNull(read("Recipes/shot.png"))
        assertEquals("Look:![](shot.png)", read("Recipes/Note.md"))
        assertEquals("Recipes/shot.png", p.resolveImageSrc(0, "shot.png"))
    }

    @Test
    fun an_image_pasted_into_a_zoomed_leaf_follows_it_into_its_new_folder() = runTest {
        seed("_node.md", "- Groceries\n- Trip\n")
        val p = pane()
        // Zooming into a leaf adds an empty placeholder child to type into.
        p.zoomInto(1)
        runCurrent()
        assertEquals("  * ", p.lines[2])
        p.moveTo(2, 4)
        p.onImagePasted("map.png", png)
        runCurrent()
        // The leaf has no folder yet: the image goes where the row is stored.
        assertNotNull(read("map.png"))
        flush(p)
        // The save promoted "Trip"; the image followed the row into it.
        assertEquals("- Groceries\n- Trip [↳](<Trip/_node.md>)\n", read("_node.md"))
        assertEquals("- ![](map.png)\n", read("Trip/_node.md"))
        assertNotNull(read("Trip/map.png"))
        assertNull(read("map.png"))
        assertEquals("Trip/map.png", p.resolveImageSrc(2, "map.png"))
    }

    // --------------------------------------------- images follow their row

    @Test
    fun an_image_moves_with_its_row_on_indent_and_back_on_outdent() = runTest {
        seed("_node.md", "- Trip\n- ![](map.png)\n")
        seed("map.png", "img")
        val p = pane()
        p.moveTo(1, 2)
        p.indentLine()
        flush(p)
        assertEquals("- Trip [↳](<Trip/_node.md>)\n", read("_node.md"))
        assertEquals("img", read("Trip/map.png"))
        assertNull(read("map.png"))

        p.moveTo(1, 4)
        p.outdentLine()
        flush(p)
        assertEquals("img", read("map.png"))
        // The folder emptied by the move is demoted right away.
        assertEquals("- Trip\n- ![](map.png)\n", read("_node.md"))
        assertFalse(dirExists("Trip"))
    }

    @Test
    fun an_image_travels_with_its_folder_on_a_rename() = runTest {
        seed("_node.md", "- Trip [↳](<Trip/_node.md>)\n")
        seed("Trip/_node.md", "- ![](map.png)\n")
        seed("Trip/map.png", "img")
        val p = pane()
        p.toggleCollapse(p.id(0))
        runCurrent()
        assertEquals("Trip/map.png", p.resolveImageSrc(1, "map.png"))
        p.moveTo(0, p.lines[0].length)
        p.insertText(" 2027")
        flush(p)
        assertEquals("img", read("Trip 2027/map.png"))
        assertEquals("- ![](map.png)\n", read("Trip 2027/_node.md"))
        assertEquals("Trip 2027/map.png", p.resolveImageSrc(1, "map.png"))
    }

    @Test
    fun an_image_another_row_still_uses_stays_put() = runTest {
        seed("_node.md", "- Trip\n- ![](map.png)\n- again ![](map.png)\n")
        seed("map.png", "img")
        val p = pane()
        p.moveTo(1, 2)
        p.indentLine()
        flush(p)
        assertEquals("img", read("map.png"))
        assertNull(read("Trip/map.png"))
    }

    @Test
    fun a_picked_vault_image_is_referenced_vault_rooted() = runTest {
        seed("_node.md", "- Trip [↳](<Trip/_node.md>)\n")
        seed("Trip/_node.md", "- here\n")
        seed("Images/logo.png", "img")
        val p = pane()
        p.toggleCollapse(p.id(0))
        runCurrent()
        p.moveTo(1, p.lines[1].length)
        p.insertVaultImage("Images/logo.png")
        assertEquals("  * here![](/Images/logo.png)", p.lines[1])
        assertEquals("Images/logo.png", p.resolveImageSrc(1, "/Images/logo.png"))
        // An image in the row's own folder is referenced by name.
        seed("Trip/pic.png", "img")
        p.insertVaultImage("Trip/pic.png")
        assertTrue(p.lines[1].endsWith("![](pic.png)"))
    }

    @Test
    fun the_palette_lists_images_from_every_folder_but_the_trash() = runTest {
        seed("Images/a.png", "img")
        seed("Trip/b.JPG", "img")
        seed("Trip/notes.md", "x")
        seed(".trash/old/c.png", "img")
        assertEquals(listOf("Images/a.png", "Trip/b.JPG"), repo.listImageFiles())
    }

    // ------------------------------------------------------ path rules

    @Test
    fun image_paths_resolve_against_the_rows_folder() {
        assertEquals("Trip/map.png", ImagePaths.resolve("Trip", "map.png"))
        assertEquals("map.png", ImagePaths.resolve("", "map.png"))
        assertEquals("Images/logo.png", ImagePaths.resolve("Trip/Day 1", "/Images/logo.png"))
        assertEquals("Trip/logo.png", ImagePaths.resolve("Trip/Day 1", "../logo.png"))
        assertEquals("logo.png", ImagePaths.resolve("Trip", "../../../logo.png"))
        assertEquals("Trip/a/b.png", ImagePaths.resolve("Trip", "./a/b.png"))
        assertNull(ImagePaths.resolve("Trip", "https://example.com/a.png"))
        assertNull(ImagePaths.resolve("Trip", "data:image/png;base64,AAAA"))
        assertNull(ImagePaths.resolve("Trip", ""))
    }

    @Test
    fun only_bare_file_names_are_folder_local() {
        assertTrue(ImagePaths.isFolderLocal("map.png"))
        assertTrue(ImagePaths.isFolderLocal("My map.png"))
        assertFalse(ImagePaths.isFolderLocal("/Images/map.png"))
        assertFalse(ImagePaths.isFolderLocal("sub/map.png"))
        assertFalse(ImagePaths.isFolderLocal("../map.png"))
        assertFalse(ImagePaths.isFolderLocal("https://x.test/map.png"))
        assertEquals("/Images/a.png", ImagePaths.vaultRooted("Images/a.png"))
    }
}
