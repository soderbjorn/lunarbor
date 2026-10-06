/* LunicleBoardNodeTest.kt (commonTest)
 *
 * Pins board nodes in a pane (LBR-27): a `{{lunicle: …}}` bullet is a board
 * node only where the app has Lunicle; its view lists the board's columns
 * (closed ones last and folded) and issues; columns and issues fold as
 * pane state; the node folds like a search node; a zoom into it is a
 * read-only page; "Insert Lunicle board…" writes the full form with the
 * title selected; and nothing of the board reaches the document or disk. */
package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.lunicle.FakeLunicleApi
import se.soderbjorn.lunarbor.lunicle.LunicleBoardKey
import se.soderbjorn.lunarbor.lunicle.LunicleBoards
import se.soderbjorn.lunarbor.lunicle.LunicleConnection
import se.soderbjorn.lunarbor.lunicle.LunicleConnectionStore
import se.soderbjorn.lunarbor.lunicle.LunicleConnectionsSnapshot
import se.soderbjorn.lunarbor.lunicle.LunicleMethod
import se.soderbjorn.lunarbor.lunicle.LunicleService
import se.soderbjorn.lunarbor.lunicle.LunicleSyncKind
import se.soderbjorn.lunarbor.testing.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LunicleBoardNodeTest {
    private val root = "/vault"
    private val fs = InMemoryFileSystem()
    private val repo = NoteRepository(fs, root, nowMillis = { 0L })

    private val store = object : LunicleConnectionStore {
        val work = LunicleConnection("c1", "work", "https://issues.lunicle.dev", hasToken = true)
        override suspend fun list() = LunicleConnectionsSnapshot(listOf(work))
        override suspend fun add(name: String?, baseUrl: String?, token: String?) = list()
        override suspend fun update(id: String, name: String?, baseUrl: String?, token: String?) = list()
        override suspend fun remove(id: String) = list()
    }

    private val api = FakeLunicleApi().apply {
        answer(LunicleMethod.GET, "/api/v1/projects", 200, """[{"id":2,"name":"Framnafolk","keyPrefix":"FRA"}]""")
        answer(
            LunicleMethod.GET, "/api/v1/projects/2/board", 200,
            """{"project":{"id":2,"name":"Framnafolk","keyPrefix":"FRA"},
               "statuses":[{"name":"New"},{"name":"Closed","requiresResolution":true},{"name":"In progress"}],
               "priorities":["High","Normal"],
               "issues":[
                 {"id":1,"key":"FRA-1","title":"Low one","status":"New","priority":"Normal","updatedAt":1},
                 {"id":2,"key":"FRA-2","title":"Urgent","status":"New","priority":"High","updatedAt":1},
                 {"id":3,"key":"FRA-3","title":"Done","status":"Closed","priority":"Normal","updatedAt":1}]}""",
        )
        answer(
            LunicleMethod.GET, "/api/v1/issues/2", 200,
            """{"id":2,"key":"FRA-2","title":"Urgent","status":"New","priority":"High","updatedAt":1,"projectId":2,
               "description":"Fix it","comments":[{"id":5,"body":"On it","author":"Linus","createdAt":0}]}""",
        )
    }

    private suspend fun TestScope.pane(withLunicle: Boolean = true, outline: String = "- Lunicle board {{lunicle: work/FRA}}\n- Other\n"): Pair<PaneBackingViewModel, DocumentRegistry> {
        fs.writeFile("$root/_node.md", outline)
        val boards = if (withLunicle) {
            LunicleBoards(LunicleService(api, store), null, backgroundScope, now = { testScheduler.currentTime })
        } else null
        val registry = DocumentRegistry(repo, backgroundScope, lunicleBoards = boards)
        val pane = PaneBackingViewModel(registry, backgroundScope, "_node.md")
        pane.stateFlow.first { it.isLoaded }
        runCurrent()
        return pane to registry
    }

    @Test
    fun a_board_node_lists_columns_and_issues() = runTest {
        val (p, _) = pane()
        assertTrue(p.stateFlow.value.lunicleEnabled)
        // The first paint asks; the board arrives as a new state.
        val first = p.lunicleBoardOf(p.stateFlow.value, 0, 0)!!
        assertEquals(LunicleBoardKey("work", "FRA"), first.key)
        runCurrent()
        val view = p.lunicleBoardOf(p.stateFlow.value, 0, 0)!!
        assertEquals(LunicleSyncKind.SYNCED, view.sync.kind)
        assertEquals(listOf("New", "In progress", "Closed"), view.columns.map { it.column.status.name })
        assertEquals(listOf(false, false, true), view.columns.map { it.folded })
        // Highest priority first, with its pill when folded.
        val new = view.columns.first()
        assertEquals(listOf("Urgent", "Low one"), new.issues.map { it.issue.title })
        assertEquals(listOf("#high"), new.issues.first().pills.map { it.text })
        assertEquals("https://issues.lunicle.dev/?issue=FRA-2", new.issues.first().url)
        // A plain bullet is no board node.
        assertNull(p.lunicleBoardOf(p.stateFlow.value, 1, 0))
        // Nothing of the board is in the document.
        assertEquals(listOf("* Lunicle board {{lunicle: work/FRA}}", "* Other"), p.stateFlow.value.lines)
    }

    @Test
    fun columns_and_issues_fold_as_pane_state() = runTest {
        val (p, _) = pane()
        p.lunicleBoardOf(p.stateFlow.value, 0, 0)
        runCurrent()
        var view = p.lunicleBoardOf(p.stateFlow.value, 0, 0)!!
        p.toggleLunicleColumn(view.columns.last())
        p.toggleLunicleColumn(view.columns.first())
        view = p.lunicleBoardOf(p.stateFlow.value, 0, 0)!!
        assertEquals(listOf(true, false, false), view.columns.map { it.folded })
        p.toggleLunicleIssue(view.columns[2].issues.single())
        view = p.lunicleBoardOf(p.stateFlow.value, 0, 0)!!
        val done = view.columns[2].issues.single()
        assertTrue(done.unfolded)
        assertEquals(listOf("#closed", "#normal", "@nobody"), done.pills.map { it.text })
        // Unfolded issues are read in full once reported shown.
        p.toggleLunicleIssue(view.columns[0].issues.first())
        p.reportShownBoards(mapOf(view.key to setOf(2L)))
        runCurrent()
        val urgent = p.lunicleBoardOf(p.stateFlow.value, 0, 0)!!.columns[0].issues.first()
        assertEquals("Fix it", urgent.detail?.description)
        assertEquals("1 comment", urgent.commentsLabel)
        // The node itself folds like a search node.
        val nodeId = p.stateFlow.value.documentState!!.lineIds[0]
        assertFalse(view.folded)
        p.toggleCollapse(nodeId)
        assertTrue(p.lunicleBoardOf(p.stateFlow.value, 0, 0)!!.folded)
    }

    @Test
    fun a_zoom_into_a_board_node_is_a_read_only_page() = runTest {
        val (p, _) = pane()
        p.zoomInto(0)
        runCurrent()
        val s = p.stateFlow.value
        assertEquals(s.documentState!!.lineIds[0], s.zoomedLineId)
        assertTrue(s.isReadOnlyPage)
        // No placeholder child was added.
        assertEquals(2, s.lines.size)
    }

    @Test
    fun without_lunicle_the_bullet_is_plain() = runTest {
        val (p, _) = pane(withLunicle = false)
        assertFalse(p.stateFlow.value.lunicleEnabled)
        assertNull(p.lunicleBoardOf(p.stateFlow.value, 0, 0))
        p.zoomInto(0)
        runCurrent()
        assertFalse(p.stateFlow.value.isReadOnlyPage)
    }

    @Test
    fun a_malformed_reference_says_how_to_write_it() = runTest {
        val (p, _) = pane(outline = "- Board {{lunicle: a/b/c}}\n")
        val view = p.lunicleBoardOf(p.stateFlow.value, 0, 0)!!
        assertEquals(LunicleSyncKind.ERROR, view.sync.kind)
        assertTrue(view.columns.isEmpty())
    }

    @Test
    fun insert_lunicle_board_writes_the_full_form_with_the_title_selected() = runTest {
        val (p, _) = pane(outline = "- Intro\n")
        p.moveTo(0, 3)
        p.insertLunicleBoard("work", "FRA")
        val s = p.stateFlow.value
        assertEquals("* Lunicle board {{lunicle: work/FRA}}", s.lines[1])
        assertEquals(1, s.cursorRow)
        assertEquals("Lunicle board", s.lines[1].substring(s.anchorCol!!, s.cursorCol))
        p.insertText("Sprint")
        assertEquals("* Sprint {{lunicle: work/FRA}}", p.stateFlow.value.lines[1])
        // Saved as a plain bullet: the board itself is never written.
        advanceTimeBy(10_000)
        runCurrent()
        val saved = fs.readFileIfExists("$root/_node.md")!!
        assertTrue("- Intro\n- Sprint {{lunicle: work/FRA}}\n" in saved, saved)
        assertFalse("Urgent" in saved)
    }
}
