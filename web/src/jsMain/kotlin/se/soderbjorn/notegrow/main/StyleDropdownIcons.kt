/*
 * StyleDropdownIcons.kt (jsMain)
 * ------------------------------
 * Inline SVG glyphs used by the Style toolbar button and its dropdown
 * menu items. Kept apart from `AppShell.kt` so the (already large) shell
 * file doesn't grow by another dozen icon strings.
 *
 * All icons follow the same conventions: 16×16 viewBox so they sit nicely
 * inside the menu's `.notegrow-style-icon` slot, `currentColor` stroke /
 * fill so they tint with the surrounding theme.
 */

package se.soderbjorn.notegrow.main

internal object StyleDropdownIcons {
    /** Toolbar trigger: a paragraph-style "A" glyph. */
    const val TOOLBAR_STYLE: String =
        "<svg viewBox=\"0 0 24 24\" width=\"14\" height=\"14\" fill=\"none\" " +
            "stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" " +
            "stroke-linejoin=\"round\">" +
            "<polyline points=\"6,18 12,4 18,18\"/>" +
            "<line x1=\"8\" y1=\"13\" x2=\"16\" y2=\"13\"/></svg>"

    /** Heading 1 — bold "H1". */
    const val H1: String =
        "<svg viewBox=\"0 0 16 16\" width=\"14\" height=\"14\" fill=\"currentColor\">" +
            "<text x=\"1\" y=\"12\" font-size=\"11\" font-weight=\"700\" font-family=\"sans-serif\">H1</text></svg>"

    /** Heading 2. */
    const val H2: String =
        "<svg viewBox=\"0 0 16 16\" width=\"14\" height=\"14\" fill=\"currentColor\">" +
            "<text x=\"1\" y=\"12\" font-size=\"11\" font-weight=\"700\" font-family=\"sans-serif\">H2</text></svg>"

    /** Heading 3. */
    const val H3: String =
        "<svg viewBox=\"0 0 16 16\" width=\"14\" height=\"14\" fill=\"currentColor\">" +
            "<text x=\"1\" y=\"12\" font-size=\"11\" font-weight=\"700\" font-family=\"sans-serif\">H3</text></svg>"

    /** Block quote. */
    const val QUOTE: String =
        "<svg viewBox=\"0 0 16 16\" width=\"14\" height=\"14\" fill=\"currentColor\">" +
            "<path d=\"M3 4h2v6c0 1.1-.9 2-2 2v-1c.55 0 1-.45 1-1H3V4zm6 0h2v6c0 1.1-.9 2-2 2v-1c.55 0 1-.45 1-1H9V4z\"/></svg>"

    /** Bold — bold "B". */
    const val BOLD: String =
        "<svg viewBox=\"0 0 16 16\" width=\"14\" height=\"14\" fill=\"currentColor\">" +
            "<text x=\"3\" y=\"12\" font-size=\"11\" font-weight=\"800\" font-family=\"serif\">B</text></svg>"

    /** Italic — italicised "I". */
    const val ITALIC: String =
        "<svg viewBox=\"0 0 16 16\" width=\"14\" height=\"14\" fill=\"currentColor\">" +
            "<text x=\"4\" y=\"12\" font-size=\"11\" font-style=\"italic\" font-family=\"serif\">I</text></svg>"

    /** Underline — "U" with a baseline rule. */
    const val UNDERLINE: String =
        "<svg viewBox=\"0 0 16 16\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.5\" stroke-linecap=\"round\">" +
            "<path d=\"M5 3v6a3 3 0 0 0 6 0V3\" fill=\"none\"/>" +
            "<line x1=\"4\" y1=\"13\" x2=\"12\" y2=\"13\"/></svg>"

    /** Strikethrough — "S" with a centred line. */
    const val STRIKETHROUGH: String =
        "<svg viewBox=\"0 0 16 16\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.5\" stroke-linecap=\"round\">" +
            "<path d=\"M11 5a3 3 0 0 0-6 1c0 .5.2 1 .5 1.4M5 11a3 3 0 0 0 6 -1\"/>" +
            "<line x1=\"3\" y1=\"8\" x2=\"13\" y2=\"8\"/></svg>"

    /** Inline code — angle brackets. */
    const val INLINE_CODE: String =
        "<svg viewBox=\"0 0 16 16\" width=\"14\" height=\"14\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
            "<polyline points=\"6,5 2,8 6,11\"/>" +
            "<polyline points=\"10,5 14,8 10,11\"/></svg>"

    /** Checkmark used to indicate currently-active styles in the menu. */
    const val CHECK: String =
        "<svg viewBox=\"0 0 16 16\" width=\"12\" height=\"12\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" +
            "<polyline points=\"3,8 7,12 13,4\"/></svg>"
}
