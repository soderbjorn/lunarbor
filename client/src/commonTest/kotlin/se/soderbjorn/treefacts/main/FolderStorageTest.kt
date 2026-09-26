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

package se.soderbjorn.treefacts.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import se.soderbjorn.treefacts.data.NoteRepository
import se.soderbjorn.treefacts.testing.InMemoryFileSystem
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

    private suspend fun TestScope.open(fileRel: String = ".treefacts"): Document {
        val doc = Document(repo, backgroundScope, fileRel)
        doc.start()
        doc.stateFlow.first { it.isLoaded }
        return doc
    }

    private fun Document.lines() = stateFlow.value.lines
    private fun Document.id(row: Int) = stateFlow.value.lineIds[row]
    private fun Document.rowOf(text: String) = lines().indexOf(text)

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

    // ------------------------------------------------------------ promotion

    @Test
    fun a_leaf_that_gets_its_first_child_becomes_a_folder() = runTest {
        seed(".treefacts", "* Groceries\n* Buy oat milk\n")
        val doc = open()
        doc.indent(1)
        doc.flush()
        assertEquals("+ [Groceries](Groceries)\n", read(".treefacts"))
        assertEquals("* Buy oat milk\n", read("Groceries/.treefacts"))
        assertTrue(doc.isPromotedRef(doc.id(0)))
        // The children stay in memory; the row is not "unloaded".
        assertFalse(doc.id(0) in doc.stateFlow.value.unloadedRefIds)
    }

    @Test
    fun an_empty_placeholder_child_does_not_create_a_folder() = runTest {
        seed(".treefacts", "* A\n")
        val doc = open()
        doc.appendLine("  * ")
        doc.appendLine("* B")
        doc.flush()
        assertEquals("* A\n* B\n", read(".treefacts"))
        assertFalse(dirExists("A"))
    }

    @Test
    fun a_block_is_content_and_round_trips() = runTest {
        seed(".treefacts", "* Trip to **Lisbon**\n")
        val doc = open()
        doc.appendLine("  :::")
        doc.appendLine("  **Packing**: passport")
        doc.appendLine("  :::")
        doc.flush()
        assertEquals("+ [Trip to **Lisbon**](Trip to Lisbon)\n", read(".treefacts"))
        assertEquals(":::\n**Packing**: passport\n:::\n", read("Trip to Lisbon/.treefacts"))
        val reopened = Document(repo, backgroundScope, ".treefacts").also { it.start() }
        reopened.stateFlow.first { it.isLoaded }
        reopened.acquireExpansion(reopened.id(0))
        assertEquals(
            listOf("* Trip to **Lisbon**", "  :::", "  **Packing**: passport", "  :::"),
            reopened.lines(),
        )
    }

    // ------------------------------------------------------------- demotion

    @Test
    fun outdenting_the_only_child_removes_the_folder_again() = runTest {
        seed(".treefacts", "* A\n* B\n")
        val doc = open()
        doc.indent(1)
        doc.flush()
        assertTrue(dirExists("A"))
        doc.outdent(1)
        doc.flush()
        assertEquals("* A\n* B\n", read(".treefacts"))
        assertFalse(dirExists("A"))
        assertFalse(doc.isPromotedRef(doc.id(0)))
    }

    @Test
    fun removing_the_last_child_keeps_a_folder_that_still_holds_files() = runTest {
        seed(".treefacts", "* A\n* B\n")
        val doc = open()
        doc.indent(1)
        doc.flush()
        fs.writeBinary("$root/A/photo.png", ByteArray(3))
        fs.writeFile("$root/A/notes.md", "hand written")
        doc.delete(0, doc.lines()[0].length, 1, doc.lines()[1].length)
        doc.flush()
        assertEquals("+ [A](A)\n", read(".treefacts"))
        assertTrue(dirExists("A"))
        assertNull(read("A/.treefacts"))
        assertEquals("hand written", read("A/notes.md"))
        assertEquals("<binary 3>", read("A/photo.png"))
    }

    @Test
    fun removing_the_last_block_demotes_the_bullet() = runTest {
        seed(".treefacts", "+ [A](A)\n")
        seed("A/.treefacts", ":::\nx\n:::\n")
        val doc = open()
        doc.acquireExpansion(doc.id(0))
        assertEquals(4, doc.lines().size)
        doc.delete(0, doc.lines()[0].length, 3, doc.lines()[3].length)
        doc.flush()
        assertEquals("* A\n", read(".treefacts"))
        assertFalse(dirExists("A"))
    }

    // --------------------------------------------------------------- rename

    @Test
    fun editing_a_title_renames_the_folder_and_keeps_its_files() = runTest {
        seed(".treefacts", "+ [Recipes](Recipes)\n")
        seed("Recipes/.treefacts", "* Pasta\n")
        fs.writeBinary("$root/Recipes/pic.png", ByteArray(1))
        val doc = open()
        doc.setLine(0, "* Q3/Q4 **plan**")
        doc.flush()
        assertEquals("+ [Q3/Q4 **plan**](Q3%2FQ4 plan)\n", read(".treefacts"))
        assertFalse(dirExists("Recipes"))
        assertEquals("* Pasta\n", read("Q3%2FQ4 plan/.treefacts"))
        assertEquals("<binary 1>", read("Q3%2FQ4 plan/pic.png"))
    }

    @Test
    fun an_empty_title_with_children_is_named_untitled_until_a_title_is_typed() = runTest {
        seed(".treefacts", "* \n* child one\n* \n* child two\n")
        val doc = open()
        doc.indent(1)
        doc.indent(3)
        doc.flush()
        assertEquals("+ [](Untitled)\n+ [](Untitled (2))\n", read(".treefacts"))
        assertEquals("* child one\n", read("Untitled/.treefacts"))
        assertEquals("* child two\n", read("Untitled (2)/.treefacts"))
        // Saving again changes nothing — the (2) folder is not churned.
        doc.insertText(3, doc.lines()[3].length, "!")
        doc.flush()
        assertTrue(dirExists("Untitled (2)"))
        doc.setLine(2, "* Second")
        doc.flush()
        assertEquals("+ [](Untitled)\n+ [Second](Second)\n", read(".treefacts"))
        assertEquals("* child two!\n", read("Second/.treefacts"))
        assertFalse(dirExists("Untitled (2)"))
    }

    // ---------------------------------------------------------- collisions

    @Test
    fun sibling_collisions_are_case_insensitive_and_avoid_foreign_folders() = runTest {
        seed(".treefacts", "* Notes\n* a\n* notes\n* b\n* Photos\n* c\n")
        fs.ensureDirectory("$root/photos")
        fs.writeFile("$root/photos/keep.txt", "mine")
        val doc = open()
        doc.indent(1)
        doc.indent(3)
        doc.indent(5)
        doc.flush()
        assertEquals(
            "+ [Notes](Notes)\n+ [notes](notes (2))\n+ [Photos](Photos (2))\n",
            read(".treefacts"),
        )
        assertEquals("mine", read("photos/keep.txt"))
        assertNull(read("photos/.treefacts"))
    }

    // ---------------------------------------------------------------- moves

    @Test
    fun indenting_a_collapsed_folder_bullet_moves_its_folder_with_attachments() = runTest {
        seed(".treefacts", "* Projects\n+ [Alpha](Alpha)\n")
        seed("Alpha/.treefacts", "* spec\n")
        fs.writeFile("$root/Alpha/attachment.txt", "data")
        val doc = open()
        doc.indent(1)
        doc.flush()
        assertEquals("+ [Projects](Projects)\n", read(".treefacts"))
        assertEquals("+ [Alpha](Alpha)\n", read("Projects/.treefacts"))
        assertEquals("* spec\n", read("Projects/Alpha/.treefacts"))
        assertEquals("data", read("Projects/Alpha/attachment.txt"))
        assertFalse(dirExists("Alpha"))
        // Outdent it again: the folder comes back and Projects is demoted.
        doc.outdent(1)
        doc.flush()
        assertEquals("* Projects\n+ [Alpha](Alpha)\n", read(".treefacts"))
        assertEquals("data", read("Alpha/attachment.txt"))
        assertFalse(dirExists("Projects"))
    }

    @Test
    fun a_nested_folder_travels_with_its_renamed_parent() = runTest {
        seed(".treefacts", "+ [A](A)\n")
        seed("A/.treefacts", "+ [B](B)\n")
        seed("A/B/.treefacts", "* leaf\n")
        val doc = open()
        doc.acquireExpansion(doc.id(0))
        doc.setLine(0, "* Renamed")
        doc.flush()
        assertEquals("+ [Renamed](Renamed)\n", read(".treefacts"))
        assertEquals("+ [B](B)\n", read("Renamed/.treefacts"))
        assertEquals("* leaf\n", read("Renamed/B/.treefacts"))
        assertFalse(dirExists("A"))
    }

    @Test
    fun swapping_two_folder_names_never_renames_onto_an_occupied_path() = runTest {
        seed(".treefacts", "+ [X](X)\n+ [Y](Y)\n")
        seed("X/.treefacts", "* x\n")
        seed("Y/.treefacts", "* y\n")
        val doc = open()
        doc.setLine(0, "* Y")
        doc.setLine(1, "* X")
        doc.flush()
        assertEquals("+ [Y](Y)\n+ [X](X)\n", read(".treefacts"))
        assertEquals("* x\n", read("Y/.treefacts"))
        assertEquals("* y\n", read("X/.treefacts"))
        assertFalse(dirExists(".trash/.moving"))
    }

    @Test
    fun dragging_a_subtree_keeps_the_folder() = runTest {
        seed(".treefacts", "* Inbox\n+ [Alpha](Alpha)\n* Later\n")
        seed("Alpha/.treefacts", "* spec\n")
        fs.writeFile("$root/Alpha/a.txt", "keep")
        val doc = open()
        val alphaId = doc.id(1)
        // Move Alpha (row 1) below "Later" as its child.
        doc.moveRows(1, 1, 3, listOf("  * Alpha"))
        assertEquals(alphaId, doc.id(2))
        doc.flush()
        assertEquals("* Inbox\n+ [Later](Later)\n", read(".treefacts"))
        assertEquals("+ [Alpha](Alpha)\n", read("Later/.treefacts"))
        assertEquals("keep", read("Later/Alpha/a.txt"))
    }

    @Test
    fun cut_and_paste_moves_the_folder_even_across_a_save() = runTest {
        seed(".treefacts", "* Target\n* Other\n+ [Alpha](Alpha)\n")
        seed("Alpha/.treefacts", "* spec\n")
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
        assertEquals("+ [Target](Target)\n* Other\n", read(".treefacts"))
        assertEquals("+ [Alpha](Alpha)\n", read("Target/.treefacts"))
        assertEquals("* spec\n", read("Target/Alpha/.treefacts"))
        assertEquals("keep", read("Target/Alpha/a.txt"))
        assertFalse(fs.dirs.any { it.startsWith("$root/.trash/$stamp") })
    }

    @Test
    fun cutting_just_the_title_and_pasting_it_elsewhere_moves_the_folder() = runTest {
        seed(".treefacts", "+ [Alpha](Alpha)\n* Target\n")
        seed("Alpha/.treefacts", "* spec\n")
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
        assertEquals("* \n+ [Target](Target)\n", read(".treefacts"))
        assertEquals("+ [Alpha](Alpha)\n", read("Target/.treefacts"))
        assertEquals("* spec\n", read("Target/Alpha/.treefacts"))
        assertFalse(dirExists("Alpha"))
    }

    // ---------------------------------------------------------------- trash

    @Test
    fun deleting_a_folder_bullet_moves_it_to_the_trash_and_undo_restores_it() = runTest {
        seed(".treefacts", "* Keep\n+ [Old stuff](Old stuff)\n")
        seed("Old stuff/.treefacts", "* a\n")
        fs.writeFile("$root/Old stuff/file.txt", "precious")
        val doc = open()
        val before = doc.stateFlow.value
        doc.delete(0, doc.lines()[0].length, 1, doc.lines()[1].length)
        doc.flush()
        assertEquals("* Keep\n", read(".treefacts"))
        assertFalse(dirExists("Old stuff"))
        assertEquals("precious", read(".trash/$stamp Old stuff/file.txt"))
        assertEquals("* a\n", read(".trash/$stamp Old stuff/.treefacts"))
        // Undo in the same session.
        doc.replaceContent(before.lines, before.lineIds, before.unloadedRefIds)
        doc.flush()
        assertEquals("* Keep\n+ [Old stuff](Old stuff)\n", read(".treefacts"))
        assertEquals("precious", read("Old stuff/file.txt"))
        assertFalse(dirExists(".trash/$stamp Old stuff"))
        // The trash itself is never removed.
        assertTrue(dirExists(".trash"))
    }

    @Test
    fun deleting_an_expanded_subtree_trashes_nested_folders_together() = runTest {
        seed(".treefacts", "* Keep\n+ [A](A)\n")
        seed("A/.treefacts", "+ [B](B)\n")
        seed("B/.treefacts", "* wrong place\n")
        seed("A/B/.treefacts", "* deep\n")
        val doc = open()
        doc.acquireExpansion(doc.id(1))
        doc.acquireExpansion(doc.id(2))
        assertEquals(listOf("* Keep", "* A", "  * B", "    * deep"), doc.lines())
        val before = doc.stateFlow.value
        doc.delete(0, 6, 3, doc.lines()[3].length)
        doc.flush()
        assertEquals("* deep\n", read(".trash/$stamp A/B/.treefacts"))
        assertEquals("* wrong place\n", read("B/.treefacts"))
        doc.replaceContent(before.lines, before.lineIds, before.unloadedRefIds)
        doc.flush()
        assertEquals("* deep\n", read("A/B/.treefacts"))
        assertFalse(dirExists(".trash/$stamp A"))
    }

    // ------------------------------------------------------ expand/collapse

    @Test
    fun collapsing_saves_the_children_before_dropping_them() = runTest {
        seed(".treefacts", "+ [A](A)\n")
        seed("A/.treefacts", "* one\n")
        val doc = open()
        val a = doc.id(0)
        assertTrue(a in doc.stateFlow.value.unloadedRefIds)
        doc.acquireExpansion(a)
        doc.insertText(1, doc.lines()[1].length, " edited")
        doc.releaseExpansion(a)
        assertEquals(listOf("* A"), doc.lines())
        assertEquals("* one edited\n", read("A/.treefacts"))
        assertTrue(a in doc.stateFlow.value.unloadedRefIds)
    }

    @Test
    fun rows_added_under_a_collapsed_folder_bullet_are_merged_not_lost() = runTest {
        seed(".treefacts", "+ [A](A)\n* B\n")
        seed("A/.treefacts", "* existing\n")
        val doc = open()
        doc.indent(1)
        doc.flush()
        assertEquals("* existing\n* B\n", read("A/.treefacts"))
        assertEquals("+ [A](A)\n", read(".treefacts"))
        assertEquals(listOf("* A", "  * existing", "  * B"), doc.lines())
    }

    @Test
    fun five_levels_deep_round_trip() = runTest {
        seed(".treefacts", "* L1\n* L2\n* L3\n* L4\n* L5\n* L6\n")
        val doc = open()
        for (row in 1..5) repeat(row) { doc.indent(row) }
        doc.flush()
        assertEquals("+ [L1](L1)\n", read(".treefacts"))
        assertEquals("* L6\n", read("L1/L2/L3/L4/L5/.treefacts"))
        val fresh = Document(repo, backgroundScope, ".treefacts").also { it.start() }
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
        val doc = open("Plain folder/.treefacts")
        // An empty outline is one empty bullet (TRF-4: bullets only).
        assertEquals(listOf("* "), doc.lines())
        doc.flush()
        assertNull(read("Plain folder/.treefacts"))
        doc.insertText(0, 2, "first")
        doc.flush()
        assertEquals("* first\n", read("Plain folder/.treefacts"))
    }

    // ---------------------------------------------------------- save timing

    @Test
    fun saves_one_second_after_the_last_edit() = runTest {
        seed(".treefacts", "* A\n* B\n")
        val doc = open()
        doc.indent(1)
        advanceTimeBy(900)
        runCurrent()
        assertFalse(dirExists("A"))
        advanceTimeBy(200)
        runCurrent()
        assertEquals("* B\n", read("A/.treefacts"))
    }

    @Test
    fun continuous_typing_still_saves_every_five_seconds() = runTest {
        seed(".treefacts", "* A\n")
        val doc = open()
        var saved = -1L
        repeat(14) { i ->
            doc.insertText(0, doc.lines()[0].length, "x")
            advanceTimeBy(500)
            runCurrent()
            if (saved < 0 && read(".treefacts") != "* A\n") saved = (i + 1) * 500L
        }
        assertTrue(saved in 4_500L..5_500L, "first save at $saved ms")
    }

    @Test
    fun shutdown_flushes_the_final_save() = runTest {
        seed(".treefacts", "* A\n")
        val doc = open()
        doc.insertText(0, 3, "bc")
        doc.shutdown()
        assertEquals("* Abc\n", read(".treefacts"))
    }
}
