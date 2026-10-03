/*
 * BundledFonts.kt (jsMain)
 *
 * The font faces Lunarbor ships beyond the toolkit's own presets: Instrument
 * Sans (outline text and chrome) and Unbounded (headings only), offered in
 * App settings → Appearance through Lunula's `registerFontPresets`. The files
 * live in `web/fonts/` and reach the page as `bundled-fonts.css` (data: URIs,
 * built by `:web:generateBundledFonts`, linked from index.html), which also
 * carries JetBrains Mono — the toolkit already lists that preset as bundled,
 * so it only needed the file.
 *
 * View-layer glue only: no state, called once from Main.kt before the shell
 * renders, so the Settings sidebar and the persisted keys resolve from the start.
 */
package se.soderbjorn.lunarbor.main

import se.soderbjorn.lunula.web.themeeditor.FontKind
import se.soderbjorn.lunula.web.themeeditor.FontPreset
import se.soderbjorn.lunula.web.themeeditor.registerFontPresets

/**
 * Registers Lunarbor's bundled font faces as font-picker presets.
 *
 * - Instrument Sans is [FontKind.Proportional]: offered in the Proportional,
 *   Sidebar, Tab bar and Window title rows (and Display).
 * - Unbounded is [FontKind.Display]: offered in the Display font row only,
 *   since it is too wide for body text.
 *
 * Both are marked bundled (no installed-fonts probe), since `bundled-fonts.css`
 * always declares them. Called by `start()` in Main.kt.
 */
fun registerLunarborFonts() {
    registerFontPresets(listOf(
        FontPreset(
            key = "instrumentSans",
            displayName = "Instrument Sans",
            cssStack = "'Instrument Sans', system-ui, sans-serif",
            detectFamily = null,
            bundled = true,
            kind = FontKind.Proportional,
        ),
        FontPreset(
            key = "unbounded",
            displayName = "Unbounded",
            cssStack = "'Unbounded', system-ui, sans-serif",
            detectFamily = null,
            bundled = true,
            kind = FontKind.Display,
        ),
    ))
}
