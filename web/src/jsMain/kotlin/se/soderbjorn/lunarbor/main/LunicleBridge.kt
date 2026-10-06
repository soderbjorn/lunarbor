/* LunicleBridge.kt (jsMain)
 *
 * The renderer's side of the Lunicle relay (LBR-26): [ElectronLunicleBridge]
 * implements the commonMain ports [LunicleApi] and [LunicleConnectionStore]
 * over the preload's `noteApi.lunicleRequest` / `getLunicle` /
 * `addLunicleConnection` / `updateLunicleConnection` /
 * `removeLunicleConnection` (electron-main `LunicleHost.kt`), and
 * [LunicleEventSource] (LBR-27) over `noteApi.lunicleWatch` /
 * `onLunicleEvent` — the change streams the main process holds.
 *
 * Every Lunicle request goes through the Electron main process: Lunicle
 * sends no CORS headers, so a `fetch` from here would be refused, and the
 * main process holds the tokens — this side only ever sees names, URLs and
 * a `hasToken` flag.
 *
 * Platform glue only: JSON in and out, no rules. `JsAppGraph` builds the
 * `LunicleService` over it when [lunicleBridge] is present (Electron); the
 * browser demo has no bridge and so no Lunicle. */
package se.soderbjorn.lunarbor.main

import kotlinx.coroutines.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import se.soderbjorn.lunarbor.lunicle.LunicleApi
import se.soderbjorn.lunarbor.lunicle.LunicleConnection
import se.soderbjorn.lunarbor.lunicle.LunicleConnectionStore
import se.soderbjorn.lunarbor.lunicle.LunicleConnectionsSnapshot
import se.soderbjorn.lunarbor.lunicle.LunicleEventSource
import se.soderbjorn.lunarbor.lunicle.LunicleStreamMessage
import se.soderbjorn.lunarbor.lunicle.LunicleHttpResponse
import se.soderbjorn.lunarbor.lunicle.LunicleMethod
import kotlin.js.Promise

/** `noteApi` when it carries the Lunicle relay (Electron), else `null`. */
internal fun lunicleBridge(): dynamic {
    val api = js("globalThis.noteApi")
    if (api == null || js("typeof api.lunicleRequest !== 'function'") as Boolean) return null
    return api
}

/**
 * [LunicleApi] + [LunicleConnectionStore] + [LunicleEventSource] over the
 * Electron preload.
 *
 * Constructed by `JsAppGraph.provideLunicleService` (requests, connections)
 * and `JsAppGraph.provideLunicleBoards` (change streams) only when
 * [lunicleBridge] is non-null.
 *
 * @param bridge The preload's `noteApi` ([lunicleBridge]).
 */
internal class ElectronLunicleBridge(private val bridge: dynamic) : LunicleApi, LunicleConnectionStore, LunicleEventSource {

    private var listener: ((LunicleStreamMessage) -> Unit)? = null
    private var subscribed = false

    /** See [LunicleEventSource.watch]: `noteApi.lunicleWatch({ connectionId, projectIds })`. */
    override fun watch(connectionId: String, projectIds: Set<Long>) {
        if (jsTypeOf(bridge.lunicleWatch) != "function") return
        val spec: dynamic = js("({})")
        spec.connectionId = connectionId
        spec.projectIds = projectIds.map { it.toDouble() }.toTypedArray()
        try {
            bridge.lunicleWatch(spec)
        } catch (e: Throwable) {
            console.error("[lunarbor] lunicleWatch failed", e)
        }
    }

    /** See [LunicleEventSource.setListener]: `noteApi.onLunicleEvent`, subscribed once. */
    override fun setListener(listener: (LunicleStreamMessage) -> Unit) {
        this.listener = listener
        if (subscribed || jsTypeOf(bridge.onLunicleEvent) != "function") return
        subscribed = true
        bridge.onLunicleEvent { payload: dynamic ->
            val json = try {
                Json.parseToJsonElement(js("JSON.stringify")(payload) as String)
            } catch (_: Throwable) {
                null
            }
            LunicleStreamMessage.parse(json)?.let { this.listener?.invoke(it) }
        }
    }

    override suspend fun request(
        connectionId: String,
        method: LunicleMethod,
        path: String,
        query: Map<String, String>,
        body: JsonObject?,
    ): LunicleHttpResponse {
        val spec: dynamic = js("({})")
        spec.connectionId = connectionId
        spec.method = method.name
        spec.path = path
        val q: dynamic = js("({})")
        for ((k, v) in query) q[k] = v
        spec.query = q
        spec.body = body?.toString()
        val answer: dynamic = try {
            (bridge.lunicleRequest(spec) as Promise<dynamic>).await()
        } catch (e: Throwable) {
            return LunicleHttpResponse.TransportError("Lunarbor could not send the request (${e.message}).")
        }
        (answer?.transportError as? String)?.let { return LunicleHttpResponse.TransportError(it) }
        val status = (answer?.status as? Number)?.toInt()
            ?: return LunicleHttpResponse.TransportError("Lunarbor got no answer from its relay.")
        val raw: dynamic = answer.json
        val json = if (raw == null) null else try {
            Json.parseToJsonElement(js("JSON.stringify")(raw) as String)
        } catch (_: Throwable) {
            null
        }
        return LunicleHttpResponse.Answer(status, json)
    }

    override suspend fun list(): LunicleConnectionsSnapshot = snapshotOf(call { bridge.getLunicle() })

    override suspend fun add(name: String?, baseUrl: String?, token: String?): LunicleConnectionsSnapshot {
        val spec: dynamic = js("({})")
        if (name != null) spec.name = name
        if (baseUrl != null) spec.baseUrl = baseUrl
        if (token != null) spec.token = token
        return snapshotOf(call { bridge.addLunicleConnection(spec) })
    }

    override suspend fun update(id: String, name: String?, baseUrl: String?, token: String?): LunicleConnectionsSnapshot {
        val patch: dynamic = js("({})")
        patch.id = id
        if (name != null) patch.name = name
        if (baseUrl != null) patch.baseUrl = baseUrl
        if (token != null) patch.token = token
        return snapshotOf(call { bridge.updateLunicleConnection(patch) })
    }

    override suspend fun remove(id: String): LunicleConnectionsSnapshot =
        snapshotOf(call { bridge.removeLunicleConnection(id) })

    private suspend fun call(block: () -> dynamic): dynamic = try {
        (block() as Promise<dynamic>).await()
    } catch (e: Throwable) {
        val r: dynamic = js("({})")
        r.error = "Lunarbor could not reach its settings (${e.message})."
        r
    }

    /** The main process's `{ error, connections }` as a snapshot. */
    private fun snapshotOf(d: dynamic): LunicleConnectionsSnapshot {
        val list: dynamic = d?.connections
        val out = ArrayList<LunicleConnection>()
        if (list != null && list != undefined) {
            for (i in 0 until (list.length as Int)) {
                val c: dynamic = list[i]
                out += LunicleConnection(
                    id = c.id as String,
                    name = (c.name as String?).orEmpty(),
                    baseUrl = (c.baseUrl as String?).orEmpty(),
                    hasToken = c.hasToken == true,
                    tokenHint = (c.tokenHint as String?).orEmpty(),
                )
            }
        }
        return LunicleConnectionsSnapshot(out, d?.error as String?)
    }
}
