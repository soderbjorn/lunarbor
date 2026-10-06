/* LunicleServiceTest.kt (commonTest)
 *
 * Pins [LunicleService]'s resolution of a board node's
 * `<connection>/<KEY>` (LBR-26): connections by name ignoring case, the
 * only connection when no name is given, `keyPrefix` matched ignoring case
 * and cached per connection (refetched once for an unknown key, dropped
 * when the connection changes), and the missing-connection, missing-token
 * and missing-project errors. */
package se.soderbjorn.lunarbor.lunicle

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** An in-memory [LunicleConnectionStore]. */
private class FakeStore(var connections: List<LunicleConnection>) : LunicleConnectionStore {
    var nextId = 100
    override suspend fun list() = LunicleConnectionsSnapshot(connections)
    override suspend fun add(name: String?, baseUrl: String?, token: String?): LunicleConnectionsSnapshot {
        connections = connections + LunicleConnection("c${nextId++}", name ?: "lunicle", baseUrl ?: "https://issues.lunicle.dev", !token.isNullOrBlank())
        return list()
    }
    override suspend fun update(id: String, name: String?, baseUrl: String?, token: String?): LunicleConnectionsSnapshot {
        connections = connections.map {
            if (it.id != id) it else it.copy(name = name ?: it.name, baseUrl = baseUrl ?: it.baseUrl, hasToken = token?.isNotEmpty() ?: it.hasToken)
        }
        return list()
    }
    override suspend fun remove(id: String): LunicleConnectionsSnapshot {
        connections = connections.filter { it.id != id }
        return list()
    }
}

class LunicleServiceTest {

    private val work = LunicleConnection("c1", "work", "https://issues.lunicle.dev", hasToken = true)
    private val home = LunicleConnection("c2", "Home", "http://localhost:8080", hasToken = true)

    private fun apiWithProjects(): FakeLunicleApi = FakeLunicleApi().apply {
        answer(
            LunicleMethod.GET, "/api/v1/projects", 200,
            """[{ "id": 2, "name": "Framnafolk", "keyPrefix": "FRA", "yourRole": "admin" }, { "id": 5, "name": "Lunarbor", "keyPrefix": "LBR" }]""",
        )
    }

    @Test
    fun connection_by_name_ignoring_case() = runTest {
        val service = LunicleService(FakeLunicleApi(), FakeStore(listOf(work, home)))
        assertEquals(work, service.resolveConnection("WORK").valueOrNull())
        assertEquals(home, service.resolveConnection("home").valueOrNull())
        assertEquals(LunicleError.NoConnection("other", 2), service.resolveConnection("other").errorOrNull())
        // No name with two connections: ambiguous.
        assertEquals(LunicleError.NoConnection(null, 2), service.resolveConnection(null).errorOrNull())
    }

    @Test
    fun no_name_uses_the_only_connection() = runTest {
        assertEquals(work, LunicleService(FakeLunicleApi(), FakeStore(listOf(work))).resolveConnection(null).valueOrNull())
        assertEquals(work, LunicleService(FakeLunicleApi(), FakeStore(listOf(work))).resolveConnection(" ").valueOrNull())
        assertEquals(LunicleError.NoConnection(null, 0), LunicleService(FakeLunicleApi(), FakeStore(emptyList())).resolveConnection(null).errorOrNull())
    }

    @Test
    fun key_prefix_resolves_ignoring_case_and_is_cached() = runTest {
        val api = apiWithProjects()
        val service = LunicleService(api, FakeStore(listOf(work, home)))
        val target = service.resolveProject("work", "fra").valueOrNull()!!
        assertEquals(work, target.connection)
        assertEquals(LunicleProject(2, "Framnafolk", "FRA", "admin"), target.project)
        assertEquals(5L, service.resolveProject("Work", "LBR").valueOrNull()?.project?.id)
        // One fetch served both.
        assertEquals(1, api.sent.size)
        // Another connection has its own cache.
        service.resolveProject("home", "FRA")
        assertEquals(listOf("c1", "c2"), api.sent.map { it.first })
    }

    @Test
    fun unknown_key_refetches_once_then_fails() = runTest {
        val api = apiWithProjects()
        val service = LunicleService(api, FakeStore(listOf(work)))
        service.resolveProject(null, "FRA")
        assertEquals(LunicleError.NoProject("NOPE", "work"), service.resolveProject(null, "NOPE").errorOrNull())
        assertEquals(2, api.sent.size)

        // A project made since: the refetch finds it.
        api.answer(LunicleMethod.GET, "/api/v1/projects", 200, """[{ "id": 9, "name": "New", "keyPrefix": "NEW" }]""")
        assertEquals(9L, service.resolveProject(null, "new").valueOrNull()?.project?.id)
    }

    @Test
    fun changing_a_connection_drops_its_cache() = runTest {
        val api = apiWithProjects()
        val service = LunicleService(api, FakeStore(listOf(work)))
        service.resolveProject("work", "FRA")
        service.updateConnection("c1", token = "lnl_pat_new")
        service.resolveProject("work", "FRA")
        assertEquals(2, api.sent.size)
    }

    @Test
    fun missing_token_and_failed_listing() = runTest {
        val noToken = work.copy(hasToken = false)
        val service = LunicleService(apiWithProjects(), FakeStore(listOf(noToken)))
        assertEquals(LunicleError.NoToken("work"), service.resolveProject("work", "FRA").errorOrNull())
        assertEquals(LunicleError.NoToken("work"), service.testConnection("c1").errorOrNull())

        val failing = FakeLunicleApi().apply {
            answer(LunicleMethod.GET, "/api/v1/projects", 401, """{ "error": "invalid_token", "message": "This token is not valid." }""")
        }
        val error = LunicleService(failing, FakeStore(listOf(work))).resolveProject("work", "FRA").errorOrNull()
        assertIs<LunicleError.Http>(error)
        assertEquals("This token is not valid.", error.message)
    }

    @Test
    fun connections_flow_follows_changes() = runTest {
        val service = LunicleService(FakeLunicleApi(), FakeStore(listOf(work)))
        assertEquals(emptyList(), service.connectionsFlow.value)
        service.refreshConnections()
        assertEquals(listOf(work), service.connectionsFlow.value)
        service.addConnection("home", "http://localhost:8080", null)
        assertEquals(listOf("work", "home"), service.connectionsFlow.value.map { it.name })
        service.removeConnection("c1")
        assertEquals(listOf("home"), service.connectionsFlow.value.map { it.name })
    }
}
