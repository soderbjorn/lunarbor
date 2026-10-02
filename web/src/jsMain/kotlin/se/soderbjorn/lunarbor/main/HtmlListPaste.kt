/*
 * HtmlListPaste.kt (jsMain)
 * -------------------------
 * Reads a nested list out of pasted HTML. Outliners such as Dynalist and
 * Workflowy put a list on the clipboard twice: as HTML (`<ul><li>…`),
 * which carries the nesting, and as plain text, which does not — every
 * line there is flush left. Pasting the plain text alone flattened the
 * outline, so the paste handler (`MainScreen.handlePaste`) asks this file
 * for the list first.
 *
 * This file only maps DOM to [PastedListItem]s: the list structure and
 * the inline formatting as Markdown (bold, italic, strikethrough, code,
 * links). Turning the items into bullets is common code
 * ([pastedListText] → `bulletLinesForPaste`).
 *
 * Platform glue — no document logic.
 */

package se.soderbjorn.lunarbor.main

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.parsing.DOMParser

/**
 * The nested list in [html], or `null` when it holds none (no `<li>`),
 * in which case the caller pastes the plain text as before.
 *
 * Every `<ul>` / `<ol>` found outside a list item starts top-level items;
 * lists inside an `<li>` are its children. An item's text is its own
 * inline content as Markdown, whitespace collapsed to single spaces.
 *
 * Called by `MainScreen.handlePaste`.
 */
fun htmlListItems(html: String): List<PastedListItem>? {
    if (!html.contains("<li", ignoreCase = true)) return null
    val doc = DOMParser().parseFromString(html, "text/html")
    val body = doc.body ?: return null
    val items = collectLists(body)
    return items.ifEmpty { null }
}

/** Items of every list under [node] that is not inside another list item. */
private fun collectLists(node: Node): List<PastedListItem> {
    val out = ArrayList<PastedListItem>()
    val kids = node.childNodes
    for (i in 0 until kids.length) {
        val child = kids.item(i) as? Element ?: continue
        when (child.tagName.uppercase()) {
            "UL", "OL" -> out += listItems(child)
            "LI" -> out += listItem(child)
            else -> out += collectLists(child)
        }
    }
    return out
}

/** The `<li>` children of the list element [list]. */
private fun listItems(list: Element): List<PastedListItem> {
    val out = ArrayList<PastedListItem>()
    val kids = list.childNodes
    for (i in 0 until kids.length) {
        val child = kids.item(i) as? Element ?: continue
        when (child.tagName.uppercase()) {
            "LI" -> out += listItem(child)
            // A list directly inside a list (invalid, but some editors emit
            // it) nests under the item before it.
            "UL", "OL" -> {
                val nested = listItems(child)
                val prev = out.removeLastOrNull()
                if (prev == null) out += nested else out += prev.copy(children = prev.children + nested)
            }
        }
    }
    return out
}

/** One `<li>`: its own inline text, and the lists inside it as children. */
private fun listItem(li: Element): PastedListItem {
    val text = StringBuilder()
    appendInline(li, text)
    return PastedListItem(
        text = text.toString().replace(Regex("\\s+"), " ").trim(),
        children = collectLists(li),
    )
}

/**
 * Appends [node]'s inline content to [out] as Markdown, skipping nested
 * lists (they are the item's children, not its text).
 */
private fun appendInline(node: Node, out: StringBuilder) {
    val kids = node.childNodes
    for (i in 0 until kids.length) {
        val child = kids.item(i) ?: continue
        if (child.nodeType == Node.TEXT_NODE) {
            out.append(child.textContent ?: "")
            continue
        }
        val el = child as? Element ?: continue
        fun wrap(marker: String) {
            val inner = StringBuilder()
            appendInline(el, inner)
            val t = inner.toString()
            if (t.isBlank()) out.append(t) else out.append(marker).append(t.trim()).append(marker)
        }
        when (el.tagName.uppercase()) {
            "UL", "OL" -> {}
            "B", "STRONG" -> wrap("**")
            "I", "EM" -> wrap("*")
            "S", "DEL", "STRIKE" -> wrap("~~")
            "CODE" -> wrap("`")
            "BR" -> out.append(' ')
            "A" -> {
                val inner = StringBuilder()
                appendInline(el, inner)
                val href = el.getAttribute("href")
                if (href.isNullOrBlank()) out.append(inner) else out.append("[").append(inner.toString().trim()).append("](").append(href).append(")")
            }
            else -> appendInline(el, out)
        }
    }
}
