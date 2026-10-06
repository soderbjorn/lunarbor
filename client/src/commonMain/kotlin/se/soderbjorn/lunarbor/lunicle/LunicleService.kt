/*
 * LunicleService.kt (commonMain)
 * ------------------------------
 * The app-scoped entry point to Lunicle (LBR-26): the named connections,
 * one [LunicleClient] per connection, and the resolution of a board node's
 * `<connection>/<KEY>` to a project.
 *
 *  - **Connections** ([connectionsFlow]) come from the
 *    [LunicleConnectionStore] — the Electron main process's
 *    `lunarbor-lunicle.json`. Changes go through [addConnection] /
 *    [updateConnection] / [removeConnection], so the flow and the caches
 *    stay in step with what is stored.
 *  - **Resolution** ([resolveConnection], [resolveProject]): a connection by
 *    name, ignoring case — or, with no name, the only connection when there
 *    is exactly one; a project by its `keyPrefix`, ignoring case, from
 *    `projects()`, cached per connection. A key not in the cache refetches
 *    the list once (the project may be new); a change to the connection drops
 *    its cache.
 *  - **Read-only tokens** ([isReadOnly], LBR-29): `GET /me`'s token scope,
 *    asked once per connection, so a board knows its titles are not
 *    editable before a write is refused.
 *
 * Provided once by `JsAppGraph` (`@Provides @SingleIn(AppScope::class)`) in
 * the desktop app; the browser demo provides none, so a `{{lunicle: …}}`
 * bullet stays plain text there.
 *
 * commonMain only — no platform imports, no DI annotations.
 */

package se.soderbjorn.lunarbor.lunicle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A board node's target, resolved.
 *
 * @property connection The connection it goes through.
 * @property project The project its key names.
 */
data class LunicleBoardTarget(val connection: LunicleConnection, val project: LunicleProject)

/**
 * Lunicle connections, clients and key resolution, for the whole app.
 *
 * ### Callers
 * - App settings → Lunicle (`web/.../main/LunicleSettings.kt`): the
 *   connection list, add / update / remove, and Test ([testConnection]).
 * - The board node (LBR-27 onward): [resolveProject], then [client].
 *
 * @param api Sends the requests (the main-process relay on the desktop).
 * @param store Reads and changes the stored connections.
 */
class LunicleService(
    private val api: LunicleApi,
    private val store: LunicleConnectionStore,
) {
    private val _connections = MutableStateFlow<List<LunicleConnection>>(emptyList())

    /** Every connection, as last read or changed. Empty until [refreshConnections] first runs. */
    val connectionsFlow: StateFlow<List<LunicleConnection>> = _connections.asStateFlow()

    private var loaded = false
    private val projectsMutex = Mutex()

    /** Connection id → its projects, as last fetched. */
    private val projectCache = HashMap<String, List<LunicleProject>>()

    /** Connection id → whether its token is read-only, once `GET /me` answered (LBR-29). */
    private val readOnlyCache = HashMap<String, Boolean>()

    /** Connection id → the token owner's name, from the same `/me` ([userName]). */
    private val userNameCache = HashMap<String, String>()

    /**
     * Re-reads the connections from the store.
     *
     * @return The snapshot read.
     */
    suspend fun refreshConnections(): LunicleConnectionsSnapshot = adopt(store.list())

    /** Adds a connection; see [LunicleConnectionStore.add]. */
    suspend fun addConnection(name: String? = null, baseUrl: String? = null, token: String? = null): LunicleConnectionsSnapshot =
        adopt(store.add(name, baseUrl, token))

    /**
     * Changes connection [id] (see [LunicleConnectionStore.update]) and drops
     * its project cache, since its URL or token may now reach other projects.
     */
    suspend fun updateConnection(id: String, name: String? = null, baseUrl: String? = null, token: String? = null): LunicleConnectionsSnapshot {
        projectsMutex.withLock {
            projectCache.remove(id)
            readOnlyCache.remove(id)
            userNameCache.remove(id)
        }
        return adopt(store.update(id, name, baseUrl, token))
    }

    /** Removes connection [id] and its project cache. */
    suspend fun removeConnection(id: String): LunicleConnectionsSnapshot {
        projectsMutex.withLock { projectCache.remove(id) }
        return adopt(store.remove(id))
    }

    /**
     * Whether connection [connectionId]'s token is read-only (LBR-29):
     * `GET /me`'s token scope, asked once per connection and cached until
     * the connection changes. `false` when `/me` cannot be read (the
     * board's writes then find out with a 403, [markReadOnly]). Called by [LunicleBoards]
     * after a board's first good read.
     */
    suspend fun isReadOnly(connectionId: String): Boolean {
        projectsMutex.withLock { readOnlyCache[connectionId] }?.let { return it }
        val me = client(connectionId).me().valueOrNull()
        // Only an explicit `read` scope counts: an answer without one says
        // nothing. A failed `/me` is remembered as "not known to be
        // read-only" too, so it is not asked on every read.
        val readOnly = me?.tokenScope == "read"
        projectsMutex.withLock {
            readOnlyCache[connectionId] = readOnly
            me?.userName?.takeIf { it.isNotBlank() }?.let { userNameCache[connectionId] = it }
        }
        return readOnly
    }

    /**
     * The token owner's name on connection [connectionId], as `GET /me`
     * gave it when [isReadOnly] asked, or `null` (not asked yet, or it could
     * not be read). The author a comment being posted shows (LBR-31).
     */
    suspend fun userName(connectionId: String): String? = projectsMutex.withLock { userNameCache[connectionId] }

    /** Remembers that [connectionId]'s token is read-only (a write answered 403 `insufficient_scope`). */
    suspend fun markReadOnly(connectionId: String) {
        projectsMutex.withLock { readOnlyCache[connectionId] = true }
    }

    /** A client for connection [connectionId] (cheap; not cached). */
    fun client(connectionId: String): LunicleClient = LunicleClient(api, connectionId)

    /**
     * `GET /me` for connection [connectionId]: who its token belongs to.
     * Called by the settings dialog's Test button.
     */
    suspend fun testConnection(connectionId: String): LunicleResult<LunicleMe> {
        val c = connections().firstOrNull { it.id == connectionId }
            ?: return LunicleResult.Failure(LunicleError.NoConnection(null, connectionsFlow.value.size))
        if (!c.hasToken) return LunicleResult.Failure(LunicleError.NoToken(c.name))
        return client(c.id).me()
    }

    /**
     * The connection [name] names, ignoring case; with [name] `null` or
     * blank, the only connection when exactly one exists.
     */
    suspend fun resolveConnection(name: String?): LunicleResult<LunicleConnection> {
        val all = connections()
        val wanted = name?.trim()?.takeIf { it.isNotEmpty() }
        val found = if (wanted == null) all.singleOrNull() else all.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
        return if (found != null) LunicleResult.Ok(found) else LunicleResult.Failure(LunicleError.NoConnection(wanted, all.size))
    }

    /**
     * Resolves a board node's `<connection>/<KEY>`: the connection
     * ([resolveConnection]), then the project whose `keyPrefix` is [key],
     * ignoring case. The project list is cached per connection and refetched
     * once when [key] is not in it.
     *
     * @param connectionName The connection part, or `null` when the node gave none.
     * @param key The project's key prefix, e.g. `FRA`.
     */
    suspend fun resolveProject(connectionName: String?, key: String): LunicleResult<LunicleBoardTarget> {
        val connection = when (val r = resolveConnection(connectionName)) {
            is LunicleResult.Ok -> r.value
            is LunicleResult.Failure -> return r
        }
        if (!connection.hasToken) return LunicleResult.Failure(LunicleError.NoToken(connection.name))
        val wanted = key.trim()
        fun find(list: List<LunicleProject>) = list.firstOrNull { it.keyPrefix.equals(wanted, ignoreCase = true) }

        projectsMutex.withLock { projectCache[connection.id] }?.let { cached ->
            find(cached)?.let { return LunicleResult.Ok(LunicleBoardTarget(connection, it)) }
        }
        val fresh = when (val r = client(connection.id).projects()) {
            is LunicleResult.Ok -> r.value
            is LunicleResult.Failure -> return r
        }
        projectsMutex.withLock { projectCache[connection.id] = fresh }
        return find(fresh)?.let { LunicleResult.Ok(LunicleBoardTarget(connection, it)) }
            ?: LunicleResult.Failure(LunicleError.NoProject(wanted, connection.name))
    }

    /** The connections, read from the store on first use. */
    private suspend fun connections(): List<LunicleConnection> {
        if (!loaded) refreshConnections()
        return _connections.value
    }

    /** Takes [snapshot] as the current connections and drops caches of connections that are gone. */
    private suspend fun adopt(snapshot: LunicleConnectionsSnapshot): LunicleConnectionsSnapshot {
        loaded = true
        _connections.value = snapshot.connections
        val ids = snapshot.connections.map { it.id }.toSet()
        projectsMutex.withLock {
            projectCache.keys.retainAll(ids)
            readOnlyCache.keys.retainAll(ids)
            userNameCache.keys.retainAll(ids)
        }
        return snapshot
    }
}
