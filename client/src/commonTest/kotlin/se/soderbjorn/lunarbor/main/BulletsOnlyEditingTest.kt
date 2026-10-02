/*
 * BulletsOnlyEditingTest.kt (commonTest)
 * --------------------------------------
 * Tests for TRF-4 "Bullets only in the outline": every line of a
 * `_node.md` node is a bullet. Drives a real [PaneBackingViewModel] over
 * a [DocumentRegistry] + [NoteRepository] on [InMemoryFileSystem], the
 * same stack the app runs, and checks Enter, Backspace, paste and
 * selection deletion never leave a non-bullet line — plus a randomized
 * key-sequence sweep over the same intents.
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
        if (content != null) fs.writeFile("$root/_node.md", content)
        val registry = DocumentRegistry(repo, backgroundScope)
        val pane = PaneBackingViewModel(registry, backgroundScope, "_node.md")
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
        val p = pane("- A\n")
        p.caretAtEnd(0)
        p.insertNewline()
        p.insertNewline()
        assertEquals(listOf("* A", "* ", "* "), p.lines)
        assertEquals(2 to 2, p.caret)
    }

    @Test
    fun enter_on_an_empty_last_child_outdents_it() = runTest {
        val p = pane("- A\n")
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
        val p = pane("- A\n  * \n  * B\n")
        p.caretAtTextStart(1)
        p.insertNewline()
        assertAllBullets(p.lines)
        assertEquals(listOf("* A", "  * ", "  * ", "  * B"), p.lines)
    }

    // ----------------------------------------------------------- Backspace

    @Test
    fun backspace_on_an_empty_first_bullet_deletes_it_and_keeps_the_next_rows_identity() = runTest {
        val p = pane("-\n- B\n")
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
        val p = pane("- A\n- B\n")
        p.caretAtTextStart(0)
        p.backspace()
        assertEquals(listOf("* A", "* B"), p.lines)
    }

    @Test
    fun backspace_on_an_empty_top_level_bullet_merges_it_into_the_row_above() = runTest {
        val p = pane("- A\n-\n- B\n")
        p.caretAtTextStart(1)
        p.backspace()
        assertEquals(listOf("* A", "* B"), p.lines)
        assertEquals(0 to 3, p.caret)
    }

    @Test
    fun backspace_merges_a_bullets_text_into_the_row_above() = runTest {
        val p = pane("- A\n- B\n")
        p.caretAtTextStart(1)
        p.backspace()
        assertEquals(listOf("* AB"), p.lines)
    }

    @Test
    fun backspace_on_an_empty_bullet_below_a_folded_subtree_deletes_it() = runTest {
        val p = pane("- A\n  * child\n-\n")
        p.toggleCollapse(p.id(0))
        p.caretAtTextStart(2)
        p.backspace()
        assertEquals(listOf("* A", "  * child"), p.lines)
        assertEquals(0 to 3, p.caret)
    }

    @Test
    fun backspace_on_a_non_empty_bullet_below_a_folded_subtree_is_refused() = runTest {
        val p = pane("- A\n  * child\n- B\n")
        p.toggleCollapse(p.id(0))
        p.caretAtTextStart(2)
        p.backspace()
        assertEquals(listOf("* A", "  * child", "* B"), p.lines)
    }

    @Test
    fun backspace_on_the_first_empty_bullet_of_a_zoom_deletes_it() = runTest {
        val p = pane("- A\n  * \n  * B\n- C\n")
        p.zoomInto(0)
        runCurrent()
        p.caretAtTextStart(1)
        p.backspace()
        assertEquals(listOf("* A", "  * B", "* C"), p.lines)
        assertEquals(1 to 4, p.caret)
    }

    @Test
    fun enter_at_the_start_of_a_zooms_first_bullet_opens_a_bullet_above_and_keeps_its_subtree() = runTest {
        fs.writeFile("$root/A/_node.md", "- Day [↳](<Day/_node.md>)\n- x\n")
        fs.writeFile("$root/A/Day/_node.md", "- child\n")
        val p = pane("- A [↳](<A/_node.md>)\n- C\n")
        p.zoomInto(0)
        runCurrent()
        // The zoom's first bullet, folded and folder-backed.
        assertEquals("  * Day", p.lines[1])
        val dayId = p.id(1)
        assertTrue(p.isPromotedRef(dayId))
        p.caretAtTextStart(1)
        p.insertNewline()
        assertEquals(listOf("* A", "  * ", "  * Day", "  * x", "* C"), p.lines)
        // The node keeps its identity — and with it its folder and children.
        assertEquals(dayId, p.id(2))
        assertEquals(2 to 4, p.caret)
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
        val p = pane("- A\n  * x\n")
        p.caretAtEnd(1)
        p.insertText(" one\r\ntwo\rthree")
        assertEquals(listOf("* A", "  * x one", "  * two", "  * three"), p.lines)
    }

    @Test
    fun copy_and_paste_of_a_subtree_keeps_its_shape_at_the_new_depth() = runTest {
        val p = pane("- A\n  * B\n    * C\n- D\n")
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
        val p = pane("- A\n- B\n- C\n")
        p.setSelection(1, 2, 2, p.lines[2].length)
        val cut = p.onCutRequested()!!
        assertEquals(listOf("* A", "* "), p.lines)
        p.insertText(cut)
        assertEquals(listOf("* A", "* B", "* C"), p.lines)
    }

    // ------------------------------------------------------------ selection

    @Test
    fun deleting_a_select_all_leaves_one_empty_bullet() = runTest {
        val p = pane("- A\n  * B\n- C\n")
        p.selectAll()
        p.backspace()
        assertEquals(listOf("* "), p.lines)
        p.selectAll()
        p.insertChar('x')
        assertEquals(listOf("* x"), p.lines)
    }

    // ------------------------------------------------------------- Tab

    @Test
    fun tab_under_a_folded_folder_backed_bullet_loads_it_and_nests_the_row_last() = runTest {
        fs.writeFile("$root/Test/_node.md", "- child\n")
        fs.writeFile("$root/_node.md", "- Test [↳](<Test/_node.md>)\n")
        val registry = DocumentRegistry(repo, backgroundScope)
        val p = PaneBackingViewModel(registry, backgroundScope, "_node.md")
        p.stateFlow.first { it.isLoaded }
        runCurrent()
        assertEquals(listOf("* Test"), p.lines)
        p.caretAtEnd(0)
        p.insertNewline()
        p.indentLine()
        runCurrent()
        assertEquals(listOf("* Test", "  * child", "  * "), p.lines)
        assertEquals(2 to 4, p.caret)
        assertTrue(p.id(0) !in p.stateFlow.value.collapsedIds)
    }

    // ------------------------------------------------ pasted nested lists

    @Test
    fun a_pasted_nested_list_keeps_its_levels() = runTest {
        val tree = listOf(
            PastedListItem("Knowledge", listOf(
                PastedListItem("Important"),
                PastedListItem("OSI model", listOf(PastedListItem("Layer **7**"))),
            )),
            PastedListItem("API design"),
        )
        val p = pane("- A\n")
        p.caretAtEnd(0)
        p.insertNewline()
        p.insertText(pastedListText(tree))
        assertEquals(
            listOf("* A", "* Knowledge", "  * Important", "  * OSI model", "    * Layer **7**", "* API design"),
            p.lines,
        )
        // A lone item pastes as plain text into the caret row.
        p.insertText(pastedListText(listOf(PastedListItem("x"))))
        assertEquals("* API designx", p.lines.last())
    }

    // ------------------------------------------------- selection deletion

    @Test
    fun typing_over_a_selection_that_swallows_a_folded_node_starts_a_new_node() = runTest {
        fs.writeFile("$root/_node.md", "- Dynalist Import [↳](<Dynalist Import/_node.md>)\n- Work\n")
        fs.writeFile("$root/Dynalist Import/_node.md", "- Adrian [↳](<Adrian/_node.md>)\n- Inbox\n")
        fs.writeFile("$root/Dynalist Import/Adrian/_node.md", "- a\n")
        val registry = DocumentRegistry(repo, backgroundScope)
        val p = PaneBackingViewModel(registry, backgroundScope, "_node.md")
        p.stateFlow.first { it.isLoaded }
        runCurrent()
        val oldId = p.id(0)
        p.selectAll()
        p.insertText("Framna")
        assertEquals(listOf("* Framna"), p.lines)
        assertTrue(p.id(0) != oldId)
        // Nesting under the new node brings nothing hidden along.
        p.insertNewline()
        p.insertText("Stampen push")
        p.indentLine()
        runCurrent()
        assertEquals(listOf("* Framna", "  * Stampen push"), p.lines)
        val doc = registry.acquire("_node.md")
        doc.flush()
        assertEquals("- Framna [↳](<Framna/_node.md>)\n", fs.read(root, "_node.md"))
        assertEquals("- Stampen push\n", fs.read(root, "Framna/_node.md"))
        assertEquals(null, fs.read(root, "Dynalist Import/_node.md"))

        // Undo all the way back restores the old node and its folder.
        repeat(10) { p.undo() }
        assertEquals(listOf("* Dynalist Import", "* Work"), p.lines)
        doc.flush()
        registry.release("_node.md")
        assertEquals("- Adrian [↳](<Adrian/_node.md>)\n- Inbox\n", fs.read(root, "Dynalist Import/_node.md"))
    }

    /**
     * Every way the editor can delete a folded node whose children are on
     * disk only. After each one, new text is typed and a row nested under
     * it; the old node's hidden children must never come back, and its
     * folder must end up in the trash.
     *
     * The outline is `* Top`, the folded `+ [Old](Old)`, `* Work`; each
     * entry drives the pane from that state.
     */
    private val folderedNodeDeletions: List<Pair<String, PaneBackingViewModel.() -> Unit>> = listOf(
        "select all, type" to { selectAll(); insertText("New") },
        "select all, backspace" to { selectAll(); backspace(); insertText("New") },
        "select all, cut" to { selectAll(); onCutRequested(); insertText("New") },
        "select Old to the end of Work, type" to { setSelection(1, 2, 2, lines[2].length); insertText("New") },
        "select Old to inside Work, backspace" to { setSelection(1, 2, 2, 4); backspace(); insertText("New") },
        "select from inside Top to the end of Old, type" to { setSelection(0, 3, 1, lines[1].length); insertText("New") },
        "backspace at Old's text start merges it into Top" to { caretAtTextStart(1); backspace(); insertText("New") },
    )

    @Test
    fun deleting_a_folded_node_in_any_way_never_brings_its_hidden_children_back() = runTest {
        for ((name, deleteIt) in folderedNodeDeletions) {
            fs.files.clear()
            fs.dirs.clear()
            fs.writeFile("$root/_node.md", "- Top\n- Old [↳](<Old/_node.md>)\n- Work\n")
            fs.writeFile("$root/Old/_node.md", "- Hidden [↳](<Hidden/_node.md>)\n- Secret\n")
            fs.writeFile("$root/Old/Hidden/_node.md", "- deep\n")
            val registry = DocumentRegistry(repo, backgroundScope)
            val p = PaneBackingViewModel(registry, backgroundScope, "_node.md")
            p.stateFlow.first { it.isLoaded }
            runCurrent()
            p.deleteIt()
            // Nest a new row under whatever the caret row is now.
            p.insertNewline()
            p.insertText("child")
            p.indentLine()
            runCurrent()
            val doc = registry.acquire("_node.md")
            doc.flush()
            runCurrent()
            val all = p.lines.joinToString("\n")
            for (hidden in listOf("Hidden", "Secret")) {
                assertTrue(hidden !in all, "$name: '$hidden' came back in ${p.lines}")
            }
            assertEquals(null, fs.read(root, "Old/_node.md"), "$name: Old still on disk")
            assertTrue(
                fs.files.keys.any { it.startsWith("$root/.trash/") && it.endsWith("Old/_node.md") },
                "$name: Old not in the trash",
            )
            registry.release("_node.md")
            p.release()
        }
    }

    @Test
    fun a_selection_ending_inside_a_row_keeps_that_rows_identity() = runTest {
        val p = pane("- A\n- B tail\n")
        val bId = p.id(1)
        p.setSelection(0, 2, 1, 4)
        p.deleteSelectionIfAny()
        assertEquals(listOf("* tail"), p.lines)
        assertEquals(bId, p.id(0))
        assertEquals(0 to 2, p.caret)
    }

    // ------------------------------------------------------ key sequences

    @Test
    fun no_key_sequence_produces_a_non_bullet_line() = runTest {
        val p = pane("- A\n  * B\n    * C\n- D\n")
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

    // ------------------------------------------------------- arrow keys

    @Test
    fun arrow_left_after_hidden_opening_marker_wraps_to_previous_row() = runTest {
        val p = pane("- A\n- `code`\n- **bold**\n")
        // Caret just past the hidden opening backtick: visually the start.
        p.setSelection(1, 3, 1, 3)
        p.moveLeft()
        assertEquals(0 to 3, p.stateFlow.value.let { it.cursorRow to it.cursorCol })
        p.setSelection(2, 4, 2, 4)
        p.moveLeft()
        assertEquals(1 to "* `code`".length, p.stateFlow.value.let { it.cursorRow to it.cursorCol })
    }

    @Test
    fun visible_text_start_counts_only_hidden_markers() {
        assertTrue(DocumentLayout.isAtVisibleTextStart("* `code`", 2))
        assertTrue(DocumentLayout.isAtVisibleTextStart("* `code`", 3))
        assertTrue(!DocumentLayout.isAtVisibleTextStart("* `code`", 4))
        assertTrue(!DocumentLayout.isAtVisibleTextStart("* plain", 3))
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
