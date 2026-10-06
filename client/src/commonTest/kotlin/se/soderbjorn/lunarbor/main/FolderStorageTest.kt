/*
 * FolderStorageTest.kt (commonTest)
 * ---------------------------------
 * End-to-end tests for TRF-3 "one folder per parent bullet": a real
 * [Document] + [NoteRepository] running against [InMemoryFileSystem], with
 * virtual time for the save timing. Every storage rule of the ticket has a
 * test here: promotion, demotion, keeping folders that hold files, renames,
 * moves (indent, outdent, drag, cut and paste), trash and undo, Untitled
 * naming, collisions, blocks, and save timing.
 */

package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
class FolderStorageTest {

    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })

    /** Trash folder name prefix for [repo]'s fixed clock. */
    private val stamp = "1970-01-01 00.00.00"

    private suspend fun seed(rel: String, content: String) = fs.writeFile("$root/$rel", content)

    private fun read(rel: String): String? = fs.read(root, rel)

    private fun dirExists(rel: String): Boolean = "$root/$rel" in fs.dirs

    private suspend fun TestScope.open(fileRel: String = "_node.md"): Document {
        val doc = Document(repo, backgroundScope, fileRel)
        doc.start()
        doc.stateFlow.first { it.isLoaded }
        return doc
    }

    private fun Document.lines() = stateFlow.value.lines
    private fun Document.id(row: Int) = stateFlow.value.lineIds[row]

    private fun Document.setLine(row: Int, text: String) {
        delete(row, 0, row, lines()[row].length)
        insertText(row, 0, text)
    }

    private fun Document.indent(row: Int) { insertText(row, 0, "  ") }
    private fun Document.outdent(row: Int) { delete(row, 0, row, 2) }

    /** Appends [text] as a new last line. */
    private fun Document.appendLine(text: String) {
        val last = lines().lastIndex
        insertText(last, lines()[last].length, "\n$text")
    }

    // ---------------------------------------------------- batched expansion

    @Test
    fun acquiring_several_folders_at_once_splices_each_under_its_row_in_one_emission() = runTest {
        seed("_node.md", "- A [↳](<A/_node.md>)\n- leaf\n> Note\n> [↳](<Note/_node.md>)\n- B [↳](<B/_node.md>)\n")
        seed("A/_node.md", "- A1 [↳](<A1/_node.md>)\n- a2 [x](../B/_node.md)\n")
        seed("A/A1/_node.md", "- deep ![](pic.png)\n")
        seed("Note/_node.md", "- under note\n")
        seed("B/_node.md", "- b1\n")
        val doc = open()
        val emissions = ArrayList<Document.State>()
        val watch = backgroundScope.launch { doc.stateFlow.collect { emissions += it } }
        runCurrent()
        emissions.clear()
        doc.acquireExpansions(listOf(doc.id(0), doc.id(2), doc.id(3)))
        runCurrent()
        // No state with only some of the three spliced in.
        assertTrue(emissions.none { it.lines.size in 6..7 }, emissions.map { it.lines.size }.toString())
        assertEquals(
            listOf(
                "* A", "  * A1", "  * a2 [x](../B/_node.md)",
                "* leaf",
                BlockLayout.firstLine(0, "Note"), "  * under note",
                "* B", "  * b1",
            ),
            doc.lines(),
        )
        // The next level: storage and link folders follow the nesting.
        doc.acquireExpansions(listOf(doc.id(1)))
        assertEquals("    * deep ![](pic.png)", doc.lines()[2])
        assertEquals("A/A1", doc.storageFolderOf(2))
        assertEquals("A", doc.linkBaseOf(3))
        assertEquals("", doc.storageFolderOf(4))
        assertEquals("Note", doc.storageFolderOf(6))
        watch.cancel()
    }

    @Test
    fun a_batch_counts_each_acquire_so_one_release_keeps_the_folder_open() = runTest {
        seed("_node.md", "- A [↳](<A/_node.md>)\n")
        seed("A/_node.md", "- a\n")
        val doc = open()
        val a = doc.id(0)
        doc.acquireExpansions(listOf(a, a))
        assertEquals(listOf("* A", "  * a"), doc.lines())
        doc.releaseExpansion(a)
        assertEquals(listOf("* A", "  * a"), doc.lines())
        doc.releaseExpansion(a)
        assertEquals(listOf("* A"), doc.lines())
    }

    // ------------------------------------------------------- missing folders

    @Test
    fun a_folder_line_whose_folder_is_missing_loads_and_saves_as_a_leaf() = runTest {
        seed("_node.md", "- Gone [↳](<Gone/_node.md>)\n> Notes\n> [↳](<Notes/_node.md>)\n- Here [↳](<Here/_node.md>)\n")
        seed("Here/_node.md", "- child\n")
        val doc = open()
        assertFalse(doc.isPromotedRef(doc.id(0)))
        assertFalse(doc.isPromotedRef(doc.id(1)))
        assertTrue(doc.isPromotedRef(doc.id(2)))
        doc.appendLine("* x")
        doc.flush()
        assertEquals("- Gone\n> Notes\n- Here [↳](<Here/_node.md>)\n- x\n", read("_node.md"))
        assertFalse(dirExists("Gone"))
    }

    @Test
    fun a_folded_nodes_folder_removed_outside_the_app_turns_it_into_a_leaf() = runTest {
        seed("_node.md", "- Here [↳](<Here/_node.md>)\n- x\n")
        seed("Here/_node.md", "- child\n")
        val doc = open()
        assertTrue(doc.isPromotedRef(doc.id(0)))
        fs.moveDirectory("$root/Here", "$root/Elsewhere")
        doc.setLine(1, "* y")
        doc.flush()
        assertEquals("- Here\n- y\n", read("_node.md"))
        assertFalse(dirExists("Here"))
        assertEquals("- child\n", read("Elsewhere/_node.md"))
        assertFalse(doc.isPromotedRef(doc.id(0)))
    }

    // ------------------------------------------------------------ promotion

    @Test
    fun a_leaf_that_gets_its_first_child_becomes_a_folder() = runTest {
        seed("_node.md", "- Groceries\n- Buy oat milk\n")
        val doc = open()
        doc.indent(1)
        doc.flush()
        assertEquals("- Groceries [↳](<Groceries/_node.md>)\n", read("_node.md"))
        assertEquals("- Buy oat milk\n", read("Groceries/_node.md"))
        assertTrue(doc.isPromotedRef(doc.id(0)))
        // The children stay in memory; the row is not "unloaded".
        assertFalse(doc.id(0) in doc.stateFlow.value.unloadedRefIds)
    }

    @Test
    fun an_empty_placeholder_child_does_not_create_a_folder() = runTest {
        seed("_node.md", "- A\n")
        val doc = open()
        doc.appendLine("  * ")
        doc.appendLine("* B")
        doc.flush()
        assertEquals("- A\n- B\n", read("_node.md"))
        assertFalse(dirExists("A"))
    }

    @Test
    fun a_block_is_content_and_round_trips() = runTest {
        seed("_node.md", "- Trip to **Lisbon**\n")
        val doc = open()
        doc.appendLine(BlockLayout.firstLine(2, "**Packing**: passport"))
        doc.flush()
        assertEquals("- Trip to **Lisbon** [↳](<Trip to Lisbon/_node.md>)\n", read("_node.md"))
        assertEquals("> **Packing**: passport\n", read("Trip to Lisbon/_node.md"))
        val reopened = Document(repo, backgroundScope, "_node.md").also { it.start() }
        reopened.stateFlow.first { it.isLoaded }
        reopened.acquireExpansion(reopened.id(0))
        assertEquals(
            listOf("* Trip to **Lisbon**", BlockLayout.firstLine(2, "**Packing**: passport")),
            reopened.lines(),
        )
    }

    // ------------------------------------------------------------- demotion

    @Test
    fun outdenting_the_only_child_removes_the_folder_again() = runTest {
        seed("_node.md", "- A\n- B\n")
        val doc = open()
        doc.indent(1)
        doc.flush()
        assertTrue(dirExists("A"))
        doc.outdent(1)
        doc.flush()
        assertEquals("- A\n- B\n", read("_node.md"))
        assertFalse(dirExists("A"))
        assertFalse(doc.isPromotedRef(doc.id(0)))
    }

    @Test
    fun removing_the_last_child_keeps_a_folder_that_still_holds_files() = runTest {
        seed("_node.md", "- A\n- B\n")
        val doc = open()
        doc.indent(1)
        doc.flush()
        fs.writeBinary("$root/A/photo.png", ByteArray(3))
        fs.writeFile("$root/A/notes.md", "hand written")
        doc.delete(0, doc.lines()[0].length, 1, doc.lines()[1].length)
        doc.flush()
        assertEquals("- A [↳](<A/_node.md>)\n", read("_node.md"))
        assertTrue(dirExists("A"))
        assertNull(read("A/_node.md"))
        assertEquals("hand written", read("A/notes.md"))
        assertEquals("<binary 3>", read("A/photo.png"))
    }

    @Test
    fun removing_the_last_block_demotes_the_bullet() = runTest {
        seed("_node.md", "- A [↳](<A/_node.md>)\n")
        seed("A/_node.md", "> x\n")
        val doc = open()
        doc.acquireExpansion(doc.id(0))
        assertEquals(2, doc.lines().size)
        doc.deleteRows(1, 1)
        doc.flush()
        assertEquals("- A\n", read("_node.md"))
        assertFalse(dirExists("A"))
    }

    // --------------------------------------------------------------- rename

    @Test
    fun editing_a_title_renames_the_folder_and_keeps_its_files() = runTest {
        seed("_node.md", "- Recipes [↳](<Recipes/_node.md>)\n")
        seed("Recipes/_node.md", "- Pasta\n")
        fs.writeBinary("$root/Recipes/pic.png", ByteArray(1))
        val doc = open()
        doc.setLine(0, "* Q3/Q4 **plan**")
        doc.flush()
        assertEquals("- Q3/Q4 **plan** [↳](<Q3%252FQ4 plan/_node.md>)\n", read("_node.md"))
        assertFalse(dirExists("Recipes"))
        assertEquals("- Pasta\n", read("Q3%2FQ4 plan/_node.md"))
        assertEquals("<binary 1>", read("Q3%2FQ4 plan/pic.png"))
    }

    @Test
    fun an_empty_title_with_children_is_named_untitled_until_a_title_is_typed() = runTest {
        seed("_node.md", "-\n- child one\n-\n- child two\n")
        val doc = open()
        doc.indent(1)
        doc.indent(3)
        doc.flush()
        assertEquals("- [↳](<Untitled/_node.md>)\n- [↳](<Untitled (2)/_node.md>)\n", read("_node.md"))
        assertEquals("- child one\n", read("Untitled/_node.md"))
        assertEquals("- child two\n", read("Untitled (2)/_node.md"))
        // Saving again changes nothing — the (2) folder is not churned.
        doc.insertText(3, doc.lines()[3].length, "!")
        doc.flush()
        assertTrue(dirExists("Untitled (2)"))
        doc.setLine(2, "* Second")
        doc.flush()
        assertEquals("- [↳](<Untitled/_node.md>)\n- Second [↳](<Second/_node.md>)\n", read("_node.md"))
        assertEquals("- child two!\n", read("Second/_node.md"))
        assertFalse(dirExists("Untitled (2)"))
    }

    // ---------------------------------------------------------- collisions

    @Test
    fun sibling_collisions_are_case_insensitive() = runTest {
        seed("_node.md", "- Notes\n- a\n- notes\n- b\n")
        val doc = open()
        doc.indent(1)
        doc.indent(3)
        doc.flush()
        assertEquals("- Notes [↳](<Notes/_node.md>)\n- notes [↳](<notes (2)/_node.md>)\n", read("_node.md"))
    }

    @Test
    fun a_new_node_adopts_an_unreferenced_folder_of_its_name() = runTest {
        seed("_node.md", "- Photos\n- c\n")
        fs.ensureDirectory("$root/photos")
        fs.writeFile("$root/photos/keep.txt", "mine")
        val doc = open()
        doc.indent(1)
        doc.flush()
        assertEquals("- Photos [↳](<photos/_node.md>)\n", read("_node.md"))
        assertEquals("- c\n", read("photos/_node.md"))
        assertEquals("mine", read("photos/keep.txt"))
        assertFalse(dirExists("Photos (2)"))
    }

    @Test
    fun an_adopted_folders_bullets_stay_after_the_new_children() = runTest {
        seed("_node.md", "- Recipes\n- Soup\n")
        seed("Recipes/_node.md", "- Bread\n- Cakes [↳](<Cakes/_node.md>)\n")
        seed("Recipes/Cakes/_node.md", "- Carrot\n")
        val doc = open()
        val before = doc.stateFlow.value.reloadCount
        doc.indent(1)
        doc.flush()
        assertEquals("- Recipes [↳](<Recipes/_node.md>)\n", read("_node.md"))
        assertEquals("- Soup\n- Bread\n- Cakes [↳](<Cakes/_node.md>)\n", read("Recipes/_node.md"))
        assertEquals("- Carrot\n", read("Recipes/Cakes/_node.md"))
        // The document shows them too, the folder-backed one folded.
        assertEquals(listOf("* Recipes", "  * Soup", "  * Bread", "  * Cakes"), doc.lines())
        assertTrue(doc.id(3) in doc.stateFlow.value.unloadedRefIds)
        assertTrue(doc.stateFlow.value.reloadCount > before)
        // Saving again keeps everything where it is.
        doc.appendLine("* x")
        doc.flush()
        assertEquals("- Soup\n- Bread\n- Cakes [↳](<Cakes/_node.md>)\n", read("Recipes/_node.md"))
        assertEquals("- Carrot\n", read("Recipes/Cakes/_node.md"))
        assertFalse(dirExists("Recipes (2)"))
    }

    @Test
    fun a_leaf_named_like_a_folder_of_files_becomes_its_node() = runTest {
        seed("_node.md", "- x\n")
        fs.ensureDirectory("$root/photos")
        fs.writeFile("$root/photos/keep.txt", "mine")
        val doc = open()
        doc.appendLine("* Photos")
        doc.flush()
        assertEquals("- x\n- Photos [↳](<photos/_node.md>)\n", read("_node.md"))
        assertTrue(doc.isPromotedRef(doc.id(1)))
        assertNull(read("photos/_node.md"))
        assertEquals("mine", read("photos/keep.txt"))
    }

    @Test
    fun a_leaf_never_adopts_an_empty_folder() = runTest {
        seed("_node.md", "- x\n")
        fs.ensureDirectory("$root/Empty")
        val doc = open()
        doc.appendLine("* Empty")
        doc.flush()
        assertEquals("- x\n- Empty\n", read("_node.md"))
        assertTrue(dirExists("Empty"))
    }

    @Test
    fun a_leaf_adopts_a_folder_whose_name_is_decomposed() = runTest {
        seed("_node.md", "- x\n")
        val decomposed = "Cafe\u0301"
        fs.ensureDirectory("$root/$decomposed")
        fs.writeFile("$root/$decomposed/menu.txt", "soup")
        val doc = open()
        doc.appendLine("* Caf\u00e9")
        doc.flush()
        assertEquals("- x\n- Caf\u00e9 [↳](<$decomposed/_node.md>)\n", read("_node.md"))
        // Saving again keeps the folder's own spelling.
        doc.appendLine("* y")
        doc.flush()
        assertTrue(dirExists(decomposed))
    }

    @Test
    fun retitling_a_node_without_child_bullets_lets_go_of_its_folder() = runTest {
        seed("_node.md", "- Photos [↳](<Photos/_node.md>)\n")
        fs.writeFile("$root/Photos/keep.txt", "mine")
        val doc = open()
        doc.acquireExpansion(doc.id(0))
        doc.setLine(0, "* Photos 2024")
        doc.flush()
        assertEquals("- Photos 2024\n", read("_node.md"))
        assertEquals("mine", read("Photos/keep.txt"))
        assertFalse(dirExists("Photos 2024"))
        assertFalse(doc.isPromotedRef(doc.id(0)))
        // Named like the folder again, it takes it back.
        doc.setLine(0, "* Photos")
        doc.flush()
        assertEquals("- Photos [↳](<Photos/_node.md>)\n", read("_node.md"))
    }

    @Test
    fun retitling_a_node_with_child_bullets_still_renames_its_folder() = runTest {
        seed("_node.md", "- Photos [↳](<Photos/_node.md>)\n")
        seed("Photos/_node.md", "- a\n")
        val doc = open()
        doc.acquireExpansion(doc.id(0))
        doc.setLine(0, "* Pictures")
        doc.flush()
        assertEquals("- Pictures [↳](<Pictures/_node.md>)\n", read("_node.md"))
        assertEquals("- a\n", read("Pictures/_node.md"))
    }

    @Test
    fun converting_a_folder_tree_makes_nodes_of_subfolders_and_notes() = runTest {
        seed("Area/_node.md", "- Kept [↳](<Kept/_node.md>)\n")
        seed("Area/Kept/_node.md", "- k\n")
        fs.writeFile("$root/Area/Kept/Deep.md", "deep")
        fs.writeFile("$root/Area/Sub/pic.png", "x")
        fs.writeFile("$root/Area/Soup.md", "# Soup\n\nHot **soup**\n")
        fs.writeFile("$root/Area/Sub/Soup.md", "cold")
        fs.ensureDirectory("$root/Area/Sub/Soup")
        fs.writeFile("$root/Area/Sub/Soup/x.txt", "x")
        val result = repo.convertFolderTree("Area")
        assertEquals("- Kept [↳](<Kept/_node.md>)\n- Sub [↳](<Sub/_node.md>)\n- Soup [↳](<Soup/_node.md>)\n", read("Area/_node.md"))
        assertEquals("> Hot **soup**\n", read("Area/Soup/_node.md"))
        assertEquals("- k\n- Deep [↳](<Deep/_node.md>)\n", read("Area/Kept/_node.md"))
        assertEquals("> deep\n", read("Area/Kept/Deep/_node.md"))
        // A note named like a subfolder gets its own ` (2)` folder.
        assertEquals("- Soup [↳](<Soup/_node.md>)\n- Soup [↳](<Soup (2)/_node.md>)\n", read("Area/Sub/_node.md"))
        assertEquals("> cold\n", read("Area/Sub/Soup (2)/_node.md"))
        assertNull(read("Area/Sub/Soup/_node.md"))
        assertEquals(
            mapOf(
                "Area/Kept/Deep.md" to "Area/Kept/Deep",
                "Area/Sub/Soup.md" to "Area/Sub/Soup (2)",
                "Area/Soup.md" to "Area/Soup",
            ),
            result.convertedNotes,
        )
        // The originals are untouched.
        assertEquals("cold", read("Area/Sub/Soup.md"))
    }

    @Test
    fun a_new_child_never_adopts_a_folder_the_adopted_outline_uses() = runTest {
        seed("_node.md", "- Recipes\n- Cakes\n- x\n")
        seed("Recipes/_node.md", "- Cakes [↳](<Cakes/_node.md>)\n")
        seed("Recipes/Cakes/_node.md", "- Carrot\n")
        val doc = open()
        doc.indent(1)
        doc.indent(2)
        doc.indent(2)
        doc.flush()
        assertEquals("- Cakes [↳](<Cakes (2)/_node.md>)\n- Cakes [↳](<Cakes/_node.md>)\n", read("Recipes/_node.md"))
        assertEquals("- x\n", read("Recipes/Cakes (2)/_node.md"))
        assertEquals("- Carrot\n", read("Recipes/Cakes/_node.md"))
    }

    // ---------------------------------------------------------------- moves

    @Test
    fun indenting_a_collapsed_folder_bullet_moves_its_folder_with_attachments() = runTest {
        seed("_node.md", "- Projects\n- Alpha [↳](<Alpha/_node.md>)\n")
        seed("Alpha/_node.md", "- spec\n")
        fs.writeFile("$root/Alpha/attachment.txt", "data")
        val doc = open()
        doc.indent(1)
        doc.flush()
        assertEquals("- Projects [↳](<Projects/_node.md>)\n", read("_node.md"))
        assertEquals("- Alpha [↳](<Alpha/_node.md>)\n", read("Projects/_node.md"))
        assertEquals("- spec\n", read("Projects/Alpha/_node.md"))
        assertEquals("data", read("Projects/Alpha/attachment.txt"))
        assertFalse(dirExists("Alpha"))
        // Outdent it again: the folder comes back and Projects is demoted.
        doc.outdent(1)
        doc.flush()
        assertEquals("- Projects\n- Alpha [↳](<Alpha/_node.md>)\n", read("_node.md"))
        assertEquals("data", read("Alpha/attachment.txt"))
        assertFalse(dirExists("Projects"))
    }

    @Test
    fun a_nested_folder_travels_with_its_renamed_parent() = runTest {
        seed("_node.md", "- A [↳](<A/_node.md>)\n")
        seed("A/_node.md", "- B [↳](<B/_node.md>)\n")
        seed("A/B/_node.md", "- leaf\n")
        val doc = open()
        doc.acquireExpansion(doc.id(0))
        doc.setLine(0, "* Renamed")
        doc.flush()
        assertEquals("- Renamed [↳](<Renamed/_node.md>)\n", read("_node.md"))
        assertEquals("- B [↳](<B/_node.md>)\n", read("Renamed/_node.md"))
        assertEquals("- leaf\n", read("Renamed/B/_node.md"))
        assertFalse(dirExists("A"))
    }

    @Test
    fun swapping_two_folder_names_never_renames_onto_an_occupied_path() = runTest {
        seed("_node.md", "- X [↳](<X/_node.md>)\n- Y [↳](<Y/_node.md>)\n")
        seed("X/_node.md", "- x\n")
        seed("Y/_node.md", "- y\n")
        val doc = open()
        doc.setLine(0, "* Y")
        doc.setLine(1, "* X")
        doc.flush()
        assertEquals("- Y [↳](<Y/_node.md>)\n- X [↳](<X/_node.md>)\n", read("_node.md"))
        assertEquals("- x\n", read("Y/_node.md"))
        assertEquals("- y\n", read("X/_node.md"))
        assertFalse(dirExists(".trash/.moving"))
    }

    @Test
    fun dragging_a_subtree_keeps_the_folder() = runTest {
        seed("_node.md", "- Inbox\n- Alpha [↳](<Alpha/_node.md>)\n- Later\n")
        seed("Alpha/_node.md", "- spec\n")
        fs.writeFile("$root/Alpha/a.txt", "keep")
        val doc = open()
        val alphaId = doc.id(1)
        // Move Alpha (row 1) below "Later" as its child.
        doc.moveRows(1, 1, 3, listOf("  * Alpha"))
        assertEquals(alphaId, doc.id(2))
        doc.flush()
        assertEquals("- Inbox\n- Later [↳](<Later/_node.md>)\n", read("_node.md"))
        assertEquals("- Alpha [↳](<Alpha/_node.md>)\n", read("Later/_node.md"))
        assertEquals("keep", read("Later/Alpha/a.txt"))
    }

    @Test
    fun cut_and_paste_moves_the_folder_even_across_a_save() = runTest {
        seed("_node.md", "- Target\n- Other\n- Alpha [↳](<Alpha/_node.md>)\n")
        seed("Alpha/_node.md", "- spec\n")
        fs.writeFile("$root/Alpha/a.txt", "keep")
        val doc = open()
        // Cut the whole "Alpha" line.
        val cutText = doc.lines()[2]
        doc.rememberCut(2, 0, 2, cutText.length, cutText)
        doc.delete(1, doc.lines()[1].length, 2, cutText.length)
        doc.flush()
        // The save in between trashes the folder...
        assertTrue(fs.dirs.any { it.startsWith("$root/.trash/$stamp Alpha") })
        // ...and the paste brings it back, under Target.
        doc.insertText(0, doc.lines()[0].length, "\n  ")
        doc.insertText(1, 2, cutText)
        doc.adoptCut(1, cutText)
        assertEquals("  * Alpha", doc.lines()[1])
        doc.flush()
        assertEquals("- Target [↳](<Target/_node.md>)\n- Other\n", read("_node.md"))
        assertEquals("- Alpha [↳](<Alpha/_node.md>)\n", read("Target/_node.md"))
        assertEquals("- spec\n", read("Target/Alpha/_node.md"))
        assertEquals("keep", read("Target/Alpha/a.txt"))
        assertFalse(fs.dirs.any { it.startsWith("$root/.trash/$stamp") })
    }

    @Test
    fun cutting_just_the_title_and_pasting_it_elsewhere_moves_the_folder() = runTest {
        seed("_node.md", "- Alpha [↳](<Alpha/_node.md>)\n- Target\n")
        seed("Alpha/_node.md", "- spec\n")
        val doc = open()
        // Select only the title text of row 0 and cut it: an empty bullet stays.
        doc.rememberCut(0, 2, 0, 7, "Alpha")
        doc.delete(0, 2, 0, 7)
        assertEquals("* ", doc.lines()[0])
        // Paste it as a new child of Target.
        doc.insertText(1, doc.lines()[1].length, "\n  * ")
        doc.insertText(2, 4, "Alpha")
        doc.adoptCut(2, "Alpha")
        doc.flush()
        assertEquals("-\n- Target [↳](<Target/_node.md>)\n", read("_node.md"))
        assertEquals("- Alpha [↳](<Alpha/_node.md>)\n", read("Target/_node.md"))
        assertEquals("- spec\n", read("Target/Alpha/_node.md"))
        assertFalse(dirExists("Alpha"))
    }

    // ---------------------------------------------------------------- trash

    @Test
    fun deleting_a_folder_bullet_moves_it_to_the_trash_and_undo_restores_it() = runTest {
        seed("_node.md", "- Keep\n- Old stuff [↳](<Old stuff/_node.md>)\n")
        seed("Old stuff/_node.md", "- a\n")
        // A dotfile is not user content: the folder still goes whole.
        fs.writeFile("$root/Old stuff/.DS_Store", "x")
        val doc = open()
        val before = doc.stateFlow.value
        doc.delete(0, doc.lines()[0].length, 1, doc.lines()[1].length)
        doc.flush()
        assertEquals("- Keep\n", read("_node.md"))
        assertFalse(dirExists("Old stuff"))
        assertEquals("- a\n", read(".trash/$stamp Old stuff/_node.md"))
        // Undo in the same session.
        doc.replaceContent(before.lines, before.lineIds, before.unloadedRefIds)
        doc.flush()
        assertEquals("- Keep\n- Old stuff [↳](<Old stuff/_node.md>)\n", read("_node.md"))
        assertEquals("- a\n", read("Old stuff/_node.md"))
        assertFalse(dirExists(".trash/$stamp Old stuff"))
        // The trash itself is never removed.
        assertTrue(dirExists(".trash"))
    }

    @Test
    fun deleting_a_folder_bullet_keeps_its_files_in_place_and_trashes_only_its_bullets() = runTest {
        seed("_node.md", "- Keep\n- Old stuff [↳](<Old stuff/_node.md>)\n")
        seed("Old stuff/_node.md", "- a\n")
        fs.writeFile("$root/Old stuff/notes.md", "precious")
        fs.writeBinary("$root/Old stuff/photo.png", ByteArray(3))
        val doc = open()
        val before = doc.stateFlow.value
        doc.delete(0, doc.lines()[0].length, 1, doc.lines()[1].length)
        doc.flush()
        assertEquals("- Keep\n", read("_node.md"))
        // The files stay where they were; the folder is now unreferenced,
        // so the parent's folder contents list shows it.
        assertEquals("precious", read("Old stuff/notes.md"))
        assertEquals("<binary 3>", read("Old stuff/photo.png"))
        assertNull(read("Old stuff/_node.md"))
        assertEquals("- a\n", read(".trash/$stamp Old stuff/_node.md"))
        assertNull(read(".trash/$stamp Old stuff/notes.md"))
        val listed = repo.listVaultLevel("").single { it.name == "Old stuff" }
        assertFalse(listed.isReferenced)
        // Undo merges the bullets back into the folder with its files.
        doc.replaceContent(before.lines, before.lineIds, before.unloadedRefIds)
        doc.flush()
        assertEquals("- Keep\n- Old stuff [↳](<Old stuff/_node.md>)\n", read("_node.md"))
        assertEquals("- a\n", read("Old stuff/_node.md"))
        assertEquals("precious", read("Old stuff/notes.md"))
        assertFalse(dirExists("Old stuff (2)"))
        assertFalse(dirExists(".trash/$stamp Old stuff"))
    }

    @Test
    fun a_split_deletion_trashes_child_bullets_and_keeps_every_folder_with_files() = runTest {
        seed("_node.md", "- Keep\n- A [↳](<A/_node.md>)\n")
        seed("A/_node.md", "- B [↳](<B/_node.md>)\n- C [↳](<C/_node.md>)\n")
        seed("A/B/_node.md", "- only bullets\n")
        seed("A/C/_node.md", "- c child\n")
        fs.writeBinary("$root/A/photo.png", ByteArray(2))
        fs.writeFile("$root/A/C/c.md", "c note")
        fs.ensureDirectory("$root/A/Foreign")
        fs.writeFile("$root/A/Foreign/x.txt", "x")
        val doc = open()
        val before = doc.stateFlow.value
        // A is collapsed: its children exist only on disk.
        doc.delete(0, doc.lines()[0].length, 1, doc.lines()[1].length)
        doc.flush()
        // A keeps its image and the folder nobody's bullet owned.
        assertEquals("<binary 2>", read("A/photo.png"))
        assertEquals("x", read("A/Foreign/x.txt"))
        assertNull(read("A/_node.md"))
        // B held only bullets: it goes to the trash whole.
        assertFalse(dirExists("A/B"))
        assertEquals("- only bullets\n", read(".trash/$stamp A/B/_node.md"))
        // C held a note: it stays with it, its bullets go.
        assertEquals("c note", read("A/C/c.md"))
        assertNull(read("A/C/_node.md"))
        assertEquals("- c child\n", read(".trash/$stamp A/C/_node.md"))
        // Undo puts every piece back.
        doc.replaceContent(before.lines, before.lineIds, before.unloadedRefIds)
        doc.flush()
        assertEquals("- B [↳](<B/_node.md>)\n- C [↳](<C/_node.md>)\n", read("A/_node.md"))
        assertEquals("- only bullets\n", read("A/B/_node.md"))
        assertEquals("- c child\n", read("A/C/_node.md"))
        assertEquals("c note", read("A/C/c.md"))
        assertFalse(dirExists(".trash/$stamp A"))
    }

    @Test
    fun deleting_an_expanded_folder_bullet_with_files_keeps_them_and_undo_restores() = runTest {
        seed("_node.md", "- Keep\n- A [↳](<A/_node.md>)\n")
        seed("A/_node.md", "- B [↳](<B/_node.md>)\n")
        seed("A/B/_node.md", "- deep\n")
        fs.writeBinary("$root/A/B/pic.png", ByteArray(4))
        val doc = open()
        doc.acquireExpansion(doc.id(1))
        doc.acquireExpansion(doc.id(2))
        assertEquals(listOf("* Keep", "* A", "  * B", "    * deep"), doc.lines())
        val before = doc.stateFlow.value
        doc.delete(0, 6, 3, doc.lines()[3].length)
        doc.flush()
        assertEquals("<binary 4>", read("A/B/pic.png"))
        assertNull(read("A/_node.md"))
        assertNull(read("A/B/_node.md"))
        assertEquals("- deep\n", read(".trash/$stamp A/B/_node.md"))
        doc.replaceContent(before.lines, before.lineIds, before.unloadedRefIds)
        doc.flush()
        assertEquals("- B [↳](<B/_node.md>)\n", read("A/_node.md"))
        assertEquals("- deep\n", read("A/B/_node.md"))
        assertEquals("<binary 4>", read("A/B/pic.png"))
        assertFalse(dirExists(".trash/$stamp A"))
    }

    @Test
    fun deleting_an_expanded_subtree_trashes_nested_folders_together() = runTest {
        seed("_node.md", "- Keep\n- A [↳](<A/_node.md>)\n")
        seed("A/_node.md", "- B [↳](<B/_node.md>)\n")
        seed("B/_node.md", "- wrong place\n")
        seed("A/B/_node.md", "- deep\n")
        val doc = open()
        doc.acquireExpansion(doc.id(1))
        doc.acquireExpansion(doc.id(2))
        assertEquals(listOf("* Keep", "* A", "  * B", "    * deep"), doc.lines())
        val before = doc.stateFlow.value
        doc.delete(0, 6, 3, doc.lines()[3].length)
        doc.flush()
        assertEquals("- deep\n", read(".trash/$stamp A/B/_node.md"))
        assertEquals("- wrong place\n", read("B/_node.md"))
        doc.replaceContent(before.lines, before.lineIds, before.unloadedRefIds)
        doc.flush()
        assertEquals("- deep\n", read("A/B/_node.md"))
        assertFalse(dirExists(".trash/$stamp A"))
    }

    // ------------------------------------------------------ expand/collapse

    @Test
    fun collapsing_saves_the_children_before_dropping_them() = runTest {
        seed("_node.md", "- A [↳](<A/_node.md>)\n")
        seed("A/_node.md", "- one\n")
        val doc = open()
        val a = doc.id(0)
        assertTrue(a in doc.stateFlow.value.unloadedRefIds)
        doc.acquireExpansion(a)
        doc.insertText(1, doc.lines()[1].length, " edited")
        doc.releaseExpansion(a)
        assertEquals(listOf("* A"), doc.lines())
        assertEquals("- one edited\n", read("A/_node.md"))
        assertTrue(a in doc.stateFlow.value.unloadedRefIds)
    }

    @Test
    fun rows_added_under_a_collapsed_folder_bullet_are_merged_not_lost() = runTest {
        seed("_node.md", "- A [↳](<A/_node.md>)\n- B\n")
        seed("A/_node.md", "- existing\n")
        val doc = open()
        doc.indent(1)
        doc.flush()
        assertEquals("- existing\n- B\n", read("A/_node.md"))
        assertEquals("- A [↳](<A/_node.md>)\n", read("_node.md"))
        assertEquals(listOf("* A", "  * existing", "  * B"), doc.lines())
    }

    @Test
    fun five_levels_deep_round_trip() = runTest {
        seed("_node.md", "- L1\n- L2\n- L3\n- L4\n- L5\n- L6\n")
        val doc = open()
        for (row in 1..5) repeat(row) { doc.indent(row) }
        doc.flush()
        assertEquals("- L1 [↳](<L1/_node.md>)\n", read("_node.md"))
        assertEquals("- L6\n", read("L1/L2/L3/L4/L5/_node.md"))
        val fresh = Document(repo, backgroundScope, "_node.md").also { it.start() }
        fresh.stateFlow.first { it.isLoaded }
        for (row in 0..4) fresh.acquireExpansion(fresh.id(row))
        assertEquals(
            listOf("* L1", "  * L2", "    * L3", "      * L4", "        * L5", "          * L6"),
            fresh.lines(),
        )
    }

    @Test
    fun a_folder_without_an_outline_is_an_empty_node_and_typing_creates_the_file() = runTest {
        fs.ensureDirectory("$root/Plain folder")
        val doc = open("Plain folder/_node.md")
        // An empty outline is one empty bullet (TRF-4: bullets only).
        assertEquals(listOf("* "), doc.lines())
        doc.flush()
        assertNull(read("Plain folder/_node.md"))
        doc.insertText(0, 2, "first")
        doc.flush()
        assertEquals("- first\n", read("Plain folder/_node.md"))
    }

    // ---------------------------------------------------------- save timing

    @Test
    fun saves_one_second_after_the_last_edit() = runTest {
        seed("_node.md", "- A\n- B\n")
        val doc = open()
        doc.indent(1)
        advanceTimeBy(900)
        runCurrent()
        assertFalse(dirExists("A"))
        advanceTimeBy(200)
        runCurrent()
        assertEquals("- B\n", read("A/_node.md"))
    }

    @Test
    fun continuous_typing_still_saves_every_five_seconds() = runTest {
        seed("_node.md", "- A\n")
        val doc = open()
        var saved = -1L
        repeat(14) { i ->
            doc.insertText(0, doc.lines()[0].length, "x")
            advanceTimeBy(500)
            runCurrent()
            if (saved < 0 && read("_node.md") != "- A\n") saved = (i + 1) * 500L
        }
        assertTrue(saved in 4_500L..5_500L, "first save at $saved ms")
    }

    @Test
    fun shutdown_flushes_the_final_save() = runTest {
        seed("_node.md", "- A\n")
        val doc = open()
        doc.insertText(0, 3, "bc")
        doc.shutdown()
        assertEquals("- Abc\n", read("_node.md"))
    }
}
