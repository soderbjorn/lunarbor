/* LunicleBoardsTest.kt (commonTest)
 *
 * Pins [LunicleBoards], the board cache behind board nodes (LBR-27), on
 * virtual time against a fake API and a fake change stream: the first
 * read, polling every 15 s only while a pane shows the board (and not
 * while the stream is live), issue details read only for unfolded issues
 * and re-read when they change, the remote-change notice and its expiry,
 * errors keeping the last good board, and the change stream — watching
 * the shown projects, routing events to their boards, coalescing a burst
 * into one read, ignoring `self`, `reset` re-reading every board of the
 * connection, and falling back to polling on a disconnect or a 404. */
package se.soderbjorn.lunarbor.lunicle

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One connection, never changing. */
private class OneConnectionStore(val connection: LunicleConnection) : LunicleConnectionStore {
    override suspend fun list() = LunicleConnectionsSnapshot(listOf(connection))
    override suspend fun add(name: String?, baseUrl: String?, token: String?) = list()
    override suspend fun update(id: String, name: String?, baseUrl: String?, token: String?) = list()
    override suspend fun remove(id: String) = list()
}

/** A change stream the test drives by hand. */
private class FakeEvents : LunicleEventSource {
    val watches = ArrayList<Pair<String, Set<Long>>>()
    private var heard: ((LunicleStreamMessage) -> Unit)? = null
    override fun watch(connectionId: String, projectIds: Set<Long>) {
        watches += connectionId to projectIds
    }
    override fun setListener(listener: (LunicleStreamMessage) -> Unit) {
        heard = listener
    }
    fun emit(message: LunicleStreamMessage) = heard!!.invoke(message)
}

@OptIn(ExperimentalCoroutinesApi::class)
class LunicleBoardsTest {
    private val work = LunicleConnection("c1", "work", "https://issues.lunicle.dev", hasToken = true)
    private val key = LunicleBoardKey("work", "FRA")
    private val pane = Any()

    private fun boardJson(vararg issues: Triple<Long, String, Long>): String =
        """{"project":{"id":2,"name":"Framnafolk","keyPrefix":"FRA"},
           "statuses":[{"name":"New","requiresResolution":false},{"name":"Closed","requiresResolution":true}],
           "priorities":["High","Normal"],
           "issues":[${issues.joinToString(",") { (id, title, at) ->
            """{"id":$id,"key":"FRA-$id","title":"$title","status":"New","priority":"Normal","updatedAt":$at}"""
        }}]}"""

    private fun issueJson(id: Long, title: String, at: Long, vararg comments: Pair<Long, String>): String =
        """{"id":$id,"key":"FRA-$id","title":"$title","status":"New","priority":"Normal","updatedAt":$at,"projectId":2,
            "description":"About $title","comments":[${comments.joinToString(",") { (cid, who) ->
            """{"id":$cid,"body":"Hi","author":"$who","createdAt":0}"""
        }}]}"""

    private fun api(): FakeLunicleApi = FakeLunicleApi().apply {
        answer(LunicleMethod.GET, "/api/v1/projects", 200, """[{"id":2,"name":"Framnafolk","keyPrefix":"FRA"}]""")
        answer(LunicleMethod.GET, "/api/v1/projects/2/board", 200, boardJson(Triple(1, "Lönerapporter", 1), Triple(2, "Other", 1)))
        answer(LunicleMethod.GET, "/api/v1/issues/1", 200, issueJson(1, "Lönerapporter", 1))
    }

    private fun FakeLunicleApi.boardReads() = sent.count { it.second.path == "/api/v1/projects/2/board" }
    private fun FakeLunicleApi.issueReads(id: Long) = sent.count { it.second.path == "/api/v1/issues/$id" }

    private fun TestScope.boards(api: FakeLunicleApi, events: LunicleEventSource? = null) =
        LunicleBoards(LunicleService(api, OneConnectionStore(work)), events, backgroundScope, now = { testScheduler.currentTime })

    @Test
    fun first_read_then_polls_only_while_shown() = runTest {
        val api = api()
        val boards = boards(api)
        assertNull(boards.request(key)?.board)
        runCurrent()
        val state = boards.boardsFlow.value[key]!!
        assertEquals(listOf(1L, 2), state.board?.issues?.map { it.id })
        assertEquals("work", state.target?.connection?.name)
        assertNotNull(state.syncedAt)
        assertEquals(1, api.boardReads())
        // Not shown: no polling.
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, api.boardReads())
        // Shown (and stale): read at once, then every 15 s.
        boards.setInterest(pane, mapOf(key to emptySet()))
        runCurrent()
        assertEquals(2, api.boardReads())
        advanceTimeBy(15_000); runCurrent()
        assertEquals(3, api.boardReads())
        advanceTimeBy(15_000); runCurrent()
        assertEquals(4, api.boardReads())
        // No pane shows it any more: polling stops.
        boards.clearInterest(pane)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(4, api.boardReads())
    }

    @Test
    fun details_only_for_unfolded_issues_and_refetched_when_changed() = runTest {
        val api = api()
        val boards = boards(api)
        boards.setInterest(pane, mapOf(key to emptySet()))
        runCurrent()
        assertEquals(0, api.issueReads(1))
        // Unfolding issue 1 reads it in full.
        boards.setInterest(pane, mapOf(key to setOf(1L)))
        runCurrent()
        assertEquals(1, api.issueReads(1))
        assertEquals("About Lönerapporter", boards.boardsFlow.value[key]!!.details[1]?.description)
        // A poll with nothing changed reads no details.
        advanceTimeBy(15_000); runCurrent()
        assertEquals(1, api.issueReads(1))
        // Issue 1 and 2 change; only the unfolded one is re-read.
        api.answer(LunicleMethod.GET, "/api/v1/projects/2/board", 200, boardJson(Triple(1, "Lönerapporter", 5), Triple(2, "Other", 5)))
        api.answer(LunicleMethod.GET, "/api/v1/issues/1", 200, issueJson(1, "Lönerapporter", 5, 7L to "Linus"))
        advanceTimeBy(15_000); runCurrent()
        assertEquals(2, api.issueReads(1))
        assertEquals(0, api.issueReads(2))
        assertEquals(1, boards.boardsFlow.value[key]!!.details[1]?.comments?.size)
        assertEquals("2 issues changed", boards.boardsFlow.value[key]!!.notice)
    }

    @Test
    fun a_remote_change_shows_a_notice_for_a_while() = runTest {
        val api = api()
        val boards = boards(api)
        boards.setInterest(pane, mapOf(key to setOf(1L)))
        runCurrent()
        api.answer(LunicleMethod.GET, "/api/v1/projects/2/board", 200, boardJson(Triple(1, "Lönerapporter", 5), Triple(2, "Other", 1)))
        api.answer(LunicleMethod.GET, "/api/v1/issues/1", 200, issueJson(1, "Lönerapporter", 5, 7L to "Linus"))
        advanceTimeBy(15_000); runCurrent()
        val state = boards.boardsFlow.value[key]!!
        assertEquals("Linus commented on Lönerapporter", state.notice)
        assertEquals(LunicleSyncKind.REMOTE, LunicleBoardLayout.syncLine(state, testScheduler.currentTime).kind)
        advanceTimeBy(LunicleBoards.NOTICE_MS); runCurrent()
        assertNull(boards.boardsFlow.value[key]!!.notice)
    }

    @Test
    fun an_error_keeps_the_last_good_board() = runTest {
        val api = api()
        val boards = boards(api)
        boards.setInterest(pane, mapOf(key to emptySet()))
        runCurrent()
        api.routes["GET /api/v1/projects/2/board"] = LunicleHttpResponse.TransportError("Could not reach it.")
        advanceTimeBy(15_000); runCurrent()
        val state = boards.boardsFlow.value[key]!!
        assertEquals(LunicleError.Transport("Could not reach it."), state.error)
        assertEquals(2, state.board?.issues?.size)
        // Back online: the error clears.
        api.answer(LunicleMethod.GET, "/api/v1/projects/2/board", 200, boardJson(Triple(1, "Lönerapporter", 1), Triple(2, "Other", 1)))
        advanceTimeBy(15_000); runCurrent()
        assertNull(boards.boardsFlow.value[key]!!.error)
    }

    @Test
    fun an_unknown_key_or_connection_is_an_error() = runTest {
        val api = api()
        val boards = boards(api)
        boards.request(LunicleBoardKey("work", "ZZZ"))
        boards.request(LunicleBoardKey("home", "FRA"))
        runCurrent()
        assertEquals(LunicleError.NoProject("ZZZ", "work"), boards.boardsFlow.value[LunicleBoardKey("work", "ZZZ")]?.error)
        assertEquals(LunicleError.NoConnection("home", 1), boards.boardsFlow.value[LunicleBoardKey("home", "FRA")]?.error)
    }

    @Test
    fun the_stream_covers_shown_projects_and_events_coalesce() = runTest {
        val api = api()
        val events = FakeEvents()
        val boards = boards(api, events)
        boards.setInterest(pane, mapOf(key to setOf(1L)))
        runCurrent()
        assertEquals(listOf("c1" to setOf(2L)), events.watches)
        events.emit(LunicleStreamMessage.Status("c1", LunicleStreamMessage.StreamState.CONNECTED))
        runCurrent()
        val readsAfterConnect = api.boardReads()
        assertTrue(boards.boardsFlow.value[key]!!.live)
        // Live: polling pauses.
        advanceTimeBy(45_000); runCurrent()
        assertEquals(readsAfterConnect, api.boardReads())
        // A burst of three events (one of them our own) is one read.
        api.answer(LunicleMethod.GET, "/api/v1/projects/2/board", 200, boardJson(Triple(1, "Lönerapporter", 9), Triple(2, "Other", 1)))
        events.emit(LunicleStreamMessage.Event("c1", "issue.updated", projectId = 2, issueId = 1, actor = "Linus"))
        events.emit(LunicleStreamMessage.Event("c1", "comment.added", projectId = 2, issueId = 1, actor = "Linus"))
        events.emit(LunicleStreamMessage.Event("c1", "issue.updated", projectId = 2, issueId = 2, actor = "Me", self = true))
        events.emit(LunicleStreamMessage.Event("c1", "issue.updated", projectId = 99, issueId = 5, actor = "Elsewhere"))
        runCurrent()
        assertEquals(readsAfterConnect, api.boardReads())
        advanceTimeBy(LunicleBoards.COALESCE_MS); runCurrent()
        assertEquals(readsAfterConnect + 1, api.boardReads())
        // The changed, unfolded issue was re-read; the notice names the actor.
        assertEquals(2, api.issueReads(1))
        assertEquals("Linus commented on Lönerapporter", boards.boardsFlow.value[key]!!.notice)
        // Our own event alone does nothing.
        events.emit(LunicleStreamMessage.Event("c1", "issue.updated", projectId = 2, issueId = 2, self = true))
        advanceTimeBy(1_000); runCurrent()
        assertEquals(readsAfterConnect + 1, api.boardReads())
        // reset re-reads every board of the connection.
        events.emit(LunicleStreamMessage.Event("c1", "reset"))
        advanceTimeBy(LunicleBoards.COALESCE_MS); runCurrent()
        assertEquals(readsAfterConnect + 2, api.boardReads())
        // Hidden: the stream closes.
        boards.clearInterest(pane)
        assertEquals("c1" to emptySet<Long>(), events.watches.last())
    }

    @Test
    fun polling_resumes_when_the_stream_drops_or_is_unsupported() = runTest {
        val api = api()
        val events = FakeEvents()
        val boards = boards(api, events)
        boards.setInterest(pane, mapOf(key to emptySet()))
        runCurrent()
        events.emit(LunicleStreamMessage.Status("c1", LunicleStreamMessage.StreamState.CONNECTED))
        runCurrent()
        val reads = api.boardReads()
        events.emit(LunicleStreamMessage.Status("c1", LunicleStreamMessage.StreamState.DISCONNECTED))
        assertEquals(false, boards.boardsFlow.value[key]!!.live)
        advanceTimeBy(15_000); runCurrent()
        assertEquals(reads + 1, api.boardReads())
        events.emit(LunicleStreamMessage.Status("c1", LunicleStreamMessage.StreamState.UNSUPPORTED))
        advanceTimeBy(15_000); runCurrent()
        assertEquals(reads + 2, api.boardReads())
    }

    @Test
    fun window_focus_rereads_shown_boards() = runTest {
        val api = api()
        val boards = boards(api)
        boards.setInterest(pane, mapOf(key to emptySet()))
        runCurrent()
        val reads = api.boardReads()
        boards.refreshShown()
        runCurrent()
        assertEquals(reads + 1, api.boardReads())
    }
}
