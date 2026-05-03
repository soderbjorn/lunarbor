/*
 * StyleDropdown.kt (jsMain)
 * -------------------------
 * Click-to-open popover invoked by the Style toolbar button. Lists the
 * line-level styles (heading 1–3, quote) above a divider and the
 * selection-level inline styles (bold, italic, underline, strikethrough,
 * inline code) below. Each item shows an icon, a label, and a checkmark
 * when the style is currently active for the selection.
 *
 * The popover is appended to `document.body` with `position: fixed`,
 * anchored under the trigger button via `getBoundingClientRect`.
 * Outside-click and ESC dismiss it; clicking an item invokes the
 * matching intent on `MainViewModel`, then closes.
 *
 * One instance per pane — `AppShell.openStyleMenu(paneId)` keeps a map
 * keyed by pane id so each window/pane has its own dropdown bound to its
 * own view model.
 */

package se.soderbjorn.notegrow.main

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import se.soderbjorn.notegrow.data.InlineStyle
import se.soderbjorn.notegrow.data.LineStyle

/**
 * The style dropdown for one pane.
 *
 * @param viewModel The pane's `MainViewModel`. Style intents and active-
 *   state queries are routed through here.
 */
internal class StyleDropdown(private val viewModel: MainViewModel) {
    private var menuEl: HTMLElement? = null
    private var anchor: HTMLElement? = null
    private var documentMouseDownHandler: ((Event) -> Unit)? = null
    private var documentKeyDownHandler: ((Event) -> Unit)? = null

    /**
     * Open the menu anchored beneath [anchorEl]. Refreshes active-state
     * indicators each time. Dismissed by clicking anywhere outside, by
     * pressing ESC, or by selecting an item.
     */
    fun open(anchorEl: HTMLElement) {
        if (menuEl != null) {
            close()
        }
        anchor = anchorEl
        val menu = buildMenu()
        document.body?.appendChild(menu)
        menuEl = menu
        positionMenu(menu, anchorEl)
        attachDocumentDismiss()
    }

    /** Close the menu if it's open. Idempotent. */
    fun close() {
        menuEl?.let { it.parentNode?.removeChild(it) }
        menuEl = null
        anchor = null
        detachDocumentDismiss()
    }

    private fun buildMenu(): HTMLElement {
        val activeLine = viewModel.activeLineStyle()
        val activeInline = viewModel.activeInlineStyles()

        val menu = document.createElement("div") as HTMLElement
        menu.className = "notegrow-style-menu"
        menu.setAttribute("role", "menu")
        // Don't let mousedown inside the menu blur the editor or trigger
        // the outside-click dismiss.
        menu.addEventListener("mousedown", { e ->
            (e as MouseEvent).stopPropagation()
            e.preventDefault()
        })

        // ------- line-level items
        menu.appendChild(buildItem(
            label = "Heading 1",
            iconSvg = StyleDropdownIcons.H1,
            isActive = activeLine == LineStyle.HEADING_1,
        ) { viewModel.applyLineStyle(LineStyle.HEADING_1) })
        menu.appendChild(buildItem(
            label = "Heading 2",
            iconSvg = StyleDropdownIcons.H2,
            isActive = activeLine == LineStyle.HEADING_2,
        ) { viewModel.applyLineStyle(LineStyle.HEADING_2) })
        menu.appendChild(buildItem(
            label = "Heading 3",
            iconSvg = StyleDropdownIcons.H3,
            isActive = activeLine == LineStyle.HEADING_3,
        ) { viewModel.applyLineStyle(LineStyle.HEADING_3) })
        menu.appendChild(buildItem(
            label = "Quote",
            iconSvg = StyleDropdownIcons.QUOTE,
            isActive = activeLine == LineStyle.QUOTE,
        ) { viewModel.applyLineStyle(LineStyle.QUOTE) })

        // ------- divider
        val hr = document.createElement("hr") as HTMLElement
        hr.className = "notegrow-style-divider"
        menu.appendChild(hr)

        // ------- selection-level items
        menu.appendChild(buildItem(
            label = "Bold",
            iconSvg = StyleDropdownIcons.BOLD,
            isActive = InlineStyle.BOLD in activeInline,
        ) { viewModel.applyInlineStyle(InlineStyle.BOLD) })
        menu.appendChild(buildItem(
            label = "Italic",
            iconSvg = StyleDropdownIcons.ITALIC,
            isActive = InlineStyle.ITALIC in activeInline,
        ) { viewModel.applyInlineStyle(InlineStyle.ITALIC) })
        menu.appendChild(buildItem(
            label = "Underline",
            iconSvg = StyleDropdownIcons.UNDERLINE,
            isActive = InlineStyle.UNDERLINE in activeInline,
        ) { viewModel.applyInlineStyle(InlineStyle.UNDERLINE) })
        menu.appendChild(buildItem(
            label = "Strikethrough",
            iconSvg = StyleDropdownIcons.STRIKETHROUGH,
            isActive = InlineStyle.STRIKETHROUGH in activeInline,
        ) { viewModel.applyInlineStyle(InlineStyle.STRIKETHROUGH) })
        menu.appendChild(buildItem(
            label = "Inline code",
            iconSvg = StyleDropdownIcons.INLINE_CODE,
            isActive = InlineStyle.INLINE_CODE in activeInline,
        ) { viewModel.applyInlineStyle(InlineStyle.INLINE_CODE) })

        return menu
    }

    private fun buildItem(
        label: String,
        iconSvg: String,
        isActive: Boolean,
        onClick: () -> Unit,
    ): HTMLElement {
        val button = document.createElement("button") as HTMLElement
        button.className = "notegrow-style-item" + if (isActive) " is-active" else ""
        button.setAttribute("type", "button")
        button.setAttribute("role", "menuitem")
        button.innerHTML =
            "<span class=\"notegrow-style-icon\">$iconSvg</span>" +
            "<span class=\"notegrow-style-label\">$label</span>" +
            (if (isActive) "<span class=\"notegrow-style-check\">${StyleDropdownIcons.CHECK}</span>" else "")
        button.addEventListener("click", { e ->
            (e as MouseEvent).preventDefault()
            e.stopPropagation()
            onClick()
            close()
        })
        return button
    }

    private fun positionMenu(menu: HTMLElement, anchorEl: HTMLElement) {
        val rect = anchorEl.getBoundingClientRect()
        menu.style.top = "${rect.bottom + 4}px"
        // Right-align: the dropdown extends to the left of the trigger so
        // it never spills off-screen on a narrow toolbar.
        menu.style.left = "${(rect.right - 220).coerceAtLeast(8.0)}px"
    }

    private fun attachDocumentDismiss() {
        val mouseHandler: (Event) -> Unit = lambda@ { e ->
            val target = e.target as? Node ?: return@lambda
            val menu = menuEl ?: return@lambda
            if (menu.contains(target)) return@lambda
            if (anchor?.contains(target) == true) return@lambda
            close()
        }
        val keyHandler: (Event) -> Unit = lambda@ { e ->
            val ke = e as? KeyboardEvent ?: return@lambda
            if (ke.key == "Escape") close()
        }
        documentMouseDownHandler = mouseHandler
        documentKeyDownHandler = keyHandler
        document.addEventListener("mousedown", mouseHandler, /* capture = */ true)
        document.addEventListener("keydown", keyHandler, /* capture = */ true)
    }

    private fun detachDocumentDismiss() {
        documentMouseDownHandler?.let { document.removeEventListener("mousedown", it, /* capture = */ true) }
        documentKeyDownHandler?.let { document.removeEventListener("keydown", it, /* capture = */ true) }
        documentMouseDownHandler = null
        documentKeyDownHandler = null
    }
}
