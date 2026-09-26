/* TreeFactsHotkeysContent.kt (jsMain)
 *
 * Source of truth for treefacts's keyboard cheatsheet content. Builds a
 * [HotkeysModalSpec] consumed by the toolkit's [ToolkitHotkeysModal].
 *
 * Why hand-curated: cheatsheet entries are a UX surface — labels,
 * grouping, and ordering reflect what the maintainer wants users to
 * see, not the literal contents of the registry. Auto-derivation would
 * miss subtleties (which chord is "user-facing" vs "plumbing", which
 * Tab/Shift-Tab pair to bundle as one row, etc.).
 *
 * Mirrors the chord set wired in [MainScreen.handleKey],
 * [AppShell.installPaletteShortcut], and the toolkit's `StandardHotkeys`.
 * When you add a new chord in those files, update this content too.
 */
package se.soderbjorn.treefacts.main

import se.soderbjorn.lunula.web.hotkey.HotkeyEntry
import se.soderbjorn.lunula.web.hotkey.HotkeyGroup
import se.soderbjorn.lunula.web.hotkey.HotkeysModalSpec
import se.soderbjorn.lunula.web.hotkey.StandardHotkeys
import se.soderbjorn.lunula.web.hotkey.toChordLabel

/**
 * Build treefacts's cheatsheet spec. Pure — no DOM access, no state.
 * Called once at boot by [AppShell] and handed to the toolkit modal
 * via [ToolkitHotkeysModal.setContent].
 */
internal fun treefactsHotkeysSpec(): HotkeysModalSpec {
    val isMac: Boolean = run {
        val ua = js("(typeof navigator !== 'undefined' && navigator.userAgent) || ''") as String
        ua.contains("Mac") || ua.contains("iPhone") || ua.contains("iPad")
    }
    val cmd = if (isMac) "⌘" else "Win"
    val opt = if (isMac) "⌥" else "Alt"
    val shift = if (isMac) "⇧" else "Shift"

    return HotkeysModalSpec(
        groups = listOf(
            HotkeyGroup(
                title = "Outline navigation",
                entries = listOf(
                    HotkeyEntry(
                        label = "Zoom into bullet (or open linked page)",
                        chord = listOf(opt, cmd, "⏎"),
                        iconSvg = ICON_ZOOM_IN,
                    ),
                    HotkeyEntry(
                        label = "Zoom out one level",
                        chord = listOf(opt, cmd, "↑"),
                        iconSvg = ICON_ARROW_UP,
                    ),
                    HotkeyEntry(
                        label = "Clear zoom (back to root)",
                        chord = listOf("Esc"),
                        iconSvg = ICON_ESC,
                    ),
                    HotkeyEntry(
                        label = "Back through zoom history",
                        chord = listOf(opt, cmd, "←"),
                        iconSvg = ICON_ARROW_LEFT,
                    ),
                    HotkeyEntry(
                        label = "Forward through zoom history",
                        chord = listOf(opt, cmd, "→"),
                        iconSvg = ICON_ARROW_RIGHT,
                    ),
                ),
            ),
            HotkeyGroup(
                title = "Editor",
                entries = listOf(
                    HotkeyEntry(label = "Bold", chord = listOf(cmd, "B"), iconSvg = ICON_BOLD),
                    HotkeyEntry(label = "Italic", chord = listOf(cmd, "I"), iconSvg = ICON_ITALIC),
                    HotkeyEntry(label = "Indent bullet", chord = listOf("Tab"), iconSvg = ICON_INDENT),
                    HotkeyEntry(
                        label = "Outdent bullet",
                        chord = listOf(shift, "Tab"),
                        iconSvg = ICON_INDENT,
                    ),
                    HotkeyEntry(
                        label = "Leave block onto a new bullet",
                        chord = listOf(cmd, "⏎"),
                        iconSvg = ICON_ESC,
                    ),
                    HotkeyEntry(
                        label = "Select all (clamped to zoom)",
                        chord = listOf(cmd, "A"),
                        iconSvg = ICON_SELECT_ALL,
                    ),
                    HotkeyEntry(label = "Undo", chord = listOf(cmd, "Z"), iconSvg = ICON_UNDO),
                    HotkeyEntry(
                        label = "Redo",
                        chord = listOf(shift, cmd, "Z"),
                        iconSvg = ICON_REDO,
                    ),
                ),
            ),
            HotkeyGroup(
                title = "Panes & tabs",
                entries = listOf(
                    HotkeyEntry(
                        label = "Previous pane",
                        chord = StandardHotkeys.PreviousPane.toChordLabel(),
                        iconSvg = ICON_PANE,
                    ),
                    HotkeyEntry(
                        label = "Next pane",
                        chord = StandardHotkeys.NextPane.toChordLabel(),
                        iconSvg = ICON_PANE,
                    ),
                    HotkeyEntry(
                        label = "Previous tab",
                        chord = StandardHotkeys.PreviousTab.toChordLabel(),
                        iconSvg = ICON_TAB,
                    ),
                    HotkeyEntry(
                        label = "Next tab",
                        chord = StandardHotkeys.NextTab.toChordLabel(),
                        iconSvg = ICON_TAB,
                    ),
                ),
            ),
            HotkeyGroup(
                title = "App",
                entries = listOf(
                    HotkeyEntry(
                        label = "Open command palette",
                        chord = listOf(cmd, "P"),
                        iconSvg = ICON_PALETTE,
                    ),
                    HotkeyEntry(
                        label = "Navigate to file",
                        chord = listOf(cmd, "O"),
                        iconSvg = ICON_NAVIGATE,
                    ),
                    HotkeyEntry(
                        label = "Open Starred",
                        chord = listOf(cmd, "S"),
                        iconSvg = ICON_STARRED,
                    ),
                    HotkeyEntry(
                        label = "Show this hotkeys cheatsheet",
                        chord = listOf(cmd, "/"),
                        iconSvg = ICON_ZOOM_OUT,
                    ),
                ),
            ),
        ),
    )
}

// ── Inline icon SVGs (16x16, currentColor) ──────────────────────────
//
// Reused from the previous treefacts-local HotkeysModal. Kept as pure
// constants so this file stays platform-free and importable from any
// jsMain code that wants the same icon vocabulary.

private const val ICON_ZOOM_IN: String =
    "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\"><circle cx=\"11\" cy=\"11\" r=\"7\"/>" +
        "<line x1=\"11\" y1=\"8\" x2=\"11\" y2=\"14\"/>" +
        "<line x1=\"8\" y1=\"11\" x2=\"14\" y2=\"11\"/>" +
        "<line x1=\"20\" y1=\"20\" x2=\"16.5\" y2=\"16.5\"/></svg>"
private const val ICON_ZOOM_OUT: String =
    "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\"><circle cx=\"11\" cy=\"11\" r=\"7\"/>" +
        "<line x1=\"8\" y1=\"11\" x2=\"14\" y2=\"11\"/>" +
        "<line x1=\"20\" y1=\"20\" x2=\"16.5\" y2=\"16.5\"/></svg>"
private const val ICON_ARROW_LEFT: String =
    "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\"><line x1=\"19\" y1=\"12\" x2=\"5\" y2=\"12\"/>" +
        "<polyline points=\"12 19 5 12 12 5\"/></svg>"
private const val ICON_ARROW_RIGHT: String =
    "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\"><line x1=\"5\" y1=\"12\" x2=\"19\" y2=\"12\"/>" +
        "<polyline points=\"12 5 19 12 12 19\"/></svg>"
private const val ICON_ARROW_UP: String =
    "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\"><line x1=\"12\" y1=\"19\" x2=\"12\" y2=\"5\"/>" +
        "<polyline points=\"19 12 12 5 5 12\"/></svg>"
private const val ICON_ESC: String =
    "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\"><rect x=\"3\" y=\"7\" width=\"18\" height=\"10\" rx=\"2\"/>" +
        "<line x1=\"8\" y1=\"11\" x2=\"8\" y2=\"13\"/>" +
        "<line x1=\"12\" y1=\"11\" x2=\"12\" y2=\"13\"/>" +
        "<line x1=\"16\" y1=\"11\" x2=\"16\" y2=\"13\"/></svg>"
private const val ICON_BOLD: String =
    "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\"><path d=\"M7 5h6a3.5 3.5 0 0 1 0 7H7z\"/>" +
        "<path d=\"M7 12h7a3.5 3.5 0 0 1 0 7H7z\"/></svg>"
private const val ICON_ITALIC: String =
    "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\"><line x1=\"19\" y1=\"5\" x2=\"11\" y2=\"5\"/>" +
        "<line x1=\"13\" y1=\"19\" x2=\"5\" y2=\"19\"/>" +
        "<line x1=\"15\" y1=\"5\" x2=\"9\" y2=\"19\"/></svg>"
private const val ICON_INDENT: String =
    "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\"><line x1=\"3\" y1=\"6\" x2=\"21\" y2=\"6\"/>" +
        "<line x1=\"9\" y1=\"12\" x2=\"21\" y2=\"12\"/>" +
        "<line x1=\"9\" y1=\"18\" x2=\"21\" y2=\"18\"/>" +
        "<polyline points=\"3 9 6 12 3 15\"/></svg>"
private const val ICON_SELECT_ALL: String =
    "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\"><rect x=\"4\" y=\"4\" width=\"16\" height=\"16\" rx=\"2\" " +
        "stroke-dasharray=\"3 3\"/><rect x=\"8\" y=\"8\" width=\"8\" height=\"8\" rx=\"1\"/></svg>"
private const val ICON_UNDO: String =
    "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\"><polyline points=\"4 9 9 9 9 4\"/>" +
        "<path d=\"M4 9l4-4a8 8 0 1 1-2 13\"/></svg>"
private const val ICON_REDO: String =
    "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\"><polyline points=\"20 9 15 9 15 4\"/>" +
        "<path d=\"M20 9l-4-4a8 8 0 1 0 2 13\"/></svg>"
private const val ICON_PALETTE: String =
    "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\"><circle cx=\"12\" cy=\"12\" r=\"9\"/>" +
        "<circle cx=\"7.5\" cy=\"10.5\" r=\"1.2\" fill=\"currentColor\" stroke=\"none\"/>" +
        "<circle cx=\"12\" cy=\"7\" r=\"1.2\" fill=\"currentColor\" stroke=\"none\"/>" +
        "<circle cx=\"16.5\" cy=\"10.5\" r=\"1.2\" fill=\"currentColor\" stroke=\"none\"/>" +
        "<circle cx=\"15\" cy=\"15\" r=\"1.2\" fill=\"currentColor\" stroke=\"none\"/></svg>"
private const val ICON_PANE: String =
    "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\"><rect x=\"3\" y=\"4\" width=\"8\" height=\"16\" rx=\"1\"/>" +
        "<rect x=\"13\" y=\"4\" width=\"8\" height=\"16\" rx=\"1\"/></svg>"
private const val ICON_TAB: String =
    "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.6\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\"><path d=\"M3 8h6l2-3h10v14H3z\"/></svg>"
private const val ICON_STARRED: String =
    "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\"><polygon points=\"12 2 15 9 22 9.5 17 14.5 " +
        "18.5 21.5 12 18 5.5 21.5 7 14.5 2 9.5 9 9\"/></svg>"
private const val ICON_NAVIGATE: String =
    "<svg viewBox=\"0 0 24 24\" width=\"16\" height=\"16\" fill=\"none\" " +
        "stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" " +
        "stroke-linejoin=\"round\"><circle cx=\"11\" cy=\"11\" r=\"6\"/>" +
        "<line x1=\"15.5\" y1=\"15.5\" x2=\"20\" y2=\"20\"/></svg>"
