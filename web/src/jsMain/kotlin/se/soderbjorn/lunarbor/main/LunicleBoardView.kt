/* LunicleBoardView.kt (jsMain)
 *
 * Draws a board node (LBR-27, proposal 2a "Properties as tags"): the sync
 * indicator on the node's line ([buildLunicleSyncIndicator]) and the board
 * under it ([buildLunicleBoard]) — a child row per column with its count,
 * a row per issue with its pills, and under an unfolded issue its
 * description, its comments and a "Comment…" row. All of it comes from
 * `PaneBackingViewModel.lunicleBoardOf`'s view; the rules (ordering,
 * pills, texts) live in commonMain (`lunicle/LunicleBoardModel.kt`).
 *
 * Like a search node's results, the board is not part of the document: one
 * `contenteditable="false"` box inside the node's row, with no `data-row`
 * of its own and none of the editor's text classes, so caret mapping,
 * selection and saving never see it. Rows look like outline rows — 30 px a
 * level, a dot, the hover −/+ control, a ring round a folded dot, guide
 * lines — but fold through the pane's own board fold state
 * (`MainViewModel.toggleLunicleColumn` / `toggleLunicleIssue`). Everything
 * acts on mousedown: a repaint between press and release would swallow a
 * click. Read-only until LBR-29 to LBR-31.
 *
 * The keyboard walks the rows (LBR-28, `LunicleBoardCursor`): the box
 * carries its node's row ([LUNICLE_BOARD_ROW_ATTR]) and every navigable
 * row its `LunicleRowRef.key` ([LUNICLE_ROW_KEY_ATTR]), in the order
 * `LunicleBoardRows.of` lists them, so the cursor finds its row again after
 * every repaint; editable rows mark where their caret is drawn
 * ([LUNICLE_CARET_HOST_CLASS]).
 *
 * Colours are the theme's `--t-*` variables; pills use the accent, not the
 * tags' `tagHue` palette (they are not tags). The CSS is installed by
 * [ensureLunicleBoardStyles], called from `ensureStyles`.
 *
 * View only: no rules beyond layout. */
package se.soderbjorn.lunarbor.main

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent
import se.soderbjorn.lunarbor.data.InlineMarkdownTokenizer
import se.soderbjorn.lunarbor.data.LineMarkdownPrefix
import se.soderbjorn.lunarbor.data.LineStyle
import se.soderbjorn.lunarbor.lunicle.LunicleBoardLayout
import se.soderbjorn.lunarbor.lunicle.LunicleSyncKind

/** On a board's box: its node's document row (for `LunicleBoardCursor`). */
internal const val LUNICLE_BOARD_ROW_ATTR = "data-lunicle-board-row"

/** On every navigable board row: its `LunicleRowRef.key`. */
internal const val LUNICLE_ROW_KEY_ATTR = "data-lunicle-key"

/**
 * On an editable row's text: where the keyboard's caret is drawn — at its
 * end, or at its start with `is-caret-start` (an empty field's placeholder).
 */
internal const val LUNICLE_CARET_HOST_CLASS = "lunarbor-lunicle-caret-host"

/**
 * The sync indicator for a board node: a dot and its text — "Live",
 * "Synced 12s ago", "Saving to Lunicle…", a remote notice, or an error.
 * Placed on the node's line after its text (and on a zoomed board node's
 * page title). A "Synced …" text keeps itself current
 * ([refreshLunicleSyncTexts], every few seconds) without a repaint.
 */
internal fun buildLunicleSyncIndicator(view: PaneBackingViewModel.LunicleBoardView): HTMLElement {
    val sync = view.sync
    val box = document.createElement("span") as HTMLElement
    box.className = "lunarbor-lunicle-sync is-${sync.kind.name.lowercase()}"
    box.setAttribute("contenteditable", "false")
    val dot = document.createElement("span") as HTMLElement
    dot.className = "lunarbor-lunicle-sync-dot"
    val text = document.createElement("span") as HTMLElement
    text.className = "lunarbor-lunicle-sync-text"
    text.textContent = sync.text
    if (sync.kind == LunicleSyncKind.SYNCED && sync.syncedAt != null) {
        text.setAttribute(SYNCED_AT_ATTR, sync.syncedAt.toString())
        ensureSyncTicker()
    }
    box.title = when (sync.kind) {
        LunicleSyncKind.LIVE -> "Live: changes in Lunicle show here as they happen"
        LunicleSyncKind.SYNCED -> "Read from Lunicle; checked every ${LunicleBoardsPollSeconds}s while shown"
        LunicleSyncKind.ERROR -> sync.text
        else -> ""
    }
    box.addEventListener("mousedown", { ev ->
        ev.preventDefault()
        ev.stopPropagation()
    })
    box.appendChild(dot)
    box.appendChild(text)
    return box
}

/** Shown in the indicator's tooltip. */
private val LunicleBoardsPollSeconds: Long = se.soderbjorn.lunarbor.lunicle.LunicleBoards.POLL_MS / 1000

/** Attribute on a "Synced …" text naming when the board was read (epoch ms). */
private const val SYNCED_AT_ATTR = "data-synced-at"

private var syncTicker: Int? = null

/** Starts the one app-wide timer that keeps "Synced 12s ago" texts current. */
private fun ensureSyncTicker() {
    if (syncTicker != null) return
    syncTicker = window.setInterval({ refreshLunicleSyncTexts() }, 5_000)
}

/** Rewrites every "Synced …" text on screen from its [SYNCED_AT_ATTR]. */
internal fun refreshLunicleSyncTexts() {
    val now = kotlin.js.Date.now().toLong()
    val texts = document.querySelectorAll("[$SYNCED_AT_ATTR]")
    for (i in 0 until texts.length) {
        val el = texts.item(i) as? HTMLElement ?: continue
        val at = el.getAttribute(SYNCED_AT_ATTR)?.toLongOrNull() ?: continue
        el.textContent = LunicleBoardLayout.syncedText(at, now)
    }
}

/**
 * The board under a board node: columns, issues and an unfolded issue's
 * children (see the file header). Empty before the first read; dimmed
 * ([PaneBackingViewModel.LunicleBoardView.stale]) under an error.
 *
 * @param nodeRow The board node's document row, set on the box
 *   ([LUNICLE_BOARD_ROW_ATTR]) for the keyboard's cursor.
 * @param isPage `true` when the pane is zoomed into the node: the board
 *   heads the page, flush left.
 */
internal fun buildLunicleBoard(
    view: PaneBackingViewModel.LunicleBoardView,
    nodeRow: Int,
    viewModel: MainViewModel,
    style: EditorStyle,
    isPage: Boolean = false,
): HTMLElement {
    val box = document.createElement("div") as HTMLElement
    box.className = "lunarbor-lunicle-board"
    if (view.stale) box.classList.add("is-stale")
    box.setAttribute("contenteditable", "false")
    box.setAttribute(LUNICLE_BOARD_ROW_ATTR, nodeRow.toString())
    if (!isPage) box.style.setProperty("margin-left", "calc(${style.indentStepPx}px - $BLOCK_DOT_SLOT)")
    // Keep presses away from the editor's caret placement and drag code.
    box.addEventListener("mousedown", { ev ->
        ev.preventDefault()
        ev.stopPropagation()
    })
    val now = kotlin.js.Date.now().toLong()
    for (column in view.columns) {
        val status = column.column.status.name
        val row = boardRow(0, style, isPage, "lunarbor-lunicle-column", LunicleRowRef(LunicleRowKind.COLUMN, status))
        appendFoldControls(row, 0, style, folded = column.folded) { viewModel.toggleLunicleColumn(column) }
        row.appendChild(span("lunarbor-lunicle-column-name", column.column.status.name))
        row.appendChild(span("lunarbor-lunicle-dim lunarbor-lunicle-count", column.column.count.toString()))
        box.appendChild(row)
        if (column.folded) continue
        for (issue in column.issues) {
            val issueRow = boardRow(1, style, isPage, "lunarbor-lunicle-issue", LunicleRowRef(LunicleRowKind.ISSUE, status, issue.issue.id))
            appendFoldControls(issueRow, 1, style, folded = !issue.unfolded) { viewModel.toggleLunicleIssue(issue) }
            val line = document.createElement("span") as HTMLElement
            line.className = "lunarbor-lunicle-issue-line"
            line.appendChild(span("lunarbor-lunicle-issue-title $LUNICLE_CARET_HOST_CLASS", issue.issue.title.ifBlank { issue.issue.key }))
            for (pill in issue.pills) line.appendChild(span("lunarbor-lunicle-pill", pill.text))
            if (!issue.unfolded) {
                issue.commentsLabel?.let { line.appendChild(span("lunarbor-lunicle-dim", it)) }
            } else {
                val key = span("lunarbor-lunicle-dim lunarbor-lunicle-key", issue.issue.key)
                issue.url?.let { url ->
                    key.classList.add("is-link")
                    key.title = "Open ${issue.issue.key} in Lunicle"
                    key.addEventListener("mousedown", { ev ->
                        ev.preventDefault()
                        ev.stopPropagation()
                        viewModel.openExternalUrl(url)
                    })
                }
                line.appendChild(key)
            }
            issueRow.appendChild(line)
            box.appendChild(issueRow)
            if (issue.unfolded) appendIssueChildren(box, issue, status, style, isPage, viewModel, now)
        }
    }
    return box
}

/** The rows under an unfolded issue: its description, its comments, and "Comment…". */
private fun appendIssueChildren(
    box: HTMLElement,
    issue: PaneBackingViewModel.LunicleIssueView,
    status: String,
    style: EditorStyle,
    isPage: Boolean,
    viewModel: MainViewModel,
    now: Long,
) {
    val id = issue.issue.id
    val detail = issue.detail
    if (detail == null) {
        // The description's row, until the issue has been read.
        val row = boardRow(2, style, isPage, "lunarbor-lunicle-child", LunicleRowRef(LunicleRowKind.DESCRIPTION, status, id))
        appendPlainDot(row)
        row.appendChild(span("lunarbor-lunicle-dim lunarbor-lunicle-loading", if (issue.loading) "Loading…" else "Not loaded yet"))
        box.appendChild(row)
        return
    }
    val desc = boardRow(2, style, isPage, "lunarbor-lunicle-child lunarbor-lunicle-description", LunicleRowRef(LunicleRowKind.DESCRIPTION, status, id))
    appendPlainDot(desc)
    if (detail.description.isBlank()) {
        desc.appendChild(span("lunarbor-lunicle-dim $LUNICLE_CARET_HOST_CLASS is-caret-start", "No description"))
    } else {
        val body = markdownBody(detail.description, viewModel)
        // The caret goes at the end of the last line.
        ((body.lastElementChild as? HTMLElement) ?: body).classList.add(LUNICLE_CARET_HOST_CLASS)
        desc.appendChild(body)
    }
    box.appendChild(desc)
    for (comment in detail.comments) {
        val row = boardRow(
            2, style, isPage, "lunarbor-lunicle-child lunarbor-lunicle-comment",
            LunicleRowRef(LunicleRowKind.COMMENT, status, id, comment.id),
        )
        appendPlainDot(row)
        val body = document.createElement("span") as HTMLElement
        body.className = "lunarbor-lunicle-comment-body"
        body.appendChild(markdownBody(comment.body, viewModel))
        val who = comment.agentName?.let { "${comment.author} ($it)" } ?: comment.author
        body.appendChild(span("lunarbor-lunicle-dim lunarbor-lunicle-meta", "$who · ${LunicleBoardLayout.whenText(comment.createdAt, now)}"))
        row.appendChild(body)
        box.appendChild(row)
    }
    // Writing a comment comes with LBR-31; the row is a placeholder until then.
    val add = boardRow(2, style, isPage, "lunarbor-lunicle-child lunarbor-lunicle-placeholder", LunicleRowRef(LunicleRowKind.ADD_COMMENT, status, id))
    appendPlainDot(add)
    // An input's caret sits before its placeholder.
    add.appendChild(span("lunarbor-lunicle-dim $LUNICLE_CARET_HOST_CLASS is-caret-start", "Comment…"))
    box.appendChild(add)
}

/**
 * One board row at board [depth] (0 column, 1 issue, 2 an issue's
 * children): hanging indent like a bullet row, and guide lines for every
 * open level above it — the node's own included, unless the board heads
 * the page ([isPage]). [ref] names it for the keyboard's cursor
 * ([LUNICLE_ROW_KEY_ATTR]).
 */
private fun boardRow(depth: Int, style: EditorStyle, isPage: Boolean, className: String, ref: LunicleRowRef): HTMLElement {
    val row = document.createElement("div") as HTMLElement
    row.className = "lunarbor-lunicle-row $className"
    row.setAttribute(LUNICLE_ROW_KEY_ATTR, ref.key)
    row.style.apply {
        setProperty("position", "relative")
        minHeight = "${style.lineHeightPx}px"
        setProperty("line-height", "${style.lineHeightPx}px")
        paddingLeft = "calc(${depth * style.indentStepPx}px + $BLOCK_DOT_SLOT)"
        setProperty("text-indent", "-$BLOCK_DOT_SLOT")
    }
    if (isPage) appendIndentGuides(row, depth, "0px", style)
    else appendIndentGuides(row, depth + 1, "-${style.indentStepPx}px", style)
    return row
}

/**
 * A foldable row's dot (a ring round it while [folded]) and its hover
 * −/+ control; pressing either runs [onToggle].
 */
private fun appendFoldControls(row: HTMLElement, depth: Int, style: EditorStyle, folded: Boolean, onToggle: () -> Unit) {
    if (folded) row.classList.add("lunarbor-row-folded")
    val chevron = buildChevron(folded, animate = false, onToggle = onToggle)
    chevron.style.left = "${depth * style.indentStepPx - 22}px"
    chevron.style.maxHeight = "${style.lineHeightPx}px"
    row.appendChild(chevron)
    val prefix = dotPrefix()
    prefix.title = if (folded) "Expand" else "Collapse"
    prefix.addEventListener("mousedown", { ev ->
        (ev as MouseEvent).preventDefault()
        ev.stopPropagation()
        onToggle()
    })
    row.appendChild(prefix)
}

/** A dot that does nothing (description, comment, placeholder rows). */
private fun appendPlainDot(row: HTMLElement) {
    val prefix = dotPrefix()
    prefix.classList.add("lunarbor-bullet-plain")
    row.appendChild(prefix)
}

/** The bullet dot and the space after it, as outline rows draw them. */
private fun dotPrefix(): HTMLElement {
    val prefix = document.createElement("span") as HTMLElement
    prefix.className = "lunarbor-bullet-prefix"
    prefix.setAttribute("contenteditable", "false")
    val glyph = document.createElement("span") as HTMLElement
    glyph.className = "lunarbor-bullet"
    prefix.appendChild(glyph)
    val space = document.createElement("span") as HTMLElement
    space.textContent = " "
    prefix.appendChild(space)
    return prefix
}

private fun span(className: String, text: String): HTMLElement {
    val el = document.createElement("span") as HTMLElement
    el.className = className
    el.textContent = text
    return el
}

/**
 * Markdown [text] (an issue's description, a comment) as read-only lines:
 * headings bold and larger, list items with a dot, fenced code in a
 * monospace band, inline Markdown styled as in the outline. Links to
 * `https:` pages open in the browser.
 */
private fun markdownBody(text: String, viewModel: MainViewModel): HTMLElement {
    val body = document.createElement("span") as HTMLElement
    body.className = "lunarbor-lunicle-md"
    var inCode = false
    for (raw in text.replace("\r\n", "\n").split('\n')) {
        if (raw.trimStart().startsWith("```")) {
            inCode = !inCode
            continue
        }
        val line = document.createElement("span") as HTMLElement
        line.className = "lunarbor-lunicle-md-line"
        if (inCode) {
            line.classList.add("is-code")
            line.textContent = raw.ifEmpty { " " }
            body.appendChild(line)
            continue
        }
        var content = raw
        val list = Regex("""^(\s*)([*+-]|\d+[.)])\s+""").find(raw)
        if (list != null) {
            line.classList.add("is-list")
            line.style.paddingLeft = "${(list.groupValues[1].length / 2) * 1.2 + 1.1}em"
            line.appendChild(span("lunarbor-lunicle-md-marker", if (list.groupValues[2].first().isDigit()) list.groupValues[2] + " " else "• "))
            content = raw.substring(list.range.last + 1)
        } else {
            val prefix = LineMarkdownPrefix.detect(raw, 0)
            when (prefix.style) {
                null -> {}
                LineStyle.QUOTE -> line.classList.add("is-quote")
                else -> line.classList.add("is-heading")
            }
            if (prefix.style != null) content = raw.substring(prefix.markerEnd)
        }
        // A blank line is a paragraph gap, half a line high.
        if (content.isBlank()) line.classList.add("is-blank")
        for (run in InlineMarkdownTokenizer.tokenize(content).runs) {
            val href = run.linkHref
            val el = span(
                inlineRunCssClasses(run.styles, isLink = href != null, isTag = run.isTag).joinToString(" "),
                run.text,
            )
            if (run.isTag) el.style.setProperty("--tag-h", tagHue(run.text).toString())
            if (href != null && href.startsWith("https://")) {
                el.title = href
                el.addEventListener("mousedown", { ev ->
                    ev.preventDefault()
                    ev.stopPropagation()
                    viewModel.openExternalUrl(href)
                })
            }
            line.appendChild(el)
        }
        body.appendChild(line)
    }
    return body
}

/**
 * The board's CSS: rows, pills, the sync indicator and the `{{lunicle: …}}`
 * chip / board icon. Returned for `ensureStyles` to append to the editor's
 * one style sheet.
 */
internal fun lunicleBoardCss(): String = """
        /* A board node's reference, `{{lunicle: …}}` (LBR-27): a chip on
           the caret's row, else a small board icon (a pseudo-element: no
           text, so caret mapping never sees it), like a search query. */
        .lunarbor-md-lunicle {
            font-size: 0;
        }
        .lunarbor-md-lunicle::before {
            content: "";
            display: inline-block;
            width: 13px;
            height: 13px;
            margin-left: 2px;
            vertical-align: -1px;
            background-color: var(--t-text-dim, #9a9a9a);
            -webkit-mask: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2.2' stroke-linejoin='round'%3E%3Crect x='3' y='4' width='18' height='16' rx='2.5'/%3E%3Cline x1='9' y1='4' x2='9' y2='20'/%3E%3Cline x1='15' y1='4' x2='15' y2='20'/%3E%3C/svg%3E") center / contain no-repeat;
            mask: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='black' stroke-width='2.2' stroke-linejoin='round'%3E%3Crect x='3' y='4' width='18' height='16' rx='2.5'/%3E%3Cline x1='9' y1='4' x2='9' y2='20'/%3E%3Cline x1='15' y1='4' x2='15' y2='20'/%3E%3C/svg%3E") center / contain no-repeat;
        }
        .lunarbor-row-caret .lunarbor-md-lunicle::before {
            display: none;
        }
        .lunarbor-row-caret .lunarbor-md-lunicle {
            color: var(--t-text-dim, #9a9a9a);
            background: rgba(127, 127, 127, 0.12);
            border-radius: 4px;
            padding: 0 4px;
            font-size: 0.85em;
            font-family: var(--dt-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
        }
        /* The sync indicator after the node's text. */
        .lunarbor-lunicle-sync {
            display: inline-flex;
            align-items: center;
            gap: 6px;
            margin-left: 10px;
            font-size: 0.78em;
            color: var(--t-text-dim, #9a9a9a);
            user-select: none;
            -webkit-user-select: none;
            vertical-align: 0.05em;
            white-space: nowrap;
        }
        .lunarbor-lunicle-sync-dot {
            width: 7px;
            height: 7px;
            border-radius: 50%;
            background: var(--t-text-dim, #9a9a9a);
            flex: none;
        }
        .lunarbor-lunicle-sync.is-synced .lunarbor-lunicle-sync-dot,
        .lunarbor-lunicle-sync.is-live .lunarbor-lunicle-sync-dot {
            background: var(--t-add, #5bae7a);
        }
        .lunarbor-lunicle-sync.is-saving .lunarbor-lunicle-sync-dot {
            background: var(--t-warn, #e8b04b);
        }
        .lunarbor-lunicle-sync.is-remote .lunarbor-lunicle-sync-dot {
            background: var(--t-accent, #5b8def);
        }
        .lunarbor-lunicle-sync.is-remote .lunarbor-lunicle-sync-text {
            color: var(--t-accent, #5b8def);
        }
        .lunarbor-lunicle-sync.is-error .lunarbor-lunicle-sync-dot {
            background: var(--t-danger, #e05a5a);
        }
        .lunarbor-lunicle-sync.is-error .lunarbor-lunicle-sync-text {
            color: var(--t-danger, #e05a5a);
            white-space: normal;
        }
        .lunarbor-title-lunicle-sync {
            font-size: 15px;
            font-weight: 400;
            font-style: normal;
            vertical-align: middle;
        }
        .lunarbor-title-lunicle-sync .lunarbor-lunicle-sync {
            font-size: 1em;
            margin-left: 12px;
        }
        /* The board under its node: read-only rows that look like the
           outline's. */
        .lunarbor-lunicle-board {
            text-indent: 0;
            user-select: none;
            -webkit-user-select: none;
            cursor: default;
        }
        /* Rows carry a hanging indent; nothing inside may inherit it
           (on a board node's page the board is outside any [data-row]). */
        .lunarbor-lunicle-row * {
            text-indent: 0;
        }
        .lunarbor-lunicle-board.is-stale {
            opacity: 0.5;
        }
        .lunarbor-lunicle-row:hover > .lunarbor-chevron,
        .lunarbor-lunicle-row.lunarbor-hover > .lunarbor-chevron {
            opacity: 0.85;
        }
        .lunarbor-lunicle-row .lunarbor-bullet-prefix {
            cursor: pointer;
        }
        .lunarbor-lunicle-row .lunarbor-bullet-plain,
        .lunarbor-lunicle-placeholder .lunarbor-bullet {
            cursor: default;
        }
        .lunarbor-lunicle-placeholder .lunarbor-bullet {
            opacity: 0.4;
        }
        .lunarbor-lunicle-column-name {
            font-weight: 600;
        }
        .lunarbor-lunicle-dim {
            color: var(--t-text-dim, #9a9a9a);
            font-size: 0.85em;
        }
        .lunarbor-lunicle-count {
            margin-left: 8px;
        }
        .lunarbor-lunicle-issue-line {
            display: inline;
        }
        .lunarbor-lunicle-issue-line > * + * {
            margin-left: 8px;
        }
        .lunarbor-lunicle-pill {
            display: inline-block;
            color: var(--t-accent, #e8825e);
            background: color-mix(in srgb, var(--t-accent, #e8825e) 14%, transparent);
            padding: 0 5px;
            border-radius: 4px;
            font-size: 0.92em;
            line-height: 1.45;
        }
        .lunarbor-lunicle-key {
            font-family: var(--dt-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
        }
        .lunarbor-lunicle-key.is-link {
            cursor: pointer;
        }
        .lunarbor-lunicle-key.is-link:hover {
            color: var(--t-accent, #5ab0ff);
            text-decoration: underline;
        }
        .lunarbor-lunicle-md {
            display: inline-block;
            vertical-align: top;
            white-space: pre-wrap;
            max-width: 100%;
        }
        .lunarbor-lunicle-md-line {
            display: block;
        }
        .lunarbor-lunicle-md-line.is-blank {
            height: 0.5em;
        }
        .lunarbor-lunicle-md-line.is-heading {
            font-weight: 700;
        }
        .lunarbor-lunicle-md-line.is-quote {
            border-left: 2px solid var(--t-border, #4a4a4a);
            padding-left: 8px;
            color: var(--t-text-dim, #9a9a9a);
        }
        .lunarbor-lunicle-md-line.is-list {
            text-indent: -1.1em;
        }
        .lunarbor-lunicle-md-line.is-code {
            font-family: var(--dt-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
            font-size: var(--dt-font-mono-size, 0.9em);
            background: var(--t-border, rgba(127, 127, 127, 0.12));
            padding: 0 6px;
            white-space: pre;
            overflow-x: auto;
        }
        .lunarbor-lunicle-comment-body > .lunarbor-lunicle-meta {
            margin-left: 8px;
        }
        /* The keyboard's caret on a board row (LBR-28, LunicleBoardCursor):
           a focus tint on rows that cannot be edited (column names,
           comments), a caret on the ones that can (titles, the
           description, "Comment…"). The editor's own caret is hidden
           meanwhile. */
        .lunarbor-board-cursor-active {
            caret-color: transparent;
        }
        .lunarbor-lunicle-row.is-board-cursor:not(.is-key-caret) > .lunarbor-lunicle-column-name,
        .lunarbor-lunicle-row.is-board-cursor:not(.is-key-caret) > .lunarbor-lunicle-comment-body,
        .lunarbor-lunicle-row.is-board-cursor:not(.is-key-caret) > .lunarbor-lunicle-loading {
            background: var(--t-accent-soft, rgba(90, 160, 255, 0.18));
            border-radius: 4px;
            padding: 1px 4px 0;
            margin: 0 -4px;
        }
        .lunarbor-lunicle-row.is-key-caret .$LUNICLE_CARET_HOST_CLASS:not(.is-caret-start)::after,
        .lunarbor-lunicle-row.is-key-caret .$LUNICLE_CARET_HOST_CLASS.is-caret-start::before {
            content: "";
            display: inline-block;
            width: 1.5px;
            height: 1.15em;
            margin: 0 1px;
            vertical-align: -0.2em;
            background: var(--t-text, currentColor);
            animation: lunarbor-lunicle-caret-blink 1.06s steps(1) infinite;
        }
        @keyframes lunarbor-lunicle-caret-blink {
            50% { opacity: 0; }
        }
        @media (prefers-reduced-motion: reduce) {
            .lunarbor-lunicle-row.is-key-caret .$LUNICLE_CARET_HOST_CLASS::after,
            .lunarbor-lunicle-row.is-key-caret .$LUNICLE_CARET_HOST_CLASS::before {
                animation: none;
            }
        }
"""
