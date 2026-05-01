/*
 * OutlinePaintLoop.kt (jsMain)
 * ----------------------------
 * The repaint pipeline for the editor body. Top-level functions accept the
 * DOM nodes, the current backing state, and a few style/measurement values;
 * they own no state themselves. The collector that subscribes to the
 * view-model's flow lives in `MainScreen` and forwards each emission to
 * [paint].
 *
 * The breadcrumb header that used to live here moved into the toolkit's
 * pane chrome title (RTL-clipped) — the editor now mounts directly into the
 * pane content so bullets start at the very top of the pane.
 *
 * Style installation, character-width measurement, and wrap-width math
 * also live here because they're concerns of the painter rather than the
 * input handlers.
 */

package se.soderbjorn.notegrow.main

import kotlinx.browser.document
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent

/**
 * Extra horizontal indent, in pixels, added per nesting level on top of
 * the natural [TAB_SIZE]-character indent already present in the line
 * text. A bullet at indent column `c` shifts right by
 * `(c / TAB_SIZE) * EXTRA_INDENT_PX_PER_LEVEL` pixels. Both the paint
 * loop and hit-test apply this offset; keep them in sync.
 */
internal const val EXTRA_INDENT_PX_PER_LEVEL: Int = 14

/**
 * Returns the per-row left offset in pixels for a line whose bullet
 * column (after any zoom-relative indent stripping) is [bulletCol]. Used
 * by the painter to push deeper bullets further right and by hit-testing
 * to undo the same shift before mapping a click to a logical column.
 */
internal fun lineLeftOffsetPxFor(bulletCol: Int): Int {
    if (bulletCol <= 0) return 0
    return (bulletCol / 2) * EXTRA_INDENT_PX_PER_LEVEL
}

/**
 * Renders the "Loading…" placeholder into [editor]. Used on cold start
 * until the document is read from disk.
 */
fun paintLoading(editor: HTMLElement) {
    editor.innerHTML = ""
    val loading = document.createElement("div") as HTMLElement
    loading.textContent = "Loading…"
    loading.style.opacity = "0.6"
    editor.appendChild(loading)
}

/**
 * Repaints the editor for the current [state]. Renders only the rows
 * inside the current zoom range (or the whole document when at root).
 * Also scrolls the caret into view at the end of the paint.
 */
fun paint(
    editor: HTMLElement,
    state: DocumentViewBackingViewModel.State,
    viewModel: MainViewModel,
    style: EditorStyle,
    charWidthPx: Double,
    wrapWidth: Int,
) {
    // Wiping innerHTML drops scrollHeight to 0, which forces the browser to clamp
    // scrollTop to 0. Capture it now and restore at the end so that repaints not
    // initiated by the user (e.g. clicking a chevron to collapse a bullet) don't
    // yank the viewport back to the document start. scrollCursorIntoView still
    // runs after the restore and pulls the caret into view when needed.
    val savedScrollTop = editor.scrollTop
    editor.innerHTML = ""
    if (!state.isLoaded) {
        paintLoading(editor)
        return
    }

    val width = wrapWidth.coerceAtLeast(1)
    val selection = DocumentViewBackingViewModel.selectionOf(state)
    val zoom = viewModel.zoomInfo(state)
    val startRow = zoom?.startRow ?: 0
    val endRowInclusive = zoom?.endRowInclusive ?: state.lines.lastIndex
    if (endRowInclusive < startRow) return
    val docState = state.documentState ?: return
    // When zoomed, indents render relative to the zoom target so the
    // closest descendants paint flush-left rather than indented one level
    // under the (no longer visible) zoom target. We strip `zoomIndent +
    // TAB_SIZE` characters: every descendant in the zoom subtree is a
    // bullet with indent strictly greater than `zoomIndent` (see
    // DocumentLayout.subtreeEnd), and TAB_SIZE-aligned descendants always
    // have at least `zoomIndent + TAB_SIZE` leading characters.
    val viewOriginCol = zoom?.let { it.zoomIndent + DocumentViewBackingViewModel.TAB_SIZE } ?: 0
    val viewSelection = if (selection != null && viewOriginCol > 0) {
        DocumentViewBackingViewModel.Selection(
            startRow = selection.startRow,
            startCol = (selection.startCol - viewOriginCol).coerceAtLeast(0),
            endRow = selection.endRow,
            endCol = (selection.endCol - viewOriginCol).coerceAtLeast(0),
        )
    } else selection

    val visibleRows = DocumentLayout.visibleRowsOf(
        docState.lines, docState.lineIds, state.collapsedIds, startRow, endRowInclusive
    )
    for (row in visibleRows) {
        val rawLine = state.lines[row]
        val line = if (viewOriginCol > 0 && rawLine.length >= viewOriginCol)
            rawLine.substring(viewOriginCol) else rawLine
        val chunks = DocumentLayout.wrapLine(line, width).toMutableList()
        val cursorInfo = if (row == state.cursorRow) {
            val cursorColRel = (state.cursorCol - viewOriginCol).coerceAtLeast(0)
            DocumentLayout.cursorVisualPosition(line, cursorColRel, width).also {
                if (it.needsTrailingEmptyChunk) chunks.add("")
            }
        } else null

        val bulletCol = DocumentLayout.bulletAsteriskColumn(line)
        val rowId = if (row in docState.lineIds.indices) docState.lineIds[row] else null
        // A bullet is collapsible if it has children in [lines] OR is a
        // folded promoted file boundary (its children aren't loaded yet, so
        // they don't appear in [lines] but may exist on disk). An *expanded*
        // promoted ref falls back to the same hasChildren check — that way
        // an empty/broken ref file doesn't paint a useless chevron.
        val absoluteIndent = DocumentLayout.bulletAsteriskColumn(rawLine)
        val isFoldedPromotedRef = rowId != null &&
            documentBackingViewModelRefCheck(viewModel, rowId) &&
            rowId !in docState.expandedRefIds
        val isCollapsibleParent = bulletCol >= 0 && rowId != null && (
            DocumentLayout.hasChildren(docState.lines, row, absoluteIndent) ||
            isFoldedPromotedRef
        )
        val isCollapsedNow = rowId != null && rowId in state.collapsedIds
        val rowLeftOffsetPx = lineLeftOffsetPxFor(bulletCol)

        chunks.forEachIndexed { chunkIndex, chunk ->
            val chunkDiv = document.createElement("div") as HTMLElement
            chunkDiv.style.apply {
                height = "${style.lineHeightPx}px"
                setProperty("position", "relative")
            }

            if (viewSelection != null) {
                val highlight = DocumentLayout.chunkSelectionHighlight(
                    viewSelection, row, line, chunkIndex, chunks.size, chunk.length, width
                )
                if (highlight != null) {
                    appendSelectionHighlight(
                        chunkDiv, highlight, charWidthPx, style.lineHeightPx, rowLeftOffsetPx
                    )
                }
            }

            val displayChunk = if (chunkIndex == 0 && bulletCol >= 0) {
                chunk.substring(0, bulletCol) + "•" + chunk.substring(bulletCol + 1)
            } else {
                chunk
            }
            val textSpan = document.createElement("span") as HTMLElement
            textSpan.style.apply {
                setProperty("position", "relative")
                setProperty("z-index", "1")
                if (rowLeftOffsetPx > 0) left = "${rowLeftOffsetPx}px"
            }
            textSpan.textContent = displayChunk
            chunkDiv.appendChild(textSpan)

            if (chunkIndex == 0 && bulletCol >= 0) {
                appendBulletClickTarget(
                    chunkDiv, bulletCol, row, charWidthPx, style.lineHeightPx, viewModel, rowLeftOffsetPx,
                ) { editor.focus() }
            }
            if (chunkIndex == 0 && isCollapsibleParent && rowId != null) {
                appendChevron(
                    chunkDiv = chunkDiv,
                    bulletCol = bulletCol,
                    rowId = rowId,
                    isCollapsed = isCollapsedNow,
                    charWidthPx = charWidthPx,
                    lineHeightPx = style.lineHeightPx,
                    viewModel = viewModel,
                    leftOffsetPx = rowLeftOffsetPx,
                ) { editor.focus() }
            }

            if (cursorInfo != null && cursorInfo.chunkIndex == chunkIndex) {
                appendCursor(
                    chunkDiv, cursorInfo.colInChunk, charWidthPx, style.lineHeightPx, rowLeftOffsetPx
                )
            }
            editor.appendChild(chunkDiv)
        }
    }
    editor.scrollTop = savedScrollTop
    scrollCursorIntoView(editor, state, style, width, startRow, endRowInclusive, viewOriginCol)
}

/**
 * Lightweight wrapper so the paint loop can answer "is this a promoted
 * reference row?" via the view-model facade without leaking the internal
 * registry. Avoids creating a public accessor on `MainViewModel` just for
 * paint-time predicates.
 */
private fun documentBackingViewModelRefCheck(
    viewModel: MainViewModel,
    rowId: LineId,
): Boolean = viewModel.isPromotedRef(rowId)

/**
 * Overlays a transparent clickable element on top of a bullet marker so
 * clicking `•` zooms into that bullet without also moving the caret.
 */
private fun appendBulletClickTarget(
    chunkDiv: HTMLElement,
    bulletCol: Int,
    absoluteRow: Int,
    charWidthPx: Double,
    lineHeightPx: Int,
    viewModel: MainViewModel,
    leftOffsetPx: Int,
    onAfterZoom: () -> Unit,
) {
    // Comfortable click target — a circular zone centred on the bullet
    // column's optical centre. Sized 24×24 so it's easy to hit on touch
    // without overlapping the next character; the inner glyph paints a
    // larger visible bullet on top of the text-layer `•` so the user
    // always sees a chunky dot, not the tiny default glyph. Hovering
    // grows the zone outward (CSS `::after` halo + inner-glyph scale).
    val hitSize = 24
    val bulletCenterX = (bulletCol + 0.5) * charWidthPx + leftOffsetPx
    val bulletCenterY = lineHeightPx / 2.0
    val target = document.createElement("div") as HTMLElement
    target.className = "notegrow-bullet"
    target.title = "Zoom into bullet"
    target.style.apply {
        setProperty("position", "absolute")
        left = "${bulletCenterX - hitSize / 2.0}px"
        top = "${bulletCenterY - hitSize / 2.0}px"
        width = "${hitSize}px"
        height = "${hitSize}px"
        setProperty("z-index", "3")
        cursor = "pointer"
        // Flex layout centers the inner glyph dot within the hit-zone.
        display = "flex"
        alignItems = "center"
        justifyContent = "center"
        // Round the hit-zone visually too — the hover halo expands from
        // a circle, growing outward, mirroring the glyph's shape.
        borderRadius = "50%"
    }
    // Inner painted bullet — sits ON TOP of the text-layer `•` so the
    // user sees a chunky dot at all times. Coloured from the text token
    // so it inherits the active theme.
    val glyph = document.createElement("span") as HTMLElement
    glyph.className = "notegrow-bullet-glyph"
    glyph.style.apply {
        display = "block"
        width = "10px"
        height = "10px"
        borderRadius = "50%"
        backgroundColor = "var(--t-text-primary, #e6e6e6)"
        setProperty("pointer-events", "none")
        setProperty("transition", "transform 120ms ease-out")
    }
    target.appendChild(glyph)

    target.addEventListener("mousedown", { event ->
        val me = event as MouseEvent
        me.stopPropagation()
        me.preventDefault()
    })
    target.addEventListener("click", { event ->
        val me = event as MouseEvent
        me.stopPropagation()
        me.preventDefault()
        viewModel.zoomInto(absoluteRow)
        onAfterZoom()
    })
    chunkDiv.appendChild(target)
}

/**
 * Renders a small disclosure chevron just to the left of a bullet glyph.
 * Defaults to a downward-pointing polyline; rotates `-90deg` when the
 * bullet is currently collapsed. Click toggles the bullet's fold state
 * via [MainViewModel.toggleCollapse]. The chevron's own click handler
 * stops propagation so the click never reaches the editor's caret-
 * positioning mousedown.
 */
private fun appendChevron(
    chunkDiv: HTMLElement,
    bulletCol: Int,
    rowId: LineId,
    isCollapsed: Boolean,
    charWidthPx: Double,
    lineHeightPx: Int,
    viewModel: MainViewModel,
    leftOffsetPx: Int,
    onAfterToggle: () -> Unit,
) {
    // Generous touch target so it's easy to hit on touchpads/small screens.
    val hitSize = 24
    // Sit the chevron well to the left of the bullet so there's a clear gap
    // between the disclosure control and the bullet glyph. The 22px gap also
    // prevents accidental zooms when the user means to toggle.
    val chevronGapPx = 22.0
    val centerX = (bulletCol + 0.5) * charWidthPx + leftOffsetPx - chevronGapPx
    val centerY = lineHeightPx / 2.0
    val target = document.createElement("div") as HTMLElement
    target.className = "notegrow-chevron"
    target.title = if (isCollapsed) "Expand" else "Collapse"
    target.style.apply {
        setProperty("position", "absolute")
        left = "${centerX - hitSize / 2.0}px"
        top = "${centerY - hitSize / 2.0}px"
        width = "${hitSize}px"
        height = "${hitSize}px"
        // Above the bullet hit-zone so a click in the small overlap goes to
        // the chevron, not to zoom.
        setProperty("z-index", "4")
        cursor = "pointer"
        display = "flex"
        alignItems = "center"
        justifyContent = "center"
        color = "var(--t-text-tertiary, #7a7a7a)"
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
        onAfterToggle()
    })
    chunkDiv.appendChild(target)
}

/**
 * Paints a translucent rectangle representing the portion of the user's
 * selection that falls inside one rendered chunk.
 */
private fun appendSelectionHighlight(
    chunkDiv: HTMLElement,
    highlight: DocumentLayout.ChunkHighlight,
    charWidthPx: Double,
    lineHeightPx: Int,
    leftOffsetPx: Int,
) {
    val div = document.createElement("div") as HTMLElement
    div.style.apply {
        setProperty("position", "absolute")
        left = "${highlight.leftChars * charWidthPx + leftOffsetPx}px"
        top = "0"
        width = "${highlight.widthChars * charWidthPx}px"
        height = "${lineHeightPx}px"
        backgroundColor = "var(--t-terminal-selection, rgba(90, 176, 255, 0.3))"
        setProperty("pointer-events", "none")
        setProperty("z-index", "0")
    }
    chunkDiv.appendChild(div)
}

/**
 * Appends the blinking caret span at column [col] inside a chunk.
 */
private fun appendCursor(
    container: HTMLElement,
    col: Int,
    charWidthPx: Double,
    lineHeightPx: Int,
    leftOffsetPx: Int,
) {
    val cursor = document.createElement("span") as HTMLElement
    cursor.className = "notegrow-cursor"
    cursor.style.apply {
        setProperty("position", "absolute")
        left = "${col * charWidthPx + leftOffsetPx}px"
        top = "0"
        width = "2px"
        height = "${lineHeightPx}px"
        backgroundColor = "var(--t-terminal-cursor, #5ab0ff)"
        setProperty("pointer-events", "none")
        setProperty("z-index", "2")
    }
    container.appendChild(cursor)
}

/**
 * Injects the stylesheet that drives caret blinking, scrollbars, and
 * bullet-hover styling. Runs once — the guard on the `id` makes repeat
 * calls cheap.
 */
fun ensureStyles() {
    val existing = document.getElementById("notegrow-cursor-style")
    if (existing != null) return
    val style = document.createElement("style") as HTMLElement
    style.id = "notegrow-cursor-style"
    style.textContent = """
        @keyframes notegrow-cursor-blink {
            0%, 49% { opacity: 1; }
            50%, 100% { opacity: 0; }
        }
        .notegrow-cursor {
            animation: notegrow-cursor-blink 1s steps(1, end) infinite;
        }
        .notegrow-editor::-webkit-scrollbar {
            width: 12px;
        }
        .notegrow-editor::-webkit-scrollbar-track {
            background: var(--t-terminal-bg, #1e1e1e);
        }
        .notegrow-editor::-webkit-scrollbar-thumb {
            background: var(--t-border-strong, #4a4a4a);
            border-radius: 6px;
            border: 2px solid var(--t-terminal-bg, #1e1e1e);
        }
        .notegrow-editor::-webkit-scrollbar-thumb:hover {
            background: var(--t-text-tertiary, #5e5e5e);
        }
        /* Bullet hit-zone: circular by virtue of `border-radius: 50%`
           inline; hover paints a translucent disc that expands outward
           from the glyph (radial gradient → soft falloff at the edge).
           The inner painted glyph also scales up slightly for tactile
           feedback. */
        .notegrow-bullet:hover {
            background: radial-gradient(
                circle at center,
                var(--t-terminal-selection, rgba(90, 176, 255, 0.30)) 0%,
                var(--t-terminal-selection, rgba(90, 176, 255, 0.15)) 60%,
                transparent 100%
            );
        }
        .notegrow-bullet:hover .notegrow-bullet-glyph {
            transform: scale(1.15);
        }
        .notegrow-bullet:active .notegrow-bullet-glyph {
            transform: scale(0.92);
        }
        /* Disclosure chevron: dim by default, brightens on hover. The SVG
           rotates to indicate state via inline style on the svg child. */
        .notegrow-chevron {
            opacity: 0.85;
        }
        .notegrow-chevron:hover {
            color: var(--t-text-primary, #e6e6e6);
            opacity: 1;
        }
        .notegrow-header:hover {
            background: var(--t-surface-overlay, #2a2a2a);
        }
        @keyframes notegrow-spinner-rotate {
            to { transform: rotate(360deg); }
        }
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
    """.trimIndent()
    document.head?.appendChild(style)
}

/**
 * Measures the width of a single `M` at the editor's font, so cursor
 * and selection overlays can position themselves in character-unit
 * multiples of that width.
 */
fun measureCharWidth(style: EditorStyle): Double {
    val canvas = document.createElement("canvas") as HTMLCanvasElement
    val ctx = canvas.getContext("2d").asDynamic()
    ctx.font = "${style.fontSize}px ${style.fontFamily}"
    return (ctx.measureText("M").width as Number).toDouble()
}

/**
 * Computes the wrap width (in characters) from the editor's pixel width
 * and the measured character width.
 */
fun wrapWidthInChars(editor: HTMLElement, charWidth: Double): Int {
    val usableWidth = (editor.clientWidth - 24).coerceAtLeast(charWidth.toInt())
    return (usableWidth / charWidth).toInt().coerceAtLeast(1)
}
