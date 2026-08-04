/*
 * SpaceSettingsModal.kt (jsMain)
 * ------------------------------
 * Metadata editor for the space containing the pane's active file,
 * opened by the command palette's "Space settings" command. Resolves
 * the space from the active file's path, loads its current AI opt-in
 * from the anchor frontmatter, and lets the user toggle it. Enter (or
 * clicking Save) persists; Escape cancels.
 *
 * Shares the `treefacts-palette-*` panel chrome and the AI checkbox row
 * (`buildSpaceAiCheckboxRow` in NewSpaceModal.kt) with the New space
 * modal. View-layer glue only — metadata semantics live in
 * `SpaceMetadata` (commonMain) behind
 * `PaneBackingViewModel.spaceAiAllowed` / `setSpaceAiAllowed`.
 */

package se.soderbjorn.treefacts.main

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent

/**
 * Per-pane space-settings modal. Same lifecycle as the pane's other
 * modals: lazily created by `AppShell.openSpaceSettingsModal`, reused
 * across opens, DOM built on [open] and torn down on [close].
 *
 * Opening resolves the pane's current space in two steps:
 * 1. [MainViewModel.currentSpaceAnchorFileRel] — purely syntactic (first
 *    path segment of the active file). `null` at the vault root.
 * 2. [MainViewModel.spaceAiAllowed] on that anchor — `null` when no
 *    anchor file exists (a foreign folder, e.g. viewing an image under
 *    `Images/`). Both misses render a "not inside a space" note instead
 *    of the editor, so the command never silently no-ops.
 *
 * @param parentScope Scope for the async load + save round-trips.
 * @param activePaneVmProvider Resolves the owning pane's [MainViewModel]
 *   at open/save time; `null` (pane closed meanwhile) no-ops.
 * @param onAfterPick Invoked after a successful save — the host focuses
 *   the pane's editor.
 */
internal class SpaceSettingsModal(
    private val parentScope: CoroutineScope,
    private val activePaneVmProvider: () -> MainViewModel?,
    private val onAfterPick: () -> Unit = {},
) {

    private var backdropEl: HTMLElement? = null
    private var panelEl: HTMLElement? = null
    private var aiCheckboxEl: HTMLInputElement? = null

    /** Anchor fileRel resolved at open time; null until loaded / when not a space. */
    private var anchorFileRel: String? = null

    /** Element that held focus before [open] was called; restored on close. */
    private var focusToRestore: HTMLElement? = null

    private var documentKeyHandler: ((Event) -> Unit)? = null

    /** Show the modal and kick off the async space resolution. Idempotent. */
    fun open() {
        if (backdropEl != null) return
        focusToRestore = document.activeElement as? HTMLElement
        buildShell()
        attachDocumentKeyHandler()
        val vm = activePaneVmProvider()
        val anchor = vm?.currentSpaceAnchorFileRel()
        if (vm == null || anchor == null) {
            renderNotASpace()
            return
        }
        parentScope.launch {
            val allowed = vm.spaceAiAllowed(anchor)
            // The user may have closed the modal (or the pane) while the
            // read was in flight — the panel element is the liveness token.
            if (panelEl == null) return@launch
            if (allowed == null) {
                renderNotASpace()
            } else {
                anchorFileRel = anchor
                renderEditor(anchor, allowed)
            }
        }
    }

    /** Hide the modal. Idempotent. Restores the previously-focused element. */
    fun close() {
        backdropEl?.let { it.parentNode?.removeChild(it) }
        backdropEl = null
        panelEl = null
        aiCheckboxEl = null
        anchorFileRel = null
        detachDocumentKeyHandler()
        focusToRestore?.focus()
        focusToRestore = null
    }

    /** Builds the backdrop + empty panel; content renders after resolution. */
    private fun buildShell() {
        val backdrop = document.createElement("div") as HTMLElement
        backdrop.className = "treefacts-palette-backdrop"
        backdrop.addEventListener("mousedown", { e ->
            if (e.target === backdrop) close()
        })
        val panel = document.createElement("div") as HTMLElement
        panel.className = "treefacts-palette-panel"
        panel.addEventListener("mousedown", { e ->
            (e as MouseEvent).stopPropagation()
        })
        panel.appendChild(buildNote("Loading…"))
        backdrop.appendChild(panel)
        document.body?.appendChild(backdrop)
        backdropEl = backdrop
        panelEl = panel
    }

    /** Replaces the panel content with the "not a space" explanation. */
    private fun renderNotASpace() {
        val panel = panelEl ?: return
        panel.innerHTML = ""
        panel.appendChild(buildTitleRow("Space settings"))
        panel.appendChild(
            buildNote(
                "The current file is not inside a space — space settings live on " +
                    "top-level trees. Create one with the New space command.",
            ),
        )
    }

    /**
     * Replaces the panel content with the editor: the space's name, the
     * shared AI checkbox row, and a Save button. Enter anywhere in the
     * panel saves too (the document-level handler only owns Escape).
     */
    private fun renderEditor(anchor: String, currentAllowed: Boolean) {
        val panel = panelEl ?: return
        panel.innerHTML = ""
        val spaceName = anchor.substringBefore('/')
        panel.appendChild(buildTitleRow("Space settings — $spaceName"))

        val (checkboxRow, checkbox) = buildSpaceAiCheckboxRow(initialChecked = currentAllowed)
        aiCheckboxEl = checkbox
        panel.appendChild(checkboxRow)

        // `.treefacts-modal-btn` (injected in AppShell's chrome styles)
        // carries the hover/active/focus affordances — the palette-item
        // class has none of those (its highlight is keyboard-driven).
        val save = document.createElement("button") as HTMLElement
        save.className = "treefacts-modal-btn"
        save.setAttribute("type", "button")
        save.textContent = "Save"
        save.addEventListener("mousedown", { e -> (e as MouseEvent).preventDefault() })
        save.addEventListener("click", { _ -> submit() })
        panel.appendChild(save)

        panel.addEventListener("keydown", { e ->
            val ke = e as KeyboardEvent
            if (ke.key == "Enter") {
                ke.preventDefault()
                submit()
            }
        })
        checkbox.focus()
    }

    /**
     * Persists the checkbox state to the space's anchor frontmatter and
     * closes. Closing happens BEFORE the write completes so the UI feels
     * instant; the write is small and the registry refreshes the footer
     * listings (badge) when it lands.
     */
    private fun submit() {
        val anchor = anchorFileRel ?: return
        val allowed = aiCheckboxEl?.checked == true
        val vm = activePaneVmProvider() ?: return
        close()
        parentScope.launch {
            vm.setSpaceAiAllowed(anchor, allowed)
        }
        onAfterPick()
    }

    private fun buildTitleRow(text: String): HTMLElement {
        val title = document.createElement("div") as HTMLElement
        title.textContent = text
        title.style.apply {
            padding = "12px 14px 4px"
            fontWeight = "600"
            color = "var(--t-text, #e6e6e6)"
            fontSize = "13px"
        }
        return title
    }

    private fun buildNote(text: String): HTMLElement {
        val note = document.createElement("div") as HTMLElement
        note.textContent = text
        note.style.apply {
            padding = "10px 14px 14px"
            color = "var(--t-text-dim, #7a7a7a)"
            fontSize = "12.5px"
        }
        return note
    }

    private fun attachDocumentKeyHandler() {
        val handler: (Event) -> Unit = lambda@{ e ->
            val ke = e as? KeyboardEvent ?: return@lambda
            if (ke.key == "Escape") {
                ke.preventDefault()
                close()
            }
        }
        documentKeyHandler = handler
        document.addEventListener("keydown", handler, /* capture = */ true)
    }

    private fun detachDocumentKeyHandler() {
        documentKeyHandler?.let {
            document.removeEventListener("keydown", it, /* capture = */ true)
        }
        documentKeyHandler = null
    }
}
