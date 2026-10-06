/* LunicleBoardRowsTest.kt (commonTest)
 *
 * Pins the keyboard's walk through a board node's rows (LBR-28): the row
 * order follows the pane's board folds (column, issue, an unfolded issue's
 * description, comments and "Comment…"); entering goes to the first row
 * going down and the last going up, and stepping past either end leaves
 * the board; ⌘↑ / ⌘↓ fold the column or issue under the caret, and ⌘↑ on a
 * row under an issue folds the issue and lands on its title; the caret's
 * row is found again by issue id plus kind after a poll that adds issues
 * above it, moves it, or removes it; and ⌘↑ on the node's own line folds
 * the board node. Each unfolded column ends with its "New issue" line
 * (LBR-29); "Comment…" only where the token may comment (LBR-31). */
package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.lunicle.FakeLunicleApi
import se.soderbjorn.lunarbor.lunicle.LunicleBoards
import se.soderbjorn.lunarbor.lunicle.LunicleConnection
import se.soderbjorn.lunarbor.lunicle.LunicleConnectionStore
import se.soderbjorn.lunarbor.lunicle.LunicleConnectionsSnapshot
import se.soderbjorn.lunarbor.lunicle.LunicleMethod
import se.soderbjorn.lunarbor.lunicle.LunicleService
import se.soderbjorn.lunarbor.testing.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LunicleBoardRowsTest {
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

    private val boardPath = "/api/v1/projects/2/board"

    private fun boardJson(issues: String) =
        """{"project":{"id":2,"name":"Framnafolk","keyPrefix":"FRA"},
           "statuses":[{"name":"New"},{"name":"Closed","requiresResolution":true},{"name":"In progress"}],
           "priorities":["High","Normal"],
           "issues":[$issues]}"""

    private val firstIssues = """
        {"id":1,"key":"FRA-1","title":"Low one","status":"New","priority":"Normal","updatedAt":1,"canEdit":true},
        {"id":2,"key":"FRA-2","title":"Urgent","status":"New","priority":"High","updatedAt":1,"canEdit":true},
        {"id":3,"key":"FRA-3","title":"Done","status":"Closed","priority":"Normal","updatedAt":1,"canEdit":true}"""

    private val api = FakeLunicleApi().apply {
        answer(LunicleMethod.GET, "/api/v1/projects", 200, """[{"id":2,"name":"Framnafolk","keyPrefix":"FRA"}]""")
        answer(LunicleMethod.GET, boardPath, 200, boardJson(firstIssues))
        answer(
            LunicleMethod.GET, "/api/v1/issues/2", 200,
            """{"id":2,"key":"FRA-2","title":"Urgent","status":"New","priority":"High","updatedAt":1,"projectId":2,"canComment":true,
               "description":"Fix it","comments":[{"id":5,"body":"On it","author":"Linus","createdAt":0},
               {"id":6,"body":"Done soon","author":"Ada","createdAt":0}]}""",
        )
    }

    private lateinit var boards: LunicleBoards

    private suspend fun TestScope.pane(): PaneBackingViewModel {
        fs.writeFile("$root/_node.md", "- Lunicle board {{lunicle: work/FRA}}\n- Other\n")
        boards = LunicleBoards(LunicleService(api, store), null, backgroundScope, now = { testScheduler.currentTime })
        val registry = DocumentRegistry(repo, backgroundScope, lunicleBoards = boards)
        val pane = PaneBackingViewModel(registry, backgroundScope, "_node.md")
        pane.stateFlow.first { it.isLoaded }
        runCurrent()
        pane.lunicleBoardOf(pane.stateFlow.value, 0, 0)
        runCurrent()
        return pane
    }

    private fun PaneBackingViewModel.rows(): List<LunicleBoardRow> =
        LunicleBoardRows.of(lunicleBoardOf(stateFlow.value, 0, 0)!!)

    private fun PaneBackingViewModel.keys(): List<String> = rows().map { it.ref.key }

    private fun PaneBackingViewModel.ref(key: String): LunicleRowRef = rows().first { it.ref.key == key }.ref

    /** Unfolds issue 2 by ⌘↓ and lets its details arrive (reported shown, as a paint would). */
    private fun TestScope.unfoldUrgent(p: PaneBackingViewModel) {
        p.foldLunicleRow(0, p.ref("i:2"), folded = false, now = 0)
        val view = p.lunicleBoardOf(p.stateFlow.value, 0, 0)!!
        p.reportShownBoards(mapOf(view.key to setOf(2L)))
        runCurrent()
    }

    @Test
    fun rows_follow_the_board_and_its_folds() = runTest {
        val p = pane()
        // Closed needs a resolution: last and folded.
        assertEquals(listOf("c:New", "i:2", "i:1", "e:New", "c:In progress", "e:In progress", "c:Closed"), p.keys())
        unfoldUrgent(p)
        assertEquals(
            listOf("c:New", "i:2", "d:2", "m:2:5", "m:2:6", "a:2", "i:1", "e:New", "c:In progress", "e:In progress", "c:Closed"),
            p.keys(),
        )
        val rows = p.rows()
        assertEquals(listOf(0, 1, 2, 2, 2, 2, 1, 1, 0, 1, 0), rows.map { it.depth })
        // A caret on titles, the description, "Comment…" and "New issue"; a highlight elsewhere.
        assertEquals(listOf(false, true, true, false, false, true, true, true, false, true, false), rows.map { it.editable })
        // Folding the column hides everything under it; unfolding Closed shows its issue.
        p.foldLunicleRow(0, p.ref("c:New"), folded = true, now = 0)
        p.foldLunicleRow(0, p.ref("c:Closed"), folded = false, now = 0)
        assertEquals(listOf("c:New", "c:In progress", "e:In progress", "c:Closed", "i:3", "e:Closed"), p.keys())
    }

    @Test
    fun an_unread_issue_shows_its_description_row_as_loading() = runTest {
        val p = pane()
        // Unfolded, not yet reported shown: no details, one row that is not editable yet.
        p.foldLunicleRow(0, p.ref("i:1"), folded = false, now = 0)
        val desc = p.rows().first { it.ref.key == "d:1" }
        assertFalse(desc.editable)
        assertEquals(listOf("c:New", "i:2", "i:1", "d:1", "e:New", "c:In progress", "e:In progress", "c:Closed"), p.keys())
    }

    @Test
    fun entering_and_leaving_at_both_ends() = runTest {
        val p = pane()
        val rows = p.rows()
        // Down from the node's line: the first row; up from the row below: the last.
        assertEquals("c:New", LunicleBoardRows.entry(rows, down = true)?.key)
        assertEquals("c:Closed", LunicleBoardRows.entry(rows, down = false)?.key)
        assertEquals(LunicleBoardRows.Step.To(p.ref("i:2")), LunicleBoardRows.step(rows, p.ref("c:New"), down = true))
        assertEquals(LunicleBoardRows.Step.To(p.ref("e:New")), LunicleBoardRows.step(rows, p.ref("c:In progress"), down = false))
        // Past either end the caret goes back into the document.
        assertEquals(LunicleBoardRows.Step.LeaveUp, LunicleBoardRows.step(rows, p.ref("c:New"), down = false))
        assertEquals(LunicleBoardRows.Step.LeaveDown, LunicleBoardRows.step(rows, p.ref("c:Closed"), down = true))
        // An empty board takes no caret.
        assertNull(LunicleBoardRows.entry(emptyList(), down = true))
    }

    @Test
    fun cmd_up_on_a_child_row_folds_the_issue_and_lands_on_its_title() = runTest {
        val p = pane()
        unfoldUrgent(p)
        for (child in listOf("d:2", "m:2:6", "a:2")) {
            val at = p.foldLunicleRow(0, p.ref(child), folded = true, now = 0)
            assertEquals("i:2", at?.key)
            assertEquals(listOf("c:New", "i:2", "i:1", "e:New", "c:In progress", "e:In progress", "c:Closed"), p.keys())
            // ⌘↓ on the title opens it again; ⌘↓ on a child changes nothing.
            p.foldLunicleRow(0, p.ref("i:2"), folded = false, now = 0)
            assertEquals("d:2", p.foldLunicleRow(0, p.ref("d:2"), folded = false, now = 0)?.key)
            assertTrue("a:2" in p.keys())
        }
        // ⌘↑ on an issue folds it and stays; again changes nothing.
        assertEquals("i:2", p.foldLunicleRow(0, p.ref("i:2"), folded = true, now = 0)?.key)
        assertEquals("i:2", p.foldLunicleRow(0, p.ref("i:2"), folded = true, now = 0)?.key)
        assertFalse("d:2" in p.keys())
        // A row that is not on the board: nothing.
        assertNull(p.foldLunicleRow(0, LunicleRowRef(LunicleRowKind.ISSUE, "New", 99), folded = true, now = 0))
        assertNull(p.foldLunicleRow(1, p.ref("i:2"), folded = true, now = 0))
    }

    @Test
    fun a_poll_that_inserts_issues_above_keeps_the_caret_on_the_same_issue() = runTest {
        val p = pane()
        val caret = p.ref("i:1")
        assertEquals(2, LunicleBoardRows.indexOf(p.rows(), caret))
        // Two new high-priority issues land above it.
        api.answer(
            LunicleMethod.GET, boardPath, 200,
            boardJson(
                firstIssues + """,
                {"id":7,"key":"FRA-7","title":"New urgent","status":"New","priority":"High","updatedAt":2,"canEdit":true},
                {"id":8,"key":"FRA-8","title":"Another","status":"New","priority":"High","updatedAt":2,"canEdit":true}""",
            ),
        )
        p.reportShownBoards(mapOf(p.lunicleBoardOf(p.stateFlow.value, 0, 0)!!.key to emptySet()))
        boards.refreshShown()
        runCurrent()
        val rows = p.rows()
        assertEquals(listOf("c:New", "i:2", "i:7", "i:8", "i:1", "e:New", "c:In progress", "e:In progress", "c:Closed"), rows.map { it.ref.key })
        assertEquals(caret, LunicleBoardRows.relocate(rows, caret))
        assertEquals(4, LunicleBoardRows.indexOf(rows, caret))
        assertEquals(LunicleBoardRows.Step.To(p.ref("e:New")), LunicleBoardRows.step(rows, caret, down = true))
    }

    @Test
    fun a_moved_or_gone_row_is_found_again() = runTest {
        val p = pane()
        unfoldUrgent(p)
        val comment = p.ref("m:2:6")
        val issue1 = p.ref("i:1")
        // Issue 1 moves to In progress; issue 2 loses a comment.
        api.answer(
            LunicleMethod.GET, boardPath, 200,
            boardJson(firstIssues.replace("\"title\":\"Low one\",\"status\":\"New\"", "\"title\":\"Low one\",\"status\":\"In progress\"")),
        )
        api.answer(
            LunicleMethod.GET, "/api/v1/issues/2", 200,
            """{"id":2,"key":"FRA-2","title":"Urgent","status":"New","priority":"High","updatedAt":3,"projectId":2,"canComment":true,
               "description":"Fix it","comments":[{"id":5,"body":"On it","author":"Linus","createdAt":0}]}""",
        )
        boards.onStreamMessage(
            se.soderbjorn.lunarbor.lunicle.LunicleStreamMessage.Event("c1", "comment.deleted", projectId = 2, issueId = 2),
        )
        boards.refreshShown()
        testScheduler.advanceTimeBy(1_000)
        runCurrent()
        val rows = p.rows()
        assertEquals(
            listOf("c:New", "i:2", "d:2", "m:2:5", "a:2", "e:New", "c:In progress", "i:1", "e:In progress", "c:Closed"),
            rows.map { it.ref.key },
        )
        // The moved issue is found in its new column; the gone comment falls back to its issue.
        assertEquals("In progress", LunicleBoardRows.relocate(rows, issue1)?.status)
        assertEquals("i:2", LunicleBoardRows.relocate(rows, comment)?.key)
        // An issue that is gone falls back to its column; a gone column leaves the board.
        assertEquals("c:New", LunicleBoardRows.relocate(rows, LunicleRowRef(LunicleRowKind.ISSUE, "New", 99))?.key)
        assertNull(LunicleBoardRows.relocate(rows, LunicleRowRef(LunicleRowKind.COLUMN, "Gone")))
        assertEquals(LunicleBoardRows.Step.LeaveUp, LunicleBoardRows.step(rows, LunicleRowRef(LunicleRowKind.COLUMN, "Gone"), down = true))
    }

    @Test
    fun cmd_up_on_the_node_line_folds_the_board_node() = runTest {
        val p = pane()
        val nodeId = p.stateFlow.value.documentState!!.lineIds[0]
        p.moveTo(0, 3)
        p.setCaretItemFolded(folded = true)
        assertTrue(nodeId in p.stateFlow.value.collapsedIds)
        p.setCaretItemFolded(folded = false)
        assertFalse(nodeId in p.stateFlow.value.collapsedIds)
    }
}
