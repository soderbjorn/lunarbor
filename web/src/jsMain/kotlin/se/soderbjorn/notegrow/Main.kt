package se.soderbjorn.notegrow

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import se.soderbjorn.darkness.web.setDtMacFullscreenBodyClass
import se.soderbjorn.notegrow.di.createJsAppGraph
import se.soderbjorn.notegrow.main.AppShell

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
        AppShell(
            scope = graph.coroutineScope,
            documentRegistry = graph.documentRegistry,
            persister = graph.persister,
        ).render(app)
    }
}

/**
 * Adds the toolkit's `dt-electron-mac` and `dt-custom-titlebar` opt-in
 * classes to `<body>` when the renderer is an Electron BrowserWindow on
 * macOS.
 *
 * Notegrow's Electron main process always opens windows with
 * `titleBarStyle: "hiddenInset"` so the themed titlebar bleeds through;
 * the OS traffic-light buttons still float over the upper-left corner.
 * The toolkit's stylesheet pads `.dt-topbar` left by ~80 px when both
 * `dt-electron-mac` and `dt-custom-titlebar` are present on `<body>`,
 * so the first interactive item never sits under a traffic-light.
 * Notegrow has no runtime toggle — `hiddenInset` is permanent — so we
 * set both classes unconditionally here. Non-mac Electron puts window
 * controls on the right and needs no padding; the `dt-electron-mac`
 * gate keeps the rule from firing there.
 */
private fun tagBodyForElectronMac() {
    val ua = window.navigator.userAgent
    val isElectron = ua.contains("Electron", ignoreCase = true)
    val isMac = ua.contains("Mac OS X", ignoreCase = true)
    if (isElectron && isMac) {
        document.body?.classList?.add("dt-electron-mac")
        document.body?.classList?.add("dt-custom-titlebar")
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
