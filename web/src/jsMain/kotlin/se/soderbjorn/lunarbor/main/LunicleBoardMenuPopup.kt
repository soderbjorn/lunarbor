/* LunicleBoardMenuPopup.kt (jsMain)
 *
 * The popup of a board issue's property menu (LBR-30): the `#` / `@`
 * typeahead, a pill's one-field menu, and the resolution popup a closing
 * status asks for first. Modelled on the search field's tag autocomplete
 * (`PaneSearchBar`'s `.lunarbor-tag-menu`): fixed on `<body>`, rows act
 * on mousedown (a repaint between press and release cannot swallow it),
 * hover moves the highlight so mouse and keys agree, and a mousedown
 * anywhere outside closes it.
 *
 * Draws only: the options, their order, the highlight and what a key does
 * come from commonMain (`main/LunicleBoardMenu.kt`), and the open menu's
 * state is `LunicleBoardCursor`'s, which owns one of these per pane editor.
 *
 * View only — no rules. */
package se.soderbjorn.lunarbor.main

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.events.Event

/**
 * One popup element, shown under a board row (see the file header).
 *
 * @param onDismiss Called when a mousedown outside the popup closed it
 *   (the cursor drops its menu state).
 */
internal class LunicleBoardMenuPopup(private val onDismiss: () -> Unit) {

    private val element: HTMLElement = (document.createElement("div") as HTMLElement).apply {
        className = "lunarbor-tag-menu lunarbor-lunicle-menu"
        setAttribute("role", "listbox")
        // Keep the press from moving the focus out of the title field.
        addEventListener("mousedown", { ev -> ev.preventDefault() })
    }

    /** The row the popup hangs under, for [reposition]. */
    private var anchor: HTMLElement? = null

    /** Closes on a press outside; added while the popup shows. */
    private val outside: (Event) -> Unit = { ev ->
        val t = ev.target as? Node
        if (t == null || !element.contains(t)) {
            hide()
            onDismiss()
        }
    }

    /** Follows the row when the page scrolls. */
    private val onScroll: (Event) -> Unit = { reposition() }

    /** `true` while it is on `<body>`. */
    val isShown: Boolean get() = element.parentNode != null

    /**
     * Shows [head] and [rows] under [anchorRow] (a board row element).
     *
     * @param head The menu's head: `#<query>`, `@<query>`, a field's name, "Resolution".
     * @param rows One entry per option: its label, ✓, and its section's
     *   header when one is drawn above it.
     * @param highlight Index of the highlighted row, or -1.
     * @param emptyText What shows when [rows] is empty ("No match").
     * @param onHover A row was hovered: its index.
     * @param onPick A row was pressed: its index.
     */
    fun show(
        anchorRow: HTMLElement,
        head: String,
        rows: List<Row>,
        highlight: Int,
        emptyText: String,
        onHover: (Int) -> Unit,
        onPick: (Int) -> Unit,
    ) {
        element.innerHTML = ""
        val top = document.createElement("div") as HTMLElement
        top.className = "lunarbor-lunicle-menu-head"
        top.appendChild(span("lunarbor-lunicle-menu-title", head))
        top.appendChild(span("lunarbor-lunicle-menu-keys", "↑↓ Enter Esc"))
        element.appendChild(top)
        rows.forEachIndexed { i, row ->
            row.header?.let { element.appendChild(span("lunarbor-lunicle-menu-section", it)) }
            val el = document.createElement("div") as HTMLElement
            el.className = "lunarbor-tag-menu-item lunarbor-lunicle-menu-item"
            if (i == highlight) el.classList.add("lunarbor-tag-menu-item-on")
            el.setAttribute("role", "option")
            el.setAttribute("aria-selected", (i == highlight).toString())
            el.appendChild(span("lunarbor-lunicle-menu-check", if (row.checked) "✓" else ""))
            el.appendChild(span("lunarbor-lunicle-menu-label", row.label))
            el.addEventListener("mouseenter", { if (i != highlight) onHover(i) })
            el.addEventListener("mousedown", { ev ->
                ev.preventDefault()
                ev.stopPropagation()
                onPick(i)
            })
            element.appendChild(el)
        }
        if (rows.isEmpty()) element.appendChild(span("lunarbor-lunicle-menu-empty", emptyText))
        anchor = anchorRow
        if (!isShown) {
            document.body?.appendChild(element)
            document.addEventListener("mousedown", outside, true)
            window.addEventListener("scroll", onScroll, true)
        }
        reposition()
        (element.querySelector(".lunarbor-tag-menu-item-on") as? HTMLElement)?.asDynamic()?.scrollIntoView(js("({block: 'nearest'})"))
    }

    /** Hangs the popup under the anchor's issue line (above it when there is no room below). */
    fun reposition() {
        val row = anchor ?: return
        if (!isShown) return
        val line = (row.querySelector(".lunarbor-lunicle-issue-line, .lunarbor-lunicle-column-name") as? HTMLElement) ?: row
        val box = line.getBoundingClientRect()
        val rowBox = row.getBoundingClientRect()
        val height = element.offsetHeight.toDouble()
        val below = rowBox.bottom + 4
        val fits = below + height <= window.innerHeight
        element.style.left = "${box.left.coerceIn(4.0, (window.innerWidth - 240.0).coerceAtLeast(4.0))}px"
        element.style.top = if (fits) "${below}px" else "${(rowBox.top - height - 4).coerceAtLeast(4.0)}px"
    }

    /** Puts the popup back under [anchorRow] after a repaint rebuilt the row. */
    fun reanchor(anchorRow: HTMLElement) {
        anchor = anchorRow
        reposition()
    }

    /** Takes the popup off `<body>`. */
    fun hide() {
        if (!isShown) return
        element.parentNode?.removeChild(element)
        document.removeEventListener("mousedown", outside, true)
        window.removeEventListener("scroll", onScroll, true)
        anchor = null
    }

    /**
     * One drawn option.
     *
     * @property label `#high`, `@linus`, `Will not fix`.
     * @property checked The issue's current value (✓).
     * @property header A section header drawn above it, or `null`.
     */
    data class Row(val label: String, val checked: Boolean, val header: String? = null)

    private fun span(className: String, text: String): HTMLElement {
        val el = document.createElement("div") as HTMLElement
        el.className = className
        el.textContent = text
        return el
    }

    companion object {
        /** The popup's CSS, on top of the tag menu's (`PaneSearchBar`); appended by `lunicleBoardCss`. */
        val CSS: String = """
        .lunarbor-lunicle-menu {
            position: fixed;
            z-index: 10020;
            overflow-y: auto;
            box-sizing: border-box;
            border: 1px solid var(--t-border, #4a4a4a);
            background: var(--t-surface, #2a2a2a);
            box-shadow: 0 6px 20px rgba(0, 0, 0, 0.25);
            min-width: 230px;
            max-width: 340px;
            font-family: var(--dt-font-prop, system-ui, -apple-system, sans-serif);
            max-height: 320px;
            padding: 5px;
            border-radius: 9px;
        }
        .lunarbor-lunicle-menu-head {
            display: flex;
            justify-content: space-between;
            align-items: baseline;
            gap: 16px;
            padding: 4px 8px 6px;
            user-select: none;
        }
        .lunarbor-lunicle-menu-title {
            font-size: 13px;
            font-weight: 600;
            color: var(--t-accent, #e8825e);
        }
        .lunarbor-lunicle-menu-keys {
            font-size: 11px;
            color: var(--t-text-dim, #9a9a9a);
        }
        .lunarbor-lunicle-menu-section {
            padding: 6px 8px 2px;
            font-size: 11px;
            letter-spacing: 0.08em;
            text-transform: uppercase;
            color: var(--t-text-dim, #9a9a9a);
            user-select: none;
        }
        .lunarbor-lunicle-menu-item {
            display: flex;
            align-items: baseline;
            justify-content: flex-start;
            gap: 8px;
            padding: 5px 8px;
            border-radius: 4px;
            font-size: 14px;
            color: var(--t-text, #e6e6e6);
            cursor: pointer;
            user-select: none;
        }
        .lunarbor-lunicle-menu-item.lunarbor-tag-menu-item-on {
            background: var(--t-surface-alt, #333);
        }
        .lunarbor-lunicle-menu-check {
            width: 14px;
            flex: none;
            color: var(--t-accent, #e8825e);
        }
        .lunarbor-lunicle-menu-empty {
            padding: 5px 8px;
            font-size: 13px;
            color: var(--t-text-dim, #9a9a9a);
        }
        """
    }
}
