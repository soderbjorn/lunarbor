/*
 * AgentOutline.kt (commonMain)
 * ----------------------------
 * The outline text the MCP tools show agents and take back from them, and
 * its translation to and from `Document` rows.
 *
 * One node's items, one per line, nested by indentation (2 spaces):
 *
 * ```
 * * Buy oat milk
 * * Recipes  <!-- /Recipes -->
 * * Trip to **Lisbon**
 *   * Book flights
 * :::
 * **Packing**: passport, charger, adapter
 * :::
 * ```
 *
 *  - `* text` — a bullet (inline Markdown). On input `- ` and `+ ` work
 *    too, and any other line is a bullet as well.
 *  - `:::` … `:::` — a block of free Markdown (a longer fence when the
 *    content holds a `:::` line). Its content lines are taken verbatim,
 *    less the fence's indentation.
 *  - `<!-- /path -->` at the end of a bullet (or of a block's opening
 *    fence) — the item has children of its own, stored in that folder.
 *    It is the item's identity: keep it on a line to keep (or retitle)
 *    that node; drop the line to delete the node.
 *
 * Pure: no I/O, no state. commonMain only.
 */

package se.soderbjorn.lunarbor.mcp

import se.soderbjorn.lunarbor.data.SubtreeCodec
import se.soderbjorn.lunarbor.data.LunarborLink
import se.soderbjorn.lunarbor.main.BlockLayout
import se.soderbjorn.lunarbor.main.PaneBackingViewModel

/**
 * Text form of outline items for agents; see the file header.
 *
 * ### Callers
 * - [McpTools] renders nodes with [format] and reads an agent's edits
 *   with [parse], turning new items into rows with [rowsOf].
 */
object AgentOutline {

    /** What an [Item] is. */
    enum class Kind { BULLET, BLOCK, TEXT }

    /**
     * One outline item.
     *
     * @property kind Bullet, block, or (only when rendering a file that
     *   holds one) a stray non-bullet text line.
     * @property text A bullet's inline-Markdown title, or a text line's raw text.
     * @property content A block's content lines in on-disk form (code
     *   fences as text); empty for other kinds.
     * @property node Vault-relative folder (no leading slash) holding the
     *   item's children, or `null` for an item without one.
     * @property children Items nested under it.
     */
    data class Item(
        val kind: Kind,
        val text: String = "",
        val content: List<String> = emptyList(),
        val node: String? = null,
        val children: List<Item> = emptyList(),
    )

    /** A line of agent text that cannot be read; the message says why. */
    class ParseException(message: String) : Exception(message)

    /** Indentation per nesting level. */
    private val STEP: Int = PaneBackingViewModel.TAB_SIZE

    /** A trailing `<!-- /path -->` node annotation. */
    private val ANNOTATION = Regex("""\s*<!--\s*(/[^>]*?)\s*-->\s*$""")

    /** A bullet marker with its space (or alone). */
    private val BULLET = Regex("""^[*+-](\s|$)""")

    /** The annotation for the folder [node]: `<!-- /node -->`. */
    fun annotation(node: String): String = "<!-- /$node -->"

    /**
     * Renders [items] as agent text, children nested 2 spaces deeper.
     *
     * @param depth Nesting level of [items] (0 for a node's own items).
     */
    fun format(items: List<Item>, depth: Int = 0): String {
        val sb = StringBuilder()
        appendItems(sb, items, depth)
        return sb.toString().trimEnd('\n')
    }

    private fun appendItems(sb: StringBuilder, items: List<Item>, depth: Int) {
        for (item in items) {
            sb.append(formatOwn(item, depth)).append('\n')
            appendItems(sb, item.children, depth + 1)
        }
    }

    /**
     * The lines of [item] itself, without its children: the key that
     * tells whether an agent changed an item.
     */
    fun formatOwn(item: Item, depth: Int = 0): String {
        val pad = " ".repeat(depth * STEP)
        val note = item.node?.let { "  " + annotation(it) }.orEmpty()
        return when (item.kind) {
            Kind.BULLET -> "$pad* ${item.text}$note"
            Kind.TEXT -> pad + item.text
            Kind.BLOCK -> {
                val fence = SubtreeCodec.fenceFor(item.content)
                val sb = StringBuilder(pad).append(fence)
                if (item.node != null) sb.append(' ').append(annotation(item.node))
                for (c in item.content) sb.append('\n').append(if (c.isEmpty()) "" else pad + c)
                sb.append('\n').append(pad).append(fence)
                sb.toString()
            }
        }
    }

    /**
     * Reads agent text into items. Blank lines outside blocks are
     * skipped; tabs count as 2 spaces; a line nests under the closest
     * line above it that is indented less.
     *
     * @throws ParseException for a block that is never closed.
     */
    fun parse(text: String): List<Item> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        class Open(val col: Int, val kind: Kind, val text: String, val content: List<String>, val node: String?) {
            val children = ArrayList<Open>()
            // `this.`: the enclosing parse's `text` parameter would shadow the property.
            fun build(): Item = Item(this.kind, this.text, this.content, this.node, children.map { it.build() })
        }
        val roots = ArrayList<Open>()
        val stack = ArrayList<Open>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].replace("\t", " ".repeat(STEP))
            if (line.isBlank()) { i++; continue }
            val col = line.length - line.trimStart().length
            var body = line.substring(col).trimEnd()
            var node: String? = null
            ANNOTATION.find(body)?.let { m ->
                node = normalizePath(m.groupValues[1])
                body = body.substring(0, m.range.first).trimEnd()
            }
            val open: Open
            if (SubtreeCodec.isFence(body)) {
                val fence = body
                val close = (i + 1 until lines.size).firstOrNull { lines[it].trim() == fence }
                    ?: throw ParseException(
                        "The block opened on line ${i + 1} is never closed: end it with a line holding only $fence.",
                    )
                val content = (i + 1 until close).map { dedent(lines[it], col) }
                open = Open(col, Kind.BLOCK, "", content, node)
                i = close + 1
            } else {
                val m = BULLET.find(body)
                val title = if (m != null) body.substring(m.range.last + 1).trim() else body.trim()
                open = Open(col, Kind.BULLET, title, emptyList(), node)
                i++
            }
            while (stack.isNotEmpty() && stack.last().col >= col) stack.removeAt(stack.lastIndex)
            if (stack.isEmpty()) roots += open else stack.last().children += open
            stack += open
        }
        return roots.map { it.build() }
    }

    /** [line] with up to [col] leading spaces removed (tabs as 2 spaces). */
    private fun dedent(line: String, col: Int): String {
        val l = line.removeSuffix("\r").replace("\t", " ".repeat(STEP))
        var n = 0
        while (n < col && n < l.length && l[n] == ' ') n++
        return l.substring(n)
    }

    /**
     * The `Document` rows of [item] and its children, the item at column
     * [indent]. A block's code fences become code rows, as on load.
     */
    fun rowsOf(item: Item, indent: Int): List<String> {
        val out = ArrayList<String>()
        out += ownRowsOf(item, indent)
        for (child in item.children) out += rowsOf(child, indent + STEP)
        return out
    }

    /** The rows of [item] itself, at column [indent]. */
    fun ownRowsOf(item: Item, indent: Int): List<String> {
        val pad = " ".repeat(indent)
        return when (item.kind) {
            Kind.BULLET -> listOf("$pad* ${item.text}")
            Kind.TEXT -> listOf(pad + item.text)
            Kind.BLOCK -> {
                val contents = BlockLayout.rowContentsOf(item.content).ifEmpty { listOf("") }
                contents.mapIndexed { i, c -> if (i == 0) BlockLayout.firstLine(indent, c) else BlockLayout.nextLine(indent, c) }
            }
        }
    }

    /**
     * A path an agent gave → vault-relative form: no leading or trailing
     * slash, `""` for the vault root. Accepts `/Recipes/Soups`,
     * `Recipes/Soups` and `lunarbor:/…` links (percent-decoded).
     *
     * @throws ParseException for a malformed `lunarbor:` link, a `..` segment
     *   (nothing outside the vault can be named) or a dot segment (the
     *   trash and other hidden entries are off limits).
     */
    fun normalizePath(path: String): String {
        val t = path.trim()
        val raw = if (LunarborLink.isLunarborLink(t)) LunarborLink.parse(t) ?: throw ParseException("Not a valid lunarbor: link: $t") else t
        val segments = raw.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) throw ParseException("Paths cannot contain \"..\": $t")
        // Dot folders and files (the trash, .DS_Store, …) are not content.
        if (segments.any { it.startsWith(".") }) throw ParseException("Hidden files and folders (such as the trash) cannot be used: $t")
        return segments.joinToString("/")
    }
}
