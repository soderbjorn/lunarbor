/*
 * PaneSearchTest.kt (commonTest)
 * ------------------------------
 * Tests for the pane search: the full-text index ([TextIndex]) and the
 * pane behaviour in [PaneBackingViewModel] — results scoped to the page,
 * the index following saves, and opening a result at its line.
 */

package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.data.SearchQuery
import se.soderbjorn.lunarbor.data.TagCount
import se.soderbjorn.lunarbor.data.TextIndex
import se.soderbjorn.lunarbor.data.TextScope
import se.soderbjorn.lunarbor.testing.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PaneSearchTest {

    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })

    // ------------------------------------------------------------ index

    private fun index(vararg files: Pair<String, String>): TextIndex {
        val idx = TextIndex(listFiles = { emptyList() }, readText = { null })
        for ((f, t) in files) idx.noteText(f, t)
        return idx
    }

    private fun q(query: String) = SearchQuery.parse(query).expr

    @Test
    fun lines_match_by_visible_text_every_word_and_scope() {
        val idx = index(
            "_node.md" to "- Call **Anna** #work\n- Work [↳](<Work/_node.md>)\n> # Notes\n> plan the #work day\n",
            "Work/_node.md" to "- [Budget](lunarbor:/Budget) plan #work\n",
            "Work/Ideas.md" to "# Ideas\n\nA #work idea\n",
            ".trash/x/_node.md" to "- old #work\n",
            "Starred.md" to "* [#work](lunarbor:/Work)\n",
        )
        val all = idx.search(TextScope.Tree(""), q("#work"))
        assertEquals(4, all.total)
        assertEquals(
            listOf("Call Anna #work", "Budget plan #work", "A #work idea", "plan the #work day"),
            all.hits.map { it.text },
        )
        // The block's second row: item 2 (after two bullets), row 1.
        assertEquals(2 to 1, all.hits[3].let { it.itemIndex to it.rowOffset })
        assertEquals(listOf("Budget plan #work", "A #work idea"), idx.search(TextScope.Tree("Work"), q("#WORK")).hits.map { it.text })
        // A link matches by label, not target; words must all be there.
        assertEquals(0, idx.search(TextScope.Tree(""), q("tf")).total)
        assertEquals(listOf("Call Anna #work"), idx.search(TextScope.Tree(""), q("anna work")).hits.map { it.text })
        assertEquals(0, idx.search(TextScope.Tree(""), q("xcxfxxdgdrdrdsd")).total)
    }

    @Test
    fun sort_tag_orders_hits_by_their_first_other_tag_and_reverse_turns_it_round() {
        val idx = index(
            "_node.md" to "- Tidy desk #todo\n- Write docs #todo #gamma\n- Fix login #beta #todo\n" +
                "- Draft spec #todo #alpha #zeta\n- Item 10 #todo #p10\n- Item 2 #todo #P2\n- Also beta #todo #beta\n",
        )
        fun sorted(query: String, max: Int = 300): List<String> {
            val parsed = SearchQuery.parse(query)
            return idx.search(TextScope.Tree(""), parsed.expr, max, parsed.reversed, tagSort = parsed.tagSort).hits.map { it.text }
        }
        // The first tag the query does not search for; natural order, case
        // ignored; ties in reading order; lines without one last.
        assertEquals(
            listOf(
                "Draft spec #todo #alpha #zeta", "Fix login #beta #todo", "Also beta #todo #beta",
                "Write docs #todo #gamma", "Item 2 #todo #P2", "Item 10 #todo #p10", "Tidy desk #todo",
            ),
            sorted("#todo sort:tag"),
        )
        // Reversed: the sorted list turned round; max cuts after sorting.
        assertEquals(listOf("Tidy desk #todo", "Item 10 #todo #p10"), sorted("#todo sort:tag order:reverse", max = 2))
        assertEquals(listOf("Draft spec #todo #alpha #zeta", "Fix login #beta #todo"), sorted("#todo sort:tag", max = 2))
        // A tag prefix in the query skips every tag it matches.
        assertEquals("Draft spec #todo #alpha #zeta", sorted("#todo #a* sort:tag").single())
        assertEquals(1, idx.search(TextScope.Tree(""), q("#todo #a*"), tagSort = SearchQuery.parse("#todo #a* sort:tag").tagSort).total)
    }

    @Test
    fun parent_tags_count_for_their_children_and_a_matching_parent_covers_them() {
        val idx = index(
            "_node.md" to "- Alpha [↳](<Alpha/_node.md>)\n- Beta [↳](<Beta/_node.md>)\n- loose #urgent\n",
            "Alpha/_node.md" to "- Alpha #project\n",
            "Beta/_node.md" to "- Gamma [↳](<Gamma/_node.md>)\n- fix it #urgent\n",
            "Beta/Gamma/_node.md" to "- deep #urgent #done\n- deeper #urgent\n",
            "Beta/Notes.md" to "#urgent call\n",
        )
        // `Beta` itself is tagged in the root outline.
        idx.noteText("_node.md", "- Alpha [↳](<Alpha/_node.md>)\n- Beta #project [↳](<Beta/_node.md>)\n- loose #urgent\n")
        val r = idx.search(TextScope.Tree(""), q("#project AND #urgent"))
        assertEquals(listOf("deep #urgent #done", "deeper #urgent", "fix it #urgent", "#urgent call"), r.hits.map { it.text })
        assertEquals(
            listOf("deeper #urgent", "fix it #urgent", "#urgent call"),
            idx.search(TextScope.Tree(""), q("#project #urgent -#done")).hits.map { it.text },
        )
        // A hit that is a folder-backed item stands for everything under it.
        assertEquals(
            listOf("Alpha #project", "Beta #project"),
            idx.search(TextScope.Tree(""), q("#project -#urgent")).hits.map { it.text },
        )
        // Grouping, OR, phrases, tag prefixes; whole tags only.
        assertEquals(
            listOf("fix it #urgent", "loose #urgent"),
            idx.search(TextScope.Tree(""), q("(loose OR \"fix it\") AND #urg*")).hits.map { it.text },
        )
        assertEquals(0, idx.search(TextScope.Tree(""), q("#urg")).total)
    }

    @Test
    fun tags_are_counted_per_tree_most_used_first() {
        val idx = index(
            "_node.md" to "- #Work call\n- #work again #home\n- Sub [↳](<Sub/_node.md>)\n",
            "Sub/_node.md" to "- #workshop and `#code` not a tag\n> ```\n> #incode\n> ```\n",
            "Sub/Note.md" to "# Heading is not a tag\n#home here\n",
        )
        assertEquals(
            listOf(TagCount("#home", 2), TagCount("#Work", 2), TagCount("#workshop", 1)),
            idx.tags(TextScope.Tree(""), ""),
        )
        assertEquals(listOf(TagCount("#Work", 2), TagCount("#workshop", 1)), idx.tags(TextScope.Tree(""), "#WO"))
        // A subtree sees only its own tags, a note only its own.
        assertEquals(listOf(TagCount("#home", 1), TagCount("#workshop", 1)), idx.tags(TextScope.Tree("Sub"), ""))
        assertEquals(listOf(TagCount("#home", 1)), idx.tags(TextScope.File("Sub/Note.md"), "h"))
        // A note scope never reaches its folder's other files.
        assertEquals(0, idx.search(TextScope.File("Sub/Note.md"), q("workshop")).total)
    }

    // ------------------------------------------------------------ pane

    private suspend fun TestScope.pane(): Pair<PaneBackingViewModel, DocumentRegistry> {
        fs.writeFile("$root/_node.md", "- Intro\n- Work [↳](<Work/_node.md>)\n- Home #errand\n")
        fs.writeFile("$root/Work/_node.md", "- Plan\n- Deep [↳](<Deep/_node.md>)\n")
        fs.writeFile("$root/Work/Deep/_node.md", "- Buy milk #errand\n- Other\n")
        val registry = DocumentRegistry(repo, backgroundScope)
        val pane = PaneBackingViewModel(registry, backgroundScope, "_node.md")
        pane.stateFlow.first { it.isLoaded }
        runCurrent()
        return pane to registry
    }

    private fun TestScope.settle() {
        advanceTimeBy(500)
        runCurrent()
    }

    @Test
    fun search_lists_matching_lines_under_the_page_and_follows_edits() = runTest {
        val (p, _) = pane()
        p.openSearch()
        assertEquals("", p.stateFlow.value.searchQuery)
        p.setSearchQuery("#errand")
        settle()
        val s = p.stateFlow.value
        assertTrue(s.isSearchActive)
        assertEquals(listOf("Buy milk #errand", "Home #errand"), s.searchHits.map { it.text })
        assertEquals(listOf("Home", "Work", "Deep"), p.searchHitCrumbs(s.searchHits[0]))

        p.setSearchQuery("xcxfxxdgdrdrdsd")
        settle()
        assertEquals(emptyList(), p.stateFlow.value.searchHits)

        // An edit is found once saved (the search flushes open documents).
        p.moveTo(0, p.stateFlow.value.lines[0].length)
        p.insertText(" #errand")
        p.setSearchQuery("#errand")
        settle()
        assertEquals("Intro #errand", p.stateFlow.value.searchHits.first().text)

        p.closeSearch()
        assertEquals(null, p.stateFlow.value.searchQuery)
    }

    @Test
    fun the_search_covers_only_the_tree_on_screen() = runTest {
        val (p, _) = pane()
        // Zoomed into Work: its folder and below, never the root's lines.
        p.toggleCollapse(p.stateFlow.value.documentState!!.lineIds[1])
        runCurrent()
        p.zoomInto(1)
        runCurrent()
        assertEquals(TextScope.Tree("Work"), p.searchScope())
        p.setSearchQuery("#errand")
        settle()
        assertEquals(listOf("Buy milk #errand"), p.stateFlow.value.searchHits.map { it.text })
        assertEquals(listOf(TagCount("#errand", 1)), p.tagSuggestions("#e"))

        // Zoomed into a leaf: no tree below it, so nothing at all.
        p.zoomInto(p.stateFlow.value.lines.indexOfFirst { it.trim() == "* Plan" })
        runCurrent()
        assertEquals(null, p.searchScope())
        p.setSearchQuery("#errand")
        settle()
        assertEquals(emptyList(), p.stateFlow.value.searchHits)
    }

    @Test
    fun clicking_a_result_goes_there_in_place_and_back_returns() = runTest {
        val (p, _) = pane()
        p.setSearchQuery("milk")
        settle()
        p.navigateToSearchHit(p.stateFlow.value.searchHits.single())
        settle()
        val s = p.stateFlow.value
        assertEquals(null, s.searchQuery)
        assertEquals("Work/Deep/_node.md", s.activeFileRel)
        assertEquals("* Buy milk #errand", s.lines[s.cursorRow])
        p.zoomBack()
        settle()
        assertEquals("_node.md", p.stateFlow.value.activeFileRel)

        // `in:` searches another tree than the one on screen.
        p.setSearchQuery("in:/Work/Deep other")
        settle()
        assertEquals(listOf("Other"), p.stateFlow.value.searchHits.map { it.text })
    }

    @Test
    fun a_hit_outside_the_zoom_zooms_out_and_back_returns_to_the_zoom() = runTest {
        val (p, _) = pane()
        p.moveTo(0, p.stateFlow.value.lines[0].length)
        p.insertText(" {{search: home}}")
        val nodeId = p.stateFlow.value.documentState!!.lineIds[0]
        // Zoomed into the search node: its results are the page.
        p.zoomInto(0)
        runCurrent()
        assertEquals(nodeId, p.stateFlow.value.zoomedLineId)
        p.searchNodeOf(p.stateFlow.value, 0)
        settle()
        val hit = p.searchNodeOf(p.stateFlow.value, 0)!!.result!!.hits.single()
        assertEquals("Home #errand", hit.text)

        // Its sibling, in the same file: the zoom is left, its placeholder dropped.
        p.navigateToSearchHit(hit)
        settle()
        val s = p.stateFlow.value
        assertEquals(null, s.zoomedLineId)
        assertEquals("* Home #errand", s.lines[s.cursorRow])
        assertEquals(listOf("* Intro {{search: home}}", "* Work", "* Home #errand"), s.lines)

        p.zoomBack()
        settle()
        assertEquals(nodeId, p.stateFlow.value.zoomedLineId)
    }

    @Test
    fun back_and_forward_through_a_search_nodes_page() = runTest {
        val (p, _) = pane()
        fun s() = p.stateFlow.value
        fun zoomTitle() = p.zoomInfo(s())?.titleText
        // A search node two levels down, reached by zooming through Work.
        p.toggleCollapse(s().documentState!!.lineIds[1])
        runCurrent()
        val planRow = s().lines.indexOfFirst { it.trim() == "* Plan" }
        p.moveTo(planRow, s().lines[planRow].length)
        p.insertText(" {{search: milk}}")
        p.zoomInto(1)
        runCurrent()
        assertEquals("Work", zoomTitle(), "step 1")
        p.zoomInto(s().lines.indexOfFirst { it.trim() == "* Plan {{search: milk}}" })
        runCurrent()
        assertEquals("Plan {{search: milk}}", zoomTitle(), "step 2")

        // Back and forward without leaving it.
        p.zoomBack(); settle()
        assertEquals("Work", zoomTitle(), "step 3")
        p.zoomForward(); settle()
        assertEquals("Plan {{search: milk}}", zoomTitle(), "step 4")

        // To a hit in another file and back.
        val row = p.zoomInfo(s())!!.zoomRow
        p.searchNodeOf(s(), row)
        settle()
        val hit = p.searchNodeOf(s(), row)!!.result!!.hits.single()
        p.navigateToSearchHit(hit)
        settle()
        assertEquals("Work/Deep/_node.md", s().activeFileRel, "step 8")
        p.zoomBack(); settle()
        assertEquals("_node.md", s().activeFileRel, "step 9")
        assertEquals("Plan {{search: milk}}", zoomTitle(), "step 5")
        p.zoomBack(); settle()
        assertEquals("Work", zoomTitle(), "step 6")
        p.zoomBack(); settle()
        assertEquals(null, zoomTitle(), "step 7")
    }

    @Test
    fun a_search_nodes_page_is_read_only() = runTest {
        val (p, _) = pane()
        p.moveTo(0, p.stateFlow.value.lines[0].length)
        p.insertText(" {{search: home}}")
        val before = p.stateFlow.value.lines
        p.zoomInto(0)
        runCurrent()
        val s = p.stateFlow.value
        // No placeholder child to type in, and the zoom holds without one.
        assertEquals(before, s.lines)
        assertEquals(s.documentState!!.lineIds[0], s.zoomedLineId)
        assertTrue(s.isReadOnlyPage)

        // Nothing edits it: typing, new bullets, undo.
        p.insertText("x")
        p.insertNewline()
        p.indentLine()
        p.undo()
        assertEquals(before, p.stateFlow.value.lines)

        // Up again, it edits as usual.
        p.zoomBack()
        runCurrent()
        assertFalse(p.stateFlow.value.isReadOnlyPage)
        p.moveTo(2, p.stateFlow.value.lines[2].length)
        p.insertText("!")
        assertEquals("* Home #errand!", p.stateFlow.value.lines[2])
    }

    @Test
    fun a_search_node_lists_matches_in_its_tree_and_follows_saves() = runTest {
        val (p, registry) = pane()
        // Turn "Intro" into a search node for #errand.
        p.moveTo(0, p.stateFlow.value.lines[0].length)
        p.insertText(" #errand {{search: #errand}}")
        assertEquals("* Intro #errand {{search: #errand}}", p.stateFlow.value.lines[0])
        p.searchNodeOf(p.stateFlow.value, 0)
        settle()
        val view = p.searchNodeOf(p.stateFlow.value, 0)!!
        assertEquals("#errand", view.query)
        // The whole tree (it sits at the root), its own line left out.
        assertEquals(listOf("Buy milk #errand", "Home #errand"), view.result!!.hits.map { it.text })
        // Where each lives, from the node's tree down: nothing for its own outline.
        assertEquals(listOf(listOf("Work", "Deep"), emptyList()), view.result!!.hits.map { p.searchHitCrumbs(it, under = view.scopeFolder) })
        assertEquals(listOf("Deep"), p.searchHitCrumbs(view.result!!.hits[0], under = "Work"))
        // The query is no part of the title (folder names, breadcrumbs).
        assertEquals("Intro #errand", se.soderbjorn.lunarbor.data.FolderName.plainTextOf("Intro #errand {{search: #errand}}"))

        // An edit elsewhere lands a few seconds after it is saved.
        val q = PaneBackingViewModel(registry, backgroundScope, "Work/Deep/_node.md")
        q.stateFlow.first { it.isLoaded }
        q.moveTo(1, q.stateFlow.value.lines[1].length)
        q.insertText(" #errand")
        registry.flushAll()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, p.searchNodeOf(p.stateFlow.value, 0)!!.result!!.hits.size)
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(
            listOf("Buy milk #errand", "Other #errand", "Home #errand"),
            p.searchNodeOf(p.stateFlow.value, 0)!!.result!!.hits.map { it.text },
        )

        // It folds like any parent (its −/+ control), open by default.
        assertFalse(p.searchNodeOf(p.stateFlow.value, 0)!!.folded)
        val nodeId = p.stateFlow.value.documentState!!.lineIds[0]
        p.toggleCollapse(nodeId)
        assertTrue(p.searchNodeOf(p.stateFlow.value, 0)!!.folded)
        p.toggleCollapse(nodeId)
        assertFalse(p.searchNodeOf(p.stateFlow.value, 0)!!.folded)
    }

    @Test
    fun results_reverse_from_the_button_or_order_reverse() = runTest {
        val (p, _) = pane()
        p.setSearchQuery("#errand")
        settle()
        assertEquals(listOf("Buy milk #errand", "Home #errand"), p.stateFlow.value.searchHits.map { it.text })
        p.setSearchReversed(true)
        settle()
        assertEquals(listOf("Home #errand", "Buy milk #errand"), p.stateFlow.value.searchHits.map { it.text })
        p.setSearchReversed(false)
        p.setSearchQuery("#errand order:reverse")
        settle()
        assertEquals(listOf("Home #errand", "Buy milk #errand"), p.stateFlow.value.searchHits.map { it.text })
        p.closeSearch()
        assertFalse(p.stateFlow.value.searchReversed)
    }

    @Test
    fun insert_search_node_adds_an_example_with_its_expression_selected() = runTest {
        val (p, _) = pane()
        p.moveTo(0, 3)
        p.insertSearchNode()
        val s = p.stateFlow.value
        assertEquals("* Open tasks {{search: #todo -#done}}", s.lines[1])
        assertEquals(1, s.cursorRow)
        assertEquals("#todo -#done", s.lines[1].substring(s.anchorCol!!, s.cursorCol))
        p.insertText("#errand")
        assertEquals("* Open tasks {{search: #errand}}", p.stateFlow.value.lines[1])
    }

    @Test
    fun back_brings_a_pages_search_and_scroll_back() = runTest {
        val (p, _) = pane()
        p.setSearchQuery("milk")
        p.setSearchReversed(true)
        settle()
        p.noteScroll(120.0)
        p.navigateToSearchHit(p.stateFlow.value.searchHits.single())
        settle()
        // A page not seen before: no search. (A search result places the
        // caret itself, and the view scrolls to it — no remembered scroll.)
        assertEquals(null, p.stateFlow.value.searchQuery)
        p.noteScroll(40.0)

        p.zoomBack()
        settle()
        val back = p.stateFlow.value
        assertEquals("_node.md", back.activeFileRel)
        assertEquals("milk", back.searchQuery)
        assertTrue(back.searchReversed)
        assertEquals(listOf("Buy milk #errand"), back.searchHits.map { it.text })
        assertEquals(120.0, back.scrollRestore!!.top)

        p.zoomForward()
        settle()
        assertEquals(null, p.stateFlow.value.searchQuery)
        assertEquals(40.0, p.stateFlow.value.scrollRestore!!.top)
    }

    @Test
    fun back_puts_the_caret_where_it_was() = runTest {
        val (p, _) = pane()
        p.moveTo(2, 4)
        p.navigateToVaultFile("Work/_node.md")
        settle()
        assertEquals("Work/_node.md", p.stateFlow.value.activeFileRel)
        p.moveTo(1, 3)
        p.zoomBack()
        settle()
        assertEquals(2 to 4, p.stateFlow.value.let { it.cursorRow to it.cursorCol })
        p.zoomForward()
        settle()
        assertEquals(1 to 3, p.stateFlow.value.let { it.cursorRow to it.cursorCol })

        // A search result places the caret itself; memory does not override it.
        p.moveTo(0, 2)
        p.zoomBack()
        settle()
        p.setSearchQuery("milk")
        settle()
        p.navigateToSearchHit(p.stateFlow.value.searchHits.single())
        settle()
        val s = p.stateFlow.value
        assertEquals("* Buy milk #errand", s.lines[s.cursorRow])
    }

    @Test
    fun a_remembered_caret_finds_its_line_after_rows_shift() = runTest {
        val (p, registry) = pane()
        p.restoreCaret(PaneBackingViewModel.Caret(1, 4, "* Work"), PaneBackingViewModel.FileHistoryEntry("_node.md"))
        // Row 1 holds "* Work", so the caret lands there…
        assertEquals(1 to 4, p.stateFlow.value.let { it.cursorRow to it.cursorCol })
        // …and with a row above it, it follows the text.
        p.restoreCaret(PaneBackingViewModel.Caret(0, 4, "* Work"), PaneBackingViewModel.FileHistoryEntry("_node.md"))
        assertEquals(1, p.stateFlow.value.cursorRow)
    }

    @Test
    fun a_result_opens_at_its_line() = runTest {
        val (p, registry) = pane()
        p.setSearchQuery("milk")
        settle()
        val hit = p.stateFlow.value.searchHits.single()
        val q = PaneBackingViewModel(registry, backgroundScope, "_node.md")
        q.openSearchHit(hit)
        settle()
        val s = q.stateFlow.value
        assertEquals("Work/Deep/_node.md", s.activeFileRel)
        assertEquals("* Buy milk #errand", s.lines[s.cursorRow])
    }
}
