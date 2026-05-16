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
 * Each rendered row is one of two shapes:
 *
 *   non-bullet:  <div data-row="N" data-prefix-len="0">
 *                  <span class="text">{line}</span>
 *                </div>
 *
 *   bullet:      <div data-row="N" data-prefix-len="P" style="padding-left:Xpx">
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

package se.soderbjorn.notegrow.main

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement
import org.w3c.dom.events.MouseEvent
import se.soderbjorn.notegrow.data.InlineMarkdownTokenizer
import se.soderbjorn.notegrow.data.InlineStyle
import se.soderbjorn.notegrow.data.LineMarkdownPrefix
import se.soderbjorn.notegrow.data.LineStyle
import se.soderbjorn.notegrow.data.StyledRun

/**
 * Per-row mapping between displayed (markers-stripped) text and the
 * underlying model line, scoped to the editable region (the
 * `.notegrow-text` wrapper inside the row's div).
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
 * Module-level map from row div → its column-translation. Cleared at the
 * start of every `paint`. Holding strong references is fine because the
 * old row divs are released when `editor.innerHTML = ""` runs first.
 */
private val rowColumnMaps: MutableMap<HTMLElement, RowColumnMap> = HashMap()

/** Look up the column map for [rowDiv], if it has one. */
internal fun rowColumnMapOf(rowDiv: HTMLElement): RowColumnMap? = rowColumnMaps[rowDiv]

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
    editor.innerHTML = ""
    rowColumnMaps.clear()
    if (!state.isLoaded) {
        paintLoading(editor)
        return
    }
    val docState = state.documentState ?: return
    val zoom = viewModel.zoomInfo(state)
    val startRow = zoom?.startRow ?: 0
    val endRowInclusive = zoom?.endRowInclusive ?: state.lines.lastIndex
    if (endRowInclusive < startRow) return

    // When zoomed, indents render relative to the zoom target so the
    // closest descendants paint flush-left rather than indented one level
    // under the (no longer visible) zoom target. We strip `zoomIndent +
    // TAB_SIZE` characters: every descendant in the zoom subtree is a
    // bullet with indent strictly greater than `zoomIndent`, and
    // TAB_SIZE-aligned descendants always have at least
    // `zoomIndent + TAB_SIZE` leading characters.
    val viewOriginCol = zoom?.let { it.zoomIndent + PaneBackingViewModel.TAB_SIZE } ?: 0

    val visibleRows = DocumentLayout.visibleRowsOf(
        docState.lines, docState.lineIds, state.collapsedIds, startRow, endRowInclusive
    )
    for (row in visibleRows) {
        val rawLine = state.lines[row]
        // Only strip the zoom indent when the row actually has at least
        // `viewOriginCol` leading whitespace chars. A row inside the zoom
        // region whose content sits at a shallower column (e.g., user-
        // edited prose at col 0, or the leftover of a stripped bullet)
        // must display its full content; stripping blindly would chop
        // real characters and the resulting prefix-len mismatch would
        // misroute typed input through the model<->DOM caret mapping.
        val canStrip = viewOriginCol > 0 &&
            rawLine.length >= viewOriginCol &&
            (0 until viewOriginCol).all { rawLine[it] == ' ' }
        val stripPrefix = if (canStrip) viewOriginCol else 0
        val line = if (stripPrefix > 0) rawLine.substring(stripPrefix) else rawLine
        editor.appendChild(
            buildRowElement(row, line, stripPrefix, state, docState, viewModel, style, onBulletMouseDown)
        )
    }
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
 */
private fun buildRowElement(
    absoluteRow: Int,
    line: String,
    viewOriginCol: Int,
    state: PaneBackingViewModel.State,
    docState: Document.State,
    viewModel: MainViewModel,
    style: EditorStyle,
    onBulletMouseDown: ((absoluteRow: Int, ev: MouseEvent) -> Unit)? = null,
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

    // Inline-image rows are much taller than a text-only line, which
    // makes a baseline-aligned bullet visually float in the vertical
    // middle of the image. The `has-image` class swaps to top
    // alignment so the bullet sits next to the first line of text.
    // Cheap heuristic — exact detection would re-tokenize, but any
    // line carrying both `![` and `](` is virtually certain to hold a
    // markdown image; a false positive just changes alignment of a
    // row that doesn't need it, which is harmless.
    if (lineLooksLikeImageRow(line)) {
        rowDiv.classList.add("notegrow-row-has-image")
    }

    if (bulletCol >= 0) {
        // Visually indent the row by its bullet depth via padding-left, so the
        // bullet glyph itself sits at a depth-appropriate offset without
        // requiring monospace alignment.
        val depth = bulletCol / PaneBackingViewModel.TAB_SIZE
        rowDiv.style.paddingLeft = "${depth * style.indentStepPx}px"
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
        if (rowId != null) {
            val isFoldedPromotedRef = viewModel.isPromotedRef(rowId) &&
                rowId !in state.expandedRefIdsLocal
            val isCollapsibleParent =
                DocumentLayout.hasChildren(docState.lines, absoluteRow, absoluteIndentInRaw) ||
                    isFoldedPromotedRef
            val isCollapsedNow = rowId in state.collapsedIds
            if (isCollapsibleParent) {
                val isRef = viewModel.isPromotedRef(rowId)
                val chevron = buildChevron(rowId, isCollapsedNow, viewModel)
                // Position the chevron just to the left of THIS row's bullet
                // glyph, not the editor's left margin. The row's bullet sits
                // at `padding-left = depth * indentStepPx` from the row's
                // box; the chevron's 22px slot lands immediately before it.
                chevron.style.left = "${depth * style.indentStepPx - 22}px"
                rowDiv.appendChild(chevron)
                if (isRef) {
                    // Discrete file-icon adornment to the left of the
                    // chevron, signalling that expanding leads into a
                    // separate Markdown file rather than child bullets.
                    val refIcon = buildPromotedRefIcon()
                    refIcon.style.left = "${depth * style.indentStepPx - 36}px"
                    rowDiv.appendChild(refIcon)
                }
            }
        }

        rowDiv.appendChild(buildBulletPrefix(absoluteRow, onBulletMouseDown))
        rowDiv.appendChild(buildStyledTextRegion(rowDiv, line.substring(bulletCol + 2)))
    } else {
        // Non-bullet line: editable text starts at column 0 of the raw line,
        // unless we stripped a zoom indent — in that case the displayed text
        // begins at `viewOriginCol` in raw model columns.
        rowDiv.setAttribute("data-prefix-len", viewOriginCol.toString())
        rowDiv.appendChild(buildStyledTextRegion(rowDiv, line))
    }

    return rowDiv
}

/**
 * Builds the `.notegrow-text` editable region for one row. Splits the
 * inline markdown into one `<span class="notegrow-text-run …">` per
 * styled run; marker characters are *not* in the DOM at all so the user
 * sees a pure WYSIWYG view. Also detects a leading line-level prefix
 * (`# `, `> `, etc.), strips it from the rendering, and adds the
 * matching styling class to the wrapper.
 *
 * Stashes a [RowColumnMap] in [rowColumnMaps] so caret-mapping code can
 * translate between the DOM (markers-stripped) and the underlying model
 * line.
 *
 * @param rowDiv The enclosing row div — used as the column-map key.
 * @param editable The editable inline text (the line with any bullet
 *   prefix already removed). May be empty.
 */
private fun buildStyledTextRegion(rowDiv: HTMLElement, editable: String): HTMLElement {
    val wrapper = document.createElement("span") as HTMLElement
    wrapper.className = "notegrow-text"

    // Detect line-level prefix at the start of the editable text.
    val linePrefix = LineMarkdownPrefix.detect(editable, 0)
    val lineMarkerLen = if (linePrefix.style != null) linePrefix.markerEnd else 0
    val lineClass = when (linePrefix.style) {
        LineStyle.HEADING_1 -> "notegrow-md-h1"
        LineStyle.HEADING_2 -> "notegrow-md-h2"
        LineStyle.HEADING_3 -> "notegrow-md-h3"
        LineStyle.HEADING_4 -> "notegrow-md-h4"
        LineStyle.HEADING_5 -> "notegrow-md-h5"
        LineStyle.HEADING_6 -> "notegrow-md-h6"
        LineStyle.QUOTE -> "notegrow-md-quote"
        null -> null
    }
    if (lineClass != null) wrapper.className = "notegrow-text $lineClass"

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
    rowColumnMaps[rowDiv] = RowColumnMap(rowModelToDom, rowDomToModel, markerCols)

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
        empty.className = "notegrow-text-run"
        empty.appendChild(document.createElement("br"))
        wrapper.appendChild(empty)
    } else {
        for (run in tokenized.runs) {
            if (run.imageSrc != null) {
                wrapper.appendChild(createImageRunElement(run, baseRunClass = "notegrow-text-run"))
                continue
            }
            val span = document.createElement("span") as HTMLElement
            span.className = runClassName(run.styles, isLink = run.linkHref != null, isTag = run.isTag)
            if (run.linkHref != null) {
                span.setAttribute("data-href", run.linkHref!!)
            }
            span.textContent = run.text
            wrapper.appendChild(span)
        }
    }
    return wrapper
}

/**
 * Cheap row-level test for "this line contains a markdown image."
 * The painter adds a `notegrow-row-has-image` class when this fires
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
 * `notegrow-md-*` CSS classes the global stylesheet defines. Returns an
 * empty list when the run carries no formatting. Used by every place
 * that converts a [StyledRun] into a styled DOM node — the editor's
 * paint loop ([runClassName]) and the zoom-headline rendering in
 * `MainScreen.updateTitle`.
 *
 * Kept caret-tracking agnostic on purpose: the caller decides whether
 * to prepend a base class (the editor uses `notegrow-text-run` so its
 * column-map walks the right spans; the headline needs no base class).
 */
internal fun inlineRunCssClasses(
    styles: Set<InlineStyle>,
    isLink: Boolean = false,
    isTag: Boolean = false,
    isImage: Boolean = false,
): List<String> {
    if (styles.isEmpty() && !isLink && !isTag && !isImage) return emptyList()
    val out = ArrayList<String>(styles.size + 2)
    if (InlineStyle.BOLD in styles) out += "notegrow-md-bold"
    if (InlineStyle.ITALIC in styles) out += "notegrow-md-italic"
    if (InlineStyle.STRIKETHROUGH in styles) out += "notegrow-md-strike"
    if (InlineStyle.INLINE_CODE in styles) out += "notegrow-md-code"
    if (isLink) out += "notegrow-md-link"
    if (isTag) out += "notegrow-md-tag"
    if (isImage) out += "notegrow-md-image"
    return out
}

/**
 * Absolute path to the vault root, set once at app boot by `Main.kt`
 * from `DocumentRegistry.rootDirectory`. Used by [notegrowAssetUrl] to
 * turn a vault-relative image path into an absolute filesystem path that
 * the Electron `notegrow-asset:` protocol handler can resolve. Defaults
 * to empty until set — in that state image runs render as broken images,
 * which is acceptable since the boot wire-up runs before any paint.
 */
@Suppress("ObjectPropertyName")
internal var _notegrowVaultRoot: String = ""

/**
 * Install the vault root used by [notegrowAssetUrl]. Called once at
 * app boot from `Main.kt` so the renderer doesn't need to crawl the DI
 * graph for every image span.
 */
fun setNotegrowVaultRoot(rootDir: String) {
    _notegrowVaultRoot = rootDir
}

/**
 * Build a `notegrow-asset:` URL for a vault-root-relative path. Electron
 * registers the protocol in the main process so the renderer can load
 * vault assets without `webSecurity` blocking `file://` URLs.
 *
 * The URL path encodes the absolute filesystem path so the main-process
 * handler can pass it straight to `fs` without needing its own copy of
 * the vault-root configuration.
 */
internal fun notegrowAssetUrl(vaultRelPath: String): String {
    val rel = vaultRelPath.trimStart('/')
    val abs = if (_notegrowVaultRoot.isEmpty()) "/$rel" else "${_notegrowVaultRoot.trimEnd('/')}/$rel"
    // Use an explicit `local` placeholder host so Chromium's
    // standard-scheme URL parser puts the full abs path into the
    // pathname (`/<abs>`). Without the host, an empty-host URL
    // (`notegrow-asset:///<abs>`) gets parsed as host=`<first segment>`,
    // path=`/<rest>` — corrupting the leading directory.
    return "notegrow-asset://local" + js("encodeURI")(abs)
}

/**
 * Create the `<span>` that stands in for an image inline run. The span is
 * `contenteditable="false"` so caret clicks treat it as an atomic glyph;
 * it contains a single `<img>` whose width is constrained to the image
 * run's [StyledRun.imageWidthPx] when present. The span carries
 * `notegrow-text-run` + `notegrow-md-image` classes so the editor's
 * column-mapping walker still iterates past it (the inner `<img>` has
 * empty `textContent`, contributing 0 display columns just as the
 * tokenizer promised).
 *
 * @param run the image run produced by [InlineMarkdownTokenizer].
 * @param baseRunClass optional base class added before the image-specific
 *   classes — `notegrow-text-run` in the editor, `null` in the headline.
 */
internal fun createImageRunElement(run: StyledRun, baseRunClass: String?): HTMLElement {
    val src = run.imageSrc ?: error("createImageRunElement called on non-image run")
    val span = document.createElement("span") as HTMLElement
    val classes = inlineRunCssClasses(run.styles, isImage = true)
    val full = if (baseRunClass == null) classes else listOf(baseRunClass) + classes
    span.className = full.joinToString(" ")
    span.setAttribute("contenteditable", "false")
    span.setAttribute("data-img-src", src)
    run.imageWidthPx?.let { span.setAttribute("data-img-width", it.toString()) }
    run.imageAlt?.takeIf { it.isNotEmpty() }?.let { span.setAttribute("data-img-alt", it) }
    val img = document.createElement("img") as HTMLImageElement
    img.src = notegrowAssetUrl(src)
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
        broken.className = "notegrow-md-image-broken"
        broken.textContent = "Missing image: $src"
        span.appendChild(broken)
    })
    span.appendChild(img)
    // Resize handle pinned at the bottom-right corner. Visible only on
    // hover so it doesn't clutter the read view. The actual drag
    // behavior lives in `MainScreen.handleImageResizeMouseDown` via
    // event delegation on the `.notegrow-image-resize-handle` class.
    val handle = document.createElement("span") as HTMLElement
    handle.className = "notegrow-image-resize-handle"
    handle.setAttribute("contenteditable", "false")
    span.appendChild(handle)
    return span
}

/**
 * Build the CSS class string for a styled run from its [styles] set.
 * Always includes the base `notegrow-text-run` class so global
 * editable-region styles still apply. Inline-style classes come from
 * the shared [inlineRunCssClasses] helper.
 */
private fun runClassName(styles: Set<InlineStyle>, isLink: Boolean = false, isTag: Boolean = false): String {
    val extras = inlineRunCssClasses(styles, isLink, isTag)
    if (extras.isEmpty()) return "notegrow-text-run"
    return "notegrow-text-run " + extras.joinToString(" ")
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
 */
private fun buildBulletPrefix(
    absoluteRow: Int,
    onBulletMouseDown: ((absoluteRow: Int, ev: MouseEvent) -> Unit)? = null,
): HTMLElement {
    val prefix = document.createElement("span") as HTMLElement
    prefix.className = "notegrow-bullet-prefix"
    prefix.setAttribute("contenteditable", "false")
    prefix.style.apply {
        setProperty("user-select", "none")
        cursor = "pointer"
    }

    val glyph = document.createElement("span") as HTMLElement
    glyph.className = "notegrow-bullet"
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
 * Disclosure chevron rendered absolutely-positioned to the left of the
 * row's bullet glyph. Clicking toggles fold state via
 * [MainViewModel.toggleCollapse]. Marked `contenteditable="false"` so it
 * never participates in caret placement.
 *
 * Promoted-ref bullets get a separate file-icon adornment via
 * [buildPromotedRefIcon] rather than restyling the chevron itself, so the
 * chevron stays visually identical regardless of whether it leads into a
 * separate document or in-file children.
 */
private fun buildChevron(
    rowId: LineId,
    isCollapsed: Boolean,
    viewModel: MainViewModel,
): HTMLElement {
    val target = document.createElement("div") as HTMLElement
    target.className = "notegrow-chevron"
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
        color = "var(--t-text-tertiary, #7a7a7a)"
        setProperty("user-select", "none")
    }
    val rotation = if (isCollapsed) "rotate(-90deg)" else "none"
    target.innerHTML = "<svg viewBox=\"0 0 16 16\" width=\"10\" height=\"10\" stroke=\"currentColor\" " +
        "stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\" fill=\"none\" " +
        "style=\"transform: $rotation; transition: transform 120ms ease; pointer-events: none;\">" +
        "<polyline points=\"4,6 8,10 12,6\"></polyline></svg>"
    target.addEventListener("mousedown", { event ->
        val me = event as MouseEvent
        me.stopPropagation()
        me.preventDefault()
    })
    target.addEventListener("click", { event ->
        val me = event as MouseEvent
        me.stopPropagation()
        me.preventDefault()
        viewModel.toggleCollapse(rowId)
    })
    return target
}

/**
 * Small file-icon adornment shown to the left of the chevron when the row
 * is a promoted-subtree reference (i.e. expanding it loads a separate
 * Markdown file). Purely decorative — non-interactive and
 * `contenteditable="false"` so it doesn't capture clicks intended for the
 * chevron or row body. Absolute positioning of the `left` offset is the
 * caller's responsibility.
 */
private fun buildPromotedRefIcon(): HTMLElement {
    val icon = document.createElement("div") as HTMLElement
    icon.className = "notegrow-promoted-ref-icon"
    icon.title = "Linked file"
    icon.setAttribute("contenteditable", "false")
    icon.style.apply {
        setProperty("position", "absolute")
        top = "0"
        width = "22px"
        height = "100%"
        display = "flex"
        alignItems = "center"
        justifyContent = "center"
        color = "var(--t-text-tertiary, #7a7a7a)"
        setProperty("user-select", "none")
        setProperty("pointer-events", "none")
    }
    // Page-with-folded-corner glyph. Sized + weighted to match
    // `VaultFooter.buildVaultFileIcon` so promoted-ref rows and tree-row
    // file rows read with the same prominence.
    icon.innerHTML = "<svg viewBox=\"0 0 24 24\" width=\"11\" height=\"11\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\" style=\"pointer-events: none;\">" +
        "<path d=\"M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z\"/>" +
        "<polyline points=\"14 3 14 9 20 9\"/></svg>"
    return icon
}

/**
 * Injects the stylesheet that drives bullet/chevron hover, scrollbars,
 * and the restructuring banner. Runs once — the guard on the `id` makes
 * repeat calls cheap.
 */
fun ensureStyles() {
    val existing = document.getElementById("notegrow-cursor-style")
    if (existing != null) return
    val style = document.createElement("style") as HTMLElement
    style.id = "notegrow-cursor-style"
    style.textContent = """
        .notegrow-scroll::-webkit-scrollbar { width: 12px; }
        .notegrow-scroll::-webkit-scrollbar-track {
            background: var(--t-terminal-bg, #1e1e1e);
        }
        .notegrow-scroll::-webkit-scrollbar-thumb {
            background: var(--t-border-strong, #4a4a4a);
            border-radius: 6px;
            border: 2px solid var(--t-terminal-bg, #1e1e1e);
        }
        .notegrow-scroll::-webkit-scrollbar-thumb:hover {
            background: var(--t-text-tertiary, #5e5e5e);
        }
        .notegrow-editor ::selection {
            background: var(--t-terminal-selection, rgba(90, 176, 255, 0.30));
        }
        .notegrow-bullet-prefix {
            display: inline;
            cursor: grab;
        }
        /* When the row contains an inline image, the row is much
           taller than a text-only line. The default baseline
           alignment of the bullet ends up centered in the middle of
           the image; switching the row's children to top alignment
           puts the bullet next to the first line of text instead.
           The bullet-prefix needs an explicit `inline-block` for
           vertical-align to apply, and a small top inset matches
           the visual line position of the surrounding text. */
        .notegrow-row-has-image > .notegrow-bullet-prefix {
            display: inline-block;
            vertical-align: top;
            line-height: var(--notegrow-line-height, normal);
        }
        .notegrow-row-has-image > .notegrow-text {
            vertical-align: top;
        }
        /* Chevron normally fills the row height (`height: 100%`) and
           centers its glyph via flex — fine for text rows, but on an
           image row that centers the chevron in the middle of the
           image. Constrain the chevron's box to the first line so
           the glyph sits next to the bullet. */
        .notegrow-row-has-image > .notegrow-chevron {
            height: 1.5em;
        }
        .notegrow-bullet-prefix:active {
            cursor: grabbing;
        }
        .notegrow-drop-indicator {
            position: fixed;
            height: 2px;
            background: var(--t-accent, #5ab0ff);
            box-shadow: 0 0 0 2px rgba(90, 176, 255, 0.18);
            pointer-events: none;
            z-index: 2000;
            border-radius: 2px;
        }
        .notegrow-bullet {
            display: inline-block;
            width: 0.4em;
            height: 0.4em;
            margin: 0 0.25em;
            background: currentColor;
            color: var(--t-text-primary, #e6e6e6);
            border-radius: 50%;
            vertical-align: 0.12em;
            transform-origin: center;
            transition: transform 120ms ease-out, box-shadow 120ms ease-out;
        }
        .notegrow-bullet-prefix:hover .notegrow-bullet {
            transform: scale(1.15);
            box-shadow: 0 0 0 5px var(--t-border-strong, rgba(255, 255, 255, 0.10));
        }
        .notegrow-bullet-prefix:active .notegrow-bullet {
            transform: scale(0.92);
            box-shadow: 0 0 0 4px var(--t-border-strong, rgba(255, 255, 255, 0.14));
        }
        .notegrow-chevron {
            opacity: 0.85;
            border-radius: 4px;
            transition: background 120ms ease-out, color 120ms ease-out, opacity 120ms ease-out;
        }
        .notegrow-chevron:hover {
            color: var(--t-text-primary, #e6e6e6);
            opacity: 1;
            background: var(--t-border-strong, rgba(255, 255, 255, 0.10));
        }
        .notegrow-chevron:active {
            background: var(--t-border-strong, rgba(255, 255, 255, 0.14));
        }
        @keyframes notegrow-spinner-rotate { to { transform: rotate(360deg); } }
        .notegrow-restructuring {
            position: fixed;
            bottom: 14px;
            right: 14px;
            display: none;
            align-items: center;
            gap: 8px;
            padding: 6px 12px;
            background: var(--t-surface-overlay, rgba(42, 42, 42, 0.95));
            color: var(--t-text-secondary, #cfcfcf);
            border: 1px solid var(--t-border-strong, #4a4a4a);
            border-radius: 999px;
            font-size: 12px;
            line-height: 1;
            z-index: 1000;
            pointer-events: none;
            box-shadow: 0 2px 6px rgba(0, 0, 0, 0.35);
        }
        .notegrow-restructuring-spinner {
            width: 12px;
            height: 12px;
            border: 2px solid var(--t-border-strong, #4a4a4a);
            border-top-color: var(--t-accent, #5ab0ff);
            border-radius: 50%;
            animation: notegrow-spinner-rotate 0.8s linear infinite;
        }
        /* Filesystem-tree footer: rendered in a sibling block under the
           contenteditable editor host so it scrolls with the document but
           can never receive caret/selection. Styled to look like another
           outline. */
        .notegrow-vault-footer {
            border-top: 1px solid var(--t-border-strong, #4a4a4a);
            margin-top: 32px;
        }
        /* When the active file is a directory anchor with nothing to
           list, `paintVaultFooter` returns without appending any
           children. The container element itself stays mounted (it's a
           permanent sibling of the editor host so the paint loop never
           wipes it), so we strip the divider, margin, and padding here
           to make the empty footer take zero vertical space. The
           `!important`s override `buildVaultFooterElement`'s inline
           padding, which would otherwise win the cascade. */
        .notegrow-vault-footer:empty {
            border-top: none !important;
            margin-top: 0 !important;
            padding: 0 !important;
        }
        .notegrow-vault-header {
            display: flex;
            align-items: center;
            gap: 6px;
            padding-top: 8px;
            padding-bottom: 6px;
            opacity: 0.65;
            font-size: 13px;
            font-weight: 600;
            text-transform: uppercase;
            letter-spacing: 0.06em;
            cursor: pointer;
            user-select: none;
        }
        .notegrow-vault-header:hover {
            opacity: 0.85;
        }
        .notegrow-vault-header-chevron {
            position: relative;
            width: 16px;
            height: 16px;
            display: inline-flex;
            align-items: center;
            justify-content: center;
            color: var(--t-text-tertiary, #7a7a7a);
        }
        .notegrow-vault-row {
            position: relative;
            cursor: pointer;
            user-select: none;
        }
        .notegrow-vault-text {
            color: var(--t-text-primary, #e6e6e6);
        }
        .notegrow-vault-folder {
            opacity: 0.85;
        }
        .notegrow-vault-loading {
            opacity: 0.5;
            font-style: italic;
            color: var(--t-text-tertiary, #7a7a7a);
        }
        /* WYSIWYG markdown styles: the marker characters (**, *, <u>, ~~,
           `) are not in the DOM at all, so styling here only affects the
           rendered text. The underlying model line still contains the
           markdown so files round-trip cleanly through other tools. */
        .notegrow-md-bold { font-weight: 700; }
        .notegrow-md-italic { font-style: italic; }
        .notegrow-md-strike { text-decoration: line-through; }
        .notegrow-md-code {
            font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
            font-size: 0.95em;
            background: var(--t-border-strong, rgba(255, 255, 255, 0.10));
            padding: 0 4px;
            border-radius: 3px;
        }
        .notegrow-md-link {
            color: var(--t-accent, #5ab0ff);
            text-decoration: underline;
            text-underline-offset: 2px;
            cursor: pointer;
        }
        /* Hashtag (`#name`) — drawn with a rectangle in the accent color.
           The whole `#name` is real text in the model so the border just
           wraps the run span; padding gives it breathing room without
           shifting surrounding glyph positions noticeably. */
        .notegrow-md-tag {
            color: var(--t-accent, #5ab0ff);
            border: 1px solid var(--t-accent, #5ab0ff);
            border-radius: 4px;
            padding: 0 4px;
        }
        /* Inline image — replaces the `![alt](src)` syntax span with an
           atomic non-editable element containing the rendered image. The
           outer span participates in inline layout; the `<img>` inside is
           constrained so a giant screenshot doesn't blow up the row. */
        .notegrow-md-image {
            display: inline-block;
            position: relative;
            vertical-align: middle;
            user-select: none;
            cursor: pointer;
            max-width: 100%;
        }
        .notegrow-md-image img {
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
        .notegrow-image-resize-handle {
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
        .notegrow-image-resize-handle::after {
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
        .notegrow-md-image:hover .notegrow-image-resize-handle,
        .notegrow-md-image.is-resizing .notegrow-image-resize-handle {
            opacity: 1;
        }
        .notegrow-md-image.is-resizing { cursor: nwse-resize; }
        .notegrow-md-image.is-resizing img { pointer-events: none; }
        /* Broken-image fallback. Swapped in by OutlinePaintLoop's
           img `error` handler when the asset URL fails to resolve. */
        .notegrow-md-image-broken {
            display: inline-block;
            padding: 4px 8px;
            border: 1px dashed var(--t-border-strong, rgba(255, 255, 255, 0.20));
            border-radius: 4px;
            color: var(--t-text-secondary, rgba(255, 255, 255, 0.55));
            font-size: 12px;
            font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
        }
        .notegrow-text.notegrow-md-h1 {
            font-size: 1.6em;
            font-weight: 700;
            line-height: 1.25;
        }
        .notegrow-text.notegrow-md-h2 {
            font-size: 1.35em;
            font-weight: 700;
            line-height: 1.25;
        }
        .notegrow-text.notegrow-md-h3 {
            font-size: 1.15em;
            font-weight: 600;
            line-height: 1.3;
        }
        .notegrow-text.notegrow-md-h4 {
            font-size: 1.05em;
            font-weight: 600;
            line-height: 1.3;
        }
        .notegrow-text.notegrow-md-h5 {
            font-size: 1.0em;
            font-weight: 600;
            line-height: 1.35;
        }
        .notegrow-text.notegrow-md-h6 {
            font-size: 0.95em;
            font-weight: 600;
            line-height: 1.4;
            color: var(--t-text-secondary, #cfcfcf);
        }
        .notegrow-text.notegrow-md-quote {
            display: inline-block;
            border-left: 3px solid var(--t-border-strong, #4a4a4a);
            padding-left: 8px;
            color: var(--t-text-secondary, #cfcfcf);
            font-style: italic;
        }
        /* Zoom headline (the big title above the editor). Base size
           is set here rather than inline on the element so the
           per-style overrides below can supersede it. */
        .notegrow-title {
            font-size: 32px;
            font-weight: 600;
            line-height: 1.2;
        }
        .notegrow-title.notegrow-title-h1 { font-size: 36px; font-weight: 700; }
        .notegrow-title.notegrow-title-h2 { font-size: 30px; font-weight: 700; }
        .notegrow-title.notegrow-title-h3 { font-size: 26px; font-weight: 600; }
        .notegrow-title.notegrow-title-h4 { font-size: 22px; font-weight: 600; }
        .notegrow-title.notegrow-title-h5 { font-size: 20px; font-weight: 600; }
        .notegrow-title.notegrow-title-h6 {
            font-size: 18px;
            font-weight: 600;
            color: var(--t-text-secondary, #cfcfcf);
        }
        .notegrow-title.notegrow-title-quote {
            border-left: 3px solid var(--t-border-strong, #4a4a4a);
            padding-left: 12px;
            font-style: italic;
            color: var(--t-text-secondary, #cfcfcf);
        }
        /* Style dropdown menu (anchored to the Style toolbar button). */
        .notegrow-style-menu {
            position: fixed;
            z-index: 2000;
            min-width: 220px;
            background: var(--t-surface-overlay, #2a2a2a);
            border: 1px solid var(--t-border-strong, #4a4a4a);
            border-radius: 6px;
            box-shadow: 0 6px 20px rgba(0, 0, 0, 0.4);
            padding: 4px;
            font-size: 13px;
            color: var(--t-text-primary, #e6e6e6);
        }
        .notegrow-style-item {
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
        .notegrow-style-item:hover {
            background: var(--t-border-strong, rgba(255, 255, 255, 0.10));
        }
        .notegrow-style-item.is-active {
            background: var(--t-accent-soft, rgba(90, 176, 255, 0.18));
        }
        .notegrow-style-icon {
            width: 16px;
            height: 16px;
            flex: 0 0 16px;
            display: inline-flex;
            align-items: center;
            justify-content: center;
            color: var(--t-text-secondary, #cfcfcf);
        }
        .notegrow-style-label { flex: 1 1 auto; }
        .notegrow-style-check {
            margin-left: auto;
            opacity: 0.9;
            color: var(--t-accent, #5ab0ff);
        }
        .notegrow-style-divider {
            border: 0;
            border-top: 1px solid var(--t-border-strong, #3a3a3a);
            margin: 4px 0;
        }
    """.trimIndent()
    document.head?.appendChild(style)
}
