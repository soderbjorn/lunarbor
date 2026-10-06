/*
 * SpacePageHead.kt (jsMain)
 * -------------------------
 * The header row every page card wears in 3D mode's Pages and Grove: the
 * pane header's Back / Forward / Up arrows (the same icons,
 * `AppShell.ICON_BACK` …) and the breadcrumb of the page's whole location.
 * The live page's arrows and crumbs work; a preview card wears the same
 * row — arrows dimmed and inert, crumbs going to their node — so a card
 * looks the same before, during and after a flight onto it.
 *
 * View glue only — intents go to the pane's `MainViewModel`.
 */

package se.soderbjorn.lunarbor.main.space

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunarbor.data.FolderName
import se.soderbjorn.lunarbor.data.LunarborLink
import se.soderbjorn.lunarbor.main.AppShell
import se.soderbjorn.lunarbor.main.MainViewModel

/**
 * One breadcrumb segment of a card's header.
 *
 * @property label The text shown.
 * @property onClick What a press does, or `null` for the page itself (the
 *   last segment never navigates).
 */
internal data class SpaceCrumb(val label: String, val onClick: (() -> Unit)?)

/**
 * Fills [head] with the arrows and the breadcrumb of [crumbs].
 *
 * Called by `PageSpaceView` and `GroveView` for the live page (with the
 * window's own state) and for every preview card (with [vm] `null`).
 *
 * @param vm The window's view model for a live header: the arrows reflect
 *   and drive its history; `null` for a preview's dimmed, inert arrows.
 * @param focus Called before any press acts (focuses the view's window).
 */
internal fun fillSpaceHead(head: HTMLElement, vm: MainViewModel?, crumbs: List<SpaceCrumb>, focus: () -> Unit) {
    head.innerHTML = ""
    val nav = (document.createElement("span") as HTMLElement).also { it.className = "lunarbor-space-navs" }
    val state = vm?.currentBackingState
    fun arrow(icon: String, title: String, enabled: Boolean, go: () -> Unit) {
        val b = document.createElement("button") as HTMLElement
        b.className = "lunarbor-space-nav"
        b.innerHTML = icon
        b.title = title
        b.setAttribute("aria-label", title)
        if (!enabled) b.setAttribute("disabled", "")
        b.addEventListener("mousedown", { e ->
            e.preventDefault()
            if (!enabled) return@addEventListener
            e.stopPropagation()
            focus()
            go()
        })
        nav.appendChild(b)
    }
    arrow(AppShell.ICON_BACK, "Back (⌥⌘←)", vm != null && state != null && vm.canZoomBack(state)) { vm?.zoomBack() }
    arrow(AppShell.ICON_FORWARD, "Forward (⌥⌘→)", vm != null && state != null && vm.canZoomForward(state)) { vm?.zoomForward() }
    arrow(AppShell.ICON_UP, "Up (⌃⌘↑)", vm != null && state != null && vm.canNavigateUp(state)) { vm?.navigateUp() }
    head.appendChild(nav)
    val row = (document.createElement("div") as HTMLElement).also { it.className = "lunarbor-space-crumbs" }
    crumbs.forEachIndexed { i, seg ->
        if (i > 0) row.appendChild(span("lunarbor-space-crumb-sep", "›"))
        val go = seg.onClick?.takeIf { i < crumbs.lastIndex }
        val el = span(if (go != null) "lunarbor-space-crumb is-link" else "lunarbor-space-crumb", seg.label)
        if (go != null) {
            el.addEventListener("mousedown", { e ->
                e.preventDefault()
                e.stopPropagation()
                focus()
                go()
            })
        }
        row.appendChild(el)
    }
    head.appendChild(row)
}

/**
 * The breadcrumb of the node page stored in [folderRel] (`""` = Home):
 * `Home`, then each folder on the way, by its decoded name (a node's
 * folder is named after its title, tags left out), the node's own [title]
 * last. Every segment but the last goes there in the window [vm].
 */
internal fun folderCrumbs(folderRel: String, title: String, vm: MainViewModel): List<SpaceCrumb> {
    val out = ArrayList<SpaceCrumb>()
    out += SpaceCrumb("Home") { vm.navigateHome() }
    if (folderRel.isEmpty()) return out
    val parts = folderRel.split('/')
    for (i in parts.indices) {
        val path = parts.subList(0, i + 1).joinToString("/")
        val label = if (i == parts.lastIndex && title.isNotEmpty()) title else FolderName.decode(parts[i])
        out += SpaceCrumb(label) { vm.navigateToLink(LunarborLink.rooted(path)) }
    }
    return out
}
