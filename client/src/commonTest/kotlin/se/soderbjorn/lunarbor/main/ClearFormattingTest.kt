/*
 * ClearFormattingTest.kt (commonTest)
 * -----------------------------------
 * Tests for the palette's "Clear formatting" ([PaneBackingViewModel.clearFormatting])
 * and "Clear formatting in subtree" ([PaneBackingViewModel.clearFormattingInSubtree]),
 * over the real stack on [InMemoryFileSystem]. Pins what is stripped (heading /
 * quote prefixes, inline style markers) and what is kept (links, tags, done,
 * code rows), that the recursive form loads folders and leaves blocks alone,
 * and that both undo.
 */

package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.testing.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ClearFormattingTest {

    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })

    private suspend fun TestScope.pane(content: String): PaneBackingViewModel {
        fs.writeFile("$root/_node.md", content)
        val registry = DocumentRegistry(repo, backgroundScope)
        val pane = PaneBackingViewModel(registry, backgroundScope, "_node.md")
        pane.stateFlow.first { it.isLoaded }
        runCurrent()
        return pane
    }

    private val PaneBackingViewModel.lines get() = stateFlow.value.lines
    private fun PaneBackingViewModel.id(row: Int) = stateFlow.value.documentState!!.lineIds[row]
    private fun first(indent: Int, content: String = "") = BlockLayout.firstLine(indent, content)

    /** Lets a subtree edit run: it waits for the view to paint its progress first. */
    private fun TestScope.runBulkEdit() {
        advanceTimeBy(PaneBackingViewModel.BULK_EDIT_PAINT_MS + 1)
        runCurrent()
    }

    @Test
    fun cleared_line_strips_prefixes_and_styles_and_keeps_links_and_tags() {
        fun clear(line: String) = MarkdownStyleViewModel.clearedLine(line)?.first
        assertEquals("* Title", clear("* ## **Title**"))
        assertEquals("* a b c d", clear("* > *a* **b** ~~c~~ `d`"))
        assertEquals("* see [the plan](Plan/_node.md) #work", clear("* see **[the plan](Plan/_node.md)** #work"))
        assertEquals("* ~~Buy milk~~ #todo", clear("* ~~**Buy** milk~~ #todo"))
        assertEquals("* plain", clear("* plain"))
        // A block row keeps its list prefix; a code row is left alone.
        assertEquals(first(0, "* item b"), clear(first(0, "* item **b**")))
        assertNull(MarkdownStyleViewModel.clearedLine(BlockLayout.firstLine(0, "${BlockLayout.CODE}**x**")))
    }

    @Test
    fun clear_formatting_works_on_the_caret_row_keeps_the_caret_and_undoes() = runTest {
        val p = pane("- ## A **bold** word\n- *other*\n")
        val before = p.lines
        p.moveTo(0, p.lines[0].indexOf("word"))
        p.clearFormatting()
        assertEquals(listOf("* A bold word", "* *other*"), p.lines)
        assertEquals(0 to p.lines[0].indexOf("word"), p.stateFlow.value.let { it.cursorRow to it.cursorCol })
        p.undo()
        assertEquals(before, p.lines)
    }

    @Test
    fun clear_formatting_covers_every_row_a_selection_touches() = runTest {
        val p = pane("- *a*\n- **b**\n- `c`\n")
        p.setSelection(0, 3, 1, 4)
        p.clearFormatting()
        assertEquals(listOf("* a", "* b", "* `c`"), p.lines)
    }

    @Test
    fun clear_formatting_in_subtree_loads_folders_and_leaves_blocks_alone() = runTest {
        fs.writeFile("$root/Sub/_node.md", "- **Deep**\n> **kept**\n")
        val p = pane("- # Top [↳](<Sub/_node.md>)\n- *Beside*\n")
        val sub = p.id(0)
        assertTrue(sub in p.stateFlow.value.collapsedIds)
        p.moveTo(0, p.lines[0].length)
        p.clearFormattingInSubtree()
        runBulkEdit()
        assertEquals(listOf("* Top", "  * Deep", first(2, "**kept**"), "* *Beside*"), p.lines)
        // Loaded for the edit, the folder stays folded.
        assertTrue(sub in p.stateFlow.value.collapsedIds)
        p.undo()
        assertEquals("* # Top", p.lines[0])
    }

    @Test
    fun clear_formatting_in_subtree_from_a_block_works_on_its_item() = runTest {
        fs.writeFile("$root/Notes/_node.md", "- *child*\n")
        val p = pane("- *A*\n> **Notes**\n> [↳](<Notes/_node.md>)\n")
        p.moveTo(1, p.lines[1].length)
        p.clearFormattingInSubtree()
        runBulkEdit()
        // The block's children are cleared; the block and its sibling are not.
        assertEquals(listOf("* *A*", first(0, "**Notes**"), "  * child"), p.lines)
    }
}
