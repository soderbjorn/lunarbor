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

package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.testing.InMemoryFileSystem
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
        if (content != null) fs.writeFile("$root/_node.md", content)
        registry = DocumentRegistry(repo, backgroundScope)
        val pane = PaneBackingViewModel(registry, backgroundScope, "_node.md")
        pane.stateFlow.first { it.isLoaded }
        runCurrent()
        for (id in pane.stateFlow.value.collapsedIds) pane.toggleCollapse(id)
        return pane
    }

    /** Saves the pane's document now. */
    private suspend fun flush() {
        val doc = registry.acquire("_node.md")
        doc.flush()
        registry.release("_node.md")
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
        val p = pane("- Trip\n- Home\n")
        p.caretAtEnd(0)
        p.insertBlock()
        assertEquals(listOf("* Trip", first(0), "* Home"), p.lines)
        assertEquals(1 to 1, p.caret)
        p.insertText("## Packing list")
        p.insertNewline()
        p.insertText("- passport")
        // Enter on a list item continues the list.
        p.insertNewline()
        p.insertText("charger")
        val edited = p.lines
        assertEquals(
            listOf("* Trip", first(0, "## Packing list"), next(0, "- passport"), next(0, "- charger"), "* Home"),
            edited,
        )
        flush()
        assertEquals("- Trip\n> ## Packing list\n> - passport\n> - charger\n- Home\n", read("_node.md"))
        val reopened = pane(null)
        assertEquals(edited, reopened.lines)
    }

    @Test
    fun insert_markdown_file_as_block_copies_its_text_and_can_be_undone() = runTest {
        val p = pane("- Trip\n- Home\n")
        p.caretAtEnd(0)
        p.insertMarkdownAsBlock("\n# Packing\r\n\n- passport\n```\ncode\n```\n\n")
        val c = BlockLayout.CODE
        assertEquals(
            listOf("* Trip", first(0, "# Packing"), next(0), next(0, "- passport"), next(0, "${c}code"), "* Home"),
            p.lines,
        )
        assertEquals(4 to 1 + "${c}code".length, p.caret)
        flush()
        assertEquals("- Trip\n> # Packing\n>\n> - passport\n> ```\n> code\n> ```\n- Home\n", read("_node.md"))
        p.undo()
        assertEquals(listOf("* Trip", "* Home"), p.lines)
    }

    @Test
    fun insert_markdown_file_as_block_on_an_empty_leaf_fills_that_bullet() = runTest {
        val p = pane("- A\n-\n")
        p.caretAtEnd(1)
        p.insertMarkdownAsBlock("one\ntwo")
        assertEquals(listOf("* A", first(0, "one"), next(0, "two")), p.lines)
        assertEquals(2 to 1 + "two".length, p.caret)
    }

    @Test
    fun a_block_indented_under_a_leaf_makes_the_leaf_folder_backed() = runTest {
        val p = pane("- Leaf\n")
        // A new child bullet, then Insert block turns it into a block item.
        p.caretAtEnd(0)
        p.insertNewline()
        p.indentLine()
        p.insertBlock()
        p.insertText("notes")
        assertEquals(listOf("* Leaf", first(2, "notes")), p.lines)
        flush()
        assertEquals("- Leaf [↳](<Leaf/_node.md>)\n", read("_node.md"))
        assertEquals("> notes\n", read("Leaf/_node.md"))
        assertTrue(p.isPromotedRef(p.id(0)))
    }

    @Test
    fun content_with_a_fence_line_round_trips() = runTest {
        val p = pane("- A\n")
        p.caretAtEnd(0)
        p.insertBlock()
        p.insertText("before\n:::\n::::\nafter")
        val edited = p.lines
        assertEquals(listOf("* A", first(0, "before"), next(0, ":::"), next(0, "::::"), next(0, "after")), edited)
        flush()
        assertEquals("- A\n> before\n> :::\n> ::::\n> after\n", read("_node.md"))
        assertEquals(edited, pane(null).lines)
    }

    @Test
    fun deleting_a_block_can_be_undone_from_every_entry_point() = runTest {
        val p = pane("- A\n> x\n> y\n- B\n")
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
        val p = pane("- A\n  * child\n- B\n")
        p.caretAtEnd(0)
        p.insertBlock()
        assertEquals(listOf("* A", "  * child", first(0), "* B"), p.lines)
        p.caretAtEnd(1)
        p.insertBlock()
        assertEquals(listOf("* A", "  * child", first(2), first(0), "* B"), p.lines)
    }

    @Test
    fun enter_splits_a_block_row_inside_the_block() = runTest {
        val p = pane("- A\n> hello world\n")
        p.moveTo(1, 1 + "hello".length)
        p.insertNewline()
        assertEquals(listOf("* A", first(0, "hello"), next(0, " world")), p.lines)
        assertEquals(2 to 1, p.caret)
    }

    @Test
    fun cmd_enter_or_escape_leaves_the_block_onto_a_new_bullet() = runTest {
        val p = pane("- A\n> x\n> y\n- B\n")
        p.caretAtEnd(1)
        p.exitBlock()
        assertEquals(listOf("* A", first(0, "x"), next(0, "y"), "* ", "* B"), p.lines)
        assertEquals(3 to 2, p.caret)
        assertFalse(p.isBlockLine())
    }

    @Test
    fun arrow_down_on_the_last_row_of_a_block_leaves_it_only_when_nothing_follows() = runTest {
        val p = pane("- A\n> x\n> y\n")
        // Not the last row: the caret just moves.
        p.caretAtEnd(1)
        assertFalse(p.moveDownOutOfBlock())
        p.caretAtEnd(2)
        assertTrue(p.moveDownOutOfBlock())
        assertEquals(listOf("* A", first(0, "x"), next(0, "y"), "* "), p.lines)
        assertEquals(3 to 2, p.caret)
        // Back in the block without typing: the new bullet goes again.
        p.caretAtEnd(2)
        assertEquals(listOf("* A", first(0, "x"), next(0, "y")), p.lines)
        // Typed in, it stays; a row below the block now exists, so Arrow
        // Down just moves.
        assertTrue(p.moveDownOutOfBlock())
        p.insertChar('z')
        p.caretAtEnd(2)
        assertFalse(p.moveDownOutOfBlock())
        assertEquals(listOf("* A", first(0, "x"), next(0, "y"), "* z"), p.lines)
    }

    @Test
    fun insert_block_on_an_empty_bullet_turns_the_bullet_into_the_block() = runTest {
        val p = pane("- A\n-\n- B\n")
        val id = p.id(1)
        p.caretAtEnd(1)
        p.insertBlock()
        assertEquals(listOf("* A", first(0), "* B"), p.lines)
        assertEquals(1 to 1, p.caret)
        assertEquals(id, p.id(1))
    }

    @Test
    fun backspace_joins_block_rows_but_never_merges_across_the_block_edge() = runTest {
        val p = pane("- A\n> x\n> y\n- B\n-\n")
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
        val p = pane("- A\n> xy\n-\n")
        p.caretAtTextStart(2)
        p.backspace()
        assertEquals(listOf("* A", first(0, "xy")), p.lines)
        assertEquals(1 to 3, p.caret)
    }

    @Test
    fun tab_indents_one_line_inside_the_block_and_never_makes_a_folder() = runTest {
        val p = pane("- A\n> * x\n> * y\n")
        // The first row has nothing to nest under.
        p.caretAtEnd(1)
        p.indentLine()
        assertEquals(listOf("* A", first(0, "* x"), next(0, "* y")), p.lines)
        p.caretAtEnd(2)
        p.indentLine()
        assertEquals(listOf("* A", first(0, "* x"), next(0, "  * y")), p.lines)
        assertEquals(2 to p.lines[2].length, p.caret)
        // At most one level deeper than the row above.
        p.indentLine()
        assertEquals(next(0, "  * y"), p.lines[2])
        // The caret starts after the list prefix; Enter continues the list
        // at its depth, and Enter on the empty item ends the list.
        assertEquals(1 + 4, DocumentLayout.caretStartCol(p.lines[2]))
        p.insertNewline()
        assertEquals(next(0, "  * "), p.lines[3])
        p.insertNewline()
        assertEquals(next(0, ""), p.lines[3])
        p.backspace()
        flush()
        assertEquals("- A\n> * x\n>   * y\n", read("_node.md"))
        assertFalse(p.isPromotedRef(p.id(0)))
        p.caretAtEnd(2)
        p.outdentLine()
        assertEquals(listOf("* A", first(0, "* x"), next(0, "* y")), p.lines)
    }

    @Test
    fun a_bullet_after_a_block_indents_under_the_block_which_then_gets_a_folder() = runTest {
        val p = pane("- A\n> ## Packing\n> list\n- B\n")
        p.caretAtEnd(3)
        p.indentLine()
        assertEquals(listOf("* A", first(0, "## Packing"), next(0, "list"), "  * B"), p.lines)
        flush()
        assertEquals("- A\n> ## Packing\n> list\n> [↳](<Packing/_node.md>)\n", read("_node.md"))
        assertEquals("- B\n", read("Packing/_node.md"))
        assertTrue(p.isPromotedRef(p.id(1)))

        // Reopened, the block shows in its parent; its children load on
        // expand, after the block's own rows.
        val q = pane(null)
        runCurrent()
        assertEquals(listOf("* A", first(0, "## Packing"), next(0, "list"), "  * B"), q.lines)

        // Outdenting the only child makes it a plain block again.
        q.caretAtEnd(3)
        q.outdentLine()
        flush()
        assertEquals("- A\n> ## Packing\n> list\n- B\n", read("_node.md"))
        assertEquals(null, read("Packing/_node.md"))
    }

    @Test
    fun convert_block_to_nodes_makes_one_bullet_per_paragraph_and_undoes() = runTest {
        val p = pane("- Trip\n> First line\n> goes on\n>\n> - item\n- B\n")
        val original = p.lines
        p.caretAtEnd(2)
        p.convertBlockToNodes()
        assertEquals(listOf("* Trip", "* First line goes on", "* item", "* B"), p.lines)
        assertEquals(1 to p.lines[1].length, p.caret)
        flush()
        assertEquals("- Trip\n- First line goes on\n- item\n- B\n", read("_node.md"))
        p.undo()
        assertEquals(original, p.lines)
    }

    @Test
    fun converting_a_block_with_children_keeps_its_folder_under_the_first_bullet() = runTest {
        val p = pane("- A\n> ## Packing\n> list\n- B\n")
        p.caretAtEnd(3)
        p.indentLine()
        flush()
        assertEquals("- B\n", read("Packing/_node.md"))
        val blockId = p.id(1)

        p.caretAtEnd(2)
        p.convertBlockToNodes()
        assertEquals(listOf("* A", "* ## Packing", "  * B", "* list"), p.lines)
        assertEquals(blockId, p.id(1))
        flush()
        assertEquals("- A\n- ## Packing [↳](<Packing/_node.md>)\n- list\n", read("_node.md"))
        assertEquals("- B\n", read("Packing/_node.md"))
    }

    @Test
    fun a_large_block_shows_its_preview_until_expanded() = runTest {
        val body = (1..20).joinToString("\n") { "> line $it" }
        val p = pane("- A\n$body\n- B\n")
        val s0 = p.stateFlow.value
        val block = 1..20
        assertEquals(12, p.hiddenBlockRows(s0, block))
        // The preview, then the row after the block.
        assertEquals((0..8).toList() + 21, p.visibleRows(s0, 0, s0.lines.lastIndex))
        // Arrow Down off the preview's last row skips the hidden rows.
        p.caretAtEnd(8)
        p.moveDown(extend = false)
        assertEquals(21, p.caret.first)

        p.toggleBlockExpanded(p.id(1))
        val s1 = p.stateFlow.value
        assertEquals(0, p.hiddenBlockRows(s1, block))
        assertEquals(22, p.visibleRows(s1, 0, s1.lines.lastIndex).size)

        // Collapsing with the caret in the hidden rows brings it back up.
        p.caretAtEnd(15)
        p.toggleBlockExpanded(p.id(1))
        assertEquals(8 to p.lines[8].length, p.caret)

        // Enter past the preview's end opens the block instead of losing the caret.
        p.insertNewline()
        val s2 = p.stateFlow.value
        assertEquals(9, s2.cursorRow)
        assertTrue(p.id(1) in s2.expandedBlockIds)

        // A small block never folds.
        assertEquals(null, pane("- A\n> x\n").let { q -> q.hiddenBlockRows(q.stateFlow.value, 1..1) })
    }

    @Test
    fun a_block_can_be_left_upwards_onto_a_bullet_before_it() = runTest {
        val p = pane("> * item\n> second\n- B\n")
        val original = p.lines
        val blockId = p.id(0)

        // Enter at the start of the first row (after the list prefix).
        p.caretAtTextStart(0)
        p.insertNewline()
        assertEquals(listOf("* ", first(0, "* item"), next(0, "second"), "* B"), p.lines)
        assertEquals(0 to 2, p.caret)
        assertEquals(blockId, p.id(1))
        p.insertText("before")
        assertEquals("* before", p.lines[0])
        p.undo()
        p.undo()
        assertEquals(original, p.lines, "after undo")

        // Arrow Up on the first row with nothing above; left unused, the
        // bullet goes away again.
        p.caretAtEnd(0)
        assertTrue(p.moveUpOutOfBlock())
        assertEquals("* ", p.lines[0])
        // (the browser moves the caret; the view syncs it with moveTo)
        p.caretAtEnd(1)
        assertEquals(original, p.lines, "after leaving")

        // Not on a later row, nor with something above.
        p.caretAtEnd(1)
        assertFalse(p.moveUpOutOfBlock())

        // Shift-Cmd-Enter from anywhere in the block.
        p.caretAtEnd(1)
        p.exitBlockAbove()
        assertEquals(listOf("* ", first(0, "* item"), next(0, "second"), "* B"), p.lines)
    }

    @Test
    fun zooming_into_a_block_shows_the_block_above_its_children() = runTest {
        val p = pane("- A\n> ## Packing\n")
        p.zoomInto(1)
        runCurrent()
        val s = p.stateFlow.value
        assertEquals(p.id(1), s.zoomedLineId)
        // A childless block gets no placeholder child: its own rows are
        // the page, and the caret starts in them.
        assertEquals(listOf("* A", first(0, "## Packing")), p.lines)
        assertEquals(1 to DocumentLayout.caretStartCol(p.lines[1]), p.caret)
        val zoom = p.zoomInfo(s)!!
        assertEquals(1, zoom.startRow)
        assertEquals(1, zoom.endRowInclusive)
        assertEquals("Packing", zoom.titleText)
        // Cmd-Enter (or Arrow Down off the last row) opens its first child…
        p.caretAtEnd(1)
        p.exitBlock()
        assertEquals(listOf("* A", first(0, "## Packing"), "  * "), p.lines)
        assertEquals(2 to 4, p.caret)
        // …a throwaway: back into the block untouched, it is gone again.
        p.caretAtEnd(1)
        assertEquals(listOf("* A", first(0, "## Packing")), p.lines)
        assertEquals(p.id(1), p.stateFlow.value.zoomedLineId)
        // Typed in, it stays.
        assertTrue(p.moveDownOutOfBlock())
        p.insertChar('x')
        p.caretAtEnd(1)
        assertEquals(listOf("* A", first(0, "## Packing"), "  * x"), p.lines)
        // An untouched one also goes when the pane zooms out.
        p.caretAtEnd(1)
        p.exitBlock()
        assertEquals(4, p.lines.size)
        p.zoomOut()
        assertEquals(listOf("* A", first(0, "## Packing"), "  * x"), p.lines)
    }

    @Test
    fun a_folded_block_keeps_its_rows_and_hides_its_children() = runTest {
        val p = pane("- A\n> x\n> y\n- B\n")
        p.caretAtEnd(3)
        p.indentLine()
        p.toggleCollapse(p.id(1))
        val s = p.stateFlow.value
        val visible = DocumentLayout.visibleRowsOf(s.lines, s.documentState!!.lineIds, s.collapsedIds, 0, s.lines.lastIndex)
        assertEquals(listOf(0, 1, 2), visible)
        // Deleting the block takes its children along.
        p.deleteBlock(p.id(1))
        assertEquals(listOf("* A"), p.lines)
    }

    @Test
    fun convert_a_note_to_a_node_then_trash_the_note() = runTest {
        fs.writeFile("$root/Plan.md", "# Plan\n\nBody\n* item\n")
        fs.writeFile("$root/Links.md", "see [p](lunarbor:/Plan.md)\n")
        val p = pane("- A\n")
        val id = p.convertNoteToNode("Plan.md")!!
        assertEquals(listOf("* A", "* Plan", first(2, "Body"), next(2, "* item")), p.lines)
        assertEquals(1, p.caret.first)
        assertEquals(null, p.trashConvertedNote("Plan.md", id))
        assertEquals("- A\n- Plan [↳](<Plan/_node.md>)\n", read("_node.md"))
        assertEquals("> Body\n> * item\n", read("Plan/_node.md"))
        assertEquals(null, read("Plan.md"))
        assertEquals("see [p](lunarbor:/Plan)\n", read("Links.md"))
    }

    @Test
    fun convert_while_zoomed_appends_under_the_zoom_target_and_undoes() = runTest {
        fs.writeFile("$root/N.md", "x")
        val p = pane("- A\n  * a1\n- B\n")
        p.zoomInto(0)
        p.convertNoteToNode("N.md")
        assertEquals(listOf("* A", "  * a1", "  * N", first(4, "x"), "* B"), p.lines)
        p.undo()
        assertEquals(listOf("* A", "  * a1", "* B"), p.lines)
    }

    @Test
    fun caret_item_folds_and_unfolds_bullets_and_blocks() = runTest {
        val p = pane("- A\n  * a1\n> x\n> y\n- B\n")
        p.caretAtEnd(4)
        p.indentLine()
        // A bullet with a child: Cmd-Up folds it, Cmd-Up again is a no-op.
        p.caretAtEnd(0)
        p.setCaretItemFolded(true)
        assertTrue(p.id(0) in p.stateFlow.value.collapsedIds)
        p.setCaretItemFolded(true)
        assertTrue(p.id(0) in p.stateFlow.value.collapsedIds)
        p.setCaretItemFolded(false)
        assertFalse(p.id(0) in p.stateFlow.value.collapsedIds)
        // Inside a block, the block itself folds.
        p.caretAtEnd(3)
        p.setCaretItemFolded(true)
        assertTrue(p.id(2) in p.stateFlow.value.collapsedIds)
        // A leaf does nothing.
        val before = p.stateFlow.value.collapsedIds
        p.caretAtEnd(1)
        p.setCaretItemFolded(true)
        assertEquals(before, p.stateFlow.value.collapsedIds)
    }

    @Test
    fun paste_inside_a_block_keeps_the_lines_verbatim() = runTest {
        val p = pane("- A\n> x\n")
        p.caretAtEnd(1)
        p.insertText(" one\n  - nested\n\n* star")
        assertEquals(
            listOf("* A", first(0, "x one"), next(0, "  - nested"), next(0, ""), next(0, "* star")),
            p.lines,
        )
    }

    @Test
    fun copied_block_text_has_no_hidden_markers() = runTest {
        val p = pane("- A\n> ## H\n> - a\n")
        p.setSelection(1, 1, 2, p.lines[2].length)
        assertEquals("## H\n- a", p.getSelectedText())
    }

    @Test
    fun a_list_item_inside_a_block_is_not_a_bullet() = runTest {
        val p = pane("- A\n> * item\n")
        assertEquals(-1, DocumentLayout.bulletAsteriskColumn(p.lines[1]))
        assertEquals(listOf("A"), p.lines.filter { DocumentLayout.bulletAsteriskColumn(it) >= 0 }.map { it.drop(2) })
        // Zooming into a block's list item zooms into the block, never
        // into the item.
        p.zoomInto(1)
        runCurrent()
        assertEquals(p.id(1), p.stateFlow.value.zoomedLineId)
    }

    @Test
    fun no_key_sequence_leaves_a_line_that_is_neither_bullet_nor_block_row() = runTest {
        val p = pane("- A\n> x\n- B\n")
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
