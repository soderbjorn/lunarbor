/* HitDoneControls.kt (jsMain)
 *
 * Toggle done on search result rows (LBR-22), view side: the small ✓
 * circle a result row shows on hover (pane search and search nodes alike)
 * and the "Marked done · Undo" toast shown after a toggle.
 *
 * The edit itself is commonMain (`PaneBackingViewModel.toggleDoneOnHit` →
 * `DocumentRegistry.toggleDoneOnHit`); this file only builds DOM and
 * forwards presses. The toast is one element on `<body>` for the whole
 * app (the newest toggle replaces an older toast), fixed at the bottom
 * centre, above the panes.
 *
 * View only — no business rules. */
package se.soderbjorn.lunarbor.main

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent
import se.soderbjorn.lunarbor.data.TextHit

/**
 * The ✓ circle for a result row: hidden until the row is hovered (or, in
 * the pane search, highlighted), drawn checked when [hit] is done. A
 * primary press calls [onToggle] — on mousedown, which never navigates:
 * the press stops here, so the row's own handler (go there, open a new
 * window) never sees it.
 *
 * Called by `PaneSearchBar.paintResults` and `OutlinePaintLoop`'s
 * `buildSearchNodeResults` for every hit with [TextHit.canToggleDone].
 *
 * @param hit The result the circle toggles.
 * @param onToggle Runs the toggle (`MainViewModel.toggleDoneOnHit`).
 */
internal fun buildHitDoneToggle(hit: TextHit, onToggle: () -> Unit): HTMLElement {
    ensureHitDoneStyles()
    val el = document.createElement("span") as HTMLElement
    el.className = "lunarbor-hit-done-toggle"
    if (hit.done) el.classList.add("is-done")
    el.title = if (hit.done) "Toggle done (strike its own title)" else "Mark done"
    el.setAttribute("role", "button")
    el.setAttribute("aria-label", "Toggle done")
    el.innerHTML = ICON_DONE
    el.addEventListener("mousedown", { ev ->
        ev.preventDefault()
        ev.stopPropagation()
        if ((ev as MouseEvent).button.toInt() != 0) return@addEventListener
        onToggle()
        swallowTrailingClick()
    })
    // The click after the press must not reach the row (which goes there).
    el.addEventListener("click", { ev ->
        ev.preventDefault()
        ev.stopPropagation()
    })
    el.addEventListener("contextmenu", { ev ->
        ev.preventDefault()
        ev.stopPropagation()
    })
    return el
}

/** The one toast element, on `<body>` while it shows. */
private var toastElement: HTMLElement? = null

/** Timer handle hiding the toast. */
private var toastTimer: Int? = null

/** How long a toast stays, in milliseconds. */
private const val TOAST_MS = 6000

/**
 * Shows "[message] · Undo" at the bottom of the window, replacing any toast
 * already up. Undo calls [onUndo] and hides it; after [TOAST_MS] it hides
 * by itself and calls [onTimeout].
 *
 * Called by `MainScreen` when a pane's `State.hitDoneToast` changes
 * serial.
 *
 * @param message "Marked done" or "Marked not done".
 * @param onUndo The toast's Undo (`MainViewModel.undoHitDoneToggle`).
 * @param onTimeout Forgets the undo (`MainViewModel.dismissHitDoneToast`).
 */
internal fun showUndoToast(message: String, onUndo: () -> Unit, onTimeout: () -> Unit) {
    ensureHitDoneStyles()
    hideUndoToast()
    val toast = document.createElement("div") as HTMLElement
    toast.className = "lunarbor-undo-toast"
    toast.setAttribute("role", "status")
    val text = document.createElement("span") as HTMLElement
    text.textContent = message
    val dot = document.createElement("span") as HTMLElement
    dot.className = "lunarbor-undo-toast-sep"
    dot.textContent = "·"
    val undo = document.createElement("button") as HTMLElement
    undo.className = "lunarbor-undo-toast-undo"
    undo.textContent = "Undo"
    // Keep focus where it is (the editor or the search field).
    undo.addEventListener("mousedown", { ev -> ev.preventDefault() })
    undo.addEventListener("click", {
        hideUndoToast()
        onUndo()
    })
    toast.appendChild(text)
    toast.appendChild(dot)
    toast.appendChild(undo)
    document.body?.appendChild(toast)
    toastElement = toast
    toastTimer = window.setTimeout({
        if (toastElement === toast) {
            hideUndoToast()
            onTimeout()
        }
    }, TOAST_MS)
}

/** Hides the toast, if one shows. */
internal fun hideUndoToast() {
    toastTimer?.let { window.clearTimeout(it) }
    toastTimer = null
    toastElement?.let { it.parentNode?.removeChild(it) }
    toastElement = null
}

/** A circle with a check mark: the done toggle. */
private const val ICON_DONE: String =
    "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
        "<circle cx=\"12\" cy=\"12\" r=\"9\"/><polyline points=\"8 12.5 11 15.5 16.5 9.5\"/></svg>"

/** Injects the toggle's and the toast's CSS once per document. */
private fun ensureHitDoneStyles() {
    if (document.getElementById("lunarbor-hit-done-styles") != null) return
    val el = document.createElement("style")
    el.id = "lunarbor-hit-done-styles"
    el.textContent = """
        .lunarbor-hit-done-toggle {
            display: inline-flex;
            align-items: center;
            justify-content: center;
            flex: 0 0 auto;
            width: 18px;
            height: 18px;
            border-radius: 50%;
            color: var(--t-text-dim, #7a7a7a);
            cursor: pointer;
            opacity: 0;
            align-self: center;
        }
        .lunarbor-search-hit:hover .lunarbor-hit-done-toggle,
        .lunarbor-search-hit-selected .lunarbor-hit-done-toggle,
        .lunarbor-search-node-hit:hover .lunarbor-hit-done-toggle {
            opacity: 1;
        }
        .lunarbor-hit-done-toggle:hover {
            color: var(--t-accent, #5ab0ff);
        }
        .lunarbor-hit-done-toggle.is-done {
            color: var(--t-accent, #5ab0ff);
        }
        /* Rows without a toggle (note lines, code rows) keep their text
           aligned with the rest. */
        .lunarbor-hit-done-spacer {
            flex: 0 0 auto;
            width: 18px;
        }
        .lunarbor-undo-toast {
            position: fixed;
            left: 50%;
            bottom: 24px;
            transform: translateX(-50%);
            z-index: 10030;
            display: flex;
            align-items: center;
            gap: 8px;
            padding: 8px 10px 8px 14px;
            border-radius: 8px;
            border: 1px solid var(--t-border, #4a4a4a);
            background: var(--t-surface, #2a2a2a);
            color: var(--t-text, #e6e6e6);
            font-size: 13px;
            box-shadow: 0 6px 20px rgba(0, 0, 0, 0.25);
            max-width: calc(100vw - 32px);
        }
        .lunarbor-undo-toast-sep {
            color: var(--t-text-dim, #7a7a7a);
        }
        .lunarbor-undo-toast-undo {
            padding: 2px 8px;
            border: none;
            border-radius: 4px;
            background: transparent;
            color: var(--t-accent, #5ab0ff);
            font: inherit;
            font-weight: 600;
            cursor: pointer;
        }
        .lunarbor-undo-toast-undo:hover {
            background: var(--t-accent-soft, rgba(90, 160, 255, 0.18));
        }
    """.trimIndent()
    document.head?.appendChild(el)
}
