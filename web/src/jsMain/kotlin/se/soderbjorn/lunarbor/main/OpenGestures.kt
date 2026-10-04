/*
 * OpenGestures.kt (jsMain)
 * ------------------------
 * DOM glue for [OpenGesture], the one rule for presses on link-like
 * targets (links, wiki links, bullet dots, search results, backlinks,
 * link preview items): reads a `MouseEvent`'s button and modifiers and
 * knows whether this is a Mac, where Ctrl-click is a right-click.
 *
 * Also owns [swallowTrailingClick]: a target that opens a new window on
 * the *press* must keep the `click` that follows from reaching the
 * toolkit's raise-on-click of the pane it was pressed in — that would put
 * the old pane back on top of (and refocus it over) the window that just
 * opened.
 *
 * View glue only — the rule itself lives in commonMain.
 */

package se.soderbjorn.lunarbor.main

import kotlinx.browser.window
import org.w3c.dom.events.Event
import org.w3c.dom.events.MouseEvent

/**
 * `true` on macOS (and iOS), read once from the user agent. On the Mac a
 * Ctrl-click fires `contextmenu` after its `mousedown`.
 */
internal val isMacPlatform: Boolean by lazy {
    val ua = js("(typeof navigator !== 'undefined' && navigator.userAgent) || ''") as String
    ua.contains("Mac") || ua.contains("iPhone") || ua.contains("iPad")
}

/**
 * What the press or click [ev] asks of a link-like target — see
 * [OpenGesture.of]. Called by every such target's `mousedown` / `click`
 * handler (`MainScreen`, `OutlinePaintLoop`, `PaneSearchBar`,
 * `BacklinksList`).
 */
internal fun openGestureOf(ev: MouseEvent): OpenGesture =
    OpenGesture.of(ev.button.toInt(), ev.shiftKey, ev.metaKey, ev.ctrlKey, isMacPlatform)

/** The pending [swallowTrailingClick] listeners' remover, if any. */
private var pendingClickSwallow: (() -> Unit)? = null

/**
 * Stops the next `click` anywhere in the window before any other handler
 * sees it (window, capture phase). Call it from a `mousedown` handler
 * that just opened a new window: the toolkit raises and focuses the pane
 * a click lands in, which would bury the new window under the pane the
 * link was in. Cleared right after the `mouseup` (a click that never
 * came — the pressed element was repainted away), by the next
 * `mousedown`, or after [CLICK_SWALLOW_TIMEOUT_MS], so it never eats a
 * later, unrelated click.
 */
internal fun swallowTrailingClick() {
    pendingClickSwallow?.invoke()
    lateinit var onClick: (Event) -> Unit
    lateinit var onDown: (Event) -> Unit
    lateinit var onUp: (Event) -> Unit
    var timer = 0
    val remove = {
        window.removeEventListener("click", onClick, true)
        window.removeEventListener("mousedown", onDown, true)
        window.removeEventListener("mouseup", onUp, true)
        window.clearTimeout(timer)
        pendingClickSwallow = null
    }
    onClick = { e ->
        e.stopPropagation()
        e.preventDefault()
        remove()
    }
    onDown = { _ -> remove() }
    // A click follows its mouseup within the same input dispatch; one
    // that has not come by the next task never will.
    onUp = { _ -> window.setTimeout({ if (pendingClickSwallow === remove) remove() }, 0) }
    // Added while the press is still being dispatched: the window's
    // capture phase is already past, so this press does not clear it.
    window.addEventListener("click", onClick, true)
    window.addEventListener("mousedown", onDown, true)
    window.addEventListener("mouseup", onUp, true)
    timer = window.setTimeout({ remove() }, CLICK_SWALLOW_TIMEOUT_MS)
    pendingClickSwallow = remove
}

/** How long [swallowTrailingClick] waits for the click before giving up. */
private const val CLICK_SWALLOW_TIMEOUT_MS = 1500
