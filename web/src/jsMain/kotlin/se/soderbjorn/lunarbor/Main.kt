package se.soderbjorn.lunarbor

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import se.soderbjorn.lunarbor.demo.isDemoMode
import se.soderbjorn.lunarbor.demo.loadDemoVault
import se.soderbjorn.lunarbor.di.createJsAppGraph
import se.soderbjorn.lunarbor.main.AppShell
import se.soderbjorn.lunarbor.main.installBackupScheduler
import se.soderbjorn.lunarbor.main.installMcpBridge
import se.soderbjorn.lunarbor.mcp.McpServer
import se.soderbjorn.lunarbor.mcp.McpTools
import se.soderbjorn.lunarbor.main.setLunarborVaultRoot

/**
 * Web entry point. Builds the DI graph and mounts the [AppShell] into the
 * `<div id="app">` host element via the toolkit's `mountAppShell`
 * assembler.
 *
 * Lunarbor consumes the toolkit's chrome end-to-end: the top bar, tab
 * strip, kebab menu, left sidebar's tabs→panes tree, layout renderer,
 * theme manager sidebar, and bottom bar all come from the toolkit.
 * The lunarbor side contributes only the per-pane note editor
 * (rendered via [AppShell.renderPaneContent]), the typed
 * `LayoutState` source ([LunarborTabSource]), and a handful of
 * lunarbor-specific topbar buttons + keyboard shortcuts; persistence
 * routes through the toolkit's `Persister` (Electron-IPC when present,
 * `localStorage` otherwise) provided by [createJsAppGraph].
 *
 * In a plain browser (the website's demo, see `demo/DemoMode.kt`) the
 * bundled demo vault is loaded into memory first, and the graph is built
 * over it.
 */
@OptIn(DelicateCoroutinesApi::class)
fun main() {
    // Body-class wiring (dt-electron-mac + dt-mac-fullscreen) used to live
    // here, but lunula's `injectLunulaStyles` — called
    // from `mountAppShell`, which `AppShell.render` ultimately invokes —
    // now does both pieces itself via `autoApplyElectronMacBodyClass` and
    // `autoWireMacFullscreenBodyClass`. The latter subscribes to
    // `globalThis.darknessApi.onFullscreenChange`, which lunarbor's preload
    // already exposes.
    window.onload = {
        GlobalScope.launch {
            if (isDemoMode()) loadDemoVault()
            start()
        }
    }
}

/**
 * Builds the DI graph, converts a vault from before `_node.md`, and mounts
 * the app. Called by [main] once the page has loaded (and, in demo mode,
 * once the demo vault is in memory).
 */
private suspend fun start() {
    val app = document.getElementById("app") as HTMLElement
    val graph = createJsAppGraph()
    setLunarborVaultRoot(graph.documentRegistry.rootDirectory)
    // A vault from before `_node.md` is converted before anything reads it.
    graph.documentRegistry.migrateLegacyOutlines()
    val shell = AppShell(
        scope = graph.coroutineScope,
        documentRegistry = graph.documentRegistry,
        persister = graph.persister,
    )
    shell.render(app)
    // Agent access (MCP): answered here, against the live registry and
    // this shell's windows. Not in the DI graph — it needs the shell.
    installMcpBridge(
        McpServer(McpTools(graph.documentRegistry, shell.agentWorkspace())),
        graph.coroutineScope,
    )
    // Automatic backups (App settings → Backup): the main process says
    // when one is due; edits are saved first, then the vault is zipped.
    installBackupScheduler(graph.coroutineScope) { graph.documentRegistry.flushAll() }
}
