package se.soderbjorn.notegrow

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import se.soderbjorn.notegrow.di.createJsAppGraph
import se.soderbjorn.notegrow.main.AppShell

/**
 * Web entry point. Builds the DI graph and mounts the [AppShell] into the
 * `<div id="app">` host element.
 *
 * The shell wraps the existing note editor inside the toolkit's windowing
 * system and applies the active darkness theme to `document.documentElement`
 * before any content is painted.
 */
fun main() {
    window.onload = {
        tagBodyForElectronMac()
        val app = document.getElementById("app") as HTMLElement
        val graph = createJsAppGraph()
        AppShell(
            scope = graph.coroutineScope,
            documentRegistry = graph.documentRegistry,
        ).render(app)
    }
}

/**
 * Adds the toolkit's `dt-electron-mac` opt-in class to `<body>` when the
 * renderer is an Electron BrowserWindow on macOS.
 *
 * Notegrow's Electron main process opens windows with
 * `titleBarStyle: "hiddenInset"` so the themed titlebar bleeds through;
 * the OS traffic-light buttons still float over the upper-left corner.
 * The toolkit's stylesheet reserves ~80 px in the top bar's leading slot
 * when this class is present, so the first interactive item never sits
 * under a traffic-light. Non-mac Electron puts window controls on the
 * right and needs no padding — the gate is intentional.
 */
private fun tagBodyForElectronMac() {
    val ua = window.navigator.userAgent
    val isElectron = ua.contains("Electron", ignoreCase = true)
    val isMac = ua.contains("Mac OS X", ignoreCase = true)
    if (isElectron && isMac) {
        document.body?.classList?.add("dt-electron-mac")
    }
}
