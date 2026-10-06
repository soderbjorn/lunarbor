/* LunicleBoardDragTest.kt (commonTest)
 *
 * Pins dragging a board issue by its dot, through a real pane, board cache
 * and fake Lunicle API, and the drop rules on their own
 * ([LunicleBoardDrag]): above / below an issue in its priority, crossing a
 * priority boundary, into another column by its name or its "New issue"
 * line, a closing column asking for a resolution, no drop where the issue
 * already is or for an issue that cannot be edited; the optimistic order
 * ([LunicleOrderEdit]); the writes (`POST /issues/{id}/move`, then
 * `PUT /issues/{id}/order`); the fallback for a Lunicle without the order
 * route; and a failed write reverting. */
package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.lunicle.FakeLunicleApi
import se.soderbjorn.lunarbor.lunicle.LunicleBoardIssue
import se.soderbjorn.lunarbor.lunicle.LunicleBoards
import se.soderbjorn.lunarbor.lunicle.LunicleConnection
import se.soderbjorn.lunarbor.lunicle.LunicleConnectionStore
import se.soderbjorn.lunarbor.lunicle.LunicleConnectionsSnapshot
import se.soderbjorn.lunarbor.lunicle.LunicleMethod
import se.soderbjorn.lunarbor.lunicle.LunicleOrderEdit
import se.soderbjorn.lunarbor.lunicle.LunicleService
import se.soderbjorn.lunarbor.testing.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LunicleBoardDragTest {
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

    private val boardJson =
        """{"project":{"id":2,"name":"Framnafolk","keyPrefix":"FRA"},
           "statuses":[{"name":"New"},{"name":"In progress"},{"name":"Closed","requiresResolution":true}],
           "priorities":["High","Normal"],
           "resolutions":["Done","Will not fix"],
           "issues":[
             {"id":2,"key":"FRA-2","title":"Urgent","status":"New","priority":"High","updatedAt":1,"canEdit":true},
             {"id":1,"key":"FRA-1","title":"First","status":"New","priority":"Normal","updatedAt":1,"canEdit":true},
             {"id":3,"key":"FRA-3","title":"Third","status":"New","priority":"Normal","updatedAt":1,"canEdit":true},
             {"id":5,"key":"FRA-5","title":"Going","status":"In progress","priority":"Normal","updatedAt":1,"canEdit":true},
             {"id":4,"key":"FRA-4","title":"Someone else's","status":"In progress","priority":"Normal","updatedAt":1,"canEdit":false}]}"""

    private lateinit var api: FakeLunicleApi

    private suspend fun TestScope.pane(orderRoute: Boolean = true): PaneBackingViewModel {
        api = FakeLunicleApi().apply {
            answer(LunicleMethod.GET, "/api/v1/projects", 200, """[{"id":2,"name":"Framnafolk","keyPrefix":"FRA","yourRole":"contributor"}]""")
            answer(LunicleMethod.GET, "/api/v1/me", 200, """{"user":{"id":1,"name":"Ada"},"token":{"name":"t","scope":"write"}}""")
            answer(LunicleMethod.GET, "/api/v1/projects/2/board", 200, boardJson)
            for (id in listOf(1, 3)) {
                answer(LunicleMethod.POST, "/api/v1/issues/$id/move", 200, """{"message":"Moved."}""")
                answer(LunicleMethod.PATCH, "/api/v1/issues/$id", 200, """{"message":"Updated."}""")
                if (orderRoute) answer(LunicleMethod.PUT, "/api/v1/issues/$id/order", 200, """{"message":"Moved."}""")
            }
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

    /** Each column's issue ids, as drawn. */
    private fun TestScope.order(p: PaneBackingViewModel): Map<String, List<Long>> =
        view(p).columns.associate { c -> c.column.status.name to c.issues.map { it.issue.id } }

    private fun writes() = api.sent.map { it.second }.filter { it.method != LunicleMethod.GET }

    @Test
    fun an_issue_drops_above_or_below_another_in_that_issues_priority() = runTest {
        val p = pane()
        val below = p.lunicleDropAt(0, 1, "i:3", lowerHalf = true, now())!!
        assertEquals(LunicleDrop(1, "New", "Normal", afterId = 3, indicatorKey = "i:3", indicator = LunicleDropIndicator.AFTER), below)
        // Below the last high issue: high; above the first normal one: normal.
        val high = p.lunicleDropAt(0, 3, "i:2", lowerHalf = true, now())!!
        assertEquals("High" to 2L, high.priority to high.afterId)
        val normal = p.lunicleDropAt(0, 3, "i:1", lowerHalf = false, now())!!
        assertEquals(Triple("Normal", 1L, LunicleDropIndicator.BEFORE), Triple(normal.priority, normal.beforeId, normal.indicator))
    }

    @Test
    fun no_drop_where_it_already_is_on_itself_or_on_its_own_column() = runTest {
        val p = pane()
        assertNull(p.lunicleDropAt(0, 1, "i:3", lowerHalf = false, now()), "Right above the issue below it")
        assertNull(p.lunicleDropAt(0, 3, "i:1", lowerHalf = true, now()), "Right below the issue above it")
        assertNull(p.lunicleDropAt(0, 1, "i:1", lowerHalf = true, now()))
        assertNull(p.lunicleDropAt(0, 1, "c:New", lowerHalf = true, now()))
    }

    @Test
    fun a_column_name_or_new_issue_line_drops_into_that_column() = runTest {
        val p = pane()
        val into = p.lunicleDropAt(0, 1, "c:In progress", lowerHalf = false, now())!!
        assertEquals(
            LunicleDrop(1, "In progress", "Normal", indicatorKey = "c:In progress", indicator = LunicleDropIndicator.INTO),
            into,
        )
        val last = p.lunicleDropAt(0, 1, "e:In progress", lowerHalf = false, now())!!
        assertEquals("In progress" to 4L, last.status to last.afterId)
    }

    @Test
    fun a_closing_column_asks_for_a_resolution_first() = runTest {
        val p = pane()
        val drop = p.lunicleDropAt(0, 1, "c:Closed", lowerHalf = false, now())!!
        assertTrue(drop.needsResolution)
        val pick = p.dropLunicleIssue(0, drop, now())
        assertIs<PaneBackingViewModel.LuniclePick.NeedsResolution>(pick)
        assertEquals(listOf("Done", "Will not fix"), pick.choice.resolutions)
        runCurrent()
        assertTrue(writes().isEmpty(), "Nothing is sent before the resolution")
    }

    @Test
    fun an_issue_that_cannot_be_edited_is_not_dragged() = runTest {
        val p = pane()
        assertFalse(p.canDragLunicleIssue(0, 4, now()))
        assertTrue(p.canDragLunicleIssue(0, 1, now()))
    }

    @Test
    fun a_drop_across_priorities_shows_at_once_and_sends_one_reorder() = runTest {
        val p = pane()
        val drop = p.lunicleDropAt(0, 3, "i:2", lowerHalf = true, now())!!
        val pick = p.dropLunicleIssue(0, drop, now())
        assertEquals(listOf(2L, 3L, 1L), order(p)["New"], "Shown in its new place before Lunicle answers")
        assertEquals("High", view(p).columns.first().issues[1].issue.priority)
        assertEquals("i:3", (pick as PaneBackingViewModel.LuniclePick.Done).caret?.key)
        runCurrent()
        val sent = writes().single()
        assertEquals(LunicleMethod.PUT to "/api/v1/issues/3/order", sent.method to sent.path)
        assertEquals(JsonObject(mapOf("after_issue_id" to JsonPrimitive(2), "priority" to JsonPrimitive("High"))), sent.body)
    }

    @Test
    fun a_drop_in_another_column_moves_then_reorders() = runTest {
        val p = pane()
        val drop = p.lunicleDropAt(0, 1, "i:5", lowerHalf = false, now())!!
        p.dropLunicleIssue(0, drop, now())
        assertEquals(listOf(1L, 5L, 4L), order(p)["In progress"])
        runCurrent()
        val sent = writes()
        assertEquals(
            listOf(LunicleMethod.POST to "/api/v1/issues/1/move", LunicleMethod.PUT to "/api/v1/issues/1/order"),
            sent.map { it.method to it.path },
        )
        assertEquals(JsonObject(mapOf("status" to JsonPrimitive("In progress"))), sent[0].body)
        assertEquals(JsonObject(mapOf("before_issue_id" to JsonPrimitive(5))), sent[1].body, "The priority is unchanged, so not sent")
    }

    @Test
    fun a_lunicle_without_the_order_route_still_gets_the_priority() = runTest {
        val p = pane(orderRoute = false)
        p.dropLunicleIssue(0, p.lunicleDropAt(0, 3, "i:2", lowerHalf = true, now())!!, now())
        runCurrent()
        assertEquals(
            listOf(LunicleMethod.PUT to "/api/v1/issues/3/order", LunicleMethod.PATCH to "/api/v1/issues/3"),
            writes().map { it.method to it.path },
        )
        assertEquals(JsonObject(mapOf("priority" to JsonPrimitive("High"))), writes()[1].body)
        assertEquals(LunicleBoards.REORDER_UNSUPPORTED_TEXT, view(p).sync.text)
    }

    @Test
    fun a_failed_move_puts_the_issue_back() = runTest {
        val p = pane()
        api.answer(LunicleMethod.POST, "/api/v1/issues/1/move", 500, """{"error":"oops","message":"Boom."}""")
        p.dropLunicleIssue(0, p.lunicleDropAt(0, 1, "c:In progress", lowerHalf = false, now())!!, now())
        assertEquals(listOf(5L, 4L, 1L), order(p)["In progress"])
        runCurrent()
        assertEquals(listOf(2L, 1L, 3L), order(p)["New"])
        assertEquals(listOf(LunicleMethod.POST to "/api/v1/issues/1/move"), writes().map { it.method to it.path })
    }

    @Test
    fun an_order_edit_lands_next_to_its_anchor_or_last() {
        fun issue(id: Long, status: String = "New", priority: String = "Normal") =
            LunicleBoardIssue(id, "K-$id", "T$id", status, priority)
        val list = listOf(issue(1), issue(2), issue(3))
        assertEquals(listOf(2L, 1L, 3L), LunicleOrderEdit(1, 1, "New", "Normal", afterId = 2).applyTo(list).map { it.id })
        assertEquals(listOf(3L, 1L, 2L), LunicleOrderEdit(1, 3, "New", "Normal", beforeId = 1).applyTo(list).map { it.id })
        val moved = LunicleOrderEdit(1, 1, "Closed", "Normal", resolution = "Done").applyTo(list)
        assertEquals(listOf(2L, 3L, 1L), moved.map { it.id })
        assertEquals("Closed" to "Done", moved.last().status to moved.last().resolution)
    }
}
