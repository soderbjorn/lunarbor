/*
 * EditorStyle.kt (jsMain)
 * -----------------------
 * Visual constants for the contenteditable note editor. Bundled into a
 * single small value type so the painter and the screen builder can pass
 * it around without ten parameters each.
 *
 * No DOM access here — this is just data. Pixel-level layout (caret,
 * selection rectangles, wrap math) is no longer our concern; the browser
 * owns it via `contenteditable` + `white-space: pre-wrap`.
 */

package se.soderbjorn.notegrow.main

/**
 * Visual constants for the editor's web view.
 *
 * @property fontFamily CSS font-family stack used everywhere in the editor.
 *   Defaults to the OS UI font so the note reads like native prose rather
 *   than code.
 * @property fontSize Pixel font size for body text.
 * @property lineHeightPx Per-row pixel height; matches [fontSize] plus
 *   leading. Used as a CSS line-height value for both bullet and plain
 *   rows so they share a uniform baseline.
 * @property indentStepPx Horizontal pixel offset added per nesting level.
 *   A bullet at indent column `c` shifts right by `(c / TAB_SIZE) *
 *   indentStepPx` pixels via CSS `padding-left`. Mirrors the legacy
 *   `EXTRA_INDENT_PX_PER_LEVEL` constant so visual indentation stays
 *   stable across the rewrite.
 * @property editorPaddingTopPx Inner padding above the first rendered row.
 * @property editorPaddingBottomPx Inner padding below the last rendered row.
 * @property editorPaddingLeftPx Inner padding to the left of every row.
 *   Sized generously so the (24×24) disclosure chevron and its 22px gap
 *   fit to the left of even root-level bullets — the chevron's left edge
 *   sits at `editorPaddingLeftPx - 34`.
 * @property editorPaddingRightPx Inner padding to the right of every row.
 */
data class EditorStyle(
    val fontFamily: String =
        "system-ui, -apple-system, \"Segoe UI\", Roboto, \"Helvetica Neue\", Arial, sans-serif",
    val fontSize: Int = 17,
    val lineHeightPx: Int = 25,
    val indentStepPx: Int = 14,
    val editorPaddingTopPx: Int = 12,
    val editorPaddingBottomPx: Int = 12,
    val editorPaddingLeftPx: Int = 40,
    val editorPaddingRightPx: Int = 12,
)
