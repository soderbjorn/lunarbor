/*
 * NewSpaceModal.kt (jsMain)
 * ------------------------
 * Name-prompt modal for the "New space" command: a text input plus the
 * AI opt-in checkbox, in the same centered panel chrome as the command
 * palette. Enter submits the typed name (and checkbox state) to
 * `MainViewModel.createSpaceAndNavigate`, Escape cancels.
 *
 * Deliberately reuses the `treefacts-palette-*` CSS classes injected by
 * the toolkit/`AppShell` styles — same backdrop, panel, and input look —
 * so this file adds no CSS of its own (the checkbox row is inline-styled,
 * matching the codebase's footer/chrome convention). View-layer glue
 * only: no business rules; name sanitization, file creation, and
 * metadata semantics live in `PaneBackingViewModel.createSpaceAndNavigate`
 * (commonMain).
 *
 * Also home to [buildSpaceAiCheckboxRow], shared with
 * `SpaceSettingsModal` so the create-time and edit-time checkboxes look
 * and behave identically.
 */

package se.soderbjorn.treefacts.main

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLLabelElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent

/**
 * Name-input modal that creates a new space (a top-level tree) and navigates the
 * owning pane into it.
 *
 * One instance per pane, lazily created by `AppShell.openNewSpaceModal`
 * and reused across opens — the same lifecycle as the pane's
 * `LinkSearchModal`s. DOM nodes are built on [open] and torn down on
 * [close], so a cached-but-closed instance holds no live DOM. The AI
 * checkbox always opens unchecked — AI eligibility is opt-in per create,
 * never a sticky preference.
 *
 * @param activePaneVmProvider Resolves the owning pane's [MainViewModel]
 *   at submit time. Nullable because the pane may have been closed
 *   between modal opens; submit no-ops in that case.
 * @param onAfterPick Invoked after a successful submit — typically the
 *   host focuses the pane's editor so the caret lands in the freshly
 *   opened (empty) page.
 */
internal class NewSpaceModal(
    private val activePaneVmProvider: () -> MainViewModel?,
    private val onAfterPick: () -> Unit = {},
) {

    private var backdropEl: HTMLElement? = null
    private var inputEl: HTMLInputElement? = null
    private var aiCheckboxEl: HTMLInputElement? = null

    /** Element that held focus before [open] was called; restored on close. */
    private var focusToRestore: HTMLElement? = null

    private var documentKeyHandler: ((Event) -> Unit)? = null

    /** Show the modal. Idempotent — re-opening just re-focuses the input. */
    fun open() {
        if (backdropEl != null) {
            inputEl?.focus()
            return
        }
        focusToRestore = document.activeElement as? HTMLElement
        buildDom()
        attachDocumentKeyHandler()
        inputEl?.focus()
    }

    /** Hide the modal. Idempotent. Restores the previously-focused element. */
    fun close() {
        backdropEl?.let { it.parentNode?.removeChild(it) }
        backdropEl = null
        inputEl = null
        aiCheckboxEl = null
        detachDocumentKeyHandler()
        focusToRestore?.focus()
        focusToRestore = null
    }

    private fun buildDom() {
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

        val input = document.createElement("input") as HTMLInputElement
        input.type = "text"
        input.className = "treefacts-palette-input"
        input.placeholder = "Name of the new space…"
        input.autocomplete = "off"
        input.spellcheck = false
        input.addEventListener("keydown", { e ->
            val ke = e as KeyboardEvent
            when (ke.key) {
                "Escape" -> {
                    ke.preventDefault()
                    close()
                }
                "Enter" -> {
                    ke.preventDefault()
                    submit()
                }
            }
        })

        val (checkboxRow, checkbox) = buildSpaceAiCheckboxRow(initialChecked = false)

        panel.appendChild(input)
        panel.appendChild(checkboxRow)
        backdrop.appendChild(panel)
        document.body?.appendChild(backdrop)

        backdropEl = backdrop
        inputEl = input
        aiCheckboxEl = checkbox
    }

    /**
     * Validates and submits the typed name plus the AI opt-in. Blank
     * input keeps the modal open (there is nothing sensible to create);
     * otherwise the modal closes BEFORE delegating so the pane's editor
     * can take focus via [onAfterPick] without the close-path's focus
     * restore stealing it back.
     */
    private fun submit() {
        val name = inputEl?.value?.trim().orEmpty()
        if (name.isEmpty()) return
        val aiAllowed = aiCheckboxEl?.checked == true
        val vm = activePaneVmProvider() ?: return
        close()
        vm.createSpaceAndNavigate(name, aiAllowed)
        onAfterPick()
    }

    private fun attachDocumentKeyHandler() {
        // Capture-phase Escape catches it even when focus accidentally
        // lands outside the input (same defensive pattern as
        // [CommandPalette]).
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

/**
 * Builds the shared "Allow AI access in this space" checkbox row used by
 * both [NewSpaceModal] (create) and [SpaceSettingsModal] (edit), so the
 * two flows stay visually and semantically identical. A native checkbox
 * wrapped in a `<label>` — clicking the text toggles too. The muted hint
 * line spells out the fail-closed default so an unchecked box is an
 * informed choice, not an oversight.
 *
 * @param initialChecked Initial checkbox state — `false` for the create
 *   flow (opt-in), the space's current value for the settings flow.
 * @return The row element to append and the checkbox input to read at
 *   submit time.
 */
internal fun buildSpaceAiCheckboxRow(initialChecked: Boolean): Pair<HTMLElement, HTMLInputElement> {
    val row = document.createElement("label") as HTMLLabelElement
    row.style.apply {
        display = "flex"
        alignItems = "baseline"
        setProperty("gap", "8px")
        padding = "10px 14px 12px"
        cursor = "pointer"
        setProperty("user-select", "none")
        color = "var(--t-text, #e6e6e6)"
        fontSize = "13px"
    }
    val checkbox = document.createElement("input") as HTMLInputElement
    checkbox.type = "checkbox"
    checkbox.checked = initialChecked
    checkbox.style.setProperty("accent-color", "var(--t-accent, #7aa2f7)")

    val textWrap = document.createElement("span") as HTMLElement
    val label = document.createElement("span") as HTMLElement
    label.textContent = "Allow AI access in this space"
    val hint = document.createElement("span") as HTMLElement
    hint.textContent = "Off by default — spaces without this opt-in are never included in AI features."
    hint.style.apply {
        display = "block"
        color = "var(--t-text-dim, #7a7a7a)"
        fontSize = "11.5px"
        marginTop = "2px"
    }
    textWrap.appendChild(label)
    textWrap.appendChild(hint)

    row.appendChild(checkbox)
    row.appendChild(textWrap)
    return row to checkbox
}
