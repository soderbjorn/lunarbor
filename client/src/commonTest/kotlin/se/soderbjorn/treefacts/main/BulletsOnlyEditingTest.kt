/*
 * BulletsOnlyEditingTest.kt (commonTest)
 * --------------------------------------
 * Tests for TRF-4 "Bullets only in the outline": every line of a
 * `.treefacts` node is a bullet. Drives a real [PaneBackingViewModel] over
 * a [DocumentRegistry] + [NoteRepository] on [InMemoryFileSystem], the
 * same stack the app runs, and checks Enter, Backspace, paste and
 * selection deletion never leave a non-bullet line — plus a randomized
 * key-sequence sweep over the same intents.
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
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BulletsOnlyEditingTest {

    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })

    /**
     * Opens a pane on the root outline seeded with [content], with every
     * subtree expanded (panes fold parents by default). [content] is
     * written as-is; indented rows load verbatim as nested lines.
     */
    private suspend fun TestScope.pane(content: String?): PaneBackingViewModel {
        if (content != null) fs.writeFile("$root/.treefacts", content)
        val registry = DocumentRegistry(repo, backgroundScope)
        val pane = PaneBackingViewModel(registry, backgroundScope, ".treefacts")
        pane.stateFlow.first { it.isLoaded }
        runCurrent()
        for (id in pane.stateFlow.value.collapsedIds) pane.toggleCollapse(id)
        return pane
    }

    private val PaneBackingViewModel.lines get() = stateFlow.value.lines
    private val PaneBackingViewModel.caret get() = stateFlow.value.let { it.cursorRow to it.cursorCol }
    private fun PaneBackingViewModel.id(row: Int) = stateFlow.value.documentState!!.lineIds[row]

    /** Puts the caret at the text start of [row]. */
    private fun PaneBackingViewModel.caretAtTextStart(row: Int) =
        moveTo(row, DocumentLayout.textStartCol(lines[row]))

    private fun PaneBackingViewModel.caretAtEnd(row: Int) = moveTo(row, lines[row].length)

    private fun assertAllBullets(lines: List<String>) {
        for ((i, line) in lines.withIndex()) {
            assertTrue(DocumentLayout.bulletAsteriskColumn(line) >= 0, "row $i is not a bullet: \"$line\" in $lines")
        }
    }

    // ----------------------------------------------------------- empty doc

    @Test
    fun an_empty_outline_is_one_empty_bullet() = runTest {
        val p = pane(null)
        assertEquals(listOf("* "), p.lines)
        p.insertChar('a')
        assertEquals(listOf("* a"), p.lines)
    }

    // --------------------------------------------------------------- Enter

    @Test
    fun enter_on_an_empty_top_level_bullet_opens_another_bullet() = runTest {
        val p = pane("* A\n")
        p.caretAtEnd(0)
        p.insertNewline()
        p.insertNewline()
        assertEquals(listOf("* A", "* ", "* "), p.lines)
        assertEquals(2 to 2, p.caret)
    }

    @Test
    fun enter_on_an_empty_last_child_outdents_it() = runTest {
        val p = pane("* A\n")
        p.caretAtEnd(0)
        p.insertNewline()
        p.indentLine()
        assertEquals(listOf("* A", "  * "), p.lines)
        p.insertNewline()
        assertEquals(listOf("* A", "* "), p.lines)
        assertEquals(1 to 2, p.caret)
    }

    @Test
    fun enter_on_an_empty_child_with_a_sibling_below_does_not_outdent() = runTest {
        val p = pane("* A\n  * \n  * B\n")
        p.caretAtTextStart(1)
        p.insertNewline()
        assertAllBullets(p.lines)
        assertEquals(listOf("* A", "  * ", "  * ", "  * B"), p.lines)
    }

    // ----------------------------------------------------------- Backspace

    @Test
    fun backspace_on_an_empty_first_bullet_deletes_it_and_keeps_the_next_rows_identity() = runTest {
        val p = pane("* \n* B\n")
        val idB = p.id(1)
        p.caretAtTextStart(0)
        p.backspace()
        assertEquals(listOf("* B"), p.lines)
        assertEquals(idB, p.id(0))
        assertEquals(0 to 2, p.caret)
    }

    @Test
    fun backspace_on_the_only_empty_bullet_keeps_it() = runTest {
        val p = pane(null)
        p.caretAtTextStart(0)
        p.backspace()
        assertEquals(listOf("* "), p.lines)
    }

    @Test
    fun backspace_at_the_start_of_a_non_empty_first_bullet_never_unbullets_it() = runTest {
        val p = pane("* A\n* B\n")
        p.caretAtTextStart(0)
        p.backspace()
        assertEquals(listOf("* A", "* B"), p.lines)
    }

    @Test
    fun backspace_on_an_empty_top_level_bullet_merges_it_into_the_row_above() = runTest {
        val p = pane("* A\n* \n* B\n")
        p.caretAtTextStart(1)
        p.backspace()
        assertEquals(listOf("* A", "* B"), p.lines)
        assertEquals(0 to 3, p.caret)
    }

    @Test
    fun backspace_merges_a_bullets_text_into_the_row_above() = runTest {
        val p = pane("* A\n* B\n")
        p.caretAtTextStart(1)
        p.backspace()
        assertEquals(listOf("* AB"), p.lines)
    }

    @Test
    fun backspace_on_an_empty_bullet_below_a_folded_subtree_deletes_it() = runTest {
        val p = pane("* A\n  * child\n* \n")
        p.toggleCollapse(p.id(0))
        p.caretAtTextStart(2)
        p.backspace()
        assertEquals(listOf("* A", "  * child"), p.lines)
        assertEquals(0 to 3, p.caret)
    }

    @Test
    fun backspace_on_a_non_empty_bullet_below_a_folded_subtree_is_refused() = runTest {
        val p = pane("* A\n  * child\n* B\n")
        p.toggleCollapse(p.id(0))
        p.caretAtTextStart(2)
        p.backspace()
        assertEquals(listOf("* A", "  * child", "* B"), p.lines)
    }

    @Test
    fun backspace_on_the_first_empty_bullet_of_a_zoom_deletes_it() = runTest {
        val p = pane("* A\n  * \n  * B\n* C\n")
        p.zoomInto(0)
        runCurrent()
        p.caretAtTextStart(1)
        p.backspace()
        assertEquals(listOf("* A", "  * B", "* C"), p.lines)
        assertEquals(1 to 4, p.caret)
    }

    // --------------------------------------------------------------- paste

    @Test
    fun pasting_multi_line_text_makes_one_bullet_per_line() = runTest {
        val p = pane(null)
        p.caretAtTextStart(0)
        p.insertText("first\nsecond\n\nthird\n")
        assertEquals(listOf("* first", "* second", "* third"), p.lines)
        assertEquals(2 to 7, p.caret)
    }

    @Test
    fun pasting_inside_a_child_keeps_the_pasted_lines_at_its_depth() = runTest {
        val p = pane("* A\n  * x\n")
        p.caretAtEnd(1)
        p.insertText(" one\r\ntwo\rthree")
        assertEquals(listOf("* A", "  * x one", "  * two", "  * three"), p.lines)
    }

    @Test
    fun copy_and_paste_of_a_subtree_keeps_its_shape_at_the_new_depth() = runTest {
        val p = pane("* A\n  * B\n    * C\n* D\n")
        p.setSelection(1, 4, 2, p.lines[2].length)
        val copied = p.getSelectedText()!!
        assertEquals("  * B\n    * C", copied)
        p.caretAtEnd(3)
        p.insertNewline()
        p.insertText(copied)
        assertEquals(listOf("* A", "  * B", "    * C", "* D", "* B", "  * C"), p.lines)
    }

    @Test
    fun pasting_a_markdown_list_turns_its_items_into_bullets() = runTest {
        val p = pane(null)
        p.caretAtTextStart(0)
        p.insertText("- one\n\t- two\n- three")
        assertEquals(listOf("* one", "  * two", "* three"), p.lines)
    }

    @Test
    fun cut_and_paste_of_whole_bullets_round_trips() = runTest {
        val p = pane("* A\n* B\n* C\n")
        p.setSelection(1, 2, 2, p.lines[2].length)
        val cut = p.onCutRequested()!!
        assertEquals(listOf("* A", "* "), p.lines)
        p.insertText(cut)
        assertEquals(listOf("* A", "* B", "* C"), p.lines)
    }

    // ------------------------------------------------------------ selection

    @Test
    fun deleting_a_select_all_leaves_one_empty_bullet() = runTest {
        val p = pane("* A\n  * B\n* C\n")
        p.selectAll()
        p.backspace()
        assertEquals(listOf("* "), p.lines)
        p.selectAll()
        p.insertChar('x')
        assertEquals(listOf("* x"), p.lines)
    }

    // ------------------------------------------------------ key sequences

    @Test
    fun no_key_sequence_produces_a_non_bullet_line() = runTest {
        val p = pane("* A\n  * B\n    * C\n* D\n")
        val random = Random(4)
        repeat(3000) {
            when (random.nextInt(14)) {
                0, 1 -> p.insertChar('a' + random.nextInt(3))
                2, 3 -> p.insertNewline()
                4, 5, 6 -> p.backspace()
                7 -> p.indentLine()
                8 -> p.outdentLine()
                9 -> p.moveUp(extend = random.nextBoolean())
                10 -> p.moveDown(extend = random.nextBoolean())
                11 -> p.moveLineStart()
                12 -> p.insertText(if (random.nextBoolean()) "x\ny" else "\n\n- z\n")
                13 -> p.selectAll()
            }
            assertAllBullets(p.lines)
        }
    }

    // ------------------------------------------------------- pure helper

    @Test
    fun bullet_lines_for_paste_rules() {
        assertEquals("one line", bulletLinesForPaste("one line", 4))
        assertEquals("", bulletLinesForPaste("\n \n", 0))
        assertEquals("a\n    * b", bulletLinesForPaste("a\nb", 4))
        // Copied from mid-text: the least-indented further line is the reference.
        assertEquals("a\n  * b\n* c", bulletLinesForPaste("a\n    * b\n  * c", 0))
        // A level can never be skipped.
        assertEquals("a\n  * b", bulletLinesForPaste("* a\n        * b", 0))
        // Never shallower than the caret row.
        assertEquals("a\n  * b", bulletLinesForPaste("    * a\n* b", 2))
    }
}
