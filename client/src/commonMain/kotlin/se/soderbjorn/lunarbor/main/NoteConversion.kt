/* NoteConversion.kt (commonMain)
 *
 * "Convert to node" for a Markdown note in the folder contents list: the
 * outline rows that stand in for the note — a bullet titled with the
 * note's name, holding one child block with the note's Markdown. Also the
 * block rows for "Insert Markdown file as block" ([blockRowContentsOf]),
 * and the other way round, the bullets for "Convert block to nodes"
 * ([nodeGroupsOfBlock]).
 *
 * Pure: builds `Document.lines` rows (hidden block markers included, code
 * fences turned into code rows by [BlockLayout.rowContentsOf]) and nothing
 * else. `PaneBackingViewModel.convertNoteToNode` inserts them; the save
 * then gives the bullet its folder like any bullet that gains a child.
 * commonMain only. */
package se.soderbjorn.lunarbor.main

import se.soderbjorn.lunarbor.data.NoteRepository

/** Builds the rows that replace a Markdown note with a node. */
object NoteConversion {

    /**
     * The rows for the note [noteRel] with text [noteText], the bullet at
     * [indent]:
     *
     * - `* <title>` — the note's display name ([NoteRepository.displayNameOf]);
     * - a block one level deeper holding the note's lines, without a
     *   leading `# <title>` heading that only repeats the name (the bullet
     *   says it now) and without leading / trailing blank lines. An empty
     *   note still gets its (empty) block.
     *
     * Called by `PaneBackingViewModel.convertNoteToNode`.
     *
     * @param noteRel Vault-relative path of the `.md` note.
     * @param noteText The note's raw text as read from disk.
     * @param indent Item column of the new bullet (a multiple of 2).
     * @return The rows, bullet first; never empty.
     */
    fun nodeRowsFor(noteRel: String, noteText: String, indent: Int): List<String> {
        val title = NoteRepository.displayNameOf(noteRel)
        val contents = BlockLayout.rowContentsOf(blockContentOf(noteRel, noteText)).ifEmpty { listOf("") }
        val blockIndent = indent + PaneBackingViewModel.TAB_SIZE
        return listOf(" ".repeat(indent) + "* " + title) +
            contents.mapIndexed { i, c ->
                if (i == 0) BlockLayout.firstLine(blockIndent, c) else BlockLayout.nextLine(blockIndent, c)
            }
    }

    /**
     * The block row contents for the Markdown text [markdownText] taken in
     * whole (no heading dropped): its lines without leading / trailing
     * blank lines, code fences turned into code rows by
     * [BlockLayout.rowContentsOf]. Empty for blank text.
     *
     * Called by `PaneBackingViewModel.insertMarkdownAsBlock`.
     */
    fun blockRowContentsOf(markdownText: String): List<String> =
        BlockLayout.rowContentsOf(
            markdownText.replace("\r\n", "\n").split('\n')
                .dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() },
        )

    /**
     * The note's lines as they go into its block, in on-disk form (code
     * fences kept as text): without a leading `# <title>` heading that only
     * repeats the note's name, and without leading / trailing blank lines.
     *
     * Called by [nodeRowsFor] and by `NoteRepository.convertFolderTree`,
     * which writes the block straight into a node file.
     *
     * @param noteRel Vault-relative path of the `.md` note.
     * @param noteText The note's raw text as read from disk.
     */
    fun blockContentOf(noteRel: String, noteText: String): List<String> {
        val title = NoteRepository.displayNameOf(noteRel)
        var body = noteText.replace("\r\n", "\n").split('\n')
        if (body.isNotEmpty() && isTitleHeading(body.first(), title)) body = body.drop(1)
        return body.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
    }

    /**
     * "Convert block to nodes": the outline rows that replace a block
     * whose rows hold [rowContents], its item at column [indent]. One node
     * per paragraph:
     *
     * - blank rows separate paragraphs; a paragraph's lines are joined
     *   with single spaces into one bullet;
     * - a heading (`# …` to `###### …`) is a bullet of its own;
     * - every list item (`* `, `- `, `+ `, `1. `, `1) `) is a bullet of its
     *   own — the `*` / `-` / `+` marker dropped, a number kept — and a
     *   nested item is a child of the item above it; a paragraph indented
     *   under a list item is that item's child;
     * - a run of code rows stays a block (code rows and all), since a
     *   bullet cannot hold code — the child of a list item it is indented
     *   under.
     *
     * The first node is always at [indent]. An empty block gives one
     * empty bullet.
     *
     * Called by `TextEditingViewModel.convertBlockToNodesAt`.
     *
     * @param rowContents The block rows' contents after the block marker
     *   ([BlockLayout.contentOf]; code rows keep their [BlockLayout.CODE]).
     * @param indent Item column of the block.
     * @return One group per top-level node, each its rows (the node's own,
     *   then its descendants'), ready for `Document.lines`; never empty.
     */
    fun nodeGroupsOfBlock(rowContents: List<String>, indent: Int): List<List<String>> {
        class Node(val depth: Int, val text: StringBuilder? = null, val code: MutableList<String>? = null)
        val nodes = ArrayList<Node>()
        // Content indents of the open list items, outermost first.
        val listIndents = ArrayList<Int>()
        // The paragraph still taking lines, or `null` after a blank row,
        // a heading or code.
        var open: Node? = null
        var lastWasCode = false
        // Depth of a paragraph or code run indented by [lead]: the child of
        // the innermost open list item it is indented under, else top level.
        fun depthUnderList(lead: Int): Int {
            while (listIndents.isNotEmpty() && listIndents.last() >= lead) listIndents.removeAt(listIndents.lastIndex)
            return listIndents.size
        }
        for (c in rowContents) {
            if (c.startsWith(BlockLayout.CODE)) {
                val last = nodes.lastOrNull()
                if (lastWasCode && last?.code != null) last.code += c
                else {
                    // Indented under a list item, the code is that item's child.
                    val lead = c.length - 1 - c.substring(1).trimStart().length
                    nodes += Node(depthUnderList(lead), code = mutableListOf(c))
                }
                open = null
                lastWasCode = true
                continue
            }
            lastWasCode = false
            if (c.isBlank()) {
                open = null
                continue
            }
            val lead = c.length - c.trimStart().length
            val body = c.trim()
            val marker = LIST_MARKER.find(body)
            when {
                HEADING.containsMatchIn(body) -> {
                    listIndents.clear()
                    nodes += Node(0, StringBuilder(body))
                    open = null
                }
                marker != null -> {
                    while (listIndents.isNotEmpty() && listIndents.last() >= lead) listIndents.removeAt(listIndents.lastIndex)
                    val text = if (marker.value.first().isDigit()) body else body.substring(marker.value.length)
                    val node = Node(listIndents.size, StringBuilder(text.trim()))
                    listIndents += lead
                    nodes += node
                    open = node
                }
                open != null -> open.text?.append(' ')?.append(body)
                else -> {
                    val node = Node(depthUnderList(lead), StringBuilder(body))
                    nodes += node
                    open = node
                }
            }
        }
        if (nodes.isEmpty()) return listOf(listOf(" ".repeat(indent) + "* "))
        val groups = ArrayList<MutableList<String>>()
        for (n in nodes) {
            val col = indent + n.depth * PaneBackingViewModel.TAB_SIZE
            val rows = n.code?.mapIndexed { i, c ->
                if (i == 0) BlockLayout.firstLine(col, c) else BlockLayout.nextLine(col, c)
            } ?: listOf(" ".repeat(col) + "* " + n.text)
            if (n.depth == 0 || groups.isEmpty()) groups += rows.toMutableList() else groups.last() += rows
        }
        return groups
    }

    /** A Markdown heading's prefix. */
    private val HEADING = Regex("^#{1,6} ")

    /** A list item's marker with its space: `* `, `- `, `+ `, `1. `, `1) `. */
    private val LIST_MARKER = Regex("^([*+-]|\\d{1,9}[.)]) ")
}
