/* PrivacyDialog.kt (jsMain)
 *
 * The "Configure privacy" dialog (LBR-10), opened by the palette command —
 * the only place the current mode shows: nothing in the app's chrome tells
 * an onlooker a mode is on. Like the Agent access dialog, every change applies
 * and is saved at once — no Save / Cancel — and confirmations are asked
 * inside the card.
 *
 *  - **Current mode**: "No privacy", then each mode; picking one applies it
 *    to the whole app at once ([DocumentRegistry.setPrivacyMode]).
 *  - **Modes** (only under "No privacy", [PrivacyConfig.canEditModes] — the
 *    cards would show what a mode hides): one card per mode with its name
 *    (edited in place; Enter or leaving the field commits, an empty or taken
 *    name goes back), its tags as chips in their outline colour ([tagHue])
 *    with ×, an "add tag" field with the vault's tags as suggestions (as in
 *    the pane search: ↑ / ↓ / Enter / Tab / Escape), a delete button
 *    (confirmed in the card, naming agent connections that use the mode),
 *    and which agent connections are scoped to it. "+ Add mode" adds one.
 *  - With a mode on, the cards are replaced by one line; switching to "No
 *    privacy" unlocks the dialog in place.
 *
 * The modes live in the vault's `_privacy.config`, written by
 * [DocumentRegistry.setPrivacyModes]; the current mode is persisted by
 * `AppShell`. Reuses the Agent access dialog's look (`lunarbor-mcp-*`).
 *
 * Platform view code only — builds DOM, delegates every change. */
package se.soderbjorn.lunarbor.main

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.lunarbor.data.PrivacyConfig
import se.soderbjorn.lunarbor.data.PrivacyMode
import se.soderbjorn.lunarbor.data.TagCount
import se.soderbjorn.lunarbor.data.TextScope
import se.soderbjorn.lunarbor.main.DocumentRegistry

/**
 * Opens the privacy dialog (one at a time). Escape (outside a text field),
 * the ×, Done and a click outside close it.
 *
 * Called by the "Configure privacy" palette command.
 *
 * @param scope Scope the registry calls run in.
 * @param registry The app's registry: the modes and the current mode.
 */
internal fun openPrivacyDialog(scope: CoroutineScope, registry: DocumentRegistry) {
    ensureAgentAccessStyles()
    ensureSearchBarStyles()
    ensurePrivacyStyles()
    if (document.querySelector(".lunarbor-privacy-dialog") != null) return
    val backdrop = el("div", "lunarbor-mcp-backdrop")
    val panel = el("div", "lunarbor-mcp-dialog lunarbor-privacy-dialog")
    panel.setAttribute("role", "dialog")
    panel.setAttribute("aria-label", "Privacy")
    backdrop.appendChild(panel)

    val header = el("div", "lunarbor-mcp-dialog-head")
    header.appendChild(el("h2", "lunarbor-mcp-dialog-title", "Privacy"))
    lateinit var close: () -> Unit
    val closeButton = button("×") { close() }
    closeButton.classList.add("lunarbor-mcp-close")
    closeButton.title = "Close"
    header.appendChild(closeButton)
    panel.appendChild(header)
    val body = el("div", "lunarbor-mcp-dialog-body")
    panel.appendChild(body)

    val tagMenu = TagMenu()
    // Agent connections by privacy mode id, read once (Electron only).
    var connectionsByMode: Map<String, List<String>> = emptyMap()
    // Mode ids whose delete confirmation is showing.
    val confirming = HashSet<String>()
    // A field to focus after the next render: mode id + which field.
    var focusAfter: Pair<String, String>? = null
    var watch: Job? = null

    val keyHandler: (Event) -> Unit = handler@{ e ->
        val ke = e as KeyboardEvent
        if (ke.key != "Escape") return@handler
        val active = document.activeElement
        if (active is HTMLInputElement && active.type == "text" && panel.contains(active)) {
            // In a field Escape closes the suggestions, else leaves the field.
            ke.preventDefault()
            ke.stopPropagation()
            if (!tagMenu.hide()) active.blur()
            return@handler
        }
        ke.preventDefault()
        ke.stopPropagation()
        close()
    }
    close = {
        document.removeEventListener("keydown", keyHandler, true)
        watch?.cancel()
        tagMenu.hide()
        backdrop.remove()
    }
    document.addEventListener("keydown", keyHandler, true)
    backdrop.addEventListener("mousedown", { e -> if (e.target === backdrop) close() })
    document.body?.appendChild(backdrop)

    fun save(modes: List<PrivacyMode>) {
        scope.launch { registry.setPrivacyModes(modes) }
    }

    lateinit var render: () -> Unit

    fun modeCard(mode: PrivacyMode, modes: List<PrivacyMode>): HTMLElement {
        val card = el("div", "lunarbor-mcp-card")
        val head = el("div", "lunarbor-mcp-card-head")
        val name = document.createElement("input") as HTMLInputElement
        name.type = "text"
        name.className = "lunarbor-mcp-name"
        name.value = mode.name
        name.spellcheck = false
        name.title = "The mode's name"
        name.setAttribute("data-privacy-field", "name:${mode.id}")
        fun commitName() {
            val next = PrivacyConfig.renamed(registry.privacyFlow.value.modes, mode.id, name.value)
            if (next == null || name.value.trim() == mode.name) name.value = mode.name else save(next)
        }
        name.addEventListener("change", { _: Event -> commitName() })
        name.addEventListener("keydown", { e -> if ((e as KeyboardEvent).key == "Enter") name.blur() })
        head.appendChild(name)
        val delete = button("Delete…") {
            confirming += mode.id
            render()
        }
        delete.title = "Delete this mode"
        head.appendChild(delete)
        card.appendChild(head)

        if (mode.id in confirming) {
            val users = connectionsByMode[mode.id].orEmpty()
            val confirm = el("div", "lunarbor-mcp-confirm")
            val text = "Delete “${mode.name}”?" + if (users.isEmpty()) "" else
                " Agent connection${if (users.size == 1) "" else "s"} ${users.joinToString { "“$it”" }} will be turned " +
                    "off until you choose another privacy scope for ${if (users.size == 1) "it" else "them"}."
            confirm.appendChild(el("span", "lunarbor-mcp-confirm-text", text))
            val yes = button("Delete") {
                confirming -= mode.id
                save(registry.privacyFlow.value.modes.filterNot { it.id == mode.id })
            }
            yes.classList.add("is-danger")
            confirm.appendChild(yes)
            confirm.appendChild(button("Cancel") {
                confirming -= mode.id
                render()
            })
            card.appendChild(confirm)
        }

        val tagsRow = el("div", "lunarbor-mcp-row lunarbor-privacy-tags")
        tagsRow.appendChild(el("span", "lunarbor-mcp-label", "Hides"))
        val chips = el("div", "lunarbor-privacy-chips")
        for (tag in mode.tags) {
            val chip = el("span", "lunarbor-md-tag lunarbor-privacy-chip", "#$tag")
            chip.style.setProperty("--tag-h", tagHue(tag).toString())
            val x = document.createElement("button") as HTMLButtonElement
            x.type = "button"
            x.className = "lunarbor-privacy-chip-x"
            x.textContent = "×"
            x.title = "Remove #$tag"
            x.addEventListener("click", { _: Event ->
                save(PrivacyConfig.withoutTag(registry.privacyFlow.value.modes, mode.id, tag))
            })
            chip.appendChild(x)
            chips.appendChild(chip)
        }
        val add = document.createElement("input") as HTMLInputElement
        add.type = "text"
        add.className = "lunarbor-privacy-add"
        add.placeholder = "+ add tag…"
        add.spellcheck = false
        add.setAttribute("data-privacy-field", "tag:${mode.id}")
        fun addTag(tag: String) {
            val next = PrivacyConfig.withTag(registry.privacyFlow.value.modes, mode.id, tag)
            tagMenu.hide()
            add.value = ""
            focusAfter = mode.id to "tag"
            if (next != registry.privacyFlow.value.modes) save(next) else render()
        }
        fun suggest() {
            val typed = PrivacyConfig.cleanTag(add.value)
            val have = mode.tags.map(PrivacyConfig::tagKey).toSet()
            val options = if (add.value.isBlank() || !registry.textIndex.isBuilt) emptyList()
            else registry.textIndex.tags(TextScope.Tree(""), typed, 12)
                .filter { PrivacyConfig.tagKey(it.tag) !in have }
                .take(8)
            tagMenu.show(add, options) { addTag(it.tag) }
        }
        add.addEventListener("input", { suggest() })
        add.addEventListener("blur", { tagMenu.hide() })
        add.addEventListener("keydown", { e ->
            val ke = e as KeyboardEvent
            if (tagMenu.handleKey(ke)) return@addEventListener
            if (ke.key == "Enter") {
                ke.preventDefault()
                addTag(add.value)
            }
        })
        chips.appendChild(add)
        tagsRow.appendChild(chips)
        card.appendChild(tagsRow)

        connectionsByMode[mode.id]?.takeIf { it.isNotEmpty() }?.let { users ->
            card.appendChild(el("div", "lunarbor-mcp-hint", "Used by agent connections: ${users.joinToString(", ")}"))
        }
        return card
    }

    render = render@{
        val view = registry.privacyFlow.value
        val modes = view.modes
        val active = document.activeElement as? HTMLInputElement
        val keep = active?.getAttribute("data-privacy-field")
        val keptValue = active?.value
        tagMenu.hide()
        body.innerHTML = ""
        body.appendChild(
            el(
                "p", "lunarbor-mcp-intro",
                "A privacy mode hides every item carrying one of its tags — with everything under it — from view, " +
                    "search and agents, so you can show your notes without showing everything. It only hides: " +
                    "nothing on disk changes, and switching back needs no password.",
            ),
        )
        body.appendChild(el("div", "lunarbor-privacy-section", "Current mode"))
        val picker = el("div", "lunarbor-privacy-picker")
        fun radio(id: String?, label: String) {
            val row = el("label", "lunarbor-mcp-toggle lunarbor-privacy-radio")
            val input = document.createElement("input") as HTMLInputElement
            input.type = "radio"
            input.name = "lunarbor-privacy-mode"
            input.checked = view.currentId == id
            input.addEventListener("change", { _: Event ->
                if (input.checked) scope.launch { registry.setPrivacyMode(id) }
            })
            row.appendChild(input)
            row.appendChild(el("span", "lunarbor-mcp-toggle-text", label))
            picker.appendChild(row)
        }
        radio(null, "No privacy")
        for (m in modes) radio(m.id, m.name)
        body.appendChild(picker)

        if (!PrivacyConfig.canEditModes(view.currentId)) {
            body.appendChild(el("p", "lunarbor-mcp-note", "Switch to No privacy to edit modes."))
        } else {
            body.appendChild(el("div", "lunarbor-privacy-section", "Modes"))
            val list = el("div", "lunarbor-mcp-connections")
            for (m in modes) list.appendChild(modeCard(m, modes))
            if (modes.isEmpty()) list.appendChild(el("p", "lunarbor-mcp-note", "No modes yet. Add one, then the tags it hides."))
            body.appendChild(list)
        }

        val footer = el("div", "lunarbor-mcp-footer lunarbor-privacy-footer")
        if (PrivacyConfig.canEditModes(view.currentId)) {
            footer.appendChild(button("+ Add mode") {
                val (next, added) = PrivacyConfig.withNewMode(registry.privacyFlow.value.modes)
                focusAfter = added.id to "name"
                save(next)
            })
        }
        footer.appendChild(el("span", "lunarbor-privacy-spacer"))
        footer.appendChild(button("Done") { close() })
        body.appendChild(footer)

        // Put the caret back where it was, or where an action asked.
        val want = focusAfter?.let { (id, field) -> "$field:$id" } ?: keep
        focusAfter = null
        if (want != null) {
            (panel.querySelector("[data-privacy-field=\"$want\"]") as? HTMLInputElement)?.let { input ->
                // Text being typed survives a repaint from elsewhere.
                if (want == keep && keptValue != null) input.value = keptValue
                input.focus()
                if (want.startsWith("name:") && keep != want) input.select()
            }
        }
    }

    render()
    // Live: a mode picked, saved or changed outside the dialog repaints it.
    watch = scope.launch {
        registry.privacyFlow.map { it.modes to it.currentId }.distinctUntilChanged().collect { render() }
    }
    // Tag suggestions need the text index; connections need the main process.
    scope.launch { registry.textIndex.ensureBuilt() }
    if (mcpBridge() != null) {
        scope.launch {
            connectionsByMode = fetchStatus().connections.filter { it.privacy.isNotEmpty() }
                .groupBy({ it.privacy }, { it.name })
            render()
        }
    }
}

/**
 * The tag suggestions under an "add tag" field — the pane search's
 * autocomplete look (`lunarbor-tag-menu`), on `<body>` while it shows.
 */
private class TagMenu {
    private val menu = el("div", "lunarbor-tag-menu lunarbor-privacy-tag-menu")
    private var options: List<TagCount> = emptyList()
    private var index = 0
    private var pick: (TagCount) -> Unit = {}

    init {
        menu.setAttribute("role", "listbox")
        // Rows act on mousedown, before the field's blur hides the menu.
        menu.addEventListener("mousedown", { e -> e.preventDefault() })
    }

    /** Shows [list] under [input] ([onPick] takes a row), or hides when empty. */
    fun show(input: HTMLInputElement, list: List<TagCount>, onPick: (TagCount) -> Unit) {
        if (list.isEmpty()) {
            hide()
            return
        }
        val previous = options.getOrNull(index)?.tag
        options = list
        pick = onPick
        index = list.indexOfFirst { it.tag == previous }.takeIf { it >= 0 } ?: 0
        menu.innerHTML = ""
        list.forEachIndexed { i, option ->
            val row = el("div", "lunarbor-tag-menu-item")
            row.appendChild(el("span", "lunarbor-tag-menu-name", option.tag))
            row.appendChild(el("span", "lunarbor-tag-menu-count", option.count.toString()))
            row.addEventListener("mouseenter", { highlight(i) })
            row.addEventListener("mousedown", { e ->
                e.preventDefault()
                onPick(option)
            })
            menu.appendChild(row)
        }
        highlight(index)
        if (menu.parentNode == null) document.body?.appendChild(menu)
        val box = input.getBoundingClientRect()
        menu.style.left = "${box.left}px"
        menu.style.top = "${box.bottom + 4}px"
    }

    /** Hides the menu; `true` when it was showing. */
    fun hide(): Boolean {
        val shown = menu.parentNode != null
        menu.remove()
        options = emptyList()
        return shown
    }

    /** ↑ / ↓ / Enter / Tab while the menu shows; `true` when it took the key. */
    fun handleKey(e: KeyboardEvent): Boolean {
        if (options.isEmpty() || e.metaKey || e.ctrlKey || e.altKey) return false
        when (e.key) {
            "ArrowDown", "ArrowUp" -> {
                val n = options.size
                highlight(((index + if (e.key == "ArrowDown") 1 else -1) % n + n) % n)
            }
            "Enter", "Tab" -> pick(options[index])
            else -> return false
        }
        e.preventDefault()
        e.stopPropagation()
        return true
    }

    private fun highlight(i: Int) {
        index = i
        val rows = menu.children
        for (k in 0 until rows.length) {
            val row = rows.item(k) as HTMLElement
            if (k == i) row.classList.add("lunarbor-tag-menu-item-on") else row.classList.remove("lunarbor-tag-menu-item-on")
        }
    }
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

private fun ensurePrivacyStyles() {
    if (document.getElementById("lunarbor-privacy-style") != null) return
    val style = document.createElement("style") as HTMLElement
    style.id = "lunarbor-privacy-style"
    style.textContent = PRIVACY_CSS
    document.head?.appendChild(style)
}

private const val PRIVACY_CSS = """
.lunarbor-privacy-section {
    font-size: 11px; font-weight: 700; letter-spacing: 0.06em; text-transform: uppercase;
    color: var(--t-text-dim, #9a9a9a); margin-top: 4px;
}
.lunarbor-privacy-picker { display: flex; flex-direction: column; gap: 6px; }
.lunarbor-privacy-radio .lunarbor-mcp-toggle-text { font-weight: 500; }
.lunarbor-privacy-tags { align-items: flex-start; }
.lunarbor-privacy-tags > .lunarbor-mcp-label { padding-top: 4px; }
.lunarbor-privacy-chips { flex: 1; min-width: 0; display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.lunarbor-privacy-chip { display: inline-flex; align-items: center; gap: 4px; line-height: 22px; }
.lunarbor-privacy-chip-x {
    border: none; background: transparent; color: inherit; cursor: pointer; padding: 0 0 0 2px;
    font: inherit; font-size: 14px; line-height: 1; opacity: 0.7;
}
.lunarbor-privacy-chip-x:hover { opacity: 1; }
.lunarbor-privacy-add {
    flex: 1 1 140px; min-width: 120px; font: inherit; font-size: 12px; color: inherit;
    background: transparent; border: 1px dashed var(--t-border, rgba(255,255,255,0.18)); border-radius: 6px; padding: 3px 8px;
}
.lunarbor-privacy-add:focus { outline: none; border-style: solid; border-color: var(--t-accent); }
.lunarbor-privacy-tag-menu { z-index: 2147483645; }
.lunarbor-privacy-footer { margin-top: 4px; }
.lunarbor-privacy-spacer { flex: 1; }
"""
