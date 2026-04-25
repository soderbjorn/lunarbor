/*
 * EditorStyle.kt (jsMain)
 * -----------------------
 * Static style constants used by the painter, hit-testing, and scroll
 * helpers. Bundled into a single small value type so the paint and input
 * helpers can pass it around without ten parameters each.
 *
 * No DOM access here — this is just data.
 */

package se.soderbjorn.notegrow.main

/**
 * Visual constants for the editor's web view.
 *
 * @property fontFamily CSS font-family stack used everywhere in the editor.
 * @property fontSize Pixel font size for body text.
 * @property lineHeightPx Per-row pixel height; matches [fontSize] plus
 *   leading.
 * @property editorPaddingPx Inner padding around the editor body.
 * @property headerPaddingPx Inner padding inside the sticky header.
 */
data class EditorStyle(
    val fontFamily: String = "ui-monospace, SFMono-Regular, Menlo, Consolas, monospace",
    val fontSize: Int = 14,
    val lineHeightPx: Int = 20,
    val editorPaddingPx: Int = 12,
    val headerPaddingPx: Int = 10,
)
