/* LunicleHost.kt — Lunicle connections, in the Electron main process (LBR-26).
 *
 * App settings → Lunicle (a dialog, `web/.../main/LunicleSettings.kt`) lets
 * the user name any number of Lunicle instances, each with a base URL and a
 * personal access token (`lnl_pat_…`). This file owns them:
 *
 *  - **Storage.** `lunarbor-lunicle.json`, beside `lunarbor-mcp.json`,
 *    written with mode 0600 and a `formatVersion` ([LUNICLE_SETTINGS_FORMAT]).
 *    The renderer never sees a token once it has been entered: it gets each
 *    connection's id, name, base URL, `hasToken` and a short `tokenHint`
 *    (Lunicle's own display prefix, `lnl_pat_3f9a1c`), over
 *    `lunarbor:getLunicle`, and changes them over
 *    `lunarbor:addLunicleConnection` / `updateLunicleConnection` /
 *    `removeLunicleConnection`.
 *  - **The relay.** Lunicle sends no CORS headers, so the renderer cannot
 *    `fetch` it; and the token must stay here. `lunarbor:lunicleRequest`
 *    takes `{ connectionId, method, path, query, body }`, refuses any path
 *    outside `/api/v1/` ([isAllowedLuniclePath]), adds
 *    `Authorization: Bearer <token>` and answers `{ status, json }`, or
 *    `{ transportError }` when no HTTP answer came (connect timeout 8 s,
 *    whole request 20 s; redirects are never followed, so the token never
 *    travels to another host).
 *
 *  - **Change streams** (LBR-27, Lunicle's SSE from LNL-224): one
 *    [LunicleStream] per connection, `GET /api/v1/events?projects=…&origin=…`
 *    covering every project a board node on screen shows
 *    (`lunarbor:lunicleWatch({ connectionId, projectIds })`). Events go to
 *    the renderer as `lunarbor:lunicleEvent` (`{ connectionId, event, id,
 *    data }`), and the stream's state as `{ connectionId, status }`
 *    (`connected`, `disconnected`, `unsupported` on a 404). It resumes with
 *    `Last-Event-ID`, reconnects with backoff ([lunicleStreamBackoffMs]:
 *    5 s, 15 s, 60 s, then every 5 min) and treats 60 s without a byte
 *    (Lunicle pings every 25 s) as a dead stream.
 *  - **Own echo**: every write carries `X-Lunicle-Origin: <`[LUNICLE_ORIGIN]`>`
 *    and every stream asks with `?origin=` the same id, so events this
 *    app's own writes caused arrive with `"self": true` and the renderer
 *    ignores them.
 *
 * Shape: one [LunicleRemote] per connection holds its token, base URL,
 * request helper and change stream. [LunicleHost] keeps the settings file
 * and the IPC handlers.
 *
 * Validation lives here, at the trust boundary ([lunicleNameError],
 * [normalizeLunicleBaseUrl], [lunicleTokenError]), and is tested in
 * `LunicleHostTest`.
 *
 * Main-process glue only; the typed client lives in commonMain
 * (`client/.../lunicle/`). */
package se.soderbjorn.lunarbor.electron

import kotlin.js.Promise

private val nodeRequireLunicle: dynamic = js("require")
private val httpsModule: dynamic = nodeRequireLunicle("https")
private val httpModuleLunicle: dynamic = nodeRequireLunicle("http")
private val cryptoModuleLunicle: dynamic = nodeRequireLunicle("crypto")

/** Version of `lunarbor-lunicle.json`; a file with another version is read as empty. */
internal const val LUNICLE_SETTINGS_FORMAT: Int = 1

/** Lunicle's REST API prefix; [isAllowedLuniclePath] refuses everything else. */
internal const val LUNICLE_API_PREFIX: String = "/api/v1/"

/** Time allowed to open the TCP (+ TLS) connection. */
internal const val LUNICLE_CONNECT_TIMEOUT_MS: Int = 8_000

/** Time allowed for the whole request, connect included. */
internal const val LUNICLE_REQUEST_TIMEOUT_MS: Int = 20_000

/** Largest response body accepted (a big board is well under this). */
private const val LUNICLE_MAX_RESPONSE_BYTES: Int = 32 * 1024 * 1024

/** The prefix every Lunicle personal access token starts with. */
internal const val LUNICLE_TOKEN_PREFIX: String = "lnl_pat_"

/** Longest connection name. */
private const val LUNICLE_NAME_MAX: Int = 32

/**
 * This app run's id for Lunicle's own-echo rule (LNL-224): sent as
 * `X-Lunicle-Origin` on every write and as `?origin=` on every change
 * stream, so events our writes caused come back marked `self`. 24 hex
 * characters (Lunicle accepts 1–64 of `[A-Za-z0-9_-]`).
 */
internal val LUNICLE_ORIGIN: String by lazy { cryptoModuleLunicle.randomBytes(12).toString("hex") as String }

/** A stream silent this long (Lunicle pings every 25 s) is dead: reconnect. */
internal const val LUNICLE_STREAM_SILENCE_MS: Int = 60_000

/**
 * Pause before reconnect attempt [attempt] (0 = the first after a drop):
 * 5 s, 15 s, 60 s, then every 5 minutes.
 */
internal fun lunicleStreamBackoffMs(attempt: Int): Int = when {
    attempt <= 0 -> 5_000
    attempt == 1 -> 15_000
    attempt == 2 -> 60_000
    else -> 300_000
}

/**
 * The change stream's URL path and query for [projectIds]
 * (`/api/v1/events?projects=1,2,3&origin=<id>`).
 */
internal fun lunicleStreamPath(projectIds: Collection<Long>, origin: String): String =
    "/api/v1/events?projects=" + projectIds.sorted().joinToString(",") + "&origin=" + origin

/**
 * An incremental parser of `text/event-stream` (the WHATWG rules Lunicle
 * writes by): lines end in `\n`, `\r\n` or `\r`; a blank line dispatches
 * the event gathered so far; `:` lines are comments (Lunicle's `: ping`);
 * `data:` lines join with `\n`; one space after the colon is dropped.
 * Pure, tested in `LunicleHostTest`.
 *
 * @param onEvent Called per event with its `id` (or `null`), its name
 *   (`message` when none was given) and its data.
 * @param onComment Called per comment line (a sign of life).
 */
internal class SseParser(
    private val onEvent: (id: String?, event: String, data: String) -> Unit,
    private val onComment: () -> Unit = {},
) {
    private var pending = ""
    private var id: String? = null
    private var event = ""
    private val data = StringBuilder()
    private var hasData = false

    /** The id of the last event that carried one: what a reconnect resumes from. */
    var lastEventId: String? = null
        private set

    /** Takes the next piece of the stream, in any split. */
    fun feed(chunk: String) {
        pending += chunk
        while (true) {
            val nl = pending.indexOfFirst { it == '\n' || it == '\r' }
            if (nl < 0) break
            // A lone `\r` at the very end may be the first half of `\r\n`.
            if (pending[nl] == '\r' && nl == pending.length - 1) break
            val line = pending.substring(0, nl)
            val skip = if (pending[nl] == '\r' && pending.getOrNull(nl + 1) == '\n') 2 else 1
            pending = pending.substring(nl + skip)
            line(line)
        }
    }

    private fun line(line: String) {
        if (line.isEmpty()) {
            if (hasData) {
                val text = data.toString()
                id?.let { lastEventId = it }
                onEvent(id, event.ifEmpty { "message" }, text)
            }
            id = null
            event = ""
            data.clear()
            hasData = false
            return
        }
        if (line.startsWith(":")) {
            onComment()
            return
        }
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        var value = if (colon < 0) "" else line.substring(colon + 1)
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> event = value
            "data" -> {
                if (hasData) data.append('\n')
                data.append(value)
                hasData = true
            }
            "id" -> if ('\u0000' !in value) id = value
        }
    }
}

/**
 * One stored connection.
 *
 * @property id Stable id the renderer addresses it by (random hex).
 * @property name The slug a board node names it by, `{{lunicle: <name>/<KEY>}}`;
 *   unique among connections, ignoring case ([lunicleNameError]).
 * @property baseUrl The instance's origin (plus an optional path), without a
 *   trailing slash, e.g. `https://issues.lunicle.dev` ([normalizeLunicleBaseUrl]).
 * @property token The personal access token, or `""` when none was entered.
 */
internal data class LunicleConnectionRecord(
    val id: String,
    val name: String,
    val baseUrl: String,
    val token: String = "",
)

/**
 * Why [name] cannot name a connection, or `null` when it can: 1–32 letters,
 * digits, `-` and `_`, starting with a letter or digit, and not the name of
 * another connection than [selfId] (ignoring case).
 *
 * Called by the add / update handlers.
 */
internal fun lunicleNameError(name: String, others: List<LunicleConnectionRecord>, selfId: String?): String? {
    if (name.isEmpty()) return "Give the connection a name."
    if (name.length > LUNICLE_NAME_MAX) return "A name can be at most $LUNICLE_NAME_MAX characters."
    if (!Regex("^[A-Za-z0-9][A-Za-z0-9_-]*$").matches(name)) {
        return "A name can hold only letters, digits, - and _ (it is written in {{lunicle: $name/KEY}})."
    }
    if (others.any { it.id != selfId && it.name.equals(name, ignoreCase = true) }) {
        return "Another connection is already called “$name”."
    }
    return null
}

/**
 * [raw] as a base URL — trimmed, without trailing slashes — or `null` when it
 * is not one: `https://<host>[:port][/path]`, or `http://` only for
 * `localhost` / `127.0.0.1` (a local Lunicle). No query, fragment or
 * credentials.
 */
internal fun normalizeLunicleBaseUrl(raw: String): String? {
    val text = raw.trim().trimEnd('/')
    val m = Regex("^(https?)://([^/?#@\\s]+)(/[^?#\\s]*)?$", RegexOption.IGNORE_CASE).find(text) ?: return null
    val scheme = m.groupValues[1].lowercase()
    val hostPort = m.groupValues[2]
    val host = if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
    if (host.isEmpty()) return null
    val port = if (hostPort.startsWith("[")) hostPort.substringAfter(']', "").removePrefix(":") else hostPort.substringAfter(':', "")
    if (port.isNotEmpty() && (port.toIntOrNull() == null || port.toInt() !in 1..65535)) return null
    if (scheme == "http" && host.lowercase() != "localhost" && host != "127.0.0.1") return null
    return "$scheme://${hostPort.lowercase()}${m.groupValues[3]}"
}

/** Why [token] is not a Lunicle personal access token, or `null` when it looks like one. */
internal fun lunicleTokenError(token: String): String? = when {
    !token.startsWith(LUNICLE_TOKEN_PREFIX) || token.length <= LUNICLE_TOKEN_PREFIX.length ->
        "A Lunicle token starts with $LUNICLE_TOKEN_PREFIX (Lunicle → Settings → You → API access)."
    token.any { it.isWhitespace() || it.code < 0x21 || it.code > 0x7e } -> "The token holds characters a token cannot have."
    else -> null
}

/**
 * Whether [path] may be relayed: it starts with `/api/v1/`, holds only URL
 * path characters (no query, fragment or backslash), and no segment is `.`
 * or `..`, plain or percent-encoded.
 */
internal fun isAllowedLuniclePath(path: String): Boolean {
    if (!path.startsWith(LUNICLE_API_PREFIX)) return false
    if (!Regex("^[A-Za-z0-9._~!$&'()*+,;=:@%/-]+$").matches(path)) return false
    if (path.contains("//")) return false
    for (segment in path.split('/')) {
        val decoded = segment.replace("%2e", ".", ignoreCase = true)
        if (decoded == "." || decoded == "..") return false
        if (segment.contains("%2f", ignoreCase = true) || segment.contains("%5c", ignoreCase = true)) return false
    }
    return true
}

/** The token's display prefix (`lnl_pat_` + 6 characters), the part Lunicle itself shows in its token list. */
internal fun lunicleTokenHint(token: String): String =
    if (token.isEmpty()) "" else token.take(LUNICLE_TOKEN_PREFIX.length + 6)

/**
 * One connection's live side: its token, base URL and HTTP helper. Rebuilt
 * whenever the connection's settings change ([LunicleHost.apply]).
 *
 * LBR-27's change stream belongs here too: it needs exactly this
 * connection's base URL and token.
 *
 * @property record The stored connection.
 */
internal class LunicleRemote(val record: LunicleConnectionRecord) {

    /** This connection's change stream, made on the first [watch]. */
    private var stream: LunicleStream? = null

    /** Projects the stream covers (empty: none open). */
    val watchedProjects: Set<Long> get() = stream?.projects.orEmpty()

    /**
     * Makes the change stream cover exactly [projectIds] (empty closes it).
     *
     * @param send Delivers one message to the renderer.
     */
    fun watch(projectIds: Set<Long>, send: (dynamic) -> Unit) {
        val s = stream ?: LunicleStream(this, send).also { stream = it }
        s.setProjects(projectIds)
    }

    /** Closes the change stream for good (the connection changed or went). */
    fun closeStream() {
        stream?.close()
        stream = null
    }

    /**
     * Sends one request to `<baseUrl><path>?<query>` with the Bearer token.
     *
     * @param method `GET`, `POST`, `PATCH`, `PUT` or `DELETE`.
     * @param path An API path ([isAllowedLuniclePath]; the caller checked it).
     * @param query Query parameters, a JS object of strings, or `null`.
     * @param body The JSON body's text, or `null` for none.
     * @param headers Extra request headers (a JS object), or `null`.
     * @return A promise of `{ status, json }` (`json` is `null` when the body
     *   was empty or not JSON) or `{ transportError }`; it never rejects.
     */
    fun request(method: String, path: String, query: dynamic, body: String?, headers: dynamic = null): Promise<dynamic> =
        Promise { resolve, _ ->
            var settled = false
            fun finish(result: dynamic) {
                if (settled) return
                settled = true
                resolve(result)
            }
            fun fail(message: String) {
                val r: dynamic = js("({})")
                r.transportError = message
                finish(r)
            }
            val url = try {
                val full = record.baseUrl + path
                val urlCtor: dynamic = js("URL")
                val u: dynamic = js("new urlCtor(full)")
                if (query != null) {
                    val keys = js("Object.keys")(query).unsafeCast<Array<String>>()
                    for (k in keys) {
                        val v: dynamic = query[k]
                        if (v != null) u.searchParams.set(k, v.toString())
                    }
                }
                u
            } catch (e: Throwable) {
                return@Promise fail("The connection's base URL is not valid.")
            }
            val h: dynamic = js("({})")
            h["Authorization"] = "Bearer ${record.token}"
            h["Accept"] = "application/json"
            h["User-Agent"] = "Lunarbor/${app.getVersion()}"
            if (headers != null) {
                val keys = js("Object.keys")(headers).unsafeCast<Array<String>>()
                for (k in keys) h[k] = headers[k]
            }
            val payload: dynamic = if (body != null) js("Buffer").from(body, "utf8") else null
            if (payload != null) {
                h["Content-Type"] = "application/json; charset=utf-8"
                h["Content-Length"] = payload.length
            }
            val opts: dynamic = js("({})")
            opts.method = method
            opts.headers = h
            val module = if ((url.protocol as String) == "http:") httpModuleLunicle else httpsModule
            var req: dynamic = null
            req = module.request(url, opts) { res: dynamic ->
                val chunks = js("[]")
                var size = 0
                res.on("data") { chunk: dynamic ->
                    size += chunk.length as Int
                    if (size > LUNICLE_MAX_RESPONSE_BYTES) {
                        req.destroy()
                        fail("Lunicle's answer was too large.")
                    } else chunks.push(chunk)
                }
                res.on("end") {
                    val text = js("Buffer").concat(chunks).toString("utf8") as String
                    val r: dynamic = js("({})")
                    r.status = res.statusCode
                    r.json = if (text.isBlank()) null else try { js("JSON.parse")(text) } catch (_: Throwable) { null }
                    finish(r)
                }
                res.on("error") { err: dynamic -> fail(describeNetworkError(err)) }
            }
            val connectTimer = js("setTimeout")({
                fail("Could not reach ${record.baseUrl} (no connection within ${LUNICLE_CONNECT_TIMEOUT_MS / 1000} s).")
                req.destroy()
            }, LUNICLE_CONNECT_TIMEOUT_MS)
            val requestTimer = js("setTimeout")({
                fail("${record.baseUrl} did not answer within ${LUNICLE_REQUEST_TIMEOUT_MS / 1000} s.")
                req.destroy()
            }, LUNICLE_REQUEST_TIMEOUT_MS)
            req.on("socket") { socket: dynamic ->
                val connected = { js("clearTimeout")(connectTimer); Unit }
                if (socket.connecting == true) {
                    socket.once(if ((url.protocol as String) == "https:") "secureConnect" else "connect", connected)
                } else connected()
            }
            req.on("error") { err: dynamic ->
                js("clearTimeout")(connectTimer)
                js("clearTimeout")(requestTimer)
                fail(describeNetworkError(err))
            }
            req.on("close") {
                js("clearTimeout")(connectTimer)
                js("clearTimeout")(requestTimer)
            }
            if (payload != null) req.write(payload)
            req.end()
        }

    private fun describeNetworkError(err: dynamic): String {
        val code = err?.code as? String
        return when (code) {
            "ENOTFOUND", "EAI_AGAIN" -> "Could not find ${record.baseUrl} (check the address and the network)."
            "ECONNREFUSED" -> "${record.baseUrl} refused the connection."
            "ECONNRESET" -> "The connection to ${record.baseUrl} was reset."
            "CERT_HAS_EXPIRED", "DEPTH_ZERO_SELF_SIGNED_CERT", "SELF_SIGNED_CERT_IN_CHAIN",
            "UNABLE_TO_VERIFY_LEAF_SIGNATURE", "ERR_TLS_CERT_ALTNAME_INVALID" ->
                "${record.baseUrl}'s certificate is not trusted ($code)."
            else -> (err?.message as? String)?.let { "Could not reach ${record.baseUrl}: $it" }
                ?: "Could not reach ${record.baseUrl}."
        }
    }
}

/**
 * One connection's change stream (see the file header): opened while
 * [projects] is non-empty, resumed from the last event id, reconnected
 * with backoff, and declared dead after [LUNICLE_STREAM_SILENCE_MS] of
 * silence. A 404 means the server has no stream: the renderer is told
 * `unsupported` (it polls) and one more try follows after 5 minutes.
 *
 * Callbacks of a replaced request are ignored (a generation counter), so
 * a restart never sees the old connection's end as its own.
 *
 * @param remote The connection: base URL and token.
 * @param send Delivers a message object to the renderer.
 */
internal class LunicleStream(private val remote: LunicleRemote, private val send: (dynamic) -> Unit) {
    /** Projects the stream covers. */
    var projects: Set<Long> = emptySet()
        private set

    private var generation = 0
    private var request: dynamic = null
    private var retryTimer: dynamic = null
    private var silenceTimer: dynamic = null
    private var attempt = 0
    private var lastEventId: String? = null
    private var lastActivity = 0.0
    private var connected = false

    /**
     * Covers [next] from now on: restarts the stream when the set changed
     * (or it is down); an unchanged set only repeats `connected`.
     */
    fun setProjects(next: Set<Long>) {
        if (next == projects && (request != null || retryTimer != null)) {
            // A renderer that started over (a reload) asks again: tell it
            // where the stream stands, since it missed the first word.
            if (connected) status("connected")
            return
        }
        projects = next
        attempt = 0
        stop()
        if (projects.isNotEmpty()) connect()
    }

    /** Closes it; nothing more is sent. */
    fun close() {
        projects = emptySet()
        stop()
    }

    private fun stop() {
        generation++
        request?.destroy()
        request = null
        val retry = retryTimer
        if (retry != null) js("clearTimeout")(retry)
        retryTimer = null
        val silence = silenceTimer
        if (silence != null) js("clearInterval")(silence)
        silenceTimer = null
        if (connected) status("disconnected")
        connected = false
    }

    private fun status(state: String) {
        val m: dynamic = js("({})")
        m.connectionId = remote.record.id
        m.status = state
        send(m)
    }

    /** After a drop: [state] to the renderer, and the next attempt after the backoff. */
    private fun dropped(gen: Int, state: String, delayMs: Int) {
        if (gen != generation) return
        connected = false
        stop()
        status(state)
        if (projects.isEmpty()) return
        retryTimer = js("setTimeout")({
            retryTimer = null
            connect()
        }, delayMs)
        attempt++
    }

    private fun connect() {
        val gen = ++generation
        val urlCtor: dynamic = js("URL")
        val full = remote.record.baseUrl + lunicleStreamPath(projects, LUNICLE_ORIGIN)
        val url: dynamic = try { js("new urlCtor(full)") } catch (_: Throwable) { return }
        val h: dynamic = js("({})")
        h["Authorization"] = "Bearer ${remote.record.token}"
        h["Accept"] = "text/event-stream"
        h["Cache-Control"] = "no-cache"
        h["User-Agent"] = "Lunarbor/${app.getVersion()}"
        lastEventId?.let { h["Last-Event-ID"] = it }
        val opts: dynamic = js("({})")
        opts.method = "GET"
        opts.headers = h
        val module = if ((url.protocol as String) == "http:") httpModuleLunicle else httpsModule
        val parser = SseParser(
            onEvent = { id, event, data -> if (gen == generation) forward(id, event, data) },
            onComment = {},
        )
        lastActivity = js("Date.now()") as Double
        val req: dynamic = module.request(url, opts) { res: dynamic ->
            if (gen != generation) {
                res.resume()
                return@request
            }
            val code = res.statusCode as Int
            if (code != 200) {
                res.resume()
                if (code == 404) dropped(gen, "unsupported", 300_000)
                else dropped(gen, "disconnected", lunicleStreamBackoffMs(attempt))
                return@request
            }
            connected = true
            attempt = 0
            status("connected")
            res.setEncoding("utf8")
            res.on("data") { chunk: dynamic ->
                if (gen == generation) {
                    lastActivity = js("Date.now()") as Double
                    parser.feed(chunk as String)
                    parser.lastEventId?.let { lastEventId = it }
                }
            }
            res.on("end") { dropped(gen, "disconnected", lunicleStreamBackoffMs(attempt)) }
            res.on("error") { _: dynamic -> dropped(gen, "disconnected", lunicleStreamBackoffMs(attempt)) }
        }
        req.on("error") { _: dynamic -> dropped(gen, "disconnected", lunicleStreamBackoffMs(attempt)) }
        req.end()
        request = req
        silenceTimer = js("setInterval")({
            val now = js("Date.now()") as Double
            if (gen == generation && now - lastActivity > LUNICLE_STREAM_SILENCE_MS) {
                dropped(gen, "disconnected", lunicleStreamBackoffMs(attempt))
            }
        }, 10_000)
    }

    /** One event to the renderer: `{ connectionId, event, id, data }`, the data parsed as JSON. */
    private fun forward(id: String?, event: String, data: String) {
        val m: dynamic = js("({})")
        m.connectionId = remote.record.id
        m.event = event
        m.id = id
        m.data = try { js("JSON.parse")(data) } catch (_: Throwable) { null }
        send(m)
    }
}

/**
 * Owner of the Lunicle connections and their IPC handlers.
 *
 * ### Callers
 * - `main` (ElectronMain.kt): [install] once, before the first window.
 */
internal object LunicleHost {
    private var settingsPath: () -> String = { "" }
    private var remotes: List<LunicleRemote> = emptyList()
    private var window: () -> BrowserWindow? = { null }

    /**
     * Loads `lunarbor-lunicle.json` and registers `lunarbor:getLunicle`,
     * `addLunicleConnection`, `updateLunicleConnection`,
     * `removeLunicleConnection`, `lunicleRequest` and `lunicleWatch`.
     *
     * @param settingsFile Path of `lunarbor-lunicle.json`.
     * @param currentWindow The window whose renderer hears the change streams.
     */
    fun install(settingsFile: () -> String, currentWindow: () -> BrowserWindow?) {
        settingsPath = settingsFile
        window = currentWindow
        ipcMain.handle("lunarbor:lunicleWatch") { _, spec -> watch(spec) }
        remotes = readSettings().map(::LunicleRemote)
        ipcMain.handle("lunarbor:getLunicle") { _, _ -> status(null) }
        ipcMain.handle("lunarbor:addLunicleConnection") { _, spec -> add(spec) }
        ipcMain.handle("lunarbor:updateLunicleConnection") { _, patch -> update(patch) }
        ipcMain.handle("lunarbor:removeLunicleConnection") { _, id ->
            apply(records().filter { it.id != id })
            status(null)
        }
        ipcMain.handle("lunarbor:lunicleRequest") { _, spec -> relay(spec) }
    }

    private fun records(): List<LunicleConnectionRecord> = remotes.map { it.record }

    /**
     * Called when a new window is created: its renderer starts over and
     * asks for the streams it needs again, so every stream closes.
     */
    fun onWindowCreated() {
        for (r in remotes) r.closeStream()
    }

    /** `{ connectionId, projectIds }`: what that connection's change stream covers. */
    private fun watch(spec: dynamic): Boolean {
        val remote = remotes.firstOrNull { it.record.id == spec?.connectionId as? String } ?: return false
        if (remote.record.token.isEmpty()) return false
        val raw: dynamic = spec.projectIds
        val ids = LinkedHashSet<Long>()
        if (raw != null && js("Array").isArray(raw) as Boolean) {
            for (i in 0 until (raw.length as Int)) {
                val n = (raw[i] as? Number)?.toLong() ?: continue
                if (n > 0) ids += n
            }
        }
        remote.watch(ids) { message -> sendToRenderer(message) }
        return true
    }

    private fun sendToRenderer(message: dynamic) {
        val w = window() ?: return
        if (!w.isDestroyed()) w.webContents.send("lunarbor:lunicleEvent", message)
    }

    /** Adds a connection from `{ name?, baseUrl?, token? }`; a missing name gets a free `lunicle`, `lunicle-2`, …. */
    private fun add(spec: dynamic): dynamic {
        val current = records()
        val given = (spec?.name as? String)?.trim().orEmpty()
        val name = given.ifEmpty {
            var n = 1
            var candidate = "lunicle"
            while (lunicleNameError(candidate, current, null) != null) candidate = "lunicle-${++n}"
            candidate
        }
        lunicleNameError(name, current, null)?.let { return status(it) }
        val baseUrl = normalizeLunicleBaseUrl((spec?.baseUrl as? String) ?: "https://issues.lunicle.dev")
            ?: return status(BASE_URL_ERROR)
        val token = (spec?.token as? String)?.trim().orEmpty()
        if (token.isNotEmpty()) lunicleTokenError(token)?.let { return status(it) }
        apply(current + LunicleConnectionRecord(newId(), name, baseUrl, token))
        return status(null)
    }

    /** Changes a connection from `{ id, name?, baseUrl?, token? }`; one bad field refuses the whole patch. */
    private fun update(patch: dynamic): dynamic {
        val id = patch?.id as? String ?: return status("No such connection.")
        val current = records()
        val old = current.firstOrNull { it.id == id } ?: return status("No such connection.")
        var next = old
        (patch.name as? String)?.trim()?.let { name ->
            lunicleNameError(name, current, id)?.let { return status(it) }
            next = next.copy(name = name)
        }
        (patch.baseUrl as? String)?.let { raw ->
            next = next.copy(baseUrl = normalizeLunicleBaseUrl(raw) ?: return status(BASE_URL_ERROR))
        }
        (patch.token as? String)?.trim()?.let { token ->
            if (token.isNotEmpty()) lunicleTokenError(token)?.let { return status(it) }
            next = next.copy(token = token)
        }
        apply(current.map { if (it.id == id) next else it })
        return status(null)
    }

    /** Relays `{ connectionId, method, path, query, body }` ([LunicleRemote.request]). */
    private fun relay(spec: dynamic): Promise<dynamic> {
        fun refuse(message: String): Promise<dynamic> {
            val r: dynamic = js("({})")
            r.transportError = message
            return Promise.resolve(r)
        }
        val remote = remotes.firstOrNull { it.record.id == spec?.connectionId as? String }
            ?: return refuse("That Lunicle connection no longer exists.")
        if (remote.record.token.isEmpty()) return refuse("The connection “${remote.record.name}” has no token.")
        val method = (spec.method as? String)?.uppercase().orEmpty()
        if (method !in setOf("GET", "POST", "PATCH", "PUT", "DELETE")) return refuse("Unsupported method $method.")
        val path = spec.path as? String ?: return refuse("No path.")
        if (!isAllowedLuniclePath(path)) return refuse("Only Lunicle's API ($LUNICLE_API_PREFIX…) can be reached.")
        // Own echo (LNL-224): events our writes cause come back marked `self`.
        val headers: dynamic = if (method == "GET") null else js("({})")
        if (headers != null) headers["X-Lunicle-Origin"] = LUNICLE_ORIGIN
        return remote.request(method, path, spec.query, spec.body as? String, headers)
    }

    /** Stores [next] and rebuilds the remotes (a changed token or URL takes effect at once). */
    private fun apply(next: List<LunicleConnectionRecord>) {
        val old = remotes.associateBy { it.record }
        val oldById = remotes.associateBy { it.record.id }
        val kept = next.mapNotNull { old[it] }.toSet()
        remotes = next.map { record ->
            old[record] ?: LunicleRemote(record).also { fresh ->
                // A changed URL or token: the stream starts over on the new one.
                val projects = oldById[record.id]?.watchedProjects.orEmpty()
                if (projects.isNotEmpty() && record.token.isNotEmpty()) fresh.watch(projects) { m -> sendToRenderer(m) }
            }
        }
        for (r in old.values) if (r !in kept) r.closeStream()
        writeSettings(next)
    }

    /** What the renderer may know: never a token. [error] is a refused change's reason. */
    private fun status(error: String?): dynamic {
        val s: dynamic = js("({})")
        s.error = error
        s.connections = records().map { c ->
            val o: dynamic = js("({})")
            o.id = c.id
            o.name = c.name
            o.baseUrl = c.baseUrl
            o.hasToken = c.token.isNotEmpty()
            o.tokenHint = lunicleTokenHint(c.token)
            o
        }.toTypedArray()
        return s
    }

    private fun newId(): String = cryptoModuleLunicle.randomBytes(6).toString("hex") as String

    private fun readSettings(): List<LunicleConnectionRecord> {
        val text = try { fsSync.readFileSync(settingsPath(), "utf8") } catch (_: Throwable) { return emptyList() }
        val obj: dynamic = try { js("JSON.parse")(text) } catch (_: Throwable) { return emptyList() }
        if ((obj?.formatVersion as? Number)?.toInt() != LUNICLE_SETTINGS_FORMAT) return emptyList()
        val list: dynamic = obj.connections
        if (!(js("Array").isArray(list) as Boolean)) return emptyList()
        val out = ArrayList<LunicleConnectionRecord>()
        for (i in 0 until (list.length as Int)) {
            val c: dynamic = list[i]
            val name = (c.name as? String).orEmpty()
            val baseUrl = normalizeLunicleBaseUrl((c.baseUrl as? String).orEmpty()) ?: continue
            if (lunicleNameError(name, out, null) != null) continue
            out += LunicleConnectionRecord(
                id = (c.id as? String)?.takeIf { it.isNotEmpty() } ?: newId(),
                name = name,
                baseUrl = baseUrl,
                token = (c.token as? String).orEmpty(),
            )
        }
        return out
    }

    private fun writeSettings(list: List<LunicleConnectionRecord>) {
        val obj: dynamic = js("({})")
        obj.formatVersion = LUNICLE_SETTINGS_FORMAT
        obj.connections = list.map { c ->
            val o: dynamic = js("({})")
            o.id = c.id
            o.name = c.name
            o.baseUrl = c.baseUrl
            o.token = c.token
            o
        }.toTypedArray()
        val path = settingsPath()
        val tmp = "$path.tmp"
        val opts: dynamic = js("({})")
        opts.mode = 384 // 0o600: this user only — the file holds the tokens.
        try {
            fsSync.asDynamic().writeFileSync(tmp, js("JSON.stringify")(obj, null, 2), opts)
            fsSync.asDynamic().renameSync(tmp, path)
            fsSync.asDynamic().chmodSync(path, 384)
        } catch (e: Throwable) {
            console.error("[lunarbor] could not save Lunicle connections", e)
        }
    }

    private const val BASE_URL_ERROR =
        "Enter the instance's address, e.g. https://issues.lunicle.dev (https only; http is allowed for localhost)."
}
