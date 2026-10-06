/*
 * SelectionHelper.kt
 * ------------------
 * Pure helpers used by the editing and navigation ViewModels: selection
 * normalization, caret-with-anchor moves, word/whitespace classification,
 * and small text predicates that depend only on the line text plus a
 * column index.
 *
 * commonMain — no DOM, no platform UI, no flows or coroutines. Everything
 * here is referentially transparent so the splitter could call it from
 * anywhere without worrying about state ownership.
 */

package se.soderbjorn.lunarbor.main

import se.soderbjorn.lunarbor.data.FolderName
import se.soderbjorn.lunarbor.data.InlineMarkdownTokenizer
import se.soderbjorn.lunarbor.data.LineMarkdownPrefix
import se.soderbjorn.lunarbor.data.LineStyle
import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.data.SearchNode
import se.soderbjorn.lunarbor.data.LunicleNode
import se.soderbjorn.lunarbor.data.SubtreeCodec
import se.soderbjorn.lunarbor.platform.toNfc

/**
 * Word-character predicate used by word movement and word selection.
 */
internal fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

/**
 * Computes the prefix (indent + `"* "`) that a newline should inherit
 * from [line] when Enter is pressed at [cursorCol]. Returns an empty
 * string for plain lines or when the caret is still at/before the
 * bullet marker (so pressing Enter at the very start of a bullet
 * produces a blank line, matching most editors).
 *
 * On a block row (TRF-5) the new row continues the block: the prefix is
 * the block's indent plus the [BlockLayout.NEXT] marker, so Enter adds a
 * line inside the block rather than a bullet — plus, on a list item
 * inside the block, the same list prefix (indent and `* ` / `- `), or on
 * a code row the [BlockLayout.CODE] marker and the row's indentation.
 */
internal fun continuationBulletPrefix(line: String, cursorCol: Int): String {
    val blockCol = BlockLayout.markerColumn(line)
    if (blockCol >= 0) {
        if (cursorCol <= blockCol) return ""
        // A code row continues the code at the same indentation.
        if (BlockLayout.isCodeLine(line)) {
            val textStart = blockCol + 2
            var end = textStart
            while (end < cursorCol && end < line.length && line[end] == ' ') end++
            return BlockLayout.nextLine(blockCol, BlockLayout.CODE + line.substring(textStart, end))
        }
        // A list item inside the block continues the list at its depth.
        val listPrefix = BlockLayout.listPrefixLength(line)
        val carried = if (listPrefix > 0 && cursorCol >= blockCol + 1 + listPrefix) {
            line.substring(blockCol + 1, blockCol + 1 + listPrefix)
        } else ""
        return BlockLayout.nextLine(blockCol, carried)
    }
    val indent = line.indexOfFirst { !it.isWhitespace() }
    if (indent < 0) return ""
    if (indent + 1 >= line.length) return ""
    if (line[indent] != '*' || line[indent + 1] != ' ') return ""
    if (cursorCol <= indent + 1) return ""
    return line.substring(0, indent) + "* "
}

/**
 * Rewrites multi-line pasted [text] for insertion at the caret of a
 * block row whose marker sits at [blockCol]: every line after the first
 * becomes a further row of the same block ([BlockLayout.nextLine]),
 * kept verbatim — block content is free Markdown, so indentation and
 * list markers are the user's own. `\r\n` and `\r` count as line
 * breaks. Pasted into a code row ([code]), every line becomes a code
 * row.
 *
 * Called by `TextEditingViewModel.insertText` when the caret is inside
 * a block.
 *
 * @return The text to insert at the caret; single-line [text] is
 *   returned unchanged.
 */
internal fun blockLinesForPaste(text: String, blockCol: Int, code: Boolean = false): String {
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    if ('\n' !in normalized) return text
    val lines = normalized.split("\n")
    val marker = if (code) BlockLayout.CODE.toString() else ""
    return lines.first() + lines.drop(1).joinToString("") { "\n" + BlockLayout.nextLine(blockCol, marker + it) }
}

/**
 * Rewrites multi-line pasted [text] so that, inserted at the caret of a
 * bullet at column [baseIndent], every line becomes its own bullet — an
 * outline never gets a non-bullet line (TRF-4).
 *
 * Rules:
 *  - `\r\n` and `\r` count as line breaks; blank lines after the
 *    first are dropped.
 *  - The first line joins the caret row. It is kept verbatim (it may be
 *    empty, when the text starts with a line break), except that a list
 *    item loses its indent and marker.
 *  - Every further line loses its indentation and any leading list
 *    marker (`* `, `- `, `+ `, or a bare `*`, `-`, `+`); tabs count as
 *    [PaneBackingViewModel.TAB_SIZE] spaces.
 *  - Every further line becomes `<indent>* <text>`. Its depth is its
 *    source indentation relative to a reference line — the first line
 *    when that line is itself a list item (so a copied bullet and its
 *    children keep their shape), otherwise the least-indented of the
 *    further lines (the first line was copied from mid-text and its
 *    depth is unknown). Depths are measured in [PaneBackingViewModel.TAB_SIZE]
 *    steps, never shallower than the caret row, and at most one level
 *    deeper than the line before, so no bullet skips a level.
 *
 * Called by `TextEditingViewModel.insertText` for outline documents.
 *
 * @param text The pasted text; single-line text is returned unchanged.
 * @param baseIndent Bullet column of the caret row (`0` at top level).
 * @return The text to insert at the caret; empty when [text] held only
 *   blank lines.
 */
internal fun bulletLinesForPaste(text: String, baseIndent: Int): String {
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    if ('\n' !in normalized) return text
    val tab = PaneBackingViewModel.TAB_SIZE
    class Parsed(val lead: Int, val isListItem: Boolean, val content: String)
    fun parse(raw: String): Parsed {
        var lead = 0
        var i = 0
        while (i < raw.length && (raw[i] == ' ' || raw[i] == '\t')) {
            lead += if (raw[i] == '\t') tab else 1
            i++
        }
        val rest = raw.substring(i)
        return when {
            rest.length >= 2 && rest[0] in "*-+" && rest[1] == ' ' -> Parsed(lead, true, rest.substring(2).trim())
            rest.length == 1 && rest[0] in "*-+" -> Parsed(lead, true, "")
            else -> Parsed(lead, false, rest.trim())
        }
    }
    val rawLines = normalized.split("\n")
    val first = parse(rawLines.first())
    val further = rawLines.drop(1).filter { it.isNotBlank() }.map(::parse)
    // The first line joins the caret row: verbatim, unless it is a list
    // item, whose indent and marker would otherwise land mid-bullet.
    val firstText = if (first.isListItem) first.content else rawLines.first().trimEnd()
    if (further.isEmpty()) return firstText
    val reference = if (first.isListItem) first.lead else further.minOf { it.lead }
    val sb = StringBuilder(firstText)
    var prevDepth = 0
    for (line in further) {
        val wanted = ((line.lead - reference).coerceAtLeast(0)) / tab
        val depth = minOf(wanted, prevDepth + 1)
        sb.append('\n')
        sb.append(" ".repeat(baseIndent + depth * tab)).append("* ").append(line.content)
        prevDepth = depth
    }
    return sb.toString()
}

/**
 * One item of a nested list read from pasted HTML (Dynalist, Workflowy,
 * a web page): its inline-Markdown text and the items nested under it.
 * Built by the web paste handler, which walks the `<ul>` / `<ol>` / `<li>`
 * structure; turned into pasteable text by [pastedListText].
 *
 * @property text The item's own text as inline Markdown, one line.
 * @property children Items nested under it, in order.
 */
data class PastedListItem(val text: String, val children: List<PastedListItem> = emptyList())

/**
 * Renders a pasted nested list as Markdown list lines, `* text`, each
 * nested level indented [PaneBackingViewModel.TAB_SIZE] more — the shape
 * [bulletLinesForPaste] turns back into bullets at the right depths (and
 * a block or `.md` note keeps as a Markdown list). A single item with no
 * children is just its text, so it pastes into the caret row like plain
 * text.
 *
 * Called by the web paste handler when the clipboard's HTML holds a list:
 * the plain-text flavour outliners put next to it has no indentation.
 */
fun pastedListText(items: List<PastedListItem>): String {
    val only = items.singleOrNull()
    if (only != null && only.children.isEmpty()) return only.text
    val lines = ArrayList<String>()
    fun add(item: PastedListItem, depth: Int) {
        lines += " ".repeat(depth * PaneBackingViewModel.TAB_SIZE) + "* " + item.text
        for (child in item.children) add(child, depth + 1)
    }
    for (item in items) add(item, 0)
    return lines.joinToString("\n")
}

/**
 * Detects the special case where the caret sits immediately after the
 * `"* "` of a bullet marker and there's nothing else on the line's
 * leading whitespace. Used by backspace so hitting Backspace on an
 * empty bullet removes the marker as one "character" rather than two.
 */
internal fun isAtBulletMarkerEnd(line: String, cursorCol: Int): Boolean {
    if (cursorCol < 2) return false
    if (line[cursorCol - 1] != ' ' || line[cursorCol - 2] != '*') return false
    for (i in 0 until cursorCol - 2) {
        if (line[i] != ' ') return false
    }
    return true
}

/**
 * Produces a new state with the caret at ([newRow], [newCol]) and either
 * grows the selection (when [extend] is `true`) or clears it.
 */
internal fun moved(
    state: PaneBackingViewModel.State,
    newRow: Int,
    newCol: Int,
    extend: Boolean
): PaneBackingViewModel.State {
    // Any cursor movement cancels armed inline styles — Cmd-B followed
    // by an arrow key should not silently style the next typed character.
    val noPending = if (state.pendingInlineStyles.isEmpty()) state
        else state.copy(pendingInlineStyles = emptySet())
    return if (extend) {
        val ar = noPending.anchorRow ?: noPending.cursorRow
        val ac = noPending.anchorCol ?: noPending.cursorCol
        noPending.copy(cursorRow = newRow, cursorCol = newCol, anchorRow = ar, anchorCol = ac)
    } else {
        noPending.copy(cursorRow = newRow, cursorCol = newCol, anchorRow = null, anchorCol = null)
    }
}

/**
 * `true` when [line] is an `# H1` whose visible text (Markdown markers
 * removed, trimmed) is exactly [title], compared in NFC so a decomposed
 * file name matches composed text. Used by
 * [PaneBackingViewModel.State.hidesTitleHeading] to spot a note whose
 * first line repeats its file name.
 *
 * @param line A note's first line.
 * @param title The note's display name ([NoteRepository.displayNameOf]).
 */
internal fun isTitleHeading(line: String, title: String): Boolean {
    if (LineMarkdownPrefix.detect(line, 0).style != LineStyle.HEADING_1) return false
    // NFC on both sides: a macOS file name spells `ö` as `o` + U+0308.
    val text = FolderName.plainTextOf(line).trim().toNfc()
    return text.isNotEmpty() && text == title.trim().toNfc()
}

/**
 * Rows [startRow]..[endRowInclusive] of [state] that are on screen:
 * folded subtrees, the rows a privacy mode hides and — with "Hide done
 * items" on — done items with their subtrees ([hiddenOnScreenIn]) left out, and large blocks the pane has not expanded
 * cut to their preview ([DocumentLayout.visibleRowsOf] with
 * [PaneBackingViewModel.State.expandedBlockIds]). A block the pane is
 * zoomed into always shows whole — it is the page.
 *
 * The one answer to "which rows are on screen": the paint loop (through
 * `PaneBackingViewModel.visibleRows`), caret movement ([nextVisibleRow] /
 * [prevVisibleRow]), `clampToVisible` and drag and drop all use it.
 */
internal fun visibleRowsIn(state: PaneBackingViewModel.State, startRow: Int, endRowInclusive: Int): List<Int> {
    val docState = state.documentState ?: return emptyList()
    val expanded = state.zoomedLineId?.let { state.expandedBlockIds + it } ?: state.expandedBlockIds
    return DocumentLayout.visibleRowsOf(
        docState.lines, docState.lineIds, state.collapsedIds, startRow, endRowInclusive, expanded,
        hiddenOnScreenIn(state),
    )
}

/**
 * Rows of [state]'s document that are never on screen: the privacy mode's
 * ([hiddenRowsIn]) plus, while the pane hides done items
 * ([PaneBackingViewModel.State.hideDone], LBR-24), every done item on the
 * page with its subtree ([DoneLayout.hiddenRows] from the row after the
 * zoom target's own rows, so a done zoom target still shows its children). `null` when none.
 *
 * Only for what is on screen ([visibleRowsIn], `hasChildrenOnScreen`,
 * `ensureVisibleRow`): the privacy mode's edit protections keep using
 * [hiddenRowsIn] alone, since hiding done items never blocks an edit.
 */
internal fun hiddenOnScreenIn(state: PaneBackingViewModel.State): BooleanArray? {
    val privacy = hiddenRowsIn(state)
    if (!state.hideDone || state.isMarkdownMode) return privacy
    val docState = state.documentState ?: return privacy
    // The page's own item (a zoomed bullet or block) always shows.
    val from = zoomInfoOf(state)?.let { DocumentLayout.itemLastRow(docState.lines, it.zoomRow) + 1 } ?: 0
    return DoneLayout.union(privacy, DoneLayout.hiddenRows(docState.lines, from))
}

/**
 * Rows of [state]'s document its privacy mode hides
 * ([PaneBackingViewModel.State.privacy], [PrivacyLayout.hiddenRows]), or
 * `null` when none. Only outlines hide rows: a `.md` note is hidden as a
 * whole file. Cached, so cheap to ask on every emission.
 */
internal fun hiddenRowsIn(state: PaneBackingViewModel.State): BooleanArray? {
    val docState = state.documentState ?: return null
    if (state.isMarkdownMode || !state.privacy.isActive) return null
    return PrivacyLayout.hiddenRows(docState.lines, state.privacy)
}

/**
 * Returns the smallest visible row index strictly greater than [fromRow], or `null`
 * if none exists. "Visible" means the row would be rendered by the paint loop given
 * the current zoom range and within-file fold state — so callers (cursor movement,
 * etc.) skip past collapsed subtrees instead of landing on hidden rows that
 * `clampToVisible` would then bounce back into the parent.
 */
internal fun nextVisibleRow(state: PaneBackingViewModel.State, fromRow: Int): Int? {
    val docState = state.documentState ?: return null
    val zoom = zoomInfoOf(state)
    val startRow = zoom?.startRow ?: state.firstEditableRow
    val endRowInclusive = zoom?.endRowInclusive ?: docState.lines.lastIndex
    if (endRowInclusive < startRow) return null
    val visible = visibleRowsIn(state, startRow, endRowInclusive)
    return visible.firstOrNull { it > fromRow }
}

/**
 * Returns the largest visible row index strictly less than [fromRow], or `null` if
 * none exists. Symmetric to [nextVisibleRow] — see that doc for rationale.
 */
internal fun prevVisibleRow(state: PaneBackingViewModel.State, fromRow: Int): Int? {
    val docState = state.documentState ?: return null
    val zoom = zoomInfoOf(state)
    val startRow = zoom?.startRow ?: state.firstEditableRow
    val endRowInclusive = zoom?.endRowInclusive ?: docState.lines.lastIndex
    if (endRowInclusive < startRow) return null
    val visible = visibleRowsIn(state, startRow, endRowInclusive)
    return visible.lastOrNull { it < fromRow }
}

/**
 * One ancestor entry in the zoom breadcrumb chain. Returned by
 * [bulletAncestorsOf] from outermost-to-innermost order so the view can
 * render a `Root / outer / … / inner / current` style trail.
 *
 * @property lineId    stable id of the ancestor bullet — pass to
 *   [PaneBackingViewModel.zoomTo] to navigate there.
 * @property titleText display text of the bullet with the leading indent,
 *   the `"* "` bullet marker, AND any line-level markdown prefix (e.g.
 *   `# `, `> `) stripped. Inline markers (`**`, `*`, `` ` ``, etc.) are
 *   left intact — consumers that want a flat label should run the text
 *   through [se.soderbjorn.lunarbor.data.InlineMarkdownTokenizer]. Empty
 *   when the bullet has no text beyond its markers.
 * @property style detected line-level style for this bullet (heading
 *   level or quote), or `null` when the bullet is plain text. The
 *   breadcrumb currently ignores this; it exists so future renderers
 *   could decorate ancestor segments with the style without re-parsing.
 */
data class BreadcrumbAncestor(
    val lineId: LineId,
    val titleText: String,
    val style: LineStyle? = null,
)

/**
 * Walks the bullet hierarchy upward from the currently zoomed line and
 * returns each ancestor (parent, grandparent, …) in outer-to-inner
 * order. The zoomed line itself is *not* included — the view already
 * renders it as the trailing breadcrumb segment via
 * [PaneBackingViewModel.ZoomInfo.titleText].
 *
 * "Ancestor" means: a bullet line above the zoom target whose indent is
 * strictly less than the running indent, walking up the document. The
 * first such line is the immediate parent; the next-smaller-indent
 * bullet above that is the grandparent, and so on, until we reach
 * indent 0 (a root-level bullet) or the top of the document.
 *
 * Used by the editor header to render a clickable breadcrumb trail —
 * each segment can call back into `zoomTo(ancestor.lineId)` so the user
 * navigates one level up at a time without losing the current zoom
 * scope when only a partial ascent is wanted.
 *
 * @param state current view-model state
 * @return ancestors outer-to-inner, or an empty list when not zoomed.
 */
internal fun bulletAncestorsOf(
    state: PaneBackingViewModel.State
): List<BreadcrumbAncestor> {
    val zoom = zoomInfoOf(state) ?: return emptyList()
    val docState = state.documentState ?: return emptyList()
    val lines = docState.lines
    val ids = docState.lineIds
    if (zoom.zoomIndent <= 0) return emptyList()

    val collected = mutableListOf<BreadcrumbAncestor>()
    var lookingFor = zoom.zoomIndent
    var row = zoom.zoomRow - 1
    while (row >= 0 && lookingFor > 0) {
        val indent = DocumentLayout.itemColumn(lines, row)
        if (indent in 0 until lookingFor) {
            val rawTitle = SubtreeCodec.itemTitleOf(lines, row)
            val prefix = LineMarkdownPrefix.detect(rawTitle, 0)
            val title = rawTitle.substring(prefix.markerEnd)
            collected += BreadcrumbAncestor(
                lineId = ids[row],
                titleText = title,
                style = prefix.style,
            )
            lookingFor = indent
        }
        row--
    }
    return collected.asReversed()
}

/**
 * Resolves [PaneBackingViewModel.State.zoomedLineId] to concrete
 * row geometry. Returns `null` if there is no zoom, the id is no longer
 * in the document, or its row no longer starts an item.
 *
 * A zoomed block item's own rows are part of the zoom region: they are
 * the page's body, shown above its children ([PaneBackingViewModel.ZoomInfo.startRow]
 * is the block's first row). A bullet's row is not — its text is the
 * zoom headline.
 */
internal fun zoomInfoOf(
    state: PaneBackingViewModel.State
): PaneBackingViewModel.ZoomInfo? {
    val id = state.zoomedLineId ?: return null
    val docState = state.documentState ?: return null
    val row = docState.lineIds.indexOf(id)
    if (row < 0) return null
    val lines = docState.lines
    if (row !in lines.indices) return null
    val indent = DocumentLayout.itemColumn(lines, row)
    if (indent < 0) return null
    // The zoom region is exactly the item's subtree. Every row an edit
    // creates inside it is a bullet or block row at a deeper indent, so
    // there is no stray row that needs the region stretched to reach it.
    val end = DocumentLayout.subtreeEnd(lines, row, indent)
    val isBlock = BlockLayout.isBlockLine(lines[row])
    val rawTitle = SubtreeCodec.itemTitleOf(lines, row)
    val prefix = LineMarkdownPrefix.detect(rawTitle, 0)
    val titleText = rawTitle.substring(prefix.markerEnd)
    return PaneBackingViewModel.ZoomInfo(
        zoomRow = row,
        zoomIndent = indent,
        startRow = if (isBlock) row else row + 1,
        endRowInclusive = end,
        titleText = titleText,
        style = prefix.style,
        isSearchNode = !isBlock && SearchNode.queryOf(rawTitle) != null,
        isBoardNode = !isBlock && state.lunicleEnabled && LunicleNode.refOf(rawTitle) != null,
    )
}

/**
 * Builds the human-readable breadcrumb segments for the current zoom path.
 *
 * Returned segments are outer-to-inner: ancestors first (root-most ancestor
 * at index 0), then the zoom target itself last. Empty when the document is
 * not currently zoomed — callers should fall back to the document/pane title
 * in that case.
 *
 * Used by AppShell to drive both the toolkit pane header title and the
 * sidebar pane row label so they stay in sync as the user zooms in/out.
 *
 * @param state current view-model state
 * @return outer-to-inner segments, or an empty list when not zoomed.
 */
internal fun zoomPathSegmentsOf(
    state: PaneBackingViewModel.State
): List<String> {
    val zoom = zoomInfoOf(state) ?: return emptyList()
    val ancestors = bulletAncestorsOf(state)
    // Each segment's source text has line-level markers stripped already
    // (by `bulletAncestorsOf` / `zoomInfoOf`). Run it through the inline
    // tokenizer so `**bold**`, `[label](href)`, `#tag`, etc. collapse to
    // their visible text — the sidebar pane label shows a clean string.
    // A search node's `{{search: …}}` is its query, not part of its name,
    // and `#tags` label the item rather than name it (FolderName.withoutTags).
    return (ancestors.map { it.titleText } + zoom.titleText)
        .map { FolderName.withoutTags(InlineMarkdownTokenizer.tokenize(LunicleNode.stripQueries(it))) }
}

/**
 * One file-level segment of a pane's breadcrumb: the vault root, a node
 * folder on the way down, or the open file itself.
 *
 * @property label display name (`Home` for the root, a decoded folder
 *   name, or a note / image name — see [NoteRepository.displayNameOf]).
 * @property fileRel the vault-relative file this segment opens: a node
 *   folder's `_node.md`, or the open `.md` note / image itself.
 */
data class FileCrumb(
    val label: String,
    val fileRel: String,
)

/**
 * The file part of a pane's breadcrumb for [fileRel]: the vault root,
 * then one segment per node folder down to the open file, root-most
 * first. The last segment is always [fileRel] itself.
 *
 *  - [rootFileName]                   → `Home`
 *  - `Recipes/Pasta/_node.md`   → `Home`, `Recipes`, `Pasta`
 *  - `Recipes/notes.md`               → `Home`, `Recipes`, `notes`
 *  - `notes.md` (at the vault root)   → `Home`, `notes`
 *
 * Path-based, like [PaneBackingViewModel.parentFileOf]: every segment
 * but the last opens that folder's outline, whether or not it exists.
 *
 * Called by the web pane header (through
 * [PaneBackingViewModel.fileBreadcrumb]), which follows these segments
 * with the zoom ancestors so one breadcrumb spans folders and bullets,
 * and by the web sidebar's pane labels (`AppShell.paneSidebarLabel`).
 *
 * @param fileRel the pane's `activeFileRel`; empty means "no file yet".
 * @param rootFileName the root outline's path ([DocumentRegistry.rootFileName]).
 * @return root-most first; just the root for an empty or root [fileRel].
 */
fun fileBreadcrumbOf(fileRel: String, rootFileName: String): List<FileCrumb> {
    val root = FileCrumb(NoteRepository.ROOT_DISPLAY_NAME, rootFileName)
    if (fileRel.isEmpty() || fileRel == rootFileName) return listOf(root)
    val isOutline = NoteRepository.isOutlineFile(fileRel)
    val folder = if (isOutline) NoteRepository.folderOfOutline(fileRel) else fileRel.substringBeforeLast('/', "")
    val out = mutableListOf(root)
    if (folder.isNotEmpty()) {
        val parts = folder.split('/')
        for (i in parts.indices) {
            val dir = parts.subList(0, i + 1).joinToString("/")
            out += FileCrumb(FolderName.decode(parts[i]), NoteRepository.outlineFileOf(dir))
        }
    }
    if (!isOutline) out += FileCrumb(NoteRepository.displayNameOf(fileRel), fileRel)
    return out
}

/**
 * Normalizes an anchor + cursor pair into a [PaneBackingViewModel.Selection].
 * Returns `null` when there is no anchor (caret only) or when the anchor
 * and caret coincide (empty selection).
 */
internal fun selectionOf(
    state: PaneBackingViewModel.State
): PaneBackingViewModel.Selection? {
    val ar = state.anchorRow ?: return null
    val ac = state.anchorCol ?: return null
    if (ar == state.cursorRow && ac == state.cursorCol) return null
    return if (ar < state.cursorRow || (ar == state.cursorRow && ac <= state.cursorCol))
        PaneBackingViewModel.Selection(ar, ac, state.cursorRow, state.cursorCol)
    else
        PaneBackingViewModel.Selection(state.cursorRow, state.cursorCol, ar, ac)
}
