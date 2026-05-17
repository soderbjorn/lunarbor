package se.soderbjorn.notegrow

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import se.soderbjorn.notegrow.di.createJsAppGraph
import se.soderbjorn.notegrow.main.AppShell
import se.soderbjorn.notegrow.main.setNotegrowVaultRoot

/**
 * Web entry point. Builds the DI graph and mounts the [AppShell] into the
 * `<div id="app">` host element via the toolkit's `mountAppShell`
 * assembler.
 *
 * Notegrow consumes the toolkit's chrome end-to-end: the top bar, tab
 * strip, kebab menu, left sidebar's tabs→panes tree, layout renderer,
 * theme manager sidebar, and bottom bar all come from the toolkit.
 * The notegrow side contributes only the per-pane note editor
 * (rendered via [AppShell.renderPaneContent]), the typed
 * `LayoutState` source ([NotegrowTabSource]), and a handful of
 * notegrow-specific topbar buttons + keyboard shortcuts; persistence
 * routes through the toolkit's `Persister` (Electron-IPC when present,
 * `localStorage` otherwise) provided by [createJsAppGraph].
 */
fun main() {
    // Body-class wiring (dt-electron-mac + dt-mac-fullscreen) used to live
    // here, but darkness-toolkit's `injectDarknessToolkitStyles` — called
    // from `mountAppShell`, which `AppShell.render` ultimately invokes —
    // now does both pieces itself via `autoApplyElectronMacBodyClass` and
    // `autoWireMacFullscreenBodyClass`. The latter subscribes to
    // `globalThis.darknessApi.onFullscreenChange`, which notegrow's preload
    // already exposes.
    window.onload = {
        val app = document.getElementById("app") as HTMLElement
        val graph = createJsAppGraph()
        setNotegrowVaultRoot(graph.documentRegistry.rootDirectory)
        AppShell(
            scope = graph.coroutineScope,
            documentRegistry = graph.documentRegistry,
            persister = graph.persister,
        ).render(app)
    }
}
