/*
 * SpaceHelp.kt (jsMain)
 * ---------------------
 * 3D mode's help (LBR-11): a scrollable modal dialog explaining the mode
 * as a whole and each of its shapes — Pages, Crown, Cone and Galaxy —
 * with every control and key. Opened by the "Help" button in the space's
 * top strip, and by `?` while a map has the keyboard ([MapView]).
 *
 * The dialog sits above the space overlay (which is at z-index 900) like
 * the palette and other modals do; Escape closes it, not 3D mode
 * ([SpaceMode] leaves an Escape alone while `.lunarbor-space-help-backdrop`
 * is open). It opens scrolled to the section of the shape on screen; the
 * contents column jumps between sections.
 *
 * Static text only — no state, no document logic. Keep it in step with
 * what `SpaceMode`, `PageSpaceView` and `MapView` actually do.
 *
 * jsMain only.
 */

package se.soderbjorn.lunarbor.main.space

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import se.soderbjorn.lunarbor.main.SpaceShape

/**
 * Opens 3D mode's help dialog, scrolled to [shape]'s section. A no-op when
 * it is already open. Focus returns to whatever had it when it closes.
 *
 * Called by [SpaceMode] (the strip's Help button) and [MapView] (`?`).
 *
 * @param shape The shape on screen; its section is shown first.
 */
internal fun showSpaceHelp(shape: SpaceShape) {
    if (document.querySelector(".lunarbor-space-help-backdrop") != null) return
    ensureHelpStyles()
    val previousFocus = document.activeElement as? HTMLElement
    val backdrop = div("lunarbor-space-help-backdrop")
    val panel = div("lunarbor-space-help")
    panel.setAttribute("role", "dialog")
    panel.setAttribute("aria-modal", "true")
    panel.setAttribute("aria-label", "3D mode help")
    panel.tabIndex = -1
    backdrop.appendChild(panel)

    lateinit var close: () -> Unit
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
        previousFocus?.focus()
    }
    document.addEventListener("keydown", keyHandler, true)
    backdrop.addEventListener("mousedown", { e -> if (e.target === backdrop) close() })

    val head = div("lunarbor-space-help-head")
    head.appendChild((document.createElement("h2") as HTMLElement).also {
        it.className = "lunarbor-space-help-title"
        it.textContent = "3D mode"
    })
    head.appendChild(span("lunarbor-space-help-sub", "How each view works"))
    val closeButton = document.createElement("button") as HTMLElement
    closeButton.className = "lunarbor-space-help-close"
    closeButton.textContent = "×"
    closeButton.title = "Close (Esc)"
    closeButton.setAttribute("aria-label", "Close")
    closeButton.addEventListener("click", { _ -> close() })
    head.appendChild(closeButton)
    panel.appendChild(head)

    val main = div("lunarbor-space-help-main")
    val toc = div("lunarbor-space-help-toc")
    val body = div("lunarbor-space-help-body")
    main.appendChild(toc)
    main.appendChild(body)
    panel.appendChild(main)

    for (section in HELP_SECTIONS) {
        val link = document.createElement("button") as HTMLElement
        link.className = "lunarbor-space-help-toc-item"
        link.textContent = section.title
        link.addEventListener("click", { _ ->
            val target = body.querySelector("#lunarbor-help-${section.id}") as? HTMLElement
            // Sections are positioned within the (relative) body, so offsetTop is already body-relative.
            if (target != null) body.scrollTop = target.offsetTop.toDouble() - 8
        })
        toc.appendChild(link)
        val el = (document.createElement("section") as HTMLElement)
        el.id = "lunarbor-help-${section.id}"
        el.className = "lunarbor-space-help-section"
        el.innerHTML = "<h3>${section.title}</h3>" + section.html
        body.appendChild(el)
    }

    document.body?.appendChild(backdrop)
    panel.focus()
    val start = body.querySelector("#lunarbor-help-${shape.name.lowercase()}") as? HTMLElement
    if (start != null && shape != SpaceShape.PAGES) {
        // Jump there at once (no smooth scroll on opening).
        body.style.setProperty("scroll-behavior", "auto")
        body.scrollTop = start.offsetTop.toDouble() - 8
        body.style.removeProperty("scroll-behavior")
    }
}

/**
 * One section of the help.
 *
 * @property id Its anchor; a shape's section uses the shape's lower-case name.
 * @property title Heading, also its entry in the contents column.
 * @property html Its body: trusted static HTML written below.
 */
private class HelpSection(val id: String, val title: String, val html: String)

/** `<kbd>` for a key label. */
private fun k(label: String): String = "<kbd>$label</kbd>"

/** A two-column table of keys (or gestures) and what they do. */
private fun keys(vararg rows: Pair<String, String>): String =
    "<table class=\"lunarbor-space-help-keys\">" +
        rows.joinToString("") { (key, what) -> "<tr><th>$key</th><td>$what</td></tr>" } +
        "</table>"

private val HELP_SECTIONS: List<HelpSection> = listOf(
    HelpSection(
        "overview", "Overview",
        """
        <p>3D mode lays your vault out in space. It changes how you <em>see</em> and move through
        the vault — never what is in it: nothing is moved, renamed or saved differently, and leaving
        3D mode puts every window back exactly where it was, caret and scroll included.</p>
        <p>There are four views, picked in the switcher at the top left:</p>
        <ul>
          <li><b>Pages</b> — where you write. Every node's page hangs at a fixed place in space; the page
          your window is on sits in front of you at full size and is the normal editor. Zooming flies
          between pages.</li>
          <li><b>Crown</b> — the whole tree as a map, growing up and out from Home. Best for
          “where is everything?”.</li>
          <li><b>Cone</b> — the tree hanging down, each node's children in a ring below it. Best for
          comparing siblings and very wide nodes.</li>
          <li><b>Galaxy</b> — a map where links pull as well as branches, so related nodes drift together
          even across areas. Best for “what is related to this?”.</li>
        </ul>
        <p>The rule of thumb: <b>find things on a map, write on a page.</b> Text in perspective is hard to
        read and edit, so the maps are for getting your bearings and the page in front of you is for
        writing.</p>
        <p>3D mode is experimental. It is turned on in App settings → Experimental → “Enable 3D mode”;
        while that is off the cube button and the shortcuts below do nothing.</p>
        """,
    ),
    HelpSection(
        "everywhere", "Getting around",
        """
        <p>3D mode covers the whole window. Along the top is a slim strip (you can drag the window by it):</p>
        <ul>
          <li><b>View switcher</b> — Pages, Crown, Cone, Galaxy. The view you leave 3D mode in is the one
          you come back to.</li>
          <li><b>Help</b> — this dialog.</li>
          <li><b>Palette</b> — the command palette, exactly as in 2D. Menus, popups and dialogs all open
          above the space.</li>
          <li><b>All windows / Focused window</b> (Pages only) — see below.</li>
          <li><b>Leave 3D</b> — back to the normal layout.</li>
        </ul>
        <p>Along the bottom is the <b>dock</b>: on the left your tabs (with a pip per window; click one to
        switch tab), on the right the active tab's windows as <code>1 · Page title</code> chips (click one
        to focus that window; the focused one is outlined) and <b>+ Window</b>, which opens a new window
        on the focused window's page.</p>
        ${keys(
            k("⌃⌘3") to "Enter or leave 3D mode (Ctrl-Alt-3 off the Mac). Also the cube in the top bar.",
            k("Esc") to "Leave 3D mode — unless a dialog, menu, the palette, a search field or other text field has the keyboard; then Esc goes to that first.",
            k("⌃⌘2") to "Next view: Pages → Crown → Cone → Galaxy → Pages (Ctrl-Alt-2 off the Mac).",
            k("⌃⌘1") to "Pages: the focused window alone, or all of the tab's windows (Ctrl-Alt-1).",
            k("⌘P") to "Command palette.",
        )}
        <p>The ⌃⌘ shortcuts can be rebound in App settings → Keyboard Shortcuts.</p>
        """,
    ),
    HelpSection(
        "pages", "Pages",
        """
        <p>Every node — the vault root (Home) and every bullet that has children — has its own page,
        hanging at a fixed place in space. The page your window is on is in front of you, at exactly one
        screen pixel per pixel, and it is <b>the real editor</b>: type, press Enter for a new bullet, Tab and
        Shift-Tab to indent, fold, search, paste images, use the palette — everything works as in 2D.
        Notes, images, drawings and web pages open on the page too.</p>
        <h4>What you see</h4>
        <ul>
          <li><b>The live page</b>, with a header holding Back / Forward and the breadcrumb of where the
          window is (every segment but the last is clickable), plus the window badges.</li>
          <li><b>Child pages</b> — the pages of this node's children hang behind it in a column on the
          left and one on the right, in outline order. Each is a read-only preview of that node's
          bullets.</li>
          <li><b>Grandchild pages</b> hang fainter, one column further out on their parent's side.</li>
          <li><b>Threads</b> run from each child bullet's dot on the live page to that child's page, so you
          can see where a dot leads before you click it.</li>
          <li><b>Window badges</b> (<code>Window 1</code>, …) on a page's header show which of the tab's
          windows are on it.</li>
        </ul>
        <h4>Moving</h4>
        <p>Only navigation moves the camera; editing never does. When an indent gives a bullet its first
        child, a new page sprouts in the space behind you — you go there only if you want to.</p>
        <ul>
          <li>Click a bullet's dot, or a preview page (or a dot inside one), to <b>fly into</b> that node.</li>
          <li>Back / Forward, the breadcrumb and links fly too. A page you return to is exactly where you
          left it.</li>
          <li>Your keystrokes reach the new page from the moment the flight starts — typing never waits
          for the animation. With “reduce motion” turned on in your system, flights become cuts.</li>
        </ul>
        ${keys(
            k("⌥⌘⏎") to "Fly into the caret's item (zoom in).",
            k("⌥⌘←") + " " + k("⌥⌘→") to "Back / Forward.",
            k("⌃⌘↑") to "Fly up one level.",
            k("⇧⌃⌘↑") to "Fly home.",
            k("⌘↑") + " " + k("⌘↓") to "Fold / unfold the caret's item.",
            k("Esc") to "Leave 3D mode (in 2D, Esc clears the zoom instead).",
        )}
        <h4>One window or all of them</h4>
        <p><b>Focused window</b> shows only the focused window, filling the space. <b>All windows</b>
        (⌃⌘1) shows every window of the tab at its usual place and size, each with its own camera:
        zooming in one window leaves the others where they are. A page open in two windows is the same
        page, so typing in one shows up in the other as you type. Click a window to focus it.</p>
        """,
    ),
    HelpSection(
        "maps", "The maps",
        """
        <p>Crown, Cone and Galaxy show the same thing in three shapes: your whole tree of nodes, seen from
        outside. They share everything below; only the arrangement differs.</p>
        <h4>What is drawn</h4>
        <ul>
          <li><b>Bodies</b> — one ball per node: Home, and every bullet that has its own folder (that is,
          has children). Bigger balls hold more.</li>
          <li><b>Dust</b> — the small specks circling a body are its leaf bullets (up to a dozen or so are
          drawn).</li>
          <li><b>Branches</b> — thin lines from each body to its parent.</li>
          <li><b>Link arcs</b> — brighter curves between two bodies when a note in one links to the other
          with a Lunarbor link (<code>lunarbor:/…</code>). Links to a note or file count for the node that
          holds it.</li>
          <li><b>Rings</b> — a ring around a body means it is <b>folded</b> on the map: its children are
          tucked inside it. Its label shows how many (e.g. <code>Recipes · 12</code>).</li>
          <li><b>Labels</b> — up to about forty at a time, so they never pile up: the selection, the
          windows' nodes, the selection's parent and children and the top-level areas come first, then
          whatever is nearest. Zoom in to see more.</li>
          <li><b>Window cards</b> — each window of the tab is a card joined by a line to the node it
          shows. The focused window's card and line are highlighted. If its node is off screen, the
          card is pinned to the edge.</li>
        </ul>
        <h4>Mouse and keys</h4>
        <p>Click on the map first so it has the keyboard.</p>
        ${keys(
            "Drag" to "Orbit around the point you are looking at.",
            "Right-drag, or " + k("⇧") + " drag" to "Pan.",
            "Scroll / pinch" to "Zoom in and out.",
            "Click a body or label" to "Select it and fly to it; the camera keeps it centred. Click the selected body again to edit it.",
            "Double-click" to "Edit that node: open it in the focused window and switch to Pages, on its page.",
            "Click a window card" to "Focus that window and fly to its node. Double-click: back to Pages to write in it.",
            k("←") + " " + k("→") to "Previous / next sibling.",
            k("↑") to "Parent.",
            k("↓") to "First child (unfolds the body if it is folded).",
            k("⏎") to "Open the selection in the focused window.",
            k("P") to "Edit the selection: as double-click.",
            k("E") to "Back to Pages, where the focused window already is.",
            k("F") to "Fold or unfold the selection.",
            k("L") to "Next view.",
            k("Home") to "Clear the selection and show the whole map again.",
            k("?") to "This help.",
        )}
        <p>With a body selected, a bar along the bottom shows its path (click a part to go there) and
        buttons for <b>Edit page</b>, <b>Open in window</b> and <b>Fold / Unfold</b>. The top left shows the
        view and how many nodes and links are on the map, and says when it is still reading folders.</p>
        <h4>Good to know</h4>
        <ul>
          <li>The map fills in as folders are read, and stays current as you edit, save, or change files
          outside the app.</li>
          <li>Positions come only from the tree (folder names and the order of siblings) — never from
          chance — so the same vault always looks the same and you can learn where things are.</li>
          <li>Very large vaults start partly folded so the map stays readable; unfold with
          <kbd>F</kbd> or <kbd>↓</kbd>. At most 1,500 nodes are shown. Folds on the map are its own and do
          not change folds in your windows.</li>
          <li>Moving between views makes the bodies glide to their new places, so you can follow a node
          from one shape to the next.</li>
        </ul>
        """,
    ),
    HelpSection(
        "crown", "Crown",
        """
        <p><b>The tree grows up and out from Home.</b> Home sits at the bottom centre. Its children — your
        top-level areas — stand on a ring around it, a little higher; their children on a wider, higher
        ring, and so on: <b>height is depth</b>.</p>
        <p>Every area gets its own slice of the circle, sized by how much it holds, and everything inside
        an area stays inside its slice. So an area is a direction: turn towards it and all of it is in
        front of you.</p>
        <p><b>Use it for:</b> getting your bearings — “where is everything?” — and seeing at a glance which
        areas are big, deep or sprawling. It is the default map.</p>
        <p><b>Try:</b> orbit slowly around Home to sweep through your areas; select an area and press
        <kbd>↓</kbd> and <kbd>→</kbd> to walk through it.</p>
        """,
    ),
    HelpSection(
        "cone", "Cone",
        """
        <p><b>The tree hangs down</b>, like a mobile. Home is at the top; each node's children hang in a
        <b>ring</b> one level below it, and each of those has its own ring below, and so on. A ring is as
        wide as its children need, so a node with many children gets a wide cone and a node with few a
        narrow one. (After Robertson's Cone Trees, Xerox PARC, 1991.)</p>
        <p><b>Use it for:</b> comparing siblings — all of a node's children are side by side on one ring —
        and for nodes with very many children, which spread out instead of crowding.</p>
        <p><b>Try:</b> select a node and press <kbd>←</kbd> / <kbd>→</kbd> to go round its ring; orbit to
        bring the sibling you want to the front.</p>
        """,
    ),
    HelpSection(
        "galaxy", "Galaxy",
        """
        <p><b>Links pull as well as branches.</b> Galaxy starts from the Crown and then lets the map
        settle like a physical system: every node pushes its neighbours away, each node is held to its
        parent by a spring, and every Lunarbor link between two nodes is a longer, softer spring. Home
        stays at the centre.</p>
        <p>The result: nodes that link to each other drift together, <b>even when they live in different
        areas</b>, and clusters show connections the tree hides. A node with links to many places ends up
        between them; an area nothing links to floats off on its own.</p>
        <p><b>Use it for:</b> “what is related to this?” — finding notes that belong together, and spotting
        orphans.</p>
        <p><b>Good to know:</b> the layout is still deterministic — the same links give the same galaxy.
        Only <code>lunarbor:</code> links pull; <code>[[wiki]]</code> links are not drawn on the map yet. On
        a large vault the galaxy takes a moment to settle after the graph changes.</p>
        """,
    ),
    HelpSection(
        "colour", "Colours",
        """
        <p>3D mode is deliberately more colourful than your theme. Each top-level area gets a vivid hue of
        its own, spread around the colour wheel in the order the areas appear under Home, so
        neighbouring areas always look different and adding an area never recolours the others.</p>
        <ul>
          <li>On the maps, an area's bodies wear its hue (each a little lighter or shifted, deeper ones
          lighter), branches take the colour of the node they lead to, and link arcs blend from one end's
          colour to the other's. Home is drawn in the theme's text colour.</li>
          <li>In Pages, every page wears its area's hue as a coloured top edge and soft glow, on its
          bullet dots, and on the thread leading to it.</li>
          <li>Some of the stars in the background are tinted, too.</li>
        </ul>
        <p>Your theme still decides the background, text and the controls, and 3D mode follows it when you
        switch themes, light or dark.</p>
        """,
    ),
    HelpSection(
        "privacy", "Privacy modes",
        """
        <p>3D mode follows the app's privacy mode in every view. Whatever the current mode hides does not
        exist in space: it is never a page, a preview row, a body, dust, a label or a link, and neither is
        anything under it. Switching the mode updates the space at once. Colours never depend on the mode,
        so they give nothing away.</p>
        """,
    ),
)

/** Injects the help dialog's stylesheet once; colours come from the theme's variables. */
private fun ensureHelpStyles() {
    if (document.getElementById("lunarbor-space-help-styles") != null) return
    val style = document.createElement("style") as HTMLElement
    style.id = "lunarbor-space-help-styles"
    style.textContent = HELP_CSS
    document.head?.appendChild(style)
}

private val HELP_CSS = """
.lunarbor-space-help-backdrop {
    position: fixed; inset: 0; z-index: 2147483640; background: rgba(0, 0, 0, 0.5);
    display: flex; align-items: flex-start; justify-content: center; padding: 6vh 16px 16px;
}
.lunarbor-space-help {
    width: min(860px, 100%); height: min(720px, 88vh); display: flex; flex-direction: column; outline: none;
    background: var(--t-bg, #1e1e1e); color: var(--t-text, #e6e6e6);
    border: 1px solid var(--t-border, rgba(255,255,255,0.12)); border-radius: 12px;
    box-shadow: 0 28px 72px rgba(0, 0, 0, 0.45), 0 10px 24px rgba(0, 0, 0, 0.30);
    font: 13.5px/1.55 var(--dt-font-prop, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif);
}
.lunarbor-space-help-head {
    flex: none; display: flex; align-items: baseline; gap: 10px; padding: 14px 18px 12px;
    border-bottom: 1px solid var(--t-border, rgba(255,255,255,0.12));
}
.lunarbor-space-help-title { margin: 0; font-size: 17px; font-weight: 650; }
.lunarbor-space-help-sub { color: var(--t-text-dim, #9aa0a6); }
.lunarbor-space-help-close {
    margin-left: auto; align-self: center; width: 28px; height: 28px; border: 0; border-radius: 6px; cursor: pointer;
    background: transparent; color: var(--t-text-dim, #9aa0a6); font-size: 20px; line-height: 1;
}
.lunarbor-space-help-close:hover { background: var(--t-surface-alt, rgba(255,255,255,0.08)); color: var(--t-text, #e6e6e6); }
.lunarbor-space-help-main { flex: 1; min-height: 0; display: flex; }
.lunarbor-space-help-toc {
    flex: none; width: 150px; padding: 12px 8px; display: flex; flex-direction: column; gap: 2px;
    border-right: 1px solid var(--t-border, rgba(255,255,255,0.12)); overflow-y: auto;
}
.lunarbor-space-help-toc-item {
    text-align: left; border: 0; background: none; border-radius: 6px; padding: 5px 9px; cursor: pointer;
    font: inherit; font-size: 13px; color: var(--t-text-dim, #9aa0a6);
}
.lunarbor-space-help-toc-item:hover { color: var(--t-text, #e6e6e6); background: var(--t-surface-alt, rgba(255,255,255,0.06)); }
.lunarbor-space-help-body { flex: 1; min-width: 0; overflow-y: auto; padding: 4px 24px 28px; position: relative; scroll-behavior: smooth; }
@media (prefers-reduced-motion: reduce) { .lunarbor-space-help-body { scroll-behavior: auto; } }
.lunarbor-space-help-section { padding-top: 14px; }
.lunarbor-space-help-section + .lunarbor-space-help-section { border-top: 1px solid var(--t-border, rgba(255,255,255,0.08)); margin-top: 14px; }
.lunarbor-space-help-section h3 { margin: 0 0 8px; font-size: 16px; font-weight: 650; font-family: var(--dt-font-display, inherit); }
.lunarbor-space-help-section h4 { margin: 16px 0 6px; font-size: 13.5px; font-weight: 650; color: var(--t-text, #e6e6e6); }
.lunarbor-space-help-section p { margin: 0 0 9px; }
.lunarbor-space-help-section ul { margin: 0 0 10px; padding-left: 20px; }
.lunarbor-space-help-section li { margin: 0 0 5px; }
.lunarbor-space-help-section code { font-family: var(--dt-font-mono, "JetBrains Mono", ui-monospace, monospace); font-size: 12px; padding: 1px 4px; border-radius: 4px; background: var(--t-surface-alt, rgba(255,255,255,0.07)); }
.lunarbor-space-help kbd {
    display: inline-block; min-width: 18px; padding: 0 5px; border-radius: 5px; text-align: center;
    font: 11.5px/18px var(--dt-font-prop, system-ui, sans-serif);
    border: 1px solid var(--t-border, rgba(255,255,255,0.18)); border-bottom-width: 2px;
    background: var(--t-surface, #252526); color: var(--t-text, #e6e6e6);
}
.lunarbor-space-help-keys { width: 100%; border-collapse: collapse; margin: 4px 0 12px; }
.lunarbor-space-help-keys th, .lunarbor-space-help-keys td { text-align: left; vertical-align: top; padding: 6px 8px; border-top: 1px solid var(--t-border, rgba(255,255,255,0.08)); }
.lunarbor-space-help-keys th { width: 34%; font-weight: 500; white-space: nowrap; color: var(--t-text, #e6e6e6); }
.lunarbor-space-help-keys td { color: var(--t-text, #e6e6e6); opacity: .88; }
@media (max-width: 640px) {
    .lunarbor-space-help-toc { display: none; }
    .lunarbor-space-help-keys th { white-space: normal; }
}
"""
