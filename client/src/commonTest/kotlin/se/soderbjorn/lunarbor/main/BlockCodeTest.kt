/*
 * BlockCodeTest.kt (commonTest)
 * -----------------------------
 * Code blocks inside a block: rows carrying the hidden
 * [BlockLayout.CODE] marker in memory, a ```` ``` ```` fence on disk.
 * Pins the round trip through the codec, the Inline code toggle across
 * several rows (and back), and how Enter, Backspace, Tab, paste, copy
 * and the other styles behave on code rows — against the real stack
 * ([PaneBackingViewModel] over [DocumentRegistry] + [NoteRepository] on
 * [InMemoryFileSystem]).
 */

package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import se.soderbjorn.lunarbor.data.InlineStyle
import se.soderbjorn.lunarbor.data.LineStyle
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.data.SubtreeCodec
import se.soderbjorn.lunarbor.testing.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BlockCodeTest {

    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })
    private lateinit var registry: DocumentRegistry

    /** Opens a pane on the root outline seeded with [content] (or what is on disk when `null`). */
    private suspend fun TestScope.pane(content: String?): PaneBackingViewModel {
        if (content != null) fs.writeFile("$root/_node.md", content)
        registry = DocumentRegistry(repo, backgroundScope)
        val pane = PaneBackingViewModel(registry, backgroundScope, "_node.md")
        pane.stateFlow.first { it.isLoaded }
        runCurrent()
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
    private fun PaneBackingViewModel.caretAtEnd(row: Int) = moveTo(row, lines[row].length)

    private fun first(content: String = "") = BlockLayout.firstLine(0, content)
    private fun next(content: String = "") = BlockLayout.nextLine(0, content)
    private val code = BlockLayout.CODE

    // ------------------------------------------------------------- disk

    @Test
    fun a_fenced_code_block_loads_as_code_rows_and_saves_back_unchanged() = runTest {
        val text = "- A\n> Setup:\n> ```\n> val x = 1\n>   * not a list\n> ```\n> after\n"
        val p = pane(text)
        assertEquals(
            listOf("* A", first("Setup:"), next("${code}val x = 1"), next("$code  * not a list"), next("after")),
            p.lines,
        )
        p.caretAtEnd(4)
        p.insertText("!")
        flush()
        assertEquals(text.replace("after", "after!"), read("_node.md"))
    }

    @Test
    fun a_fence_with_a_language_or_without_a_close_stays_plain_text() = runTest {
        val p = pane("- A\n> ```kotlin\n> val x = 1\n> ```\n- B\n> ```\n> open\n")
        assertEquals(
            listOf("* A", first("```kotlin"), next("val x = 1"), next("```"), "* B", first("```"), next("open")),
            p.lines,
        )
    }

    @Test
    fun code_that_holds_a_fence_line_gets_a_longer_fence() {
        assertEquals(
            listOf("````", "```", "x", "````"),
            BlockLayout.diskContentOf(listOf("$code```", "${code}x")),
        )
        assertEquals(listOf("$code```", "${code}x"), BlockLayout.rowContentsOf(listOf("````", "```", "x", "````")))
    }

    @Test
    fun a_block_that_starts_with_code_is_titled_by_its_first_code_line() {
        assertEquals("val x = 1", SubtreeCodec.blockTitleOf(listOf("```", "val x = 1", "```")))
    }

    // ------------------------------------------------------------ toggle

    @Test
    fun inline_code_across_rows_of_a_block_makes_a_code_block_and_toggles_back() = runTest {
        val p = pane("- A\n> intro\n> fun main() {\n>   println()\n> }\n")
        // Select from inside "fun" down into the last row.
        p.setSelection(2, 3, 4, 2)
        p.applyInlineStyle(InlineStyle.INLINE_CODE)
        assertEquals(
            listOf("* A", first("intro"), next("${code}fun main() {"), next("$code  println()"), next("$code}")),
            p.lines,
        )
        assertEquals(true, p.codeBlockState())
        flush()
        assertEquals("- A\n> intro\n> ```\n> fun main() {\n>   println()\n> }\n> ```\n", read("_node.md"))

        // The caret alone on a code row takes the whole run out again.
        p.moveTo(3, p.lines[3].length)
        p.applyInlineStyle(InlineStyle.INLINE_CODE)
        assertEquals(listOf("* A", first("intro"), next("fun main() {"), next("  println()"), next("}")), p.lines)
        assertNull(p.codeBlockState())
        // Undo brings the code back.
        p.undo()
        assertEquals(next("$code  println()"), p.lines[3])
    }

    @Test
    fun a_selection_ending_at_the_start_of_a_row_leaves_that_row_out() = runTest {
        val p = pane("- A\n> one\n> two\n> three\n")
        p.setSelection(1, 1, 3, DocumentLayout.textStartCol(p.lines[3]))
        p.applyInlineStyle(InlineStyle.INLINE_CODE)
        assertEquals(listOf("* A", first("${code}one"), next("${code}two"), next("three")), p.lines)
    }

    @Test
    fun rows_outside_one_block_do_not_become_code() = runTest {
        val p = pane("- A\n- B\n")
        p.setSelection(0, 2, 1, 3)
        p.applyInlineStyle(InlineStyle.INLINE_CODE)
        assertEquals(listOf("* A", "* B"), p.lines)
    }

    // ----------------------------------------------------------- editing

    @Test
    fun enter_in_code_keeps_the_indent_and_an_empty_last_row_ends_the_code() = runTest {
        val p = pane("- A\n> ```\n> if (x) {\n> ```\n")
        p.caretAtEnd(1)
        p.insertNewline()
        assertEquals(next("$code"), p.lines[2])
        p.insertText("    y()")
        p.insertNewline()
        // The new row inherits the four spaces.
        assertEquals(next("$code    "), p.lines[3])
        assertEquals(3 to 6, p.caret)
        // Clear the indent, then Enter on the empty last code row leaves the code.
        p.backspace(); p.backspace(); p.backspace(); p.backspace()
        assertEquals(next("$code"), p.lines[3])
        p.insertNewline()
        assertEquals(next(""), p.lines[3])
        assertEquals(3 to 1, p.caret)
    }

    @Test
    fun backspace_at_the_start_of_code_leaves_the_code_and_later_rows_merge() = runTest {
        val p = pane("- A\n> ```\n> one\n> two\n> ```\n")
        // Second code row: merges into the first, as in any block.
        p.moveTo(2, DocumentLayout.caretStartCol(p.lines[2]))
        p.backspace()
        assertEquals(listOf("* A", first("${code}onetwo")), p.lines)
        // First code row: leaves the code.
        p.moveTo(1, DocumentLayout.caretStartCol(p.lines[1]))
        p.backspace()
        assertEquals(listOf("* A", first("onetwo")), p.lines)
    }

    @Test
    fun tab_indents_code_freely_and_list_or_heading_text_stays_verbatim() = runTest {
        val p = pane("- A\n> ```\n> # comment\n> * item\n> ```\n")
        // Code rows: the caret starts right after the hidden marker.
        assertEquals(2, DocumentLayout.caretStartCol(p.lines[1]))
        assertEquals(2, DocumentLayout.caretStartCol(p.lines[2]))
        p.moveTo(1, 2)
        p.indentLine()
        assertEquals(first("$code  # comment"), p.lines[1])
        // A heading style is not applied to code.
        p.applyLineStyle(LineStyle.HEADING_2)
        assertEquals(first("$code  # comment"), p.lines[1])
        p.outdentLine()
        assertEquals(first("$code# comment"), p.lines[1])
    }

    @Test
    fun pasting_lines_into_code_makes_code_rows_and_copy_drops_the_markers() = runTest {
        val p = pane("- A\n> ```\n> x\n> ```\n")
        p.caretAtEnd(1)
        p.insertText("1\n  y\nz")
        assertEquals(listOf("* A", first("${code}x1"), next("$code  y"), next("${code}z")), p.lines)
        p.setSelection(1, 2, 3, p.lines[3].length)
        assertEquals("x1\n  y\nz", p.getSelectedText())
    }
}
