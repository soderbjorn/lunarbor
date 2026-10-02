/*
 * CommandPalette.kt (jsMain)
 * --------------------------
 * Obsidian-style command palette opened with Cmd-P. A centered modal with
 * a search input and a live, keyboard-navigable list of matching commands.
 *
 * The palette itself owns no commands — it is constructed with a
 * `provideCommands` lambda that the host (`AppShell`) re-evaluates each
 * open. That keeps the palette agnostic of which pane is focused, which
 * tab is active, and what the available actions are at any given moment.
 *
 * Keyboard contract:
 *  - typing rebuilds the result list using `matchCommand` (see below).
 *  - ArrowUp / ArrowDown move the highlight inside the list.
 *  - Enter invokes the highlighted command and closes the palette.
 *  - Escape closes without doing anything.
 *  - The mouse path stays available — clicking a row runs that command.
 *
 * The surface uses the same toolkit `--t-*` CSS variables as `StarredModal`
 * so the palette inherits the active theme automatically. CSS is injected
 * once from `AppShell.ensureLunarborChromeStyles` to keep all chrome styles
 * in one place.
 */

package se.soderbjorn.lunarbor.main

import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.Node
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.MouseEvent

/**
 * Singleton command-palette modal. One instance per `AppShell`; reuses the
 * same DOM nodes across opens.
 *
 * @param provideCommands Called on each [open] to compute the list of
 *   currently-applicable commands. Letting the host pass a lambda means
 *   commands can capture the focused pane / active tab at open time
 *   without the palette having to know any of those concepts itself.
 */
internal class CommandPalette(
    private val provideCommands: () -> List<Command>,
) {

    /**
     * One command surfaced in the palette.
     *
     * @property id Stable identifier used to break ranking ties.
     * @property title Visible label. Word-prefix and initialism matching
     *   both run on this string, so naming matters for discoverability.
     * @property run Invoked when the user picks the command. The palette
     *   closes itself BEFORE calling `run`, so handlers can freely open
     *   their own popovers / dialogs without fighting the palette's
     *   teardown.
     */
    data class Command(
        val id: String,
        val title: String,
        val run: () -> Unit,
    )

    private var backdropEl: HTMLElement? = null
    private var panelEl: HTMLElement? = null
    private var inputEl: HTMLInputElement? = null
    private var listEl: HTMLElement? = null

    /** Latest filtered + ranked match list paired with the command. */
    private var matches: List<Pair<Command, Match>> = emptyList()

    /** Index into [matches] of the currently-highlighted row. */
    private var highlightedIndex: Int = 0

    /** Element that held focus before [open] was called; restored on close. */
    private var focusToRestore: HTMLElement? = null

    private var documentKeyHandler: ((Event) -> Unit)? = null

    /** Show the palette. Idempotent — re-opening just re-focuses the input. */
    fun open() {
        if (backdropEl != null) {
            inputEl?.focus()
            return
        }
        focusToRestore = document.activeElement as? HTMLElement
        buildDom()
        rebuildList(query = "")
        attachDocumentKeyHandler()
        // Defer focus by a tick so Chromium's "focus the just-attached
        // element" path doesn't lose it to the synthetic keydown that
        // opened us.
        inputEl?.focus()
    }

    /** Hide the palette. Idempotent. Restores the previously-focused element. */
    fun close() {
        backdropEl?.let { it.parentNode?.removeChild(it) }
        backdropEl = null
        panelEl = null
        inputEl = null
        listEl = null
        matches = emptyList()
        highlightedIndex = 0
        detachDocumentKeyHandler()
        // Return focus to the editor (or wherever it was) so the user can
        // resume typing immediately after a no-op Escape.
        focusToRestore?.focus()
        focusToRestore = null
    }

    private fun buildDom() {
        val backdrop = document.createElement("div") as HTMLElement
        backdrop.className = "lunarbor-palette-backdrop"
        backdrop.addEventListener("mousedown", { e ->
            // Click outside the panel closes; clicks inside the panel are
            // stopped by the panel's own handler so this only fires for
            // the backdrop area.
            if (e.target === backdrop) close()
        })

        val panel = document.createElement("div") as HTMLElement
        panel.className = "lunarbor-palette-panel"
        panel.addEventListener("mousedown", { e ->
            // Stop the mousedown from bubbling to the backdrop, but let it
            // propagate to inner buttons / the input so focus + click still
            // work normally.
            (e as MouseEvent).stopPropagation()
        })

        val input = document.createElement("input") as HTMLInputElement
        input.type = "text"
        input.className = "lunarbor-palette-input"
        input.placeholder = "Type a command…"
        input.autocomplete = "off"
        input.spellcheck = false
        input.addEventListener("input", { _ -> rebuildList(input.value) })
        // Keydown on the input handles arrow nav + Enter so the focus stays
        // in the input the entire time the palette is open.
        input.addEventListener("keydown", { e ->
            handleInputKey(e as KeyboardEvent)
        })

        val list = document.createElement("div") as HTMLElement
        list.className = "lunarbor-palette-list"

        panel.appendChild(input)
        panel.appendChild(list)
        backdrop.appendChild(panel)
        document.body?.appendChild(backdrop)

        backdropEl = backdrop
        panelEl = panel
        inputEl = input
        listEl = list
    }

    private fun handleInputKey(ke: KeyboardEvent) {
        when (ke.key) {
            "Escape" -> {
                ke.preventDefault()
                close()
            }
            "Enter" -> {
                ke.preventDefault()
                runHighlighted()
            }
            "ArrowDown" -> {
                ke.preventDefault()
                if (matches.isNotEmpty()) {
                    highlightedIndex = (highlightedIndex + 1).coerceAtMost(matches.lastIndex)
                    repaintHighlight()
                }
            }
            "ArrowUp" -> {
                ke.preventDefault()
                if (matches.isNotEmpty()) {
                    highlightedIndex = (highlightedIndex - 1).coerceAtLeast(0)
                    repaintHighlight()
                }
            }
            "Home" -> {
                if (ke.metaKey || ke.ctrlKey) {
                    ke.preventDefault()
                    highlightedIndex = 0
                    repaintHighlight()
                }
            }
            "End" -> {
                if (ke.metaKey || ke.ctrlKey) {
                    ke.preventDefault()
                    highlightedIndex = matches.lastIndex.coerceAtLeast(0)
                    repaintHighlight()
                }
            }
        }
    }

    private fun attachDocumentKeyHandler() {
        // Capture-phase Escape catches it even when focus accidentally
        // lands somewhere outside the input (e.g., a row click that we
        // explicitly prevent default for, but defensively also here).
        val handler: (Event) -> Unit = lambda@{ e ->
            val ke = e as? KeyboardEvent ?: return@lambda
            if (ke.key == "Escape") {
                ke.preventDefault()
                close()
            }
        }
        documentKeyHandler = handler
        document.addEventListener("keydown", handler, /* capture = */ true)
    }

    private fun detachDocumentKeyHandler() {
        documentKeyHandler?.let {
            document.removeEventListener("keydown", it, /* capture = */ true)
        }
        documentKeyHandler = null
    }

    private fun rebuildList(query: String) {
        val list = listEl ?: return
        val commands = provideCommands()
        val q = query.trim()
        matches = if (q.isEmpty()) {
            // Empty query: show every command in declaration order so the
            // palette is also discoverable as a "what can I do?" menu.
            commands.map { it to Match(score = 0, kind = MatchKind.None) }
        } else {
            commands
                .mapNotNull { cmd -> matchCommand(q, cmd.title)?.let { cmd to it } }
                .sortedWith(
                    compareBy<Pair<Command, Match>>({ it.second.score })
                        .thenBy { it.first.title.length }
                        .thenBy { commands.indexOfFirst { c -> c.id == it.first.id } },
                )
        }
        highlightedIndex = if (matches.isEmpty()) 0 else 0
        // Wipe and rebuild the row DOM. The list is small (≤ 16 commands)
        // so a full rebuild is cheaper than diffing.
        while (list.firstChild != null) list.removeChild(list.firstChild!!)
        if (matches.isEmpty()) {
            val empty = document.createElement("div") as HTMLElement
            empty.className = "lunarbor-palette-empty"
            empty.textContent = if (q.isEmpty()) "No commands available" else "No matches"
            list.appendChild(empty)
            return
        }
        for ((index, pair) in matches.withIndex()) {
            val (cmd, _) = pair
            val row = document.createElement("button") as HTMLElement
            row.className = "lunarbor-palette-item" +
                if (index == highlightedIndex) " is-active" else ""
            row.setAttribute("type", "button")
            row.textContent = cmd.title
            // Hover updates the highlight so mouse + keyboard agree on
            // which row is "active". Using mousemove (rather than mouseenter)
            // keeps the highlight stable when the palette opens with the
            // pointer already over a row but the mouse is idle.
            row.addEventListener("mousemove", { _ ->
                if (highlightedIndex != index) {
                    highlightedIndex = index
                    repaintHighlight()
                }
            })
            row.addEventListener("mousedown", { e ->
                // Don't blur the input on mousedown — keep focus in the
                // search box so a click + retype flow works.
                (e as MouseEvent).preventDefault()
            })
            row.addEventListener("click", { _ ->
                highlightedIndex = index
                runHighlighted()
            })
            list.appendChild(row)
        }
    }

    private fun repaintHighlight() {
        val list = listEl ?: return
        val rows = list.children
        for (i in 0 until rows.length) {
            val row = rows.item(i) as? HTMLElement ?: continue
            val active = i == highlightedIndex
            val cls = "lunarbor-palette-item" + if (active) " is-active" else ""
            row.className = cls
            if (active) row.scrollIntoView(js("({block:'nearest'})"))
        }
    }

    private fun runHighlighted() {
        val pair = matches.getOrNull(highlightedIndex) ?: return
        // Close BEFORE running so the command's own popover (e.g. the
        // layout dropdown) can take focus cleanly without our restoreFocus
        // path stealing it back.
        close()
        pair.first.run()
    }
}

// ── Matching ────────────────────────────────────────────────────────────

/**
 * Result of matching a query against a command title. Lower [score] is
 * better (sort ascending).
 */
internal data class Match(
    val score: Int,
    val kind: MatchKind,
)

internal enum class MatchKind { None, WordPrefixSequence, Initialism, ContiguousPrefix }

/**
 * Match [query] against [title]. Returns the best [Match] across the
 * supported strategies, or `null` if the query cannot match.
 *
 * Strategies (in priority order — best score wins):
 *  1. Whitespace-tokenized word-prefix sequence: each query token must be
 *     a prefix of a distinct title word, consumed left-to-right.
 *     "n pa" → "Open new pane" (n→new, pa→pane).
 *  2. Single-token initialism: each character of the query prefixes the
 *     start of consecutive title words.
 *     "onp" → "Open new pane".
 *  3. Single-token contiguous prefix: the query is a plain prefix of any
 *     title word.
 *     "bol" → "Bold".
 *
 * Lowercased on both sides; word boundaries are simple whitespace splits
 * (titles in this app don't use mixed-case word breaks).
 */
internal fun matchCommand(query: String, title: String): Match? {
    val q = query.lowercase()
    val titleLower = title.lowercase()
    val titleWords = titleLower.split(' ').filter { it.isNotEmpty() }
    val queryTokens = q.split(' ').filter { it.isNotEmpty() }
    if (queryTokens.isEmpty()) return Match(score = 0, kind = MatchKind.None)

    // Strategy 1: word-prefix sequence.
    val wordPrefixScore = wordPrefixSequenceScore(queryTokens, titleWords)
    var best: Match? = wordPrefixScore?.let {
        Match(score = it, kind = MatchKind.WordPrefixSequence)
    }

    // Strategy 2 + 3 are single-token only.
    if (queryTokens.size == 1) {
        val token = queryTokens[0]

        val initialismScore = initialismScore(token, titleWords)
        if (initialismScore != null) {
            val candidate = Match(score = initialismScore, kind = MatchKind.Initialism)
            val current = best
            if (current == null || candidate.score < current.score) best = candidate
        }

        val prefixScore = contiguousWordPrefixScore(token, titleWords)
        if (prefixScore != null) {
            val candidate = Match(score = prefixScore, kind = MatchKind.ContiguousPrefix)
            val current = best
            if (current == null || candidate.score < current.score) best = candidate
        }
    }

    return best
}

private fun wordPrefixSequenceScore(
    queryTokens: List<String>,
    titleWords: List<String>,
): Int? {
    // Greedy left-to-right: consume the next title word that starts with
    // the next query token. If we run out of words mid-query, no match.
    var ti = 0
    var firstMatchIndex = -1
    var consumedWordCount = 0
    for (qt in queryTokens) {
        var matched = false
        while (ti < titleWords.size) {
            if (titleWords[ti].startsWith(qt)) {
                if (firstMatchIndex == -1) firstMatchIndex = ti
                consumedWordCount++
                ti++
                matched = true
                break
            }
            ti++
        }
        if (!matched) return null
    }
    // Score: prefer matches that start earlier in the title and that span
    // a tighter set of words. 100 base for word-prefix > the 200 / 300
    // tiers below for initialism / plain prefix so this strategy wins
    // ties when applicable.
    return 100 + firstMatchIndex * 2 + (titleWords.size - consumedWordCount)
}

private fun initialismScore(token: String, titleWords: List<String>): Int? {
    if (token.length > titleWords.size) return null
    // Walk a sliding window over titleWords looking for `token.length`
    // consecutive words whose first chars equal `token`.
    for (start in 0..(titleWords.size - token.length)) {
        var ok = true
        for (i in token.indices) {
            if (titleWords[start + i].firstOrNull() != token[i]) {
                ok = false
                break
            }
        }
        if (ok) return 200 + start * 2
    }
    return null
}

private fun contiguousWordPrefixScore(token: String, titleWords: List<String>): Int? {
    for ((i, w) in titleWords.withIndex()) {
        if (w.startsWith(token)) return 300 + i * 2
    }
    return null
}
