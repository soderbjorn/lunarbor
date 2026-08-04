package se.soderbjorn.treefacts

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import se.soderbjorn.treefacts.di.createJsAppGraph
import se.soderbjorn.treefacts.main.AppShell
import se.soderbjorn.treefacts.main.setTreeFactsVaultRoot

/**
 * Web entry point. Builds the DI graph and mounts the [AppShell] into the
 * `<div id="app">` host element via the toolkit's `mountAppShell`
 * assembler.
 *
 * TreeFacts consumes the toolkit's chrome end-to-end: the top bar, tab
 * strip, kebab menu, left sidebar's tabs→panes tree, layout renderer,
 * theme manager sidebar, and bottom bar all come from the toolkit.
 * The treefacts side contributes only the per-pane note editor
 * (rendered via [AppShell.renderPaneContent]), the typed
 * `LayoutState` source ([TreeFactsTabSource]), and a handful of
 * treefacts-specific topbar buttons + keyboard shortcuts; persistence
 * routes through the toolkit's `Persister` (Electron-IPC when present,
 * `localStorage` otherwise) provided by [createJsAppGraph].
 */
fun main() {
    // Body-class wiring (dt-electron-mac + dt-mac-fullscreen) used to live
    // here, but lunula's `injectLunulaStyles` — called
    // from `mountAppShell`, which `AppShell.render` ultimately invokes —
    // now does both pieces itself via `autoApplyElectronMacBodyClass` and
    // `autoWireMacFullscreenBodyClass`. The latter subscribes to
    // `globalThis.darknessApi.onFullscreenChange`, which treefacts's preload
    // already exposes.
    window.onload = {
        val app = document.getElementById("app") as HTMLElement
        val graph = createJsAppGraph()
        setTreeFactsVaultRoot(graph.documentRegistry.rootDirectory)
        AppShell(
            scope = graph.coroutineScope,
            documentRegistry = graph.documentRegistry,
            persister = graph.persister,
        ).render(app)
    }
}
