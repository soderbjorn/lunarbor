/* AppSettingsContent.kt (jsMain)
 *
 * Lunarbor's bodies for two toolkit right-sidebars, modelled on lunamux's
 * `AppSettingsContent.kt` / `HotkeysSidebarContent.kt`:
 *
 *  - **App settings** (the topbar gear, `AppShellSpec.appSettingsContent`):
 *     1. Jump buttons to Themes, Appearance and Keyboard Shortcuts. Themes /
 *        Appearance click the toolkit's own topbar buttons, so they behave
 *        exactly like those (including closing this sidebar); Keyboard
 *        Shortcuts opens the hotkeys sidebar below.
 *     2. A **Vault** section (Electron only — it needs the `noteApi` vault
 *        bridge): the current vault path and a Change… button that opens a
 *        native folder picker. The button is disabled while any edit is not
 *        yet on disk ([VaultSectionState.hasUnsavedEdits]); the shell keeps
 *        it live via [refreshVaultSectionState]. Applying a new folder is
 *        [AppSettingsHandlers.switchVault] — this file has no vault logic.
 *     3. A **Backup** section (Electron only): backup folder, Back up now,
 *        automatic interval and last backup — see BackupSettings.kt.
 *     4. An **Agent access** section (Electron only): the MCP server's
 *        switch, key and setup instructions — see AgentAccessSettings.kt.
 *     5. An **Experimental** section: "Enable 3D mode" (off by default;
 *        `SpaceMode.setEnabled`).
 *  - **Keyboard shortcuts** (`AppShellSpec.hotkeysContent`): the curated
 *    [lunarborHotkeysSpec] list, grouped, with keycap chords. Replaces the
 *    old cheatsheet modal; Cmd-/ and `Lunarbor → Hotkeys…` open it.
 *
 * Every setting here is local: the vault path lives in the Electron main
 * process's `lunarbor.json`, the MCP settings in its `lunarbor-mcp.json`,
 * the backup settings in its `lunarbor-backup.json`.
 *
 * Platform view code only — builds DOM, delegates every action. */
package se.soderbjorn.lunarbor.main

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import se.soderbjorn.lunula.web.hotkey.openHotkeyConfigDialog
import kotlin.js.Promise

/**
 * What the App settings body delegates to. Supplied by [AppShell].
 *
 * @property scope Scope the Change… flow runs in.
 * @property openHotkeys Opens the keyboard-shortcuts sidebar.
 * @property hasUnsavedEdits Whether any open document holds edits not yet
 *   on disk (`DocumentRegistry.unsavedFilesFlow`), read when the body is
 *   built and again right before switching.
 * @property switchVault Moves the app to the vault at the given absolute
 *   path (`AppShell.switchVault`). Returns an error message, or `null` when
 *   the window is reopening against the new vault.
 * @property flushEdits Saves every open document (`DocumentRegistry.flushAll`);
 *   "Back up now" calls it before zipping the vault.
 * @property privacyModes The vault's privacy modes (`DocumentRegistry.privacyFlow`),
 *   offered as Agent access connections' privacy scopes.
 * @property spaceModeEnabled Whether 3D mode is turned on
 *   (`isSpaceModeEnabled`), read when the body is built.
 * @property setSpaceModeEnabled Turns 3D mode on or off (`SpaceMode.setEnabled`).
 */
class AppSettingsHandlers(
    val scope: CoroutineScope,
    val openHotkeys: () -> Unit,
    val hasUnsavedEdits: () -> Boolean,
    val switchVault: suspend (String) -> String?,
    val flushEdits: suspend () -> Unit,
    val privacyModes: () -> List<se.soderbjorn.lunarbor.data.PrivacyMode> = { emptyList() },
    val spaceModeEnabled: () -> Boolean = { false },
    val setSpaceModeEnabled: (Boolean) -> Unit = {},
)

/**
 * Build the body the toolkit mounts inside the App settings sidebar.
 * Invoked on every open, so it reflects the current vault.
 *
 * Called by `AppShellSpec.appSettingsContent` in [AppShell.render].
 */
fun buildAppSettingsContent(handlers: AppSettingsHandlers): HTMLElement {
    ensureAppSettingsStyles()
    val body = div("lunarbor-app-settings-body")
    val nav = div("lunarbor-app-settings-nav")
    nav.appendChild(navButton("Themes", ICON_THEMES) { clickTopbarButton("Theme manager") })
    nav.appendChild(navButton("Appearance", ICON_APPEARANCE) { clickTopbarButton("Appearance") })
    nav.appendChild(navButton("Keyboard Shortcuts", ICON_HOTKEYS) { handlers.openHotkeys() })
    body.appendChild(nav)
    if (vaultBridge() != null) body.appendChild(buildVaultSection(handlers))
    if (backupBridge() != null) body.appendChild(buildBackupSection(handlers.scope, handlers.flushEdits))
    if (mcpBridge() != null) body.appendChild(buildAgentAccessSection(handlers.scope, handlers.privacyModes))
    body.appendChild(buildExperimentalSection(handlers))
    return body
}

/**
 * The Experimental section: the "Enable 3D mode" switch, like Lunamux's
 * "Enable 3D app switcher". Applies at once — the topbar cube, ⌃⌘3 / ⌃⌘1
 * and their Keyboard Shortcuts rows follow it.
 */
private fun buildExperimentalSection(handlers: AppSettingsHandlers): HTMLElement {
    val section = document.createElement("section") as HTMLElement
    section.className = "lunarbor-app-settings-section"
    val title = document.createElement("h3") as HTMLElement
    title.className = "lunarbor-app-settings-section-title"
    title.textContent = "Experimental"
    section.appendChild(title)

    val row = document.createElement("label") as HTMLElement
    row.className = "lunarbor-app-settings-toggle"
    val box = document.createElement("input") as HTMLInputElement
    box.type = "checkbox"
    box.checked = handlers.spaceModeEnabled()
    box.addEventListener("change", { _: Event -> handlers.setSpaceModeEnabled(box.checked) })
    row.appendChild(box)
    val text = document.createElement("span") as HTMLElement
    text.textContent = "Enable 3D mode"
    row.appendChild(text)
    section.appendChild(row)
    return section
}

/**
 * The Vault section: the vault path, a Change… button and a note line.
 * The path is filled in asynchronously from `noteApi.getVault`.
 */
private fun buildVaultSection(handlers: AppSettingsHandlers): HTMLElement {
    val section = document.createElement("section") as HTMLElement
    section.className = "lunarbor-app-settings-section"
    val title = document.createElement("h3") as HTMLElement
    title.className = "lunarbor-app-settings-section-title"
    title.textContent = "Vault"
    section.appendChild(title)

    val row = div("lunarbor-vault-row")
    // Right-to-left box so a long path clips at its start (the folder
    // name stays visible); the text itself sits in a left-to-right
    // isolate so its slashes stay put.
    val path = div("lunarbor-vault-path")
    val pathText = document.createElement("span") as HTMLElement
    pathText.className = "lunarbor-vault-path-text"
    pathText.textContent = "…"
    path.appendChild(pathText)
    row.appendChild(path)
    val change = document.createElement("button") as HTMLButtonElement
    change.type = "button"
    change.className = "lunarbor-vault-change"
    change.textContent = "Change…"
    row.appendChild(change)
    section.appendChild(row)

    val note = div("lunarbor-vault-note")
    section.appendChild(note)

    var locked = false
    var busy = false
    fun sync() = refreshVaultSectionState(section, VaultSectionState(handlers.hasUnsavedEdits(), locked, busy))
    sync()

    handlers.scope.launch {
        val info: dynamic = (vaultBridge().getVault() as Promise<dynamic>).await()
        pathText.textContent = info.path as String
        path.title = info.path as String
        locked = info.locked == true
        sync()
    }

    change.addEventListener("click", { _: Event ->
        if (busy || locked || handlers.hasUnsavedEdits()) return@addEventListener
        busy = true
        sync()
        handlers.scope.launch {
            val picked = (vaultBridge().chooseVaultFolder() as Promise<String?>).await()
            val error = when {
                picked == null -> null
                handlers.hasUnsavedEdits() -> "Edits are still being saved — try again in a moment."
                else -> handlers.switchVault(picked)
            }
            busy = false
            sync()
            if (error != null) {
                note.textContent = error
                note.classList.add("is-error")
            }
        }
    })
    return section
}

/**
 * The state the Vault section's button and note reflect.
 *
 * @property hasUnsavedEdits Edits are pending: the button is disabled.
 * @property locked `LUNARBOR_VAULT` pins the vault: the button is hidden.
 * @property busy A pick / switch is in flight.
 */
data class VaultSectionState(val hasUnsavedEdits: Boolean, val locked: Boolean, val busy: Boolean)

/**
 * Applies [state] to a Vault [section]. Called when the section is built
 * and by the shell whenever the unsaved-edits state changes while the
 * sidebar is open ([refreshOpenVaultSection]).
 */
fun refreshVaultSectionState(section: HTMLElement, state: VaultSectionState) {
    section.asDynamic().lunarborVaultState = state
    val button = section.querySelector(".lunarbor-vault-change") as? HTMLButtonElement ?: return
    val note = section.querySelector(".lunarbor-vault-note") as? HTMLElement ?: return
    button.hidden = state.locked
    button.disabled = state.busy || state.hasUnsavedEdits
    if (note.classList.contains("is-error") && !state.busy) return
    note.textContent = when {
        state.locked -> "Set by LUNARBOR_VAULT for this run."
        state.hasUnsavedEdits -> "Waiting for edits to be saved…"
        else -> "Windows outside the new vault close."
    }
}

/**
 * Re-applies the unsaved-edits flag to the Vault section if the App
 * settings sidebar is open. Called by [AppShell] from its collector of
 * `DocumentRegistry.unsavedFilesFlow`.
 */
fun refreshOpenVaultSection(hasUnsavedEdits: Boolean) {
    val button = document.querySelector(".lunarbor-vault-change") ?: return
    val section = button.closest(".lunarbor-app-settings-section") as? HTMLElement ?: return
    val previous = section.asDynamic().lunarborVaultState as? VaultSectionState ?: return
    refreshVaultSectionState(section, previous.copy(hasUnsavedEdits = hasUnsavedEdits))
}

/**
 * Build the body of the keyboard-shortcuts sidebar from
 * [lunarborHotkeysSpec]. Invoked on every open.
 *
 * Called by `AppShellSpec.hotkeysContent` in [AppShell.render].
 */
fun buildHotkeysSidebarContent(): HTMLElement {
    ensureAppSettingsStyles()
    val spec = lunarborHotkeysSpec()
    val body = div("lunarbor-hotkeys-body")
    for (group in spec.groups) {
        val section = div("lunarbor-hotkeys-group")
        val title = document.createElement("h3") as HTMLElement
        title.className = "lunarbor-hotkeys-group-title"
        title.textContent = group.title
        section.appendChild(title)
        for (entry in group.entries) {
            val row = div("lunarbor-hotkeys-row")
            val label = div("lunarbor-hotkeys-label")
            label.textContent = entry.label
            row.appendChild(label)
            val chord = div("lunarbor-hotkeys-chord")
            fun fillCaps(caps: List<String>) {
                chord.innerHTML = ""
                for (cap in caps) {
                    val kbd = document.createElement("kbd") as HTMLElement
                    kbd.className = "lunarbor-hotkeys-cap"
                    kbd.textContent = cap
                    chord.appendChild(kbd)
                }
            }
            fillCaps(entry.chord)
            row.appendChild(chord)
            // A configurable action: clicking the row opens the toolkit's
            // binding editor, and the caps follow a saved change.
            lunarborConfigurableHotkeys[entry.label]?.let { actionId ->
                row.classList.add("is-configurable")
                row.title = "Click to change this shortcut"
                row.addEventListener("click", { _ ->
                    openHotkeyConfigDialog(actionId, entry.label) { fillCaps(effectiveChordLabel(actionId)) }
                })
            }
            section.appendChild(row)
        }
        body.appendChild(section)
    }
    spec.footerNote?.let {
        val foot = div("lunarbor-hotkeys-note")
        foot.textContent = it
        body.appendChild(foot)
    }
    return body
}

/** `noteApi` when it carries the vault bridge (Electron), else `null`. */
private fun vaultBridge(): dynamic {
    val api = js("globalThis.noteApi")
    if (api == null || js("typeof api.getVault !== 'function'") as Boolean) return null
    return api
}

/**
 * Clicks the toolkit topbar button whose tooltip is [title], so the jump
 * behaves exactly like the toolbar icon — including the toolkit's mutual
 * exclusion, which closes this sidebar first.
 */
private fun clickTopbarButton(title: String) {
    (document.querySelector(".dt-topbar-icon-button[title=\"$title\"]") as? HTMLElement)?.click()
}

private fun navButton(label: String, iconHtml: String, onClick: () -> Unit): HTMLElement {
    val button = document.createElement("button") as HTMLButtonElement
    button.type = "button"
    button.className = "lunarbor-app-settings-nav-button"
    button.innerHTML = iconHtml
    val span = document.createElement("span") as HTMLElement
    span.className = "lunarbor-app-settings-nav-label"
    span.textContent = label
    button.appendChild(span)
    button.addEventListener("click", { _: Event -> onClick() })
    return button
}

private fun div(className: String): HTMLElement =
    (document.createElement("div") as HTMLElement).also { it.className = className }

/** Injects the stylesheet for both sidebar bodies once. */
private fun ensureAppSettingsStyles() {
    if (document.getElementById("lunarbor-app-settings-style") != null) return
    val style = document.createElement("style") as HTMLElement
    style.id = "lunarbor-app-settings-style"
    style.textContent = APP_SETTINGS_CSS
    document.head?.appendChild(style)
}

/** Palette glyph for the "Themes" button (same as lunamux's). */
private const val ICON_THEMES =
    """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="13.5" cy="6.5" r="1.2"/><circle cx="17.5" cy="10.5" r="1.2"/><circle cx="8.5" cy="7.5" r="1.2"/><circle cx="6.5" cy="12.5" r="1.2"/><path d="M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10c1.7 0 3-1.3 3-3 0-.8-.3-1.5-.8-2-.5-.5-.8-1.2-.8-2 0-1.7 1.3-3 3-3h2c2.2 0 4-1.8 4-4 0-4.4-4.5-8-10-8z"/></svg>"""

/** "Aa" glyph for the "Appearance" button — the toolkit topbar's own mark. */
private const val ICON_APPEARANCE =
    """<svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor" aria-hidden="true"><text x="1" y="18" font-family="-apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif" font-size="14" font-weight="700" letter-spacing="-0.5">A</text><text x="12" y="18" font-family="-apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif" font-size="10" font-weight="500" letter-spacing="-0.3">a</text></svg>"""

/** Keyboard glyph for the "Keyboard Shortcuts" button. */
private const val ICON_HOTKEYS =
    """<svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="2" y="6" width="20" height="12" rx="2"/><line x1="6" y1="10" x2="6" y2="10"/><line x1="10" y1="10" x2="10" y2="10"/><line x1="14" y1="10" x2="14" y2="10"/><line x1="18" y1="10" x2="18" y2="10"/><line x1="8" y1="14" x2="16" y2="14"/></svg>"""

/** Styles for both bodies; mirrors lunamux's `.lunamux-app-settings-*` / `.lunamux-hotkeys-*`. */
private const val APP_SETTINGS_CSS = """
.lunarbor-app-settings-body, .lunarbor-hotkeys-body {
    display: flex; flex-direction: column; gap: 18px;
    padding: 16px 18px 24px;
    color: var(--t-text, #e6e6e6); font-size: 13px; line-height: 1.4;
}
.lunarbor-app-settings-nav { display: flex; flex-direction: column; gap: 8px; }
.lunarbor-app-settings-nav-button {
    display: inline-flex; align-items: center; gap: 10px; width: 100%;
    padding: 10px 12px; font: inherit; font-weight: 600; text-align: left;
    color: var(--t-text, #e6e6e6); background: transparent;
    border: 1px solid var(--t-border, rgba(255,255,255,0.12)); border-radius: 8px;
    cursor: pointer; transition: background 120ms ease, border-color 120ms ease, transform 60ms ease;
}
.lunarbor-app-settings-nav-button > svg { flex: 0 0 auto; }
.lunarbor-app-settings-nav-label { flex: 1; }
.lunarbor-app-settings-nav-button:hover,
.lunarbor-vault-change:hover:not(:disabled) {
    background: color-mix(in srgb, var(--t-accent) 14%, var(--t-surface));
    border-color: color-mix(in srgb, var(--t-accent) 40%, var(--t-border, rgba(255,255,255,0.12)));
}
.lunarbor-app-settings-nav-button:active { transform: translateY(1px); }
.lunarbor-app-settings-nav-button:focus-visible,
.lunarbor-vault-change:focus-visible { outline: 2px solid var(--t-accent); outline-offset: 2px; }
.lunarbor-app-settings-section {
    display: flex; flex-direction: column; gap: 8px; padding: 10px 12px 12px;
    border: 1px solid var(--t-border, rgba(255,255,255,0.12)); border-radius: 10px;
}
.lunarbor-app-settings-section-title, .lunarbor-hotkeys-group-title {
    margin: 4px 0 2px; font-size: 11px; font-weight: 600; line-height: 1.2;
    letter-spacing: 0.08em; text-transform: uppercase; color: var(--t-text-dim, #9a9a9a);
}
.lunarbor-app-settings-toggle { display: flex; align-items: center; gap: 8px; cursor: pointer; font-weight: 600; }
.lunarbor-app-settings-toggle input { margin: 0; accent-color: var(--t-accent); }
.lunarbor-vault-row { display: flex; align-items: center; gap: 10px; }
.lunarbor-vault-path {
    flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    direction: rtl; text-align: left;
    font-family: var(--t-font-mono, ui-monospace, Menlo, monospace); font-size: 12px;
}
.lunarbor-vault-path-text { direction: ltr; unicode-bidi: isolate; }
.lunarbor-vault-change {
    flex: 0 0 auto; padding: 6px 12px; font: inherit; font-weight: 600;
    color: var(--t-text, #e6e6e6); background: transparent;
    border: 1px solid var(--t-border, rgba(255,255,255,0.12)); border-radius: 8px; cursor: pointer;
}
.lunarbor-vault-change:disabled { opacity: 0.45; cursor: default; }
.lunarbor-vault-note { font-size: 12px; color: var(--t-text-dim, #9a9a9a); }
.lunarbor-vault-note.is-error { color: var(--t-danger, #e5534b); }
body.appearance-light .lunarbor-app-settings-nav-button,
body.appearance-light .lunarbor-app-settings-section,
body.appearance-light .lunarbor-vault-change { border-color: rgba(0,0,0,0.10); }
.lunarbor-hotkeys-group { display: flex; flex-direction: column; }
.lunarbor-hotkeys-row {
    display: flex; align-items: flex-start; justify-content: space-between; gap: 12px;
    padding: 7px 2px;
    border-bottom: 1px solid color-mix(in srgb, var(--t-border, rgba(255,255,255,0.12)) 55%, transparent);
}
.lunarbor-hotkeys-row:last-child { border-bottom: none; }
.lunarbor-hotkeys-row.is-configurable { cursor: pointer; border-radius: 6px; }
.lunarbor-hotkeys-row.is-configurable:hover { background: color-mix(in srgb, var(--t-text, #e6e6e6) 6%, transparent); }
.lunarbor-hotkeys-label { flex: 1; min-width: 0; padding-top: 3px; }
.lunarbor-hotkeys-chord { display: inline-flex; gap: 4px; align-items: center; flex-wrap: wrap; justify-content: flex-end; flex: 0 0 auto; }
.lunarbor-hotkeys-cap {
    display: inline-flex; align-items: center; justify-content: center;
    min-width: 22px; padding: 2px 6px;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
    font-size: 11px; line-height: 1; color: var(--t-text, #e6e6e6);
    background: color-mix(in srgb, var(--t-text, #e6e6e6) 8%, transparent);
    border: 1px solid color-mix(in srgb, var(--t-text, #e6e6e6) 18%, transparent);
    border-bottom-width: 2px; border-radius: 4px;
}
body.appearance-light .lunarbor-hotkeys-cap { background: rgba(0,0,0,0.05); border-color: rgba(0,0,0,0.16); }
.lunarbor-hotkeys-note { font-size: 12px; color: var(--t-text-dim, #9a9a9a); }
"""
