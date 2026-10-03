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
 * `toggleBacklinksCollapsed`). Lines are grouped by the page they live in,
 * under its breadcrumb. A click goes to the line in place (pushing file
 * history, like a search result); Shift-, ⌘- or Ctrl-click and a right-click
 * open it in a new window. Hidden when nothing links here.
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
        for ((file, inFile) in hits.groupBy { it.fileRel }) {
            val group = el("div", "lunarbor-backlinks-group")
            group.appendChild(el("div", "lunarbor-search-hit-where lunarbor-backlinks-where", viewModel.searchHitCrumbs(inFile.first()).joinToString(" › ")))
            group.setAttribute("data-backlink-file", file)
            for (hit in inFile) group.appendChild(backlinkRow(hit, viewModel))
            list.appendChild(group)
        }
        section.appendChild(list)
    }
    container.appendChild(section)
}

/** One linking line: its text; click goes there, modified or right-click opens a new window. */
private fun backlinkRow(hit: TextHit, viewModel: MainViewModel): HTMLElement {
    val row = el("div", "lunarbor-search-hit lunarbor-backlinks-row")
    row.title = "Go to this line (⇧-click or right-click: new window)"
    val body = el("div", "lunarbor-search-hit-body")
    body.appendChild(el("div", "lunarbor-search-hit-text", hit.text))
    row.appendChild(body)
    // Keep the editor's selection: act on click, not on the press.
    row.addEventListener("mousedown", { ev -> ev.preventDefault() })
    row.addEventListener("click", { ev ->
        val me = ev as MouseEvent
        if (me.shiftKey || me.metaKey || me.ctrlKey) viewModel.openSearchHitInNewWindow?.invoke(hit)
        else viewModel.navigateToSearchHit(hit)
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
.lunarbor-backlinks-list { display: flex; flex-direction: column; gap: 8px; margin-top: 6px; }
.lunarbor-backlinks-group { display: flex; flex-direction: column; gap: 2px; }
.lunarbor-backlinks-where { padding: 0 8px; }
.lunarbor-backlinks-row { cursor: pointer; }
"""
