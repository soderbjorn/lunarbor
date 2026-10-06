/* LunicleBoardView.kt (jsMain)
 *
 * Draws a board node (LBR-27, proposal 2a "Properties as tags"): the sync
 * indicator on the node's line ([buildLunicleSyncIndicator]) and the board
 * under it ([buildLunicleBoard]) — a child row per column with its count,
 * a row per issue with its pills, and under an unfolded issue its
 * description, its comments and a "Comment…" row (LBR-31). All of it comes from
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
 * click.
 *
 * Editing (LBR-29): an editable issue's title, a draft and the column's
 * "New issue" line are marked as fields ([LUNICLE_FIELD_ATTR]);
 * `LunicleBoardCursor` puts its text field there when its caret is on the
 * row, and a press on one dispatches [LUNICLE_PRESS_EVENT] so the cursor
 * starts editing it. A draft shows a dim note ("new issue · created in
 * Lunicle when you leave the line"); an issue being filed shows its title
 * with "Saving…" and then its key.
 *
 * The keyboard walks the rows (LBR-28, `LunicleBoardCursor`): the box
 * carries its node's row ([LUNICLE_BOARD_ROW_ATTR]) and every navigable
 * row its `LunicleRowRef.key` ([LUNICLE_ROW_KEY_ATTR]), in the order
 * `LunicleBoardRows.of` lists them, so the cursor finds its row again after
 * every repaint; editable rows mark where their caret is drawn
 * ([LUNICLE_CARET_HOST_CLASS]).
 *
 * Description and comments (LBR-31): the description is a bordered box
 * of Markdown lines, the caret's drawn raw ([LUNICLE_DESC_LINE_ATTR]),
 * large ones cut to a preview; comments are one inline row each with
 * `author · when`, arrivals flashing; "Comment…" is a field.
 *
 * Properties (LBR-30): every pill dispatches [LUNICLE_PILL_EVENT] on a
 * press, so `LunicleBoardCursor` opens that field's menu; the row a change
 * moved ([PaneBackingViewModel.LunicleBoardView.flashKey]) gets
 * `is-flash`, a warm background fading over `LunicleBoardMenu.FLASH_MS`.
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
 * On the text of a row edited in a real text field (LBR-29): an issue's
 * title, a draft, the "New issue" line. `LunicleBoardCursor` puts its
 * `<input>` in its place.
 */
internal const val LUNICLE_FIELD_ATTR = "data-lunicle-field"

/**
 * Dispatched (bubbling) by a press on a field's text ([LUNICLE_FIELD_ATTR]),
 * with `detail = { row, key }` — the node's document row and the board
 * row's key. `MainScreen` hands it to `LunicleBoardCursor.press`.
 */
internal const val LUNICLE_PRESS_EVENT = "lunarbor-lunicle-press"

/** The dim note on a draft's line (LBR-29). */
private const val DRAFT_NOTE = "new issue · created in Lunicle when you leave the line"

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
        for (item in column.items) {
            val issue = when (item) {
                is LunicleColumnItem.Draft -> {
                    val ref = LunicleRowRef(LunicleRowKind.DRAFT, status, item.draft.localId)
                    val row = boardRow(1, style, isPage, "lunarbor-lunicle-issue lunarbor-lunicle-draft", ref)
                    appendPlainDot(row)
                    val line = document.createElement("span") as HTMLElement
                    line.className = "lunarbor-lunicle-issue-line"
                    line.appendChild(fieldText("lunarbor-lunicle-issue-title is-caret-start", "", nodeRow, ref))
                    line.appendChild(span("lunarbor-lunicle-dim lunarbor-lunicle-draft-note", DRAFT_NOTE))
                    row.appendChild(line)
                    box.appendChild(row)
                    continue
                }
                is LunicleColumnItem.Creating -> {
                    val entry = item.entry
                    val row = boardRow(1, style, isPage, "lunarbor-lunicle-issue lunarbor-lunicle-creating", LunicleRowRef(LunicleRowKind.CREATING, status, entry.localId))
                    appendPlainDot(row)
                    val line = document.createElement("span") as HTMLElement
                    line.className = "lunarbor-lunicle-issue-line"
                    line.appendChild(span("lunarbor-lunicle-issue-title", entry.title))
                    line.appendChild(
                        span(
                            "lunarbor-lunicle-dim" + if (entry.createdKey != null) " lunarbor-lunicle-key" else "",
                            entry.createdKey ?: "Saving to Lunicle…",
                        ),
                    )
                    row.appendChild(line)
                    box.appendChild(row)
                    continue
                }
                is LunicleColumnItem.Issue -> item.view
            }
            val issueRef = LunicleRowRef(LunicleRowKind.ISSUE, status, issue.issue.id)
            val issueRow = boardRow(1, style, isPage, "lunarbor-lunicle-issue", issueRef)
            appendFoldControls(issueRow, 1, style, folded = !issue.unfolded) { viewModel.toggleLunicleIssue(issue) }
            val line = document.createElement("span") as HTMLElement
            line.className = "lunarbor-lunicle-issue-line"
            val titleText = issue.issue.title.ifBlank { issue.issue.key }
            line.appendChild(
                if (issue.editable) fieldText("lunarbor-lunicle-issue-title", titleText, nodeRow, issueRef)
                else span("lunarbor-lunicle-issue-title $LUNICLE_CARET_HOST_CLASS", titleText),
            )
            for (pill in issue.pills) line.appendChild(pillElement(pill, nodeRow, issueRef))
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
            if (issue.unfolded) appendIssueChildren(box, issue, status, style, isPage, viewModel, nodeRow, now)
        }
        if (column.newIssueLine) {
            // The column's last row: type a title here to file an issue (LBR-29).
            val ref = LunicleRowRef(LunicleRowKind.NEW_ISSUE, status)
            val row = boardRow(1, style, isPage, "lunarbor-lunicle-issue lunarbor-lunicle-placeholder lunarbor-lunicle-new-issue", ref)
            appendPlainDot(row)
            row.appendChild(fieldText("lunarbor-lunicle-dim is-caret-start", NEW_ISSUE_TEXT, nodeRow, ref))
            box.appendChild(row)
        }
    }
    view.flashKey?.let { flash ->
        // The row a property change moved (LBR-30) flashes; a repaint
        // resumes the fade where it was rather than starting it again.
        val rows = box.children
        for (i in 0 until rows.length) {
            val el = rows.item(i) as? HTMLElement ?: continue
            if (el.getAttribute(LUNICLE_ROW_KEY_ATTR) != flash) continue
            el.classList.add("is-flash")
            el.style.setProperty("animation-delay", "-${(now - view.flashAt).coerceAtLeast(0)}ms")
        }
    }
    return box
}

/**
 * Dispatched (bubbling) by a press on an issue's pill (LBR-30), with
 * `detail = { row, key, field }` — the node's document row, the issue
 * row's key and the pill's `LuniclePill.Field` name. `MainScreen` hands it
 * to `LunicleBoardCursor.pressPill`.
 */
internal const val LUNICLE_PILL_EVENT = "lunarbor-lunicle-pill"

/** An issue's pill: a press opens its field's menu ([LUNICLE_PILL_EVENT]). */
private fun pillElement(pill: se.soderbjorn.lunarbor.lunicle.LuniclePill, nodeRow: Int, issueRef: LunicleRowRef): HTMLElement {
    val el = span("lunarbor-lunicle-pill", pill.text)
    el.title = "Change ${LunicleBoardMenu.sectionOf(pill.field).lowercase()}"
    el.addEventListener("mousedown", { ev ->
        val me = ev as MouseEvent
        ev.preventDefault()
        ev.stopPropagation()
        if (me.button.toInt() != 0) return@addEventListener
        val detail: dynamic = js("({})")
        detail.row = nodeRow
        detail.key = issueRef.key
        detail.field = pill.field.name
        el.dispatchEvent(org.w3c.dom.CustomEvent(LUNICLE_PILL_EVENT, org.w3c.dom.CustomEventInit(detail = detail, bubbles = true)))
    })
    return el
}

/** The "New issue" line's placeholder text (LBR-29). */
internal const val NEW_ISSUE_TEXT = "New issue"

/**
 * The text of a row edited in a field ([LUNICLE_FIELD_ATTR]): a press on
 * it asks the keyboard's cursor to start editing the row
 * ([LUNICLE_PRESS_EVENT]) instead of being swallowed by the board.
 */
private fun fieldText(className: String, text: String, nodeRow: Int, ref: LunicleRowRef): HTMLElement {
    val el = span("$className $LUNICLE_CARET_HOST_CLASS lunarbor-lunicle-field-host", text)
    el.setAttribute(LUNICLE_FIELD_ATTR, ref.key)
    el.addEventListener("mousedown", { ev ->
        val me = ev as MouseEvent
        if (me.button.toInt() != 0 || me.metaKey || me.ctrlKey || me.shiftKey) return@addEventListener
        val detail: dynamic = js("({})")
        detail.row = nodeRow
        detail.key = ref.key
        el.dispatchEvent(org.w3c.dom.CustomEvent(LUNICLE_PRESS_EVENT, org.w3c.dom.CustomEventInit(detail = detail, bubbles = true)))
        ev.preventDefault()
        ev.stopPropagation()
    })
    return el
}


/**
 * The rows under an unfolded issue: its description, its comments, and
 * "Comment…" (LBR-31).
 *
 *  - The **description** is one row holding a bordered box (a block's
 *    look) with a line per Markdown line, each rendered — but the caret's
 *    line while this pane edits it, drawn raw for `LunicleBoardCursor`'s
 *    field to cover ([LUNICLE_DESC_LINE_ATTR]). Empty: "Add a description"
 *    (dim; "No description" when it cannot be edited). A large one shows
 *    its preview, the last row fading, with "N more lines" and an
 *    expand / collapse control (`MainViewModel.toggleLunicleDescription`).
 *    A press on a line edits it there ([LUNICLE_PRESS_EVENT] with `line`).
 *  - **Comments**: the body as one inline row, then `author · when`
 *    dimmed; a comment being posted is faded, one that just arrived from
 *    Lunicle flashes ([LunicleComments.ARRIVAL_MS]).
 *  - **"Comment…"**, where the token may comment: a field
 *    ([LUNICLE_FIELD_ATTR]) showing the text kept for it, or the placeholder.
 */
private fun appendIssueChildren(
    box: HTMLElement,
    issue: PaneBackingViewModel.LunicleIssueView,
    status: String,
    style: EditorStyle,
    isPage: Boolean,
    viewModel: MainViewModel,
    nodeRow: Int,
    now: Long,
) {
    val id = issue.issue.id
    val description = issue.description
    val descRef = LunicleRowRef(LunicleRowKind.DESCRIPTION, status, id)
    if (issue.detail == null || description == null) {
        // The description's row, until the issue has been read.
        val row = boardRow(2, style, isPage, "lunarbor-lunicle-child", descRef)
        appendPlainDot(row)
        row.appendChild(span("lunarbor-lunicle-dim lunarbor-lunicle-loading", if (issue.loading) "Loading…" else "Not loaded yet"))
        box.appendChild(row)
        return
    }
    val desc = boardRow(2, style, isPage, "lunarbor-lunicle-child lunarbor-lunicle-description", descRef)
    appendPlainDot(desc)
    desc.appendChild(descriptionBox(issue, description, descRef, nodeRow, viewModel))
    box.appendChild(desc)
    val offset = utcOffsetMinutes()
    for (comment in issue.comments) {
        val row = boardRow(
            2, style, isPage, "lunarbor-lunicle-child lunarbor-lunicle-comment",
            LunicleRowRef(LunicleRowKind.COMMENT, status, id, comment.id),
        )
        if (comment.pending) row.classList.add("is-pending")
        comment.arrivedAt?.let { at ->
            val age = now - at
            if (age in 0 until LunicleComments.ARRIVAL_MS) {
                // A comment that arrived from Lunicle: a brief highlight,
                // resumed where it was on a repaint.
                row.classList.add("is-arrived")
                row.style.setProperty("animation-delay", "-${age}ms")
            }
        }
        appendPlainDot(row)
        val body = document.createElement("span") as HTMLElement
        body.className = "lunarbor-lunicle-comment-body"
        val text = document.createElement("span") as HTMLElement
        text.className = "lunarbor-lunicle-comment-text"
        appendInlineRuns(text, comment.body, viewModel)
        body.appendChild(text)
        body.appendChild(
            span("lunarbor-lunicle-dim lunarbor-lunicle-meta", LunicleComments.metaText(comment.author, comment.agentName, comment.createdAt, now, offset)),
        )
        row.appendChild(body)
        box.appendChild(row)
    }
    if (!issue.canComment) return
    val ref = LunicleRowRef(LunicleRowKind.ADD_COMMENT, status, id)
    val add = boardRow(2, style, isPage, "lunarbor-lunicle-child lunarbor-lunicle-add-comment", ref)
    if (issue.commentDraft.isEmpty()) add.classList.add("lunarbor-lunicle-placeholder")
    appendPlainDot(add)
    add.appendChild(
        if (issue.commentDraft.isEmpty()) fieldText("lunarbor-lunicle-dim is-caret-start", LunicleComments.PLACEHOLDER, nodeRow, ref)
        else fieldText("lunarbor-lunicle-comment-draft", issue.commentDraft, nodeRow, ref),
    )
    box.appendChild(add)
}

/**
 * On each line of a description's box (LBR-31): its index among the
 * description's lines. `LunicleBoardCursor` puts its field over the
 * caret's line, found by it.
 */
internal const val LUNICLE_DESC_LINE_ATTR = "data-lunicle-desc-line"

/** An issue's description as a bordered box of lines (see [appendIssueChildren]). */
private fun descriptionBox(
    issue: PaneBackingViewModel.LunicleIssueView,
    description: LunicleDescriptionView,
    ref: LunicleRowRef,
    nodeRow: Int,
    viewModel: MainViewModel,
): HTMLElement {
    val boxEl = document.createElement("div") as HTMLElement
    boxEl.className = "lunarbor-lunicle-desc"
    if (description.editable) boxEl.classList.add("is-editable")
    fun pressable(el: HTMLElement, line: Int) {
        if (!description.editable) return
        el.addEventListener("mousedown", { ev ->
            val me = ev as MouseEvent
            if (me.button.toInt() != 0 || me.metaKey || me.ctrlKey || me.shiftKey) return@addEventListener
            val detail: dynamic = js("({})")
            detail.row = nodeRow
            detail.key = ref.key
            detail.line = line
            el.dispatchEvent(org.w3c.dom.CustomEvent(LUNICLE_PRESS_EVENT, org.w3c.dom.CustomEventInit(detail = detail, bubbles = true)))
            ev.preventDefault()
            ev.stopPropagation()
        })
    }
    if (description.isEmpty && description.caretLine == null) {
        val line = document.createElement("div") as HTMLElement
        line.className = "lunarbor-lunicle-desc-line is-empty"
        line.setAttribute(LUNICLE_DESC_LINE_ATTR, "0")
        line.appendChild(
            span(
                "lunarbor-lunicle-dim $LUNICLE_CARET_HOST_CLASS is-caret-start",
                if (description.editable) "Add a description" else "No description",
            ),
        )
        pressable(line, 0)
        boxEl.appendChild(line)
        return boxEl
    }
    val shown = description.shownLines
    var inCode = false
    for ((i, raw) in shown.withIndex()) {
        val line = document.createElement("div") as HTMLElement
        line.className = "lunarbor-lunicle-desc-line"
        line.setAttribute(LUNICLE_DESC_LINE_ATTR, i.toString())
        val fence = raw.trimStart().startsWith("```")
        if (i == description.caretLine) {
            // The caret's line, raw: the cursor's field covers it.
            line.classList.add("is-caret-line")
            line.textContent = raw.ifEmpty { "\u200B" }
        } else if (fence) {
            line.classList.add("is-fence")
            line.textContent = raw
        } else {
            renderMarkdownLine(line, raw, inCode, viewModel)
        }
        if (fence) inCode = !inCode
        if (i == shown.size - 1 && description.hiddenRows > 0) line.classList.add("is-clipped")
        pressable(line, i)
        boxEl.appendChild(line)
    }
    if (description.hiddenRows > 0) {
        val more = document.createElement("span") as HTMLElement
        more.className = "lunarbor-block-more lunarbor-lunicle-desc-more"
        val n = description.hiddenRows
        more.textContent = if (n == 1) "1 more line" else "$n more lines"
        more.addEventListener("mousedown", { ev ->
            ev.preventDefault()
            ev.stopPropagation()
            viewModel.toggleLunicleDescription(issue)
        })
        boxEl.appendChild(more)
    }
    if (description.large) {
        val collapsed = description.hiddenRows > 0
        val fold = document.createElement("span") as HTMLElement
        fold.className = "lunarbor-block-fold lunarbor-lunicle-desc-fold"
        fold.title = if (collapsed) "Show the whole description" else "Show less"
        fold.innerHTML = if (collapsed) ICON_BLOCK_EXPAND else ICON_BLOCK_COLLAPSE
        fold.addEventListener("mousedown", { ev ->
            ev.preventDefault()
            ev.stopPropagation()
            viewModel.toggleLunicleDescription(issue)
        })
        boxEl.appendChild(fold)
    }
    return boxEl
}

/** The user's time zone offset, minutes east of UTC (for comment times). */
private fun utcOffsetMinutes(): Int = -(kotlin.js.Date().getTimezoneOffset()).toInt()

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
 * Fills [line] with one Markdown line of a description (LBR-31): a code
 * line ([inCode]) in a monospace band, a list item with a dot, a heading
 * bold, a quote with a bar, and inline Markdown styled as in the outline.
 * Links to `https:` pages open in the browser.
 */
private fun renderMarkdownLine(line: HTMLElement, raw: String, inCode: Boolean, viewModel: MainViewModel) {
    if (inCode) {
        line.classList.add("is-code")
        line.textContent = raw.ifEmpty { " " }
        return
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
    if (content.isBlank()) line.classList.add("is-blank")
    appendInlineRuns(line, content, viewModel)
}

/**
 * [text]'s inline Markdown as styled spans in [el], as the outline draws
 * it (a comment's body, a description line). Links to `https:` pages open
 * in the browser.
 */
private fun appendInlineRuns(el: HTMLElement, text: String, viewModel: MainViewModel) {
    // Lunicle text is verbatim Markdown: `\*` shows as `*`, as in Lunicle.
    for (run in InlineMarkdownTokenizer.tokenize(text, escapes = true).runs) {
        val href = run.linkHref
        val runEl = span(
            inlineRunCssClasses(run.styles, isLink = href != null, isTag = run.isTag).joinToString(" "),
            run.text,
        )
        if (run.isTag) runEl.style.setProperty("--tag-h", tagHue(run.text).toString())
        if (href != null && href.startsWith("https://")) {
            runEl.title = href
            runEl.addEventListener("mousedown", { ev ->
                ev.preventDefault()
                ev.stopPropagation()
                viewModel.openExternalUrl(href)
            })
        }
        el.appendChild(runEl)
    }
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
        /* An issue's description (LBR-31): a bordered box like a block's,
           one line per Markdown line. */
        .lunarbor-lunicle-desc {
            position: relative;
            display: inline-block;
            vertical-align: top;
            box-sizing: border-box;
            width: calc(100% - 1.6em);
            margin: 3px 0;
            padding: 3px 10px;
            border: 1px solid var(--t-border, #4a4a4a);
            border-radius: 6px;
            background: rgba(127, 127, 127, 0.05);
            white-space: pre-wrap;
        }
        .lunarbor-lunicle-desc.is-editable .lunarbor-lunicle-desc-line {
            cursor: text;
        }
        .lunarbor-lunicle-desc:has(.lunarbor-lunicle-desc-fold) {
            padding-right: 32px;
        }
        .lunarbor-lunicle-desc-line {
            display: block;
            min-height: 1lh;
        }
        .lunarbor-lunicle-desc-line.is-heading {
            font-weight: 700;
        }
        .lunarbor-lunicle-desc-line.is-quote {
            border-left: 2px solid var(--t-border, #4a4a4a);
            padding-left: 8px;
            color: var(--t-text-dim, #9a9a9a);
        }
        .lunarbor-lunicle-desc-line.is-list {
            text-indent: -1.1em;
        }
        .lunarbor-lunicle-desc-line.is-code,
        .lunarbor-lunicle-desc-line.is-fence {
            font-family: var(--dt-font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
            font-size: var(--dt-font-mono-size, 0.9em);
            background: var(--t-border, rgba(127, 127, 127, 0.12));
            padding: 0 6px;
            white-space: pre;
            overflow-x: auto;
        }
        .lunarbor-lunicle-desc-line.is-fence {
            color: var(--t-text-dim, #9a9a9a);
        }
        .lunarbor-lunicle-desc-line.is-caret-line {
            font-family: inherit;
            white-space: pre-wrap;
        }
        /* A large description's preview: its last row fades out. */
        .lunarbor-lunicle-desc-line.is-clipped {
            -webkit-mask-image: linear-gradient(to bottom, #000 30%, transparent);
            mask-image: linear-gradient(to bottom, #000 30%, transparent);
        }
        .lunarbor-lunicle-desc-fold {
            top: 4px;
        }
        .lunarbor-lunicle-desc input.lunarbor-lunicle-field {
            width: 100%;
        }
        /* Comments (LBR-31): one being posted is faded; one that just
           arrived from Lunicle flashes a warm background. */
        .lunarbor-lunicle-comment.is-pending .lunarbor-lunicle-comment-body {
            opacity: 0.6;
        }
        .lunarbor-lunicle-row.is-arrived {
            border-radius: 6px;
            animation: lunarbor-lunicle-flash ${LunicleComments.ARRIVAL_MS}ms ease-out both;
        }
        .lunarbor-lunicle-add-comment input.lunarbor-lunicle-field {
            width: calc(100% - 1.6em);
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
        .lunarbor-lunicle-row.is-board-cursor:not(.is-key-caret) > .lunarbor-lunicle-loading,
        .lunarbor-lunicle-row.is-board-cursor:not(.is-key-caret) > .lunarbor-lunicle-desc {
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
        /* Editing in place (LBR-29): the cursor's text field takes the
           place of a title, a draft or the "New issue" line, and looks
           like the text it replaces. */
        .lunarbor-lunicle-row.is-board-cursor:not(.is-key-caret) > .lunarbor-lunicle-issue-line {
            background: var(--t-accent-soft, rgba(90, 160, 255, 0.18));
            border-radius: 4px;
            padding: 1px 4px 0;
            margin: 0 -4px;
        }
        input.lunarbor-lunicle-field {
            font: inherit;
            color: var(--t-text, inherit);
            caret-color: var(--t-text, currentColor);
            background: transparent;
            border: 0;
            outline: 0;
            padding: 0;
            margin: 0;
            min-width: 2ch;
            max-width: 100%;
            field-sizing: content;
            line-height: inherit;
            vertical-align: baseline;
            -webkit-user-select: text;
            user-select: text;
        }
        input.lunarbor-lunicle-field::placeholder {
            color: var(--t-text-dim, #9a9a9a);
            opacity: 1;
        }
        .lunarbor-lunicle-field-host {
            cursor: text;
        }
        .lunarbor-lunicle-new-issue .lunarbor-lunicle-field-host {
            font-size: 1em;
        }
        .lunarbor-lunicle-draft-note {
            font-style: italic;
        }
        .lunarbor-lunicle-creating .lunarbor-lunicle-issue-title {
            opacity: 0.75;
        }
        /* Properties (LBR-30): pills open their field's menu; a changed
           issue (or the folded closing column it went into) flashes a warm
           background that fades over 1.5 s. */
        .lunarbor-lunicle-pill {
            cursor: pointer;
        }
        .lunarbor-lunicle-pill:hover {
            background: color-mix(in srgb, var(--t-accent, #e8825e) 24%, transparent);
        }
        .lunarbor-lunicle-row.is-flash {
            border-radius: 6px;
            animation: lunarbor-lunicle-flash ${LunicleBoardMenu.FLASH_MS}ms ease-out both;
        }
        @keyframes lunarbor-lunicle-flash {
            from { background: color-mix(in srgb, var(--t-warn, #e8b04b) 30%, transparent); }
            to { background: transparent; }
        }
        ${LunicleBoardMenuPopup.CSS}
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
