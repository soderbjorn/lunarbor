/* AgentAccessSettings.kt (jsMain)
 *
 * App settings → **Agent access** (Electron only). The sidebar holds a short
 * section — what it is, whether it is on, how many connections — and an
 * "Agent access…" button; everything else is in a modal dialog
 * ([openAgentAccessDialog]):
 *
 *  - "Let agents connect" turns the MCP server on and off; the endpoint or
 *    the reason it is not running shows under it.
 *  - One card per **connection**: its name, the folder it is limited to
 *    ("Whole vault" or a folder picked with the system chooser, confined to
 *    the vault), whether it may edit, its key (masked until Show; Copy and
 *    New key), and copy-ready setup for Claude Code (a `claude mcp add`
 *    command), JSON-configured clients (Cursor, VS Code, a project's
 *    `.mcp.json`) and Claude Desktop (through `mcp-remote`), each under its
 *    own server name so several can be added side by side.
 *  - "Add connection…" picks a folder and makes a connection for it.
 *
 * Everything is read from and written to the Electron main process
 * (`noteApi.getMcp` / `setMcp` / `addMcpConnection` / `updateMcpConnection`
 * / `removeMcpConnection` / `newMcpKey` / `chooseMcpFolder`,
 * McpHttpServer.kt), which owns the server, the connections and their keys.
 * Copy always copies the real key. Confirmations (New key, Remove) are
 * asked inside the card, so nothing opens under the dialog.
 *
 * Platform view code only — builds DOM, delegates every action. */
package se.soderbjorn.lunarbor.main

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import kotlin.js.Promise

/** `noteApi` when it carries the MCP bridge (Electron), else `null`. */
internal fun mcpBridge(): dynamic {
    val api = js("globalThis.noteApi")
    if (api == null || js("typeof api.getMcp !== 'function'") as Boolean) return null
    return api
}

/**
 * One connection as the main process reports it (see `McpConnection`).
 *
 * @property id Its stable id.
 * @property name The user's label.
 * @property key Its secret.
 * @property folder Vault-relative folder it is limited to; `""` for the whole vault.
 * @property allowEdits It may change the vault.
 */
private data class McpConnectionView(
    val id: String,
    val name: String,
    val key: String,
    val folder: String,
    val allowEdits: Boolean,
)

/**
 * The status the main process reports (see `McpHost.status`).
 *
 * @property enabled The server is on.
 * @property url The endpoint, e.g. `http://127.0.0.1:47321/mcp`.
 * @property running The server is listening.
 * @property error Why it is not, e.g. a port in use.
 * @property connections Every connection, in the order they were added.
 */
private data class McpStatus(
    val enabled: Boolean,
    val url: String,
    val running: Boolean,
    val error: String?,
    val connections: List<McpConnectionView>,
) {
    companion object {
        fun of(d: dynamic): McpStatus {
            val list: dynamic = d.connections
            val connections = ArrayList<McpConnectionView>()
            if (list != null && list != undefined) {
                for (i in 0 until (list.length as Int)) {
                    val c: dynamic = list[i]
                    connections += McpConnectionView(
                        id = c.id as String,
                        name = (c.name as String?).orEmpty(),
                        key = (c.key as String?).orEmpty(),
                        folder = (c.folder as String?).orEmpty(),
                        allowEdits = c.allowEdits != false,
                    )
                }
            }
            return McpStatus(
                enabled = d.enabled == true,
                url = d.url as String,
                running = d.running == true,
                error = d.error as String?,
                connections = connections,
            )
        }
    }
}

/** Reads the status from the main process. */
private suspend fun fetchStatus(): McpStatus = McpStatus.of((mcpBridge().getMcp() as Promise<dynamic>).await())

/** One line saying whether agents can connect, and how. */
private fun summaryOf(s: McpStatus): String = when {
    !s.enabled -> "Off."
    s.error != null -> s.error
    !s.running -> "Starting…"
    s.connections.size == 1 -> "On — 1 connection."
    else -> "On — ${s.connections.size} connections."
}

/**
 * Builds the sidebar's Agent access section: a short explanation, the
 * current state and the button opening [openAgentAccessDialog]. Called by
 * `buildAppSettingsContent` when [mcpBridge] is present.
 *
 * @param scope Scope the bridge calls run in.
 */
internal fun buildAgentAccessSection(scope: CoroutineScope): HTMLElement {
    ensureAgentAccessStyles()
    val section = el("section", "lunarbor-app-settings-section lunarbor-mcp")
    section.appendChild(el("h3", "lunarbor-app-settings-section-title", "Agent access (MCP)"))
    section.appendChild(
        el(
            "p", "lunarbor-mcp-intro",
            "Let AI agents such as Claude Code read, search and edit your notes — the whole vault or just one folder " +
                "per connection.",
        ),
    )
    val statusLine = el("div", "lunarbor-mcp-status")
    section.appendChild(statusLine)
    fun refresh() {
        scope.launch {
            val s = fetchStatus()
            statusLine.className = "lunarbor-mcp-status" + if (s.enabled && s.error != null) " is-error" else ""
            statusLine.textContent = summaryOf(s)
        }
    }
    section.appendChild(button("Agent access…") { openAgentAccessDialog(scope, onClose = ::refresh) })
    refresh()
    return section
}

/**
 * Opens the Agent access dialog over the app (see the file header). Escape,
 * the × and a click outside close it.
 *
 * @param scope Scope the bridge calls run in.
 * @param onClose Called once the dialog is gone, e.g. to refresh the
 *   sidebar's summary.
 */
internal fun openAgentAccessDialog(scope: CoroutineScope, onClose: () -> Unit = {}) {
    ensureAgentAccessStyles()
    if (document.querySelector(".lunarbor-mcp-backdrop") != null) return
    val backdrop = el("div", "lunarbor-mcp-backdrop")
    val panel = el("div", "lunarbor-mcp-dialog")
    panel.setAttribute("role", "dialog")
    panel.setAttribute("aria-label", "Agent access")
    backdrop.appendChild(panel)

    val header = el("div", "lunarbor-mcp-dialog-head")
    header.appendChild(el("h2", "lunarbor-mcp-dialog-title", "Agent access (MCP)"))
    lateinit var close: () -> Unit
    val closeButton = button("×") { close() }
    closeButton.classList.add("lunarbor-mcp-close")
    closeButton.title = "Close"
    header.appendChild(closeButton)
    panel.appendChild(header)
    val body = el("div", "lunarbor-mcp-dialog-body")
    panel.appendChild(body)

    val keyHandler: (Event) -> Unit = { e ->
        if ((e as KeyboardEvent).key == "Escape") {
            e.preventDefault()
            e.stopPropagation()
            close()
        }
    }
    close = {
        document.removeEventListener("keydown", keyHandler, true)
        backdrop.remove()
        onClose()
    }
    document.addEventListener("keydown", keyHandler, true)
    backdrop.addEventListener("mousedown", { e -> if (e.target === backdrop) close() })
    document.body?.appendChild(backdrop)

    body.appendChild(
        el(
            "p", "lunarbor-mcp-intro",
            "Agents connect over MCP and work on the running app: they read, search and edit nodes, and open windows " +
                "to show you their work. Only programs on this computer that have a connection's key can connect; " +
                "other computers cannot reach it at all. Each connection has its own key and can be limited to one " +
                "folder — an agent using it cannot see or change anything outside that folder.",
        ),
    )
    val enabledBox = checkbox()
    body.appendChild(toggleRow(enabledBox, "Let agents connect"))
    val statusLine = el("div", "lunarbor-mcp-status")
    body.appendChild(statusLine)
    val list = el("div", "lunarbor-mcp-connections")
    body.appendChild(list)
    val footer = el("div", "lunarbor-mcp-footer")
    body.appendChild(footer)

    var status: McpStatus? = null
    val shownKeys = HashSet<String>()
    var folderError: String? = null

    fun apply(next: dynamic) {
        status = McpStatus.of(next)
    }

    lateinit var render: () -> Unit
    fun call(block: suspend () -> dynamic) {
        scope.launch {
            apply(block())
            render()
        }
    }

    render = render@{
        val s = status ?: return@render
        enabledBox.checked = s.enabled
        statusLine.className = "lunarbor-mcp-status" + if (s.enabled && s.error != null) " is-error" else ""
        statusLine.textContent = when {
            !s.enabled -> "Off: no agent can connect."
            s.error != null -> s.error
            s.running -> "On at ${s.url}"
            else -> "Starting…"
        }
        list.innerHTML = ""
        footer.innerHTML = ""
        list.hidden = !s.enabled
        footer.hidden = !s.enabled
        if (!s.enabled) return@render
        val names = serverNames(s.connections)
        for (c in s.connections) {
            list.appendChild(connectionCard(c, names.getValue(c.id), s.url, scope, shownKeys, ::call) { render() })
        }
        if (s.connections.isEmpty()) {
            list.appendChild(el("p", "lunarbor-mcp-note", "No connections: add one to let an agent in."))
        }
        footer.appendChild(button("Add connection…") {
            scope.launch {
                val picked = (mcpBridge().chooseMcpFolder("") as Promise<dynamic>).await()
                val error = picked.error as String?
                val folder = picked.folder as String?
                folderError = error
                if (folder != null) {
                    val spec: dynamic = js("({})")
                    spec.folder = folder
                    spec.allowEdits = true
                    apply((mcpBridge().addMcpConnection(spec) as Promise<dynamic>).await())
                }
                render()
            }
        })
        footer.appendChild(
            el("span", "lunarbor-mcp-hint", "Pick a folder for the new connection; choose the vault itself for all of it."),
        )
        folderError?.let { footer.appendChild(el("div", "lunarbor-mcp-status is-error", it)) }
    }

    enabledBox.addEventListener("change", { _: Event ->
        val patch: dynamic = js("({})")
        patch.enabled = enabledBox.checked
        scope.launch {
            apply((mcpBridge().setMcp(patch) as Promise<dynamic>).await())
            render()
            // The server binds asynchronously; read the outcome once it has.
            kotlinx.coroutines.delay(400)
            apply((mcpBridge().getMcp() as Promise<dynamic>).await())
            render()
        }
    })

    scope.launch {
        status = fetchStatus()
        render()
    }
}

/**
 * The MCP server name each connection's setup snippets use, unique among
 * [connections]: `lunarbor` for a whole-vault connection named
 * "Lunarbor", else `lunarbor-<name>` in lower-case letters, digits and
 * dashes, numbered when two would clash.
 */
private fun serverNames(connections: List<McpConnectionView>): Map<String, String> {
    val used = HashSet<String>()
    return connections.associate { c ->
        val slug = c.name.lowercase().map { if (it.isLetterOrDigit() && it.code < 128) it else '-' }
            .joinToString("").split('-').filter { it.isNotEmpty() }.joinToString("-")
        val base = if (slug.isEmpty() || slug == "lunarbor") "lunarbor" else "lunarbor-$slug"
        var name = base
        var n = 2
        while (!used.add(name)) name = "$base-${n++}"
        c.id to name
    }
}

/**
 * One connection's card: name, folder, edits, key and setup.
 *
 * @param serverName The MCP server name for its snippets ([serverNames]).
 * @param url The endpoint.
 * @param shownKeys Ids of the connections whose key is shown unmasked.
 * @param call Runs a bridge call and repaints with the status it returns.
 * @param rerender Repaints without a call (Show / Hide, confirmations).
 */
private fun connectionCard(
    c: McpConnectionView,
    serverName: String,
    url: String,
    scope: CoroutineScope,
    shownKeys: MutableSet<String>,
    call: (suspend () -> dynamic) -> Unit,
    rerender: () -> Unit,
): HTMLElement {
    val card = el("div", "lunarbor-mcp-card")
    fun patch(build: (dynamic) -> Unit) = call {
        val p: dynamic = js("({})")
        p.id = c.id
        build(p)
        (mcpBridge().updateMcpConnection(p) as Promise<dynamic>).await()
    }

    val nameRow = el("div", "lunarbor-mcp-card-head")
    val nameInput = document.createElement("input") as HTMLInputElement
    nameInput.type = "text"
    nameInput.className = "lunarbor-mcp-name"
    nameInput.value = c.name
    nameInput.spellcheck = false
    nameInput.title = "The connection's name"
    nameInput.addEventListener("change", { _: Event ->
        val v = nameInput.value.trim()
        if (v.isNotEmpty() && v != c.name) patch { it.name = v } else nameInput.value = c.name
    })
    nameInput.addEventListener("keydown", { e -> if ((e as KeyboardEvent).key == "Enter") nameInput.blur() })
    nameRow.appendChild(nameInput)
    val confirm = el("div", "lunarbor-mcp-confirm")
    confirm.hidden = true
    nameRow.appendChild(button("Remove…") {
        showInlineConfirm(confirm, "Remove “${c.name}”? Agents using its key can no longer connect.", "Remove") {
            call { (mcpBridge().removeMcpConnection(c.id) as Promise<dynamic>).await() }
        }
    })
    card.appendChild(nameRow)
    card.appendChild(confirm)

    val folderRow = el("div", "lunarbor-mcp-row")
    folderRow.appendChild(el("span", "lunarbor-mcp-label", "Folder"))
    folderRow.appendChild(el("code", "lunarbor-mcp-folder", if (c.folder.isEmpty()) "Whole vault" else "/${c.folder}"))
    val folderError = el("div", "lunarbor-mcp-status is-error")
    folderError.hidden = true
    folderRow.appendChild(button("Choose…") {
        scope.launch {
            val picked = (mcpBridge().chooseMcpFolder(c.folder) as Promise<dynamic>).await()
            val error = picked.error as String?
            val folder = picked.folder as String?
            folderError.hidden = error == null
            folderError.textContent = error.orEmpty()
            if (folder != null && folder != c.folder) patch { it.folder = folder }
        }
    })
    if (c.folder.isNotEmpty()) folderRow.appendChild(button("Whole vault") { patch { it.folder = "" } })
    card.appendChild(folderRow)
    card.appendChild(folderError)

    val editsBox = checkbox()
    editsBox.checked = c.allowEdits
    editsBox.addEventListener("change", { _: Event -> patch { it.allowEdits = editsBox.checked } })
    card.appendChild(toggleRow(editsBox, "Allow edits", "Off: this connection can only read and search."))

    val showKey = c.id in shownKeys
    val shown = if (showKey) c.key else c.key.take(6) + "•".repeat(20)
    val keyRow = el("div", "lunarbor-mcp-row")
    keyRow.appendChild(el("span", "lunarbor-mcp-label", "Key"))
    keyRow.appendChild(el("code", "lunarbor-mcp-key", shown))
    keyRow.appendChild(button(if (showKey) "Hide" else "Show") {
        if (!shownKeys.add(c.id)) shownKeys.remove(c.id)
        rerender()
    })
    keyRow.appendChild(copyButton { c.key })
    keyRow.appendChild(button("New key…") {
        showInlineConfirm(confirm, "Make a new key? Agents set up with this connection's key stop working until you set them up again.", "New Key") {
            call { (mcpBridge().newMcpKey(c.id) as Promise<dynamic>).await() }
        }
    })
    card.appendChild(keyRow)

    val details = document.createElement("details") as HTMLElement
    details.className = "lunarbor-mcp-details"
    details.appendChild(el("summary", "lunarbor-mcp-summary", "Set up an agent with this connection"))
    fun withKey(text: String, key: String) = text.replace(KEY_PLACEHOLDER, key)
    details.appendChild(snippet(
        "Claude Code",
        "Run this in a terminal, then start a new Claude Code session (or run /mcp). --scope user makes " +
            "it available in every project.",
        withKey(claudeCodeCommand(serverName, url), shown),
    ) { withKey(claudeCodeCommand(serverName, url), c.key) })
    details.appendChild(snippet(
        "Cursor, VS Code, Windsurf and other MCP clients",
        "Add this server in the app's MCP settings — a project's .mcp.json, ~/.cursor/mcp.json, VS Code's " +
            "mcp.json (there the outer key is \"servers\"). The server speaks MCP over HTTP.",
        withKey(jsonConfig(serverName, url), shown),
    ) { withKey(jsonConfig(serverName, url), c.key) })
    details.appendChild(snippet(
        "Claude Desktop",
        "Claude Desktop starts its servers as commands, so it connects through mcp-remote (needs Node.js). Add " +
            "this under \"mcpServers\" in Settings → Developer → Edit Config, then restart Claude Desktop.",
        withKey(desktopConfig(serverName, url), shown),
    ) { withKey(desktopConfig(serverName, url), c.key) })
    card.appendChild(details)
    return card
}

/**
 * Shows [message] with [confirmLabel] / Cancel buttons in [host] (a hidden
 * slot in a connection card); [onConfirm] runs on the first.
 */
private fun showInlineConfirm(host: HTMLElement, message: String, confirmLabel: String, onConfirm: () -> Unit) {
    host.innerHTML = ""
    host.hidden = false
    host.appendChild(el("span", "lunarbor-mcp-confirm-text", message))
    val yes = button(confirmLabel) { host.hidden = true; onConfirm() }
    yes.classList.add("is-danger")
    host.appendChild(yes)
    host.appendChild(button("Cancel") { host.hidden = true })
}

/** Stands for the key in the snippet templates. */
private const val KEY_PLACEHOLDER = "{{KEY}}"

private fun claudeCodeCommand(name: String, url: String): String =
    "claude mcp add --transport http --scope user $name $url --header \"Authorization: Bearer $KEY_PLACEHOLDER\""

private fun jsonConfig(name: String, url: String): String = """
{
  "mcpServers": {
    "$name": {
      "type": "http",
      "url": "$url",
      "headers": { "Authorization": "Bearer $KEY_PLACEHOLDER" }
    }
  }
}
""".trim()

private fun desktopConfig(name: String, url: String): String = """
"$name": {
  "command": "npx",
  "args": ["-y", "mcp-remote", "$url", "--header", "Authorization:${'$'}{LUNARBOR_AUTH}"],
  "env": { "LUNARBOR_AUTH": "Bearer $KEY_PLACEHOLDER" }
}
""".trim()

/** A titled, explained code snippet with a Copy button copying [copyText]. */
private fun snippet(title: String, help: String, shown: String, copyText: () -> String): HTMLElement {
    val box = el("div", "lunarbor-mcp-snippet")
    val head = el("div", "lunarbor-mcp-snippet-head")
    head.appendChild(el("label", "lunarbor-mcp-label", title))
    head.appendChild(copyButton(copyText))
    box.appendChild(head)
    box.appendChild(el("p", "lunarbor-mcp-help", help))
    box.appendChild(el("pre", "lunarbor-mcp-code", shown))
    return box
}

private fun copyButton(text: () -> String): HTMLButtonElement {
    lateinit var b: HTMLButtonElement
    b = button("Copy") {
        copyToClipboard(text())
        b.textContent = "Copied"
        window.setTimeout({ b.textContent = "Copy" }, 1200)
    }
    return b
}

/** Clipboard API, falling back to a hidden textarea and `execCommand`. */
private fun copyToClipboard(text: String) {
    val clipboard = window.navigator.asDynamic().clipboard
    if (clipboard != null) {
        (clipboard.writeText(text) as Promise<dynamic>).catch { fallbackCopy(text) }
    } else fallbackCopy(text)
}

private fun fallbackCopy(text: String) {
    val area = document.createElement("textarea") as HTMLTextAreaElement
    area.value = text
    area.style.position = "fixed"
    area.style.left = "-9999px"
    document.body?.appendChild(area)
    area.select()
    document.execCommand("copy")
    area.remove()
}

private fun checkbox(): HTMLInputElement =
    (document.createElement("input") as HTMLInputElement).also { it.type = "checkbox" }

private fun toggleRow(box: HTMLInputElement, label: String, hint: String? = null): HTMLElement {
    val row = el("label", "lunarbor-mcp-toggle")
    row.appendChild(box)
    val text = el("span", "lunarbor-mcp-toggle-text", label)
    if (hint != null) text.appendChild(el("span", "lunarbor-mcp-hint", hint))
    row.appendChild(text)
    return row
}

private fun button(label: String, onClick: () -> Unit): HTMLButtonElement {
    val b = document.createElement("button") as HTMLButtonElement
    b.type = "button"
    b.className = "lunarbor-mcp-button"
    b.textContent = label
    b.addEventListener("click", { _: Event -> onClick() })
    return b
}

private fun el(tag: String, className: String, text: String? = null): HTMLElement =
    (document.createElement(tag) as HTMLElement).also {
        it.className = className
        if (text != null) it.textContent = text
    }

private fun ensureAgentAccessStyles() {
    if (document.getElementById("lunarbor-mcp-style") != null) return
    val style = document.createElement("style") as HTMLElement
    style.id = "lunarbor-mcp-style"
    style.textContent = AGENT_ACCESS_CSS
    document.head?.appendChild(style)
}

private const val AGENT_ACCESS_CSS = """
.lunarbor-mcp-intro, .lunarbor-mcp-help, .lunarbor-mcp-note { margin: 0; font-size: 12px; color: var(--t-text-dim, #9a9a9a); }
.lunarbor-mcp-toggle { display: flex; align-items: flex-start; gap: 8px; cursor: pointer; }
.lunarbor-mcp-toggle input { margin: 2px 0 0; accent-color: var(--t-accent); }
.lunarbor-mcp-toggle-text { display: flex; flex-direction: column; gap: 2px; font-weight: 600; }
.lunarbor-mcp-hint { font-weight: 400; font-size: 12px; color: var(--t-text-dim, #9a9a9a); }
.lunarbor-mcp-toggle[hidden], .lunarbor-mcp-connections[hidden], .lunarbor-mcp-footer[hidden],
.lunarbor-mcp-confirm[hidden], .lunarbor-mcp-status[hidden] { display: none; }
.lunarbor-mcp-backdrop {
    position: fixed; inset: 0; z-index: 2147483640; background: rgba(0, 0, 0, 0.45);
    display: flex; align-items: flex-start; justify-content: center; padding: 8vh 16px 16px;
}
.lunarbor-mcp-dialog {
    width: min(680px, 100%); max-height: 84vh; display: flex; flex-direction: column;
    background: var(--t-bg, #1e1e1e); color: var(--t-text, #e6e6e6);
    border: 1px solid var(--t-border, rgba(255,255,255,0.12)); border-radius: 12px;
    box-shadow: 0 28px 72px rgba(0, 0, 0, 0.45), 0 10px 24px rgba(0, 0, 0, 0.30);
    font-size: 13px; line-height: 1.4;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
}
.lunarbor-mcp-dialog-head {
    display: flex; align-items: center; justify-content: space-between; gap: 8px;
    padding: 14px 16px 10px; border-bottom: 1px solid var(--t-border, rgba(255,255,255,0.12));
}
.lunarbor-mcp-dialog-title { margin: 0; font-size: 16px; font-weight: 700; }
.lunarbor-mcp-close { font-size: 16px; line-height: 1; padding: 2px 8px; }
.lunarbor-mcp-dialog-body { display: flex; flex-direction: column; gap: 12px; padding: 14px 16px 18px; overflow-y: auto; }
.lunarbor-mcp-connections { display: flex; flex-direction: column; gap: 12px; }
.lunarbor-mcp-card {
    display: flex; flex-direction: column; gap: 10px; padding: 12px;
    border: 1px solid var(--t-border, rgba(255,255,255,0.12)); border-radius: 8px;
    background: color-mix(in srgb, var(--t-text, #e6e6e6) 3%, transparent);
}
.lunarbor-mcp-card-head { display: flex; align-items: center; gap: 8px; }
.lunarbor-mcp-name {
    flex: 1; min-width: 0; font: inherit; font-weight: 700; font-size: 14px; color: inherit;
    background: transparent; border: 1px solid transparent; border-radius: 6px; padding: 3px 6px; margin-left: -6px;
}
.lunarbor-mcp-name:hover { border-color: var(--t-border, rgba(255,255,255,0.12)); }
.lunarbor-mcp-name:focus { outline: none; border-color: var(--t-accent); }
.lunarbor-mcp-row { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.lunarbor-mcp-row > .lunarbor-mcp-label { width: 48px; flex: 0 0 auto; }
.lunarbor-mcp-folder { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; font-family: var(--t-font-mono, ui-monospace, Menlo, monospace); }
.lunarbor-mcp-confirm {
    display: flex; align-items: center; gap: 6px; flex-wrap: wrap; padding: 8px 10px; border-radius: 6px;
    background: color-mix(in srgb, var(--t-danger, #e5534b) 10%, transparent);
}
.lunarbor-mcp-confirm-text { flex: 1 1 200px; font-size: 12px; }
.lunarbor-mcp-button.is-danger { color: var(--t-danger, #e5534b); border-color: color-mix(in srgb, var(--t-danger, #e5534b) 50%, transparent); }
.lunarbor-mcp-details { display: flex; flex-direction: column; gap: 10px; }
.lunarbor-mcp-details[open] > .lunarbor-mcp-summary { margin-bottom: 10px; }
.lunarbor-mcp-summary { cursor: pointer; font-size: 12px; font-weight: 600; color: var(--t-text-dim, #9a9a9a); }
.lunarbor-mcp-details > .lunarbor-mcp-snippet + .lunarbor-mcp-snippet { margin-top: 10px; }
.lunarbor-mcp-footer { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.lunarbor-mcp-status { font-size: 12px; color: var(--t-text-dim, #9a9a9a); font-family: var(--t-font-mono, ui-monospace, Menlo, monospace); word-break: break-all; }
.lunarbor-mcp-status.is-error { color: var(--t-danger, #e5534b); font-family: inherit; }
.lunarbor-mcp-setup { display: flex; flex-direction: column; gap: 12px; margin-top: 4px; }
.lunarbor-mcp-label { font-size: 12px; font-weight: 600; }
.lunarbor-mcp-key-row { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.lunarbor-mcp-key { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; font-family: var(--t-font-mono, ui-monospace, Menlo, monospace); }
.lunarbor-mcp-snippet { display: flex; flex-direction: column; gap: 4px; }
.lunarbor-mcp-snippet-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.lunarbor-mcp-code {
    margin: 0; padding: 8px 10px; overflow-x: auto; white-space: pre-wrap; word-break: break-all;
    font-family: var(--t-font-mono, ui-monospace, Menlo, monospace); font-size: 11px; line-height: 1.45;
    background: color-mix(in srgb, var(--t-text, #e6e6e6) 6%, transparent);
    border: 1px solid var(--t-border, rgba(255,255,255,0.12)); border-radius: 6px;
    user-select: text;
}
.lunarbor-mcp-button {
    flex: 0 0 auto; padding: 4px 10px; font: inherit; font-size: 12px; font-weight: 600;
    color: var(--t-text, #e6e6e6); background: transparent;
    border: 1px solid var(--t-border, rgba(255,255,255,0.12)); border-radius: 6px; cursor: pointer;
}
.lunarbor-mcp-button:hover {
    background: color-mix(in srgb, var(--t-accent) 14%, var(--t-surface));
    border-color: color-mix(in srgb, var(--t-accent) 40%, var(--t-border, rgba(255,255,255,0.12)));
}
.lunarbor-mcp-button:focus-visible { outline: 2px solid var(--t-accent); outline-offset: 2px; }
body.appearance-light .lunarbor-mcp-button, body.appearance-light .lunarbor-mcp-code { border-color: rgba(0,0,0,0.10); }
"""
