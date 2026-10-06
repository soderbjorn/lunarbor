/* LunicleBoardMenuTest.kt (commonTest)
 *
 * Pins changing an issue's properties from its line (LBR-30), through a
 * real pane, board cache and fake Lunicle API, and the menu rules on their
 * own ([LunicleBoardMenu]): the `#` menu's Status and Priority sections,
 * the `@` menu's Assignee list (`assignableUsers`, the board's names as the
 * fallback, none for a viewer), filtering, the highlight wrapping round the
 * ends and the menu keys; the resolution popup only for a status that
 * requires one; a move placing the issue under its new column, flashing
 * there with the caret following (a folded column unfolded, a folded
 * closing column left folded with its own row flashing); the writes sent
 * (`POST /issues/{id}/move`, `PATCH` priority / assignee); and a failed
 * write reverting. */
package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.lunicle.FakeLunicleApi
import se.soderbjorn.lunarbor.lunicle.LunicleBoard
import se.soderbjorn.lunarbor.lunicle.LunicleBoardIssue
import se.soderbjorn.lunarbor.lunicle.LunicleBoardKey
import se.soderbjorn.lunarbor.lunicle.LunicleBoards
import se.soderbjorn.lunarbor.lunicle.LunicleConnection
import se.soderbjorn.lunarbor.lunicle.LunicleConnectionStore
import se.soderbjorn.lunarbor.lunicle.LunicleConnectionsSnapshot
import se.soderbjorn.lunarbor.lunicle.LunicleMethod
import se.soderbjorn.lunarbor.lunicle.LuniclePill
import se.soderbjorn.lunarbor.lunicle.LunicleProject
import se.soderbjorn.lunarbor.lunicle.LunicleService
import se.soderbjorn.lunarbor.lunicle.LunicleStatus
import se.soderbjorn.lunarbor.lunicle.LunicleSyncKind
import se.soderbjorn.lunarbor.testing.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LunicleBoardMenuTest {
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

    private fun boardJson(assignable: String? = """[{"name":"Ada"},{"name":"Linus"},{"name":"Linus"}]""") =
        """{"project":{"id":2,"name":"Framnafolk","keyPrefix":"FRA"},
           "statuses":[{"name":"New"},{"name":"In progress"},{"name":"Closed","requiresResolution":true}],
           "priorities":["High","Normal"],
           "resolutions":["Done","Will not fix","Duplicate"],
           ${assignable?.let { "\"assignableUsers\":$it," } ?: ""}
           "issues":[
             {"id":1,"key":"FRA-1","title":"Low one","status":"New","priority":"Normal","assignee":"Linus","updatedAt":1,"canEdit":true},
             {"id":2,"key":"FRA-2","title":"Urgent","status":"New","priority":"High","updatedAt":1,"canEdit":true},
             {"id":4,"key":"FRA-4","title":"Someone else's","status":"In progress","priority":"Normal","updatedAt":1,"canEdit":false}]}"""

    private lateinit var api: FakeLunicleApi
    private val key = LunicleBoardKey("work", "FRA")

    private suspend fun TestScope.pane(board: String = boardJson()): PaneBackingViewModel {
        api = FakeLunicleApi().apply {
            answer(LunicleMethod.GET, "/api/v1/projects", 200, """[{"id":2,"name":"Framnafolk","keyPrefix":"FRA","yourRole":"contributor"}]""")
            answer(LunicleMethod.GET, "/api/v1/me", 200, """{"user":{"id":1,"name":"Ada"},"token":{"name":"t","scope":"write"}}""")
            answer(LunicleMethod.GET, boardPath, 200, board)
            answer(LunicleMethod.PATCH, "/api/v1/issues/1", 200, """{"message":"Updated FRA-1."}""")
            answer(LunicleMethod.POST, "/api/v1/issues/1/move", 200, """{"message":"Moved FRA-1."}""")
        }
        fs.writeFile("$root/_node.md", "- Lunicle board {{lunicle: work/FRA}}\n- Other\n")
        val boards = LunicleBoards(LunicleService(api, store), null, backgroundScope, now = { testScheduler.currentTime })
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

    private fun TestScope.columnOf(p: PaneBackingViewModel, id: Long): String =
        view(p).columns.first { c -> c.issues.any { it.issue.id == id } }.column.status.name

    private fun TestScope.labels(p: PaneBackingViewModel, menu: LunicleMenu) = p.lunicleMenuOptions(0, menu, now()).map { it.label }

    private fun writes(method: LunicleMethod) = api.sent.map { it.second }.filter { it.method == method }

    @Test
    fun hash_opens_status_and_priority_sections_with_the_current_values_checked() = runTest {
        val p = pane()
        val menu = p.openLunicleMenu(0, 1, '#', null, now())!!
        val options = p.lunicleMenuOptions(0, menu, now())
        assertEquals(listOf("#new", "#in-progress", "#closed", "#high", "#normal"), options.map { it.label })
        assertEquals(listOf("Status", "Status", "Status", "Priority", "Priority"), options.map { it.section })
        assertEquals(listOf(true, false, false, true, false), options.map { it.showHeader })
        assertEquals(listOf("#new", "#normal"), options.filter { it.current }.map { it.label })
        assertEquals("#", LunicleBoardMenu.head(menu))
    }

    @Test
    fun at_lists_nobody_and_the_assignable_users() = runTest {
        val p = pane()
        val menu = p.openLunicleMenu(0, 1, '@', null, now())!!
        val options = p.lunicleMenuOptions(0, menu, now())
        // Same-name users show twice, as Lunicle lists them.
        assertEquals(listOf("@nobody", "@ada", "@linus", "@linus"), options.map { it.label })
        assertEquals(listOf(true, false, false, false), options.map { it.showHeader })
        assertEquals(listOf("Linus", "Linus"), options.filter { it.current }.map { it.value })
    }

    @Test
    fun without_assignable_users_the_board_names_stand_in_and_a_viewer_gets_no_menu() = runTest {
        val p = pane(boardJson(assignable = null))
        val menu = p.openLunicleMenu(0, 2, '@', null, now())!!
        assertEquals(listOf("@nobody", "@linus"), labels(p, menu))
        val board = LunicleBoard(
            LunicleProject(2, "F", "FRA", "viewer"), listOf(LunicleStatus("New")), listOf("High"),
            issues = listOf(LunicleBoardIssue(1, "FRA-1", "t", "New", "High", assignee = "Linus")),
        )
        assertNull(LunicleBoardMenu.assignees(board, canCreate = false))
        assertEquals(listOf("Linus"), LunicleBoardMenu.assignees(board, canCreate = true))
        assertEquals(listOf("Zed"), LunicleBoardMenu.assignees(board.copy(assignableUsers = listOf("Zed")), canCreate = false))
    }

    @Test
    fun typing_narrows_the_list_and_keys_close_it() = runTest {
        val p = pane()
        var menu = p.openLunicleMenu(0, 1, '#', null, now())!!
        for (c in "PRO") menu = (LunicleBoardMenu.key(menu, p.lunicleMenuOptions(0, menu, now()), c.toString()) as LunicleMenuStep.Update).menu
        assertEquals("#PRO", LunicleBoardMenu.head(menu))
        assertEquals(listOf("#in-progress"), labels(p, menu))
        menu = menu.copy(query = "in progress".take(2))
        assertEquals(listOf("#in-progress"), labels(p, menu))
        assertTrue(labels(p, menu.copy(query = "zzz")).isEmpty())
        // ⌫ shortens the query, and on an empty query closes.
        val shorter = LunicleBoardMenu.key(menu, emptyList(), "Backspace") as LunicleMenuStep.Update
        assertEquals("i", shorter.menu.query)
        assertEquals(LunicleMenuStep.Close, LunicleBoardMenu.key(menu.copy(query = ""), emptyList(), "Backspace"))
        // A space and Esc close; Enter with nothing matching closes.
        assertEquals(LunicleMenuStep.Close, LunicleBoardMenu.key(menu, emptyList(), " "))
        assertEquals(LunicleMenuStep.Close, LunicleBoardMenu.key(menu, emptyList(), "Escape"))
        assertEquals(LunicleMenuStep.Close, LunicleBoardMenu.key(menu, emptyList(), "Enter"))
        // Enter and Tab pick the highlighted option.
        val options = p.lunicleMenuOptions(0, menu, now())
        assertEquals(LunicleMenuStep.Pick(options[0]), LunicleBoardMenu.key(menu, options, "Tab"))
        assertEquals(LunicleMenuStep.Pass, LunicleBoardMenu.key(menu, options, "ArrowLeft"))
    }

    @Test
    fun the_highlight_wraps_round_both_ends() = runTest {
        val p = pane()
        val menu = p.openLunicleMenu(0, 1, '#', null, now())!!
        val options = p.lunicleMenuOptions(0, menu, now())
        val up = LunicleBoardMenu.key(menu, options, "ArrowUp") as LunicleMenuStep.Update
        assertEquals(options.size - 1, up.menu.highlight)
        val down = LunicleBoardMenu.key(up.menu, options, "ArrowDown") as LunicleMenuStep.Update
        assertEquals(0, down.menu.highlight)
        assertEquals(1, LunicleBoardMenu.wrap(0, 3, down = true))
        assertEquals(2, LunicleBoardMenu.wrap(0, 3, down = false))
        // A list that shrank keeps the highlight on its last option.
        assertEquals(0, LunicleBoardMenu.highlightOf(menu.copy(highlight = 4), 1))
        val choice = LunicleResolutionChoice(key, 1, "Closed", listOf("Done", "Will not fix"))
        assertEquals("Done", choice.picked)
        assertEquals("Will not fix", choice.moved(down = false).picked)
        assertEquals("Done", choice.moved(down = true).moved(down = true).picked)
    }

    @Test
    fun a_pill_opens_its_field_only_with_the_current_value_highlighted() = runTest {
        val p = pane()
        val menu = p.openLunicleMenu(0, 1, null, LuniclePill.Field.PRIORITY, now())!!
        val options = p.lunicleMenuOptions(0, menu, now())
        assertEquals(listOf("#high", "#normal"), options.map { it.label })
        assertTrue(options.none { it.showHeader })
        assertEquals(1, menu.highlight)
        assertEquals("Priority", LunicleBoardMenu.head(menu))
        // Typing on a pill's menu closes it and types as usual.
        assertEquals(LunicleMenuStep.CloseAndPass, LunicleBoardMenu.key(menu, options, "x"))
        // An issue that cannot be edited opens no menu.
        assertNull(p.openLunicleMenu(0, 4, null, LuniclePill.Field.STATUS, now()))
        assertNull(p.openLunicleMenu(0, 4, '#', null, now()))
    }

    @Test
    fun only_a_status_that_requires_a_resolution_asks_for_one() = runTest {
        val p = pane()
        val menu = p.openLunicleMenu(0, 1, '#', null, now())!!
        val options = p.lunicleMenuOptions(0, menu, now())
        val closed = p.pickLunicleOption(0, menu, options.first { it.label == "#closed" }, now())
        val choice = assertIs<PaneBackingViewModel.LuniclePick.NeedsResolution>(closed).choice
        assertEquals(listOf("Done", "Will not fix", "Duplicate"), choice.resolutions)
        assertEquals(0, choice.highlight)
        runCurrent()
        assertTrue(writes(LunicleMethod.POST).isEmpty())
        // Priority and an open status go straight through.
        assertIs<PaneBackingViewModel.LuniclePick.Done>(p.pickLunicleOption(0, menu, options.first { it.label == "#high" }, now()))
        assertIs<PaneBackingViewModel.LuniclePick.Done>(p.pickLunicleOption(0, menu, options.first { it.label == "#in-progress" }, now()))
        runCurrent()
        val move = writes(LunicleMethod.POST).single { it.path == "/api/v1/issues/1/move" }
        assertEquals(JsonObject(mapOf("status" to JsonPrimitive("In progress"))), move.body)
        assertEquals(JsonObject(mapOf("priority" to JsonPrimitive("High"))), writes(LunicleMethod.PATCH).single().body)
    }

    @Test
    fun a_closing_move_sends_the_resolution_and_stays_in_the_folded_column() = runTest {
        val p = pane()
        val menu = p.openLunicleMenu(0, 1, '#', null, now())!!
        val pick = p.pickLunicleOption(0, menu, p.lunicleMenuOptions(0, menu, now()).first { it.label == "#closed" }, now())
        val choice = (pick as PaneBackingViewModel.LuniclePick.NeedsResolution).choice.moved(down = true)
        val caret = p.chooseLunicleResolution(0, choice, choice.picked, now())
        // Closed stays folded: its row flashes and takes the caret, and its count went up.
        assertEquals("c:Closed", caret?.key)
        val v = view(p)
        val column = v.columns.first { it.column.status.name == "Closed" }
        assertTrue(column.folded)
        assertEquals(1, column.column.count)
        assertEquals("c:Closed", v.flashKey)
        runCurrent()
        val move = writes(LunicleMethod.POST).single { it.path == "/api/v1/issues/1/move" }
        assertEquals(JsonObject(mapOf("status" to JsonPrimitive("Closed"), "resolution" to JsonPrimitive("Will not fix"))), move.body)
    }

    @Test
    fun a_move_places_the_issue_under_its_new_column_and_flashes_it_there() = runTest {
        val p = pane()
        // The user folded "In progress": the move unfolds it.
        p.toggleLunicleColumn(view(p).columns.first { it.column.status.name == "In progress" })
        assertTrue(view(p).columns.first { it.column.status.name == "In progress" }.folded)
        val menu = p.openLunicleMenu(0, 1, null, LuniclePill.Field.STATUS, now())!!
        val pick = p.pickLunicleOption(0, menu, p.lunicleMenuOptions(0, menu, now()).first { it.value == "In progress" }, now())
        assertEquals("i:1", (pick as PaneBackingViewModel.LuniclePick.Done).caret?.key)
        // At once, before Lunicle answers.
        assertEquals("In progress", columnOf(p, 1))
        assertEquals(LunicleSyncKind.SAVING, view(p).sync.kind)
        val v = view(p)
        assertTrue(!v.columns.first { it.column.status.name == "In progress" }.folded)
        assertEquals("i:1", v.flashKey)
        assertTrue(LunicleBoardRows.of(v).map { it.ref.key }.let { it.indexOf("i:1") > it.indexOf("c:In progress") })
        // The flash fades out.
        testScheduler.advanceTimeBy(LunicleBoardMenu.FLASH_MS + 1)
        assertNull(view(p).flashKey)
        // Picking the current value sends nothing more.
        runCurrent()
        val sent = api.sent.size
        val again = p.openLunicleMenu(0, 1, null, LuniclePill.Field.STATUS, now())!!
        p.pickLunicleOption(0, again, p.lunicleMenuOptions(0, again, now()).first { it.current }, now())
        runCurrent()
        assertEquals(0, api.sent.drop(sent).count { it.second.method != LunicleMethod.GET })
    }

    @Test
    fun nobody_unassigns_by_sending_null() = runTest {
        val p = pane()
        val menu = p.openLunicleMenu(0, 1, '@', null, now())!!
        p.pickLunicleOption(0, menu, p.lunicleMenuOptions(0, menu, now()).first { it.label == "@nobody" }, now())
        assertEquals(listOf("@nobody"), p.lunicleMenuOptions(0, menu, now()).filter { it.current }.map { it.label })
        runCurrent()
        assertEquals(JsonObject(mapOf("assignee" to JsonNull)), writes(LunicleMethod.PATCH).single().body)
    }

    @Test
    fun a_refused_change_reverts_with_lunicles_message() = runTest {
        val p = pane()
        api.answer(LunicleMethod.POST, "/api/v1/issues/1/move", 500, """{"error":"internal_error","message":"Boom."}""")
        api.answer(LunicleMethod.PATCH, "/api/v1/issues/1", 400, """{"error":"invalid_request","message":"Two users are called Linus."}""")
        val menu = p.openLunicleMenu(0, 1, '#', null, now())!!
        p.pickLunicleOption(0, menu, p.lunicleMenuOptions(0, menu, now()).first { it.label == "#in-progress" }, now())
        assertEquals("In progress", columnOf(p, 1))
        runCurrent()
        assertEquals("New", columnOf(p, 1))
        assertEquals(LunicleSyncKind.ERROR, view(p).sync.kind)
        assertTrue("Boom" in view(p).sync.text, view(p).sync.text)
        val at = p.openLunicleMenu(0, 2, '@', null, now())!!
        p.pickLunicleOption(0, at, p.lunicleMenuOptions(0, at, now()).first { it.label == "@linus" }, now())
        runCurrent()
        // Issue 2 has no PATCH answer: 404, reverted.
        assertNull(view(p).columns.flatMap { it.issues }.first { it.issue.id == 2L }.issue.assignee)
    }

    @Test
    fun placement_rules_on_their_own() = runTest {
        val p = pane()
        val v = view(p)
        assertEquals(LunicleChangePlacement(LunicleRowRef(LunicleRowKind.ISSUE, "New", 1), LunicleRowRef(LunicleRowKind.ISSUE, "New", 1)), LunicleBoardMenu.placeAfterChange(v, 1, null))
        val closed = LunicleBoardMenu.placeAfterChange(v, 1, "Closed")
        assertEquals("c:Closed", closed.flash.key)
        assertNull(closed.unfold)
        assertEquals("i:1", LunicleBoardMenu.placeAfterChange(v, 1, "In progress").caret.key)
    }
}
