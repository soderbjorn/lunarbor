/*
 * BlockEditingTest.kt (commonTest)
 * --------------------------------
 * Tests for TRF-5 "Blocks: bordered free-Markdown blocks among bullets".
 * Drives a real [PaneBackingViewModel] over a [DocumentRegistry] +
 * [NoteRepository] on [InMemoryFileSystem] — the stack the app runs — and
 * checks the ticket's "done when" list (insert + save + reload, a block
 * making its parent folder-backed, `:::` content round-tripping, undoable
 * deletion) plus the keys inside a block.
 */

package se.soderbjorn.treefacts.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import se.soderbjorn.treefacts.data.NoteRepository
import se.soderbjorn.treefacts.testing.InMemoryFileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BlockEditingTest {

    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })

    private lateinit var registry: DocumentRegistry

    /**
     * Opens a pane on the root outline seeded with [content] (or on
     * whatever is already on disk when `null`), with every subtree
     * expanded.
     */
    private suspend fun TestScope.pane(content: String?): PaneBackingViewModel {
        if (content != null) fs.writeFile("$root/.treefacts", content)
        registry = DocumentRegistry(repo, backgroundScope)
        val pane = PaneBackingViewModel(registry, backgroundScope, ".treefacts")
        pane.stateFlow.first { it.isLoaded }
        runCurrent()
        for (id in pane.stateFlow.value.collapsedIds) pane.toggleCollapse(id)
        return pane
    }

    /** Saves the pane's document now. */
    private suspend fun flush() {
        val doc = registry.acquire(".treefacts")
        doc.flush()
        registry.release(".treefacts")
    }

    private fun read(rel: String): String? = fs.read(root, rel)

    private val PaneBackingViewModel.lines get() = stateFlow.value.lines
    private val PaneBackingViewModel.caret get() = stateFlow.value.let { it.cursorRow to it.cursorCol }
    private fun PaneBackingViewModel.id(row: Int) = stateFlow.value.documentState!!.lineIds[row]
    private fun PaneBackingViewModel.caretAtEnd(row: Int) = moveTo(row, lines[row].length)
    private fun PaneBackingViewModel.caretAtTextStart(row: Int) =
        moveTo(row, DocumentLayout.textStartCol(lines[row]))

    private fun first(indent: Int, content: String = "") = BlockLayout.firstLine(indent, content)
    private fun next(indent: Int, content: String = "") = BlockLayout.nextLine(indent, content)

    // ------------------------------------------------------------ done when

    @Test
    fun insert_block_type_a_heading_and_a_list_then_save_and_reload() = runTest {
        val p = pane("* Trip\n* Home\n")
        p.caretAtEnd(0)
        p.insertBlock()
        assertEquals(listOf("* Trip", first(0), "* Home"), p.lines)
        assertEquals(1 to 1, p.caret)
        p.insertText("## Packing list")
        p.insertNewline()
        p.insertText("- passport")
        p.insertNewline()
        p.insertText("- charger")
        val edited = p.lines
        assertEquals(
            listOf("* Trip", first(0, "## Packing list"), next(0, "- passport"), next(0, "- charger"), "* Home"),
            edited,
        )
        flush()
        assertEquals("* Trip\n:::\n## Packing list\n- passport\n- charger\n:::\n* Home\n", read(".treefacts"))
        val reopened = pane(null)
        assertEquals(edited, reopened.lines)
    }

    @Test
    fun a_block_indented_under_a_leaf_makes_the_leaf_folder_backed() = runTest {
        val p = pane("* Leaf\n")
        p.caretAtEnd(0)
        p.insertBlock()
        p.insertText("notes")
        p.indentLine()
        assertEquals(listOf("* Leaf", first(2, "notes")), p.lines)
        flush()
        assertEquals("+ [Leaf](Leaf)\n", read(".treefacts"))
        assertEquals(":::\nnotes\n:::\n", read("Leaf/.treefacts"))
        assertTrue(p.isPromotedRef(p.id(0)))
    }

    @Test
    fun content_with_a_fence_line_round_trips() = runTest {
        val p = pane("* A\n")
        p.caretAtEnd(0)
        p.insertBlock()
        p.insertText("before\n:::\n::::\nafter")
        val edited = p.lines
        assertEquals(listOf("* A", first(0, "before"), next(0, ":::"), next(0, "::::"), next(0, "after")), edited)
        flush()
        assertEquals("* A\n:::::\nbefore\n:::\n::::\nafter\n:::::\n", read(".treefacts"))
        assertEquals(edited, pane(null).lines)
    }

    @Test
    fun deleting_a_block_can_be_undone_from_every_entry_point() = runTest {
        val p = pane("* A\n:::\nx\ny\n:::\n* B\n")
        val original = p.lines
        assertEquals(listOf("* A", first(0, "x"), next(0, "y"), "* B"), original)

        // Palette: "Delete block" with the caret in the block.
        p.caretAtEnd(2)
        p.deleteBlockAtCursor()
        assertEquals(listOf("* A", "* B"), p.lines)
        assertEquals(0 to 3, p.caret)
        p.undo()
        assertEquals(original, p.lines)

        // Hover control: by the first row's id.
        p.deleteBlock(p.id(1))
        assertEquals(listOf("* A", "* B"), p.lines)
        p.undo()
        assertEquals(original, p.lines)

        // Backspace in an empty block.
        p.caretAtEnd(0)
        p.insertBlock()
        assertEquals(listOf("* A", first(0), first(0, "x"), next(0, "y"), "* B"), p.lines)
        p.backspace()
        assertEquals(original, p.lines)
        assertEquals(0 to 3, p.caret)
        p.undo()
        assertEquals(listOf("* A", first(0), first(0, "x"), next(0, "y"), "* B"), p.lines)
    }

    // --------------------------------------------------------------- keys

    @Test
    fun insert_block_lands_after_the_bullets_subtree_at_its_level() = runTest {
        val p = pane("* A\n  * child\n* B\n")
        p.caretAtEnd(0)
        p.insertBlock()
        assertEquals(listOf("* A", "  * child", first(0), "* B"), p.lines)
        p.caretAtEnd(1)
        p.insertBlock()
        assertEquals(listOf("* A", "  * child", first(2), first(0), "* B"), p.lines)
    }

    @Test
    fun enter_splits_a_block_row_inside_the_block() = runTest {
        val p = pane("* A\n:::\nhello world\n:::\n")
        p.moveTo(1, 1 + "hello".length)
        p.insertNewline()
        assertEquals(listOf("* A", first(0, "hello"), next(0, " world")), p.lines)
        assertEquals(2 to 1, p.caret)
    }

    @Test
    fun cmd_enter_or_escape_leaves_the_block_onto_a_new_bullet() = runTest {
        val p = pane("* A\n:::\nx\ny\n:::\n* B\n")
        p.caretAtEnd(1)
        p.indentLine()
        p.exitBlock()
        assertEquals(listOf("* A", first(2, "x"), next(2, "y"), "  * ", "* B"), p.lines)
        assertEquals(3 to 4, p.caret)
        assertFalse(p.isBlockLine())
    }

    @Test
    fun backspace_joins_block_rows_but_never_merges_across_the_block_edge() = runTest {
        val p = pane("* A\n:::\nx\ny\n:::\n* B\n* \n")
        p.caretAtTextStart(2)
        p.backspace()
        assertEquals(listOf("* A", first(0, "xy"), "* B", "* "), p.lines)
        // First row of a non-empty block: nothing happens.
        p.caretAtTextStart(1)
        p.backspace()
        assertEquals(listOf("* A", first(0, "xy"), "* B", "* "), p.lines)
        // A bullet after the block does not merge into it.
        p.caretAtTextStart(2)
        p.backspace()
        assertEquals(listOf("* A", first(0, "xy"), "* B", "* "), p.lines)
    }

    @Test
    fun backspace_on_an_empty_bullet_after_a_block_deletes_the_bullet() = runTest {
        val p = pane("* A\n:::\nxy\n:::\n* \n")
        p.caretAtTextStart(2)
        p.backspace()
        assertEquals(listOf("* A", first(0, "xy")), p.lines)
        assertEquals(1 to 3, p.caret)
    }

    @Test
    fun tab_moves_the_whole_block_and_shift_tab_brings_it_back() = runTest {
        val p = pane("* A\n:::\nx\ny\n:::\n")
        p.caretAtEnd(2)
        p.indentLine()
        assertEquals(listOf("* A", first(2, "x"), next(2, "y")), p.lines)
        p.outdentLine()
        assertEquals(listOf("* A", first(0, "x"), next(0, "y")), p.lines)
    }

    @Test
    fun a_bullet_after_a_top_level_block_cannot_be_indented_under_the_bullet_above_it() = runTest {
        val p = pane("* A\n:::\nx\n:::\n* B\n")
        p.caretAtEnd(2)
        p.indentLine()
        assertEquals(listOf("* A", first(0, "x"), "* B"), p.lines)
    }

    @Test
    fun paste_inside_a_block_keeps_the_lines_verbatim() = runTest {
        val p = pane("* A\n:::\nx\n:::\n")
        p.caretAtEnd(1)
        p.insertText(" one\n  - nested\n\n* star")
        assertEquals(
            listOf("* A", first(0, "x one"), next(0, "  - nested"), next(0, ""), next(0, "* star")),
            p.lines,
        )
    }

    @Test
    fun copied_block_text_has_no_hidden_markers() = runTest {
        val p = pane("* A\n:::\n## H\n- a\n:::\n")
        p.setSelection(1, 1, 2, p.lines[2].length)
        assertEquals("## H\n- a", p.getSelectedText())
    }

    @Test
    fun a_list_item_inside_a_block_is_not_a_bullet() = runTest {
        val p = pane("* A\n:::\n* item\n:::\n")
        assertEquals(-1, DocumentLayout.bulletAsteriskColumn(p.lines[1]))
        assertEquals(listOf("A"), p.lines.filter { DocumentLayout.bulletAsteriskColumn(it) >= 0 }.map { it.drop(2) })
        // Zooming into a block row does nothing.
        p.zoomInto(1)
        runCurrent()
        assertEquals(null, p.stateFlow.value.zoomedLineId)
    }

    @Test
    fun no_key_sequence_leaves_a_line_that_is_neither_bullet_nor_block_row() = runTest {
        val p = pane("* A\n:::\nx\n:::\n* B\n")
        val random = Random(5)
        // Kept short enough for the 2 s per-test limit of the JS browser runner.
        repeat(800) {
            when (random.nextInt(16)) {
                0, 1 -> p.insertChar('a' + random.nextInt(3))
                2, 3 -> p.insertNewline()
                4, 5 -> p.backspace()
                6 -> p.indentLine()
                7 -> p.outdentLine()
                8 -> p.moveUp(extend = random.nextBoolean())
                9 -> p.moveDown(extend = random.nextBoolean())
                10 -> p.moveLineStart()
                11 -> p.insertText(if (random.nextBoolean()) "x\ny" else "\n:::\n- z\n")
                12 -> p.insertBlock()
                13 -> p.exitBlock()
                14 -> p.deleteBlockAtCursor()
                15 -> if (random.nextBoolean()) p.undo() else p.redo()
            }
            for ((i, line) in p.lines.withIndex()) {
                assertTrue(
                    DocumentLayout.bulletAsteriskColumn(line) >= 0 || BlockLayout.isBlockLine(line),
                    "row $i is neither a bullet nor a block row: \"$line\" in ${p.lines}",
                )
            }
        }
    }
}
