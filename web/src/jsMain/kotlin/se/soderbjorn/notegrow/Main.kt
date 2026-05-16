package se.soderbjorn.notegrow

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import se.soderbjorn.darkness.web.setDtMacFullscreenBodyClass
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
    window.onload = {
        tagBodyForElectronMac()
        wireMacFullscreenBodyClass()
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

/**
 * Adds the toolkit's `dt-electron-mac` body class when the renderer is
 * an Electron BrowserWindow on macOS. The companion `dt-custom-titlebar`
 * class is no longer set here — it's driven by the toolkit's
 * `AppShellMount` subscriber off the persisted
 * `ThemeSnapshot.useCustomTitleBar` value, so it tracks the Settings
 * sidebar toggle (and the corresponding `electron-chrome.json` cache the
 * main process keeps for boot-time `titleBarStyle`).
 *
 * `dt-electron-mac` gates the toolkit's 80 px traffic-light reservation
 * rule on `.dt-topbar`, which only fires when both classes are present
 * — non-mac Electron puts window controls on the right and needs no
 * padding.
 */
private fun tagBodyForElectronMac() {
    val ua = window.navigator.userAgent
    val isElectron = ua.contains("Electron", ignoreCase = true)
    val isMac = ua.contains("Mac OS X", ignoreCase = true)
    if (isElectron && isMac) {
        document.body?.classList?.add("dt-electron-mac")
    }
}

/**
 * Subscribes to macOS native fullscreen state changes from the Electron
 * main process and toggles the toolkit's `dt-mac-fullscreen` body class
 * accordingly.
 *
 * macOS hides the traffic-light cluster entirely while a window is in
 * native fullscreen, so the toolkit's 80 px traffic-light reservation
 * on `.dt-topbar` (gated on `dt-electron-mac` + `dt-custom-titlebar`,
 * both set by [tagBodyForElectronMac]) would render as dead whitespace
 * with the topbar's leading content visibly off-center. The toolkit
 * suppresses the rule for the duration of the fullscreen state via
 * `:not(.dt-mac-fullscreen)`.
 *
 * The `noteApi.onFullscreenChange` bridge fires once at boot reflecting
 * the window's initial state (macOS may relaunch directly into a
 * restored fullscreen Space) and then on every `enter-full-screen` /
 * `leave-full-screen` BrowserWindow event. No-op in plain browsers
 * (no `noteApi`) and on non-mac Electron (`isFullScreen` still works
 * but the toolkit's reservation rule is gated on `dt-electron-mac` so
 * the class has no visible effect).
 */
private fun wireMacFullscreenBodyClass() {
    val noteApi = window.asDynamic().noteApi
    if (noteApi?.onFullscreenChange == null) return
    noteApi.onFullscreenChange({ enabled: Boolean ->
        setDtMacFullscreenBodyClass(enabled)
    })
}
