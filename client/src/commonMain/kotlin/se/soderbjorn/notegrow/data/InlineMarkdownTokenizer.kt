/*
 * InlineMarkdownTokenizer.kt
 * --------------------------
 * Pure inline-markdown tokenizer used by the WYSIWYG editor renderer to
 * split a single line of markdown into styled runs whose marker characters
 * are *not* part of the visible text. The five inline styles supported are
 * bold (`**`), italic (`*`), underline (`<u>...</u>`), strikethrough
 * (`~~`), and inline code (`` ` ``). Underline is HTML-in-markdown
 * (CommonMark allows raw HTML); strikethrough is GFM. Inline code takes
 * absolute precedence — no other tokenization happens inside a code span.
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

package se.soderbjorn.notegrow.data

/**
 * One of the five inline markdown styles understood by the WYSIWYG editor.
 *
 * The `entries` order encodes the precedence used when more than one
 * style could open at a given position: inline code beats everything,
 * bold (longer marker) beats italic (shorter marker), and underline /
 * strikethrough have unique markers so they slot in between without
 * ambiguity.
 *
 * @property openMarker Literal characters that open a span of this style.
 * @property closeMarker Literal characters that close a span of this style.
 */
enum class InlineStyle(val openMarker: String, val closeMarker: String) {
    /** Inline code (`` `…` ``) — opaque: no further tokenization inside. */
    INLINE_CODE("`", "`"),

    /** Bold (`**…**`). Checked before italic so `**` is not parsed as `*`+`*`. */
    BOLD("**", "**"),

    /** Underline (`<u>…</u>`). Raw HTML — not in CommonMark, but valid markdown. */
    UNDERLINE("<u>", "</u>"),

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
 *   character. `modelEnd - modelStart == text.length`.
 */
data class StyledRun(
    val text: String,
    val styles: Set<InlineStyle>,
    val modelStart: Int,
    val modelEnd: Int,
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
