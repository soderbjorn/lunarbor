/*
 * AppPanes.kt (jsMain)
 * --------------------
 * Notegrow's pane → universal-section map. The toolkit's theme model is
 * defined in terms of a small fixed set of universal sections (see
 * [se.soderbjorn.darkness.core.Sections]); each Darkness app declares how
 * its concrete pane names inherit from those sections when the active
 * theme has no per-pane override.
 *
 * This file is the single source of truth for that mapping in notegrow.
 * It's consumed by [AppShell] when it builds the [DefaultThemeManagerHost]
 * and when it calls the toolkit resolver (`resolveActiveUiSettings`) on
 * every theme-state mutation.
 *
 * Adding a new concrete pane in notegrow means adding a row here. Picking
 * the right section determines the colour the new pane inherits from any
 * cross-app theme — see the `Sections` table in the toolkit for the
 * intended visual role of each section.
 *
 * commonMain rules: this file is jsMain only by convention (the Web app
 * is the only consumer today), but it has no DOM imports and could move
 * to commonMain unchanged if other platforms need the same mapping.
 */

package se.soderbjorn.notegrow.main

import se.soderbjorn.darkness.core.Sections

/**
 * Map from notegrow's concrete pane names to the universal section each
 * inherits from when the active theme has no per-pane override.
 *
 * The keys are the pane identifiers notegrow uses across [AppShell] and
 * its CSS (e.g. `"editor"`, `"sidebar"`, `"starred"`, `"outline"`); the
 * values are constants from [Sections]. The toolkit's resolver
 * ([se.soderbjorn.darkness.web.themeeditor.resolveActiveUiSettings]) reads
 * this map to decide which section's colour scheme to apply to each pane.
 *
 * Per-pane overrides on a [se.soderbjorn.darkness.core.Theme] take
 * precedence over the inherited section assignment; panes the current app
 * doesn't render are silently ignored.
 *
 * ### Callers
 * - [AppShell.themeHost] — wired through `DefaultThemeManagerHost` so the
 *   Theme Manager UI can present per-pane overrides for notegrow's panes.
 * - [AppShell.onThemeStateChange] / [AppShell.cycleAppearance] — passed to
 *   `resolveActiveUiSettings` so the painter sees fully-resolved schemes.
 */
val notegrowPanes: Map<String, String> = mapOf(
    "editor" to Sections.Main,
    "sidebar" to Sections.Sidebar,
    "tabs" to Sections.Tabs,
    "chrome" to Sections.Chrome,
    "active" to Sections.Active,
    "windows" to Sections.Windows,
    "starred" to Sections.Auxiliary,
    "outline" to Sections.Auxiliary,
    "bottomBar" to Sections.BottomBar,
)
