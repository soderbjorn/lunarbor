/*
 * PrivacyModesTest.kt (commonTest)
 * --------------------------------
 * Privacy modes (LBR-10): which rows and paths a mode hides (whole tags,
 * any case, whole subtrees, across files, notes, blocks), search and search
 * nodes under a mode, editing next to hidden rows (select-all delete, Tab /
 * Shift-Tab past a hidden sibling, drag and drop, sort, paste, the check
 * that undoes anything else touching them), where panes go when the mode
 * changes, and the modes file and the dialog's rules ([PrivacyConfig]).
 * Runs the real stack — [PaneBackingViewModel] over [DocumentRegistry] +
 * [NoteRepository] on [InMemoryFileSystem].
 */

package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.data.PrivacyConfig
import se.soderbjorn.lunarbor.data.PrivacyFilter
import se.soderbjorn.lunarbor.data.PrivacyMode
import se.soderbjorn.lunarbor.data.SearchQuery
import se.soderbjorn.lunarbor.data.TextScope
import se.soderbjorn.lunarbor.main.PaneBackingViewModel.DropTarget
import se.soderbjorn.lunarbor.testing.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PrivacyModesTest {

    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })
    private val colleagues = PrivacyMode("m1", "Colleagues", listOf("private"))

    private suspend fun seed(rel: String, content: String) = fs.writeFile("$root/$rel", content)

    /** A registry with the [colleagues] mode, switched on when [on]. */
    private suspend fun TestScope.registry(on: Boolean = true): DocumentRegistry {
        val registry = DocumentRegistry(repo, backgroundScope)
        registry.setPrivacyModes(listOf(colleagues))
        if (on) registry.setPrivacyMode(colleagues.id)
        return registry
    }

    /** A pane on [file] with every subtree unfolded. */
    private suspend fun TestScope.pane(registry: DocumentRegistry, file: String = "_node.md"): PaneBackingViewModel {
        val pane = PaneBackingViewModel(registry, backgroundScope, file)
        pane.stateFlow.first { it.isLoaded }
        runCurrent()
        for (id in pane.stateFlow.value.collapsedIds) pane.toggleCollapse(id)
        runCurrent()
        return pane
    }

    private val PaneBackingViewModel.lines get() = stateFlow.value.lines
    private fun PaneBackingViewModel.shown() =
        visibleRows(stateFlow.value, 0, lines.lastIndex).map { lines[it] }

    // ------------------------------------------------------------ the rules

    @Test
    fun a_tag_hides_its_item_and_subtree_matching_whole_tags_in_any_case() {
        val filter = PrivacyFilter.of(listOf("#Private"))
        val lines = listOf(
            "* Open",
            "* Diary #private",
            "  * child",
            "    * grandchild",
            "* Ship #privateer",
            BlockLayout.firstLine(0, "A block"),
            BlockLayout.nextLine(0, "with #PRIVATE inside"),
            "  * under the block",
            "* Last",
        )
        val hidden = PrivacyLayout.hiddenRows(lines, filter)!!
        assertEquals(listOf(false, true, true, true, false, true, true, true, false), hidden.toList())
        assertNull(PrivacyLayout.hiddenRows(listOf("* a", "* b #work"), filter))
        assertNull(PrivacyLayout.hiddenRows(lines, PrivacyFilter.NONE))
    }

    @Test
    fun paths_under_a_hidden_item_and_tagged_notes_are_hidden_across_files() = runTest {
        seed("_node.md", "- Health #private [↳](<Health/_node.md>)\n- Work [↳](<Work/_node.md>)\n")
        seed("Health/_node.md", "- Deep [↳](<Deep/_node.md>)\n")
        seed("Health/Deep/_node.md", "- x\n")
        seed("Health/scan.png", "png")
        seed("Work/_node.md", "- Budget\n")
        seed("Work/notes.md", "Call the doctor #Private\n")
        seed("Work/open.md", "Hello\n")
        val off = registry(on = false)
        assertFalse(off.isPathHidden("Health"))
        val r = registry()
        for (p in listOf("Health", "Health/_node.md", "Health/Deep", "Health/Deep/_node.md", "Health/scan.png", "Work/notes.md")) {
            assertTrue(r.isPathHidden(p), p)
        }
        for (p in listOf("", "_node.md", "Work", "Work/_node.md", "Work/open.md")) assertFalse(r.isPathHidden(p), p)
        assertTrue(r.hasHiddenUnder("Work"))
        assertTrue(r.hasHiddenUnder(""))
        // Link search, wiki links and Insert Image never offer them.
        assertEquals(emptyList(), r.vaultIndex.search("Deep").map { it.pathRel })
        assertEquals(listOf("Work"), r.vaultIndex.search("Work").map { it.pathRel })
        assertEquals(emptyList(), r.listImageFiles())
    }

    @Test
    fun search_and_tag_suggestions_leave_hidden_lines_out() = runTest {
        seed("_node.md", "- Groceries #todo\n- Health #private [↳](<Health/_node.md>)\n- Diary #private #todo\n")
        seed("Health/_node.md", "- Pills #todo\n")
        seed("plan.md", "Plan #todo #private\n")
        val r = registry()
        val hits = r.searchText(TextScope.Tree(""), SearchQuery.parse("#todo").expr).hits.map { it.text }
        assertEquals(listOf("Groceries #todo"), hits)
        val tags = r.textIndex.tags(TextScope.Tree(""), "", filter = r.privacyFilter).map { it.tag }
        assertEquals(listOf("#todo"), tags)
        // A search node sees what the app's mode leaves.
        val key = DocumentRegistry.SearchNodeKey(TextScope.Tree(""), "#todo")
        r.requestSearchNode(key)
        runCurrent()
        assertEquals(1, r.searchNodeResultsFlow.value[key]!!.total)
        r.setPrivacyMode(null)
        assertEquals(4, r.searchNodeResultsFlow.value[key]!!.total)
    }

    // ------------------------------------------------------------- the pane

    @Test
    fun hidden_rows_are_never_on_screen_and_the_caret_steps_over_them() = runTest {
        seed("_node.md", "- A\n- Diary #private\n  * secret\n- B\n")
        val p = pane(registry())
        assertEquals(listOf("* A", "* B"), p.shown())
        p.moveTo(0, 3)
        p.moveDown()
        assertEquals(3, p.stateFlow.value.cursorRow)
        p.moveUp()
        assertEquals(0, p.stateFlow.value.cursorRow)
    }

    @Test
    fun select_all_and_delete_keeps_every_hidden_row() = runTest {
        seed("_node.md", "- A\n- Diary #private\n  * secret\n- B\n- C\n")
        val p = pane(registry())
        p.selectAll()
        p.deleteSelectionIfAny()
        runCurrent()
        assertEquals(listOf("* Diary #private", "  * secret"), p.lines.filter { it.isNotBlank() && it != "* " })
        // The page keeps a row to type in.
        assertEquals(listOf("* "), p.shown())
        p.insertChar('x')
        assertEquals(listOf("* x"), p.shown())
        assertTrue("* Diary #private" in p.lines && "  * secret" in p.lines)
    }

    @Test
    fun tab_past_a_hidden_sibling_nests_under_the_visible_item() = runTest {
        seed("_node.md", "- V\n- Diary #private\n- R\n")
        val p = pane(registry())
        p.moveTo(2, 3)
        p.indentLine()
        assertEquals(listOf("* V", "  * R", "* Diary #private"), p.lines)
        // Never under a hidden item: one level under the visible one at most.
        p.indentLine()
        assertEquals(listOf("* V", "  * R", "* Diary #private"), p.lines)
    }

    @Test
    fun shift_tab_with_a_hidden_sibling_below_moves_past_the_parent() = runTest {
        seed("_node.md", "- P\n  * R\n  * Diary #private\n- Q\n")
        val p = pane(registry())
        p.moveTo(1, 5)
        p.outdentLine()
        assertEquals(listOf("* P", "  * Diary #private", "* R", "* Q"), p.lines)
    }

    @Test
    fun a_drop_lands_before_hidden_rows_never_under_them() = runTest {
        seed("_node.md", "- V\n  * v1\n- Diary #private\n- R\n- X\n")
        val p = pane(registry())
        // X onto R's upper half, two levels right: before the hidden item.
        val target = p.dropTarget(4, 4, 3, insertAbove = true, levelDelta = 2)
        assertEquals(DropTarget(2, 4), target)
        p.moveLineRange(4, 4, target!!.insertBeforeRow, target.indent)
        assertEquals(listOf("* V", "  * v1", "    * X", "* Diary #private", "* R"), p.lines)
    }

    @Test
    fun sorting_leaves_hidden_children_where_they_are() = runTest {
        seed("_node.md", "- c\n- Diary #private\n- b\n- a\n")
        val p = pane(registry())
        p.sortChildrenByName()
        assertEquals(listOf("* a", "* Diary #private", "* b", "* c"), p.lines)
    }

    @Test
    fun pasting_into_a_bullet_with_hidden_children_adds_first_children() = runTest {
        seed("_node.md", "- P\n  * Diary #private\n- Q\n")
        val p = pane(registry())
        p.moveTo(0, 3)
        p.insertText("\none\ntwo")
        assertEquals(listOf("* P", "  * one", "  * two", "  * Diary #private", "* Q"), p.lines)
    }

    @Test
    fun an_edit_that_would_re_parent_a_hidden_item_is_undone() = runTest {
        seed("_node.md", "- Z\n- A\n- Diary #private\n- B\n")
        val p = pane(registry())
        val before = p.lines
        // Tab on A..B would put the hidden item under Z.
        p.moveTo(1, 2)
        p.moveTo(3, 3, extend = true)
        p.indentLine()
        assertEquals(before, p.lines)
        assertFalse(p.canUndo())
    }

    @Test
    fun typing_a_hiding_tag_hides_the_line_and_undo_brings_it_back() = runTest {
        seed("_node.md", "- A\n- B\n")
        val p = pane(registry())
        p.moveTo(1, 3)
        p.insertText(" #private")
        assertEquals(listOf("* A"), p.shown())
        assertEquals(0, p.stateFlow.value.cursorRow)
        p.undo()
        assertEquals(listOf("* A", "* B"), p.shown())
    }

    @Test
    fun a_node_with_hidden_content_is_not_offered_for_deletion() = runTest {
        seed("_node.md", "- P [↳](<P/_node.md>)\n")
        seed("P/_node.md", "- secret #private\n- ok\n")
        val off = pane(registry(on = false), "P/_node.md")
        assertEquals("P", off.pageNodeTitle())
        val on = pane(registry(), "P/_node.md")
        assertNull(on.pageNodeTitle())
    }

    @Test
    fun switching_the_mode_moves_panes_off_hidden_places() = runTest {
        seed("_node.md", "- A\n- Health #private [↳](<Health/_node.md>)\n- Diary #private\n  * entry\n")
        seed("Health/_node.md", "- Pills\n")
        val r = registry(on = false)
        val onFile = pane(r, "Health/_node.md")
        val zoomed = pane(r)
        zoomed.zoomInto(zoomed.lines.indexOf("  * entry"))
        runCurrent()
        assertNotNull(zoomed.stateFlow.value.zoomedLineId)

        r.setPrivacyMode(colleagues.id)
        runCurrent()
        onFile.stateFlow.first { it.activeFileRel == "_node.md" && it.isLoaded }
        assertNull(zoomed.stateFlow.value.zoomedLineId)
        assertEquals(listOf("* A"), zoomed.shown())
        // Navigating there again lands on the nearest visible node.
        onFile.navigateToVaultFile("Health/_node.md")
        runCurrent()
        assertEquals("_node.md", onFile.stateFlow.value.activeFileRel)
    }

    @Test
    fun folder_contents_and_links_follow_the_mode() = runTest {
        seed("_node.md", "- Health #private [↳](<Health/_node.md>)\n- See [h](lunarbor:/Health)\n")
        seed("Health/_node.md", "- Pills\n")
        seed("secret.md", "#private\n")
        seed("open.md", "hi\n")
        val r = registry()
        val p = pane(r)
        r.ensureVaultListing("")
        runCurrent()
        val shown = p.folderContentsOf(p.stateFlow.value, "")!!.map { it.pathRel }
        assertEquals(listOf("open.md"), shown)
        assertTrue(p.isLinkBroken(p.stateFlow.value, "lunarbor:/Health"))
    }

    // ------------------------------------------------- the file and dialog

    @Test
    fun the_modes_file_round_trips_and_the_dialog_rules_hold() {
        val modes = listOf(colleagues, PrivacyMode("m2", "Friends", listOf("work", "health")))
        assertEquals(modes, PrivacyConfig.parse(PrivacyConfig.format(modes)))
        assertEquals(emptyList(), PrivacyConfig.parse("not json"))
        assertEquals(emptyList(), PrivacyConfig.parse("""{"version": 99, "modes": []}"""))
        // Cards only under "No privacy".
        assertTrue(PrivacyConfig.canEditModes(null))
        assertFalse(PrivacyConfig.canEditModes("m1"))
        // Names: never empty, never taken (any case).
        assertNull(PrivacyConfig.renamed(modes, "m2", "  "))
        assertNull(PrivacyConfig.renamed(modes, "m2", "colleagues"))
        assertEquals("Family", PrivacyConfig.renamed(modes, "m2", " Family ")!![1].name)
        // Tags: with or without #, once each (any case).
        val tagged = PrivacyConfig.withTag(PrivacyConfig.withTag(modes, "m1", "#Health"), "m1", "health")
        assertEquals(listOf("private", "Health"), tagged[0].tags)
        assertEquals(listOf("private"), PrivacyConfig.withoutTag(tagged, "m1", "#HEALTH")[0].tags)
        val (added, mode) = PrivacyConfig.withNewMode(modes)
        assertEquals("New mode", mode.name)
        assertEquals(3, added.size)
        assertEquals("New mode 2", PrivacyConfig.withNewMode(added).second.name)
    }

    @Test
    fun a_removed_current_mode_falls_back_to_no_privacy() = runTest {
        val r = registry()
        assertEquals("m1", r.privacyFlow.value.currentId)
        r.setPrivacyModes(emptyList())
        assertNull(r.privacyFlow.value.currentId)
        assertFalse(r.privacyFilter.isActive)
        // Unknown ids count as "No privacy" too.
        r.setPrivacyMode("gone")
        assertNull(r.privacyFlow.value.currentId)
        assertEquals(PrivacyFilter.NONE, r.filterForMode(null))
        assertNull(r.filterForMode("gone"))
    }
}
