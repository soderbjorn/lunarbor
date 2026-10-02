/* PaneSearchBar.kt (jsMain)
 *
 * The pane's search: a rounded filter field (magnifier, input, match
 * count, close button) drawn above the page title while the search is open
 * (`PaneBackingViewModel.State.searchQuery != null`), and the result list
 * that takes the page's place while the field holds a word — one row per
 * matching line with where it lives. Clicking a row (or Enter on the
 * highlighted one) goes to the line in this pane; its link icon (or
 * Cmd-Enter) opens it in a new window.
 * The search itself runs in commonMain (`PaneBackingViewModel.setSearchQuery`
 * over the vault's `TextIndex`).
 *
 * Typing a `#tag` in the field opens an autocomplete popup of the tree's
 * tags (`PaneBackingViewModel.tagSuggestions`, from the text index), most
 * used first — modelled on Lunicle's mention popup: fixed to the viewport
 * and mounted on `<body>` (no ancestor can crop it), rows act on
 * mousedown, the highlight moves without rebuilding the rows.
 *
 * View only: holds ephemeral UI state (the highlighted result) and forwards
 * every intent to the [MainViewModel]. Owned by `MainScreen`, which mounts
 * [element] above the title and [resultsElement] in the scroll area, and
 * calls [update] on every state emission. */
package se.soderbjorn.lunarbor.main

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent
import se.soderbjorn.lunarbor.data.TagCount
import se.soderbjorn.lunarbor.data.TextHit
import se.soderbjorn.lunarbor.data.SearchQuery

/**
 * One pane's search field and result list.
 *
 * ### Callers
 * - `MainScreen.render` mounts [element] and [resultsElement] and calls
 *   [update] with each pane state.
 * - `MainScreen.openSearch` (search button, Cmd-F, palette) calls [focus].
 *
 * @param viewModel The pane's view model; receives the search intents.
 * @param onLeave Called after the field closes, so the editor takes focus.
 * @param onOpenHit Opens a result in a new window (`AppShell`); going to a
 *   result in this pane is `MainViewModel.navigateToSearchHit`.
 */
internal class PaneSearchBar(
    private val viewModel: MainViewModel,
    private val onLeave: () -> Unit,
    private val onOpenHit: (TextHit) -> Unit,
) {
    /** The field; hidden while the search is closed. */
    val element: HTMLElement = document.createElement("div") as HTMLElement

    /** The result list; shown in place of the page while a query is active. */
    val resultsElement: HTMLElement = document.createElement("div") as HTMLElement

    private val input = document.createElement("input") as HTMLInputElement
    private val status = document.createElement("span") as HTMLElement

    /** The ⇅ button: reverses the order of the results. */
    private val reverse = document.createElement("button") as HTMLElement

    /** The hits the list shows, to repaint only when they change. */
    private var shownHits: List<TextHit>? = null

    /** Index of the highlighted result (Enter opens it). */
    private var selected = 0

    /** The tag autocomplete popup; on `<body>` only while it shows. */
    private val tagMenu: HTMLElement = document.createElement("div") as HTMLElement

    /** The partly typed tag under the caret: `#` index in the input and the caret. */
    private var tagToken: Pair<Int, Int>? = null

    /** The tags the popup offers. */
    private var tagOptions: List<TagCount> = emptyList()

    /** Index of the highlighted tag (Enter / Tab takes it). */
    private var tagIndex = 0

    init {
        ensureSearchBarStyles()
        // Open / closed is a class, so the bar can slide (see the CSS).
        element.className = "lunarbor-search"
        resultsElement.className = "lunarbor-search-results"
        resultsElement.style.display = "none"
        val box = document.createElement("div") as HTMLElement
        box.className = "lunarbor-search-box"
        val icon = document.createElement("span") as HTMLElement
        icon.className = "lunarbor-search-icon"
        icon.innerHTML = ICON_SEARCH
        input.className = "lunarbor-search-input"
        input.type = "text"
        input.placeholder = "Search this tree…"
        input.setAttribute("spellcheck", "false")
        input.setAttribute("aria-label", "Search this tree")
        status.className = "lunarbor-search-status"
        val close = document.createElement("button") as HTMLElement
        close.className = "lunarbor-search-close"
        close.title = "Close search (Esc)"
        close.innerHTML = ICON_CLOSE
        reverse.className = "lunarbor-search-reverse"
        reverse.innerHTML = ICON_REVERSE
        reverse.addEventListener("mousedown", { ev -> ev.preventDefault() })
        reverse.addEventListener("click", {
            viewModel.setSearchReversed(!viewModel.currentBackingState.searchReversed)
        })
        box.appendChild(icon)
        box.appendChild(input)
        box.appendChild(status)
        box.appendChild(reverse)
        box.appendChild(close)
        // The clip wrapper is what the grid row shrinks to nothing.
        val clip = document.createElement("div") as HTMLElement
        clip.className = "lunarbor-search-clip"
        clip.appendChild(box)
        element.appendChild(clip)

        // Clicking anywhere in the box but the button focuses the input.
        box.addEventListener("mousedown", { ev ->
            val t = ev.target as? org.w3c.dom.Node
            if (t !== input && !close.contains(t) && !reverse.contains(t)) {
                ev.preventDefault()
                input.focus()
            }
        })
        tagMenu.className = "lunarbor-tag-menu"
        tagMenu.setAttribute("role", "listbox")
        // Keep the press from moving focus out of the input.
        tagMenu.addEventListener("mousedown", { ev -> ev.preventDefault() })
        input.addEventListener("input", {
            viewModel.setSearchQuery(input.value)
            refreshTagMenu()
        })
        // Caret moves (arrows, Home / End, clicks) can enter or leave a tag.
        input.addEventListener("keyup", { ev ->
            val k = (ev as KeyboardEvent).key
            if (k == "ArrowLeft" || k == "ArrowRight" || k == "Home" || k == "End") refreshTagMenu()
        })
        input.addEventListener("click", { refreshTagMenu() })
        input.addEventListener("blur", { hideTagMenu() })
        input.addEventListener("keydown", { ev ->
            val ke = ev as KeyboardEvent
            val plain = !ke.metaKey && !ke.ctrlKey && !ke.altKey
            // While the tag popup shows it owns these keys.
            if (tagOptions.isNotEmpty() && plain) {
                when (ke.key) {
                    "ArrowDown", "ArrowUp" -> {
                        ke.preventDefault()
                        ke.stopPropagation()
                        val n = tagOptions.size
                        setTagHighlight(((tagIndex + if (ke.key == "ArrowDown") 1 else -1) % n + n) % n)
                        return@addEventListener
                    }
                    "Enter", "Tab" -> {
                        ke.preventDefault()
                        ke.stopPropagation()
                        applyTag(tagOptions[tagIndex])
                        return@addEventListener
                    }
                    "Escape" -> {
                        ke.preventDefault()
                        ke.stopPropagation()
                        hideTagMenu()
                        return@addEventListener
                    }
                }
            }
            when {
                ke.key == "Escape" -> {
                    ke.preventDefault()
                    close()
                }
                ke.key == "ArrowDown" && plain -> {
                    ke.preventDefault()
                    select(selected + 1)
                }
                ke.key == "ArrowUp" && plain -> {
                    ke.preventDefault()
                    select(selected - 1)
                }
                ke.key == "Enter" && plain -> {
                    ke.preventDefault()
                    shownHits?.getOrNull(selected)?.let(::goTo)
                }
                ke.key == "Enter" && (ke.metaKey || ke.ctrlKey) && !ke.altKey -> {
                    ke.preventDefault()
                    shownHits?.getOrNull(selected)?.let(onOpenHit)
                }
            }
            // Keep the pane's and the app's shortcuts off plain typing.
            ke.stopPropagation()
        })
        close.addEventListener("mousedown", { ev -> ev.preventDefault() })
        close.addEventListener("click", { close() })
    }

    /** Focuses the input with its text selected, so typing replaces it. */
    fun focus() {
        input.focus()
        input.select()
    }

    /** Goes to [hit] in this pane (the search closes) and hands focus to the editor. */
    private fun goTo(hit: TextHit) {
        hideTagMenu()
        viewModel.navigateToSearchHit(hit)
        onLeave()
    }

    /** Closes the search and hands focus back to the editor. */
    fun close() {
        hideTagMenu()
        viewModel.closeSearch()
        onLeave()
    }

    /**
     * The partly typed tag ending at the caret: the index of its `#` and
     * the caret, or `null`. A `#` counts when it starts a word (like
     * `InlineMarkdownTokenizer`'s tag rule) and only tag characters lie
     * between it and the caret.
     */
    private fun tagAtCaret(): Pair<Int, Int>? {
        val text = input.value
        val caret = (input.selectionStart ?: return null)
        if (input.selectionEnd != caret) return null
        var i = caret
        while (i > 0 && isTagChar(text[i - 1])) i--
        if (i == 0 || text[i - 1] != '#') return null
        val hash = i - 1
        if (hash > 0 && isTagChar(text[hash - 1])) return null
        return hash to caret
    }

    private fun isTagChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '-'

    /**
     * Shows the tags matching the `#…` under the caret, or hides the popup
     * when there is none (or the only match is exactly what is typed). The
     * highlight stays on the same tag across keystrokes when it survives.
     */
    private fun refreshTagMenu() {
        val token = tagAtCaret()
        val options = token?.let { (hash, caret) ->
            val typed = input.value.substring(hash, caret)
            viewModel.tagSuggestions(typed).takeUnless { it.size == 1 && it[0].tag.equals(typed, ignoreCase = true) }
        }.orEmpty()
        if (token == null || options.isEmpty()) {
            hideTagMenu()
            return
        }
        val previous = tagOptions.getOrNull(tagIndex)?.tag
        tagToken = token
        tagOptions = options
        tagIndex = options.indexOfFirst { it.tag == previous }.takeIf { it >= 0 } ?: 0
        tagMenu.innerHTML = ""
        options.forEachIndexed { i, option ->
            val row = document.createElement("div") as HTMLElement
            row.className = "lunarbor-tag-menu-item"
            row.setAttribute("role", "option")
            val name = document.createElement("span") as HTMLElement
            name.className = "lunarbor-tag-menu-name"
            name.textContent = option.tag
            val count = document.createElement("span") as HTMLElement
            count.className = "lunarbor-tag-menu-count"
            count.textContent = option.count.toString()
            row.appendChild(name)
            row.appendChild(count)
            // Hover moves the highlight (so mouse and keys agree); a press
            // picks — on mousedown, which a re-render between press and
            // release cannot swallow.
            row.addEventListener("mouseenter", { setTagHighlight(i) })
            row.addEventListener("mousedown", { ev ->
                ev.preventDefault()
                applyTag(option)
            })
            tagMenu.appendChild(row)
        }
        setTagHighlight(tagIndex)
        if (tagMenu.parentNode == null) document.body?.appendChild(tagMenu)
        positionTagMenu(token.first)
    }

    /**
     * Places the popup under the field, its left edge under the `#` (the
     * text's width up to it, measured in the input's font), flipped above
     * the field when there is no room below.
     */
    private fun positionTagMenu(hash: Int) {
        val box = input.getBoundingClientRect()
        val canvas = document.createElement("canvas").asDynamic()
        val ctx = canvas.getContext("2d")
        val cs = kotlinx.browser.window.getComputedStyle(input)
        ctx.font = "${cs.fontWeight} ${cs.fontSize} ${cs.fontFamily}"
        val offset = (ctx.measureText(input.value.substring(0, hash)).width as Number).toDouble() - input.scrollLeft
        val left = (box.left + offset.coerceIn(0.0, box.width)).coerceAtMost(kotlinx.browser.window.innerWidth - 240.0)
        val height = tagMenu.offsetHeight.toDouble()
        val below = box.bottom + 8
        val fits = below + height <= kotlinx.browser.window.innerHeight
        tagMenu.style.left = "${left.coerceAtLeast(4.0)}px"
        tagMenu.style.top = if (fits) "${below}px" else "${box.top - height - 8}px"
    }

    /** Moves the tag highlight to [index] without rebuilding the rows. */
    private fun setTagHighlight(index: Int) {
        tagIndex = index
        val rows = tagMenu.children
        for (i in 0 until rows.length) {
            val el = rows.item(i) as? HTMLElement ?: continue
            if (i == index) {
                el.classList.add("lunarbor-tag-menu-item-on")
                el.setAttribute("aria-selected", "true")
                el.asDynamic().scrollIntoView(js("{block: 'nearest'}"))
            } else {
                el.classList.remove("lunarbor-tag-menu-item-on")
                el.setAttribute("aria-selected", "false")
            }
        }
    }

    /**
     * Replaces the partly typed tag with [option] and a space, puts the
     * caret after it, and searches.
     */
    private fun applyTag(option: TagCount) {
        val (hash, caret) = tagToken ?: return
        val text = input.value
        val rest = text.substring(caret).let { if (it.startsWith(" ")) it.substring(1) else it }
        val inserted = option.tag + " "
        input.value = text.substring(0, hash) + inserted + rest
        val at = hash + inserted.length
        input.setSelectionRange(at, at)
        hideTagMenu()
        viewModel.setSearchQuery(input.value)
    }

    /** Hides the tag popup. */
    private fun hideTagMenu() {
        tagOptions = emptyList()
        tagToken = null
        tagMenu.parentNode?.removeChild(tagMenu)
    }

    /**
     * Shows or hides the field and the list for [state], refreshes the
     * input (unless the user is typing in it) and the status, and repaints
     * the list when the hits changed.
     */
    fun update(state: PaneBackingViewModel.State) {
        val query = state.searchQuery
        val open = query != null && !state.isImageView && !state.isHtmlView
        if (open) element.classList.add("lunarbor-search-open") else element.classList.remove("lunarbor-search-open")
        val active = open && state.isSearchActive
        resultsElement.style.display = if (active) "" else "none"
        if (!open) {
            shownHits = null
            hideTagMenu()
            return
        }
        if (document.activeElement !== input && input.value != query) input.value = query.orEmpty()
        if (state.searchReversed) reverse.classList.add("lunarbor-search-reverse-on") else reverse.classList.remove("lunarbor-search-reverse-on")
        reverse.title = if (state.searchReversed) "Normal order" else "Reverse order"
        status.textContent = when {
            !active -> ""
            state.isSearching -> "Searching…"
            state.searchTotal == 0 -> "No matches"
            state.searchTotal == 1 -> "1 match"
            else -> "${state.searchTotal} matches"
        }
        if (active && state.searchHits !== shownHits) paintResults(state)
    }

    /** Rebuilds the result list for [state]; the first row is highlighted. */
    private fun paintResults(state: PaneBackingViewModel.State) {
        val hits = state.searchHits
        shownHits = hits
        selected = 0
        resultsElement.innerHTML = ""
        val terms = SearchQuery.parse(state.searchQuery).highlightTerms()
        for ((i, hit) in hits.withIndex()) {
            val row = document.createElement("div") as HTMLElement
            row.className = "lunarbor-search-hit"
            row.title = "Go to this line"
            val text = document.createElement("div") as HTMLElement
            text.className = "lunarbor-search-hit-text"
            appendHighlighted(text, hit.text, terms)
            val where = document.createElement("div") as HTMLElement
            where.className = "lunarbor-search-hit-where"
            where.appendChild(isolatedText(viewModel.searchHitCrumbs(hit).joinToString(" › ")))
            val open = document.createElement("span") as HTMLElement
            open.className = "lunarbor-search-hit-open"
            open.title = "Open in a new window (⌘⏎)"
            open.innerHTML = ICON_OPEN
            open.addEventListener("mousedown", { ev ->
                ev.preventDefault()
                ev.stopPropagation()
                if ((ev as MouseEvent).button.toInt() != 0) return@addEventListener
                select(i)
                onOpenHit(hit)
            })
            val body = document.createElement("div") as HTMLElement
            body.className = "lunarbor-search-hit-body"
            body.appendChild(text)
            body.appendChild(where)
            row.appendChild(body)
            row.appendChild(open)
            row.addEventListener("mousedown", { ev -> ev.preventDefault() })
            row.addEventListener("click", {
                select(i)
                goTo(hit)
            })
            // Right-click opens it in a new window, like a link or a file.
            row.addEventListener("contextmenu", { ev ->
                ev.preventDefault()
                ev.stopPropagation()
                select(i)
                onOpenHit(hit)
            })
            resultsElement.appendChild(row)
        }
        if (state.searchTotal > hits.size) {
            val more = document.createElement("div") as HTMLElement
            more.className = "lunarbor-search-more"
            more.textContent = "Showing the first ${hits.size} of ${state.searchTotal} matches — add a word to narrow them."
            resultsElement.appendChild(more)
        }
        select(0)
    }

    /** Highlights result [index] (clamped) and scrolls it into view. */
    private fun select(index: Int) {
        val count = shownHits?.size ?: 0
        if (count == 0) return
        selected = index.coerceIn(0, count - 1)
        val rows = resultsElement.querySelectorAll(".lunarbor-search-hit")
        for (i in 0 until rows.length) {
            val el = rows.item(i) as? HTMLElement ?: continue
            if (i == selected) {
                el.classList.add("lunarbor-search-hit-selected")
                el.asDynamic().scrollIntoView(js("{block: 'nearest'}"))
            } else {
                el.classList.remove("lunarbor-search-hit-selected")
            }
        }
    }

    companion object {
        /** Magnifier, also the pane header's search button (`AppShell`). */
        const val ICON_SEARCH: String =
            "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
                "stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
                "<circle cx=\"11\" cy=\"11\" r=\"7\"/><line x1=\"21\" y1=\"21\" x2=\"16.65\" y2=\"16.65\"/></svg>"

        /** ⇅: an arrow up and an arrow down, side by side. */
        private const val ICON_REVERSE: String =
            "<svg viewBox=\"0 0 24 24\" width=\"15\" height=\"15\" fill=\"none\" " +
                "stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
                "<line x1=\"8\" y1=\"19\" x2=\"8\" y2=\"5\"/><polyline points=\"4 9 8 5 12 9\"/>" +
                "<line x1=\"16\" y1=\"5\" x2=\"16\" y2=\"19\"/><polyline points=\"12 15 16 19 20 15\"/></svg>"

        private const val ICON_CLOSE: String =
            "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" " +
                "stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\">" +
                "<line x1=\"6\" y1=\"6\" x2=\"18\" y2=\"18\"/><line x1=\"18\" y1=\"6\" x2=\"6\" y2=\"18\"/></svg>"

        /** "Open in new window": a box with an arrow leaving it. */
        private const val ICON_OPEN: String =
            "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" " +
                "stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
                "<path d=\"M14 4h6v6\"/><line x1=\"20\" y1=\"4\" x2=\"11\" y2=\"13\"/>" +
                "<path d=\"M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5\"/></svg>"
    }
}

/**
 * Appends [text] to [parent] with every occurrence of the search [terms]
 * (already lowercased) wrapped in a `<mark>`. Overlapping occurrences
 * merge. Also used for search-node result rows (`OutlinePaintLoop`).
 */
internal fun appendHighlighted(parent: HTMLElement, text: String, terms: List<String>) {
    val lower = text.lowercase()
    val marked = BooleanArray(text.length)
    // Lowercasing can change a string's length (rare letters); then just
    // skip the highlight rather than mark the wrong characters.
    if (lower.length == text.length) {
        for (term in terms) {
            var from = lower.indexOf(term)
            while (from >= 0) {
                for (i in from until from + term.length) marked[i] = true
                from = lower.indexOf(term, from + 1)
            }
        }
    }
    var i = 0
    while (i < text.length) {
        val on = marked[i]
        var j = i
        while (j < text.length && marked[j] == on) j++
        val piece = text.substring(i, j)
        if (on) {
            val mark = document.createElement("mark") as HTMLElement
            mark.textContent = piece
            parent.appendChild(mark)
        } else {
            parent.appendChild(document.createTextNode(piece))
        }
        i = j
    }
}

/** Injects the search's CSS once per document. */
private fun ensureSearchBarStyles() {
    if (document.getElementById("lunarbor-search-styles") != null) return
    val el = document.createElement("style")
    el.id = "lunarbor-search-styles"
    el.textContent = """
        /* Slides open and shut: the grid row goes 0fr <-> 1fr while the
           bar fades, so the page below glides rather than jumps. */
        .lunarbor-search {
            flex: 0 0 auto;
            display: grid;
            grid-template-rows: 0fr;
            opacity: 0;
            padding: 0 16px;
            transition: grid-template-rows 180ms ease, opacity 180ms ease, padding 180ms ease;
        }
        .lunarbor-search.lunarbor-search-open {
            grid-template-rows: 1fr;
            opacity: 1;
            padding: 10px 16px 4px 16px;
        }
        .lunarbor-search-clip {
            min-height: 0;
            overflow: hidden;
        }
        @media (prefers-reduced-motion: reduce) {
            .lunarbor-search { transition: none; }
        }
        .lunarbor-search-box {
            display: flex;
            align-items: center;
            gap: 10px;
            max-width: 480px;
            height: 36px;
            padding: 0 8px 0 12px;
            box-sizing: border-box;
            border-radius: 8px;
            background: var(--t-surface-alt, #262626);
            border: 1px solid transparent;
            cursor: text;
        }
        .lunarbor-search-box:focus-within {
            border-color: var(--t-border, #4a4a4a);
        }
        .lunarbor-search-icon {
            display: flex;
            flex: 0 0 auto;
            color: var(--t-text-dim, #7a7a7a);
        }
        .lunarbor-search-input {
            flex: 1 1 auto;
            min-width: 0;
            border: none;
            outline: none;
            background: transparent;
            color: var(--t-text, #e6e6e6);
            font: inherit;
            font-size: 14px;
        }
        .lunarbor-search-input::placeholder {
            color: var(--t-text-dim, #7a7a7a);
        }
        .lunarbor-search-status {
            flex: 0 0 auto;
            font-size: 12px;
            color: var(--t-text-dim, #7a7a7a);
            white-space: nowrap;
        }
        .lunarbor-search-close {
            display: flex;
            align-items: center;
            justify-content: center;
            flex: 0 0 auto;
            width: 22px;
            height: 22px;
            padding: 0;
            border: none;
            border-radius: 4px;
            background: transparent;
            color: var(--t-text-dim, #7a7a7a);
            cursor: pointer;
        }
        .lunarbor-search-reverse {
            display: flex;
            align-items: center;
            justify-content: center;
            flex: 0 0 auto;
            width: 24px;
            height: 22px;
            padding: 0;
            border: none;
            border-radius: 4px;
            background: transparent;
            color: var(--t-text-dim, #7a7a7a);
            cursor: pointer;
        }
        .lunarbor-search-reverse:hover {
            color: var(--t-text, #e6e6e6);
            background: var(--t-surface, #2a2a2a);
        }
        /* On: the results are reversed. */
        .lunarbor-search-reverse-on {
            color: var(--t-accent, #5ab0ff);
            background: var(--t-accent-soft, rgba(90, 160, 255, 0.18));
        }
        .lunarbor-search-close:hover {
            color: var(--t-text, #e6e6e6);
            background: var(--t-surface, #2a2a2a);
        }
        .lunarbor-search-results {
            padding: 4px 12px 24px 12px;
        }
        .lunarbor-search-hit {
            display: flex;
            align-items: center;
            gap: 12px;
            max-width: 760px;
            padding: 6px 8px;
            border-radius: 6px;
            cursor: pointer;
            user-select: none;
        }
        .lunarbor-search-hit:hover,
        .lunarbor-search-hit-selected {
            background: var(--t-surface-alt, #262626);
        }
        .lunarbor-search-hit-body {
            flex: 1 1 auto;
            min-width: 0;
        }
        .lunarbor-search-hit-text {
            color: var(--t-text, #e6e6e6);
            font-size: 14px;
            line-height: 20px;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
        }
        .lunarbor-search-hit-text mark {
            background: var(--t-accent-soft, rgba(90, 160, 255, 0.25));
            color: inherit;
            border-radius: 2px;
        }
        /* Clipped from the start, so the path's tail shows (see
           `.lunarbor-search-node-where`). */
        .lunarbor-search-hit-where {
            color: var(--t-text-dim, #7a7a7a);
            font-size: 12px;
            line-height: 16px;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
            direction: rtl;
            text-align: left;
        }
        .lunarbor-search-hit-open {
            display: flex;
            flex: 0 0 auto;
            padding: 4px;
            border-radius: 4px;
            color: var(--t-text-dim, #7a7a7a);
            opacity: 0;
        }
        .lunarbor-search-hit-open:hover {
            color: var(--t-text, #e6e6e6);
            background: var(--t-surface, #2a2a2a);
        }
        .lunarbor-search-hit:hover .lunarbor-search-hit-open,
        .lunarbor-search-hit-selected .lunarbor-search-hit-open {
            opacity: 1;
        }
        .lunarbor-tag-menu {
            position: fixed;
            z-index: 10020;
            min-width: 180px;
            max-width: 320px;
            max-height: 264px;
            overflow-y: auto;
            padding: 4px;
            box-sizing: border-box;
            border-radius: 8px;
            border: 1px solid var(--t-border, #4a4a4a);
            background: var(--t-surface, #2a2a2a);
            box-shadow: 0 6px 20px rgba(0, 0, 0, 0.25);
        }
        .lunarbor-tag-menu-item {
            display: flex;
            align-items: baseline;
            justify-content: space-between;
            gap: 16px;
            padding: 5px 8px;
            border-radius: 4px;
            font-size: 13px;
            color: var(--t-text, #e6e6e6);
            cursor: pointer;
            user-select: none;
        }
        .lunarbor-tag-menu-item-on {
            background: var(--t-surface-alt, #333);
        }
        .lunarbor-tag-menu-name {
            color: var(--t-accent, #5ab0ff);
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
        }
        .lunarbor-tag-menu-count {
            flex: 0 0 auto;
            font-size: 12px;
            color: var(--t-text-dim, #7a7a7a);
        }
        .lunarbor-search-more {
            padding: 10px 8px;
            color: var(--t-text-dim, #7a7a7a);
            font-size: 12px;
        }
    """.trimIndent()
    document.head?.appendChild(el)
}
