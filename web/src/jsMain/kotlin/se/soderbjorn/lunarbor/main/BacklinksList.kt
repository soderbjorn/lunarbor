/* BacklinksList.kt (jsMain)
 *
 * The "Linked from" section (LBR-7): under the bullets of the page a pane
 * shows, before its folder contents list, every line elsewhere that links
 * to this page — a `lunarbor:` link to it, or a `[[wiki]]` link resolving
 * to it ("backlinks", as Roam / Obsidian call them). Which lines count is
 * decided in commonMain (`TextIndex.backlinks`, via
 * `PaneBackingViewModel.backlinksOf`).
 *
 * The header reads "Linked from · N" and folds the list (pane state,
 * `toggleBacklinksCollapsed`). One compact row per line: its text, then —
 * dimmed, on the same row — the breadcrumb of the page it lives in. A click goes to the line in place (pushing file
 * history, like a search result); Shift- or ⌘-click (Ctrl-click off the
 * Mac) and a right-click (the Mac's Ctrl-click) open it in a new window —
 * the same rule as links ([OpenGesture]). Hidden when nothing links here.
 *
 * Platform view code only — builds DOM, delegates every action. Reuses the
 * pane search's result-row look (`lunarbor-search-hit-*`). */
package se.soderbjorn.lunarbor.main

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent
import se.soderbjorn.lunarbor.data.TextHit

/**
 * Paints the "Linked from" section of [state]'s page into [container]
 * (emptied first); paints nothing while the backlinks are being found or
 * when there are none.
 *
 * Called by [paintFolderContents] on every repaint, before the folder's
 * entries.
 *
 * @param container The section's host, above the folder contents.
 * @param state Latest pane state.
 * @param viewModel Gives the backlinks and receives the navigation intents.
 */
internal fun paintBacklinks(container: HTMLElement, state: PaneBackingViewModel.State, viewModel: MainViewModel) {
    ensureSearchBarStyles()
    ensureBacklinksStyles()
    container.innerHTML = ""
    val hits = viewModel.backlinksOf(state)?.takeIf { it.isNotEmpty() } ?: return
    val section = el("div", "lunarbor-backlinks")
    val header = el("button", "lunarbor-backlinks-head")
    header.setAttribute("type", "button")
    header.setAttribute("aria-expanded", (!state.backlinksCollapsed).toString())
    header.appendChild(el("span", "lunarbor-backlinks-chevron", if (state.backlinksCollapsed) "▸" else "▾"))
    header.appendChild(el("span", "lunarbor-backlinks-title", "Linked from · ${hits.size}"))
    header.title = if (state.backlinksCollapsed) "Show the lines that link here" else "Hide them"
    header.addEventListener("mousedown", { ev -> ev.preventDefault() })
    header.addEventListener("click", { viewModel.toggleBacklinksCollapsed() })
    section.appendChild(header)
    if (!state.backlinksCollapsed) {
        val list = el("div", "lunarbor-backlinks-list")
        for (hit in hits) list.appendChild(backlinkRow(hit, viewModel))
        section.appendChild(list)
    }
    container.appendChild(section)
}

/**
 * One linking line on one row: its text, then where it lives. A click goes
 * there; a modified click or a right-click opens a new window.
 */
private fun backlinkRow(hit: TextHit, viewModel: MainViewModel): HTMLElement {
    val row = el("div", "lunarbor-backlinks-row")
    val where = viewModel.searchHitCrumbs(hit).joinToString(" › ")
    row.title = "${hit.text} — $where\nClick to go there; ⇧-click or right-click: new window"
    row.setAttribute("data-backlink-file", hit.fileRel)
    row.appendChild(el("span", "lunarbor-backlinks-text", hit.text))
    row.appendChild(el("span", "lunarbor-backlinks-where", where))
    // Keep the editor's selection: act on click, not on the press.
    row.addEventListener("mousedown", { ev -> ev.preventDefault() })
    row.addEventListener("click", { ev ->
        // Same rule as a link ([OpenGesture]); the Mac's Ctrl-click is a
        // right-click, whose `contextmenu` below opens the one new window.
        when (openGestureOf(ev as MouseEvent)) {
            OpenGesture.HERE -> viewModel.navigateToSearchHit(hit)
            OpenGesture.NEW_WINDOW -> viewModel.openSearchHitInNewWindow?.invoke(hit)
            OpenGesture.CONTEXT_MENU, OpenGesture.NONE -> {}
        }
    })
    row.addEventListener("contextmenu", { ev ->
        ev.preventDefault()
        ev.stopPropagation()
        viewModel.openSearchHitInNewWindow?.invoke(hit)
    })
    return row
}

private fun el(tag: String, className: String, text: String? = null): HTMLElement =
    (document.createElement(tag) as HTMLElement).also {
        it.className = className
        if (text != null) it.textContent = text
    }

private fun ensureBacklinksStyles() {
    if (document.getElementById("lunarbor-backlinks-style") != null) return
    val style = document.createElement("style") as HTMLElement
    style.id = "lunarbor-backlinks-style"
    style.textContent = BACKLINKS_CSS
    document.head?.appendChild(style)
}

private const val BACKLINKS_CSS = """
.lunarbor-backlinks { margin: 4px 0 14px; }
.lunarbor-backlinks-head {
    display: flex; align-items: center; gap: 6px; padding: 2px 0; margin: 0;
    font: inherit; font-size: 12px; font-weight: 700; letter-spacing: 0.04em; text-transform: uppercase;
    color: var(--t-text-dim, #9a9a9a); background: transparent; border: none; cursor: pointer;
}
.lunarbor-backlinks-head:hover { color: var(--t-text, #e6e6e6); }
.lunarbor-backlinks-chevron { width: 10px; display: inline-block; }
.lunarbor-backlinks-list { display: flex; flex-direction: column; margin-top: 4px; }
.lunarbor-backlinks-row {
    display: flex; align-items: baseline; gap: 12px; padding: 2px 8px; margin: 0 -8px;
    border-radius: 6px; cursor: pointer; min-width: 0;
}
.lunarbor-backlinks-row:hover { background: var(--t-surface, #2a2a2a); }
.lunarbor-backlinks-text { flex: 0 1 auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.lunarbor-backlinks-where {
    flex: 1 1 auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    font-size: 0.8em; color: var(--t-text-dim, #9a9a9a);
}
"""
