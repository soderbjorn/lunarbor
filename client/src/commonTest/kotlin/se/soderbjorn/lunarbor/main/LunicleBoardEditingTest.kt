/* LunicleBoardEditingTest.kt (commonTest)
 *
 * Pins editing a board node's rows (LBR-29), through a real pane, board
 * cache and fake Lunicle API: a title edit is sent when the caret leaves
 * it (`PATCH /issues/{id}` `{title}`), optimistically, and not when it is
 * empty or unchanged; Enter on a title, on a column's name and on the
 * "New issue" line places drafts below the issue, at the top of the column
 * and at its end; a draft is filed (`POST /projects/{id}/issues`) once it
 * has a title and the caret leaves it, and gets its key; an empty draft is
 * removed by ⌫ or by leaving it; drafts survive polls and are dropped when
 * the pane navigates; a failed write reverts and shows the error; a
 * read-only token (or `canEdit: false`) edits nothing and says why once;
 * and the placement rules on their own ([LunicleBoardEditing.place]). */
package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.lunicle.FakeLunicleApi
import se.soderbjorn.lunarbor.lunicle.LunicleBoardIssue
import se.soderbjorn.lunarbor.lunicle.LunicleBoardKey
import se.soderbjorn.lunarbor.lunicle.LunicleBoardLayout
import se.soderbjorn.lunarbor.lunicle.LunicleBoards
import se.soderbjorn.lunarbor.lunicle.LunicleConnection
import se.soderbjorn.lunarbor.lunicle.LunicleConnectionStore
import se.soderbjorn.lunarbor.lunicle.LunicleConnectionsSnapshot
import se.soderbjorn.lunarbor.lunicle.LunicleCreatingIssue
import se.soderbjorn.lunarbor.lunicle.LunicleDraftAnchor
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
class LunicleBoardEditingTest {
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
           "statuses":[{"name":"New"},{"name":"In progress"},{"name":"Closed","requiresResolution":true}],
           "priorities":["High","Normal"],
           "issues":[$issues]}"""

    private val issues = """
        {"id":1,"key":"FRA-1","title":"Low one","status":"New","priority":"Normal","updatedAt":1,"canEdit":true},
        {"id":2,"key":"FRA-2","title":"Urgent","status":"New","priority":"High","updatedAt":1,"canEdit":true},
        {"id":4,"key":"FRA-4","title":"Someone else's","status":"In progress","priority":"Normal","updatedAt":1,"canEdit":false}"""

    private fun api(scope: String = "write") = FakeLunicleApi().apply {
        answer(LunicleMethod.GET, "/api/v1/projects", 200, """[{"id":2,"name":"Framnafolk","keyPrefix":"FRA","yourRole":"contributor"}]""")
        answer(LunicleMethod.GET, "/api/v1/me", 200, """{"user":{"id":1,"name":"Ada"},"token":{"name":"t","scope":"$scope"}}""")
        answer(LunicleMethod.GET, boardPath, 200, boardJson(issues))
        answer(LunicleMethod.PATCH, "/api/v1/issues/1", 200, """{"message":"Updated FRA-1."}""")
        answer(LunicleMethod.POST, "/api/v1/projects/2/issues", 201, """{"id":9,"key":"FRA-9","message":"Created FRA-9."}""")
    }

    private lateinit var api: FakeLunicleApi
    private lateinit var boards: LunicleBoards
    private val key = LunicleBoardKey("work", "FRA")

    private suspend fun TestScope.pane(scope: String = "write"): PaneBackingViewModel {
        api = api(scope)
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

    private fun TestScope.now() = testScheduler.currentTime

    private fun TestScope.view(p: PaneBackingViewModel) = p.lunicleBoardOf(p.stateFlow.value, 0, now())!!

    private fun TestScope.keys(p: PaneBackingViewModel): List<String> = LunicleBoardRows.of(view(p)).map { it.ref.key }

    private fun TestScope.ref(p: PaneBackingViewModel, k: String): LunicleRowRef =
        LunicleBoardRows.of(view(p)).first { it.ref.key == k }.ref

    private fun TestScope.titleOf(p: PaneBackingViewModel, id: Long): String =
        view(p).columns.flatMap { it.issues }.first { it.issue.id == id }.issue.title

    private fun json(vararg fields: Pair<String, String>) =
        kotlinx.serialization.json.JsonObject(fields.associate { (k, v) -> k to JsonPrimitive(v) })

    private fun writes(method: LunicleMethod) = api.sent.map { it.second }.filter { it.method == method }

    @Test
    fun a_title_is_sent_when_the_caret_leaves_it_and_shows_at_once() = runTest {
        val p = pane()
        val title = ref(p, "i:1")
        assertEquals("Low one", p.beginLunicleEdit(0, title, now()))
        p.leaveLunicleRow(key, title, "  Lower one ")
        // Optimistic: the new title and "Saving to Lunicle…" before Lunicle answers.
        assertEquals("Lower one", titleOf(p, 1))
        assertEquals(LunicleSyncKind.SAVING, view(p).sync.kind)
        // Lunicle has it now; the re-read after the write brings it back, touched.
        api.answer(LunicleMethod.GET, boardPath, 200, boardJson(issues.replace("\"Low one\",\"status\":\"New\",\"priority\":\"Normal\",\"updatedAt\":1", "\"Lower one\",\"status\":\"New\",\"priority\":\"Normal\",\"updatedAt\":7")))
        p.reportShownBoards(mapOf(key to emptySet()))
        runCurrent()
        val patch = writes(LunicleMethod.PATCH).single()
        assertEquals("/api/v1/issues/1", patch.path)
        assertEquals(json("title" to "Lower one"), patch.body)
        assertEquals("Synced just now", view(p).sync.text)
        assertEquals("Lower one", titleOf(p, 1))
        assertNull(p.stateFlow.value.lunicleEditing)
        // An own edit is not news: no "Lower one changed" notice.
        assertEquals(2, api.sent.count { it.second.path == boardPath })
        testScheduler.advanceTimeBy(LunicleBoardLayout.SAVED_MS + 1)
        runCurrent()
        assertFalse(view(p).sync.kind == LunicleSyncKind.REMOTE)
    }

    @Test
    fun an_empty_or_unchanged_title_is_not_sent() = runTest {
        val p = pane()
        val title = ref(p, "i:1")
        p.beginLunicleEdit(0, title, now())
        p.leaveLunicleRow(key, title, "   ")
        p.beginLunicleEdit(0, title, now())
        p.leaveLunicleRow(key, title, "Low one")
        runCurrent()
        assertTrue(writes(LunicleMethod.PATCH).isEmpty())
        assertEquals("Low one", titleOf(p, 1))
    }

    @Test
    fun a_failed_write_reverts_and_shows_the_error() = runTest {
        val p = pane()
        api.answer(LunicleMethod.PATCH, "/api/v1/issues/1", 500, """{"error":"internal_error","message":"Boom."}""")
        val title = ref(p, "i:1")
        p.beginLunicleEdit(0, title, now())
        p.leaveLunicleRow(key, title, "Renamed")
        assertEquals("Renamed", titleOf(p, 1))
        runCurrent()
        assertEquals("Low one", titleOf(p, 1))
        val sync = view(p).sync
        assertEquals(LunicleSyncKind.ERROR, sync.kind)
        assertTrue("Boom" in sync.text, sync.text)
        // The error goes after a while; the board is as before.
        testScheduler.advanceTimeBy(LunicleBoards.ALERT_MS + 1)
        runCurrent()
        assertFalse(view(p).sync.kind == LunicleSyncKind.ERROR)
    }

    @Test
    fun a_failed_create_removes_its_row() = runTest {
        val p = pane()
        api.answer(LunicleMethod.POST, "/api/v1/projects/2/issues", 400, """{"error":"invalid_request","message":"No such priority."}""")
        val draft = p.lunicleEnter(0, ref(p, "i:1"), "Low one", now())!!
        p.leaveLunicleRow(key, draft, "Doomed")
        assertTrue(keys(p).any { it.startsWith("p:") })
        runCurrent()
        assertFalse(keys(p).any { it.startsWith("p:") || it.startsWith("n:") })
        assertEquals(LunicleSyncKind.ERROR, view(p).sync.kind)
    }

    @Test
    fun enter_on_a_title_starts_a_draft_below_it_in_its_column_and_priority() = runTest {
        val p = pane()
        val draft = p.lunicleEnter(0, ref(p, "i:2"), "Urgent", now())!!
        assertEquals(LunicleRowKind.DRAFT, draft.kind)
        assertEquals(listOf("c:New", "i:2", draft.key, "i:1", "e:New"), keys(p).take(5))
        val d = p.stateFlow.value.lunicleDrafts.single()
        assertEquals("New", d.status)
        assertEquals("High", d.priority)
        // Leaving it with a title files it with title, status and priority.
        p.leaveLunicleRow(key, draft, " Follow-up ")
        assertTrue(p.stateFlow.value.lunicleDrafts.isEmpty())
        val filing = "p:${d.localId}"
        assertEquals(listOf("c:New", "i:2", filing, "i:1"), keys(p).take(4))
        runCurrent()
        val post = writes(LunicleMethod.POST).single()
        assertEquals("/api/v1/projects/2/issues", post.path)
        assertEquals(
            json("title" to "Follow-up", "status" to "New", "priority" to "High"),
            post.body,
        )
        // Once the board lists the new issue its stand-in row goes, and the
        // caret's ref follows the draft into it.
        val created = view(p).createdIds[d.localId]
        assertEquals(9L, created)
        api.answer(
            LunicleMethod.GET, boardPath, 200,
            boardJson(issues + """,{"id":9,"key":"FRA-9","title":"Follow-up","status":"New","priority":"High","updatedAt":5,"canEdit":true}"""),
        )
        p.reportShownBoards(mapOf(key to emptySet()))
        boards.refreshShown()
        runCurrent()
        assertFalse(filing in keys(p))
        assertEquals("i:9", LunicleBoardRows.relocate(LunicleBoardRows.of(view(p)), draft, view(p).createdIds)?.key)
        // Its own creation is not reported as somebody else's change.
        assertFalse(view(p).sync.kind == LunicleSyncKind.REMOTE)
    }

    @Test
    fun enter_mid_title_commits_it_and_starts_the_draft_too() = runTest {
        val p = pane()
        val title = ref(p, "i:1")
        p.beginLunicleEdit(0, title, now())
        val draft = p.lunicleEnter(0, title, "Low one, renamed", now())
        assertEquals(LunicleRowKind.DRAFT, draft?.kind)
        runCurrent()
        assertEquals(json("title" to "Low one, renamed"), writes(LunicleMethod.PATCH).single().body)
    }

    @Test
    fun enter_on_a_draft_files_it_and_starts_the_next_below_it() = runTest {
        val p = pane()
        val first = p.lunicleEnter(0, ref(p, "i:1"), "Low one", now())!!
        // Empty: Enter does nothing.
        assertNull(p.lunicleEnter(0, first, "", now()))
        val second = p.lunicleEnter(0, first, "One", now())!!
        val firstId = first.issueId!!
        assertEquals(listOf("i:1", "p:$firstId", second.key, "e:New"), keys(p).drop(2).take(4))
        assertEquals("Normal", p.stateFlow.value.lunicleDrafts.single().priority)
    }

    @Test
    fun enter_on_a_column_name_adds_a_draft_at_its_top_and_unfolds_it() = runTest {
        val p = pane()
        val draft = p.lunicleEnter(0, ref(p, "c:New"), "", now())!!
        assertEquals(listOf("c:New", draft.key, "i:2"), keys(p).take(3))
        assertEquals("High", p.stateFlow.value.lunicleDrafts.single().priority)
        // Closed starts folded: Enter unfolds it; an empty column's draft has the default priority.
        val closed = p.lunicleEnter(0, ref(p, "c:Closed"), "", now())!!
        assertEquals(listOf("c:Closed", closed.key, "e:Closed"), keys(p).takeLast(3))
        assertNull(p.stateFlow.value.lunicleDrafts.last().priority)
    }

    @Test
    fun the_new_issue_line_files_at_the_end_of_its_column_at_the_default_priority() = runTest {
        val p = pane()
        val line = ref(p, "e:In progress")
        assertEquals("", p.beginLunicleEdit(0, line, now()))
        // Empty: nothing.
        assertNull(p.lunicleEnter(0, line, "  ", now()))
        assertEquals(line, p.lunicleEnter(0, line, "From the line", now()))
        val after = keys(p)
        val at = after.indexOf("e:In progress")
        assertTrue(after[at - 1].startsWith("p:"), after.toString())
        assertEquals("i:4", after[at - 2])
        runCurrent()
        assertEquals(
            json("title" to "From the line", "status" to "In progress"),
            writes(LunicleMethod.POST).single().body,
        )
        // Leaving the line with text files too.
        p.leaveLunicleRow(key, line, "Another")
        runCurrent()
        assertEquals(2, writes(LunicleMethod.POST).size)
    }

    @Test
    fun an_empty_draft_is_removed_by_backspace_or_by_leaving_it() = runTest {
        val p = pane()
        val draft = p.lunicleEnter(0, ref(p, "i:2"), "Urgent", now())!!
        // ⌫: removed, the caret goes to the row above.
        assertEquals(LunicleBoardRows.Step.To(ref(p, "i:2")), p.removeLunicleDraft(0, draft, now()))
        assertTrue(p.stateFlow.value.lunicleDrafts.isEmpty())
        // Leaving it empty removes it too, and sends nothing.
        val again = p.lunicleEnter(0, ref(p, "i:2"), "Urgent", now())!!
        p.leaveLunicleRow(key, again, "")
        runCurrent()
        assertTrue(p.stateFlow.value.lunicleDrafts.isEmpty())
        assertFalse(keys(p).any { it.startsWith("n:") || it.startsWith("p:") })
        assertTrue(writes(LunicleMethod.POST).isEmpty())
        // ⌫ on a draft at the top of a column: the caret goes to the column's name.
        val top = p.lunicleEnter(0, ref(p, "c:New"), "", now())!!
        assertEquals(LunicleBoardRows.Step.To(ref(p, "c:New")), p.removeLunicleDraft(0, top, now()))
    }

    @Test
    fun a_poll_keeps_drafts_and_the_row_being_edited() = runTest {
        val p = pane()
        val draft = p.lunicleEnter(0, ref(p, "i:2"), "Urgent", now())!!
        val title = ref(p, "i:1")
        p.beginLunicleEdit(0, title, now())
        // Issue 1 is gone from the next read, issue 2 renamed.
        api.answer(
            LunicleMethod.GET, boardPath, 200,
            boardJson("""{"id":2,"key":"FRA-2","title":"Urgent!","status":"New","priority":"High","updatedAt":2,"canEdit":true}"""),
        )
        p.reportShownBoards(mapOf(key to emptySet()))
        boards.refreshShown()
        runCurrent()
        assertEquals("Urgent!", titleOf(p, 2))
        assertTrue(draft.key in keys(p))
        assertTrue("i:1" in keys(p))
    }

    @Test
    fun drafts_are_dropped_when_the_pane_navigates_away() = runTest {
        val p = pane()
        p.lunicleEnter(0, ref(p, "i:2"), "Urgent", now())
        assertEquals(1, p.stateFlow.value.lunicleDrafts.size)
        p.zoomInto(1)
        runCurrent()
        assertTrue(p.stateFlow.value.lunicleDrafts.isEmpty())
    }

    @Test
    fun a_read_only_token_edits_nothing_and_says_why_once() = runTest {
        val p = pane(scope = "read")
        val v = view(p)
        assertTrue(v.readOnly)
        assertFalse(v.canCreate)
        // No "New issue" lines, no editable titles.
        assertFalse(keys(p).any { it.startsWith("e:") })
        assertTrue(LunicleBoardRows.of(v).none { it.ref.kind == LunicleRowKind.ISSUE && it.editable })
        val title = ref(p, "i:1")
        assertNull(p.beginLunicleEdit(0, title, now()))
        assertNull(p.lunicleEnter(0, title, "Low one", now()))
        assertNull(p.lunicleEnter(0, ref(p, "c:New"), "", now()))
        assertTrue(p.stateFlow.value.lunicleDrafts.isEmpty())
        assertEquals(LunicleBoardLayout.READ_ONLY_TEXT, view(p).sync.text)
        // Said once: after it fades, trying again says nothing.
        testScheduler.advanceTimeBy(LunicleBoards.ALERT_MS + 1)
        runCurrent()
        p.explainLunicleRow(0, title, now())
        assertFalse(view(p).sync.kind == LunicleSyncKind.ERROR)
        assertTrue(api.sent.none { it.second.method != LunicleMethod.GET })
    }

    @Test
    fun a_write_refused_as_read_only_marks_the_board_read_only() = runTest {
        val p = pane()
        api.answer(
            LunicleMethod.PATCH, "/api/v1/issues/1", 403,
            """{"error":"insufficient_scope","message":"This token is read-only."}""",
        )
        val title = ref(p, "i:1")
        p.beginLunicleEdit(0, title, now())
        p.leaveLunicleRow(key, title, "Nope")
        runCurrent()
        assertEquals("Low one", titleOf(p, 1))
        assertTrue(view(p).readOnly)
        assertEquals(LunicleBoardLayout.READ_ONLY_TEXT, view(p).sync.text)
        assertFalse(keys(p).any { it.startsWith("e:") })
    }

    @Test
    fun an_issue_without_edit_rights_cannot_be_renamed() = runTest {
        val p = pane()
        val other = ref(p, "i:4")
        assertFalse(LunicleBoardRows.of(view(p)).first { it.ref.key == "i:4" }.editable)
        assertNull(p.beginLunicleEdit(0, other, now()))
        assertNull(p.lunicleEnter(0, other, "", now()))
        assertEquals(LunicleBoardLayout.noEditText("FRA-4"), view(p).sync.text)
    }

    @Test
    fun placement_follows_the_anchors() {
        fun issue(id: Long) = PaneBackingViewModel.LunicleIssueView(
            LunicleBoardIssue(id, "FRA-$id", "t$id", "New", "Normal"), "k$id", false, emptyList(), null, null, false, null,
        )
        fun draft(local: Long, anchor: LunicleDraftAnchor) = LunicleDraft(local, key, "New", null, anchor)
        fun names(items: List<LunicleColumnItem>) = items.map {
            when (it) {
                is LunicleColumnItem.Issue -> "i${it.view.issue.id}"
                is LunicleColumnItem.Draft -> "d${-it.draft.localId}"
                is LunicleColumnItem.Creating -> "c${-it.entry.localId}"
            }
        }
        val placed = LunicleBoardEditing.place(
            listOf(issue(1), issue(2)),
            listOf(LunicleCreatingIssue(-1, "New", null, "x", LunicleDraftAnchor.AfterIssue(1))),
            listOf(
                draft(-2, LunicleDraftAnchor.AfterLocal(-1)),
                draft(-3, LunicleDraftAnchor.Top),
                draft(-4, LunicleDraftAnchor.End),
                draft(-5, LunicleDraftAnchor.AfterIssue(99)),
            ),
        )
        assertEquals(listOf("d3", "i1", "c1", "d2", "i2", "d4", "d5"), names(placed))
        // A filed draft's followers find the issue it became.
        val later = LunicleBoardEditing.place(listOf(issue(1), issue(9)), emptyList(), listOf(draft(-2, LunicleDraftAnchor.AfterLocal(-1))), mapOf(-1L to 9L))
        assertEquals(listOf("i1", "i9", "d2"), names(later))
        // Commit rules.
        assertNull(LunicleBoardEditing.committedTitle("Same", " Same "))
        assertNull(LunicleBoardEditing.committedTitle("Old", "  "))
        assertEquals("New", LunicleBoardEditing.committedTitle("Old", " New "))
        assertNull(LunicleBoardEditing.draftTitle(" "))
    }
}
