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
import org.w3c.dom.events.MouseEvent

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
    state: DocumentViewBackingViewModel.State,
    viewModel: MainViewModel,
    style: EditorStyle,
) {
    editor.innerHTML = ""
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
    val viewOriginCol = zoom?.let { it.zoomIndent + DocumentViewBackingViewModel.TAB_SIZE } ?: 0

    val visibleRows = DocumentLayout.visibleRowsOf(
        docState.lines, docState.lineIds, state.collapsedIds, startRow, endRowInclusive
    )
    for (row in visibleRows) {
        val rawLine = state.lines[row]
        val line = if (viewOriginCol > 0 && rawLine.length >= viewOriginCol)
            rawLine.substring(viewOriginCol) else rawLine
        editor.appendChild(buildRowElement(row, line, viewOriginCol, state, docState, viewModel, style))
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
    state: DocumentViewBackingViewModel.State,
    docState: DocumentBackingViewModel.State,
    viewModel: MainViewModel,
    style: EditorStyle,
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

    if (bulletCol >= 0) {
        // Visually indent the row by its bullet depth via padding-left, so the
        // bullet glyph itself sits at a depth-appropriate offset without
        // requiring monospace alignment.
        val depth = bulletCol / DocumentViewBackingViewModel.TAB_SIZE
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
                rowId !in docState.expandedRefIds
            val isCollapsibleParent =
                DocumentLayout.hasChildren(docState.lines, absoluteRow, absoluteIndentInRaw) ||
                    isFoldedPromotedRef
            val isCollapsedNow = rowId in state.collapsedIds
            if (isCollapsibleParent) {
                val isRef = viewModel.isPromotedRef(rowId)
                val chevron = buildChevron(rowId, isCollapsedNow, isRef, viewModel)
                // Position the chevron just to the left of THIS row's bullet
                // glyph, not the editor's left margin. The row's bullet sits
                // at `padding-left = depth * indentStepPx` from the row's
                // box; the chevron's 22px slot lands immediately before it.
                chevron.style.left = "${depth * style.indentStepPx - 22}px"
                rowDiv.appendChild(chevron)
            }
        }

        rowDiv.appendChild(buildBulletPrefix(absoluteRow, viewModel))
        rowDiv.appendChild(buildTextSpan(line.substring(bulletCol + 2)))
    } else {
        // Non-bullet line: editable text starts at column 0 of the raw line,
        // unless we stripped a zoom indent — in that case the displayed text
        // begins at `viewOriginCol` in raw model columns.
        rowDiv.setAttribute("data-prefix-len", viewOriginCol.toString())
        rowDiv.appendChild(buildTextSpan(line))
    }

    return rowDiv
}

/**
 * Editable text region of one row. Always exactly one child of its parent
 * row (so caret offsets within it map cleanly to model columns via
 * `data-prefix-len + offset`). Empty rows still get an empty span so the
 * caret has a stable target.
 */
private fun buildTextSpan(text: String): HTMLElement {
    val span = document.createElement("span") as HTMLElement
    span.className = "notegrow-text"
    span.textContent = text
    return span
}

/**
 * Non-editable bullet glyph + trailing space. Sized as a single inline
 * unit so wrap behaviour treats it as the start of the line. Click on
 * this element zooms into the bullet's subtree.
 */
private fun buildBulletPrefix(
    absoluteRow: Int,
    viewModel: MainViewModel,
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

    val zoomHandler: (org.w3c.dom.events.Event) -> Unit = { event ->
        val me = event as MouseEvent
        me.stopPropagation()
        me.preventDefault()
        viewModel.zoomInto(absoluteRow)
    }
    prefix.addEventListener("mousedown", { event ->
        val me = event as MouseEvent
        me.stopPropagation()
        me.preventDefault()
    })
    prefix.addEventListener("click", zoomHandler)
    return prefix
}

/**
 * Disclosure chevron rendered absolutely-positioned to the left of the
 * row's bullet glyph. Clicking toggles fold state via
 * [MainViewModel.toggleCollapse]. Marked `contenteditable="false"` so it
 * never participates in caret placement.
 *
 * When [isPromotedRef] is true the chevron is drawn with a heavier stroke
 * so the user can tell at a glance that expanding it leads into a
 * separate document, not just child bullets within the current file.
 */
private fun buildChevron(
    rowId: LineId,
    isCollapsed: Boolean,
    isPromotedRef: Boolean,
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
    val strokeWidth = if (isPromotedRef) "4.5" else "1.6"
    val size = if (isPromotedRef) "12" else "10"
    target.innerHTML = "<svg viewBox=\"0 0 16 16\" width=\"$size\" height=\"$size\" stroke=\"currentColor\" " +
        "stroke-width=\"$strokeWidth\" stroke-linecap=\"round\" stroke-linejoin=\"round\" fill=\"none\" " +
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
        .notegrow-editor::-webkit-scrollbar { width: 12px; }
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
        .notegrow-editor ::selection {
            background: var(--t-terminal-selection, rgba(90, 176, 255, 0.30));
        }
        .notegrow-bullet-prefix {
            display: inline;
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
        /* Notegrow-only: bump the navigation (left) sidebar rows so pane
           labels read at a comfortable size. Scoped via the toolkit's
           left-sidebar wrapper class so this stylesheet (loaded only by
           the notegrow bundle) does not affect any other toolkit-based
           app such as termtastic, which has its own bundle. */
        .dt-app-frame-sidebar-left .dt-sidebar-row {
            font-size: 14px;
        }
        .dt-app-frame-sidebar-left .dt-sidebar-section-header {
            font-size: 13px;
        }
    """.trimIndent()
    document.head?.appendChild(style)
}
