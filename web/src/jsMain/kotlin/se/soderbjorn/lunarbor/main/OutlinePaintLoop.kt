/*
 * OutlinePaintLoop.kt (jsMain)
 * ----------------------------
 * Renders the editor's row list into the `contenteditable` host. The
 * browser handles glyph rendering, caret, selection, and wrap; this
 * module only emits the structural DOM (one `<div data-row="N">` per
 * visible row, plus a non-editable bullet glyph and an optional
 * disclosure chevron). Caret restoration after a reconciled paint lives
 * in `MainScreen` since it has access to the captured DOM selection
 * snapshot.
 *
 * Each rendered row is one of three shapes. In an outline (a `_node.md`
 * node, `Document.bulletsOnly`) every row is a bullet (TRF-4) or a block
 * row (TRF-5). The plain shape is kept for plain Markdown files
 * (`Starred.md`, and the Markdown mode of TRF-7); the editing intents
 * never produce it in an outline.
 *
 * In Markdown mode (`PaneBackingViewModel.State.isMarkdownMode`, a `.md`
 * note) a `* item` line still draws as a list bullet, but with no bullet
 * behaviour: no chevron, no count badge, and the dot is not a drag or
 * zoom handle.
 *
 * Inline images resolve their `src` against the folder the row is stored
 * in (`MainViewModel.resolveImageSrc`, rules in `ImagePaths`), so a
 * pasted image's bare file name finds the file in the node's folder.
 *
 * A vault link whose target no longer exists (`MainViewModel.isLinkBroken`,
 * TRF-8) is drawn struck through with a "not found" tooltip
 * ([markBrokenLinks]); the text is left exactly as it is.
 *
 *   plain:       <div data-row="N" data-prefix-len="0">
 *                  <span class="text">{line}</span>
 *                </div>
 *
 *   block row:   <div data-row="N" data-prefix-len="P" class="lunarbor-block-row …"
 *                     style="margin-left:calc(Xpx + 1.15em)">
 *                  [<span class="lunarbor-block-item-dot">•</span>]  (first row only)
 *                  [<span class="lunarbor-block-delete">×</span>]    (first row only)
 *                  [<span class="lunarbor-block-list-dot">• </span>] (list item `* x`)
 *                  <span class="text">{line.substring(P)}</span>
 *                </div>
 *
 * A block is drawn as one bordered rectangle by giving its rows side
 * borders and its first/last rows the top/bottom border, so it grows with
 * its content while every line stays an ordinary editable row. The hidden
 * block marker ([BlockLayout]) sits before `data-prefix-len` and never
 * reaches the DOM.
 *
 *   bullet:      <div data-row="N" data-prefix-len="P" style="padding-left:calc(Xpx + 1.15em); text-indent:-1.15em">
 *                  <span class="bullet-prefix" contenteditable="false">
 *                    <span class="bullet">•</span><span> </span>
 *                  </span>
 *                  <span class="text">{line.substring(P)}</span>
 *                </div>
 *
 * `data-prefix-len` is the column the editable text span starts at in the
 * **raw** model line — `MainScreen` uses it to translate DOM offsets
 * to/from logical `(row, col)` pairs. When the editor is zoomed, the
 * display strips a leading `viewOriginCol` chars off each line, but the
 * stored prefix-len still includes those stripped chars so model columns
 * stay in absolute coordinates. The bullet prefix is `contenteditable="false"`
 * so the browser refuses to put the caret inside it.
 *
 * Style installation lives here too, alongside the painter that consumes
 * the styles.
 */

package se.soderbjorn.lunarbor.main

import se.soderbjorn.lunarbor.data.NoteRepository
import se.soderbjorn.lunarbor.demo.demoFileSystem

import kotlinx.browser.document
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import kotlinx.browser.window
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.events.MouseEvent
import se.soderbjorn.lunula.web.showConfirmDialog
import se.soderbjorn.lunarbor.data.InlineMarkdownTokenizer
import se.soderbjorn.lunarbor.data.InlineStyle
import se.soderbjorn.lunarbor.data.LineMarkdownPrefix
import se.soderbjorn.lunarbor.data.LineStyle
import se.soderbjorn.lunarbor.data.StyledRun
import se.soderbjorn.lunarbor.data.WikiLink
import se.soderbjorn.lunarbor.data.LunarborLink

/**
 * Per-row mapping between displayed (markers-stripped) text and the
 * underlying model line, scoped to the editable region (the
 * `.lunarbor-text` wrapper inside the row's div).
 *
 * Both arrays are 0-indexed against the editable region; callers must
 * add `data-prefix-len` to translate to absolute model columns.
 *
 * @property modelToDom Editable-relative model column → display column.
 *   Length is `editableLen + 1`. Marker columns (line-level prefix and
 *   inline `**`/`*`/etc.) collapse to neighbouring display columns.
 * @property domToModel Display column → editable-relative model column.
 *   Length is `displayLen + 1`.
 * @property markerCols Editable-relative model columns occupied by
 *   marker characters. The caret-snap logic skips these.
 */
internal class RowColumnMap(
    val modelToDom: IntArray,
    val domToModel: IntArray,
    val markerCols: Set<Int>,
)

/**
 * Property name under which each row div carries its [RowColumnMap].
 * Stored on the element rather than in a module-level map: every pane
 * paints into its own editor, and a shared map cleared on each paint let
 * a second pane showing the same document wipe the first pane's maps —
 * the caret mapping then ignored hidden heading markers (`## `) and
 * typing in a heading landed at its start.
 */
private const val ROW_COLUMN_MAP_KEY = "__lunarborColumnMap"

/** Look up the column map for [rowDiv], if it has one. */
internal fun rowColumnMapOf(rowDiv: HTMLElement): RowColumnMap? =
    rowDiv.asDynamic()[ROW_COLUMN_MAP_KEY].unsafeCast<RowColumnMap?>()

/**
 * Renders the "Loading…" placeholder into [editor]. Used on cold start
 * until the document is read from disk.
 */
fun paintLoading(editor: HTMLElement) {
    editor.innerHTML = ""
    val loading = document.createElement("div") as HTMLElement
    loading.textContent = "Loading…"
    loading.style.opacity = "0.6"
    loading.setAttribute("contenteditable", "false")
    editor.appendChild(loading)
}

/**
 * Repaints [editor] for [state]. Builds one `<div>` per visible row
 * (rows hidden by collapse or zoom are omitted), and emits the bullet
 * glyph + chevron as non-editable adornments inside that row.
 *
 * The editor's `contenteditable` flag is set on the host once in
 * `MainScreen`; this function does not touch it. Caret/selection
 * restoration is the caller's responsibility (see
 * `MainScreen.reconcileAndRestore`).
 */
fun paint(
    editor: HTMLElement,
    state: PaneBackingViewModel.State,
    viewModel: MainViewModel,
    style: EditorStyle,
    onBulletMouseDown: ((absoluteRow: Int, ev: MouseEvent) -> Unit)? = null,
) {
    // Read before the rebuild: the chunks' boundaries and heights carry over.
    val oldChunks = measuredChunks(editor, state.activeFileRel)
    editor.innerHTML = ""
    if (!state.isLoaded) {
        paintLoading(editor)
        return
    }
    val docState = state.documentState ?: return
    val zoom = viewModel.zoomInfo(state)
    // Not zoomed: from the first row, or the second when a note's
    // `# H1` repeats the page title (State.hidesTitleHeading).
    val startRow = zoom?.startRow ?: state.firstEditableRow
    val endRowInclusive = zoom?.endRowInclusive ?: state.lines.lastIndex
    // Zoomed into a search node: its text is the page title, so its
    // results head the page, above any bullets of its own.
    if (zoom != null && !state.isMarkdownMode) {
        val zoomId = docState.lineIds.getOrNull(zoom.zoomRow)
        val searchView = viewModel.searchNodeOf(state, zoom.zoomRow)
        if (zoomId != null && searchView != null) {
            editor.appendChild(buildSearchNodeResults(searchView, zoom.zoomRow, viewModel, style, isPage = true))
        }
    }
    if (endRowInclusive < startRow) return

    // When zoomed, indents render relative to the zoom target so the
    // closest descendants paint flush-left rather than indented one level
    // under the (no longer visible) zoom target. We strip `zoomIndent +
    // TAB_SIZE` characters: every descendant in the zoom subtree is a
    // bullet with indent strictly greater than `zoomIndent`, and
    // TAB_SIZE-aligned descendants always have at least
    // `zoomIndent + TAB_SIZE` leading characters.
    val viewOriginCol = zoom?.let { it.zoomIndent + PaneBackingViewModel.TAB_SIZE } ?: 0

    // Folds, and large blocks cut to their preview.
    val visibleRows = viewModel.visibleRows(state, startRow, endRowInclusive)
    // Row → the block it belongs to, so each block row knows whether it
    // draws the block's top or bottom edge.
    val blockOfRow = HashMap<Int, IntRange>()
    for (block in BlockLayout.blocksOf(docState.lines)) for (r in block) blockOfRow[r] = block
    // Zoomed into a block item: its own rows head the page as its body,
    // one level shallower than its children.
    val zoomBody = zoom?.let { z -> blockOfRow[z.zoomRow]?.takeIf { it.first == z.zoomRow } }
    val rowAppender = RowAppender(editor, chunked = visibleRows.size > CHUNK_MIN_ROWS, style, oldChunks)
    for (row in visibleRows) {
        val rawLine = state.lines[row]
        if (zoomBody != null && row in zoomBody) {
            val strip = zoom.zoomIndent
            rowAppender.add(
                buildRowElement(
                    row, rawLine.substring(strip), strip, state, docState, viewModel, style,
                    zoomBody, onBulletMouseDown, isZoomBody = true,
                ),
                docState.lineIds[row],
            )
            continue
        }
        // Only strip the zoom indent when the row actually has at least
        // `viewOriginCol` leading whitespace chars. Every row of the zoom
        // region is nested deeper than the zoom target, so this holds for
        // all TAB_SIZE-aligned bullets; the guard only protects a
        // hand-edited, oddly indented line from losing real characters
        // (which would misroute input through the model<->DOM mapping).
        val canStrip = viewOriginCol > 0 &&
            rawLine.length >= viewOriginCol &&
            (0 until viewOriginCol).all { rawLine[it] == ' ' }
        val stripPrefix = if (canStrip) viewOriginCol else 0
        val line = if (stripPrefix > 0) rawLine.substring(stripPrefix) else rawLine
        rowAppender.add(
            buildRowElement(
                row, line, stripPrefix, state, docState, viewModel, style, blockOfRow[row], onBulletMouseDown
            ),
            docState.lineIds[row],
        )
    }
    rowAppender.finish()

    // Empty-document affordance. A fresh/empty outline loads as a single
    // empty bullet (a plain file as a blank line); without a cue the pane
    // looks like a dead area (there is no "create your first note" flow —
    // you type into the root document directly). Overlay a faint,
    // non-editable, click-through hint on that row. The first keystroke
    // repaints and it vanishes. Guarded to the unzoomed root-empty case
    // so it never covers real content.
    val onlyLine = docState.lines.singleOrNull()
    if (zoom == null && onlyLine != null && (onlyLine.isEmpty() || DocumentLayout.isEmptyBulletLine(onlyLine))) {
        (editor.querySelector("[data-row]") as? HTMLElement)?.let { firstRow ->
            val hint = document.createElement("span") as HTMLElement
            hint.className = "lunarbor-empty-hint"
            hint.textContent =
                if (state.isMarkdownMode) "Type here to start writing…" else "Type here to start your outline…"
            hint.setAttribute("contenteditable", "false")
            // Start the hint where the text starts — right of the bullet
            // glyph on an empty bullet — instead of covering the glyph.
            val textSpan = firstRow.querySelector(".lunarbor-text") as? HTMLElement
            if (textSpan != null) hint.style.left = "${textSpan.offsetLeft}px"
            firstRow.appendChild(hint)
        }
    }
}

/**
 * Rows per chunk on a long page (see [RowAppender]).
 */
private const val ROWS_PER_CHUNK: Int = 64

/** Pages with more visible rows than this are painted in chunks ([RowAppender]). */
private const val CHUNK_MIN_ROWS: Int = 2 * ROWS_PER_CHUNK

/** Class of the chunk wrappers [rowAppender] groups rows into. */
internal const val ROW_CHUNK_CLASS: String = "lunarbor-chunk"

/** Attribute on a chunk: the [LineId] value of its first row. */
private const val CHUNK_FIRST_ID_ATTR: String = "data-chunk-first"

/** Attribute on a chunk: how many rows it holds. */
private const val CHUNK_ROWS_ATTR: String = "data-chunk-rows"

/**
 * A chunk of the previous paint: its height on screen (real, or the
 * remembered size of a skipped one) and its row count.
 */
private class MeasuredChunk(val height: Double, val rows: Int)

/** Property on the editor element holding its chunk-height cache ([measuredChunks]). */
private const val CHUNK_CACHE_KEY: String = "__lunarborChunkHeights"

/** Property on the editor element: the file its chunks were painted for. */
private const val CHUNK_FILE_KEY: String = "__lunarborChunkFile"

/** Chunks the cache keeps, over all files, before it starts over. */
private const val CHUNK_CACHE_MAX: Int = 20_000

/**
 * The chunk heights [editor] knows for [fileRel], by each chunk's first
 * row's [LineId] value (ids are per document, hence per file): the
 * chunks it shows now, measured before [paint] rebuilds them, on top of
 * those of earlier paints — so coming back to a long page (Back, a link)
 * sizes its off-screen chunks as they were and the remembered scroll
 * position lands where it was. Reading a chunk's own box never lays out
 * its skipped rows.
 *
 * @param fileRel The file about to be painted.
 */
private fun measuredChunks(editor: HTMLElement, fileRel: String): Map<Long, MeasuredChunk> {
    val dyn = editor.asDynamic()
    var cache = dyn[CHUNK_CACHE_KEY].unsafeCast<HashMap<String, HashMap<Long, MeasuredChunk>>?>()
    if (cache == null || cache.values.sumOf { it.size } > CHUNK_CACHE_MAX) {
        cache = HashMap()
        dyn[CHUNK_CACHE_KEY] = cache
    }
    val paintedFile = dyn[CHUNK_FILE_KEY].unsafeCast<String?>()
    val chunks = editor.querySelectorAll(":scope > .$ROW_CHUNK_CLASS")
    if (paintedFile != null && chunks.length > 0) {
        val known = cache.getOrPut(paintedFile) { HashMap() }
        for (i in 0 until chunks.length) {
            val c = chunks.item(i) as? HTMLElement ?: continue
            val id = c.getAttribute(CHUNK_FIRST_ID_ATTR)?.toLongOrNull() ?: continue
            val rows = c.getAttribute(CHUNK_ROWS_ATTR)?.toIntOrNull() ?: continue
            known[id] = MeasuredChunk(c.getBoundingClientRect().height, rows)
        }
    }
    dyn[CHUNK_FILE_KEY] = fileRel
    return cache[fileRel] ?: emptyMap()
}

/**
 * How [paint] adds row elements to the editor: directly, or — on a long
 * page ([chunked]) — in chunk wrappers with `content-visibility: auto`.
 * Chromium then skips style, layout, paint and hit-testing for every
 * chunk off screen; without it, scrolling a page of thousands of rows
 * hit-tested every row's layer on each frame (12k rows: 25–35 ms frames,
 * a crawl). `overflow-clip-margin` lets the parts drawn left of a row
 * (fold control, dots) through the chunk's paint clip.
 *
 * A skipped chunk is as tall as its `contain-intrinsic-size`, and every
 * repaint builds new chunks, so the heights must carry over or the page
 * would shift under the scroll position on each keystroke: a chunk starts
 * at a row that started one in an earlier paint ([previous], by line
 * id) and takes that chunk's measured height, scaled by its row count.
 * Fresh chunks hold [ROWS_PER_CHUNK] rows; a carried one grows to at
 * most twice that before it splits.
 *
 * Rows stay found by `[data-row]` / `closest`, never as the editor's
 * direct children; code that walks rows by sibling must cross chunks
 * ([previousRowElement]). Short pages are painted without chunks.
 *
 * @param editor The editor [paint] has just cleared.
 * @param chunked `true` on a long page ([CHUNK_MIN_ROWS]).
 * @param style Gives the row height for a fresh chunk's estimate.
 * @param previous Chunks of earlier paints ([measuredChunks]).
 */
private class RowAppender(
    private val editor: HTMLElement,
    private val chunked: Boolean,
    private val style: EditorStyle,
    private val previous: Map<Long, MeasuredChunk>,
) {
    private var chunk: HTMLElement? = null
    private var carried: MeasuredChunk? = null
    private var count = 0

    /** Adds [row], the element of the row with line id [id]. */
    fun add(row: HTMLElement, id: LineId) {
        if (!chunked) {
            editor.appendChild(row)
            return
        }
        val limit = if (carried != null) 2 * ROWS_PER_CHUNK else ROWS_PER_CHUNK
        if (chunk == null || count >= limit || (count > 0 && id.value in previous)) open(id)
        chunk!!.appendChild(row)
        count++
    }

    /** Sizes the last chunk. Call once after the last [add]. */
    fun finish() = close()

    private fun open(id: LineId) {
        close()
        val c = document.createElement("div") as HTMLElement
        c.className = ROW_CHUNK_CLASS
        c.setAttribute(CHUNK_FIRST_ID_ATTR, id.value.toString())
        c.style.setProperty("content-visibility", "auto")
        c.style.setProperty("overflow-clip-margin", "${style.editorPaddingLeftPx + 24}px")
        editor.appendChild(c)
        chunk = c
        carried = previous[id.value]
        count = 0
    }

    private fun close() {
        val c = chunk ?: return
        c.setAttribute(CHUNK_ROWS_ATTR, count.toString())
        val old = carried
        val height = if (old != null && old.rows > 0) old.height * count / old.rows
        else (count * style.lineHeightPx).toDouble()
        c.style.setProperty("contain-intrinsic-size", "auto ${height}px")
    }
}

/**
 * The row element painted right before [row], across chunk boundaries
 * ([RowAppender]), or `null` for the first row. Non-row elements (a
 * search node's result list) are skipped.
 */
internal fun previousRowElement(row: Element): Element? {
    var prev = row.previousElementSibling
    while (prev != null && !prev.hasAttribute("data-row")) prev = prev.previousElementSibling
    if (prev != null) return prev
    val parent = row.parentElement ?: return null
    if (!parent.classList.contains(ROW_CHUNK_CLASS)) return null
    var chunk = parent.previousElementSibling
    while (chunk != null) {
        val rows = chunk.querySelectorAll(":scope > [data-row]")
        if (rows.length > 0) return rows.item(rows.length - 1) as? Element
        if (chunk.hasAttribute("data-row")) return chunk
        chunk = chunk.previousElementSibling
    }
    return null
}

/**
 * The chunks ([RowAppender]) of [editor] whose box lies within
 * [marginPx] of the viewport, or `null` when the page is not chunked.
 * Lets whole-page row scans (the fold animation, drag aiming) stay with
 * the rows near the screen instead of forcing every skipped chunk to lay
 * out.
 */
internal fun chunksNearViewport(editor: HTMLElement, marginPx: Double): List<HTMLElement>? {
    val chunks = editor.querySelectorAll(":scope > .$ROW_CHUNK_CLASS")
    if (chunks.length == 0) return null
    val top = -marginPx
    val bottom = window.innerHeight + marginPx
    val out = ArrayList<HTMLElement>()
    for (i in 0 until chunks.length) {
        val c = chunks.item(i) as? HTMLElement ?: continue
        val r = c.getBoundingClientRect()
        if (r.bottom >= top && r.top <= bottom) out += c
    }
    return out
}

/**
 * Builds one row's DOM element. Splits into a non-editable bullet prefix
 * (when the line is a bullet) and an editable text span; the row div
 * itself is contenteditable so multi-row selections behave naturally.
 *
 * @param absoluteRow Zero-based document row index. Stamped onto the
 *   div as `data-row` so caret-mapping helpers can find it.
 * @param line The line text after any zoom-relative indent stripping.
 * @param state Current viewer state, used to resolve fold/ref status.
 * @param docState Latest document snapshot, used for `lineIds` and
 *   sibling lookups.
 * @param viewModel Receives bullet click + chevron toggle intents.
 * @param style Visual constants (line height, indent step, etc.).
 * @param block The rows of the block this row belongs to, or `null` when
 *   it is not a block row.
 * @param onBulletMouseDown Press on a bullet's (or block item's) dot:
 *   click to zoom, drag to move. `null` disables it.
 * @param isZoomBody `true` for the rows of the block item the pane is
 *   zoomed into, drawn as the page's body (see [decorateBlockRow]).
 */
private fun buildRowElement(
    absoluteRow: Int,
    line: String,
    viewOriginCol: Int,
    state: PaneBackingViewModel.State,
    docState: Document.State,
    viewModel: MainViewModel,
    style: EditorStyle,
    block: IntRange?,
    onBulletMouseDown: ((absoluteRow: Int, ev: MouseEvent) -> Unit)? = null,
    isZoomBody: Boolean = false,
): HTMLElement {
    val rowDiv = document.createElement("div") as HTMLElement
    rowDiv.setAttribute("data-row", absoluteRow.toString())
    rowDiv.style.apply {
        setProperty("position", "relative")
        minHeight = "${style.lineHeightPx}px"
        setProperty("line-height", "${style.lineHeightPx}px")
    }

    val bulletCol = DocumentLayout.bulletAsteriskColumn(line)
    val rowId = if (absoluteRow in docState.lineIds.indices) docState.lineIds[absoluteRow] else null
    // Inline images on this row resolve against the row's own folder.
    val imageResolver: (String) -> String? = { src -> viewModel.resolveImageSrc(absoluteRow, src) }
    val wikiResolver: (String) -> String? = { name -> viewModel.wikiLinkHref(state, name) }
    // Links are written relative to the row's folder; drawn as vault paths.
    val linkResolver: (String) -> String = { url -> viewModel.linkHrefOf(absoluteRow, url) }

    // Inline-image rows are much taller than a text-only line, which
    // makes a baseline-aligned bullet visually float in the vertical
    // middle of the image. The `has-image` class swaps to top
    // alignment so the bullet sits next to the first line of text.
    // Cheap heuristic — exact detection would re-tokenize, but any
    // line carrying both `![` and `](` is virtually certain to hold a
    // markdown image; a false positive just changes alignment of a
    // row that doesn't need it, which is harmless.
    if (lineLooksLikeImageRow(line)) {
        rowDiv.classList.add("lunarbor-row-has-image")
    }

    if (bulletCol >= 0) {
        // Visually indent the row by its bullet depth via padding-left, so the
        // bullet glyph itself sits at a depth-appropriate offset without
        // requiring monospace alignment.
        val depth = bulletCol / PaneBackingViewModel.TAB_SIZE
        // Hanging indent: the dot sits at the level's origin on the first
        // line, and wrapped lines line up with the text, not the dot.
        rowDiv.style.paddingLeft = "calc(${depth * style.indentStepPx}px + $BLOCK_DOT_SLOT)"
        rowDiv.style.setProperty("text-indent", "-$BLOCK_DOT_SLOT")
        // `data-prefix-len` must be in raw (absolute) model columns so caret
        // mapping in MainScreen translates DOM offsets to model `(row, col)`
        // correctly when zoomed — `viewOriginCol` characters of indent were
        // stripped from the displayed line above, but the model's columns
        // still count them.
        rowDiv.setAttribute("data-prefix-len", (viewOriginCol + bulletCol + 2).toString())

        // The `hasChildren` check needs the row's absolute bullet column,
        // not the zoom-relative one — child rows in `docState.lines` have
        // their full indent, so a relative comparison would never match.
        val absoluteIndentInRaw = DocumentLayout.bulletAsteriskColumn(docState.lines[absoluteRow])
        appendIndentGuides(rowDiv, depth, "0px", style)
        // Markdown mode draws the bullet but gives it no outline
        // behaviour: no chevron, no badge, no drag/zoom handle.
        val outline = !state.isMarkdownMode
        // A search node folds like a parent: its results are its contents.
        val searchView = if (rowId != null && outline) viewModel.searchNodeOf(state, absoluteRow) else null
        if (rowId != null && outline) {
            val isFoldedPromotedRef = viewModel.isPromotedRef(rowId) &&
                rowId !in state.expandedRefIdsLocal
            // Children the privacy mode hides do not count.
            val isCollapsibleParent = viewModel.hasChildrenOnScreen(state, absoluteRow) || searchView != null
            val isCollapsedNow = rowId in state.collapsedIds
            if (isCollapsibleParent) {
                if (isCollapsedNow || isFoldedPromotedRef) rowDiv.classList.add("lunarbor-row-folded")
                val chevron = buildChevron(isCollapsedNow || isFoldedPromotedRef) { viewModel.toggleCollapse(rowId) }
                // Position the chevron just to the left of THIS row's bullet
                // glyph, not the editor's left margin. The row's bullet sits
                // at `padding-left = depth * indentStepPx` from the row's
                // box; the chevron's 22px slot lands immediately before it.
                chevron.style.left = "${depth * style.indentStepPx - 22}px"
                // Centred on the first line, not on the whole row: a
                // wrapped title, or a search node's results, grow it.
                chevron.style.maxHeight = firstLineHeightCss(line.substring(bulletCol + 2), style)
                rowDiv.appendChild(chevron)
            }
        }
        // A mirror folds open onto another node's items; its dot says so.
        val mirror = rowId != null && outline && viewModel.isMirror(rowId)
        if (mirror) rowDiv.classList.add("lunarbor-row-mirror")

        // A heading's dot is centred on the heading's letters, not the row's.
        headingScaleOf(line.substring(bulletCol + 2))?.let { scale ->
            rowDiv.classList.add("lunarbor-row-heading")
            rowDiv.style.setProperty("--lunarbor-heading-scale", scale.toString())
        }
        val bulletPrefix = buildBulletPrefix(absoluteRow, if (outline) onBulletMouseDown else null, interactive = outline)
        if (mirror) bulletPrefix.title = "Mirror: editing here edits the node it shows"
        rowDiv.appendChild(bulletPrefix)
        rowDiv.appendChild(buildStyledTextRegion(rowDiv, line.substring(bulletCol + 2), imageResolver, wikiResolver, linkResolver))
        if (rowId != null && outline) buildFolderBadge(absoluteRow, rowId, state, viewModel)?.let(rowDiv::appendChild)
        // A search node's match count, on its line after the magnifier.
        if (searchView != null && rowId != null) rowDiv.appendChild(buildSearchNodeCount(searchView, rowId, viewModel))
        // A search node lists its live results under its text (unless folded).
        if (searchView != null && rowId != null && !searchView.folded) {
            rowDiv.appendChild(buildSearchNodeResults(searchView, absoluteRow, viewModel, style))
        }
    } else if (block != null && BlockLayout.markerColumn(line) >= 0) {
        decorateBlockRow(
            rowDiv, absoluteRow, line, viewOriginCol, block, state, docState, viewModel, style, imageResolver,
            onBulletMouseDown, isZoomBody,
        )
    } else {
        // Plain line (plain Markdown files, block lines — never created by
        // editing an outline): editable text starts at column 0 of the raw line,
        // unless we stripped a zoom indent — in that case the displayed text
        // begins at `viewOriginCol` in raw model columns.
        rowDiv.setAttribute("data-prefix-len", viewOriginCol.toString())
        rowDiv.appendChild(buildStyledTextRegion(rowDiv, line, imageResolver, wikiResolver, linkResolver))
    }

    markBrokenLinks(rowDiv, state, viewModel)
    // The caret's row: a search node shows its `{{search: …}}` only here.
    if (absoluteRow == state.cursorRow) rowDiv.classList.add("lunarbor-row-caret")
    // Done (LBR-24): the item whose title is struck through and everything
    // under it are dimmed; only the item itself is struck (its Markdown).
    if (viewModel.isRowDone(state, absoluteRow)) rowDiv.classList.add("lunarbor-row-done")
    return rowDiv
}

/**
 * Marks every vault link span under [root] whose target is missing
 * ([MainViewModel.isLinkBroken]) with the `lunarbor-md-link-broken` class
 * (struck through) and a "Not found" tooltip naming the path. Links whose
 * status is still being checked are drawn normally; the check's result
 * arrives as a new pane state and repaints.
 *
 * Called for every painted row by [buildRowElement].
 */
internal fun markBrokenLinks(root: HTMLElement, state: PaneBackingViewModel.State, viewModel: MainViewModel) {
    val spans = root.querySelectorAll("[data-href]")
    for (i in 0 until spans.length) {
        val span = spans.item(i) as? HTMLElement ?: continue
        val href = span.getAttribute("data-href") ?: continue
        if (!LunarborLink.isRooted(href)) continue
        if (viewModel.isLinkBroken(state, href)) {
            span.classList.add("lunarbor-md-link-broken")
            span.title = "Not found: " + (LunarborLink.parseRooted(href)?.let { "/$it" } ?: href)
        } else {
            span.title = LunarborLink.parseRooted(href)?.let { "/$it" } ?: href
        }
    }
}

/**
 * The count badge of an expanded folder-backed bullet (TRF-6), e.g.
 * `3 files`: how many items zooming into the bullet would list under its
 * bullets (the same [FolderContents.visible] entries, so the numbers
 * match). `null` — no badge — when the bullet is a leaf, is folded in
 * this pane, or its folder holds nothing besides its child bullets'
 * folders. Clicking the badge zooms into the bullet, where the list is.
 *
 * @param absoluteRow The bullet's document row, for the zoom.
 * @param rowId The bullet's id.
 */
private fun buildFolderBadge(
    absoluteRow: Int,
    rowId: LineId,
    state: PaneBackingViewModel.State,
    viewModel: MainViewModel,
): HTMLElement? {
    if (!viewModel.isPromotedRef(rowId)) return null
    if (rowId in state.collapsedIds) return null
    val entries = viewModel.folderContentsOfBullet(state, rowId) ?: return null
    val label = FolderContents.badgeLabel(entries) ?: return null
    val badge = document.createElement("span") as HTMLElement
    badge.className = "lunarbor-folder-badge"
    badge.setAttribute("contenteditable", "false")
    badge.title = "Zoom in to see them"
    badge.textContent = label
    badge.addEventListener("mousedown", { ev ->
        // Keep the press away from the editor's caret placement and drag
        // handlers, which listen on the editor itself.
        ev.preventDefault()
        ev.stopPropagation()
        viewModel.zoomInto(absoluteRow)
    })
    return badge
}

/**
 * Fills [rowDiv] as one row of a block (TRF-5): the block's side borders
 * (plus the top edge on its first row and the bottom edge on its last),
 * the editable content after the hidden marker, and — on the first row —
 * the block item's outline dot, hung left of the box, and the
 * non-editable delete control. A block is the body of an outline item,
 * so its box starts where a sibling bullet's text starts. A list item
 * inside the block (`* x` / `- x`) gets a dot in place of its prefix,
 * indented by its leading spaces.
 *
 * The delete control shows while the pointer is over any row of the
 * block (each row toggles `lunarbor-block-hover` on the first row) and
 * while the caret is in the block. Pressing it asks first
 * ([confirmDeleteBlock]), then calls `MainViewModel.deleteBlock` with the
 * first row's id; undo restores the block.
 *
 * A large block ([BlockLayout.isLarge]) also gets an expand / collapse
 * control right of the ×, always shown. Collapsed (the default) it shows
 * only its first [BlockLayout.PREVIEW_ROWS] rows: the last of them draws
 * the bottom edge, fades out and carries a "N more lines" label that
 * expands it (`MainViewModel.toggleBlockExpanded`).
 *
 * @param rowDiv The row div being built.
 * @param absoluteRow Document row of [rowDiv].
 * @param line The row text after any zoom-indent stripping.
 * @param viewOriginCol Columns stripped for the zoom, added back to
 *   `data-prefix-len` so it stays in raw model columns.
 * @param block Rows of the block, in the document.
 * @param imageResolver Maps an inline image `src` to its vault file; see
 *   [buildStyledTextRegion].
 */
private fun decorateBlockRow(
    rowDiv: HTMLElement,
    absoluteRow: Int,
    line: String,
    viewOriginCol: Int,
    block: IntRange,
    state: PaneBackingViewModel.State,
    docState: Document.State,
    viewModel: MainViewModel,
    style: EditorStyle,
    imageResolver: (String) -> String?,
    onBulletMouseDown: ((absoluteRow: Int, ev: MouseEvent) -> Unit)?,
    isZoomBody: Boolean,
) {
    val markerCol = BlockLayout.markerColumn(line)
    val depth = markerCol / PaneBackingViewModel.TAB_SIZE
    rowDiv.classList.add("lunarbor-block-row")
    // The block is the body of an outline item: its box starts where a
    // sibling bullet's text starts, right of the item's dot. Zoomed into
    // it, it is the page's body: no dot, flush left.
    rowDiv.style.marginLeft =
        if (isZoomBody) "0" else "calc(${depth * style.indentStepPx}px + $BLOCK_DOT_SLOT)"
    rowDiv.setAttribute("data-block-start", block.first.toString())
    // A list item inside the block (`* x`, `- x`, indented by leading
    // spaces) draws a dot in place of its prefix; the caret starts after
    // it, like on an outline bullet.
    val listPrefixLen = BlockLayout.listPrefixLength(line)
    // A code row's text starts after its hidden code marker too.
    val codePrefixLen = BlockLayout.codePrefixLength(line)
    rowDiv.setAttribute("data-prefix-len", (viewOriginCol + markerCol + 1 + codePrefixLen + listPrefixLen).toString())
    if (codePrefixLen > 0) {
        // One band per run of code rows: rounded where the run starts
        // and ends.
        rowDiv.classList.add("lunarbor-code-row")
        val lines = docState.lines
        if (absoluteRow - 1 !in block || !BlockLayout.isCodeLine(lines[absoluteRow - 1])) {
            rowDiv.classList.add("lunarbor-code-first")
        }
        if (absoluteRow + 1 !in block || !BlockLayout.isCodeLine(lines[absoluteRow + 1])) {
            rowDiv.classList.add("lunarbor-code-last")
        }
    }
    val isFirst = absoluteRow == block.first
    // A large block the pane has not expanded ends, on screen, at the last
    // row of its preview: that row draws the bottom edge and the fade.
    val hiddenRows = viewModel.hiddenBlockRows(state, block)
    val clippedLast = hiddenRows != null && hiddenRows > 0 &&
        absoluteRow == block.first + BlockLayout.PREVIEW_ROWS - 1
    val isLastShown = absoluteRow == block.last || clippedLast
    if (isFirst) rowDiv.classList.add("lunarbor-block-first")
    if (isLastShown) rowDiv.classList.add("lunarbor-block-last")
    if (clippedLast) rowDiv.classList.add("lunarbor-block-clipped")
    if (state.cursorRow in block) rowDiv.classList.add("lunarbor-block-active")

    // Hovering any row of the block reveals the delete control, which
    // lives on the first row.
    fun firstRowDiv(): HTMLElement? =
        rowDiv.closest(".lunarbor-editor")?.querySelector("[data-row='${block.first}']") as? HTMLElement
    rowDiv.addEventListener("mouseenter", { _ -> firstRowDiv()?.classList?.add("lunarbor-block-hover") })
    rowDiv.addEventListener("mouseleave", { _ -> firstRowDiv()?.classList?.remove("lunarbor-block-hover") })

    if (!isZoomBody) {
        // The row box starts right of the item's dot slot; the guides are
        // measured from the row's outline origin, so shift them back.
        val guides = appendIndentGuides(rowDiv, depth, "calc(-${depth * style.indentStepPx}px - $BLOCK_DOT_SLOT - 1px)", style)
        // Bridge the margins around a block so the lines stay unbroken.
        // (the 4px margin plus the 1px border edge).
        if (isFirst) guides?.style?.top = "-5px"
        if (isLastShown) guides?.style?.bottom = "-5px"
    }
    if (isFirst && !isZoomBody) {
        rowDiv.appendChild(buildBlockItemDot(absoluteRow, style, onBulletMouseDown))
        // A block item with children folds like a bullet.
        val rowId = docState.lineIds.getOrNull(absoluteRow)
        if (rowId != null) {
            val isFoldedPromotedRef = viewModel.isPromotedRef(rowId) && rowId !in state.expandedRefIdsLocal
            if (viewModel.hasChildrenOnScreen(state, absoluteRow)) {
                val folded = rowId in state.collapsedIds || isFoldedPromotedRef
                if (folded) rowDiv.classList.add("lunarbor-row-folded")
                val chevron = buildChevron(folded) { viewModel.toggleCollapse(rowId) }
                chevron.style.left = "calc(-$BLOCK_DOT_SLOT - 1px - 22px)"
                chevron.style.top = "4px"
                chevron.style.height = "${style.lineHeightPx}px"
                rowDiv.appendChild(chevron)
            }
        }
    }

    if (isFirst) {
        val id = docState.lineIds.getOrNull(block.first)
        if (id != null) {
            val del = document.createElement("span") as HTMLElement
            del.className = "lunarbor-block-delete"
            del.setAttribute("contenteditable", "false")
            del.setAttribute("title", "Delete block")
            del.textContent = "×"
            del.addEventListener("mousedown", { ev ->
                // Keep the press away from the editor's caret placement
                // and drag handlers, which listen on the editor itself.
                ev.preventDefault()
                ev.stopPropagation()
                confirmDeleteBlock(id, block, docState, viewModel)
            })
            rowDiv.appendChild(del)
            // A large block's expand / collapse control, right of the ×.
            if (hiddenRows != null) {
                rowDiv.classList.add("lunarbor-block-foldable")
                val collapsed = hiddenRows > 0
                val fold = document.createElement("span") as HTMLElement
                fold.className = "lunarbor-block-fold"
                fold.setAttribute("contenteditable", "false")
                fold.setAttribute("title", if (collapsed) "Show the whole block" else "Show less")
                fold.innerHTML = if (collapsed) ICON_BLOCK_EXPAND else ICON_BLOCK_COLLAPSE
                fold.addEventListener("mousedown", { ev ->
                    ev.preventDefault()
                    ev.stopPropagation()
                    viewModel.toggleBlockExpanded(id)
                })
                rowDiv.appendChild(fold)
            }
        }
    }
    if (listPrefixLen > 0) {
        val contentIndent = BlockLayout.contentIndentOf(line)
        rowDiv.appendChild(buildBlockListDot(contentIndent / PaneBackingViewModel.TAB_SIZE, style))
    }
    val editable = line.substring(markerCol + 1 + codePrefixLen + listPrefixLen)
    rowDiv.appendChild(
        if (codePrefixLen > 0) buildCodeTextRegion(rowDiv, editable)
        else buildStyledTextRegion(
            rowDiv, editable, imageResolver, { name -> viewModel.wikiLinkHref(state, name) },
            { url -> viewModel.linkHrefOf(absoluteRow, url) },
        )
    )

    // Under the text of a collapsed large block's last shown row: how
    // much is hidden; clicking it shows the whole block.
    if (clippedLast && hiddenRows != null) {
        val id = docState.lineIds.getOrNull(block.first)
        val more = document.createElement("span") as HTMLElement
        more.className = "lunarbor-block-more"
        more.setAttribute("contenteditable", "false")
        more.textContent = if (hiddenRows == 1) "1 more line" else "$hiddenRows more lines"
        more.addEventListener("mousedown", { ev ->
            ev.preventDefault()
            ev.stopPropagation()
            if (id != null) viewModel.toggleBlockExpanded(id)
        })
        rowDiv.appendChild(more)
    }

    // An empty block would be an empty box; say what it is for.
    if (block.first == block.last && codePrefixLen == 0 && BlockLayout.isEmptyContent(line)) {
        val hint = document.createElement("span") as HTMLElement
        hint.className = "lunarbor-block-hint"
        hint.textContent = "Block — write Markdown here"
        hint.setAttribute("contenteditable", "false")
        rowDiv.appendChild(hint)
    }
}

/**
 * Asks before deleting the block whose first row carries [id] (the ×
 * control), naming how much goes with it; on confirm calls
 * `MainViewModel.deleteBlock`. Undo brings the block back either way.
 */
private fun confirmDeleteBlock(id: LineId, block: IntRange, docState: Document.State, viewModel: MainViewModel) {
    val lines = docState.lines
    val col = BlockLayout.markerColumn(lines[block.first])
    val hasChildren = DocumentLayout.hasChildren(lines, block.first, col) || viewModel.isPromotedRef(id)
    val rows = block.last - block.first + 1
    val what = if (rows == 1) "This block" else "This $rows-line block"
    showConfirmDialog(
        title = "Delete this block?",
        message = what + (if (hasChildren) ", and every item under it," else "") +
            " will be deleted. ⌘Z brings it back.",
        confirmLabel = "Delete",
        cancelLabel = "Cancel",
        destructive = true,
        onConfirm = { viewModel.deleteBlock(id) },
    )
}

/** Large block control, collapsed: show the whole block (chevrons apart). */
private const val ICON_BLOCK_EXPAND: String =
    "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\" " +
        "stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
        "<polyline points=\"7 9 12 4 17 9\"/><polyline points=\"7 15 12 20 17 15\"/></svg>"

/** Large block control, expanded: show less (chevrons together). */
private const val ICON_BLOCK_COLLAPSE: String =
    "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\" " +
        "stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
        "<polyline points=\"7 4 12 9 17 4\"/><polyline points=\"7 20 12 15 17 20\"/></svg>"

/**
 * Draws the indent guides behind a row at nesting [depth] (relative to
 * the view): one thin vertical line per ancestor level, centred under
 * that ancestor's dot. Every row of a subtree draws the lines of all its
 * ancestors, so consecutive rows join into continuous lines that run
 * from a parent's dot down to its last descendant and stop where the
 * next sibling begins — Dynalist's guides. A folded parent's hidden rows
 * are not painted, so its line simply is not there.
 *
 * Called by [buildRowElement] for bullet rows and [decorateBlockRow] for
 * block rows.
 *
 * @param left CSS `left` of the guide layer, so that `0` inside it is the
 *   outline's level-0 origin (`0px` on a bullet row, whose padding
 *   carries the indent).
 * @return The guide layer, or `null` at depth 0 (no ancestors).
 */
private fun appendIndentGuides(rowDiv: HTMLElement, depth: Int, left: String, style: EditorStyle): HTMLElement? {
    if (depth <= 0) return null
    val step = style.indentStepPx
    val guides = document.createElement("span") as HTMLElement
    guides.className = "lunarbor-guides"
    guides.setAttribute("contenteditable", "false")
    guides.style.apply {
        setProperty("left", left)
        width = "${depth * step}px"
        // One line per `step`, at the bullet dot's centre (its left margin
        // plus half its width: 0.25em + 0.2em).
        setProperty(
            "background-image",
            "repeating-linear-gradient(to right, transparent 0, transparent $GUIDE_X, " +
                "var(--t-border, rgba(127, 127, 127, 0.35)) $GUIDE_X, " +
                "var(--t-border, rgba(127, 127, 127, 0.35)) calc($GUIDE_X + 1px), " +
                "transparent calc($GUIDE_X + 1px), transparent ${step}px)",
        )
    }
    rowDiv.insertBefore(guides, rowDiv.firstChild)
    return guides
}

/** Horizontal centre of a bullet dot within its level: see `.lunarbor-bullet`. */
private const val GUIDE_X = "0.45em"

/**
 * Width of the slot left of a block's box that holds the block item's
 * dot: the bullet glyph's margins and width plus the space after it, so
 * the box's left edge lines up with a sibling bullet's text.
 */
private const val BLOCK_DOT_SLOT = "1.15em"

/**
 * The outline dot of a block item, hung in the slot left of the block's
 * first row ([BLOCK_DOT_SLOT]) at the height of its first line, where a
 * bullet's dot sits. It behaves like a bullet's dot ([buildBulletPrefix]):
 * a click zooms into the block item, a drag moves it with its children.
 *
 * Called by [decorateBlockRow] for a block's first row.
 *
 * @param absoluteRow Document row of the block's first line.
 * @param onBulletMouseDown The view's dot handler; `null` for a plain dot.
 */
private fun buildBlockItemDot(
    absoluteRow: Int,
    style: EditorStyle,
    onBulletMouseDown: ((absoluteRow: Int, ev: MouseEvent) -> Unit)?,
): HTMLElement {
    val prefix = buildBulletPrefix(absoluteRow, onBulletMouseDown, interactive = onBulletMouseDown != null)
    prefix.classList.add("lunarbor-block-item-dot")
    prefix.style.apply {
        setProperty("left", "calc(-$BLOCK_DOT_SLOT - 1px)")
        setProperty("line-height", "${style.lineHeightPx}px")
    }
    return prefix
}

/**
 * The dot drawn in place of a list item's `* ` / `- ` prefix inside a
 * block, indented [level] steps by the item's leading spaces. Not
 * editable; the prefix characters stay in the model.
 *
 * Called by [decorateBlockRow].
 */
private fun buildBlockListDot(level: Int, style: EditorStyle): HTMLElement {
    val prefix = document.createElement("span") as HTMLElement
    prefix.className = "lunarbor-block-list-dot lunarbor-bullet-plain"
    prefix.setAttribute("contenteditable", "false")
    prefix.style.marginLeft = "${level * style.indentStepPx}px"
    val glyph = document.createElement("span") as HTMLElement
    glyph.className = "lunarbor-bullet"
    prefix.appendChild(glyph)
    val space = document.createElement("span") as HTMLElement
    space.textContent = " "
    prefix.appendChild(space)
    return prefix
}

/**
 * Builds the editable text region of a code row inside a block: the
 * text verbatim — no Markdown, no line prefix — in one run, with an
 * identity [RowColumnMap] (every model column is its own display
 * column, nothing hidden).
 *
 * Called by [decorateBlockRow] for rows carrying
 * [BlockLayout.CODE]; the row's code band is its CSS.
 *
 * @param rowDiv The enclosing row div — used as the column-map key.
 * @param editable The code text (the line after its hidden markers).
 */
private fun buildCodeTextRegion(rowDiv: HTMLElement, editable: String): HTMLElement {
    val wrapper = document.createElement("span") as HTMLElement
    wrapper.className = "lunarbor-text lunarbor-code-text"
    val len = editable.length
    val identity = IntArray(len + 1) { it }
    rowDiv.asDynamic()[ROW_COLUMN_MAP_KEY] = RowColumnMap(identity, identity.copyOf(), emptySet())
    val run = document.createElement("span") as HTMLElement
    run.className = "lunarbor-text-run"
    if (len == 0) {
        // Same caret placeholder as an empty styled region: a source span
        // covering the end-of-line column and a `<br>` to anchor on.
        run.setAttribute("data-src-start", "0")
        run.setAttribute("data-src-end", "1")
        run.appendChild(document.createElement("br"))
    } else {
        run.setAttribute("data-src-start", "0")
        run.setAttribute("data-src-end", len.toString())
        run.textContent = editable
    }
    wrapper.appendChild(run)
    return wrapper
}

/**
 * Builds the `.lunarbor-text` editable region for one row. Splits the
 * inline markdown into one `<span class="lunarbor-text-run …">` per
 * styled run; marker characters are *not* in the DOM at all so the user
 * sees a pure WYSIWYG view. Also detects a leading line-level prefix
 * (`# `, `> `, etc.), strips it from the rendering, and adds the
 * matching styling class to the wrapper.
 *
 * Stashes a [RowColumnMap] on [rowDiv] (see [rowColumnMapOf]) so caret-mapping code can
 * translate between the DOM (markers-stripped) and the underlying model
 * line.
 *
 * @param rowDiv The enclosing row div — used as the column-map key.
 * @param editable The editable inline text (the line with any bullet
 *   prefix already removed). May be empty.
 * @param imageResolver Maps an inline image's `src` to the vault-relative
 *   file it shows, or `null` for an external URL — normally
 *   `MainViewModel.resolveImageSrc` for this row.
 * @param wikiResolver Maps a wiki link's name ([StyledRun.wikiName]) to
 *   the vault link it stands for, or `null` to draw it as plain text —
 *   normally `MainViewModel.wikiLinkHref` for the pane's state. A
 *   resolved wiki link gets the same link class and `data-href` as a
 *   `[label](…)` link, so clicking and broken-link marking treat it
 *   alike.
 * @param linkResolver Maps a link's destination as written to the href the
 *   app uses — `/<path>` for a place in the vault, read relative to
 *   the row's folder — normally `MainViewModel.linkHrefOf` for this row.
 */
private fun buildStyledTextRegion(
    rowDiv: HTMLElement,
    editable: String,
    imageResolver: (String) -> String?,
    wikiResolver: (String) -> String?,
    linkResolver: (String) -> String,
): HTMLElement {
    val wrapper = document.createElement("span") as HTMLElement
    wrapper.className = "lunarbor-text"

    // Detect line-level prefix at the start of the editable text.
    val linePrefix = LineMarkdownPrefix.detect(editable, 0)
    val lineMarkerLen = if (linePrefix.style != null) linePrefix.markerEnd else 0
    val lineClass = when (linePrefix.style) {
        LineStyle.HEADING_1 -> "lunarbor-md-h1"
        LineStyle.HEADING_2 -> "lunarbor-md-h2"
        LineStyle.HEADING_3 -> "lunarbor-md-h3"
        LineStyle.HEADING_4 -> "lunarbor-md-h4"
        LineStyle.HEADING_5 -> "lunarbor-md-h5"
        LineStyle.HEADING_6 -> "lunarbor-md-h6"
        LineStyle.QUOTE -> "lunarbor-md-quote"
        null -> null
    }
    if (lineClass != null) wrapper.className = "lunarbor-text $lineClass"

    val inlineText = if (lineMarkerLen > 0) editable.substring(lineMarkerLen) else editable
    val tokenized = InlineMarkdownTokenizer.tokenize(inlineText)

    // Compose the row's full column map: cols inside the line marker
    // collapse to display 0; inline cols use the tokenizer's map.
    val editableLen = editable.length
    val rowModelToDom = IntArray(editableLen + 1)
    for (i in 0..lineMarkerLen) rowModelToDom[i] = 0
    for (i in 0..(editableLen - lineMarkerLen)) {
        rowModelToDom[lineMarkerLen + i] = tokenized.modelToDom[i]
    }
    val displayLen = tokenized.displayText.length
    val rowDomToModel = IntArray(displayLen + 1)
    for (d in 0..displayLen) {
        rowDomToModel[d] = lineMarkerLen + tokenized.domToModel[d]
    }
    val markerCols = HashSet<Int>(tokenized.markerCols.size + lineMarkerLen)
    for (i in 0 until lineMarkerLen) markerCols += i
    for (m in tokenized.markerCols) markerCols += (m + lineMarkerLen)
    rowDiv.asDynamic()[ROW_COLUMN_MAP_KEY] = RowColumnMap(rowModelToDom, rowDomToModel, markerCols)

    if (tokenized.runs.isEmpty()) {
        // Empty editable region (or whole region was markers like `****`).
        // Still emit one empty run-span so the caret has a stable target.
        // The `<br>` inside is a layout placeholder: without it, the browser
        // can't find a caret position on this row during ArrowUp/ArrowDown
        // navigation (the inline span has zero width and no text node, so
        // the visual-line search treats the row as having no caret slot and
        // skips over it to the next non-empty row). The `<br>` carries no
        // text content, so column math (`textContent.length`) is unaffected.
        val empty = document.createElement("span") as HTMLElement
        empty.className = "lunarbor-text-run"
        // Source range covering every editable column INCLUSIVE of the
        // end-of-line position (hence the +1), so any caret column on a
        // blank / markers-only row anchors on this span rather than
        // falling through to a wrapper offset — Chromium refuses to
        // paint a caret at wrapper offsets whose child has no text node.
        empty.setAttribute("data-src-start", "0")
        empty.setAttribute("data-src-end", (editableLen + 1).toString())
        empty.appendChild(document.createElement("br"))
        wrapper.appendChild(empty)
    } else {
        for (run in tokenized.runs) {
            val span: HTMLElement
            val srcEnd: Int
            if (run.imageSrc != null) {
                span = createImageRunElement(run, baseRunClass = "lunarbor-text-run", imageResolver = imageResolver)
                srcEnd = run.modelStart + (run.imageSourceLen ?: 0)
            } else {
                val href = run.linkHref?.let(linkResolver) ?: run.wikiName?.let(wikiResolver)
                if (href != null && run.linkHref == null) {
                    // A resolved wiki link: its brackets hide off the caret row.
                    appendWikiLinkSpans(wrapper, run, href, lineMarkerLen)
                    continue
                }
                span = document.createElement("span") as HTMLElement
                span.className = runClassName(run.styles, isLink = href != null, isTag = run.isTag, isSearch = run.isSearchQuery)
                if (run.isTag) span.style.setProperty("--tag-h", tagHue(run.text).toString())
                if (href != null) {
                    span.setAttribute("data-href", href)
                }
                span.textContent = run.text
                srcEnd = run.modelEnd
            }
            // Editable-relative source span of this run, marker chars
            // excluded (hidden markers live in the gaps between spans).
            // `MainScreen.locateDomPosition` walks these to place the
            // caret for a model column — the model→DOM mirror of
            // [RowColumnMap.domToModel].
            span.setAttribute("data-src-start", (lineMarkerLen + run.modelStart).toString())
            span.setAttribute("data-src-end", (lineMarkerLen + srcEnd).toString())
            wrapper.appendChild(span)
        }
        // When every run is zero-width (e.g. the line contains only an
        // inline image, since image runs carry empty text), the wrapper
        // has no text node the browser can paint a caret next to.
        // Chromium quietly refuses to draw a caret anchored "between
        // two inline elements" — the symptom is "no visible cursor
        // until you type a character." The fix is a placeholder text
        // node containing a single zero-width space (U+200B). Anchoring
        // the caret inside that text node gives Chromium an explicit
        // glyph-position anchor; the ZWSP itself is invisible.
        //
        // Column math elsewhere (`displayColForDomPosition`,
        // `imageSourceColsPastOffset`, `locateDomPosition`) treats the
        // placeholder as a normal trailing run — it contributes 1
        // character to source length but, since `displayText` is
        // empty by construction, no `domToModel` entries reference
        // it, so it never falsifies the click→model mapping.
        if (tokenized.displayText.isEmpty()) {
            val empty = document.createElement("span") as HTMLElement
            empty.className = "lunarbor-text-run lunarbor-caret-placeholder"
            empty.appendChild(document.createTextNode("​"))
            wrapper.appendChild(empty)
        }
    }
    return wrapper
}

/**
 * Paints a resolved wiki link `[[Name]]` as three adjacent link spans —
 * the syntax before the shown text (`[[`, or `[[Name|` before an alias),
 * the shown text ([WikiLink.shownRangeOf]), and the closing `]]` — each
 * with its own `data-src-start` / `data-src-end`, so caret mapping treats
 * them like any other runs. The syntax spans carry
 * `lunarbor-md-wiki-syntax`, which the stylesheet shrinks to nothing
 * unless the row holds the caret: the link reads as just its name, and
 * the full syntax comes back while it is being edited. All three carry
 * the link class and `data-href`, so clicking, hover popups and
 * broken-link marking see one link. An unresolved wiki link is not
 * painted here; it stays plain, fully visible text.
 *
 * Called by [buildStyledTextRegion].
 *
 * @param wrapper The row's text wrapper to append the spans to.
 * @param run The wiki link run ([StyledRun.wikiName] set).
 * @param href The vault link the name resolves to.
 * @param lineMarkerLen Length of the hidden line-level prefix, added to
 *   every source offset (they are relative to the editable text).
 */
private fun appendWikiLinkSpans(wrapper: HTMLElement, run: StyledRun, href: String, lineMarkerLen: Int) {
    val shown = WikiLink.shownRangeOf(run.text)
    val parts = listOf(
        0 until shown.first to true,
        shown to false,
        (shown.last + 1) until run.text.length to true,
    )
    for ((range, isSyntax) in parts) {
        if (range.isEmpty()) continue
        val span = document.createElement("span") as HTMLElement
        span.className = runClassName(run.styles, isLink = true, isTag = false, isSearch = false) +
            if (isSyntax) " lunarbor-md-wiki-syntax" else ""
        span.setAttribute("data-href", href)
        span.textContent = run.text.substring(range)
        span.setAttribute("data-src-start", (lineMarkerLen + run.modelStart + range.first).toString())
        span.setAttribute("data-src-end", (lineMarkerLen + run.modelStart + range.last + 1).toString())
        wrapper.appendChild(span)
    }
}

/**
 * Cheap row-level test for "this line contains a markdown image."
 * The painter adds a `lunarbor-row-has-image` class when this fires
 * so the bullet glyph can align to the top of the (much taller) row
 * instead of floating in the vertical middle of the image.
 *
 * A full re-tokenize would be more precise, but two `indexOf` scans
 * on a short line are essentially free and the false-positive cost
 * is zero — the class only affects vertical alignment of a bullet
 * on a row that's already showing whatever the user typed.
 */
private fun lineLooksLikeImageRow(line: String): Boolean {
    val bang = line.indexOf("![")
    if (bang < 0) return false
    return line.indexOf("](", startIndex = bang + 2) >= 0
}

/**
 * Map a tokenized inline run's [styles] / link / tag flags to the
 * `lunarbor-md-*` CSS classes the global stylesheet defines. Returns an
 * empty list when the run carries no formatting. Used by every place
 * that converts a [StyledRun] into a styled DOM node — the editor's
 * paint loop ([runClassName]) and the zoom-headline rendering in
 * `MainScreen.updateTitle`.
 *
 * Kept caret-tracking agnostic on purpose: the caller decides whether
 * to prepend a base class (the editor uses `lunarbor-text-run` so its
 * column-map walks the right spans; the headline needs no base class).
 */
internal fun inlineRunCssClasses(
    styles: Set<InlineStyle>,
    isLink: Boolean = false,
    isTag: Boolean = false,
    isImage: Boolean = false,
    isSearch: Boolean = false,
): List<String> {
    if (styles.isEmpty() && !isLink && !isTag && !isImage && !isSearch) return emptyList()
    val out = ArrayList<String>(styles.size + 2)
    if (InlineStyle.BOLD in styles) out += "lunarbor-md-bold"
    if (InlineStyle.ITALIC in styles) out += "lunarbor-md-italic"
    if (InlineStyle.STRIKETHROUGH in styles) out += "lunarbor-md-strike"
    if (InlineStyle.INLINE_CODE in styles) out += "lunarbor-md-code"
    if (isLink) out += "lunarbor-md-link"
    if (isTag) out += "lunarbor-md-tag"
    if (isImage) out += "lunarbor-md-image"
    if (isSearch) out += "lunarbor-md-search"
    return out
}

/**
 * The hue (0–359) a tag is drawn in (`.lunarbor-md-tag`'s `--tag-h`):
 * derived from its name, case-insensitively, so `#Work` and `#work` match
 * and a tag looks the same everywhere.
 *
 * The name's hash is spread round the wheel by the golden ratio
 * (Fibonacci hashing): a plain `hash % 360` put names that differ only in
 * their last character (`#p1`, `#p2`) one degree apart, so they looked
 * the same; this way such neighbours land ~137° apart. Hues are not
 * unique — two unrelated tags can still come out close.
 *
 * Called for tag runs by the paint loop and the page title, and for the
 * privacy dialog's tag chips.
 */
internal fun tagHue(tag: String): Int {
    var h = 0
    for (c in tag.removePrefix("#").lowercase()) h = (h * 31 + c.code) and 0x7fffffff
    val turn = (h * GOLDEN_RATIO_FRACTION) % 1.0
    return (turn * 360).toInt() % 360
}

/** The golden ratio's fractional part, (√5 − 1) / 2: [tagHue]'s spread. */
private const val GOLDEN_RATIO_FRACTION = 0.6180339887498949

/**
 * Absolute path to the vault root, set once at app boot by `Main.kt`
 * from `DocumentRegistry.rootDirectory`. Used by [lunarborAssetUrl] to
 * turn a vault-relative image path into an absolute filesystem path that
 * the Electron `lunarbor-asset:` protocol handler can resolve. Defaults
 * to empty until set — in that state image runs render as broken images,
 * which is acceptable since the boot wire-up runs before any paint.
 */
@Suppress("ObjectPropertyName")
internal var _lunarborVaultRoot: String = ""

/**
 * Install the vault root used by [lunarborAssetUrl]. Called once at
 * app boot from `Main.kt` so the renderer doesn't need to crawl the DI
 * graph for every image span.
 */
fun setLunarborVaultRoot(rootDir: String) {
    _lunarborVaultRoot = rootDir
}

/**
 * Build a `lunarbor-asset:` URL for a vault-root-relative path. Electron
 * registers the protocol in the main process so the renderer can load
 * vault assets without `webSecurity` blocking `file://` URLs.
 *
 * The URL path encodes the absolute filesystem path so the main-process
 * handler can pass it straight to `fs` without needing its own copy of
 * the vault-root configuration. In the browser demo it is a `blob:` URL
 * of the file in memory instead (`DemoFileSystem.assetUrl`).
 */
internal fun lunarborAssetUrl(vaultRelPath: String): String {
    val rel = vaultRelPath.trimStart('/')
    val abs = if (_lunarborVaultRoot.isEmpty()) "/$rel" else "${_lunarborVaultRoot.trimEnd('/')}/$rel"
    // The browser demo has no protocol handler: its files are in memory
    // and served as blob: URLs (a missing file gets a URL that fails to
    // load, like a missing file on disk).
    demoFileSystem?.let { return it.assetUrl(abs) ?: "about:blank#missing" }
    // Use an explicit `local` placeholder host so Chromium's
    // standard-scheme URL parser puts the full abs path into the
    // pathname (`/<abs>`). Without the host, an empty-host URL
    // (`lunarbor-asset:///<abs>`) gets parsed as host=`<first segment>`,
    // path=`/<rest>` — corrupting the leading directory.
    return "lunarbor-asset://local" + js("encodeURI")(abs)
}

/**
 * Create the `<span>` that stands in for an image inline run. The span is
 * `contenteditable="false"` so caret clicks treat it as an atomic glyph;
 * it contains a single `<img>` whose width is constrained to the image
 * run's [StyledRun.imageWidthPx] when present — or, when the source is an
 * `.excalidraw` drawing, a `lunarbor-md-drawing` span holding an SVG
 * picture of it ([renderDrawingPreview]). The span carries
 * `lunarbor-text-run` + `lunarbor-md-image` classes so the editor's
 * column-mapping walker still iterates past it (the inner `<img>` has
 * empty `textContent`, contributing 0 display columns just as the
 * tokenizer promised).
 *
 * @param run the image run produced by [InlineMarkdownTokenizer].
 * @param baseRunClass optional base class added before the image-specific
 *   classes — `lunarbor-text-run` in the editor, `null` in the headline.
 * @param imageResolver maps the run's `src` to the vault-relative file it
 *   shows (relative to the row's folder, see `ImagePaths`), or `null` for
 *   an external URL, which is loaded as written.
 */
internal fun createImageRunElement(
    run: StyledRun,
    baseRunClass: String?,
    imageResolver: (String) -> String?,
): HTMLElement {
    val src = run.imageSrc ?: error("createImageRunElement called on non-image run")
    val span = document.createElement("span") as HTMLElement
    val classes = inlineRunCssClasses(run.styles, isImage = true)
    val full = if (baseRunClass == null) classes else listOf(baseRunClass) + classes
    span.className = full.joinToString(" ")
    span.setAttribute("contenteditable", "false")
    span.setAttribute("data-img-src", src)
    run.imageWidthPx?.let { span.setAttribute("data-img-width", it.toString()) }
    run.imageAlt?.takeIf { it.isNotEmpty() }?.let { span.setAttribute("data-img-alt", it) }
    // Source-side `![…](…)` length, so the click→cursor mapper can
    // step past this atomic glyph instead of collapsing to its start.
    run.imageSourceLen?.let { span.setAttribute("data-img-source-len", it.toString()) }
    val resolved = imageResolver(src)
    if (resolved != null && NoteRepository.isDrawingPath(resolved)) {
        // An embedded Excalidraw drawing: drawn as an SVG picture of the
        // scene (DrawingPreview.kt); clicking it opens the drawing editor.
        val picture = document.createElement("span") as HTMLElement
        picture.className = "lunarbor-md-drawing"
        span.appendChild(picture)
        renderDrawingPreview(picture, resolved, run.imageWidthPx)
        appendImageResizeHandle(span)
        return span
    }
    val img = document.createElement("img") as HTMLImageElement
    img.src = resolved?.let { lunarborAssetUrl(it) } ?: src
    img.alt = run.imageAlt ?: ""
    img.draggable = false
    run.imageWidthPx?.let { img.style.width = "${it}px" }
    // Swap in a "missing image" placeholder when the protocol fails to
    // resolve (file deleted, path typo). The container retains its
    // click affordance so the resize popover still opens — the user
    // might want to fix the size attr or replace the file.
    img.addEventListener("error", { _ ->
        if (span.classList.contains("is-broken")) return@addEventListener
        span.classList.add("is-broken")
        img.remove()
        val broken = document.createElement("span") as HTMLElement
        broken.className = "lunarbor-md-image-broken"
        broken.textContent = "Missing image: $src"
        span.appendChild(broken)
    })
    span.appendChild(img)
    appendImageResizeHandle(span)
    return span
}

/**
 * Appends the resize handle pinned at the bottom-right corner of an inline
 * image or drawing [span]. Visible only on hover so it doesn't clutter the
 * read view. The actual drag behavior lives in
 * `MainScreen.handleImageResizeMouseDown` via event delegation on the
 * `.lunarbor-image-resize-handle` class.
 */
private fun appendImageResizeHandle(span: HTMLElement) {
    val handle = document.createElement("span") as HTMLElement
    handle.className = "lunarbor-image-resize-handle"
    handle.setAttribute("contenteditable", "false")
    span.appendChild(handle)
}

/**
 * Build the CSS class string for a styled run from its [styles] set.
 * Always includes the base `lunarbor-text-run` class so global
 * editable-region styles still apply. Inline-style classes come from
 * the shared [inlineRunCssClasses] helper.
 */
private fun runClassName(
    styles: Set<InlineStyle>,
    isLink: Boolean = false,
    isTag: Boolean = false,
    isSearch: Boolean = false,
): String {
    val extras = inlineRunCssClasses(styles, isLink, isTag, isSearch = isSearch)
    if (extras.isEmpty()) return "lunarbor-text-run"
    return "lunarbor-text-run " + extras.joinToString(" ")
}

/**
 * Non-editable bullet glyph + trailing space. Sized as a single inline
 * unit so wrap behaviour treats it as the start of the line.
 *
 * The only gesture wired here is **mousedown**, forwarded to
 * [onBulletMouseDown] (when supplied) so the caller can begin tracking a
 * potential drag-to-move gesture. The handler calls `preventDefault()` to
 * keep the browser from focusing the editor or moving the caret, and
 * `stopPropagation()` to keep the editor's gutter-drag init from also
 * firing.
 *
 * Zoom-on-click is *not* wired here. The editor's `mouseup` listener runs
 * `syncSelectionFromDom`, which can rebuild the DOM and detach this
 * span — a `click` listener would then fail to fire on the first press.
 * Stationary release → zoom is handled by `MainScreen.handleDragUp`
 * instead, which sees the same event via the window-level mouseup
 * listener installed by `startDragSession`. See the comment in
 * `MainScreen.wireInputListeners` about the matching pattern for
 * external-link follow.
 *
 * @param interactive `false` in Markdown mode: the dot is a plain list
 *   marker (default cursor; the caller passes no [onBulletMouseDown]).
 */
private fun buildBulletPrefix(
    absoluteRow: Int,
    onBulletMouseDown: ((absoluteRow: Int, ev: MouseEvent) -> Unit)? = null,
    interactive: Boolean = true,
): HTMLElement {
    val prefix = document.createElement("span") as HTMLElement
    prefix.className = "lunarbor-bullet-prefix"
    prefix.setAttribute("contenteditable", "false")
    prefix.style.apply {
        setProperty("user-select", "none")
        cursor = if (interactive) "pointer" else "default"
    }
    if (!interactive) prefix.classList.add("lunarbor-bullet-plain")

    val glyph = document.createElement("span") as HTMLElement
    glyph.className = "lunarbor-bullet"
    // Empty text: the dot is drawn via CSS (background colour + border-radius)
    // so its size and vertical position aren't tied to the `•` glyph metrics
    // which sit high in their em-box and cause baseline misalignment.
    prefix.appendChild(glyph)

    // Trailing single space keeps the rendered gap between bullet and
    // text consistent with the underlying model column for cursor
    // restoration math (prefix occupies columns [indent .. indent+2)).
    val space = document.createElement("span") as HTMLElement
    space.textContent = " "
    prefix.appendChild(space)

    prefix.addEventListener("mousedown", { event ->
        val me = event as MouseEvent
        me.stopPropagation()
        me.preventDefault()
        onBulletMouseDown?.invoke(absoluteRow, me)
    })
    return prefix
}

/**
 * Font-size factor of the `.lunarbor-md-h*` rule for a bullet row whose
 * text (after `* `) is [text], or `null` when it is no heading. The row
 * painter sets it as `--lunarbor-heading-scale`, which the
 * `.lunarbor-row-heading` rule uses to lift the dot to the middle of the
 * heading's letters (it would otherwise sit at the small text's height,
 * near the heading's baseline).
 */
private fun headingScaleOf(text: String): Double? = when (LineMarkdownPrefix.detect(text, 0).style) {
    LineStyle.HEADING_1 -> 1.6
    LineStyle.HEADING_2 -> 1.35
    LineStyle.HEADING_3 -> 1.15
    LineStyle.HEADING_4 -> 1.05
    LineStyle.HEADING_5 -> 1.0
    LineStyle.HEADING_6 -> 0.95
    LineStyle.QUOTE, null -> null
}

/**
 * CSS height of the first line of a bullet row whose text (after `* `)
 * is [text]: the row's line height, or a heading's taller line
 * (font-size × line-height of its `.lunarbor-md-h*` rule, in the row's
 * em). Caps the −/+ control's height so it stays beside the dot when
 * the title wraps. Called by the bullet branch of the row painter.
 */
private fun firstLineHeightCss(text: String, style: EditorStyle): String {
    val em = when (LineMarkdownPrefix.detect(text, 0).style) {
        LineStyle.HEADING_1 -> 2.0
        LineStyle.HEADING_2 -> 1.6875
        LineStyle.HEADING_3 -> 1.495
        LineStyle.HEADING_4 -> 1.365
        LineStyle.HEADING_5 -> 1.35
        LineStyle.HEADING_6 -> 1.33
        LineStyle.QUOTE, null -> return "${style.lineHeightPx}px"
    }
    return "max(${style.lineHeightPx}px, ${em}em)"
}

/**
 * The live result list of a search node ([MainViewModel.searchNodeOf]),
 * read-only, under the node's text one level in: one row per matching
 * line (the count is on the node's line, [buildSearchNodeCount]) — its text with the query's terms marked and,
 * dimmer, where it lives. Under the node, the first
 * [PaneBackingViewModel.SEARCH_NODE_INLINE] of them and a "…and N more"
 * that zooms into the node; on its own page, all the registry keeps
 * ([DocumentRegistry.SEARCH_NODE_MAX_HITS]). Pressing a row goes there in
 * this pane ([MainViewModel.navigateToSearchHit]); a Shift- / ⌘-press or a
 * right-click opens it in a new window
 * ([MainViewModel.openSearchHitInNewWindow], [OpenGesture]). The arrow
 * keys walk the rows from the editor ([SearchNodeHitCursor]): Enter goes
 * there, Toggle done (LBR-22) acts on the highlighted one. The node's
 * −/+ control folds the whole list (its fold state). Everything acts on
 * mousedown: a repaint between press and release (the editor's selection
 * sync) would replace the element and swallow a click. Not editable and
 * not part of the text, so caret mapping never sees it.
 *
 * @param nodeRow The node's document row, which "…and N more" zooms into.
 * @param isPage `true` when the pane is zoomed into the node: the list
 *   heads the page, flush left, and is whole.
 */
private fun buildSearchNodeResults(
    view: PaneBackingViewModel.SearchNodeView,
    nodeRow: Int,
    viewModel: MainViewModel,
    style: EditorStyle,
    isPage: Boolean = false,
): HTMLElement {
    val box = document.createElement("div") as HTMLElement
    box.className = "lunarbor-search-node"
    box.setAttribute("contenteditable", "false")
    // Found again by the keyboard's hit cursor ([SearchNodeHitCursor]) after every repaint.
    box.setAttribute(SEARCH_NODE_ROW_ATTR, nodeRow.toString())
    if (!isPage) box.style.setProperty("margin-left", "calc(${style.indentStepPx}px - $BLOCK_DOT_SLOT)")
    // Keep presses away from the editor's caret placement and drag code.
    box.addEventListener("mousedown", { ev ->
        ev.preventDefault()
        ev.stopPropagation()
    })
    val result = view.result
    // The count sits on the node's line ([buildSearchNodeCount]), or on
    // its own page in the title (`MainScreen.updateTitle`).
    if (result == null) return box
    val terms = se.soderbjorn.lunarbor.data.SearchQuery.parse(view.query).highlightTerms()
    val hits = if (isPage) result.hits else result.hits.take(PaneBackingViewModel.SEARCH_NODE_INLINE)
    for (hit in hits) {
        val row = document.createElement("div") as HTMLElement
        row.className = "lunarbor-search-node-hit"
        row.title = "Go to this line"
        // A done line (LBR-24) is dimmed, as in the outline.
        if (hit.done) row.classList.add("lunarbor-hit-done")
        val text = document.createElement("span") as HTMLElement
        text.className = "lunarbor-search-node-text"
        appendHighlighted(text, hit.text, terms)
        val where = document.createElement("span") as HTMLElement
        where.className = "lunarbor-search-node-where"
        // From the node's tree down: the rest of the path is where the node is.
        where.appendChild(isolatedText(viewModel.searchHitCrumbs(hit, under = view.scopeFolder).joinToString(" › ")))
        // No ✓ circle here: Toggle done (palette, ⌃↩) acts on the hit
        // the keyboard highlights ([SearchNodeHitCursor]).
        row.appendChild(text)
        row.appendChild(where)
        row.addEventListener("mousedown", { ev ->
            // Same rule as a link ([OpenGesture]): plain goes there,
            // Shift / ⌘ opens a new window, the Mac's Ctrl-press waits for
            // its `contextmenu`.
            when (openGestureOf(ev as MouseEvent)) {
                OpenGesture.HERE -> viewModel.navigateToSearchHit(hit)
                OpenGesture.NEW_WINDOW -> viewModel.openSearchHitInNewWindow?.let { open ->
                    open(hit)
                    swallowTrailingClick()
                }
                OpenGesture.CONTEXT_MENU, OpenGesture.NONE -> {}
            }
        })
        // Right-click (the Mac's Ctrl-click too) opens it in a new window, like a link or a file.
        row.addEventListener("contextmenu", { ev ->
            ev.preventDefault()
            ev.stopPropagation()
            viewModel.openSearchHitInNewWindow?.invoke(hit)
        })
        box.appendChild(row)
    }
    if (result.total > hits.size) {
        val more = document.createElement("div") as HTMLElement
        more.className = "lunarbor-search-node-more"
        val rest = result.total - hits.size
        if (isPage) {
            // Past what the registry keeps, nothing can list them; say so.
            more.textContent = "…and $rest more — narrow the search to see them"
        } else {
            more.textContent = "…and $rest more"
            more.title = "Show them all"
            more.classList.add("is-clickable")
            more.addEventListener("mousedown", { viewModel.zoomInto(nodeRow) })
        }
        box.appendChild(more)
    }
    return box
}

/**
 * Attribute on a search node's result box naming the node's document row,
 * so [SearchNodeHitCursor] finds the box again after a repaint. Each child
 * of the box is one entry: a hit row, then "…and N more" when listed.
 */
internal const val SEARCH_NODE_ROW_ATTR = "data-search-node-row"

/** A search node's count: "Searching…", "No matches", "1 match", "N matches". */
internal fun searchCountText(result: se.soderbjorn.lunarbor.data.TextSearchResult?): String = when {
    result == null -> "Searching…"
    result.total == 0 -> "No matches"
    result.total == 1 -> "1 match"
    else -> "${result.total} matches"
}

/**
 * A search node's match count, drawn on its line after its text (and the
 * magnifier standing in for its query), folded or not. Pressing it folds
 * or unfolds the result list, like the node's −/+ control. Not editable,
 * like the folder badge, so caret mapping never sees it.
 *
 * Called by [buildRowElement] for every search node row.
 */
private fun buildSearchNodeCount(
    view: PaneBackingViewModel.SearchNodeView,
    nodeId: LineId,
    viewModel: MainViewModel,
): HTMLElement {
    val count = document.createElement("span") as HTMLElement
    count.className = "lunarbor-search-node-count"
    count.setAttribute("contenteditable", "false")
    count.textContent = searchCountText(view.result)
    count.title = if (view.folded) "Show the matches" else "Hide the matches"
    count.addEventListener("mousedown", { ev ->
        // Keep the press away from the editor's caret placement and drag code.
        ev.preventDefault()
        ev.stopPropagation()
        viewModel.toggleCollapse(nodeId)
    })
    return count
}

/**
 * [text] in a `<bdi>`: kept left to right inside an element whose base
 * direction is right to left only so that it clips from the start (a
 * search hit's path, `direction: rtl`). Without it, punctuation at either
 * end would be reordered.
 *
 * Called for the paths of search node results and of the pane search's
 * result list (`PaneSearchBar`).
 */
internal fun isolatedText(text: String): HTMLElement {
    val bdi = document.createElement("bdi") as HTMLElement
    bdi.textContent = text
    return bdi
}

/**
 * Expand / collapse control rendered absolutely-positioned to the left
 * of the row's bullet glyph: a "−" on an open parent, a "+" on a folded
 * one, shown only while the row is hovered (Dynalist-style — a folded
 * parent is otherwise marked by the ring around its dot,
 * `.lunarbor-row-folded`). Clicking runs [onToggle] — normally
 * [MainViewModel.toggleCollapse]. Marked
 * `contenteditable="false"` so it never participates in caret placement.
 * Dispatches [FOLD_EVENT] before [onToggle], so the pane animates.
 *
 * @param isCollapsed `true` when the parent's children are hidden in
 *   this pane (folded, or a folder-backed bullet not yet expanded).
 * @param onToggle What a click does.
 *
 * Folder-backed bullets use the same chevron as any other parent: since
 * every parent bullet is folder-backed once saved, a separate adornment
 * would mark nothing and only flicker in after the first save.
 */
private fun buildChevron(
    isCollapsed: Boolean,
    onToggle: () -> Unit,
): HTMLElement {
    val target = document.createElement("div") as HTMLElement
    target.className = "lunarbor-chevron"
    target.title = if (isCollapsed) "Expand" else "Collapse"
    target.setAttribute("contenteditable", "false")
    target.style.apply {
        setProperty("position", "absolute")
        // The chevron sits to the left of the bullet glyph. Padding-left on
        // the row positions the bullet; we anchor the chevron at -22px so it
        // hangs into the editor's left margin rather than overlapping text.
        left = "-22px"
        top = "0"
        width = "22px"
        height = "100%"
        cursor = "pointer"
        display = "flex"
        alignItems = "center"
        justifyContent = "center"
        color = "var(--t-text-dim, #7a7a7a)"
        setProperty("user-select", "none")
    }
    // Dynalist-style: "−" collapses an open parent, "+" expands a folded
    // one. Only visible while the row is hovered (see the CSS).
    val vertical = if (isCollapsed) "<line x1=\"8\" y1=\"3\" x2=\"8\" y2=\"13\"></line>" else ""
    target.innerHTML = "<span class=\"lunarbor-chevron-hit\">" +
        "<svg viewBox=\"0 0 16 16\" width=\"12\" height=\"12\" stroke=\"currentColor\" " +
        "stroke-width=\"1.6\" stroke-linecap=\"round\" fill=\"none\" style=\"pointer-events: none;\">" +
        "<line x1=\"3\" y1=\"8\" x2=\"13\" y2=\"8\"></line>$vertical</svg></span>"
    target.addEventListener("mousedown", { event ->
        val me = event as MouseEvent
        me.stopPropagation()
        me.preventDefault()
    })
    target.addEventListener("click", { event ->
        val me = event as MouseEvent
        me.stopPropagation()
        me.preventDefault()
        // Tell the pane a fold is starting, so it animates the repaint
        // (`MainScreen` listens on the editor).
        val init: dynamic = js("({ bubbles: true })")
        target.dispatchEvent(org.w3c.dom.events.Event(FOLD_EVENT, init.unsafeCast<org.w3c.dom.EventInit>()))
        onToggle()
    })
    return target
}

/**
 * Class that stands in for `:hover` on freshly painted elements; every
 * `:hover` rule of a row's hover-only controls also matches it (see
 * [ensureStyles]).
 */
private const val HOVER_CARRY_CLASS: String = "lunarbor-hover"

/** Last pointer position in client coordinates; negative when outside the window. */
private var pointerX: Double = -1.0
private var pointerY: Double = -1.0

/** `true` while some element wears [HOVER_CARRY_CLASS]. */
private var hoverCarried: Boolean = false

/** Guards [installHoverTracking]. */
private var hoverTrackingInstalled: Boolean = false

/**
 * Tracks the pointer for [carryHoverAcrossRepaint]: remembers where it
 * is and drops the carried hover on the first move, when the browser's
 * own `:hover` has caught up. Installed once, on the document.
 */
private fun installHoverTracking() {
    if (hoverTrackingInstalled) return
    hoverTrackingInstalled = true
    document.addEventListener("mousemove", { event ->
        val me = event as MouseEvent
        pointerX = me.clientX.toDouble()
        pointerY = me.clientY.toDouble()
        clearHoverCarry()
    }, true)
    document.documentElement?.addEventListener("mouseleave", {
        pointerX = -1.0
        pointerY = -1.0
        clearHoverCarry()
    })
}

/** Removes [HOVER_CARRY_CLASS] from every element that wears it. */
private fun clearHoverCarry() {
    if (!hoverCarried) return
    hoverCarried = false
    val carried = document.querySelectorAll(".$HOVER_CARRY_CLASS")
    for (i in 0 until carried.length) (carried.item(i) as? HTMLElement)?.classList?.remove(HOVER_CARRY_CLASS)
}

/**
 * Keeps hover-only controls steady across a repaint (LBR-17). [paint]
 * rebuilds every row, and Chrome applies `:hover` to the new elements
 * under a still pointer only a frame or more later, so the hovered row's
 * −/+ (and its dot's hover ring, a block's ×) vanished and faded back in
 * on every keystroke anywhere, and several times per fold. This marks the
 * element under the pointer and its ancestors up to [editor] with
 * [HOVER_CARRY_CLASS], so they paint hovered from the start, with no
 * transition; the next pointer move hands back to `:hover`.
 *
 * Called by `MainScreen.reconcile` right after [paint] (and the scroll
 * restore, so the hit test sees the final layout).
 *
 * @param editor The pane's editor element [paint] just filled.
 */
internal fun carryHoverAcrossRepaint(editor: HTMLElement) {
    installHoverTracking()
    clearHoverCarry()
    if (pointerX < 0 || pointerY < 0) return
    var el = document.elementFromPoint(pointerX, pointerY) as? HTMLElement ?: return
    if (!editor.contains(el)) return
    while (el !== editor) {
        el.classList.add(HOVER_CARRY_CLASS)
        el = el.parentElement as? HTMLElement ?: break
    }
    hoverCarried = true
}

/**
 * DOM event a fold control dispatches (bubbling) right before it folds or
 * unfolds, so `MainScreen` animates only user folds — never the fold state
 * a document gets on load.
 */
internal const val FOLD_EVENT: String = "lunarbor-fold"

/**
 * Injects the stylesheet that drives bullet/chevron hover, scrollbars,
 * and the restructuring banner. Runs once — the guard on the `id` makes
 * repeat calls cheap.
 */
fun ensureStyles() {
    val existing = document.getElementById("lunarbor-cursor-style")
    if (existing != null) return
    val style = document.createElement("style") as HTMLElement
    style.id = "lunarbor-cursor-style"
    style.textContent = """
        .lunarbor-scroll::-webkit-scrollbar { width: 12px; }
        .lunarbor-scroll::-webkit-scrollbar-track {
            background: var(--t-bg, #1e1e1e);
        }
        .lunarbor-scroll::-webkit-scrollbar-thumb {
            background: var(--t-border, #4a4a4a);
            border-radius: 6px;
            border: 2px solid var(--t-bg, #1e1e1e);
        }
        .lunarbor-scroll::-webkit-scrollbar-thumb:hover {
            background: var(--t-text-dim, #5e5e5e);
        }
        /* The theme's opaque selection colour (lunula's `selection` token),
           with selected text in the bright text colour: links, tags, code
           and headings carry colours of their own (a tag's comes from its
           name), and only `text` / `textBright` are guaranteed readable on
           the selection (lunula's ThemeSelectionTest). */
        .lunarbor-editor ::selection {
            background: var(--t-selection, rgba(90, 176, 255, 0.45));
            color: var(--t-text-bright, #ffffff);
        }
        /* Onboarding affordance for an empty document (fresh vault). The
           blank root line is otherwise invisible, so the pane reads as
           "nothing to write in". This faint, click-through hint sits on
           the empty row and disappears on the first keystroke. It is not
           part of the model — `pointer-events: none` lets clicks fall
           through to place the caret, and `user-select: none` keeps it
           out of copy/selection. */
        .lunarbor-empty-hint {
            position: absolute;
            left: 0;
            top: 0;
            pointer-events: none;
            user-select: none;
            opacity: 0.4;
            font-style: italic;
        }
        .lunarbor-bullet-prefix {
            display: inline;
            cursor: grab;
        }
        /* While a selection drag is held (LBR-32, MainScreen's
           beginTextSelectionDrag), the rows' non-editable islands — bullet
           dots, fold controls, badges — let the pointer through, so the
           browser keeps finding a text position under it and the selection
           follows the mouse over the bullets. */
        body.lunarbor-text-selecting .lunarbor-editor [contenteditable="false"] {
            pointer-events: none;
        }
        /* Blocks (TRF-5): each row draws the side borders; the first and
           last rows add the top and bottom edges, so the rows together
           read as one rectangle that grows with its content. */
        .lunarbor-block-row {
            border-left: 1px solid var(--t-border, #4a4a4a);
            border-right: 1px solid var(--t-border, #4a4a4a);
            padding-left: 10px;
            padding-right: 28px;
            background: rgba(127, 127, 127, 0.05);
        }
        .lunarbor-block-first {
            border-top: 1px solid var(--t-border, #4a4a4a);
            border-top-left-radius: 6px;
            border-top-right-radius: 6px;
            padding-top: 4px;
            margin-top: 4px;
        }
        .lunarbor-block-last {
            border-bottom: 1px solid var(--t-border, #4a4a4a);
            border-bottom-left-radius: 6px;
            border-bottom-right-radius: 6px;
            padding-bottom: 4px;
            margin-bottom: 4px;
        }
        .lunarbor-block-delete {
            position: absolute;
            top: 3px;
            right: 6px;
            display: none;
            width: 18px;
            height: 18px;
            align-items: center;
            justify-content: center;
            border-radius: 4px;
            cursor: pointer;
            user-select: none;
            color: var(--t-text-dim, #9a9a9a);
            line-height: 1;
        }
        .lunarbor-block-delete:hover,
        .lunarbor-block-delete.lunarbor-hover {
            color: var(--t-text, #e6e6e6);
            background: var(--t-border, rgba(255, 255, 255, 0.10));
        }
        /* A large block's expand / collapse control: always shown, at
           the far right; the × moves one slot left of it. */
        .lunarbor-block-fold {
            position: absolute;
            top: 3px;
            right: 6px;
            display: flex;
            width: 18px;
            height: 18px;
            align-items: center;
            justify-content: center;
            border-radius: 4px;
            cursor: pointer;
            user-select: none;
            color: var(--t-text-dim, #9a9a9a);
        }
        .lunarbor-block-fold:hover,
        .lunarbor-block-fold.lunarbor-hover {
            color: var(--t-text, #e6e6e6);
            background: var(--t-border, rgba(255, 255, 255, 0.10));
        }
        .lunarbor-block-foldable {
            padding-right: 50px;
        }
        .lunarbor-block-foldable .lunarbor-block-delete {
            right: 28px;
        }
        /* The last row a collapsed large block shows: its text fades out
           and a label under it says how much is hidden. */
        .lunarbor-block-clipped > .lunarbor-text {
            -webkit-mask-image: linear-gradient(to bottom, #000 30%, transparent);
            mask-image: linear-gradient(to bottom, #000 30%, transparent);
        }
        .lunarbor-block-more {
            display: block;
            width: fit-content;
            margin-top: 2px;
            font-size: 12px;
            line-height: 18px;
            color: var(--t-text-dim, #9a9a9a);
            cursor: pointer;
            user-select: none;
        }
        .lunarbor-block-more:hover {
            color: var(--t-text, #e6e6e6);
            text-decoration: underline;
        }
        .lunarbor-block-first:hover .lunarbor-block-delete,
        .lunarbor-block-first.lunarbor-hover .lunarbor-block-delete,
        .lunarbor-block-hover .lunarbor-block-delete,
        .lunarbor-block-active .lunarbor-block-delete {
            display: flex;
        }
        .lunarbor-block-item-dot {
            position: absolute;
            top: 4px;
        }
        .lunarbor-block-list-dot {
            user-select: none;
            cursor: default;
        }
        .lunarbor-block-hint {
            position: absolute;
            left: 11px;
            top: 4px;
            pointer-events: none;
            user-select: none;
            opacity: 0.4;
            font-style: italic;
        }
        /* When the row contains an inline image, the row is much
           taller than a text-only line. The default baseline
           alignment of the bullet ends up centered in the middle of
           the image; switching the row's children to top alignment
           puts the bullet next to the first line of text instead.
           The bullet-prefix needs an explicit `inline-block` for
           vertical-align to apply, and a small top inset matches
           the visual line position of the surrounding text. */
        .lunarbor-row-has-image > .lunarbor-bullet-prefix {
            display: inline-block;
            vertical-align: top;
            line-height: var(--lunarbor-line-height, normal);
        }
        .lunarbor-row-has-image > .lunarbor-text {
            vertical-align: top;
        }
        /* Chevron normally fills the row height (`height: 100%`) and
           centers its glyph via flex — fine for text rows, but on an
           image row that centers the chevron in the middle of the
           image. Constrain the chevron's box to the first line so
           the glyph sits next to the bullet. */
        .lunarbor-row-has-image > .lunarbor-chevron {
            height: 1.5em;
        }
        .lunarbor-bullet-prefix:active {
            cursor: grabbing;
        }
        /* While a row drag is armed (MainScreen adds the class to <body>),
           the pointer is a closed hand everywhere — over text too, which
           would otherwise show the I-beam — and nothing gets selected. */
        body.lunarbor-dragging,
        body.lunarbor-dragging * {
            cursor: grabbing !important;
            user-select: none !important;
        }
        /* A drag is not a hover: rows the pointer passes over keep their
           hover-only controls (fold −/+, a block's ×) hidden and their
           dots still, so the drop line is the only thing that moves. */
        body.lunarbor-dragging [data-row]:hover > .lunarbor-chevron,
        body.lunarbor-dragging [data-row].lunarbor-hover > .lunarbor-chevron,
        body.lunarbor-dragging .lunarbor-chevron:hover,
        body.lunarbor-dragging .lunarbor-chevron.lunarbor-hover {
            opacity: 0;
        }
        body.lunarbor-dragging .lunarbor-block-first:not(.lunarbor-block-active) .lunarbor-block-delete {
            display: none;
        }
        body.lunarbor-dragging .lunarbor-bullet-prefix:hover .lunarbor-bullet,
        body.lunarbor-dragging .lunarbor-bullet-prefix.lunarbor-hover .lunarbor-bullet {
            transform: none;
            box-shadow: none;
        }
        .lunarbor-drop-indicator {
            position: fixed;
            height: 2px;
            background: var(--t-accent, #5ab0ff);
            box-shadow: 0 0 0 2px rgba(90, 176, 255, 0.18);
            pointer-events: none;
            z-index: 2000;
            border-radius: 2px;
        }
        .lunarbor-bullet {
            display: inline-block;
            width: 0.4em;
            height: 0.4em;
            margin: 0 0.25em;
            background: currentColor;
            color: var(--t-text, #e6e6e6);
            border-radius: 50%;
            vertical-align: 0.12em;
            transform-origin: center;
            transition: transform 120ms ease-out, box-shadow 120ms ease-out;
        }
        /* A heading bullet (--lunarbor-heading-scale, set by the row
           painter): the dot is measured in the heading's font, so it can
           sit at the heading's x-height (0.65ex ≈ the plain dot's 0.32em
           in the text font) while keeping the plain dot's size. */
        .lunarbor-row-heading > .lunarbor-bullet-prefix .lunarbor-bullet {
            font-family: var(--dt-font-display, inherit);
            font-size: calc(var(--lunarbor-heading-scale, 1) * 1em);
            width: calc(0.4em / var(--lunarbor-heading-scale, 1));
            height: calc(0.4em / var(--lunarbor-heading-scale, 1));
            margin: 0 calc(0.25em / var(--lunarbor-heading-scale, 1));
            vertical-align: calc(0.65ex - 0.2em / var(--lunarbor-heading-scale, 1));
        }
        .lunarbor-bullet-prefix:hover .lunarbor-bullet,
        .lunarbor-bullet-prefix.lunarbor-hover .lunarbor-bullet {
            transform: scale(1.15);
            box-shadow: 0 0 0 5px var(--t-border, rgba(255, 255, 255, 0.10));
        }
        .lunarbor-bullet-prefix:active .lunarbor-bullet {
            transform: scale(0.92);
            box-shadow: 0 0 0 4px var(--t-border, rgba(255, 255, 255, 0.14));
        }
        /* Markdown mode (TRF-7): the dot is a plain list marker, not a
           drag / zoom handle, so it does not react to the pointer. */
        .lunarbor-bullet-prefix.lunarbor-bullet-plain:active {
            cursor: default;
        }
        .lunarbor-bullet-plain:hover .lunarbor-bullet,
        .lunarbor-bullet-plain.lunarbor-hover .lunarbor-bullet,
        .lunarbor-bullet-plain:active .lunarbor-bullet {
            transform: none;
            box-shadow: none;
        }
        /* Expand / collapse control: hidden until the row is hovered,
           like Dynalist. `.lunarbor-hover` stands in for `:hover` right
           after a repaint (carryHoverAcrossRepaint), so it never blinks. */
        .lunarbor-chevron {
            opacity: 0;
            transition: color 120ms ease-out, opacity 120ms ease-out;
        }
        [data-row]:hover > .lunarbor-chevron,
        [data-row].lunarbor-hover > .lunarbor-chevron {
            opacity: 0.85;
        }
        /* The repaint has already styled the new elements unhovered
           (the scroll restore forces a style pass), so a carried hover
           must not fade in from there. */
        [data-row].lunarbor-hover > .lunarbor-chevron,
        .lunarbor-chevron.lunarbor-hover .lunarbor-chevron-hit,
        .lunarbor-bullet-prefix.lunarbor-hover .lunarbor-bullet {
            transition: none;
        }
        .lunarbor-chevron:hover,
        .lunarbor-chevron.lunarbor-hover {
            color: var(--t-text, #e6e6e6);
            opacity: 1;
        }
        /* A folded parent's dot wears a ring, so a row with hidden
           children reads as such without any chevron. */
        .lunarbor-row-folded > .lunarbor-bullet-prefix .lunarbor-bullet {
            box-shadow: 0 0 0 4px var(--t-border, rgba(127, 127, 127, 0.35));
        }
        /* A mirror's dot is a ring in the accent colour: its items live in
           another node. Folded, the usual ring sits around it, tinted. */
        .lunarbor-row-mirror > .lunarbor-bullet-prefix .lunarbor-bullet {
            background: transparent;
            box-shadow: inset 0 0 0 2px var(--t-accent, #5ab0ff);
        }
        .lunarbor-row-mirror.lunarbor-row-folded > .lunarbor-bullet-prefix .lunarbor-bullet {
            box-shadow: inset 0 0 0 2px var(--t-accent, #5ab0ff),
                0 0 0 4px color-mix(in srgb, var(--t-accent, #5ab0ff) 30%, transparent);
        }
        /* A done item (LBR-24) and everything under it: dimmed. Its
           guide lines dim with it; the fold control keeps full strength
           on hover. */
        [data-row].lunarbor-row-done > .lunarbor-text,
        [data-row].lunarbor-row-done > .lunarbor-bullet-prefix,
        [data-row].lunarbor-row-done.lunarbor-block-row {
            opacity: 0.5;
        }
        /* Bullet rows carry a negative text-indent for the hanging
           indent; nothing inside a row may inherit it. */
        [data-row] * {
            text-indent: 0;
        }
        /* Indent guide lines (see appendIndentGuides). */
        .lunarbor-guides {
            position: absolute;
            top: 0;
            bottom: 0;
            pointer-events: none;
            user-select: none;
        }
        /* The hover highlight lives on this inner pill, not the full 22px
           chevron hit-box, so it hugs the arrow and never slides under the
           neighbouring promoted-ref icon (which hangs just to its left). */
        .lunarbor-chevron-hit {
            display: flex;
            align-items: center;
            justify-content: center;
            padding: 2px;
            border-radius: 4px;
            transition: background 120ms ease-out;
        }
        .lunarbor-chevron:hover .lunarbor-chevron-hit,
        .lunarbor-chevron.lunarbor-hover .lunarbor-chevron-hit {
            background: var(--t-border, rgba(255, 255, 255, 0.10));
        }
        .lunarbor-chevron:active .lunarbor-chevron-hit {
            background: var(--t-border, rgba(255, 255, 255, 0.14));
        }
        @keyframes lunarbor-spinner-rotate { to { transform: rotate(360deg); } }
        .lunarbor-restructuring {
            position: fixed;
            bottom: 14px;
            right: 14px;
            display: none;
            align-items: center;
            gap: 8px;
            padding: 6px 12px;
            background: var(--t-surface-alt, rgba(42, 42, 42, 0.95));
            color: var(--t-text-dim, #cfcfcf);
            border: 1px solid var(--t-border, #4a4a4a);
            border-radius: 999px;
            font-size: 12px;
            line-height: 1;
            z-index: 1000;
            pointer-events: none;
            box-shadow: 0 2px 6px rgba(0, 0, 0, 0.35);
        }
        /* "Convert block to nodes" / "Clean up blocks" progress
           (MainScreen.updateBulkEditProgress). */
        .lunarbor-bulk-progress {
            position: fixed;
            bottom: 14px;
            right: 14px;
            display: flex;
            flex-direction: column;
            gap: 6px;
            min-width: 220px;
            padding: 8px 12px;
            background: var(--t-surface-alt, rgba(42, 42, 42, 0.95));
            color: var(--t-text-dim, #cfcfcf);
            border: 1px solid var(--t-border, #4a4a4a);
            border-radius: 8px;
            font-size: 12px;
            line-height: 1.2;
            z-index: 1001;
            pointer-events: none;
        }
        .lunarbor-bulk-progress-track {
            height: 4px;
            border-radius: 2px;
            overflow: hidden;
            background: var(--t-border, #4a4a4a);
        }
        .lunarbor-bulk-progress-fill {
            height: 100%;
            width: 0;
            background: var(--t-accent, #5ab0ff);
            transition: width 150ms ease-out;
        }
        .lunarbor-bulk-progress-indeterminate .lunarbor-bulk-progress-fill {
            width: 30%;
            animation: lunarbor-bulk-progress-slide 1.1s ease-in-out infinite;
        }
        @keyframes lunarbor-bulk-progress-slide {
            from { transform: translateX(-100%); }
            to { transform: translateX(340%); }
        }
        @media (prefers-reduced-motion: reduce) {
            .lunarbor-bulk-progress-indeterminate .lunarbor-bulk-progress-fill { animation: none; }
        }
        .lunarbor-restructuring-spinner {
            width: 12px;
            height: 12px;
            border: 2px solid var(--t-border, #4a4a4a);
            border-top-color: var(--t-accent, #5ab0ff);
            border-radius: 50%;
            animation: lunarbor-spinner-rotate 0.8s linear infinite;
        }
        /* Folder contents list (TRF-6): everything in the current node's
           folder that is not a bullet, in a sibling block under the
           contenteditable editor host, so it scrolls with the bullets but
           never takes the caret. A divider and a smaller, dimmer type set
           it apart from the outline. */
        .lunarbor-folder-contents {
            border-top: 1px solid var(--t-border, #4a4a4a);
            margin-top: 24px;
            font-size: 0.92em !important;
        }
        /* Nothing to list: the element stays mounted (the paint loop never
           removes it) but takes no space. The `!important`s beat the
           inline padding set by `buildFolderContentsElement`. */
        .lunarbor-folder-contents:empty {
            border-top: none !important;
            margin-top: 0 !important;
            padding: 0 !important;
        }
        .lunarbor-folder-entry {
            display: flex;
            align-items: center;
            gap: 8px;
            cursor: pointer;
            user-select: none;
            border-radius: 4px;
            padding: 0 6px;
            margin: 0 -6px;
            color: var(--t-text, #e6e6e6);
        }
        .lunarbor-folder-entry:hover {
            background: var(--t-surface-alt, rgba(127, 127, 127, 0.12));
        }
        .lunarbor-folder-entry-glyph {
            display: inline-flex;
            align-items: center;
            justify-content: center;
            flex: 0 0 auto;
            color: var(--t-text-dim, #7a7a7a);
        }
        .lunarbor-folder-entry[data-entry-kind="folder"] .lunarbor-folder-entry-glyph {
            color: var(--t-accent, #5ab0ff);
        }
        .lunarbor-folder-entry-name {
            flex: 1 1 auto;
            min-width: 0;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
        }
        /* Row menu (⋮): only while the row is hovered or its menu is open
           (the toolkit's openPaneMenu tags the anchor with `dt-open`). */
        .lunarbor-folder-entry-menu {
            flex: 0 0 auto;
            width: 22px;
            text-align: center;
            border-radius: 4px;
            font-size: 16px;
            color: var(--t-text-dim, #7a7a7a);
            visibility: hidden;
        }
        .lunarbor-folder-entry:hover .lunarbor-folder-entry-menu,
        .lunarbor-folder-entry-menu.dt-open {
            visibility: visible;
        }
        .lunarbor-folder-entry-menu:hover,
        .lunarbor-folder-entry-menu.dt-open {
            color: var(--t-text, #e6e6e6);
            background: var(--t-surface-alt, rgba(127, 127, 127, 0.18));
        }
        /* Count badge on an expanded folder-backed bullet ("3 files"):
           the items zooming into it would list under its bullets. Not
           editable and not part of the text, so caret mapping (which
           reads only `.lunarbor-text`) never sees it. */
        .lunarbor-folder-badge {
            display: inline-block;
            margin-left: 10px;
            padding: 0 6px;
            border-radius: 8px;
            font-size: 11px;
            line-height: 16px;
            vertical-align: 1px;
            color: var(--t-text-dim, #7a7a7a);
            border: 1px solid var(--t-border, #4a4a4a);
            user-select: none;
            cursor: pointer;
            white-space: nowrap;
        }
        .lunarbor-folder-badge:hover {
            color: var(--t-text, #e6e6e6);
        }
        /* WYSIWYG markdown styles: the marker characters (**, *, <u>, ~~,
           `) are not in the DOM at all, so styling here only affects the
           rendered text. The underlying model line still contains the
           markdown so files round-trip cleanly through other tools. */
        .lunarbor-md-bold { font-weight: 700; }
        .lunarbor-md-italic { font-style: italic; }
        .lunarbor-md-strike { text-decoration: line-through; }
        /* A code block inside a block: its rows' text forms one band,
           rounded where the run of code rows starts and ends. */
        .lunarbor-code-row > .lunarbor-code-text {
            display: block;
            /* The Code font and size (App settings → Appearance → Fonts). */
            font-family: var(--dt-font-mono, ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace);
            font-size: var(--dt-font-mono-size, 0.9em);
            white-space: pre-wrap;
            background: var(--t-border, rgba(255, 255, 255, 0.10));
            padding: 0 10px;
            margin-right: -18px;
        }
        .lunarbor-code-first > .lunarbor-code-text {
            border-top-left-radius: 6px;
            border-top-right-radius: 6px;
            padding-top: 6px;
        }
        /* Space around the band as row padding: a margin on the band
           would collapse through the row and open a gap in the block's
           background. */
        .lunarbor-code-first { padding-top: 4px; }
        .lunarbor-code-last { padding-bottom: 4px; }
        .lunarbor-code-last > .lunarbor-code-text {
            border-bottom-left-radius: 6px;
            border-bottom-right-radius: 6px;
            padding-bottom: 6px;
        }
        .lunarbor-md-code {
            /* The Code font and size (App settings → Appearance → Fonts). */
            font-family: var(--dt-font-mono, ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace);
            font-size: var(--dt-font-mono-size, 0.95em);
            background: var(--t-border, rgba(255, 255, 255, 0.10));
            padding: 0 4px;
            border-radius: 3px;
        }
        /* Code in a heading grows with the heading, not the Code size. */
        .lunarbor-md-h1 .lunarbor-md-code, .lunarbor-md-h2 .lunarbor-md-code,
        .lunarbor-md-h3 .lunarbor-md-code, .lunarbor-md-h4 .lunarbor-md-code,
        .lunarbor-md-h5 .lunarbor-md-code, .lunarbor-md-h6 .lunarbor-md-code {
            font-size: 0.95em;
        }
        /* Read-only preview of a linked node under its link bullet. */
        /* A search node's query, `{{search: …}}`: a chip, still plain
           editable text — shown only on the caret's row, so the node reads
           as its title the rest of the time. Elsewhere its text shrinks to
           nothing and a small magnifier marks the bullet as a search node
           (a pseudo-element: no text, so caret mapping never sees it). */
        .lunarbor-md-search {
            font-size: 0;
        }
        .lunarbor-md-search::before {
            content: "";
            display: inline-block;
            width: 13px;
            height: 13px;
            margin-left: 2px;
            vertical-align: -1px;
            background-color: var(--t-text-dim, #9a9a9a);
            -webkit-mask: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2.4' stroke-linecap='round'%3E%3Ccircle cx='11' cy='11' r='6.5'/%3E%3Cline x1='20' y1='20' x2='16' y2='16'/%3E%3C/svg%3E") center / contain no-repeat;
            mask: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2.4' stroke-linecap='round'%3E%3Ccircle cx='11' cy='11' r='6.5'/%3E%3Cline x1='20' y1='20' x2='16' y2='16'/%3E%3C/svg%3E") center / contain no-repeat;
        }
        /* A resolved wiki link's syntax (`[[`, `Name|`, `]]`): hidden like
           a search query unless the row holds the caret, so the link reads
           as its name. Still text, so caret mapping never changes. */
        .lunarbor-md-wiki-syntax {
            font-size: 0;
        }
        .lunarbor-row-caret .lunarbor-md-wiki-syntax {
            font-size: inherit;
        }
        .lunarbor-row-caret .lunarbor-md-search::before {
            display: none;
        }
        .lunarbor-row-caret .lunarbor-md-search {
            color: var(--t-text-dim, #9a9a9a);
            background: rgba(127, 127, 127, 0.12);
            border-radius: 4px;
            padding: 0 4px;
            font-size: 0.85em;
        }
        /* A search node's live results, read-only, under its text. */
        .lunarbor-search-node {
            text-indent: 0;
            user-select: none;
            -webkit-user-select: none;
            font-size: 0.92em;
        }
        /* The count in a zoomed search node's page title, after its
           magnifier: the title's size would make it shout. */
        .lunarbor-title-search-count {
            margin-left: 12px;
            color: var(--t-text-dim, #9a9a9a);
            font-size: 15px;
            font-weight: 400;
            vertical-align: middle;
            user-select: none;
            -webkit-user-select: none;
        }
        /* "Daily template" (LBR-21): a small pill after the template
           page's title — chrome, so it never takes the title's size. */
        .lunarbor-title-daily-template {
            display: inline-block;
            margin-left: 12px;
            padding: 1px 8px;
            border-radius: 999px;
            color: var(--t-accent, #6aa5ff);
            background: color-mix(in srgb, var(--t-accent, #6aa5ff) 14%, transparent);
            box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--t-accent, #6aa5ff) 40%, transparent);
            font-size: 12px;
            font-weight: 500;
            line-height: 18px;
            letter-spacing: 0.02em;
            font-style: normal;
            text-decoration: none;
            vertical-align: middle;
            white-space: nowrap;
            user-select: none;
            -webkit-user-select: none;
        }
        /* The count on the node's line, after the magnifier. */
        .lunarbor-search-node-count {
            margin-left: 8px;
            color: var(--t-text-dim, #9a9a9a);
            font-size: 0.8em;
            cursor: pointer;
            user-select: none;
            -webkit-user-select: none;
        }
        .lunarbor-search-node-count:hover {
            color: var(--t-text, #e6e6e6);
        }
        .lunarbor-search-node-hit.lunarbor-hit-done {
            opacity: 0.5;
        }
        /* Done (LBR-24): struck through, like a done row in the outline. */
        .lunarbor-search-node-hit.lunarbor-hit-done .lunarbor-search-node-text {
            text-decoration: line-through;
        }
        .lunarbor-search-node-hit {
            display: flex;
            align-items: baseline;
            gap: 10px;
            padding: 1px 6px;
            margin-left: -6px;
            border-radius: 4px;
            cursor: pointer;
            white-space: nowrap;
            overflow: hidden;
        }
        .lunarbor-search-node-hit:hover {
            background: rgba(127, 127, 127, 0.10);
        }
        /* The entry the arrow keys are on (SearchNodeHitCursor); the
           editor's caret is hidden meanwhile. */
        .lunarbor-search-node-hit.is-key-selected,
        .lunarbor-search-node-more.is-key-selected {
            background: var(--t-accent-soft, rgba(90, 160, 255, 0.18));
        }
        .lunarbor-hit-cursor-active {
            caret-color: transparent;
        }
        .lunarbor-search-node-text {
            color: var(--t-accent, #5ab0ff);
            overflow: hidden;
            text-overflow: ellipsis;
        }
        .lunarbor-search-node-text mark {
            background: var(--t-accent-soft, rgba(90, 160, 255, 0.25));
            color: inherit;
            border-radius: 2px;
        }
        /* Clipped from the START: a path's informative end is its tail.
           `direction: rtl` moves the clip to the left edge; the text sits
           in a <bdi> ([isolatedText]) so it still reads left to right. */
        .lunarbor-search-node-where {
            flex: 1 1 auto;
            min-width: 0;
            color: var(--t-text-dim, #9a9a9a);
            font-size: 0.85em;
            overflow: hidden;
            text-overflow: ellipsis;
            direction: rtl;
            text-align: left;
        }
        .lunarbor-search-node-more {
            width: fit-content;
            color: var(--t-text-dim, #9a9a9a);
            font-size: 0.85em;
        }
        .lunarbor-search-node-more.is-clickable {
            cursor: pointer;
        }
        .lunarbor-search-node-more.is-clickable:hover {
            color: var(--t-text, #e6e6e6);
            text-decoration: underline;
        }
        .lunarbor-md-link {
            color: var(--t-accent, #5ab0ff);
            text-decoration: underline;
            text-underline-offset: 2px;
            cursor: pointer;
        }
        /* A vault link whose target is gone (TRF-8): struck through and
           muted; the tooltip says "Not found". */
        .lunarbor-md-link.lunarbor-md-link-broken {
            color: var(--t-text-muted, #8a8a8a);
            text-decoration: line-through;
        }
        /* Hashtag (`#name`) — a soft pill in the tag's own colour: the hue
           `--tag-h` comes from the tag's name ([tagHue]), so a tag always
           looks the same and tags tell apart at a glance. The text mixes
           the hue with the theme's text colour, so it reads on light and
           dark themes alike. The whole `#name` is real text in the model;
           the padding only pads the run span. */
        .lunarbor-md-tag {
            --tag-h: 210;
            color: color-mix(in srgb, hsl(var(--tag-h) 75% 45%) 72%, var(--t-text, #e6e6e6));
            background: hsl(var(--tag-h) 80% 55% / 0.16);
            box-shadow: inset 0 0 0 1px hsl(var(--tag-h) 70% 50% / 0.35);
            border-radius: 5px;
            padding: 0 5px;
            font-weight: 500;
        }
        /* Inline image — replaces the `![alt](src)` syntax span with an
           atomic non-editable element containing the rendered image. The
           outer span participates in inline layout; the `<img>` inside is
           constrained so a giant screenshot doesn't blow up the row.
           Top-aligned, so text before or after a tall image stays on the
           row's first line, level with the bullet dot, instead of sinking
           to the image's middle. */
        .lunarbor-md-image {
            display: inline-block;
            position: relative;
            vertical-align: top;
            user-select: none;
            cursor: pointer;
            max-width: 100%;
        }
        .lunarbor-md-image img {
            display: block;
            max-width: 100%;
            max-height: 600px;
            border-radius: 4px;
            -webkit-user-drag: none;
        }
        /* Drag-to-resize affordance pinned at the bottom-right corner.
           Hidden until hover so the read view stays clean. The handle
           is intentionally large (16 px) with a generous hit area so
           it's easy to grab; the inner dot draws the accent visual. */
        .lunarbor-image-resize-handle {
            position: absolute;
            right: -4px;
            bottom: -4px;
            width: 16px;
            height: 16px;
            cursor: nwse-resize;
            opacity: 0;
            transition: opacity 0.1s;
            z-index: 2;
        }
        .lunarbor-image-resize-handle::after {
            content: "";
            position: absolute;
            right: 4px;
            bottom: 4px;
            width: 10px;
            height: 10px;
            border-radius: 50%;
            background: var(--t-accent, #5ab0ff);
            border: 2px solid var(--t-surface, #1a1a1a);
            box-sizing: content-box;
        }
        .lunarbor-md-image:hover .lunarbor-image-resize-handle,
        .lunarbor-md-image.is-resizing .lunarbor-image-resize-handle {
            opacity: 1;
        }
        .lunarbor-md-image.is-resizing { cursor: nwse-resize; }
        .lunarbor-md-image.is-resizing img { pointer-events: none; }
        /* Embedded Excalidraw drawing (DrawingPreview.kt): the SVG fills
           the picture span, whose width is the embed's or the drawing's. */
        .lunarbor-md-drawing {
            display: block;
            max-width: 100%;
            border-radius: 4px;
            overflow: hidden;
        }
        .lunarbor-md-drawing svg {
            display: block;
            width: 100%;
            height: auto;
        }
        .lunarbor-md-drawing.is-placeholder {
            padding: 4px 8px;
            border: 1px dashed var(--t-border, rgba(255, 255, 255, 0.20));
            color: var(--t-text-dim, rgba(255, 255, 255, 0.55));
            font-size: 12px;
        }
        .lunarbor-md-image.is-resizing .lunarbor-md-drawing { pointer-events: none; }
        /* Broken-image fallback. Swapped in by OutlinePaintLoop's
           img `error` handler when the asset URL fails to resolve. */
        .lunarbor-md-image-broken {
            display: inline-block;
            padding: 4px 8px;
            border: 1px dashed var(--t-border, rgba(255, 255, 255, 0.20));
            border-radius: 4px;
            color: var(--t-text-dim, rgba(255, 255, 255, 0.55));
            font-size: 12px;
            font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
        }
        /* Pane-level read-only image view (see ImageViewer.kt). Shown in
           place of the contenteditable editor whenever the pane's active
           file is an image. Centered with a generous gutter so the image
           sits on the same content axis as the editor's text. */
        .lunarbor-image-viewer {
            display: flex;
            align-items: center;
            justify-content: center;
            min-height: 50vh;
        }
        .lunarbor-image-viewer-img {
            max-width: 100%;
            max-height: 80vh;
            object-fit: contain;
            user-select: none;
            -webkit-user-drag: none;
            border-radius: 4px;
        }
        .lunarbor-image-viewer-broken {
            padding: 8px 12px;
            border: 1px dashed var(--t-border, rgba(255, 255, 255, 0.20));
            border-radius: 4px;
            color: var(--t-text-dim, rgba(255, 255, 255, 0.55));
            font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
        }
        /* Pane-level drawing editor (see DrawingEditor.kt): Excalidraw
           fills the pane below the title and scrolls its own canvas. */
        .lunarbor-drawing-root {
            position: absolute;
            inset: 0;
        }
        .lunarbor-drawing-loading {
            padding: 16px 24px;
            color: var(--t-text-dim, rgba(255, 255, 255, 0.55));
        }
        /* Markdown headings take the Display font (App settings →
           Appearance; e.g. Unbounded), the editor's own face while none
           is picked. The page title does too (inline, MainScreen). */
        .lunarbor-text.lunarbor-md-h1, .lunarbor-text.lunarbor-md-h2,
        .lunarbor-text.lunarbor-md-h3, .lunarbor-text.lunarbor-md-h4,
        .lunarbor-text.lunarbor-md-h5, .lunarbor-text.lunarbor-md-h6 {
            font-family: var(--dt-font-display, inherit);
        }
        .lunarbor-text.lunarbor-md-h1 {
            font-size: 1.6em;
            font-weight: 700;
            line-height: 1.25;
        }
        .lunarbor-text.lunarbor-md-h2 {
            font-size: 1.35em;
            font-weight: 700;
            line-height: 1.25;
        }
        .lunarbor-text.lunarbor-md-h3 {
            font-size: 1.15em;
            font-weight: 600;
            line-height: 1.3;
        }
        .lunarbor-text.lunarbor-md-h4 {
            font-size: 1.05em;
            font-weight: 600;
            line-height: 1.3;
        }
        .lunarbor-text.lunarbor-md-h5 {
            font-size: 1.0em;
            font-weight: 600;
            line-height: 1.35;
        }
        .lunarbor-text.lunarbor-md-h6 {
            font-size: 0.95em;
            font-weight: 600;
            line-height: 1.4;
            color: var(--t-text-dim, #cfcfcf);
        }
        .lunarbor-text.lunarbor-md-quote {
            display: inline-block;
            border-left: 3px solid var(--t-border, #4a4a4a);
            padding-left: 8px;
            color: var(--t-text-dim, #cfcfcf);
            font-style: italic;
        }
        /* Zoom headline (the big title above the editor). Base size
           is set here rather than inline on the element so the
           per-style overrides below can supersede it. */
        .lunarbor-title {
            font-size: 32px;
            font-weight: 600;
            line-height: 1.2;
        }
        /* A note's title renames the file (MainScreen.wireTitleEditing). */
        .lunarbor-title.lunarbor-title-editable { cursor: text; }
        .lunarbor-title.lunarbor-title-editable:focus {
            outline: none;
            text-overflow: clip;
        }
        .lunarbor-title.lunarbor-title-h1 { font-size: 36px; font-weight: 700; }
        .lunarbor-title.lunarbor-title-h2 { font-size: 30px; font-weight: 700; }
        .lunarbor-title.lunarbor-title-h3 { font-size: 26px; font-weight: 600; }
        .lunarbor-title.lunarbor-title-h4 { font-size: 22px; font-weight: 600; }
        .lunarbor-title.lunarbor-title-h5 { font-size: 20px; font-weight: 600; }
        .lunarbor-title.lunarbor-title-h6 {
            font-size: 18px;
            font-weight: 600;
            color: var(--t-text-dim, #cfcfcf);
        }
        .lunarbor-title.lunarbor-title-quote {
            border-left: 3px solid var(--t-border, #4a4a4a);
            padding-left: 12px;
            font-style: italic;
            color: var(--t-text-dim, #cfcfcf);
        }
        /* Style dropdown menu (anchored to the Style toolbar button). */
        .lunarbor-style-menu {
            position: fixed;
            z-index: 2000;
            min-width: 220px;
            background: var(--t-surface-alt, #2a2a2a);
            border: 1px solid var(--t-border, #4a4a4a);
            border-radius: 6px;
            box-shadow: 0 6px 20px rgba(0, 0, 0, 0.4);
            padding: 4px;
            font-size: 13px;
            color: var(--t-text, #e6e6e6);
        }
        .lunarbor-style-item {
            display: flex;
            align-items: center;
            gap: 8px;
            width: 100%;
            padding: 6px 10px;
            background: transparent;
            border: 0;
            color: inherit;
            text-align: left;
            cursor: pointer;
            border-radius: 4px;
            font-size: inherit;
            font-family: inherit;
        }
        .lunarbor-style-item:hover {
            background: var(--t-border, rgba(255, 255, 255, 0.10));
        }
        .lunarbor-style-item.is-active {
            background: var(--t-accent-soft, rgba(90, 176, 255, 0.18));
        }
        .lunarbor-style-icon {
            width: 16px;
            height: 16px;
            flex: 0 0 16px;
            display: inline-flex;
            align-items: center;
            justify-content: center;
            color: var(--t-text-dim, #cfcfcf);
        }
        .lunarbor-style-label { flex: 1 1 auto; }
        .lunarbor-style-check {
            margin-left: auto;
            opacity: 0.9;
            color: var(--t-accent, #5ab0ff);
        }
        .lunarbor-style-divider {
            border: 0;
            border-top: 1px solid var(--t-border, #3a3a3a);
            margin: 4px 0;
        }
    """.trimIndent()
    document.head?.appendChild(style)
}
