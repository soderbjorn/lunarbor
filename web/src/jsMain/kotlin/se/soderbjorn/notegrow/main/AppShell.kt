/*
 * AppShell.kt (jsMain)
 * --------------------
 * Top-level shell for the notegrow web app, built on darkness-toolkit.
 *
 * The shell does just two things:
 *
 *   1. Applies theme CSS variables to `document.documentElement` at boot,
 *      so the rest of the UI can paint with `var(--t-…)` references.
 *   2. Mounts the toolkit's `LayoutRenderer` into the host element with a
 *      single-leaf `PaneTree`. The leaf's content is the existing
 *      `MainScreen` editor — i.e. notegrow's note editor lives inside one
 *      of the toolkit's windows.
 *
 * Per the project's UI direction, notegrow has **no shell chrome** — no
 * top bar, no left/right sidebars, no theme editor. The shell is just the
 * windowing system. Splitting/closing windows currently isn't wired into
 * the UI; the `PaneTree` model supports it, so future work can add a
 * keyboard shortcut or context menu without revisiting the boot path.
 *
 * Theme persistence is loaded from a global `window.__darknessSettings`
 * JSON string which Electron's preload script (or any future IPC bridge)
 * can populate. If absent, `UiSettings.defaults()` is used. There is no
 * direct filesystem access here — the renderer is browser-side and can't
 * read or write disk; the host process owns that.
 *
 * commonMain rules: this file is jsMain only (touches the DOM).
 */

package se.soderbjorn.notegrow.main

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import org.w3c.dom.HTMLElement
import se.soderbjorn.darkness.core.Appearance
import se.soderbjorn.darkness.core.UiSettings
import se.soderbjorn.darkness.core.resolve
import se.soderbjorn.darkness.web.applyColorScheme
import se.soderbjorn.darkness.web.applyCssVars
import se.soderbjorn.darkness.web.isDarkActive
import se.soderbjorn.darkness.web.toCssAliasMap
import se.soderbjorn.darkness.web.toCssVarMap
import se.soderbjorn.darkness.web.layout.LayoutRenderer
import se.soderbjorn.darkness.web.layout.PaneCallbacks
import se.soderbjorn.darkness.web.layout.PaneNode
import se.soderbjorn.darkness.web.layout.PaneTree

/**
 * Top-level shell that wires the toolkit windowing system around the
 * notegrow editor.
 *
 * ### Callers
 * - Instantiated in `Main.kt` (web entry point) once per app startup.
 *   Exactly one instance.
 *
 * @param viewModel the platform view-model passed through to the embedded
 *   [MainScreen] inside the editor pane.
 * @param scope     coroutine scope shared with the embedded [MainScreen]
 *   for its paint loop.
 */
class AppShell(
    private val viewModel: MainViewModel,
    private val scope: CoroutineScope,
) {
    /** Stable id of the single editor pane. */
    private val editorPaneId = "editor"

    private var editorScreen: MainScreen? = null
    private var editorContent: HTMLElement? = null
    private var paneTree: PaneTree = PaneTree(
        root = PaneNode.Leaf(id = editorPaneId, title = "Notes"),
    )
    private var renderer: LayoutRenderer? = null

    /**
     * Boots the shell into [root]:
     *
     * 1. Loads [UiSettings] from `window.__darknessSettings` if available
     *    (a JSON string); otherwise uses [UiSettings.defaults].
     * 2. Resolves the active palette and applies its CSS variables to
     *    `document.documentElement` so subsequent renders pick up the
     *    theme.
     * 3. Builds the [LayoutRenderer] and mounts it into [root].
     * 4. Inside the editor pane's content slot, mounts the existing
     *    [MainScreen] note editor via [MainScreen.render].
     *
     * Safe to call once at startup.
     *
     * @param root the host element to mount the shell into (typically
     *   `<div id="app">`).
     */
    fun render(root: HTMLElement) {
        applyTheme()
        // Make the host element fill the viewport so the layout has space
        // to expand into. The host stylesheet can override these if it
        // already sizes #app.
        root.style.display = "flex"
        root.style.flexDirection = "column"
        root.style.height = "100vh"
        root.style.margin = "0"
        root.style.background = "var(--t-surface-base, #1e1e1e)"
        root.style.color = "var(--t-text-primary, #e6e6e6)"

        val callbacks = PaneCallbacks(
            contentRenderer = { id ->
                if (id == editorPaneId) renderEditorContent()
                else renderEmptyPlaceholder(id)
            },
            onClose = { /* Single window for now — closing is not wired. */ },
            onResize = { _, _ -> /* No splits yet. */ },
        )
        renderer = LayoutRenderer(root, callbacks).also { it.render(paneTree) }
    }

    /**
     * Resolves [UiSettings] from the global injection point if present, or
     * falls back to defaults. Then walks the [ResolvedPalette] and writes
     * its CSS-var map onto `document.documentElement`.
     *
     * Apps that need to re-apply the theme on settings change (e.g. a
     * future settings menu) should call [applyTheme] again with the new
     * settings.
     */
    private fun applyTheme(settings: UiSettings = loadInitialSettings()) {
        val isDark = when (settings.appearance) {
            Appearance.Dark -> true
            Appearance.Light -> false
            Appearance.Auto -> isDarkActive(Appearance.Auto)
        }
        val palette = settings.theme.resolve(isDark)
        val docEl = document.documentElement as? HTMLElement ?: return
        applyCssVars(docEl, palette.toCssVarMap())
        applyCssVars(docEl, palette.toCssAliasMap())
        applyColorScheme(docEl, isDark)
    }

    /**
     * Reads `window.__darknessSettings` (a JSON string) if present and
     * parses it via [UiSettings.fromJsonString]. Returns [UiSettings.defaults]
     * if the global is absent or malformed.
     *
     * Electron preload scripts and host bridges populate this global to
     * supply persisted settings without requiring direct filesystem access
     * from the renderer.
     */
    private fun loadInitialSettings(): UiSettings {
        val raw = js("globalThis.__darknessSettings || null") as? String
            ?: return UiSettings.defaults()
        return UiSettings.fromJsonString(raw)
    }

    /**
     * Builds the editor pane's content element on demand. Reuses a single
     * [MainScreen] instance — the layout renderer wipes and rebuilds the
     * DOM on each render, so we hand it a fresh content container each
     * time but keep our [MainScreen] reference for state continuity.
     */
    private fun renderEditorContent(): HTMLElement {
        val container = document.createElement("div") as HTMLElement
        container.style.width = "100%"
        container.style.height = "100%"
        container.style.background = "var(--t-terminal-bg, #1e1e1e)"
        container.style.color = "var(--t-terminal-fg, #e6e6e6)"
        container.style.setProperty("overflow", "hidden")
        editorContent = container
        if (editorScreen == null) {
            editorScreen = MainScreen(viewModel, scope)
        }
        editorScreen!!.render(container)
        return container
    }

    /**
     * Builds a placeholder element shown inside any pane that isn't the
     * editor (currently unused — the boot tree has only the editor pane,
     * but if a future split adds extra panes, they get this until the host
     * provides real content for them).
     *
     * @param id the pane id (just for diagnostic display)
     */
    private fun renderEmptyPlaceholder(id: String): HTMLElement {
        val placeholder = document.createElement("div") as HTMLElement
        placeholder.style.display = "flex"
        placeholder.style.alignItems = "center"
        placeholder.style.justifyContent = "center"
        placeholder.style.width = "100%"
        placeholder.style.height = "100%"
        placeholder.style.color = "var(--t-text-tertiary, #888)"
        placeholder.style.fontSize = "13px"
        placeholder.textContent = "[ empty pane: $id ]"
        return placeholder
    }
}
