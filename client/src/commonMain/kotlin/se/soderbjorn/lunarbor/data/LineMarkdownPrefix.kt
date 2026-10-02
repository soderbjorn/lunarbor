/*
 * LineMarkdownPrefix.kt
 * ---------------------
 * Detection and toggle helpers for the line-level markdown prefixes the
 * WYSIWYG editor recognises: `# `, `## `, `### ` (headings 1–3) and `> `
 * (block quote). These prefixes sit *after* any bullet marker (`* `) at
 * the start of a line, so on a typical bullet-rooted Lunarbor line they
 * appear at column [DocumentLayout.textStartCol].
 *
 * The renderer hides the prefix characters (the line just renders larger
 * / quoted) and the editor's caret-snap logic keeps the cursor from
 * landing inside them. The on-disk markdown still contains the prefix —
 * Lunarbor's storage format is plain CommonMark, so files round-trip
 * cleanly through any other markdown tool.
 *
 * commonMain — pure helpers, side-effect free.
 */

package se.soderbjorn.lunarbor.data

/**
 * The line-level markdown prefixes understood by the WYSIWYG editor.
 *
 * @property marker Literal characters (including the trailing space) that
 *   appear at the start of the editable text portion of a line.
 */
enum class LineStyle(val marker: String) {
    /** Heading 1 — `# `. Rendered with the largest size. */
    HEADING_1("# "),

    /** Heading 2 — `## `. */
    HEADING_2("## "),

    /** Heading 3 — `### `. */
    HEADING_3("### "),

    /** Heading 4 — `#### `. */
    HEADING_4("#### "),

    /** Heading 5 — `##### `. */
    HEADING_5("##### "),

    /** Heading 6 — `###### `. Smallest heading level (matches Obsidian/CommonMark). */
    HEADING_6("###### "),

    /** Block quote — `> `. Rendered with a left rule and italic body. */
    QUOTE("> "),
}

/**
 * Result of detecting a line-level prefix on a single line.
 *
 * @property style The detected style, or `null` when the line has no
 *   recognised prefix.
 * @property markerStart Model column where the marker starts. For a
 *   bullet line this equals [textStart]. For a non-bullet line it is `0`.
 *   Always equals [markerEnd] when [style] is `null`.
 * @property markerEnd Model column one past the marker. Equal to
 *   `markerStart + style.marker.length` when a style is present.
 */
data class LinePrefix(
    val style: LineStyle?,
    val markerStart: Int,
    val markerEnd: Int,
)

/**
 * Pure helpers for detecting, applying, toggling, and removing the
 * line-level prefixes defined by [LineStyle].
 */
object LineMarkdownPrefix {
    /**
     * Find the line-level prefix on [line]. The check starts at
     * [textStart] (typically the position right after a bullet's `* `
     * marker; pass `0` for non-bullet lines).
     *
     * Returns a [LinePrefix] describing what was found. Longer markers
     * are checked first so `### ` matches as Heading 3 rather than
     * Heading 1 on a `### Foo` line.
     */
    fun detect(line: String, textStart: Int = 0): LinePrefix {
        // Order matters: longer headings before shorter so `###### ` doesn't
        // match HEADING_1 on the leading `# `.
        val ordered = listOf(
            LineStyle.HEADING_6,
            LineStyle.HEADING_5,
            LineStyle.HEADING_4,
            LineStyle.HEADING_3,
            LineStyle.HEADING_2,
            LineStyle.HEADING_1,
            LineStyle.QUOTE,
        )
        for (style in ordered) {
            if (line.regionMatches(textStart, style.marker, 0, style.marker.length)) {
                return LinePrefix(style, textStart, textStart + style.marker.length)
            }
        }
        return LinePrefix(null, textStart, textStart)
    }

    /**
     * Returns [line] with [target]'s marker present at [textStart]. If a
     * different line-level prefix is already there it is replaced; if the
     * same one is present the line is returned unchanged.
     */
    fun apply(line: String, target: LineStyle, textStart: Int = 0): String {
        val current = detect(line, textStart)
        if (current.style == target) return line
        val without = if (current.style != null) {
            line.substring(0, textStart) + line.substring(current.markerEnd)
        } else line
        return without.substring(0, textStart) + target.marker + without.substring(textStart)
    }

    /**
     * Toggles [target] on [line]: removes it if currently present, applies
     * it (replacing any other prefix) if not.
     */
    fun toggle(line: String, target: LineStyle, textStart: Int = 0): String {
        val current = detect(line, textStart)
        return if (current.style == target) {
            line.substring(0, textStart) + line.substring(current.markerEnd)
        } else {
            apply(line, target, textStart)
        }
    }

    /**
     * Returns [line] with any line-level prefix removed. No-op when no
     * recognised prefix is present.
     */
    fun remove(line: String, textStart: Int = 0): String {
        val current = detect(line, textStart)
        if (current.style == null) return line
        return line.substring(0, textStart) + line.substring(current.markerEnd)
    }

    /**
     * Returns the marker length for [style], used by callers that need to
     * compute column offsets without instantiating a [LinePrefix].
     */
    fun markerLengthOf(style: LineStyle): Int = style.marker.length
}
