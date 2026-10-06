/*
 * LunicleApi.kt (commonMain)
 * --------------------------
 * The ports the Lunicle client needs from its platform (LBR-26), as plain
 * interfaces like `NewsFetcher`, so [LunicleClient] and [LunicleService]
 * stay annotation-free and testable against fakes:
 *
 *  - [LunicleApi]: one HTTP request to a connection's Lunicle REST API.
 *    On the desktop it is relayed by the Electron main process
 *    (`lunarbor:lunicleRequest`, `electron-main/.../LunicleHost.kt`): Lunicle
 *    sends no CORS headers, so the renderer can never `fetch` it, and the
 *    main process adds the token, which the renderer never holds.
 *  - [LunicleConnectionStore]: the named connections (App settings →
 *    Lunicle), as the renderer may see them — names, base URLs and a
 *    `hasToken` flag, never a token.
 *
 * The web implementation is `web/.../main/LunicleBridge.kt`; the browser
 * demo has none (no Lunicle at all).
 *
 * commonMain only — no platform imports.
 */

package se.soderbjorn.lunarbor.lunicle

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** HTTP methods the relay accepts. */
enum class LunicleMethod { GET, POST, PATCH, PUT, DELETE }

/**
 * What became of one request ([LunicleApi.request]).
 */
sealed interface LunicleHttpResponse {
    /**
     * An HTTP answer, whatever its status.
     *
     * @property status The HTTP status code.
     * @property json The parsed body, or `null` when it was empty or not JSON.
     */
    data class Answer(val status: Int, val json: JsonElement?) : LunicleHttpResponse

    /**
     * No HTTP answer: the host could not be reached, a timeout (connect 8 s,
     * request 20 s), a refused path, or a connection that no longer exists.
     *
     * @property message A sentence for the user.
     */
    data class TransportError(val message: String) : LunicleHttpResponse
}

/**
 * Sends requests to a connection's Lunicle REST API.
 *
 * Implemented by `ElectronLunicleBridge` (IPC to the main process); tests
 * use a fake answering canned JSON.
 */
fun interface LunicleApi {
    /**
     * Sends one request and never throws for network trouble.
     *
     * @param connectionId [LunicleConnection.id] — the main process looks the
     *   token and base URL up by it.
     * @param method The HTTP method.
     * @param path The full API path, starting `/api/v1/` (anything else is
     *   refused by the relay); ids appear here, never in [body].
     * @param query Query parameters (snake_case names, string values).
     * @param body The JSON body, or `null` for none.
     * @return The answer or the transport error.
     */
    suspend fun request(
        connectionId: String,
        method: LunicleMethod,
        path: String,
        query: Map<String, String>,
        body: JsonObject?,
    ): LunicleHttpResponse
}

/**
 * One named Lunicle instance, as the renderer sees it.
 *
 * @property id Stable id the main process keys it by.
 * @property name The slug a board node uses, `{{lunicle: <name>/<KEY>}}`;
 *   unique among connections, ignoring case.
 * @property baseUrl E.g. `https://issues.lunicle.dev`, without a trailing slash.
 * @property hasToken Whether a personal access token is stored for it.
 * @property tokenHint The token's display prefix (`lnl_pat_3f9a1c`, as
 *   Lunicle's own token list shows it), or `""` without a token.
 */
data class LunicleConnection(
    val id: String,
    val name: String,
    val baseUrl: String,
    val hasToken: Boolean,
    val tokenHint: String = "",
)

/**
 * The connections after a read or a change.
 *
 * @property connections Every connection, in the order they were added.
 * @property error Why the change was refused (a taken name, a bad URL or
 *   token), or `null`.
 */
data class LunicleConnectionsSnapshot(
    val connections: List<LunicleConnection>,
    val error: String? = null,
)

/**
 * Reads and changes the stored connections. Tokens go in ([add] / [update])
 * and never come back out.
 *
 * Implemented by `ElectronLunicleBridge`; tests use an in-memory one.
 */
interface LunicleConnectionStore {
    /** @return the stored connections. */
    suspend fun list(): LunicleConnectionsSnapshot

    /**
     * Adds a connection. A blank [name] gets a free default (`lunicle`, `lunicle-2`, …).
     *
     * @param baseUrl `null` for the default, `https://issues.lunicle.dev`.
     * @param token `null` or blank for none yet.
     */
    suspend fun add(name: String?, baseUrl: String?, token: String?): LunicleConnectionsSnapshot

    /**
     * Changes the given fields of connection [id]; `null` leaves one alone,
     * and an empty [token] removes the token.
     */
    suspend fun update(id: String, name: String? = null, baseUrl: String? = null, token: String? = null): LunicleConnectionsSnapshot

    /** Removes connection [id]. */
    suspend fun remove(id: String): LunicleConnectionsSnapshot
}
