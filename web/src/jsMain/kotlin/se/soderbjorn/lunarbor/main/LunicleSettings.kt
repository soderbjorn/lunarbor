/* LunicleSettings.kt (jsMain)
 *
 * App settings → **Lunicle** (Electron only; absent in the browser demo,
 * which has no `LunicleService`), LBR-26. Same pattern as Agent access: the
 * sidebar holds a short section — what it is, which connections exist — and
 * a "Lunicle connections…" button; everything else is in a modal dialog
 * ([openLunicleDialog]):
 *
 *  - One card per **connection**: its **name** (the slug a board node uses,
 *    `{{lunicle: <name>/<KEY>}}`; unique, ignoring case), its **base URL**
 *    (`https:`, or `http://localhost`), its **personal access token** —
 *    masked to Lunicle's own display prefix, since the renderer never gets
 *    the token back; Replace… types a new one, with Show to reveal what is
 *    typed — a **Test** button (`GET /api/v1/me`: whose token it is and its
 *    scope, or the error), and Delete…, confirmed inside the card.
 *  - "Add connection" adds a card with a free name and Lunicle's public
 *    address, ready to edit.
 *  - A hint says where tokens are made (Lunicle → Settings → You → API
 *    access) and that a read-only token gives read-only boards.
 *
 * Every change goes through [LunicleService] (`addConnection` /
 * `updateConnection` / `removeConnection` / `testConnection`), which writes
 * through the Electron main process (`LunicleHost.kt`, `lunarbor-lunicle.json`)
 * and keeps its connections flow and project caches in step; a refused
 * change (taken name, bad URL or token) shows its reason in the card.
 *
 * Reuses Agent access's dialog styles (`lunarbor-mcp-*`,
 * [ensureAgentAccessStyles]) plus a few of its own.
 *
 * Platform view code only — builds DOM, delegates every action. */
package se.soderbjorn.lunarbor.main

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.lunarbor.lunicle.LunicleConnection
import se.soderbjorn.lunarbor.lunicle.LunicleConnectionsSnapshot
import se.soderbjorn.lunarbor.lunicle.LunicleResult
import se.soderbjorn.lunarbor.lunicle.LunicleService

/** Where personal access tokens are made, shown in the dialog. */
private const val TOKEN_HINT =
    "Make a token in Lunicle under Settings → You → API access. A read-only token works too; its boards are then read-only."

/** One line naming the connections. */
private fun summaryOf(connections: List<LunicleConnection>): String = when (connections.size) {
    0 -> "No connections."
    1 -> "1 connection: ${connections[0].name}."
    else -> "${connections.size} connections: ${connections.joinToString(", ") { it.name }}."
}

/**
 * Builds the sidebar's Lunicle section: a short explanation, the
 * connections' names and the button opening [openLunicleDialog]. Called by
 * `buildAppSettingsContent` when the app has a [LunicleService].
 *
 * @param scope Scope the service calls run in.
 * @param service The app's Lunicle service.
 */
internal fun buildLunicleSection(scope: CoroutineScope, service: LunicleService): HTMLElement {
    ensureAgentAccessStyles()
    ensureLunicleStyles()
    val section = el("section", "lunarbor-app-settings-section lunarbor-mcp")
    section.appendChild(el("h3", "lunarbor-app-settings-section-title", "Lunicle"))
    section.appendChild(el("p", "lunarbor-mcp-intro", "Show Lunicle issue boards in your notes. Add the Lunicle instances you use."))
    val statusLine = el("div", "lunarbor-mcp-status lunarbor-lunicle-summary")
    section.appendChild(statusLine)
    fun refresh() {
        scope.launch { statusLine.textContent = summaryOf(service.refreshConnections().connections) }
    }
    section.appendChild(button("Lunicle connections…") { openLunicleDialog(scope, service, onClose = ::refresh) })
    refresh()
    return section
}

/**
 * Opens the Lunicle connections dialog over the app (see the file header).
 * Escape, the × and a click outside close it.
 *
 * @param scope Scope the service calls run in.
 * @param service The app's Lunicle service.
 * @param onClose Called once the dialog is gone, e.g. to refresh the
 *   sidebar's summary.
 */
internal fun openLunicleDialog(scope: CoroutineScope, service: LunicleService, onClose: () -> Unit = {}) {
    ensureAgentAccessStyles()
    ensureLunicleStyles()
    if (document.querySelector(".lunarbor-mcp-backdrop") != null) return
    val backdrop = el("div", "lunarbor-mcp-backdrop")
    val panel = el("div", "lunarbor-mcp-dialog")
    panel.setAttribute("role", "dialog")
    panel.setAttribute("aria-label", "Lunicle connections")
    backdrop.appendChild(panel)

    val header = el("div", "lunarbor-mcp-dialog-head")
    header.appendChild(el("h2", "lunarbor-mcp-dialog-title", "Lunicle connections"))
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
            "A bullet holding {{lunicle: <connection>/<KEY>}} shows that Lunicle project's board under itself. " +
                "Each connection names one Lunicle instance and the personal access token Lunarbor uses there. " +
                "The token stays in Lunarbor's settings on this computer; Lunarbor sends it only to that address.",
        ),
    )
    body.appendChild(el("p", "lunarbor-mcp-intro", TOKEN_HINT))
    val list = el("div", "lunarbor-mcp-connections")
    body.appendChild(list)
    val footer = el("div", "lunarbor-mcp-footer")
    body.appendChild(footer)

    val state = DialogState()
    lateinit var render: () -> Unit
    /** Runs a change; a refused one shows its reason on [cardId]'s card. */
    fun change(cardId: String?, block: suspend () -> LunicleConnectionsSnapshot) {
        scope.launch {
            val snapshot = block()
            if (cardId != null) {
                val refused = snapshot.error
                if (refused != null) state.errors[cardId] = refused else state.errors.remove(cardId)
            } else {
                state.addError = snapshot.error
            }
            state.connections = snapshot.connections
            render()
        }
    }

    render = render@{
        val connections = state.connections ?: return@render
        list.innerHTML = ""
        footer.innerHTML = ""
        for (c in connections) list.appendChild(connectionCard(c, scope, service, state, ::change) { render() })
        if (connections.isEmpty()) {
            list.appendChild(el("p", "lunarbor-mcp-note", "No connections yet: add one for each Lunicle you use."))
        }
        footer.appendChild(button("Add connection") {
            change(null) { service.addConnection() }
        })
        state.addError?.let { footer.appendChild(el("span", "lunarbor-mcp-status is-error", it)) }
    }

    scope.launch {
        state.connections = service.refreshConnections().connections
        render()
    }
}

/**
 * What the open dialog remembers between repaints.
 *
 * @property connections The connections as last read, `null` before the first read.
 * @property errors Card id → why its last change was refused.
 * @property tests Card id → the last Test's outcome line (text, is-error).
 * @property replacing Ids of the cards whose token field is open.
 * @property addError Why Add connection was refused, if it was.
 */
private class DialogState {
    var connections: List<LunicleConnection>? = null
    val errors = HashMap<String, String>()
    val tests = HashMap<String, Pair<String, Boolean>>()
    val replacing = HashSet<String>()
    var addError: String? = null
}

/**
 * One connection's card: name, address, token, Test and Delete.
 *
 * @param change Runs a store change and repaints; a refusal shows on this card.
 * @param rerender Repaints without a change (Replace…, a Test's outcome).
 */
private fun connectionCard(
    c: LunicleConnection,
    scope: CoroutineScope,
    service: LunicleService,
    state: DialogState,
    change: (String?, suspend () -> LunicleConnectionsSnapshot) -> Unit,
    rerender: () -> Unit,
): HTMLElement {
    val card = el("div", "lunarbor-mcp-card")

    // Name + Delete.
    val head = el("div", "lunarbor-mcp-card-head")
    val nameInput = textInput(c.name, "lunarbor-mcp-name")
    nameInput.title = "The connection's name, as written in {{lunicle: ${c.name}/KEY}}"
    nameInput.addEventListener("change", { _: Event ->
        val v = nameInput.value.trim()
        if (v != c.name) change(c.id) { service.updateConnection(c.id, name = v) } else nameInput.value = c.name
    })
    nameInput.addEventListener("keydown", { e -> if ((e as KeyboardEvent).key == "Enter") nameInput.blur() })
    head.appendChild(nameInput)
    val confirm = el("div", "lunarbor-mcp-confirm")
    confirm.hidden = true
    head.appendChild(button("Delete…") {
        confirm.innerHTML = ""
        confirm.hidden = false
        confirm.appendChild(
            el("span", "lunarbor-mcp-confirm-text", "Delete “${c.name}”? Its token is forgotten, and boards using it stop showing."),
        )
        val yes = button("Delete") { confirm.hidden = true; change(null) { service.removeConnection(c.id) } }
        yes.classList.add("is-danger")
        confirm.appendChild(yes)
        confirm.appendChild(button("Cancel") { confirm.hidden = true })
    })
    card.appendChild(head)
    card.appendChild(confirm)
    state.errors[c.id]?.let { card.appendChild(el("div", "lunarbor-mcp-status is-error", it)) }

    // Base URL.
    val urlRow = el("div", "lunarbor-mcp-row")
    urlRow.appendChild(el("span", "lunarbor-mcp-label", "Address"))
    val urlInput = textInput(c.baseUrl, "lunarbor-lunicle-input")
    urlInput.placeholder = "https://issues.lunicle.dev"
    urlInput.title = "The Lunicle instance's address (https; http only for localhost)"
    urlInput.addEventListener("change", { _: Event ->
        val v = urlInput.value.trim()
        if (v != c.baseUrl) change(c.id) { service.updateConnection(c.id, baseUrl = v) } else urlInput.value = c.baseUrl
    })
    urlInput.addEventListener("keydown", { e -> if ((e as KeyboardEvent).key == "Enter") urlInput.blur() })
    urlRow.appendChild(urlInput)
    card.appendChild(urlRow)

    // Token: masked, Replace… opens a field.
    val tokenRow = el("div", "lunarbor-mcp-row")
    tokenRow.appendChild(el("span", "lunarbor-mcp-label", "Token"))
    if (c.id in state.replacing) {
        val tokenInput = document.createElement("input") as HTMLInputElement
        tokenInput.type = "password"
        tokenInput.className = "lunarbor-lunicle-input"
        tokenInput.placeholder = "lnl_pat_…"
        tokenInput.spellcheck = false
        tokenInput.autocomplete = "off"
        tokenRow.appendChild(tokenInput)
        lateinit var showButton: HTMLButtonElement
        showButton = button("Show") {
            val hidden = tokenInput.type == "password"
            tokenInput.type = if (hidden) "text" else "password"
            showButton.textContent = if (hidden) "Hide" else "Show"
        }
        tokenRow.appendChild(showButton)
        val save = {
            val v = tokenInput.value.trim()
            if (v.isNotEmpty()) {
                state.replacing.remove(c.id)
                state.tests.remove(c.id)
                change(c.id) {
                    val snapshot = service.updateConnection(c.id, token = v)
                    // Refused: keep the field open to try again.
                    if (snapshot.error != null) state.replacing.add(c.id)
                    snapshot
                }
            }
        }
        tokenRow.appendChild(button("Save") { save() })
        tokenRow.appendChild(button("Cancel") { state.replacing.remove(c.id); rerender() })
        tokenInput.addEventListener("keydown", { e ->
            when ((e as KeyboardEvent).key) {
                "Enter" -> save()
                "Escape" -> { e.stopPropagation(); state.replacing.remove(c.id); rerender() }
            }
        })
        kotlinx.browser.window.setTimeout({ tokenInput.focus() }, 0)
    } else {
        val masked = if (c.hasToken) "${c.tokenHint}${"•".repeat(16)}" else "No token yet"
        tokenRow.appendChild(el("code", "lunarbor-mcp-key" + if (c.hasToken) "" else " lunarbor-lunicle-none", masked))
        tokenRow.appendChild(button(if (c.hasToken) "Replace…" else "Add token…") { state.replacing.add(c.id); rerender() })
    }
    card.appendChild(tokenRow)

    // Test.
    val testRow = el("div", "lunarbor-mcp-row")
    testRow.appendChild(el("span", "lunarbor-mcp-label", ""))
    val testButton = button("Test") {
        state.tests[c.id] = "Testing…" to false
        rerender()
        scope.launch {
            state.tests[c.id] = when (val r = service.testConnection(c.id)) {
                is LunicleResult.Ok -> {
                    val me = r.value
                    val scopeText = if (me.isReadOnly) "read-only" else "read-write"
                    val tokenName = me.tokenName.takeIf { it.isNotBlank() }?.let { " “$it”" }.orEmpty()
                    "Works: $scopeText token$tokenName of ${me.userName}." to false
                }
                is LunicleResult.Failure -> r.error.message to true
            }
            rerender()
        }
    }
    testButton.disabled = !c.hasToken
    testButton.title = if (c.hasToken) "Ask Lunicle whose token this is" else "Add a token first"
    testRow.appendChild(testButton)
    state.tests[c.id]?.let { (text, isError) ->
        testRow.appendChild(el("span", "lunarbor-lunicle-test" + if (isError) " is-error" else "", text))
    }
    card.appendChild(testRow)

    card.appendChild(el("p", "lunarbor-mcp-help", "In a bullet: {{lunicle: ${c.name}/KEY}}, where KEY is the project's issue prefix."))
    return card
}

private fun textInput(value: String, className: String): HTMLInputElement =
    (document.createElement("input") as HTMLInputElement).also {
        it.type = "text"
        it.className = className
        it.value = value
        it.spellcheck = false
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

private fun ensureLunicleStyles() {
    if (document.getElementById("lunarbor-lunicle-style") != null) return
    val style = document.createElement("style") as HTMLElement
    style.id = "lunarbor-lunicle-style"
    style.textContent = LUNICLE_SETTINGS_CSS
    document.head?.appendChild(style)
}

private const val LUNICLE_SETTINGS_CSS = """
.lunarbor-lunicle-input {
    flex: 1; min-width: 0; font: inherit; font-size: 12px; color: inherit;
    background: transparent; border: 1px solid var(--t-border, rgba(255,255,255,0.12)); border-radius: 6px; padding: 3px 6px;
}
.lunarbor-lunicle-input:focus { outline: none; border-color: var(--t-accent); }
.lunarbor-lunicle-none { color: var(--t-text-dim, #9a9a9a); font-family: inherit; }
.lunarbor-lunicle-test { flex: 1 1 200px; font-size: 12px; color: var(--t-text-dim, #9a9a9a); }
.lunarbor-lunicle-test.is-error { color: var(--t-danger, #e5534b); }
.lunarbor-lunicle-summary { font-family: inherit; }
"""
