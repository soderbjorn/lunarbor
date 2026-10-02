/*
 * InlineMarkdownTokenizer.kt
 * --------------------------
 * Pure inline-markdown tokenizer used by the WYSIWYG editor renderer to
 * split a single line of markdown into styled runs whose marker characters
 * are *not* part of the visible text. The four inline styles supported are
 * bold (`**`), italic (`*`), strikethrough (`~~`), and inline code
 * (`` ` ``). Strikethrough is GFM. Inline code takes absolute precedence
 * — no other tokenization happens inside a code span. HTML character
 * references (`&amp;`, `&#228;`) display as the character they stand for
 * ([HtmlEntities]); the file keeps the reference as written. Wiki links
 * (`[[Name]]`, [WikiLink]) keep every character in the display text (the
 * web view hides a resolved link's brackets off the caret row) and are marked for the
 * renderer to link.
 *
 * The tokenizer also produces the column maps the editor needs to keep the
 * cursor consistent between the rendered (markers stripped) text and the
 * underlying markdown:
 *
 *   - [TokenizedLine.displayText]: the visible text, with all marker chars
 *     removed.
 *   - [TokenizedLine.modelToDom]: model column → display column. Marker
 *     chars collapse to the display column on the side they sit closest
 *     to (open markers map to the column where their content starts;
 *     close markers map to the column where their content ends).
 *   - [TokenizedLine.domToModel]: display column → model column. Each
 *     visible character maps back to its source position; the entry one
 *     past the displayed length maps to the line length so caret-after-
 *     last-char round-trips cleanly.
 *   - [TokenizedLine.markerCols]: set of model columns whose char is a
 *     marker. The cursor must never come to rest on one of these — they
 *     are skipped past by the editor's caret-snap logic.
 *
 * commonMain — no DOM, no platform UI. Side-effect free.
 */

package se.soderbjorn.lunarbor.data

/**
 * One of the four inline markdown styles understood by the WYSIWYG editor.
 *
 * The `entries` order encodes the precedence used when more than one
 * style could open at a given position: inline code beats everything,
 * bold (longer marker) beats italic (shorter marker), and strikethrough
 * has a unique marker so it slots in between without ambiguity.
 *
 * @property openMarker Literal characters that open a span of this style.
 * @property closeMarker Literal characters that close a span of this style.
 */
enum class InlineStyle(val openMarker: String, val closeMarker: String) {
    /** Inline code (`` `…` ``) — opaque: no further tokenization inside. */
    INLINE_CODE("`", "`"),

    /** Bold (`**…**`). Checked before italic so `**` is not parsed as `*`+`*`. */
    BOLD("**", "**"),

    /** Strikethrough (`~~…~~`). GFM extension. */
    STRIKETHROUGH("~~", "~~"),

    /** Italic (`*…*`). Lowest priority among inline markers. */
    ITALIC("*", "*"),
}

/**
 * One contiguous span of text with a uniform set of styles. Produced by
 * [InlineMarkdownTokenizer.tokenize] in source order; concatenating each
 * run's [text] yields [TokenizedLine.displayText].
 *
 * @property text The visible characters of this run (no marker chars).
 * @property styles The active inline styles at this position; empty for
 *   a plain run with no formatting.
 * @property modelStart Inclusive model column where this run's first
 *   visible character lives in the source line.
 * @property modelEnd Exclusive model column one past the last visible
 *   character. `modelEnd - modelStart == text.length`, except for image
 *   runs and character-reference runs: a reference such as `&amp;` is a
 *   run of its own whose [text] is the decoded character (`&`) while its
 *   model span covers the whole reference.
 */
data class StyledRun(
    val text: String,
    val styles: Set<InlineStyle>,
    val modelStart: Int,
    val modelEnd: Int,
    /**
     * When non-null, this run is the visible label of a markdown inline
     * link `[label](href)`. The label characters live in [text]; the
     * surrounding `[`, `]`, `(`, href, and `)` columns are folded into
     * [TokenizedLine.markerCols] so the rendered text shows just the
     * label and the cursor skips past the link's syntax.
     *
     * Renderers should style link runs distinctively (color/underline)
     * and may use [linkHref] as a click target — for read-only viewers
     * (the Starred bookmarks modal) clicking can navigate to the URL.
     */
    val linkHref: String? = null,
    /**
     * When `true`, this run is a hashtag of the form `#name` (e.g.
     * `#test`, `#test-me`). The `#` and the name characters are all
     * visible — none collapse into [TokenizedLine.markerCols] — so the
     * caret moves through tag text like normal characters. Renderers are
     * expected to draw a distinguishing decoration around the run (a
     * rectangle in the accent color).
     *
     * The tag's [text] always starts with `#` and contains the full
     * matched name. Tags can co-occur with inline [styles] (a tag inside
     * `**…**` carries [InlineStyle.BOLD] and remains a tag).
     */
    val isTag: Boolean = false,
    /**
     * When non-null, this run is the placeholder for a markdown inline
     * image `![alt](src)`. Image runs always carry empty [text] — the
     * entire source span (from `!` through the closing `)`) is folded
     * into [TokenizedLine.markerCols] so the caret skips past the image
     * just as it skips past style markers. The renderer is expected to
     * emit a replacement element (e.g. an `<img>`) when it encounters
     * a run whose [imageSrc] is non-null.
     *
     * The source path is stored verbatim — a bare file name for images
     * pasted by Lunarbor (`Foo.png`, relative to the row's folder), a
     * vault-rooted `/…` path for images picked from elsewhere, or whatever
     * the user typed; `ImagePaths.resolve` maps it to a vault file.
     * Angle-bracket wrapping for paths with spaces (e.g. `<My pic.png>`)
     * is supported on read.
     *
     * [imageAlt] is the alt text minus any trailing `|<digits>` width
     * suffix. [imageWidthPx] is set when the alt ended with `|<digits>`
     * (Obsidian convention) — the trailing `|N` is stripped from
     * [imageAlt] in that case. Width is null when no suffix was present.
     */
    val imageSrc: String? = null,
    /** Alt text with any trailing `|<width>` stripped; null for non-image runs. */
    val imageAlt: String? = null,
    /** Image width in CSS pixels parsed from `|<digits>` at the end of the alt. */
    val imageWidthPx: Int? = null,
    /**
     * For image runs, the number of source characters the
     * `![…](…)` syntax occupies, starting at [modelStart]. The
     * renderer attaches this to the image span as a `data-*`
     * attribute so click-to-cursor mapping can step *past* the
     * image (treating it as one atomic glyph) rather than collapsing
     * to the same model column as a click before it.
     */
    val imageSourceLen: Int? = null,
    /**
     * When non-null, this run is a wiki link `[[…]]` ([WikiLink]) and
     * this is the name it refers to ([WikiLink.nameOf]). Like a tag, every
     * character — brackets included — is visible and nothing folds into
     * [TokenizedLine.markerCols], so the text reads and edits as written.
     * Renderers draw it as a link when the name resolves to exactly one
     * vault target, and as plain text otherwise.
     */
    val wikiName: String? = null,
    /**
     * When `true`, this run is a search node's query `{{search: …}}`
     * ([SearchNode]). Like a tag, every character — braces included — is
     * visible and nothing folds into [TokenizedLine.markerCols], so the
     * query reads and edits as written; renderers draw it as a chip.
     */
    val isSearchQuery: Boolean = false,
)

/**
 * The complete tokenization of one line. Carries the runs (for rendering)
 * and the column maps (for translating between rendered DOM positions and
 * underlying model columns).
 *
 * @property runs Source-ordered styled runs. Empty when the input was
 *   empty or contained only marker chars.
 * @property displayText Concatenation of [runs]' text — what the editor
 *   actually renders inside the row.
 * @property domToModel Map from display column (0..displayText.length) to
 *   model column. Length is `displayText.length + 1`.
 * @property modelToDom Map from model column (0..inputLength) to display
 *   column. Length is `inputLength + 1`. Marker chars collapse to a
 *   neighboring display column.
 * @property markerCols Model columns whose source character is a marker
 *   (e.g. the `*` chars of `**bold**`). The editor skips these when
 *   moving the cursor so the caret never lands inside a marker pair.
 */
class TokenizedLine internal constructor(
    val runs: List<StyledRun>,
    val displayText: String,
    val domToModel: IntArray,
    val modelToDom: IntArray,
    val markerCols: Set<Int>,
) {
    /**
     * Returns the styles that enclose [modelCol]. If the cursor is at a
     * boundary between two runs the returned set is the intersection (so
     * a caret between bold and plain text reports plain).
     */
    fun stylesAt(modelCol: Int): Set<InlineStyle> {
        val run = runs.firstOrNull { modelCol > it.modelStart && modelCol < it.modelEnd }
            ?: return emptySet()
        return run.styles
    }

    /**
     * Returns the styles common to *every* character in `[startCol, endCol)`.
     * Used to decide which inline-style menu items show as currently-active
     * when the user has a non-empty selection.
     */
    fun stylesAcross(startCol: Int, endCol: Int): Set<InlineStyle> {
        if (endCol <= startCol) return stylesAt(startCol)
        var common: Set<InlineStyle>? = null
        var col = startCol
        while (col < endCol) {
            if (col in markerCols) { col++; continue }
            val here = runs.firstOrNull { col >= it.modelStart && col < it.modelEnd }?.styles ?: emptySet()
            common = if (common == null) here else common.intersect(here)
            if (common.isEmpty()) return emptySet()
            col++
        }
        return common ?: emptySet()
    }
}

/**
 * Splits a line of markdown into styled runs and produces DOM↔model
 * column maps. The companion to the WYSIWYG renderer; everything here is
 * pure and easily unit-tested.
 */
object InlineMarkdownTokenizer {
    /**
     * Tokenize [text]. Returns a [TokenizedLine] suitable for both
     * rendering and cursor-mapping. The input is treated as a single
     * line; `\n` is not handled specially.
     */
    fun tokenize(text: String): TokenizedLine {
        val parser = Parser(text)
        parser.parse()
        return TokenizedLine(
            runs = parser.runs,
            displayText = parser.displayBuilder.toString(),
            domToModel = parser.domToModel.toIntArray(),
            modelToDom = parser.modelToDom,
            markerCols = parser.markerCols,
        )
    }

    /**
     * The start column of an HTML character reference (`&amp;`) that ends
     * exactly at [end] in [text], or `null`. Used by the editor's
     * backspace so one press deletes the whole reference, which displays
     * as a single character.
     */
    fun entityStartBefore(text: String, end: Int): Int? {
        val from = text.lastIndexOf('&', end - 1)
        if (from < 0) return null
        return from.takeIf { HtmlEntities.decodeAt(text, it)?.second == end }
    }

    /**
     * If a markdown image `![alt](src)` starts at [pos] in [text],
     * return the position one past the closing `)` (i.e. the exclusive
     * source end of the image syntax). Returns `null` when the
     * substring at [pos] does not start a well-formed image.
     *
     * Used by the editor's `backspace` to detect "cursor is just after
     * an image" so a single press can delete the whole syntax atomically
     * rather than the dangling `)` that a one-char delete would leave
     * behind.
     */
    fun imageEndAt(text: String, pos: Int): Int? {
        if (pos < 0 || pos >= text.length) return null
        if (text[pos] != '!') return null
        if (pos + 1 >= text.length || text[pos + 1] != '[') return null
        val syntax = parseLinkSyntaxAtTopLevel(text, pos + 1) ?: return null
        return syntax.closingParen + 1
    }
}

/**
 * If a bare `http://` / `https://` URL starts at [pos] in [text], the
 * position one past its end; otherwise `null`. The URL must start a word
 * (at the line start or after a character that is not a letter, digit,
 * `/`, `(` of a Markdown link destination, `<` or `"`), runs to the next
 * whitespace or `<` / `>`, and gives up trailing sentence punctuation
 * (`.,;:!?'"`) and a closing `)` it has no `(` for — so `(see
 * https://x.org).` links just `https://x.org`.
 *
 * Used by the tokenizer (a bare URL is a link to itself) and by
 * [LinkSource.at].
 */
internal fun bareUrlEndAt(text: String, pos: Int): Int? {
    val scheme = when {
        text.startsWith("https://", pos) -> 8
        text.startsWith("http://", pos) -> 7
        else -> return null
    }
    if (pos > 0) {
        val before = text[pos - 1]
        if (before.isLetterOrDigit() || before == '/' || before == '<' || before == '"' ||
            (before == '(' && pos >= 2 && text[pos - 2] == ']')
        ) return null
    }
    var end = pos + scheme
    while (end < text.length && !text[end].isWhitespace() && text[end] != '<' && text[end] != '>') end++
    while (end > pos + scheme) {
        val last = text[end - 1]
        val drop = last in ".,;:!?'\"" ||
            (last == ')' && text.substring(pos, end).count { it == '(' } < text.substring(pos, end).count { it == ')' })
        if (!drop) break
        end--
    }
    return if (end > pos + scheme) end else null
}

/** File-private helper used by both the [Parser] class and the
 *  [InlineMarkdownTokenizer.imageEndAt] entry point. Pure structural
 *  parse of `[label](dest)` starting at the `[` at [bracketPos]. */
internal data class TopLevelLinkSyntax(val labelEnd: Int, val closingParen: Int, val destination: String)

internal fun parseLinkSyntaxAtTopLevel(text: String, bracketPos: Int): TopLevelLinkSyntax? {
    if (bracketPos >= text.length || text[bracketPos] != '[') return null
    var i = bracketPos + 1
    while (i < text.length) {
        val c = text[i]
        if (c == '\\' && i + 1 < text.length) { i += 2; continue }
        if (c == ']') break
        if (c == '[') return null
        i++
    }
    if (i >= text.length || text[i] != ']') return null
    val labelEnd = i
    if (labelEnd + 1 >= text.length || text[labelEnd + 1] != '(') return null
    val urlOpen = labelEnd + 2
    val urlContentStart: Int
    val urlContentEnd: Int
    val closingParen: Int
    if (urlOpen < text.length && text[urlOpen] == '<') {
        val angleEnd = text.indexOf('>', urlOpen + 1)
        if (angleEnd < 0) return null
        if (angleEnd + 1 >= text.length || text[angleEnd + 1] != ')') return null
        urlContentStart = urlOpen + 1
        urlContentEnd = angleEnd
        closingParen = angleEnd + 1
    } else {
        val parenEnd = text.indexOf(')', urlOpen)
        if (parenEnd < 0) return null
        urlContentStart = urlOpen
        urlContentEnd = parenEnd
        closingParen = parenEnd
    }
    return TopLevelLinkSyntax(
        labelEnd = labelEnd,
        closingParen = closingParen,
        destination = text.substring(urlContentStart, urlContentEnd),
    )
}

private class Parser(val text: String) {
    val runs = mutableListOf<StyledRun>()
    val displayBuilder = StringBuilder()
    val domToModel = ArrayList<Int>(text.length + 1)
    val modelToDom = IntArray(text.length + 1)
    val markerCols = HashSet<Int>()

    private val activeStyles = ArrayDeque<InlineStyle>()
    private var pos: Int = 0
    private var runStart: Int = 0

    fun parse() {
        while (pos < text.length) {
            // Inline code is opaque: only its closing backtick can break us out.
            if (InlineStyle.INLINE_CODE in activeStyles) {
                if (text[pos] == '`') {
                    flushRun()
                    markMarker(pos, 1)
                    activeStyles.removeLast()
                    pos++
                    runStart = pos
                    continue
                }
                appendLiteralChar()
                continue
            }

            // 0a. Markdown inline image `![alt](src)` — checked before the
            //     link branch so the leading `!` is consumed by the image
            //     path rather than falling through as a literal char.
            // A search node's `{{search: …}}`: one verbatim run, so no
            // Markdown inside the query (a `*` in `#proj*`) opens a style.
            if (text[pos] == '{' && tryConsumeSearchQuery()) continue

            if (text[pos] == '!' && pos + 1 < text.length && text[pos + 1] == '[' && tryConsumeImage()) continue

            // 0. Markdown inline link `[label](href)` — checked before
            //    style openers so a `[` that begins a link is consumed by
            //    the link path instead of becoming a literal `[`. Plain
            //    `[`/`]` characters that don't form a link fall through.
            if (text[pos] == '[' && tryConsumeWikiLink()) continue
            if (text[pos] == '[' && tryConsumeLink()) continue

            // 0b. Hashtag `#name` — preceded by start-of-string or
            //     whitespace, then `#` followed by a letter and any number
            //     of letters/digits/`_`/`-`. Emits a single tag run with
            //     all chars visible; nothing is folded into markerCols.
            //     Comes before opener detection so a `*` after the tag
            //     still gets a chance to open italic / bold normally.
            if (text[pos] == '#' && tryConsumeTag()) continue

            // 0d. Bare URL `https://…` ([bareUrlEndAt]) — a link to
            //     itself, all characters visible, so `*` or `_` inside it
            //     never opens a style.
            if (text[pos] == 'h' && tryConsumeBareUrl()) continue

            // 0c. HTML character reference (`&amp;`, `&#228;`) — shown as
            //     the character it stands for; the rest of its source
            //     folds into markerCols. Not inside inline code (above).
            if (text[pos] == '&' && tryConsumeEntity()) continue

            // 1. Try to open a new style first — opening takes precedence over
            //    closing so that e.g. `*it **bo** it*` opens BOLD inside ITALIC
            //    instead of mistakenly treating the first `*` of `**` as the
            //    italic close.
            val opener = findOpener()
            if (opener != null) {
                flushRun()
                markMarker(pos, opener.openMarker.length)
                activeStyles.addLast(opener)
                pos += opener.openMarker.length
                runStart = pos
                continue
            }

            // 2. Otherwise try to close the innermost active style.
            val closing = findClosingMarker()
            if (closing != null) {
                flushRun()
                markMarker(pos, closing.markerLength)
                activeStyles.removeLast()
                pos += closing.markerLength
                runStart = pos
                continue
            }

            // 3. Plain content character in the current style scope.
            appendLiteralChar()
        }
        flushRun()
        // Trailing position one past the end maps to the end of display.
        modelToDom[text.length] = displayBuilder.length
        domToModel.add(text.length)
    }

    private fun appendLiteralChar() {
        modelToDom[pos] = displayBuilder.length
        domToModel.add(pos)
        displayBuilder.append(text[pos])
        pos++
    }

    /**
     * Consumes the character reference at [pos] ([HtmlEntities]) as a run
     * of its own: its decoded text is displayed, the `&` column maps to
     * it, and the columns after the `&` up to and including `;` become
     * marker columns, so the caret steps over the whole reference.
     * Returns `false` (leaving [pos] alone) when no known reference
     * starts here.
     */
    private fun tryConsumeEntity(): Boolean {
        val (decoded, end) = HtmlEntities.decodeAt(text, pos) ?: return false
        flushRun()
        val start = pos
        modelToDom[start] = displayBuilder.length
        for (ch in decoded) {
            domToModel.add(start)
            displayBuilder.append(ch)
        }
        for (i in start + 1 until end) {
            markerCols += i
            modelToDom[i] = displayBuilder.length
        }
        runs += StyledRun(text = decoded, styles = activeStyles.toSet(), modelStart = start, modelEnd = end)
        pos = end
        runStart = pos
        return true
    }

    private fun flushRun() {
        if (pos > runStart) {
            runs += StyledRun(
                text = text.substring(runStart, pos),
                styles = activeStyles.toSet(),
                modelStart = runStart,
                modelEnd = pos,
            )
        }
        runStart = pos
    }

    private fun markMarker(start: Int, len: Int) {
        for (i in start until start + len) {
            markerCols += i
            modelToDom[i] = displayBuilder.length
        }
    }

    private data class Closing(val style: InlineStyle, val markerLength: Int)

    /**
     * Try to close the innermost (last-opened) active style at [pos]. Only
     * the innermost is considered — strict LIFO matching, like CommonMark.
     */
    private fun findClosingMarker(): Closing? {
        if (activeStyles.isEmpty()) return null
        val innermost = activeStyles.last()
        val closer = innermost.closeMarker
        return if (text.regionMatches(pos, closer, 0, closer.length)) {
            Closing(innermost, closer.length)
        } else null
    }

    /**
     * Try to open a new style at [pos]. Iterates [InlineStyle.entries] in
     * declared order, which encodes the precedence rules. A candidate is
     * accepted only if a matching close marker exists later in the line.
     */
    private fun findOpener(): InlineStyle? {
        for (style in InlineStyle.entries) {
            if (style in activeStyles) continue
            val opener = style.openMarker
            if (!text.regionMatches(pos, opener, 0, opener.length)) continue
            // Bold-vs-italic ambiguity: at `**` we always prefer bold over italic.
            // Enum order already enforces that (BOLD precedes ITALIC), but we also
            // need to ensure ITALIC doesn't open at a `**` that could be bold.
            if (style == InlineStyle.ITALIC) {
                if (pos + 1 < text.length && text[pos + 1] == '*') continue
            }
            if (hasMatchingCloser(pos + opener.length, style)) return style
        }
        return null
    }

    /**
     * Try to consume a markdown inline link starting at [pos]. Returns
     * `true` and advances [pos] past the closing `)` when the syntax
     * matches `[label](href)` — with optional `<…>` wrapping for [href];
     * returns `false` and leaves [pos] alone otherwise (so the `[` falls
     * through to literal handling).
     *
     * On success:
     * - the `[`, `]`, `(`, every char of href, and `)` are added to
     *   [markerCols] (and their [modelToDom] entries collapse to the
     *   surrounding display column);
     * - the label characters are appended to [displayBuilder] and a single
     *   [StyledRun] is emitted with the current [activeStyles] plus a
     *   non-null [StyledRun.linkHref].
     *
     * Backslash escapes inside the label (`\]`, `\[`) are honored. The
     * label may contain arbitrary inline characters but is NOT recursively
     * tokenized — nested formatting inside link labels is intentionally
     * out of scope to keep column-map invariants simple.
     */
    private fun tryConsumeLink(): Boolean {
        if (pos >= text.length || text[pos] != '[') return false
        val parsed = parseLinkSyntaxAt(pos) ?: return false
        // Commit. Close out any pending plain run first.
        flushRun()
        // Mark `[` as marker.
        markMarker(pos, 1)
        // Emit each label char as a visible character.
        val labelStart = pos + 1
        runStart = labelStart
        val styles = activeStyles.toSet()
        pos = labelStart
        while (pos < parsed.labelEnd) {
            appendLiteralChar()
        }
        // Emit the link run with the captured href.
        if (pos > runStart) {
            runs += StyledRun(
                text = text.substring(runStart, pos),
                styles = styles,
                modelStart = runStart,
                modelEnd = pos,
                linkHref = parsed.destination,
            )
        }
        runStart = pos
        // Mark `]`, `(`, optional `<`, href chars, optional `>`, `)` as markers.
        markMarker(parsed.labelEnd, parsed.closingParen + 1 - parsed.labelEnd)
        pos = parsed.closingParen + 1
        runStart = pos
        return true
    }

    /**
     * Try to consume a markdown inline image starting at [pos]. Expects
     * `pos` to point at the leading `!` (with `[` immediately after).
     * Returns `true` and advances `pos` past the closing `)` when the
     * syntax matches `![alt](src)`; returns `false` otherwise so the `!`
     * falls through as a literal character.
     *
     * Image runs differ from link runs in two ways:
     *
     * 1. **All source chars are markers.** The entire span from `!`
     *    through `)` is added to [markerCols] and contributes zero
     *    display characters. The renderer is expected to emit a
     *    replacement element (e.g. `<img>`) when it sees an image run.
     * 2. **Alt text may carry a width suffix.** Obsidian uses
     *    `![alt|300](src)` for sizing; this parser strips the trailing
     *    `|<digits>` and exposes it as [StyledRun.imageWidthPx]. If the
     *    suffix is absent or non-numeric, the whole alt is kept verbatim
     *    in [StyledRun.imageAlt].
     *
     * The fully-empty form `![]()` is rejected (returns `false`).
     */
    private fun tryConsumeImage(): Boolean {
        if (pos >= text.length || text[pos] != '!') return false
        if (pos + 1 >= text.length || text[pos + 1] != '[') return false
        val bracketStart = pos + 1
        val parsed = parseLinkSyntaxAt(bracketStart) ?: return false
        val rawAlt = text.substring(bracketStart + 1, parsed.labelEnd)
        // Reject the fully-empty `![]()` form so a stray `!` followed by
        // empty brackets doesn't render as a placeholder image.
        if (rawAlt.isEmpty() && parsed.destination.isEmpty()) return false
        val (alt, width) = splitAltAndWidth(rawAlt)
        // Commit.
        flushRun()
        val totalLen = parsed.closingParen + 1 - pos
        // Image run carries no visible characters — pin modelStart at
        // the leading `!` so highlight tooling can map back to source.
        runs += StyledRun(
            text = "",
            styles = activeStyles.toSet(),
            modelStart = pos,
            modelEnd = pos,
            imageSrc = parsed.destination,
            imageAlt = alt,
            imageWidthPx = width,
            imageSourceLen = totalLen,
        )
        // Fold every source char of the image into markerCols.
        markMarker(pos, totalLen)
        pos += totalLen
        runStart = pos
        return true
    }

    /** Parsed shape of a `[label](dest)` form — typealias of the
     *  file-private [TopLevelLinkSyntax] so the Parser can reuse the
     *  shared [parseLinkSyntaxAtTopLevel] helper without duplicating
     *  its parsing logic. */
    private fun parseLinkSyntaxAt(bracketPos: Int): TopLevelLinkSyntax? =
        parseLinkSyntaxAtTopLevel(text, bracketPos)

    /**
     * Split `"alt|300"` into `("alt", 300)` and `"plain"` into
     * `("plain", null)`. Only a trailing `\|\d+` is honored; anything
     * else leaves the alt verbatim and width null.
     */
    private fun splitAltAndWidth(rawAlt: String): Pair<String, Int?> {
        val pipe = rawAlt.lastIndexOf('|')
        if (pipe < 0 || pipe == rawAlt.length - 1) return rawAlt to null
        val tail = rawAlt.substring(pipe + 1)
        if (tail.isEmpty() || !tail.all { it.isDigit() }) return rawAlt to null
        val width = tail.toIntOrNull() ?: return rawAlt to null
        return rawAlt.substring(0, pipe) to width
    }

    /**
     * Try to consume a hashtag starting at [pos]. A tag is `#` followed
     * by a letter and any number of `[A-Za-z0-9_-]` chars. The `#` must
     * sit on a word boundary — at position 0, or preceded by a character
     * that is not itself part of a tag name (anything other than
     * `[A-Za-z0-9_-]`). That way a stray `#` inside a word (e.g.
     * `id#42`) is left as a literal, while `(#foo)` and `**#foo**` both
     * still produce a tag.
     *
     * On success: flushes any pending plain run, emits one [StyledRun]
     * with [StyledRun.isTag] = true containing the entire `#name`,
     * advances [pos] past the tag name, and returns `true`. Inherits the
     * current [activeStyles] so a tag inside bold or italic still carries
     * those styles.
     *
     * On failure (not enough text after `#`, first char is not a letter,
     * preceding char is itself a tag-name char): returns `false` and
     * leaves [pos] alone so the `#` falls through to literal handling.
     */
    private fun tryConsumeTag(): Boolean {
        if (pos >= text.length || text[pos] != '#') return false
        if (pos > 0 && isTagNameChar(text[pos - 1])) return false
        val nameStart = pos + 1
        if (nameStart >= text.length) return false
        if (!text[nameStart].isLetter()) return false
        var end = nameStart + 1
        while (end < text.length && isTagNameChar(text[end])) end++
        flushRun()
        val tagStart = pos
        val styles = activeStyles.toSet()
        while (pos < end) {
            // All tag characters — including the leading `#` — are visible.
            // Don't route through `appendLiteralChar` because we want them
            // emitted as their own dedicated run rather than being merged
            // into a surrounding plain run by `flushRun`.
            modelToDom[pos] = displayBuilder.length
            domToModel.add(pos)
            displayBuilder.append(text[pos])
            pos++
        }
        runs += StyledRun(
            text = text.substring(tagStart, pos),
            styles = styles,
            modelStart = tagStart,
            modelEnd = pos,
            isTag = true,
        )
        runStart = pos
        return true
    }

    /**
     * Try to consume a bare URL ([bareUrlEndAt]) at [pos]. On success
     * emits one run whose [StyledRun.linkHref] is the URL itself, with
     * every character visible (as for a tag), inheriting [activeStyles],
     * and returns `true`; otherwise leaves [pos] alone.
     */
    private fun tryConsumeBareUrl(): Boolean {
        val end = bareUrlEndAt(text, pos) ?: return false
        flushRun()
        val start = pos
        while (pos < end) {
            modelToDom[pos] = displayBuilder.length
            domToModel.add(pos)
            displayBuilder.append(text[pos])
            pos++
        }
        val url = text.substring(start, end)
        runs += StyledRun(text = url, styles = activeStyles.toSet(), modelStart = start, modelEnd = end, linkHref = url)
        runStart = pos
        return true
    }

    /**
     * Try to consume a wiki link `[[…]]` ([WikiLink.endAt]) at [pos].
     * Checked before [tryConsumeLink] so `[[Name]]` is never read as a
     * bracketed `[Name]`. On success emits one run carrying
     * [StyledRun.wikiName] with every character visible (as for a tag),
     * inheriting [activeStyles], and returns `true`; otherwise leaves
     * [pos] alone and returns `false`.
     */
    private fun tryConsumeWikiLink(): Boolean {
        val end = WikiLink.endAt(text, pos) ?: return false
        flushRun()
        val start = pos
        while (pos < end) {
            modelToDom[pos] = displayBuilder.length
            domToModel.add(pos)
            displayBuilder.append(text[pos])
            pos++
        }
        runs += StyledRun(
            text = text.substring(start, end),
            styles = activeStyles.toSet(),
            modelStart = start,
            modelEnd = end,
            wikiName = WikiLink.nameOf(text.substring(start + 2, end - 2)),
        )
        runStart = pos
        return true
    }

    /**
     * Try to consume a search node's query `{{search: …}}` ([SearchNode])
     * at [pos]. On success emits one run with [StyledRun.isSearchQuery],
     * every character visible, and returns `true`; otherwise leaves [pos]
     * alone.
     */
    private fun tryConsumeSearchQuery(): Boolean {
        val range = SearchNode.rangeIn(text, pos)?.takeIf { it.first == pos } ?: return false
        flushRun()
        val start = pos
        while (pos <= range.last) {
            modelToDom[pos] = displayBuilder.length
            domToModel.add(pos)
            displayBuilder.append(text[pos])
            pos++
        }
        runs += StyledRun(
            text = text.substring(start, pos),
            styles = activeStyles.toSet(),
            modelStart = start,
            modelEnd = pos,
            isSearchQuery = true,
        )
        runStart = pos
        return true
    }

    /** Tag-name predicate used by [tryConsumeTag] for both the trailing
     *  scan and the preceding word-boundary check. */
    private fun isTagNameChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '-'

    /**
     * Lookahead: does a valid closer for [style] appear at or after [from]?
     * For italic (`*`) we skip over `**` so a bold pair isn't mistaken for
     * an italic close. For inline code, any later backtick will do.
     */
    private fun hasMatchingCloser(from: Int, style: InlineStyle): Boolean {
        val closer = style.closeMarker
        var i = from
        while (i <= text.length - closer.length) {
            if (style == InlineStyle.ITALIC) {
                if (text[i] == '*' && i + 1 < text.length && text[i + 1] == '*') {
                    i += 2
                    continue
                }
            }
            if (text.regionMatches(i, closer, 0, closer.length)) return true
            i++
        }
        return false
    }
}
