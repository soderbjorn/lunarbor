/* McpHttpServer.kt — the MCP endpoint, in the Electron main process.
 *
 * App settings → Agent access (a dialog) turns it on. It is the transport only:
 * an HTTP server (Node's `http`) answering MCP's Streamable HTTP POSTs on
 * `http://127.0.0.1:<port>/mcp`. Each request body goes to the renderer
 * over `lunarbor:mcpRequest`; the renderer answers it against the live
 * vault (`McpServer` in commonMain, via `web/.../McpBridge.kt`) and sends the
 * response back over `lunarbor:mcpResponse`. Stateless: no sessions, no
 * server-sent events (GET is 405, which clients accept).
 *
 * Connections: the settings hold any number of them, each with its own
 * name, key, privacy scope and edits switch. The key a request carries
 * picks its connection ([connectionFor]); the request goes to the renderer
 * with that connection's privacy scope — a privacy mode's id, or "" for
 * "No privacy" (LBR-10) — and edits switch, and the tools leave out what
 * that mode hides (`McpTools`). So one agent can see everything and
 * another nothing tagged #private, on the same server and port. The
 * renderer owns the modes (they live in the vault); this process only
 * stores the id.
 *
 * Security — nothing gets in without a key:
 *  - Bound to 127.0.0.1, so other computers cannot connect at all.
 *  - Every request must carry `Authorization: Bearer <key>` for one of the
 *    connections; each key is 256 random bits, compared in constant time.
 *    Other programs on this Mac cannot use the server without one.
 *  - Requests from web pages are refused: a request with an `Origin` header
 *    that is not a loopback origin, or a `Host` that is not 127.0.0.1 /
 *    localhost, is rejected (cross-site requests and DNS rebinding).
 *  - Bodies over 4 MB are refused.
 *  - The settings — on/off, port, connections with their keys — live in
 *    their own file, `lunarbor-mcp.json`, written with mode 0600 (readable
 *    by this user only) and owned by this process: the renderer reads them
 *    through `lunarbor:getMcp` and changes them through `lunarbor:setMcp`
 *    and the connection handlers (`add` / `update` / `remove` /
 *    `newMcpKey`). Off by default; a new key invalidates every agent
 *    configured with the old one. Connections from before privacy scopes
 *    (folder-scoped ones, or a single `key`) are dropped when the file is
 *    read: they are set up again in the dialog.
 *
 * Main-process glue only; the protocol and the tools live in commonMain. */
package se.soderbjorn.lunarbor.electron

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise

private val nodeRequireMcp: dynamic = js("require")
private val httpModule: dynamic = nodeRequireMcp("http")
private val cryptoModule: dynamic = nodeRequireMcp("crypto")

/** Port the server listens on unless `lunarbor-mcp.json` says otherwise. */
internal const val MCP_DEFAULT_PORT: Int = 47321

/** Path of the MCP endpoint. */
internal const val MCP_PATH: String = "/mcp"

/** Largest request body accepted. */
private const val MCP_MAX_BODY_BYTES: Int = 4 * 1024 * 1024

/** How long a request waits for the renderer's answer. */
private const val MCP_REPLY_TIMEOUT_MS: Int = 120_000

/**
 * One way in: an agent set up with [key] sees the vault through [privacy].
 *
 * @property id Stable id the settings dialog addresses it by.
 * @property name The user's label, e.g. "Claude Code — work"; also the
 *   suggested MCP server name in the setup snippets.
 * @property key The secret its requests carry.
 * @property privacy Its privacy scope: the id of the privacy mode whose
 *   hidden content it may not see, or `""` for "No privacy". An id the
 *   vault no longer has turns the connection off (the renderer refuses
 *   its calls) until the user picks a scope again.
 * @property allowEdits Whether it may change the vault (otherwise it can
 *   only read and search; window tools work either way).
 */
internal data class McpConnection(
    val id: String,
    val name: String,
    val key: String,
    val privacy: String = "",
    val allowEdits: Boolean = true,
)

/**
 * The persisted Agent access settings.
 *
 * @property enabled Whether the server runs.
 * @property port The loopback port.
 * @property connections Every connection; a request needs one's key.
 */
internal data class McpSettings(
    val enabled: Boolean = false,
    val port: Int = MCP_DEFAULT_PORT,
    val connections: List<McpConnection> = emptyList(),
)

/**
 * The connection whose key [authorization] (the request's `Authorization`
 * header) carries, or `null`. Every key is compared ([isAuthorized]), so the
 * time taken does not tell which one matched.
 */
internal fun connectionFor(authorization: String?, connections: List<McpConnection>): McpConnection? {
    var found: McpConnection? = null
    for (c in connections) if (isAuthorized(authorization, c.key) && found == null) found = c
    return found
}

/**
 * `true` when [authorization] (the request's `Authorization` header) is
 * `Bearer <key>` for [key]. Constant-time in the key's length, and never
 * true for an empty [key].
 */
internal fun isAuthorized(authorization: String?, key: String): Boolean {
    if (key.isEmpty() || authorization == null) return false
    val prefix = "Bearer "
    if (!authorization.startsWith(prefix)) return false
    val given = authorization.substring(prefix.length).trim()
    if (given.length != key.length) return false
    var diff = 0
    for (i in key.indices) diff = diff or (given[i].code xor key[i].code)
    return diff == 0
}

/**
 * `true` when a request's `Origin` / `Host` headers allow it: no Origin
 * (agents and command-line tools send none) or a loopback one, and a Host
 * naming this machine's loopback address. Refuses requests a web page
 * makes, including through DNS rebinding.
 */
internal fun isLocalRequest(origin: String?, host: String?): Boolean {
    fun loopbackHost(h: String): Boolean {
        val name = if (h.startsWith("[")) h.substringBefore(']') + "]" else h.substringBefore(':')
        return name == "127.0.0.1" || name == "localhost" || name == "[::1]"
    }
    if (host == null || !loopbackHost(host)) return false
    if (origin == null) return true
    val rest = origin.removePrefix("http://").removePrefix("https://")
    if (rest == origin) return false
    return loopbackHost(rest.substringBefore('/'))
}

/** Version of `lunarbor-mcp.json`'s connections; older connections are dropped on read. */
internal const val MCP_SETTINGS_FORMAT: Int = 2

/** The MCP endpoint URL for [port]. */
internal fun mcpUrl(port: Int): String = "http://127.0.0.1:$port$MCP_PATH"

/** Owner of the server, its settings and the request ↔ renderer relay. */
internal object McpHost {
    private var settingsPath: () -> String = { "" }
    private var window: () -> BrowserWindow? = { null }

    private var settings = McpSettings()
    private var server: dynamic = null
    private var listening = false
    private var lastError: String? = null

    /** WebContents id of the window whose renderer said it answers MCP requests. */
    private var readyContentsId: Int? = null

    private var nextRequestId = 1
    private val pending = HashMap<Int, (String?) -> Unit>()

    /**
     * Loads the settings and starts the server when it is on; registers the
     * `lunarbor:*Mcp*` IPC handlers. Called once by `main` (after the run
     * paths are resolved), before the first window.
     *
     * @param settingsFile Path of `lunarbor-mcp.json`.
     * @param currentWindow The app's window, if any (it is recreated on a
     *   vault switch).
     */
    fun install(settingsFile: () -> String, currentWindow: () -> BrowserWindow?) {
        settingsPath = settingsFile
        window = currentWindow
        settings = readSettings()
        registerIpc()
        if (settings.enabled) start()
    }

    /** Called when a new window is created: its renderer must say it is ready again. */
    fun onWindowCreated() {
        readyContentsId = null
        for (reply in pending.values.toList()) reply(null)
        pending.clear()
    }

    private fun registerIpc() {
        ipcMain.handle("lunarbor:getMcp") { _, _ -> status() }
        ipcMain.handle("lunarbor:setMcp") { _, patch ->
            GlobalScope.promise {
                var next = settings
                (patch.enabled as? Boolean)?.let { next = next.copy(enabled = it) }
                (patch.port as? Number)?.toInt()?.let { if (it in 1024..65535) next = next.copy(port = it) }
                // Turning it on for the first time: one connection to start with.
                if (next.enabled && next.connections.isEmpty()) {
                    next = next.copy(connections = listOf(McpConnection(newId(), "Lunarbor", newKey())))
                }
                apply(next)
                status()
            }
        }
        ipcMain.handle("lunarbor:addMcpConnection") { _, spec ->
            GlobalScope.promise {
                val privacy = (spec?.privacy as? String).orEmpty()
                val name = (spec?.name as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "Lunarbor"
                val c = McpConnection(newId(), name, newKey(), privacy, spec?.allowEdits != false)
                apply(settings.copy(connections = settings.connections + c))
                status()
            }
        }
        ipcMain.handle("lunarbor:updateMcpConnection") { _, patch ->
            GlobalScope.promise {
                val id = patch.id as? String
                apply(settings.copy(connections = settings.connections.map { c ->
                    if (c.id != id) return@map c
                    var n = c
                    (patch.name as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { n = n.copy(name = it) }
                    (patch.privacy as? String)?.let { n = n.copy(privacy = it) }
                    (patch.allowEdits as? Boolean)?.let { n = n.copy(allowEdits = it) }
                    n
                }))
                status()
            }
        }
        ipcMain.handle("lunarbor:removeMcpConnection") { _, id ->
            GlobalScope.promise {
                apply(settings.copy(connections = settings.connections.filter { it.id != id }))
                status()
            }
        }
        ipcMain.handle("lunarbor:newMcpKey") { _, id ->
            GlobalScope.promise {
                apply(settings.copy(connections = settings.connections.map { if (it.id == id) it.copy(key = newKey()) else it }))
                status()
            }
        }
        ipcMain.handle("lunarbor:mcpReady") { event, _ ->
            readyContentsId = event.sender.id as Int
            Unit
        }
        ipcMain.handle("lunarbor:mcpResponse") { _, reply ->
            val id = (reply.id as Number).toInt()
            pending.remove(id)?.invoke(reply.body as String?)
            Unit
        }
    }

    /** Stores [next] and starts, stops or restarts the server to match. */
    private fun apply(next: McpSettings) {
        val restart = next.enabled != settings.enabled || next.port != settings.port
        settings = next
        writeSettings(next)
        if (restart) {
            stop()
            if (next.enabled) start()
        }
    }

    /** What the Agent access dialog shows. */
    private fun status(): dynamic {
        val s: dynamic = js("({})")
        s.enabled = settings.enabled
        s.port = settings.port
        s.url = mcpUrl(settings.port)
        s.running = listening
        s.error = lastError
        s.connections = settings.connections.map { c ->
            val o: dynamic = js("({})")
            o.id = c.id
            o.name = c.name
            o.key = c.key
            o.privacy = c.privacy
            o.allowEdits = c.allowEdits
            o
        }.toTypedArray()
        return s
    }

    private fun start() {
        lastError = null
        val srv = httpModule.createServer { req: dynamic, res: dynamic -> handle(req, res) }
        srv.on("error") { err: dynamic ->
            listening = false
            lastError = if (err.code == "EADDRINUSE") "Port ${settings.port} is in use by another program." else (err.message as? String ?: "$err")
            console.error("[lunarbor] MCP server:", err)
        }
        srv.listen(settings.port, "127.0.0.1") {
            listening = true
            console.log("[lunarbor] MCP server listening on ${mcpUrl(settings.port)}")
        }
        server = srv
    }

    private fun stop() {
        server?.close()
        server = null
        listening = false
        lastError = null
        onWindowCreated()
    }

    private fun handle(req: dynamic, res: dynamic) {
        val headers: dynamic = req.headers
        if (!isLocalRequest(headers.origin as String?, headers.host as String?)) {
            return reply(res, 403, "text/plain", "Requests from web pages are not allowed.")
        }
        val connection = connectionFor(headers.authorization as String?, settings.connections)
        if (connection == null) {
            res.setHeader("WWW-Authenticate", "Bearer")
            return reply(res, 401, "text/plain", "Lunarbor needs a connection's key: Authorization: Bearer <key> (App settings → Agent access).")
        }
        val path = (req.url as String).substringBefore('?')
        if (path != MCP_PATH) return reply(res, 404, "text/plain", "Not found. The endpoint is $MCP_PATH.")
        if (req.method != "POST") {
            res.setHeader("Allow", "POST")
            return reply(res, 405, "text/plain", "Use POST.")
        }
        val chunks = js("[]")
        var size = 0
        var tooLarge = false
        req.on("data") { chunk: dynamic ->
            size += chunk.length as Int
            if (size > MCP_MAX_BODY_BYTES) {
                if (!tooLarge) reply(res, 413, "text/plain", "Request too large.")
                tooLarge = true
                req.destroy()
            } else chunks.push(chunk)
        }
        req.on("end") {
            if (tooLarge) return@on Unit
            val body = js("Buffer").concat(chunks).toString("utf8") as String
            forward(body, connection) { answer ->
                when {
                    answer == null -> reply(res, 503, "text/plain", "Lunarbor is not ready to answer (no window open, or it is starting).")
                    answer.isEmpty() -> { res.statusCode = 202; res.end() }
                    else -> reply(res, 200, "application/json", answer)
                }
            }
        }
    }

    /**
     * Sends [body] to the renderer, with [connection]'s edits switch and
     * privacy scope, and calls [done] with its answer: the JSON response, `""`
     * when nothing is to be sent back, or `null` when no renderer can
     * answer (or it took too long).
     */
    private fun forward(body: String, connection: McpConnection, done: (String?) -> Unit) {
        val w = window()
        if (w == null || w.isDestroyed() || readyContentsId == null || w.webContents.asDynamic().id != readyContentsId) {
            return done(null)
        }
        val id = nextRequestId++
        var finished = false
        val timer = js("setTimeout")({
            if (!finished) {
                finished = true
                pending.remove(id)
                done(null)
            }
        }, MCP_REPLY_TIMEOUT_MS)
        pending[id] = { answer ->
            if (!finished) {
                finished = true
                js("clearTimeout")(timer)
                done(answer ?: "")
            }
        }
        w.webContents.send("lunarbor:mcpRequest", id, body, connection.allowEdits, connection.privacy)
    }

    private fun reply(res: dynamic, status: Int, type: String, body: String) {
        if (res.headersSent == true) return
        res.statusCode = status
        res.setHeader("Content-Type", "$type; charset=utf-8")
        res.setHeader("Cache-Control", "no-store")
        res.end(body)
    }

    private fun newKey(): String = "tf_" + (cryptoModule.randomBytes(32).toString("hex") as String)

    private fun newId(): String = cryptoModule.randomBytes(6).toString("hex") as String

    private fun readSettings(): McpSettings {
        val text = try { fsSync.readFileSync(settingsPath(), "utf8") } catch (_: Throwable) { return McpSettings() }
        val obj: dynamic = try { js("JSON.parse")(text) } catch (_: Throwable) { return McpSettings() }
        val connections = ArrayList<McpConnection>()
        val list: dynamic = obj.connections
        // Connections from before privacy scopes (folder-scoped, or one
        // `key`) are dropped: they are set up again in the dialog.
        if ((obj.formatVersion as? Number)?.toInt() == MCP_SETTINGS_FORMAT && js("Array").isArray(list) as Boolean) {
            for (i in 0 until (list.length as Int)) {
                val c: dynamic = list[i]
                val key = (c.key as? String).orEmpty()
                if (key.isEmpty()) continue
                connections += McpConnection(
                    id = (c.id as? String)?.takeIf { it.isNotEmpty() } ?: newId(),
                    name = (c.name as? String)?.takeIf { it.isNotBlank() } ?: "Lunarbor",
                    key = key,
                    privacy = (c.privacy as? String).orEmpty(),
                    allowEdits = c.allowEdits != false,
                )
            }
        }
        return McpSettings(
            enabled = obj.enabled == true,
            port = (obj.port as? Number)?.toInt()?.takeIf { it in 1024..65535 } ?: MCP_DEFAULT_PORT,
            connections = connections,
        )
    }

    private fun writeSettings(s: McpSettings) {
        val obj: dynamic = js("({})")
        obj.formatVersion = MCP_SETTINGS_FORMAT
        obj.enabled = s.enabled
        obj.port = s.port
        obj.connections = s.connections.map { c ->
            val o: dynamic = js("({})")
            o.id = c.id
            o.name = c.name
            o.key = c.key
            o.privacy = c.privacy
            o.allowEdits = c.allowEdits
            o
        }.toTypedArray()
        val path = settingsPath()
        val tmp = "$path.tmp"
        val opts: dynamic = js("({})")
        opts.mode = 384 // 0o600: this user only — the file holds the key.
        try {
            fsSync.asDynamic().writeFileSync(tmp, js("JSON.stringify")(obj, null, 2), opts)
            fsSync.asDynamic().renameSync(tmp, path)
            fsSync.asDynamic().chmodSync(path, 384)
        } catch (e: Throwable) {
            console.error("[lunarbor] could not save MCP settings", e)
        }
    }
}
